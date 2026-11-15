package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts Proxy requests:
 * 1) Redirects same-origin virtual /__myth_crawl/* calls cleanly as HTTP/1.1 requests to the loopback
 *    LocalControlServer (works identically for Chrome HTTP/2, Burp Chromium, and Firefox HTTP/1.1/HTTP/2).
 * 2) Forces uncompressed 200 OK HTML documents (Accept-Encoding: identity, no 304 cache validators)
 *    for allowed hosts while Armed/Running so gzip/br/zstd/304 never prevent agent injection.
 * 3) Captures the active UI action path keyed by Montoya messageId() at request initiation time.
 */
public final class ControlPlaneHandler implements ProxyRequestHandler {

    private final CrawlConfig config;
    private final CrawlState state;
    private final int localControlPort;
    private final Set<Integer> trackedInFlightIds = ConcurrentHashMap.newKeySet();
    private volatile boolean agentArmed = true;

    public ControlPlaneHandler(CrawlConfig config, CrawlState state, int localControlPort) {
        this.config = config;
        this.state = state;
        this.localControlPort = localControlPort;
    }

    public void setAgentArmed(boolean agentArmed) {
        this.agentArmed = agentArmed;
    }

    public boolean consumeTrackedInFlight(int messageId) {
        return trackedInFlightIds.remove(messageId);
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest interceptedRequest) {
        String path = interceptedRequest.path();
        if (path != null && path.startsWith("/__myth_crawl/")) {
            HttpService localService = HttpService.httpService("127.0.0.1", localControlPort, false);
            HttpRequest localReq = buildHttp11ControlRequest(interceptedRequest, localService, path);
            return ProxyRequestReceivedAction.doNotIntercept(localReq);
        }

        HttpService svc = interceptedRequest.httpService();
        if (svc != null && config.isHostAllowed(svc.host())) {
            CrawlState.Status st = state.getStatus();
            if (st == CrawlState.Status.RUNNING) {
                state.bindMessageActionPath(interceptedRequest.messageId(), state.getCurrentActionPath());
                if (trackedInFlightIds.size() < 4096) {
                    trackedInFlightIds.add(interceptedRequest.messageId());
                    state.incrementInFlight();
                }
            }

            // Ensure browser navigation HTML documents arrive uncompressed & fresh (no br/zstd/304)
            // whenever agent injection is enabled so both Chrome and Firefox get <script> reliably.
            boolean shouldInject = (st == CrawlState.Status.RUNNING) || (agentArmed && st != CrawlState.Status.STOPPED);
            if (shouldInject && isDocumentNavigationRequest(interceptedRequest)) {
                HttpRequest modified = interceptedRequest
                    .withUpdatedHeader("Accept-Encoding", "identity")
                    .withRemovedHeader("If-None-Match")
                    .withRemovedHeader("If-Modified-Since");
                return ProxyRequestReceivedAction.continueWith(modified);
            }
        }
        return ProxyRequestReceivedAction.continueWith(interceptedRequest);
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest interceptedRequest) {
        return ProxyRequestToBeSentAction.continueWith(interceptedRequest);
    }

    private static boolean isDocumentNavigationRequest(InterceptedRequest req) {
        String secFetchDest = req.headerValue("Sec-Fetch-Dest");
        if (secFetchDest != null) {
            String d = secFetchDest.trim().toLowerCase();
            if (d.equals("iframe") || d.equals("frame") || d.equals("object") || d.equals("embed")) {
                return false;
            }
            if (d.equals("document")) {
                return true;
            }
        }
        String accept = req.headerValue("Accept");
        if (accept != null && accept.toLowerCase().contains("text/html")) {
            return true;
        }
        String path = req.pathWithoutQuery();
        return path != null && (path.equals("/") || path.endsWith(".html") || path.endsWith(".htm")
            || path.endsWith(".php") || path.endsWith(".jsp") || path.endsWith(".aspx"));
    }

    private HttpRequest buildHttp11ControlRequest(InterceptedRequest req, HttpService localService, String path) {
        String method = req.method() != null ? req.method().toUpperCase() : "GET";
        byte[] body = req.body() != null ? req.body().getBytes() : new byte[0];

        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1:").append(localControlPort).append("\r\n");

        for (HttpHeader h : req.headers()) {
            String name = h.name();
            if (name == null || name.startsWith(":")) {
                continue; // strip HTTP/2 pseudo-headers
            }
            String lower = name.toLowerCase();
            if (lower.equals("host") || lower.equals("connection")
                || lower.equals("content-length") || lower.equals("transfer-encoding")
                || lower.equals("accept-encoding")) {
                continue;
            }
            sb.append(name).append(": ").append(h.value()).append("\r\n");
        }
        if (body.length > 0) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length == 0) {
            return HttpRequest.httpRequest(localService, ByteArray.byteArray(headerBytes));
        }
        ByteArrayOutputStream full = new ByteArrayOutputStream(headerBytes.length + body.length);
        full.write(headerBytes, 0, headerBytes.length);
        full.write(body, 0, body.length);
        return HttpRequest.httpRequest(localService, ByteArray.byteArray(full.toByteArray()));
    }
}
