package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.websocket.BinaryMessageReceivedAction;
import burp.api.montoya.proxy.websocket.BinaryMessageToBeSentAction;
import burp.api.montoya.proxy.websocket.InterceptedBinaryMessage;
import burp.api.montoya.proxy.websocket.InterceptedTextMessage;
import burp.api.montoya.proxy.websocket.ProxyMessageHandler;
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreation;
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreationHandler;
import burp.api.montoya.proxy.websocket.TextMessageReceivedAction;
import burp.api.montoya.proxy.websocket.TextMessageToBeSentAction;
import burp.api.montoya.websocket.Direction;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ParamExtractor;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Captures WebSocket text & binary frames for allowed hosts via Montoya
 * {@link ProxyWebSocketCreationHandler} without blocking the WebSocket proxy pipeline.
 * Records {@code WS_SEND} / {@code WS_RECV} events under the active {@link ActionGroup}
 * with bounded 64 KB payloads and JSON/GraphQL-WS parameter extraction.
 */
public final class WebSocketCaptureHandler implements ProxyWebSocketCreationHandler {

    private static final int MAX_WS_PAYLOAD_BYTES = 64 * 1024;

    private final CrawlConfig config;
    private final CrawlState state;
    private final Supplier<Boolean> passiveSupplier;
    private volatile ExcelSpecMatcher matcher;
    private final Runnable onUiUpdated;
    private final Consumer<String> logger;
    private final ExecutorService wsExecutor;

