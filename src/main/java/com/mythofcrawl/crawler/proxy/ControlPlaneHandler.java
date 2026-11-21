package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import com.mythofcrawl.crawler.core.guardrail.ActionGateAuditLog;
import com.mythofcrawl.crawler.core.guardrail.GuardrailMode;
import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.guardrail.PolicyEvaluator;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.ChangesBatch;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Intercepts Proxy requests:
 * 1) Redirects same-origin virtual /__myth_crawl/* calls (for allowed/loopback hosts while active/armed)
 *    cleanly as HTTP/1.1 requests to the loopback LocalControlServer.
 * 2) Rewrites Accept-Encoding: gzip, deflate, br, identity on ALL in-scope requests (stripping zstd)
 *    and forces identity + no-cache on document navigations so HTML/JS always decode cleanly.
 * 3) Captures the active UI action path (preferring Referer-correlated context) keyed by
 *    Montoya messageId() with 60s TTL eviction in CrawlState.
 */
public final class ControlPlaneHandler implements ProxyRequestHandler {

    private final CrawlConfig config;
    private final CrawlState state;
    private final int localControlPort;
    private volatile boolean agentArmed = true;
    private volatile boolean passiveCapture = false;
    private volatile PolicyLedger policyLedger;
    private volatile ProxyInterceptQueue interceptQueue;
    private volatile ActionGateAuditLog auditLog;
    private volatile String localControlToken = "";
    private volatile Supplier<String> uiActionSupplier;
    private volatile ChangesBatch changesBatch;

    public ControlPlaneHandler(CrawlConfig config, CrawlState state, int localControlPort) {
        this.config = config;
        this.state = state;
        this.localControlPort = localControlPort;
    }

    public void setGuardrailEngine(
        PolicyLedger policyLedger,
        ProxyInterceptQueue interceptQueue,
        ActionGateAuditLog auditLog,
        String localControlToken,
        Supplier<String> uiActionSupplier
    ) {
        this.policyLedger = policyLedger;
        this.interceptQueue = interceptQueue;
        this.auditLog = auditLog;
        this.localControlToken = localControlToken != null ? localControlToken : "";
        this.uiActionSupplier = uiActionSupplier;
    }

    public void setChangesBatch(ChangesBatch changesBatch) {
        this.changesBatch = changesBatch;
    }

    public void setAgentArmed(boolean agentArmed) {
        this.agentArmed = agentArmed;
    }

    public void setPassiveCapture(boolean passiveCapture) {
        this.passiveCapture = passiveCapture;
    }

