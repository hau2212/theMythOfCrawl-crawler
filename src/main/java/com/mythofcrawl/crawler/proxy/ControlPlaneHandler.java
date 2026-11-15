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
import java.net.URI;
import java.net.URLEncoder;
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
            HttpRequest localReq = buildHttp11ControlRequest(interceptedRequest, localService, path, false);
            return ProxyRequestReceivedAction.doNotIntercept(localReq);
        }

        HttpService svc = interceptedRequest.httpService();
        if (svc != null && config.isHostAllowed(svc.host())) {
            CrawlState.Status st = state.getStatus();
            if (st == CrawlState.Status.RUNNING) {
                state.bindMessageContext(
                    interceptedRequest.messageId(),
                    state.getCurrentActionPath(),
                    state.getCurrentGroup());
                if (trackedInFlightIds.size() < 4096) {
                    trackedInFlightIds.add(interceptedRequest.messageId());
                    state.incrementInFlight();
                }
            }

            // Apply custom user HTTP Header Filter rules (e.g., X-Account: a) to target requests
            HttpRequest modified = config.getHeaderFilterProfile()
                .applyToMontoyaRequest(interceptedRequest, svc.host(), interceptedRequest.url(), false);

            // Ensure browser navigation HTML documents arrive uncompressed & fresh (no br/zstd/304)
            // whenever agent injection is enabled so both Chrome and Firefox get <script> reliably.
            boolean shouldInject = (st == CrawlState.Status.RUNNING) || (agentArmed && st != CrawlState.Status.STOPPED);
            if (shouldInject && isDocumentNavigationRequest(modified)) {
                modified = modified
                    .withUpdatedHeader("Accept-Encoding", "identity")
                    .withRemovedHeader("If-None-Match")
                    .withRemovedHeader("If-Modified-Since");
            }
            if (modified != interceptedRequest) {
                return ProxyRequestReceivedAction.continueWith(modified);
            }
        } else if (svc != null && shouldInterceptExternalRedirect(interceptedRequest, svc.host())) {
            String destUrl = interceptedRequest.url() != null ? interceptedRequest.url() : "";
            String referer = interceptedRequest.headerValue("Referer");
            String fromUrl = isRefererHostAllowed(referer) ? referer : state.getCurrentPageUrl();
            String bouncePath = "/__myth_crawl/bounce_back?dest=" + urlEncode(destUrl)
                + "&from=" + urlEncode(fromUrl);
            HttpService localService = HttpService.httpService("127.0.0.1", localControlPort, false);
            HttpRequest localReq = buildHttp11ControlRequest(interceptedRequest, localService, bouncePath, true);
            return ProxyRequestReceivedAction.doNotIntercept(localReq);
        }
        return ProxyRequestReceivedAction.continueWith(interceptedRequest);
    }

    private boolean shouldInterceptExternalRedirect(InterceptedRequest req, String host) {
        if (state.getStatus() != CrawlState.Status.RUNNING || !config.isAllowAutoRequests()) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        String hostLower = host.toLowerCase();
        if ("127.0.0.1".equals(hostLower) || "localhost".equals(hostLower) || "::1".equals(hostLower)) {
            return false;
        }
        String fetchDest = req.headerValue("Sec-Fetch-Dest");
        if (fetchDest == null || !"document".equalsIgnoreCase(fetchDest.trim())) {
            return false;
        }
        String fetchMode = req.headerValue("Sec-Fetch-Mode");
        if (fetchMode != null && !fetchMode.trim().isEmpty() && !"navigate".equalsIgnoreCase(fetchMode.trim())) {
            return false;
        }
        String referer = req.headerValue("Referer");
        if (isRefererHostAllowed(referer)) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - state.getLastContextActionAtMs();
        return state.getLastContextActionAtMs() > 0 && elapsed >= 0 && elapsed <= 8000L;
    }

    private boolean isRefererHostAllowed(String referer) {
        if (referer == null || referer.trim().isEmpty()) {
            return false;
        }
        try {
            URI u = URI.create(referer.trim());
            String refHost = u.getHost();
            return refHost != null && config.isHostAllowed(refHost);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String urlEncode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest interceptedRequest) {
        return ProxyRequestToBeSentAction.continueWith(interceptedRequest);
    }

    private static boolean isDocumentNavigationRequest(HttpRequest req) {
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

    private HttpRequest buildHttp11ControlRequest(
        InterceptedRequest req,
        HttpService localService,
        String path,
        boolean forceGet
    ) {
        String method = forceGet ? "GET" : (req.method() != null ? req.method().toUpperCase() : "GET");
        byte[] body = forceGet ? new byte[0] : (req.body() != null ? req.body().getBytes() : new byte[0]);

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