    public WebSocketCaptureHandler(
        CrawlConfig config,
        CrawlState state,
        Supplier<Boolean> passiveSupplier,
        ExcelSpecMatcher matcher,
        Runnable onUiUpdated,
        Consumer<String> logger
    ) {
        this.config = config;
        this.state = state;
        this.passiveSupplier = passiveSupplier;
        this.matcher = matcher;
        this.onUiUpdated = onUiUpdated;
        this.logger = logger;
        this.wsExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "MythCrawler-WsCapture");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.matcher = matcher;
    }

    public void shutdown() {
        wsExecutor.shutdownNow();
    }

    @Override
    public void handleWebSocketCreation(ProxyWebSocketCreation creation) {
        if (creation == null || creation.upgradeRequest() == null) {
            return;
        }
        HttpRequest upgradeReq = creation.upgradeRequest();
        HttpService svc = upgradeReq.httpService();
        if (svc == null || !config.isHostAllowed(svc.host())) {
            return;
        }

        final String host = svc.host();
        final int port = svc.port();
        final boolean https = svc.secure();
        final String pathWithQuery = upgradeReq.path() != null ? upgradeReq.path() : "/";
        final String fullUrl = (https ? "wss://" : "ws://") + host
            + ((port > 0 && !((!https && port == 80) || (https && port == 443))) ? (":" + port) : "")
            + pathWithQuery;
        final String referer = upgradeReq.headerValue("Referer");

        creation.proxyWebSocket().registerProxyMessageHandler(new ProxyMessageHandler() {
            @Override
            public TextMessageReceivedAction handleTextMessageReceived(InterceptedTextMessage msg) {
                if (shouldCaptureNow()) {
                    String payload = msg.payload() != null ? msg.payload() : "";
                    byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
                    byte[] bounded = CapturedApiEntry.capBytes(raw, MAX_WS_PAYLOAD_BYTES);
                    enqueueFrame(
                        msg.direction(), true, host, port, https,
                        pathWithQuery, fullUrl, referer, bounded, raw.length);
                }
                return TextMessageReceivedAction.continueWith(msg);
            }

            @Override
            public TextMessageToBeSentAction handleTextMessageToBeSent(InterceptedTextMessage msg) {
                return TextMessageToBeSentAction.continueWith(msg);
            }

            @Override
            public BinaryMessageReceivedAction handleBinaryMessageReceived(InterceptedBinaryMessage msg) {
                if (shouldCaptureNow()) {
                    int len = msg.payload() != null ? msg.payload().length() : 0;
                    byte[] bounded = msg.payload() != null
                        ? (len <= MAX_WS_PAYLOAD_BYTES
                            ? msg.payload().getBytes()
                            : msg.payload().subArray(0, MAX_WS_PAYLOAD_BYTES).getBytes())
                        : new byte[0];
                    enqueueFrame(
                        msg.direction(), false, host, port, https,
                        pathWithQuery, fullUrl, referer, bounded, len);
                }
                return BinaryMessageReceivedAction.continueWith(msg);
            }

            @Override
            public BinaryMessageToBeSentAction handleBinaryMessageToBeSent(InterceptedBinaryMessage msg) {
                return BinaryMessageToBeSentAction.continueWith(msg);
            }
        });
    }

    private boolean shouldCaptureNow() {
        if (state.getStatus() == CrawlState.Status.RUNNING) {
            return true;
        }
        return passiveSupplier != null && Boolean.TRUE.equals(passiveSupplier.get());
    }

    private void enqueueFrame(
        Direction direction,
        boolean isText,
        String host,
        int port,
        boolean https,
        String pathWithQuery,
        String fullUrl,
        String referer,
        byte[] payloadBytes,
        int fullLength
    ) {
        final long captureGen = state.getResetGeneration();
        final long ts = System.currentTimeMillis();
        final String methodTag = (direction == Direction.SERVER_TO_CLIENT) ? "WS_RECV" : "WS_SEND";
        final CrawlState.ActionContextSnapshot ctx = state.resolveContextForReferer(referer);

        wsExecutor.execute(() -> {
            if (state.getResetGeneration() != captureGen) {
                return;
            }
            recordFrameWorker(
                methodTag, isText, host, port, https, pathWithQuery, fullUrl,
                referer, payloadBytes, fullLength, ctx, ts);
        });
    }

    private void recordFrameWorker(
        String methodTag,
        boolean isText,
        String host,
        int port,
        boolean https,
        String pathWithQuery,
        String fullUrl,
        String referer,
        byte[] payloadBytes,
        int fullLength,
        CrawlState.ActionContextSnapshot ctx,
        long ts
    ) {
        String actionPath = (ctx != null && ctx.actionPath != null)
            ? ctx.actionPath : state.getCurrentActionPath();
        ActionGroup group = (ctx != null) ? ctx.group : null;
        if (group == null) {
            group = state.getActionGroups().openGroup(
                actionPath, "websocket", "", referer != null ? referer : state.getCurrentPageUrl());
        }

        Set<String> params = Collections.emptySet();
        String gqlOp = "";
        if (isText && payloadBytes.length > 0) {
            String trimmed = new String(payloadBytes, StandardCharsets.UTF_8).trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                ParamExtractor.extractJsonKeys(trimmed, "ws", keys);
                params = Collections.unmodifiableSet(keys);
                gqlOp = ParamExtractor.extractGraphqlOperation("application/json", payloadBytes);
            }
        }

        // Synthesize lightweight HTTP-viewable request/response buffers for Montoya Inspector
        String headerLead = methodTag + " " + pathWithQuery + " HTTP/1.1\r\n"
            + "Host: " + host + "\r\n"
            + "X-WebSocket-Frame: " + (isText ? "TEXT" : "BINARY") + " (" + fullLength + " bytes)\r\n\r\n";
        byte[] hdrBytes = headerLead.getBytes(StandardCharsets.UTF_8);
        byte[] reqPreview = new byte[hdrBytes.length + payloadBytes.length];
        System.arraycopy(hdrBytes, 0, reqPreview, 0, hdrBytes.length);
        System.arraycopy(payloadBytes, 0, reqPreview, hdrBytes.length, payloadBytes.length);

        String respText = "HTTP/1.1 101 Switching Protocols\r\nContent-Type: "
            + (isText ? "application/json; charset=utf-8" : "application/octet-stream")
            + "\r\nContent-Length: " + payloadBytes.length + "\r\n\r\n";
        byte[] respHdr = respText.getBytes(StandardCharsets.UTF_8);
        byte[] respPreview = new byte[respHdr.length + payloadBytes.length];
        System.arraycopy(respHdr, 0, respPreview, 0, respHdr.length);
        System.arraycopy(payloadBytes, 0, respPreview, respHdr.length, payloadBytes.length);

        ExcelSpecMatcher curMatcher = this.matcher;
        boolean excelLoaded = curMatcher != null && curMatcher.isLoaded();
        String normPath = ExcelSpecMatcher.normalizeSpecPath(pathWithQuery, true);
        String endpointKey = gqlOp.isEmpty()
            ? (methodTag + " " + normPath)
            : (methodTag + " " + normPath + " [op:" + gqlOp + "]");

        CapturedApiEntry cap = new CapturedApiEntry(
            "-", methodTag, fullUrl, pathWithQuery, host, port, https,
            actionPath, reqPreview, respPreview, 101, fullLength, params, ts,
            null, HttpMode.HTTP_1, CapturedApiEntry.sha256Hex(payloadBytes));

        state.getActionGroups().recordCall(
            group, cap, endpointKey, gqlOp, "", "", excelLoaded, false,
            isText ? "WEBSOCKET_TEXT" : "WEBSOCKET_BIN");

        if (onUiUpdated != null) {
            onUiUpdated.run();
        }
    }
}