    public boolean consumeTrackedInFlight(int messageId) {
        return state.consumeTrackedInFlight(messageId);
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest interceptedRequest) {
        String path = interceptedRequest.path();
        HttpService svc = interceptedRequest.httpService();
        CrawlState.Status st = state.getStatus();

        if (path != null && path.startsWith("/__myth_crawl/") && shouldHandleControlPath(svc, interceptedRequest, st)) {
            HttpService localService = HttpService.httpService("127.0.0.1", localControlPort, false);
            HttpRequest localReq = buildHttp11ControlRequest(interceptedRequest, localService, path, false);
            return ProxyRequestReceivedAction.doNotIntercept(localReq);
        }

        if (svc != null && isTargetOrReplayHost(svc.host())) {
            String referer = interceptedRequest.headerValue("Referer");
            CrawlState.ActionContextSnapshot ctx = state.resolveContextForReferer(referer);
            if (st == CrawlState.Status.RUNNING && config.isHostAllowed(svc.host())) {
                state.bindMessageContext(
                    interceptedRequest.messageId(),
                    ctx.actionPath,
                    ctx.group,
                    true);
            }

            HttpRequest activeRequest = interceptedRequest;
            if (policyLedger != null && !isLoopbackHost(svc.host())) {
                String branchPath = ctx.actionPath != null ? ctx.actionPath : "";
                String uiAction = uiActionSupplier != null ? uiActionSupplier.get() : "";
                String reqMethod = interceptedRequest.method() != null
                    ? interceptedRequest.method().toUpperCase() : "GET";
                String reqPath = path != null ? path : "/";
                PolicyEvaluator.Decision decision = PolicyEvaluator.evaluate(
                    policyLedger, branchPath, svc.host(), reqMethod, reqPath, uiAction);

                if (decision.effectiveMode == GuardrailMode.HOLD_AT_PROXY
                    || decision.effectiveMode == GuardrailMode.LOCK_2XX) {
                    if (auditLog != null) {
                        auditLog.record(
                            decision.effectiveMode == GuardrailMode.LOCK_2XX
                                ? ActionGateAuditLog.Outcome.LOCK_2XX
                                : ActionGateAuditLog.Outcome.HOLD_AT_PROXY,
                            branchPath, svc.host(), reqMethod, reqPath, uiAction,
                            decision.winningRule, decision.reason);
                    }
                    if (changesBatch != null) {
                        changesBatch.markDirty();
                    }
                    String holdPath = "/__myth_crawl/virtual_hold?method=" + urlEncode(reqMethod)
                        + "&endpoint=" + urlEncode(reqPath)
                        + "&reason=" + urlEncode(HttpEncodingGuard.asciiSafe(decision.reason));
                    HttpService localService = HttpService.httpService("127.0.0.1", localControlPort, false);
                    HttpRequest localReq = buildHttp11ControlRequest(interceptedRequest, localService, holdPath, true);
                    if (localControlToken != null && !localControlToken.isEmpty()) {
                        localReq = localReq.withUpdatedHeader("X-Myth-Token", localControlToken);
                    }
                    return ProxyRequestReceivedAction.doNotIntercept(localReq);
                }

                if (decision.effectiveMode == GuardrailMode.INTERCEPT_WAIT && interceptQueue != null) {
                    if (auditLog != null) {
                        auditLog.record(
                            ActionGateAuditLog.Outcome.INTERCEPT_WAIT,
                            branchPath, svc.host(), reqMethod, reqPath, uiAction,
                            decision.winningRule, decision.reason);
                    }
                    byte[] rawBytes = interceptedRequest.toByteArray().getBytes();
                    String ruleName = decision.winningRule != null ? decision.winningRule.name : "";
                    ProxyInterceptQueue.InterceptResolution res = interceptQueue.enqueueAndWait(
                        branchPath, svc.host(), reqMethod, reqPath, rawBytes,
                        decision.reason, ruleName, ProxyInterceptQueue.DEFAULT_TIMEOUT_MS);
                    if (res.action == ProxyInterceptQueue.ResolutionAction.DROP) {
                        if (auditLog != null) {
                            auditLog.record(ActionGateAuditLog.Outcome.DROP,
                                branchPath, svc.host(), reqMethod, reqPath, uiAction,
                                decision.winningRule, res.note);
                        }
                        return ProxyRequestReceivedAction.drop();
                    }
                    if (res.action == ProxyInterceptQueue.ResolutionAction.SAVE_DRAFT) {
                        if (auditLog != null) {
                            auditLog.record(ActionGateAuditLog.Outcome.SAVE_DRAFT,
                                branchPath, svc.host(), reqMethod, reqPath, uiAction,
                                decision.winningRule, res.note);
                        }
                        return ProxyRequestReceivedAction.drop();
                    }
                    if (res.action == ProxyInterceptQueue.ResolutionAction.FORWARD) {
                        if (auditLog != null) {
                            auditLog.record(ActionGateAuditLog.Outcome.FORWARD,
                                branchPath, svc.host(), reqMethod, reqPath, uiAction,
                                decision.winningRule, res.note);
                        }
                        if (res.editedRawBytes != null && res.editedRawBytes.length > 0) {
                            activeRequest = HttpRequest.httpRequest(svc, ByteArray.byteArray(res.editedRawBytes));
                        }
                    }
                } else if (decision.winningRule != null && decision.winningRule.allow && auditLog != null) {
                    auditLog.record(ActionGateAuditLog.Outcome.ALLOW,
                        branchPath, svc.host(), reqMethod, reqPath, uiAction,
                        decision.winningRule, decision.reason);
                }
            }

            // Apply custom user HTTP Header Filter rules (e.g., X-Account: a) to target requests
            HttpRequest modified = config.getHeaderFilterProfile()
                .applyToMontoyaRequest(activeRequest, svc.host(), activeRequest.url(), false);

            // Rewrite Accept-Encoding for ALL in-scope traffic while active/armed/passive/replaying so
            // zstd is never negotiated and JS bundles + HTML always decode via gzip/deflate/brotli/identity.
            boolean replayActive = state.hasActiveReplayPlan();
            boolean shouldInject = (st == CrawlState.Status.RUNNING) || replayActive || (agentArmed && st != CrawlState.Status.STOPPED);
            boolean shouldNormalizeEncoding = shouldInject || passiveCapture;
            if (shouldInject && isDocumentOrFrameNavigationRequest(modified)) {
                modified = modified
                    .withUpdatedHeader("Accept-Encoding", "identity")
                    .withRemovedHeader("If-None-Match")
                    .withRemovedHeader("If-Modified-Since");
            } else if (shouldNormalizeEncoding) {
                modified = modified.withUpdatedHeader("Accept-Encoding", "gzip, deflate, br, identity");
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

    private boolean isTargetOrReplayHost(String host) {
        return config.isHostAllowed(host) || state.isReplayHostAllowed(host);
    }

    private boolean shouldHandleControlPath(HttpService svc, InterceptedRequest req, CrawlState.Status st) {
        if (svc == null) {
            return false;
        }
        if (st == CrawlState.Status.STOPPED && !agentArmed && !state.hasActiveReplayPlan()) {
            return false;
        }
        String host = svc.host();
        if (isLoopbackHost(host)) {
            return true;
        }
        if (isTargetOrReplayHost(host)) {
            return true;
        }
        return isRefererHostAllowed(req.headerValue("Referer"));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h);
    }

    private boolean shouldInterceptExternalRedirect(InterceptedRequest req, String host) {
        if (state.getStatus() != CrawlState.Status.RUNNING || !config.isAllowAutoRequests()) {
            return false;
        }
        if (host == null || host.isEmpty() || isLoopbackHost(host)) {
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
        // Tightened correlation for rel="noreferrer" redirects: only within 2.5s of an active UI click
        if (referer == null || referer.trim().isEmpty()) {
            long clickElapsed = System.currentTimeMillis() - state.getLastClickActionAtMs();
            return state.getLastClickActionAtMs() > 0 && clickElapsed >= 0 && clickElapsed <= 2500L;
        }
        return false;
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

    private static boolean isDocumentOrFrameNavigationRequest(HttpRequest req) {
        String secFetchDest = req.headerValue("Sec-Fetch-Dest");
        if (secFetchDest != null) {
            String d = secFetchDest.trim().toLowerCase();
            if (d.equals("object") || d.equals("embed")) {
                return false;
            }
            if (d.equals("document") || d.equals("iframe") || d.equals("frame")) {
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
