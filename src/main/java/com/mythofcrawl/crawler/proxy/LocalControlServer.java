package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionStep;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.MiniJson;
import com.mythofcrawl.crawler.model.ReplayPlan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pure java.base in-JVM HTTP/1.1 loopback control plane bound strictly to 127.0.0.1.
 * Burp's ProxyRequestHandler redirects same-origin requests targeting /__myth_crawl/* here,
 * so zero control traffic ever touches the target website and CORS/Mixed-Content/CSP never block Firefox.
 *
 * Hardened with:
 * - Bounded ThreadPoolExecutor (4..16 workers, 256-slot queue)
 * - 2 MB body cap on both Content-Length and chunked transfer encoding
 * - Per-session X-Myth-Token injected into /__myth_crawl/agent.js and enforced when enabled
 */
public final class LocalControlServer {

    private static final int MAX_BODY_BYTES = 2_000_000;

    private final CrawlConfig config;
    private final CrawlState state;
    private final byte[] agentJsBytes;
    private final String sessionToken;
    private final Runnable onUiEvent;
    private final Consumer<String> logger;

    private volatile boolean requireToken;
    private volatile ProxyInterceptQueue interceptQueue;
    private final java.util.concurrent.atomic.AtomicReference<String> latestUiAction =
        new java.util.concurrent.atomic.AtomicReference<>("");
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private volatile boolean running = false;

    public LocalControlServer(
        CrawlConfig config,
        CrawlState state,
        Runnable onUiEvent,
        Consumer<String> logger
    ) throws IOException {
        this(config, state, onUiEvent, logger, false);
    }

