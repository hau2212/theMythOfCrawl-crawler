package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous Java background scanner that extracts hidden routes, API endpoints,
 * GraphQL operations, and gRPC-Web service paths from .js bundles passing through Burp Proxy
 * and cross-references MISSING Excel entries via Aho-Corasick prefix matching.
 */
public final class StaticJsScanner {

    private static final Pattern QUOTED_PATH = Pattern.compile(
        "[\"'`](/(?:[a-zA-Z0-9_\\-.:{}*]+/)*[a-zA-Z0-9_\\-.:{}*]+)[\"'`]");

    private static final Pattern GRAPHQL_OP_DEF = Pattern.compile(
        "\\b(query|mutation|subscription)\\s+([A-Za-z_][A-Za-z0-9_]{2,48})\\s*[({]");

    private static final Pattern STATIC_NOISE = Pattern.compile(
        ".*\\.(png|jpe?g|gif|svg|ico|webp|css|scss|less|woff2?|ttf|eot|map|mp4|mp3|pdf)$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern MIME_OR_NOISE = Pattern.compile(
        "^/(?:application|text|image|audio|video|font|multipart|node_modules|webpack|__webpack)/.*",
        Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor;
    private final CrawlConfig config;
    private final CrawlState state;
    private volatile ExcelSpecMatcher matcher;
    private volatile Runnable onUpdateCallback;

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

    public void setOnUpdateCallback(Runnable cb) {
        this.onUpdateCallback = cb;
    }

    public void submitJsScan(String jsUrl, String jsBody) {
        if (jsBody == null || jsBody.isEmpty() || !config.isCaptureJsEndpoints()) {
            return;
        }
        // Cap single bundle scan at 3MB to keep memory predictable on huge vendor chunks
        String slice = jsBody.length() > 3_000_000 ? jsBody.substring(0, 3_000_000) : jsBody;
        executor.execute(() -> scanBody(jsUrl, slice));
    }

    private void scanBody(String jsUrl, String jsBody) {
        try {
            ExcelSpecMatcher currentMatcher = this.matcher;
            if (currentMatcher != null && currentMatcher.isLoaded()) {
                currentMatcher.attachJsHintIfMentioned(jsBody, jsUrl);
            }

            String origin = extractOrigin(jsUrl);
            String shortName = shortJsName(jsUrl);
            Matcher m = QUOTED_PATH.matcher(jsBody);
            boolean changed = false;
            int count = 0;
            while (m.find() && count < 600) {
                String path = m.group(1);
                if (path.length() < 2 || path.length() > 140) {
                    continue;
                }
                if (path.startsWith("//") || STATIC_NOISE.matcher(path).matches()
                    || MIME_OR_NOISE.matcher(path).matches()) {
                    continue;
                }
                if (path.startsWith("/api/") || path.contains("/v1/") || path.contains("/v2/")
                    || path.contains("/v3/") || path.contains("/graphql") || path.contains("/rest/")
                    || path.matches("^/[a-zA-Z0-9_.]+\\.[A-Z][a-zA-Z0-9_]+/[A-Z][a-zA-Z0-9_]+$")) {
                    state.addDiscoveredJsEndpoint(path + "  (from " + shortName + ")");
                    changed = true;
                } else if (!origin.isEmpty() && !path.contains("{") && !path.contains(":")
                    && !path.contains("*") && !path.contains("..") && !path.endsWith(".js")) {
                    if (config.isDeepCrawlMode() && state.enqueueJsRouteIfEligible(path, jsUrl, config)) {
                        changed = true;
                    }
                    state.addDiscoveredJsEndpoint(path + "  [route] (from " + shortName + ")");
                }
                count++;
            }

            // Extract GraphQL operation definitions embedded in JS bundles
            if (jsBody.contains("query ") || jsBody.contains("mutation ") || jsBody.contains("subscription ")) {
                Matcher gm = GRAPHQL_OP_DEF.matcher(jsBody);
                int gCount = 0;
                while (gm.find() && gCount < 100) {
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
