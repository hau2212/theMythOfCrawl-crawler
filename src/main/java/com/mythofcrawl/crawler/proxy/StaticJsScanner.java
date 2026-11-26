package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.core.guardrail.LiveIdPool;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous Java background scanner that extracts hidden routes, API endpoints,
 * ES6 template-literal endpoints, router config paths, inline SSR state (__NEXT_DATA__ / __NUXT__),
 * GraphQL operations, and gRPC-Web service paths from .js bundles and HTML inline scripts,
 * concretizing parameterized routes with {@link LiveIdPool} as IDs are harvested.
 */
public final class StaticJsScanner {

    private static final int MAX_JS_SCAN_CHARS = 6_000_000;
    private static final int MAX_FINDINGS_PER_BUNDLE = 2500;
    private static final int MAX_PENDING_TEMPLATES = 300;

    private static final Pattern QUOTED_OR_TEMPLATE_PATH = Pattern.compile(
        "[\"'`](?:\\$\\{[^}`\"'\\r\\n]{1,48}\\})?(/(?:[a-zA-Z0-9_\\-.:{}*]+|\\$\\{[^}`\"'\\r\\n]{1,48}\\})(?:/(?:[a-zA-Z0-9_\\-.:{}*]+|\\$\\{[^}`\"'\\r\\n]{1,48}\\}))*)[\"'`]");

    private static final Pattern ROUTER_PATH_PROP = Pattern.compile(
        "\\b(?:path|to|route|href)\\s*:\\s*[\"'`](/?[a-zA-Z0-9_\\-.:{}]+(?:/[a-zA-Z0-9_\\-.:{}]+)*)[\"'`]");

    private static final Pattern FULL_URL_ENDPOINT = Pattern.compile(
        "[\"'`](https?://([a-zA-Z0-9.-]+(?::\\d+)?)(/[a-zA-Z0-9_\\-./:{}*]+))[\"'`]");

    private static final Pattern TEMPLATE_EXPR = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final Pattern INLINE_SCRIPT_BLOCK = Pattern.compile(
        "(?is)<script\\b[^>]*>(.*?)</script>");

    private static final Pattern GRAPHQL_OP_DEF = Pattern.compile(
        "\\b(query|mutation|subscription)\\s+([A-Za-z_][A-Za-z0-9_]{2,48})\\s*[({]");

