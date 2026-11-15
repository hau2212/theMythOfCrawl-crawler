package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ParamExtractor;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.PathNamer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Intercepts Proxy responses for allowed hosts:
 * 1) Injects /__myth_crawl/agent.js at the top of <head> for target HTML documents
 *    (and relaxes CSP headers while crawling so Firefox executes the agent reliably).
 * 2) Feeds .js bundles into StaticJsScanner asynchronously.
 * 3) Matches API calls strictly against the loaded Excel checklist (STT + Method + Path),
 *    preserving first capture, upgrading fuller parameter sets, and logging Param Diff Notes.
 */
public final class HtmlAgentInjector implements ProxyResponseHandler {

    private static final Pattern HEAD_OPEN = Pattern.compile("(<head\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_OPEN = Pattern.compile("(<html\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("(<body\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CSP = Pattern.compile(
        "<meta[^>]+http-equiv\\s*=\\s*[\"']?Content-Security-Policy[\"']?[^>]*>",
        Pattern.CASE_INSENSITIVE);
    private static final String SCRIPT_TAG = "<script src=\"/__myth_crawl/agent.js\" data-myth-agent=\"1\"></script>";

    private final CrawlConfig config;
    private final CrawlState state;
    private final ControlPlaneHandler controlPlaneHandler;
    private final StaticJsScanner jsScanner;
    private volatile ExcelSpecMatcher matcher;
    private volatile boolean agentArmed = true;
    private volatile boolean passiveCapture = false;
    private final Runnable onUiUpdated;
    private final Consumer<String> logger;

    public HtmlAgentInjector(
        CrawlConfig config,
        CrawlState state,
        ControlPlaneHandler controlPlaneHandler,
        StaticJsScanner jsScanner,
        ExcelSpecMatcher matcher,
        Runnable onUiUpdated,
        Consumer<String> logger
    ) {
        this.config = config;
        this.state = state;
        this.controlPlaneHandler = controlPlaneHandler;
        this.jsScanner = jsScanner;
        this.matcher = matcher;
        this.onUiUpdated = onUiUpdated;
        this.logger = logger;
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.matcher = matcher;
        if (jsScanner != null) {
            jsScanner.setMatcher(matcher);
        }
    }

    public void setAgentArmed(boolean armed) {
        this.agentArmed = armed;
        if (controlPlaneHandler != null) {
            controlPlaneHandler.setAgentArmed(armed);
        }
    }

    public boolean isAgentArmed() {
        return agentArmed;
    }

    public void setPassiveCapture(boolean passiveCapture) {
        this.passiveCapture = passiveCapture;
    }

    public boolean isPassiveCapture() {
        return passiveCapture;
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse interceptedResponse) {
        HttpRequest req = interceptedResponse.initiatingRequest();
        if (req == null) {
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        }

        String reqPath = req.path();
        if (reqPath != null && reqPath.startsWith("/__myth_crawl/")) {
            return ProxyResponseReceivedAction.doNotIntercept(interceptedResponse);
        }

        if (controlPlaneHandler.consumeTrackedInFlight(interceptedResponse.messageId())) {
            state.decrementInFlight();
        }

        HttpService svc = req.httpService();
        if (svc == null || !config.isHostAllowed(svc.host())) {
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        }

        CrawlState.Status st = state.getStatus();
        if (st == CrawlState.Status.RUNNING) {
            state.noteAuthStatus(interceptedResponse.statusCode(), interceptedResponse.headerValue("Location"));
            if (state.getConsecutiveAuthRejects() >= 15) {
                state.setStatus(CrawlState.Status.PAUSED);
                log("CẢNH BÁO: 15 request liên tiếp trả 401/redirect login -> Tạm dừng crawl để bạn kiểm tra phiên đăng nhập!");
                notifyUi();
            }
        }

        // 1. Background JS scanning for hidden routes & Excel hints
        MimeType mime = interceptedResponse.statedMimeType();
        MimeType inferred = interceptedResponse.inferredMimeType();
        String ct = interceptedResponse.headerValue("Content-Type");
        String ctLower = ct != null ? ct.toLowerCase() : "";
        boolean isJs = (mime == MimeType.SCRIPT || inferred == MimeType.SCRIPT
            || ctLower.contains("javascript") || (reqPath != null && reqPath.toLowerCase().contains(".js")));
        if (isJs && config.isCaptureJsEndpoints() && interceptedResponse.statusCode() >= 200 && interceptedResponse.statusCode() < 400) {
            String jsText = decodeBodyText(interceptedResponse);
            if (!jsText.isEmpty()) {
                jsScanner.submitJsScan(req.url(), jsText);
            }
        }

        // 2. Capture & match against Excel API checklist
        boolean shouldCapture = (st == CrawlState.Status.RUNNING) || passiveCapture;
        if (shouldCapture && !req.method().equalsIgnoreCase("OPTIONS")) {
            processApiCapture(req, interceptedResponse);
        }

        // 3. Inject Browser DOM Crawler Agent into top-level HTML pages when armed or running (skip iframes)
        String fetchDest = req.headerValue("Sec-Fetch-Dest");
        boolean isIframe = fetchDest != null && (fetchDest.equalsIgnoreCase("iframe") || fetchDest.equalsIgnoreCase("frame"));
        boolean shouldInject = (st == CrawlState.Status.RUNNING) || (agentArmed && st != CrawlState.Status.STOPPED);
        if (shouldInject && !isIframe && interceptedResponse.statusCode() >= 200 && interceptedResponse.statusCode() < 300) {
            String decodedHtml = decodeBodyTextIfHtml(mime, inferred, ctLower, interceptedResponse);
            if (decodedHtml != null) {
                HttpResponse injected = injectAgentIntoHtml(interceptedResponse, decodedHtml);
                if (injected != null) {
                    log("[Agent] Đã bơm script vào trang: " + req.url());
                    return ProxyResponseReceivedAction.continueWith(injected);
                }
            }
        }

        return ProxyResponseReceivedAction.continueWith(interceptedResponse);
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse interceptedResponse) {
        return ProxyResponseToBeSentAction.continueWith(interceptedResponse);
    }

    private void processApiCapture(HttpRequest req, InterceptedResponse resp) {
        ExcelSpecMatcher currentMatcher = this.matcher;
        boolean excelLoaded = currentMatcher != null && currentMatcher.isLoaded();
        String method = req.method() != null ? req.method().toUpperCase() : "GET";
        String pathWithQuery = req.path() != null ? req.path() : "/";
        String fullUrl = req.url() != null ? req.url() : pathWithQuery;

        String actionPath = state.consumeMessageActionPath(resp.messageId());
        ActionGroup group = state.consumeMessageGroup(resp.messageId());
        if (state.getStatus() != CrawlState.Status.RUNNING) {
            String ref = req.headerValue("Referer");
            if (ref != null && !ref.isEmpty()) {
                actionPath = PathNamer.buildActionPath(
                    ref, "manual", "", config.isNormalizeRouteIds(), config.getRouteRootLabel());
            } else {
                actionPath = config.getRouteRootLabel() + " > manual";
            }
            group = state.getActionGroups().openGroup(actionPath, "manual", "", ref != null ? ref : "");
        } else if (group == null) {
            group = state.getActionGroups().openGroup(actionPath, "", "", state.getCurrentPageUrl());
        }

        HttpService svc = req.httpService();
        String reqCt = req.headerValue("Content-Type");
        byte[] reqBytes = req.toByteArray().getBytes();
        byte[] reqBodyBytes = req.body().getBytes();
        byte[] respBytes = resp.toByteArray().getBytes();
        int status = resp.statusCode();
        int respLen = resp.body().length();

        String excelStt = "";
        String excelSpecPath = "";
        boolean excelUpgraded = false;
        ExcelSpecMatcher.MatchResult excelMatch = null;

        if (excelLoaded) {
            excelMatch = currentMatcher.matchAndRecord(
                method, fullUrl, pathWithQuery,
                svc.host(), svc.port(), svc.secure(),
                actionPath, reqCt, reqBytes, reqBodyBytes, respBytes,
                status, respLen, config);
            if (excelMatch != null) {
                excelStt = excelMatch.entry.stt;
                excelSpecPath = excelMatch.entry.rawSpecPath;
                excelUpgraded = (excelMatch.outcome == ApiSpecEntry.RecordOutcome.UPGRADED_SUPERSET);
                switch (excelMatch.outcome) {
                    case FIRST_MATCH:
                        log(String.format("[STT %s] MATCHED %s %s  <--  %s (%d params)",
                            excelMatch.entry.stt, method, excelMatch.entry.normalizedSpecPath, actionPath,
                            excelMatch.entry.getPrimaryCapture().paramCount()));
                        break;
                    case UPGRADED_SUPERSET:
                        log(String.format("[STT %s] UPGRADED %s %s  <--  %s (%d params - đầy đủ hơn)",
                            excelMatch.entry.stt, method, excelMatch.entry.normalizedSpecPath, actionPath,
                            excelMatch.entry.getPrimaryCapture().paramCount()));
                        break;
                    case ADDED_DIFF_VARIANT:
                        log(String.format("[STT %s] PARAM DIFF %s %s  <--  %s",
                            excelMatch.entry.stt, method, excelMatch.entry.normalizedSpecPath, actionPath));
                        break;
                    case IGNORED_SAME_OR_SUBSET:
                        break;
                }
            }
        }

        boolean isStatic = isStaticAsset(pathWithQuery);
        if (excelMatch == null && isStatic) {
            return;
        }

        Set<String> params = ParamExtractor.extractParameterKeys(pathWithQuery, reqCt, reqBodyBytes);
        String graphqlOp = ParamExtractor.extractGraphqlOperation(reqCt, reqBodyBytes);
        String endpointKey = buildEndpointKey(method, pathWithQuery, graphqlOp, excelMatch, config);

        CapturedApiEntry cap = new CapturedApiEntry(
            excelStt.isEmpty() ? "-" : excelStt,
            method, fullUrl, pathWithQuery, svc.host(), svc.port(), svc.secure(),
            actionPath, reqBytes, respBytes, status, respLen, params, System.currentTimeMillis());

        // Always record non-static APIs inside ActionGroupStore so all APIs fired by one button
        // stay grouped together and any diff/error/extra NOTE is visible immediately.
        ApiCall recordedCall = state.getActionGroups().recordCall(
            group, cap, endpointKey, graphqlOp, excelStt, excelSpecPath, excelLoaded, excelUpgraded);

        if (excelMatch == null && !config.isStrictExcelOnly()) {
            state.addExtraCapture(cap);
            if (recordedCall != null && recordedCall.getHits() == 1) {
                log(String.format("[EXTRA] %s %s  <--  %s (%d params, HTTP %d)",
                    method, recordedCall.displayPath(), actionPath, params.size(), status));
            }
        }
        notifyUi();
    }

    private static boolean isStaticAsset(String pathWithQuery) {
        if (pathWithQuery == null) {
            return false;
        }
        int q = pathWithQuery.indexOf('?');
        String cleanPath = (q >= 0 ? pathWithQuery.substring(0, q) : pathWithQuery).toLowerCase();
        return cleanPath.matches(".*\\.(css|js|mjs|png|jpe?g|gif|svg|ico|webp|avif|woff2?|ttf|eot|otf|map|mp3|mp4|webm|wasm)$");
    }

    private static String buildEndpointKey(
        String method,
        String pathWithQuery,
        String graphqlOp,
        ExcelSpecMatcher.MatchResult excelMatch,
        CrawlConfig config
    ) {
        String basePath;
        if (excelMatch != null) {
            basePath = excelMatch.entry.normalizedSpecPath;
        } else {
            boolean stripSlash = config == null || config.isIgnoreTrailingSlash();
            String norm = ExcelSpecMatcher.normalizeSpecPath(pathWithQuery, stripSlash);
            StringBuilder sb = new StringBuilder();
            if ("/".equals(norm)) {
                sb.append('/');
            } else {
                for (String seg : norm.substring(1).split("/")) {
                    if (seg.isEmpty()) {
                        continue;
                    }
                    sb.append('/').append(PathNamer.isDynamicIdSegment(seg) ? "{id}" : seg);
                }
                if (sb.length() == 0) {
                    sb.append('/');
                }
            }
            basePath = sb.toString();
        }
        return graphqlOp != null && !graphqlOp.isEmpty()
            ? (method + " " + basePath + " [op:" + graphqlOp + "]")
            : (method + " " + basePath);
    }

    private static String decodeBodyText(InterceptedResponse resp) {
        try {
            byte[] raw = resp.body().getBytes();
            if (raw == null || raw.length == 0) {
                return "";
            }
            String ce = resp.headerValue("Content-Encoding");
            String ceLower = ce != null ? ce.trim().toLowerCase() : "";
            byte[] decoded = raw;
            if (ceLower.contains("gzip")) {
                decoded = decompressGzip(raw);
            } else if (ceLower.contains("deflate")) {
                decoded = decompressDeflate(raw);
            } else if (!ceLower.isEmpty() && !"identity".equals(ceLower)) {
                return ""; // Unsupported compression (e.g. br/zstd)
            }
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] decompressGzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readAllLimited(gis);
        }
    }

    private static byte[] decompressDeflate(byte[] compressed) throws Exception {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return readAllLimited(iis);
        }
    }