    public LocalControlServer(
        CrawlConfig config,
        CrawlState state,
        Runnable onUiEvent,
        Consumer<String> logger,
        boolean requireToken
    ) throws IOException {
        this.config = config;
        this.state = state;
        this.onUiEvent = onUiEvent;
        this.logger = logger;
        this.requireToken = requireToken;
        this.sessionToken = generateToken();
        this.agentJsBytes = loadAgentResource();
        this.serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        this.workers = new ThreadPoolExecutor(
            4,
            16,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "MythCrawler-ControlWorker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void setRequireToken(boolean requireToken) {
        this.requireToken = requireToken;
    }

    public boolean isRequireToken() {
        return requireToken;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getLatestUiAction() {
        return latestUiAction.get();
    }

    public void setInterceptQueue(ProxyInterceptQueue interceptQueue) {
        this.interceptQueue = interceptQueue;
        if (interceptQueue != null) {
            state.setPendingInterceptCountSupplier(interceptQueue::pendingCount);
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    try {
                        workers.execute(() -> handleConnection(sock));
                    } catch (RejectedExecutionException rex) {
                        try {
                            sock.close();
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                    if (!running) {
                        break;
                    }
                }
            }
        }, "MythCrawler-ControlAcceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private static String generateToken() {
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : rnd) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private byte[] loadAgentResource() {
        try (InputStream in = LocalControlServer.class.getResourceAsStream("/crawler-agent.js")) {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (IOException ignored) {
        }
        return "/* missing crawler-agent.js */\n".getBytes(StandardCharsets.UTF_8);
    }

    private void handleConnection(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            int qIdx = rawPath.indexOf('?');
            String path = qIdx >= 0 ? rawPath.substring(0, qIdx) : rawPath;
            String query = qIdx >= 0 ? rawPath.substring(qIdx + 1) : "";

            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
                }
            }

            byte[] bodyBytes = new byte[0];
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                bodyBytes = readChunkedBody(in);
            } else if (headers.containsKey("content-length")) {
                try {
                    int len = Integer.parseInt(headers.get("content-length").trim());
                    if (len > MAX_BODY_BYTES) {
                        writeJsonStatus(out, 413, Map.of("ok", false, "error", "payload too large"));
                        return;
                    }
                    if (len > 0) {
                        bodyBytes = readExact(in, len);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

            dispatch(method, path, query, headers, bodyStr, out);
        } catch (Exception ignored) {
        }
    }

    private boolean isTokenAuthorized(Map<String, String> headers) {
        if (!requireToken) {
            return true;
        }
        String supplied = headers != null ? headers.get("x-myth-token") : null;
        if (supplied == null || supplied.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            sessionToken.getBytes(StandardCharsets.US_ASCII),
            supplied.trim().getBytes(StandardCharsets.US_ASCII)
        );
    }

    private void dispatch(
        String method,
        String path,
        String query,
        Map<String, String> headers,
        String bodyStr,
        OutputStream out
    ) throws IOException {
        if ("/__myth_crawl/agent.js".equals(path)) {
            byte[] tokenHeader = ("window.__MYTH_TOKEN__=" + MiniJson.quote(sessionToken) + ";\n")
                .getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[tokenHeader.length + agentJsBytes.length];
            System.arraycopy(tokenHeader, 0, combined, 0, tokenHeader.length);
            System.arraycopy(agentJsBytes, 0, combined, tokenHeader.length, agentJsBytes.length);
            writeResponse(out, 200, "application/javascript; charset=utf-8", combined);
            return;
        }

        if ("/__myth_crawl/bounce_back".equals(path)) {
            handleBounceBack(query, out);
            return;
        }

        if (!isTokenAuthorized(headers)) {
            writeJsonStatus(out, 403, Map.of("ok", false, "error", "invalid or missing X-Myth-Token"));
            return;
        }

        if ("/__myth_crawl/virtual_hold".equals(path)) {
            String reason = extractQueryParam(query, "reason");
            String endpoint = extractQueryParam(query, "endpoint");
            byte[] json = HttpEncodingGuard.buildVirtual200OkResponseBytes(reason, endpoint);
            writeResponseWithHeaders(
                out,
                200,
                "application/json; charset=utf-8",
                "X-Myth-Proxy-Hold: 1\r\n",
                json
            );
            return;
        }

        if ("/__myth_crawl/state".equals(path)) {
            String pageUrl = extractQueryParam(query, "url");
            String phaseParam = extractQueryParam(query, "phase");
            if (!phaseParam.isEmpty()) {
                int doneParam = parseIntParam(extractQueryParam(query, "done"), 0);
                int totalParam = parseIntParam(extractQueryParam(query, "total"), 0);
                state.updateAgentTelemetry(phaseParam, pageUrl, doneParam, totalParam);
            } else {
                state.touchAgentHeartbeat(pageUrl);
            }
            String routeKey = CrawlState.routeKeyForUrl(pageUrl, config.isNormalizeRouteIds());
            int pendingIntercept = interceptQueue != null ? interceptQueue.pendingCount() : state.getPendingInterceptCount();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", state.getStatus().name());
            resp.put("allowAutoRequests", config.isAllowAutoRequests());
            resp.put("actionWaitMs", config.getActionWaitMs());
            resp.put("maxActionsPerPage", config.getMaxActionsPerPage());
            resp.put("maxPageDurationMs", config.getMaxPageDurationSec() * 1000);
            resp.put("maxPaginationSamples", config.getMaxPaginationSamples());
            resp.put("pendingInterceptCount", pendingIntercept);
            resp.put("autoFillForms", config.isAutoFillForms());
            resp.put("deepCrawlMode", config.isDeepCrawlMode());
            resp.put("bypassServiceWorker", config.isBypassServiceWorker());
            resp.put("dangerGuardEnabled", config.isDangerGuardEnabled());
            resp.put("dangerKeywords", config.getDangerKeywordsList());
            resp.put("normalizeRouteIds", config.isNormalizeRouteIds());
            resp.put("routeRootLabel", config.getRouteRootLabel());
            resp.put("allowedHosts", new java.util.ArrayList<>(config.getAllowedHostsSet()));
            resp.put("formFill", config.getFormFillProfile().toAgentMap());
            resp.put("clickedSignatures", state.getClickedSignatures(routeKey));
            resp.put("globalClickedSignatures", state.getGlobalClickedSignatures());
            resp.put("inFlight", state.getInFlightCount());
            resp.put("queueRemaining", state.getPendingQueueSize());
            resp.put("pagesVisited", state.getVisitedPagesCount());
            ReplayPlan rp = state.getActiveReplayPlan();
            resp.put("replayPlan", (rp != null && rp.isActive()) ? rp.toMap() : null);
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/telemetry".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = !bodyStr.isEmpty() ? MiniJson.parseObject(bodyStr) : Map.of();
            String phase = MiniJson.asString(req, "phase", "IDLE");
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            int actionsDone = parseIntObj(req.get("actionsDone"), 0);
            int totalTargets = parseIntObj(req.get("totalTargets"), 0);
            state.updateAgentTelemetry(phase, pageUrl, actionsDone, totalTargets);
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            int pendingIntercept = interceptQueue != null ? interceptQueue.pendingCount() : state.getPendingInterceptCount();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("phase", phase);
            resp.put("pendingInterceptCount", pendingIntercept);
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/context".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String defaultRoot = config.getRouteRootLabel() + " > page load";
            String actionPath = MiniJson.asString(req, "actionPath", defaultRoot);
            String kind = MiniJson.asString(req, "kind", "");
            String uiAction = MiniJson.asString(req, "uiAction", kind);
            latestUiAction.set(uiAction != null ? uiAction.trim().toLowerCase(Locale.ROOT) : "");
            String label = MiniJson.asString(req, "label", "");
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            Object locObj = req.get("stepLocator");
            ActionStep step = (locObj instanceof Map<?, ?>)
                ? ActionStep.fromStepLocatorMap(0, pageUrl, kind, label, actionPath, (Map<?, ?>) locObj)
                : null;
            ActionGroup grp = state.setCurrentContext(actionPath, kind, label, pageUrl, step);
            if (logger != null && !actionPath.endsWith("> page load")) {
                logger.accept("Nhóm thao tác -> " + actionPath);
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("actionPath", actionPath);
            resp.put("groupId", grp != null ? grp.id : 0);
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/done_action".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            String sig = MiniJson.asString(req, "signature", "");
            String globalSig = MiniJson.asString(req, "globalSignature", "");
            String routeKey = CrawlState.routeKeyForUrl(pageUrl, config.isNormalizeRouteIds());
            state.markActionClicked(routeKey, sig);
            if (!globalSig.isEmpty()) {
                state.markGlobalActionClicked(globalSig);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("inFlight", state.getInFlightCount());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/discovered".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            if (!pageUrl.isEmpty()) {
                state.markUrlVisited(pageUrl);
            }
            List<String> urls = MiniJson.asStringList(req, "urls");
            int added = 0;
            for (String u : urls) {
                if (state.enqueueUrl(u, config)) {
                    added++;
                }
            }
            Object skippedObj = req.get("skipped");
            if (skippedObj instanceof List<?>) {
                for (Object item : (List<?>) skippedObj) {
                    if (item instanceof Map<?, ?>) {
                        Map<?, ?> m = (Map<?, ?>) item;
                        String ap = m.get("actionPath") != null ? String.valueOf(m.get("actionPath")) : "";
                        String reason = m.get("reason") != null ? String.valueOf(m.get("reason")) : "Danger Guard";
                        String tag = m.get("tag") != null ? String.valueOf(m.get("tag")) : "";
                        state.recordSkippedDanger(ap, reason, tag, pageUrl);
                    }
                }
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("enqueued", added);
            resp.put("queueRemaining", state.getPendingQueueSize());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/next_page".equals(path)) {
            String curUrl = extractQueryParam(query, "url");
            if (!curUrl.isEmpty()) {
                state.markUrlCompleted(curUrl);
            }
            String nextUrl = null;
            int levelsBack = 0;
            if (state.getStatus() == CrawlState.Status.RUNNING) {
                CrawlState.BacktrackResult bt = state.resolveBacktrackOrNextPage(curUrl, false, true);
                if (!bt.exhausted && !bt.targetUrl.isEmpty()) {
                    nextUrl = bt.targetUrl;
                    levelsBack = bt.levelsBack;
                }
            }
            if (nextUrl == null && state.getStatus() == CrawlState.Status.RUNNING) {
                state.setStatus(CrawlState.Status.STOPPED);
                if (logger != null) {
                    logger.accept("Hoàn tất crawl hàng đợi! Đã duyệt " + state.getVisitedPagesCount() + " trang.");
                }
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("nextUrl", nextUrl != null ? nextUrl : "");
            resp.put("levelsBack", levelsBack);
            resp.put("done", nextUrl == null);
            resp.put("status", state.getStatus().name());
            resp.put("queueRemaining", state.getPendingQueueSize());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/note".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String actionPath = MiniJson.asString(req, "actionPath", state.getCurrentActionPath());
            String kind = MiniJson.asString(req, "kind", "");
            String label = MiniJson.asString(req, "label", "");
            String pageUrl = MiniJson.asString(req, "pageUrl", state.getCurrentPageUrl());
            String noteTypeRaw = MiniJson.asString(req, "noteType", "REDIRECT_DEFERRED");
            String sevRaw = MiniJson.asString(req, "severity", "WARN");
            String reqMethod = MiniJson.asString(req, "method", "NAV");
            String endpoint = MiniJson.asString(req, "endpoint", "");
            String message = MiniJson.asString(req, "message", "");
            String deferUrl = MiniJson.asString(req, "deferUrl", "");

            AuditNote.Type noteType = parseNoteType(noteTypeRaw);
            AuditNote.Severity sev = parseSeverity(sevRaw);
            ActionGroup grp = state.getActionGroups().openGroup(actionPath, kind, label, pageUrl);
            state.getActionGroups().recordStandaloneNote(grp, sev, noteType, reqMethod, endpoint, message);
            if (!deferUrl.isEmpty()) {
                state.markUrlDeferred(deferUrl);
            }
            if (logger != null && !message.isEmpty()) {
                logger.accept("[NOTE " + noteType.label + "] " + actionPath + " -> " + message);
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("groupId", grp != null ? grp.id : 0);
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/backtrack".equals(path)) {
            Map<String, Object> req = "POST".equals(method) && !bodyStr.isEmpty()
                ? MiniJson.parseObject(bodyStr) : Map.of();
            String curUrl = extractQueryParam(query, "url");
            if (curUrl.isEmpty()) {
                curUrl = MiniJson.asString(req, "url", state.getCurrentPageUrl());
            }
            String deferParam = extractQueryParam(query, "defer");
            if (deferParam.isEmpty()) {
                deferParam = MiniJson.asString(req, "defer", "");
            }
            boolean defer = "1".equals(deferParam) || "true".equalsIgnoreCase(deferParam);
            String dest = extractQueryParam(query, "dest");
            if (dest.isEmpty()) {
                dest = MiniJson.asString(req, "dest", "");
            }
            String reason = extractQueryParam(query, "reason");
            if (reason.isEmpty()) {
                reason = MiniJson.asString(req, "reason", "");
            }
            if (defer && !curUrl.isEmpty()) {
                state.markUrlDeferred(curUrl);
            }
            if (!dest.isEmpty() || !reason.isEmpty()) {
                ActionGroup grp = state.getActionGroups().openGroup(
                    state.getCurrentActionPath(), "", "", curUrl);
                String msg = !reason.isEmpty() ? reason : ("Tạm NOTE & quay lại từ chuyển hướng: " + dest);
                state.getActionGroups().recordStandaloneNote(
                    grp,
                    AuditNote.Severity.WARN,
                    AuditNote.Type.REDIRECT_DEFERRED,
                    "NAV",
                    !dest.isEmpty() ? dest : curUrl,
                    msg
                );
            }

            CrawlState.BacktrackResult bt = state.getStatus() == CrawlState.Status.RUNNING
                ? state.resolveBacktrackOrNextPage(curUrl, true, true)
                : new CrawlState.BacktrackResult("", 0, false, true);
            boolean done = bt.exhausted || bt.targetUrl.isEmpty();
            if (done && state.getStatus() == CrawlState.Status.RUNNING) {
                state.setStatus(CrawlState.Status.STOPPED);
                if (logger != null) {
                    logger.accept("Hoàn tất backtrack & hàng đợi! Đã duyệt " + state.getVisitedPagesCount() + " trang.");
                }
            } else if (!done && logger != null) {
                logger.accept("[Backtrack " + bt.levelsBack + " cấp] Quay lại: " + bt.targetUrl);
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("targetUrl", bt.targetUrl);
            resp.put("levelsBack", bt.levelsBack);
            resp.put("fromQueue", bt.fromQueue);
            resp.put("done", done);
            resp.put("status", state.getStatus().name());
            resp.put("queueRemaining", state.getPendingQueueSize());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/pause".equals(path) && ("POST".equals(method) || "GET".equals(method))) {
            Map<String, Object> req = ("POST".equals(method) && !bodyStr.isEmpty())
                ? MiniJson.parseObject(bodyStr) : Map.of();
            String reason = MiniJson.asString(req, "reason", extractQueryParam(query, "reason"));
            state.setStatus(CrawlState.Status.PAUSED);
            if (logger != null && !reason.isEmpty()) {
                logger.accept("Tạm dừng Crawl (Agent): " + reason);
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("status", state.getStatus().name());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/replay_plan".equals(path)) {
            if ("GET".equals(method)) {
                ReplayPlan rp = state.getActiveReplayPlan();
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                resp.put("replayPlan", (rp != null && rp.isActive()) ? rp.toMap() : null);
                writeJson(out, resp);
                return;
            }
            if ("POST".equals(method)) {
                Map<String, Object> req = !bodyStr.isEmpty() ? MiniJson.parseObject(bodyStr) : Map.of();
                String action = MiniJson.asString(req, "action", "step").trim().toLowerCase(Locale.ROOT);
                String planId = MiniJson.asString(req, "planId", "");
                if ("stop".equals(action)) {
                    state.stopReplayPlan(planId);
                    if (onUiEvent != null) {
                        onUiEvent.run();
                    }
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("ok", true);
                    resp.put("replayPlan", null);
                    writeJson(out, resp);
                    return;
                }
                Integer idx = null;
                Object rawIdx = req.get("currentIndex");
                if (rawIdx instanceof Number) {
                    idx = ((Number) rawIdx).intValue();
                } else if (rawIdx != null) {
                    try {
                        idx = Integer.parseInt(String.valueOf(rawIdx).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                Boolean autoPlay = req.containsKey("autoPlay")
                    ? Boolean.valueOf(MiniJson.asBoolean(req, "autoPlay", false))
                    : null;
                ReplayPlan updated = state.updateReplayStep(planId, idx, autoPlay);
                if (onUiEvent != null) {
                    onUiEvent.run();
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", updated != null);
                resp.put("replayPlan", (updated != null && updated.isActive()) ? updated.toMap() : null);
                writeJson(out, resp);
                return;
            }
        }

        writeJsonStatus(out, 404, Map.of("ok", false, "error", "unknown control endpoint"));
    }

    private void handleBounceBack(String query, OutputStream out) throws IOException {
        String dest = extractQueryParam(query, "dest");
        String from = extractQueryParam(query, "from");
        String markFrom = extractQueryParam(query, "markFrom");
        if (state.getStatus() != CrawlState.Status.RUNNING || !config.isAllowAutoRequests()) {
            String html = "<!doctype html><html><meta charset=\"utf-8\"><body>"
                + "<p>Crawl đang tạm dừng/dừng. Đã chặn chuyển hướng ngoài host.</p>"
                + "</body></html>";
            writeResponse(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String pageUrl = !from.isEmpty() ? from : state.getCurrentPageUrl();
        String actionPath = state.getCurrentActionPath();
        ActionGroup grp = state.getActionGroups().openGroup(actionPath, "", "", pageUrl);
        state.getActionGroups().recordStandaloneNote(
            grp,
            AuditNote.Severity.WARN,
            AuditNote.Type.EXTERNAL_REDIRECT,
            "NAV",
            dest,
            "Đã chặn chuyển hướng ngoài host khi tự động crawl: " + dest
        );
        CrawlState.BacktrackResult bt = state.recordRedirectBounce(pageUrl, dest, "1".equals(markFrom));
        String backUrl = bt.targetUrl;
        if (bt.exhausted || backUrl.isEmpty()) {
            state.setStatus(CrawlState.Status.STOPPED);
            if (logger != null) {
                logger.accept("[Redirect Guard] Chặn " + dest + " và hết URL an toàn -> Dừng crawl.");
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            String html = "<!doctype html><html><meta charset=\"utf-8\"><body>"
                + "<p>Đã chặn chuyển hướng ngoài host và hoàn tất hàng đợi.</p></body></html>";
            writeResponse(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (logger != null) {
            logger.accept("[Redirect Guard] Chặn ra ngoài (" + dest + "), lùi "
                + bt.levelsBack + " cấp -> " + backUrl);
        }
        if (onUiEvent != null) {
            onUiEvent.run();
        }
        writeResponse(out, 200, "text/html; charset=utf-8",
            buildBounceHtml(backUrl, dest).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(OutputStream out, Object obj) throws IOException {
        writeJsonStatus(out, 200, obj);
    }

    private static void writeJsonStatus(OutputStream out, int code, Object obj) throws IOException {
        writeResponse(out, code, "application/json; charset=utf-8",
            MiniJson.stringify(obj).getBytes(StandardCharsets.UTF_8));
    }

    private static String reasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            default: return "Status";
        }
    }

    private static void writeResponse(OutputStream out, int code, String contentType, byte[] body)
        throws IOException {
        writeResponseWithHeaders(out, code, contentType, "", body);
    }

    private static void writeResponseWithHeaders(
        OutputStream out, int code, String contentType, String extraHeaders, byte[] body
    ) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reasonPhrase(code) + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + (extraHeaders != null ? extraHeaders : "")
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static AuditNote.Type parseNoteType(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return AuditNote.Type.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return AuditNote.Type.REDIRECT_DEFERRED;
    }

    private static AuditNote.Severity parseSeverity(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return AuditNote.Severity.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return AuditNote.Severity.WARN;
    }

    public static String buildBounceHtml(String backUrl, String blockedDest) {
        String jsTarget = MiniJson.stringify(backUrl);
        String safeDest = blockedDest != null ? blockedDest.replace("<", "&lt;").replace(">", "&gt;") : "";
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<title>Returning to safe crawl page</title></head><body>"
            + "<p>Blocked off-host redirect to " + safeDest + ". Returning...</p>"
            + "<script>try{window.location.replace(" + jsTarget + ");}catch(e){window.location.href=" + jsTarget + ";}</script>"
            + "</body></html>";
    }

    private static int parseIntParam(String raw, int def) {
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static int parseIntObj(Object val, int def) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return val != null ? parseIntParam(String.valueOf(val), def) : def;
    }

    private static String extractQueryParam(String query, String targetKey) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (targetKey.equals(k)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    return v;
                }
            }
        }
        return "";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.mark(1);
                int next = in.read();
                if (next != '\n' && next != -1) {
                    in.reset();
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            bos.write(b);
            if (bos.size() > 32768) {
                break;
            }
        }
        if (b == -1 && bos.size() == 0) {
            return null;
        }
        return bos.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        if (off == len) {
            return buf;
        }
        byte[] trim = new byte[off];
        System.arraycopy(buf, 0, trim, 0, off);
        return trim;
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int size = Integer.parseInt(hex, 16);
            if (size < 0 || size > MAX_BODY_BYTES || total + size > MAX_BODY_BYTES) {
                throw new IOException("Chunked body exceeds maximum allowed size");
            }
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // drain trailing headers
                }
                break;
            }
            byte[] chunk = readExact(in, size);
            out.write(chunk);
            total += chunk.length;
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }
}
