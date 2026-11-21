package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.MiniJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Optional zero-dependency Chromium DevTools Protocol (CDP) sidecar using Java 17's
 * built-in {@link HttpClient} WebSocket client.
 *
 * When {@link CrawlConfig#isCdpSidecarEnabled()} is enabled (default: false), polls
 * {@code http://127.0.0.1:<cdpPort>/json/list} for open Chromium page targets and executes
 * {@code Page.addScriptToEvaluateOnNewDocument} + {@code Network.setBypassServiceWorker}
 * so strict CSP / Trusted Types / Service Workers cannot block agent injection.
 */
public final class CdpSidecarBridge {

    private final CrawlConfig config;
    private final LocalControlServer controlServer;
    private final Consumer<String> logger;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger cmdId = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, WebSocket> attachedTargets = new ConcurrentHashMap<>();
    private volatile String agentScriptSource;

    public CdpSidecarBridge(
        CrawlConfig config,
        LocalControlServer controlServer,
        Consumer<String> logger
    ) {
        this.config = config;
        this.controlServer = controlServer;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MythCrawler-CdpSidecar");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::pollAndAttachOnce, 2, 3, TimeUnit.SECONDS);
    }

    public void shutdown() {
        running.set(false);
        scheduler.shutdownNow();
        closeAllSockets();
    }

    private void closeAllSockets() {
        for (Map.Entry<String, WebSocket> e : attachedTargets.entrySet()) {
            try {
                e.getValue().sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
            }
        }
        attachedTargets.clear();
    }

    private void pollAndAttachOnce() {
        if (!running.get() || !config.isCdpSidecarEnabled()) {
            if (!attachedTargets.isEmpty()) {
                closeAllSockets();
            }
            return;
        }
        int port = config.getCdpPort();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/json/list"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                return;
            }
            String body = resp.body().trim();
            if (!body.startsWith("[")) {
                return;
            }
            // Parse array of target JSON objects via wrapped object
            Map<String, Object> wrapper = MiniJson.parseObject("{\"targets\":" + body + "}");
            Object rawList = wrapper.get("targets");
            if (!(rawList instanceof List<?>)) {
                return;
            }
            Set<String> activeWsUrls = ConcurrentHashMap.newKeySet();
            for (Object item : (List<?>) rawList) {
                if (!(item instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) item;
                String type = MiniJson.asString(t, "type", "");
                String pageUrl = MiniJson.asString(t, "url", "");
                String wsUrl = MiniJson.asString(t, "webSocketDebuggerUrl", "");
                if (!"page".equalsIgnoreCase(type) || wsUrl.isEmpty()) {
                    continue;
                }
                if (!isTargetRelevant(pageUrl)) {
                    continue;
                }
                activeWsUrls.add(wsUrl);
                if (!attachedTargets.containsKey(wsUrl)) {
                    attachTarget(wsUrl, pageUrl);
                }
            }
            // Clean up closed tabs
            attachedTargets.keySet().removeIf(url -> !activeWsUrls.contains(url));
        } catch (Exception ignored) {
            // Chrome --remote-debugging-port not active on localhost; silent retry next tick
        }
    }

    private boolean isTargetRelevant(String pageUrl) {
        if (config.getAllowedHostsSet().isEmpty()) {
            return false;
        }
        if (pageUrl == null || pageUrl.isEmpty() || pageUrl.startsWith("about:") || pageUrl.startsWith("chrome://")) {
            return config.getAllowedHostsSet().contains("*");
        }
        try {
            URI u = URI.create(pageUrl);
            return u.getHost() != null && config.isHostAllowed(u.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void attachTarget(String wsUrl, String pageUrl) {
        try {
            CompletableFuture<WebSocket> fut = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        attachedTargets.remove(wsUrl, webSocket);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        attachedTargets.remove(wsUrl, webSocket);
                        return null;
                    }
                });
            fut.thenAccept(ws -> {
                attachedTargets.put(wsUrl, ws);
                sendCdpCommand(ws, "Page.enable", Map.of());
                sendCdpCommand(ws, "Network.enable", Map.of());
                if (config.isBypassServiceWorker()) {
                    sendCdpCommand(ws, "Network.setBypassServiceWorker", Map.of("bypass", true));
                }
                String script = getAgentScriptWithToken();
                if (script != null && !script.isEmpty()) {
                    sendCdpCommand(ws, "Page.addScriptToEvaluateOnNewDocument", Map.of("source", script));
                    sendCdpCommand(ws, "Runtime.evaluate", Map.of("expression", script));
                }
                if (logger != null) {
                    logger.accept("[CDP Sidecar] Đã gắn Page.addScriptToEvaluateOnNewDocument cho tab: " + pageUrl);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void sendCdpCommand(WebSocket ws, String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", cmdId.getAndIncrement());
        payload.put("method", method);
        payload.put("params", params);
        ws.sendText(MiniJson.stringify(payload), true);
    }

    private String getAgentScriptWithToken() {
        if (agentScriptSource != null) {
            return agentScriptSource;
        }
        try (java.io.InputStream in = CdpSidecarBridge.class.getResourceAsStream("/crawler-agent.js")) {
            if (in != null) {
                String js = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String tok = controlServer != null ? controlServer.getSessionToken() : "";
                agentScriptSource = "window.__MYTH_TOKEN__=" + MiniJson.quote(tok) + ";\n" + js;
                return agentScriptSource;
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
