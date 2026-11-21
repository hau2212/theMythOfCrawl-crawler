package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import burp.api.montoya.utilities.CompressionType;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ParamExtractor;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.PathNamer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Intercepts Proxy responses for allowed hosts:
 * 1) Injects /__myth_crawl/agent.js at the top of <head> (comment-safe, charset-aware,
 *    preserving CSP nonces when relaxCspWhileCrawling=false, and supporting in-scope iframes).
 * 2) Decodes gzip, deflate (zlib & raw nowrap=true), and Brotli (via Montoya CompressionUtils)
 *    so JS bundles feed StaticJsScanner reliably.
 * 3) Pre-filters static assets and offloads parameter extraction, Excel matching, and
 *    ActionGroupStore recording onto a bounded single-writer worker ExecutorService with
 *    Burp temp-file backing and capped heap previews.
 */
public final class HtmlAgentInjector implements ProxyResponseHandler {

    private static final int MAX_DECODE_BYTES = 12_000_000;
    private static final int MAX_PARAM_BODY_BYTES = 512 * 1024;

    private static final Pattern HEAD_OPEN = Pattern.compile("(<head\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_OPEN = Pattern.compile("(<html\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("(<body\\b[^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CSP = Pattern.compile(
        "<meta[^>]+http-equiv\\s*=\\s*[\"']?Content-Security-Policy[\"']?[^>]*>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CT_CHARSET = Pattern.compile(
        "(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)[\"']?");
    private static final Pattern META_CHARSET = Pattern.compile(
        "(?is)<meta\\b[^>]*\\bcharset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)[\"']?");
    private static final Pattern SCRIPT_NONCE = Pattern.compile(
        "(?is)<script\\b[^>]*\\bnonce\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')");
    private static final Pattern CSP_NONCE = Pattern.compile(
        "(?i)'nonce-([A-Za-z0-9+/=_-]+)'");

    private static final String SCRIPT_SRC = "/__myth_crawl/agent.js";

    private static final class DecodedBody {
        final String text;
        final Charset charset;

        DecodedBody(String text, Charset charset) {
            this.text = text;
            this.charset = charset;
        }
    }

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final ControlPlaneHandler controlPlaneHandler;
    private final StaticJsScanner jsScanner;
    private volatile ExcelSpecMatcher matcher;
    private volatile boolean agentArmed = true;
    private volatile boolean passiveCapture = false;
    private volatile Consumer<ExcelSpecMatcher> onMatcherUpdated;
    private final Runnable onUiUpdated;
    private final Consumer<String> logger;
    private final ExecutorService captureExecutor;

    public HtmlAgentInjector(
        CrawlConfig config,
        CrawlState state,
        ControlPlaneHandler controlPlaneHandler,
        StaticJsScanner jsScanner,
        ExcelSpecMatcher matcher,
        Runnable onUiUpdated,
        Consumer<String> logger
    ) {
        this(null, config, state, controlPlaneHandler, jsScanner, matcher, onUiUpdated, logger);
    }

    public HtmlAgentInjector(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        ControlPlaneHandler controlPlaneHandler,
        StaticJsScanner jsScanner,
        ExcelSpecMatcher matcher,
        Runnable onUiUpdated,
        Consumer<String> logger
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.controlPlaneHandler = controlPlaneHandler;
        this.jsScanner = jsScanner;
        this.matcher = matcher;
        this.onUiUpdated = onUiUpdated;
        this.logger = logger;
        this.captureExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2048),
            r -> {
                Thread t = new Thread(r, "MythCrawler-CaptureWorker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public void shutdown() {
        captureExecutor.shutdownNow();
    }

    public void setOnMatcherUpdated(Consumer<ExcelSpecMatcher> onMatcherUpdated) {
        this.onMatcherUpdated = onMatcherUpdated;
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.matcher = matcher;
        if (jsScanner != null) {
            jsScanner.setMatcher(matcher);
        }
        Consumer<ExcelSpecMatcher> cb = this.onMatcherUpdated;
        if (cb != null) {
            cb.accept(matcher);
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
        if (controlPlaneHandler != null) {
            controlPlaneHandler.setPassiveCapture(passiveCapture);
        }
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

        int msgId = interceptedResponse.messageId();
        boolean wasTracked = controlPlaneHandler != null && controlPlaneHandler.consumeTrackedInFlight(msgId);
        CrawlState.ActionContextSnapshot boundCtx = state.consumeMessageFullContext(msgId);
        if (wasTracked) {
            state.decrementInFlight();
        }

        HttpService svc = req.httpService();
        boolean hostAllowed = svc != null && config.isHostAllowed(svc.host());
        boolean replayHostAllowed = svc != null && state.isReplayHostAllowed(svc.host());
        if (svc == null || (!hostAllowed && !replayHostAllowed)) {
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        }

        CrawlState.Status st = state.getStatus();
        if (hostAllowed && st == CrawlState.Status.RUNNING) {
            state.noteAuthStatus(interceptedResponse.statusCode(), interceptedResponse.headerValue("Location"));
            if (state.getConsecutiveAuthRejects() >= 15) {
                state.setStatus(CrawlState.Status.PAUSED);
                log("CẢNH BÁO: 15 request liên tiếp trả 401/redirect login -> Tạm dừng crawl để bạn kiểm tra phiên đăng nhập!");
                notifyUi();
            }
        }

        // 1. Background JS scanning for hidden routes & Excel hints (supports gzip, deflate, brotli)
        MimeType mime = interceptedResponse.statedMimeType();
        MimeType inferred = interceptedResponse.inferredMimeType();
        String ct = interceptedResponse.headerValue("Content-Type");
        String ctLower = ct != null ? ct.toLowerCase(Locale.ROOT) : "";
        boolean isJs = (mime == MimeType.SCRIPT || inferred == MimeType.SCRIPT
            || ctLower.contains("javascript") || (reqPath != null && reqPath.toLowerCase(Locale.ROOT).contains(".js")));
        if (hostAllowed && isJs && config.isCaptureJsEndpoints()
            && interceptedResponse.statusCode() >= 200 && interceptedResponse.statusCode() < 400) {
            DecodedBody jsDecoded = decodeBodyText(interceptedResponse, ct);
            if (jsDecoded != null && !jsDecoded.text.isEmpty()) {
                jsScanner.submitJsScan(req.url(), jsDecoded.text);
            }
        }

        // 2. Capture & match against Excel API checklist off-thread
        boolean shouldCapture = hostAllowed && ((st == CrawlState.Status.RUNNING) || passiveCapture);
        if (shouldCapture && !req.method().equalsIgnoreCase("OPTIONS")) {
            enqueueApiCapture(req, interceptedResponse, boundCtx, st, mime, inferred);
        }

        // 2b. Intercept top-level HTTP 3xx off-host redirects during active auto-crawl
        int statusCode = interceptedResponse.statusCode();
        if (hostAllowed && st == CrawlState.Status.RUNNING && config.isAllowAutoRequests()
            && statusCode >= 300 && statusCode < 400 && isTopLevelDocumentNavigation(req, reqPath)) {
            HttpResponse redirectBounce = handleOffHostHttpRedirect(req, interceptedResponse, statusCode);
            if (redirectBounce != null) {
                return ProxyResponseReceivedAction.doNotIntercept(redirectBounce);
            }
        }

        // 3. Inject Browser DOM Crawler Agent into HTML pages & in-scope iframes (or when Replay Plan is active)
        String fetchDest = req.headerValue("Sec-Fetch-Dest");
        boolean isNonHtmlEmbed = fetchDest != null && (fetchDest.equalsIgnoreCase("object") || fetchDest.equalsIgnoreCase("embed"));
        boolean replayActive = state.hasActiveReplayPlan();
        boolean shouldInject = (st == CrawlState.Status.RUNNING) || replayActive || (agentArmed && st != CrawlState.Status.STOPPED);
        if (shouldInject && !isNonHtmlEmbed && interceptedResponse.statusCode() >= 200 && interceptedResponse.statusCode() < 300) {
            DecodedBody htmlDecoded = decodeBodyTextIfHtml(mime, inferred, ct, ctLower, interceptedResponse);
            if (htmlDecoded != null) {
                HttpResponse injected = injectAgentIntoHtml(interceptedResponse, htmlDecoded);
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

    private void enqueueApiCapture(
        HttpRequest req,
        InterceptedResponse resp,
        CrawlState.ActionContextSnapshot boundCtx,
        CrawlState.Status statusAtCapture,
        MimeType statedMime,
        MimeType inferredMime
    ) {
        String method = req.method() != null ? req.method().toUpperCase(Locale.ROOT) : "GET";
        String pathWithQuery = req.path() != null ? req.path() : "/";
        ExcelSpecMatcher currentMatcher = this.matcher;
        boolean excelLoaded = currentMatcher != null && currentMatcher.isLoaded();

        // Pre-filter static assets BEFORE copying any byte[] arrays
        if (isStaticAsset(pathWithQuery)) {
            if (!excelLoaded || currentMatcher.findMatch(method, pathWithQuery, config) == null) {
                return;
            }
        }

        HttpService svc = req.httpService();
        if (svc == null) {
            return;
        }
        String fullUrl = req.url() != null ? req.url() : pathWithQuery;
        String referer = req.headerValue("Referer");
        String reqCt = req.headerValue("Content-Type");
        String httpVer = req.httpVersion();
        HttpMode httpMode = (httpVer != null && httpVer.contains("2")) ? HttpMode.HTTP_2 : HttpMode.HTTP_1;
        int status = resp.statusCode();
        int respLen = resp.body().length();
        MimeType effectiveMime = (statedMime != null && statedMime != MimeType.NONE) ? statedMime : inferredMime;
        String mimeLabel = effectiveMime != null ? effectiveMime.name() : "";

        // Bound in-memory byte copies; persist full message to Burp temp file when available
        byte[] reqPreview = copyBoundedBytes(req.toByteArray(), CapturedApiEntry.MAX_INLINE_REQUEST_BYTES);
        byte[] reqBodyForParams = copyBoundedBytes(req.body(), MAX_PARAM_BODY_BYTES);
        byte[] respPreview = copyBoundedBytes(resp.toByteArray(), CapturedApiEntry.MAX_INLINE_RESPONSE_BYTES);
        HttpRequestResponse tempStore = null;
        if (api != null) {
            try {
                tempStore = HttpRequestResponse.httpRequestResponse(req, resp).copyToTempFile();
            } catch (Exception ignored) {
            }
        }

        final HttpRequestResponse finalTempStore = tempStore;
        final long captureGen = state.getResetGeneration();
        final long capturedAt = System.currentTimeMillis();
        final String host = svc.host();
        final int port = svc.port();
        final boolean https = svc.secure();

        captureExecutor.execute(() -> {
            if (state.getResetGeneration() != captureGen) {
                return;
            }
            processApiCaptureWorker(
                currentMatcher, excelLoaded, method, fullUrl, pathWithQuery,
                host, port, https, referer, reqCt,
                reqPreview, reqBodyForParams, respPreview,
                finalTempStore, httpMode, status, respLen, mimeLabel,
                boundCtx, statusAtCapture, capturedAt
            );
        });
    }

    private void processApiCaptureWorker(
        ExcelSpecMatcher currentMatcher,
        boolean excelLoaded,
        String method,
        String fullUrl,
        String pathWithQuery,
        String host,
        int port,
        boolean https,
        String referer,
        String reqCt,
        byte[] reqPreview,
        byte[] reqBodyForParams,
        byte[] respPreview,
        HttpRequestResponse tempStore,
        HttpMode httpMode,
        int status,
        int respLen,
        String mimeLabel,
        CrawlState.ActionContextSnapshot boundCtx,
        CrawlState.Status statusAtCapture,
        long capturedAt
    ) {
        String actionPath = boundCtx != null && boundCtx.actionPath != null
            ? boundCtx.actionPath : state.getCurrentActionPath();
        ActionGroup group = boundCtx != null ? boundCtx.group : null;

        if (statusAtCapture != CrawlState.Status.RUNNING) {
            if (referer != null && !referer.isEmpty()) {
                actionPath = PathNamer.buildActionPath(
                    referer, "manual", "", config.isNormalizeRouteIds(), config.getRouteRootLabel());
            } else {
                actionPath = config.getRouteRootLabel() + " > manual";
            }
            group = state.getActionGroups().openGroup(actionPath, "manual", "", referer != null ? referer : "");
        } else if (group == null) {
            group = state.getActionGroups().openGroup(actionPath, "", "", state.getCurrentPageUrl());
        }

        String excelStt = "";
        String excelSpecPath = "";
        boolean excelUpgraded = false;
        ExcelSpecMatcher.MatchResult excelMatch = null;

        if (excelLoaded && currentMatcher != null) {
            excelMatch = currentMatcher.matchAndRecord(
                method, fullUrl, pathWithQuery,
                host, port, https,
                actionPath, reqCt, reqPreview, reqBodyForParams, respPreview,
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

        Set<String> params = ParamExtractor.extractParameterKeys(pathWithQuery, reqCt, reqBodyForParams);
        String graphqlOp = ParamExtractor.extractGraphqlOperation(reqCt, reqBodyForParams);
        String endpointKey = buildEndpointKey(method, pathWithQuery, graphqlOp, excelMatch, config);
        String sha = CapturedApiEntry.sha256Hex(respPreview);

        CapturedApiEntry cap = new CapturedApiEntry(
            excelStt.isEmpty() ? "-" : excelStt,
            method, fullUrl, pathWithQuery, host, port, https,
            actionPath, reqPreview, respPreview, status, respLen, params, capturedAt,
            tempStore, httpMode, sha);

        ApiCall recordedCall = state.getActionGroups().recordCall(
            group, cap, endpointKey, graphqlOp, excelStt, excelSpecPath, excelLoaded, excelUpgraded, mimeLabel);

        if (excelMatch == null && !config.isStrictExcelOnly()) {
            state.addExtraCapture(cap);
            if (recordedCall != null && recordedCall.getHits() == 1) {
                log(String.format("[EXTRA] %s %s  <--  %s (%d params, HTTP %d)",
                    method, recordedCall.displayPath(), actionPath, params.size(), status));
            }
        }
        notifyUi();
    }

    private static byte[] copyBoundedBytes(ByteArray ba, int maxBytes) {
        if (ba == null) {
            return new byte[0];
        }
        int len = ba.length();
        if (len <= 0) {
            return new byte[0];
        }
        if (len <= maxBytes) {
            return ba.getBytes();
        }
        return ba.subArray(0, maxBytes).getBytes();
    }

    private static boolean isStaticAsset(String pathWithQuery) {
        if (pathWithQuery == null) {
            return false;
        }
        int q = pathWithQuery.indexOf('?');
        String cleanPath = (q >= 0 ? pathWithQuery.substring(0, q) : pathWithQuery).toLowerCase(Locale.ROOT);
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

    private DecodedBody decodeBodyText(InterceptedResponse resp, String contentType) {
        try {
            byte[] raw = resp.body().getBytes();
            if (raw == null || raw.length == 0) {
                return null;
            }
            String ce = resp.headerValue("Content-Encoding");
            byte[] decoded = decompressBody(raw, ce);
            if (decoded == null) {
                return null;
            }
            Charset charset = detectCharset(contentType, decoded);
            return new DecodedBody(new String(decoded, charset), charset);
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] decompressBody(byte[] raw, String contentEncoding) {
        if (contentEncoding == null || contentEncoding.trim().isEmpty()) {
            return raw;
        }
        String[] layers = contentEncoding.split(",");
        byte[] cur = raw;
        // Multiple encodings are listed in the order applied; decode in reverse order
        for (int i = layers.length - 1; i >= 0; i--) {
            String enc = layers[i].trim().toLowerCase(Locale.ROOT);
            if (enc.isEmpty() || "identity".equals(enc)) {
                continue;
            }
            try {
                if ("gzip".equals(enc) || "x-gzip".equals(enc)) {
                    cur = decompressGzip(cur);
                } else if ("deflate".equals(enc)) {
                    cur = decompressDeflate(cur);
                } else if ("br".equals(enc)) {
                    if (api != null) {
                        ByteArray out = api.utilities().compressionUtils()
                            .decompress(ByteArray.byteArray(cur), CompressionType.BROTLI);
                        cur = out != null ? out.getBytes() : null;
                        if (cur == null) {
                            return null;
                        }
                    } else {
                        return null;
                    }
                } else {
                    return null; // unsupported (e.g. zstd)
                }
            } catch (Exception ex) {
                return null;
            }
        }
        return cur;
    }

    private static Charset detectCharset(String contentType, byte[] decodedBytes) {
        if (contentType != null && !contentType.isEmpty()) {
            Matcher m = CT_CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        if (decodedBytes != null && decodedBytes.length > 0) {
            int headLen = Math.min(decodedBytes.length, 4096);
            String headAscii = new String(decodedBytes, 0, headLen, StandardCharsets.ISO_8859_1);
            Matcher mMeta = META_CHARSET.matcher(headAscii);
            if (mMeta.find()) {
                try {
                    return Charset.forName(mMeta.group(1).trim());
                } catch (Exception ignored) {
                }
            }
            Matcher mCtMeta = CT_CHARSET.matcher(headAscii);
            if (mCtMeta.find()) {
                try {
                    return Charset.forName(mCtMeta.group(1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static byte[] decompressGzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readAllLimited(gis);
        }
    }

    private static byte[] decompressDeflate(byte[] compressed) throws Exception {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return readAllLimited(iis);
        } catch (Exception zlibEx) {
            Inflater rawInflater = new Inflater(true);
            try (InflaterInputStream rawStream = new InflaterInputStream(new ByteArrayInputStream(compressed), rawInflater)) {
                return readAllLimited(rawStream);
            } finally {
                rawInflater.end();
            }
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
            if (total > MAX_DECODE_BYTES) {
                break;
            }
        }
        return bos.toByteArray();
    }

    private DecodedBody decodeBodyTextIfHtml(
        MimeType mime,
        MimeType inferred,
        String ct,
        String ctLower,
        InterceptedResponse resp
    ) {
        if (mime == MimeType.JSON || ctLower.contains("application/json")) {
            return null;
        }
        boolean ctHtml = ctLower.contains("text/html") || mime == MimeType.HTML || inferred == MimeType.HTML;
        if (!ctHtml) {
            return null;
        }
        DecodedBody decoded = decodeBodyText(resp, ct);
        if (decoded == null || decoded.text.length() < 15) {
            return null;
        }
        // Scan past leading whitespace/HTML comments (>1024B) up to 8192 chars
        String sample = decoded.text.substring(0, Math.min(decoded.text.length(), 8192)).toLowerCase(Locale.ROOT);
        if (sample.contains("<html") || sample.contains("<!doctype") || sample.contains("<head") || sample.contains("<body")) {
            return decoded;
        }
        return null;
    }

    private HttpResponse injectAgentIntoHtml(InterceptedResponse resp, DecodedBody decoded) {
        try {
            String html = decoded.text;
            if (html == null || html.isEmpty() || html.contains("data-myth-agent=\"1\"")) {
                return null;
            }

            if (config.isRelaxCspWhileCrawling()) {
                html = META_CSP.matcher(html).replaceAll("");
            }

            String nonceAttr = "";
            if (!config.isRelaxCspWhileCrawling()) {
                String nonce = extractPageNonce(resp, html);
                if (!nonce.isEmpty()) {
                    nonceAttr = " nonce=\"" + escapeAttr(nonce) + "\"";
                }
            }
            String scriptTag = "<script src=\"" + SCRIPT_SRC + "\" data-myth-agent=\"1\"" + nonceAttr + "></script>";
            String injectedHtml = insertScriptAtHeadTop(html, scriptTag);

            Charset outCharset = decoded.charset != null ? decoded.charset : StandardCharsets.UTF_8;
            HttpResponse updated = resp
                .withBody(ByteArray.byteArray(injectedHtml.getBytes(outCharset)))
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

    private static String extractPageNonce(InterceptedResponse resp, String html) {
        if (html != null) {
            Matcher m = SCRIPT_NONCE.matcher(html);
            if (m.find()) {
                String n = m.group(1) != null ? m.group(1) : m.group(2);
                if (n != null && !n.trim().isEmpty()) {
                    return n.trim();
                }
            }
        }
        String csp = resp != null ? resp.headerValue("Content-Security-Policy") : null;
        if (csp != null && !csp.isEmpty()) {
            Matcher mc = CSP_NONCE.matcher(csp);
            if (mc.find()) {
                return mc.group(1).trim();
            }
        }
        return "";
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String insertScriptAtHeadTop(String html, String scriptTag) {
        int[] headSpan = findTagOutsideComments(html, HEAD_OPEN);
        if (headSpan != null) {
            return html.substring(0, headSpan[1]) + "\n" + scriptTag + html.substring(headSpan[1]);
        }
        int[] htmlSpan = findTagOutsideComments(html, HTML_OPEN);
        if (htmlSpan != null) {
            return html.substring(0, htmlSpan[1]) + "\n<head>" + scriptTag + "</head>" + html.substring(htmlSpan[1]);
        }
        int[] bodySpan = findTagOutsideComments(html, BODY_OPEN);
        if (bodySpan != null) {
            return html.substring(0, bodySpan[0]) + scriptTag + "\n" + html.substring(bodySpan[0]);
        }
        return scriptTag + "\n" + html;
    }

    /** Finds the first regex match for an HTML tag that is NOT inside an <!-- ... --> comment. */
    private static int[] findTagOutsideComments(String html, Pattern tagPattern) {
        int len = html.length();
        int pos = 0;
        while (pos < len) {
            int commentStart = html.indexOf("<!--", pos);
            int segmentEnd = commentStart >= 0 ? commentStart : len;
            Matcher m = tagPattern.matcher(html);
            m.region(pos, segmentEnd);
            if (m.find()) {
                return new int[] { m.start(), m.end() };
            }
            if (commentStart < 0) {
                break;
            }
            int commentEnd = html.indexOf("-->", commentStart + 4);
            if (commentEnd < 0) {
                break;
            }
            pos = commentEnd + 3;
        }
        return null;
    }

    private static boolean isTopLevelDocumentNavigation(HttpRequest req, String reqPath) {
        String fetchDest = req.headerValue("Sec-Fetch-Dest");
        String fetchMode = req.headerValue("Sec-Fetch-Mode");
        if (fetchMode != null && !fetchMode.trim().isEmpty() && !"navigate".equalsIgnoreCase(fetchMode.trim())) {
            return false;
        }
        if (fetchDest != null && !fetchDest.trim().isEmpty()) {
            return "document".equalsIgnoreCase(fetchDest.trim());
        }
        if (isStaticAsset(reqPath)) {
            return false;
        }
        String accept = req.headerValue("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private HttpResponse handleOffHostHttpRedirect(
        HttpRequest req,
        InterceptedResponse resp,
        int statusCode
    ) {
        String locationHeader = resp.headerValue("Location");
        if (locationHeader == null || locationHeader.trim().isEmpty()) {
            return null;
        }
        try {
            String reqUrl = req.url() != null ? req.url() : "";
            URI base = URI.create(reqUrl);
            URI resolved = base.resolve(locationHeader.trim());
            String scheme = resolved.getScheme() != null ? resolved.getScheme().toLowerCase(Locale.ROOT) : "";
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            String targetHost = resolved.getHost();
            if (targetHost == null || config.isHostAllowed(targetHost)) {
                return null;
            }
            String destUrl = resolved.toString();
            ActionGroup grp = state.getCurrentGroup();
            if (grp == null) {
                grp = state.getActionGroups().openGroup(
                    state.getCurrentActionPath(), "", "", state.getCurrentPageUrl());
            }
            state.getActionGroups().recordStandaloneNote(
                grp,
                AuditNote.Severity.WARN,
                AuditNote.Type.EXTERNAL_REDIRECT,
                "NAV",
                destUrl,
                "HTTP " + statusCode + " chuyển hướng ngoài host: " + destUrl
            );
            CrawlState.BacktrackResult bt = state.recordRedirectBounce(reqUrl, destUrl, true);
            String backUrl = bt.targetUrl;
            String html;
            if (bt.exhausted || backUrl.isEmpty()) {
                state.setStatus(CrawlState.Status.STOPPED);
                log("[Redirect Guard] HTTP " + statusCode + " -> " + destUrl + " (Hết URL an toàn, dừng crawl)");
                html = "<!doctype html><html><meta charset=\"utf-8\"><body>"
                    + "<p>Đã chặn chuyển hướng ngoài host và hoàn tất hàng đợi.</p></body></html>";
            } else {
                log("[Redirect Guard] HTTP " + statusCode + " -> " + destUrl
                    + ", lùi " + bt.levelsBack + " cấp -> " + backUrl);
                html = LocalControlServer.buildBounceHtml(backUrl, destUrl);
            }
            notifyUi();
            return HttpResponse.httpResponse(ByteArray.byteArray(html.getBytes(StandardCharsets.UTF_8)))
                .withUpdatedHeader("Content-Type", "text/html; charset=utf-8")
                .withUpdatedHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        } catch (Exception ignored) {
            return null;
        }
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