    private static byte[] readAllLimited(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 12_000_000) {
                break;
            }
        }
        return bos.toByteArray();
    }

    private static String decodeBodyTextIfHtml(MimeType mime, MimeType inferred, String ctLower, InterceptedResponse resp) {
        if (mime == MimeType.JSON || ctLower.contains("application/json")) {
            return null;
        }
        boolean ctHtml = ctLower.contains("text/html") || mime == MimeType.HTML || inferred == MimeType.HTML;
        if (!ctHtml) {
            return null;
        }
        String html = decodeBodyText(resp);
        if (html.length() < 15) {
            return null;
        }
        String sample = html.substring(0, Math.min(html.length(), 1024)).toLowerCase();
        if (sample.contains("<html") || sample.contains("<!doctype") || sample.contains("<head") || sample.contains("<body")) {
            return html;
        }
        return null;
    }

    private HttpResponse injectAgentIntoHtml(InterceptedResponse resp, String html) {
        try {
            if (html == null || html.isEmpty() || html.contains("data-myth-agent=\"1\"")) {
                return null;
            }

            if (config.isRelaxCspWhileCrawling()) {
                html = META_CSP.matcher(html).replaceAll("");
            }

            String injectedHtml = insertScriptAtHeadTop(html);
            HttpResponse updated = resp
                .withBody(ByteArray.byteArray(injectedHtml.getBytes(StandardCharsets.UTF_8)))
                .withRemovedHeader("Content-Encoding");
            if (config.isRelaxCspWhileCrawling()) {
                updated = updated
                    .withRemovedHeader("Content-Security-Policy")
                    .withRemovedHeader("Content-Security-Policy-Report-Only")
                    .withRemovedHeader("X-Frame-Options");
            }
            return updated;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String insertScriptAtHeadTop(String html) {
        Matcher head = HEAD_OPEN.matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + "\n" + SCRIPT_TAG + html.substring(head.end());
        }
        Matcher htmlTag = HTML_OPEN.matcher(html);
        if (htmlTag.find()) {
            return html.substring(0, htmlTag.end()) + "\n<head>" + SCRIPT_TAG + "</head>" + html.substring(htmlTag.end());
        }
        Matcher body = BODY_OPEN.matcher(html);
        if (body.find()) {
            return html.substring(0, body.start()) + SCRIPT_TAG + "\n" + html.substring(body.start());
        }
        return SCRIPT_TAG + "\n" + html;
    }

    private void notifyUi() {
        if (onUiUpdated != null) {
            onUiUpdated.run();
        }
    }

    private void log(String msg) {
        if (logger != null) {
            logger.accept(msg);
        }
    }
}