    private static final Pattern STATIC_NOISE = Pattern.compile(
        ".*\\.(png|jpe?g|gif|svg|ico|webp|css|scss|less|woff2?|ttf|eot|map|mp4|mp3|pdf)$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern MIME_OR_NOISE = Pattern.compile(
        "^/(?:application|text|image|audio|video|font|multipart|node_modules|webpack|__webpack|_next/static|assets/|static/).*",
        Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor;
    private final CrawlConfig config;
    private final CrawlState state;
    private volatile ExcelSpecMatcher matcher;
    private volatile LiveIdPool liveIdPool;
    private volatile Runnable onUpdateCallback;
    private final Object templateLock = new Object();
    // routeTemplate -> sourceJsUrl
    private final Map<String, String> pendingParameterizedRoutes = new LinkedHashMap<>();

    public StaticJsScanner(CrawlConfig config, CrawlState state, ExcelSpecMatcher matcher) {
        this.config = config;
        this.state = state;
        this.matcher = matcher;
        this.executor = new ThreadPoolExecutor(
            1,
            2,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "MythCrawler-JsScanner");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.matcher = matcher;
    }

    public void setLiveIdPool(LiveIdPool liveIdPool) {
        this.liveIdPool = liveIdPool;
    }

    public void setOnUpdateCallback(Runnable cb) {
        this.onUpdateCallback = cb;
    }

    public void submitJsScan(String jsUrl, String jsBody) {
        if (jsBody == null || jsBody.isEmpty() || !config.isCaptureJsEndpoints()) {
            return;
        }
        String slice = jsBody.length() > MAX_JS_SCAN_CHARS ? jsBody.substring(0, MAX_JS_SCAN_CHARS) : jsBody;
        executor.execute(() -> scanBody(jsUrl, slice));
    }

    /**
     * Extracts inline {@code <script>} blocks (including Next.js {@code __NEXT_DATA__} and Nuxt state)
     * from HTML documents and scans them asynchronously for routes and API endpoints.
     */
    public void submitHtmlInlineScan(String pageUrl, String htmlBody) {
        if (htmlBody == null || htmlBody.isEmpty() || !config.isCaptureJsEndpoints()) {
            return;
        }
        String slice = htmlBody.length() > 1_500_000 ? htmlBody.substring(0, 1_500_000) : htmlBody;
        executor.execute(() -> {
            StringBuilder scripts = new StringBuilder();
            Matcher sm = INLINE_SCRIPT_BLOCK.matcher(slice);
            int blocks = 0;
            while (sm.find() && blocks < 40 && scripts.length() < 512_000) {
                String chunk = sm.group(1);
                if (chunk != null && chunk.length() >= 8 && !chunk.contains("__MYTH_CRAWLER_LOADED__")) {
                    scripts.append(chunk).append('\n');
                    blocks++;
                }
            }
            if (scripts.length() > 0) {
                scanBody(pageUrl, scripts.toString());
            }
        });
    }

    /**
     * Re-evaluates previously discovered parameterized JS routes (e.g. {@code /orders/{orderId}})
     * whenever {@link LiveIdPool} harvests fresh IDs from backend JSON responses.
     */
    public void tryConcretizePendingRoutes() {
        LiveIdPool pool = this.liveIdPool;
        if (pool == null || !config.isDeepCrawlMode()) {
            return;
        }
        List<Map.Entry<String, String>> snapshot;
        synchronized (templateLock) {
            if (pendingParameterizedRoutes.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pendingParameterizedRoutes.entrySet());
        }
        boolean anyEnqueued = false;
        for (Map.Entry<String, String> entry : snapshot) {
            if (state.enqueueJsRouteIfEligible(entry.getKey(), entry.getValue(), config, pool)) {
                anyEnqueued = true;
                synchronized (templateLock) {
                    pendingParameterizedRoutes.remove(entry.getKey());
                }
            }
        }
        if (anyEnqueued && onUpdateCallback != null) {
            onUpdateCallback.run();
        }
    }

    private void scanBody(String jsUrl, String jsBody) {
        try {
            ExcelSpecMatcher currentMatcher = this.matcher;
            if (currentMatcher != null && currentMatcher.isLoaded()) {
                currentMatcher.attachJsHintIfMentioned(jsBody, jsUrl);
            }

            String origin = extractOrigin(jsUrl);
            String shortName = shortJsName(jsUrl);
            boolean changed = false;
            int count = 0;

            // 1. Quoted paths & ES6 template-literal paths (`${base}/orders/${order.id}`)
            Matcher m = QUOTED_OR_TEMPLATE_PATH.matcher(jsBody);
            while (m.find() && count < MAX_FINDINGS_PER_BUNDLE) {
                String rawPath = m.group(1);
                String path = normalizeTemplatePlaceholders(rawPath);
                if (recordDiscoveredPath(path, origin, jsUrl, shortName)) {
                    changed = true;
                    count++;
                }
            }

            // 2. Router object definitions (e.g. { path: "orders/:orderId" } in React/Vue Router)
            Matcher rm = ROUTER_PATH_PROP.matcher(jsBody);
            int rCount = 0;
            while (rm.find() && rCount < 400) {
                String rp = rm.group(1);
                if (rp == null || rp.length() < 2 || rp.length() > 120) {
                    continue;
                }
                String normRp = rp.startsWith("/") ? rp : ("/" + rp);
                if (recordDiscoveredPath(normRp, origin, jsUrl, shortName)) {
                    changed = true;
                    rCount++;
                }
            }

            // 3. Full in-scope subdomain URLs (e.g. https://api.example.com/v1/orders)
            Matcher fm = FULL_URL_ENDPOINT.matcher(jsBody);
            int fCount = 0;
            while (fm.find() && fCount < 300) {
                String hostWithPort = fm.group(2);
                String subPath = fm.group(3);
                int colon = hostWithPort.indexOf(':');
                String hostOnly = colon > 0 ? hostWithPort.substring(0, colon) : hostWithPort;
                if (config.isHostAllowed(hostOnly)) {
                    String cleanSubPath = normalizeTemplatePlaceholders(subPath);
                    if (recordDiscoveredPath(cleanSubPath, extractOrigin(fm.group(1)), fm.group(1), shortName)) {
                        changed = true;
                        fCount++;
                    }
                }
            }

            // 4. Extract GraphQL operation definitions embedded in JS bundles
            if (jsBody.contains("query ") || jsBody.contains("mutation ") || jsBody.contains("subscription ")) {
                Matcher gm = GRAPHQL_OP_DEF.matcher(jsBody);
                int gCount = 0;
                while (gm.find() && gCount < 200) {
                    state.addDiscoveredJsEndpoint(
                        "[GraphQL " + gm.group(1) + "] " + gm.group(2) + "  (from " + shortName + ")");
                    changed = true;
                    gCount++;
                }
            }

            if (changed && onUpdateCallback != null) {
                onUpdateCallback.run();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean recordDiscoveredPath(String path, String origin, String sourceUrl, String shortName) {
        if (path == null || path.length() < 2 || path.length() > 150) {
            return false;
        }
        if (path.startsWith("//") || STATIC_NOISE.matcher(path).matches()
            || MIME_OR_NOISE.matcher(path).matches()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/api/") || lower.contains("/v1/") || lower.contains("/v2/")
            || lower.contains("/v3/") || lower.contains("/v4/") || lower.contains("/graphql")
            || lower.contains("/rest/") || lower.contains("/rpc/") || lower.contains("/internal/")
            || path.matches("^/[a-zA-Z0-9_.]+\\.[A-Z][a-zA-Z0-9_]+/[A-Z][a-zA-Z0-9_]+$")) {
            state.addDiscoveredJsEndpoint(path + "  (from " + shortName + ")");
            return true;
        }
        if (!origin.isEmpty() && !path.contains("*") && !path.contains("..") && !lower.endsWith(".js")) {
            boolean hasParam = path.contains("{") || path.contains(":");
            if (config.isDeepCrawlMode()) {
                if (!hasParam) {
                    state.enqueueJsRouteIfEligible(path, sourceUrl, config, liveIdPool);
                } else {
                    boolean enqueuedNow = state.enqueueJsRouteIfEligible(path, sourceUrl, config, liveIdPool);
                    if (!enqueuedNow) {
                        rememberParameterizedTemplate(path, sourceUrl);
                    }
                }
            }
            state.addDiscoveredJsEndpoint(path + "  [route] (from " + shortName + ")");
            return true;
        }
        return false;
    }

    private void rememberParameterizedTemplate(String routeTemplate, String sourceUrl) {
        synchronized (templateLock) {
            if (pendingParameterizedRoutes.size() < MAX_PENDING_TEMPLATES) {
                pendingParameterizedRoutes.putIfAbsent(routeTemplate, sourceUrl);
            }
        }
    }

    static String normalizeTemplatePlaceholders(String raw) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        Matcher m = TEMPLATE_EXPR.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String expr = m.group(1).trim();
            String token = extractCleanParamName(expr);
            m.appendReplacement(sb, Matcher.quoteReplacement("{" + token + "}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String extractCleanParamName(String expr) {
        if (expr == null || expr.isEmpty()) {
            return "id";
        }
        String cleaned = expr.replaceAll("[^a-zA-Z0-9_.]", "");
        int dot = cleaned.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < cleaned.length()) {
            String prop = cleaned.substring(dot + 1);
            String obj = cleaned.substring(0, dot);
            int prevDot = obj.lastIndexOf('.');
            if (prevDot >= 0) {
                obj = obj.substring(prevDot + 1);
            }
            if ("id".equalsIgnoreCase(prop) && !obj.isEmpty() && obj.length() <= 20) {
                return obj + "Id";
            }
            cleaned = prop;
        }
        if (cleaned.isEmpty() || cleaned.length() > 32) {
            return "id";
        }
        return cleaned;
    }

    private static String extractOrigin(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() != null && u.getHost() != null) {
                String port = u.getPort() > 0 ? (":" + u.getPort()) : "";
                return u.getScheme() + "://" + u.getHost() + port;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String shortJsName(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 && slash + 1 < clean.length() ? clean.substring(slash + 1) : clean;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
