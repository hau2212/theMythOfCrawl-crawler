package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous Java background scanner that extracts hidden routes and API endpoints
 * from .js bundles / chunks passing through Burp Proxy and cross-references MISSING
 * Excel entries so pentesters know where hidden endpoints live in the frontend code.
 */
public final class StaticJsScanner {

    private static final Pattern QUOTED_PATH = Pattern.compile(
        "[\"'`](/(?:[a-zA-Z0-9_\\-.:{}*]+/)*[a-zA-Z0-9_\\-.:{}*]+)[\"'`]");

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
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MythCrawler-JsScanner");
            t.setDaemon(true);
            return t;
        });
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
        executor.submit(() -> scanBody(jsUrl, slice));
    }

    private void scanBody(String jsUrl, String jsBody) {
        try {
            ExcelSpecMatcher currentMatcher = this.matcher;
            if (currentMatcher != null && currentMatcher.isLoaded()) {
                currentMatcher.attachJsHintIfMentioned(jsBody, jsUrl);
            }

            String origin = extractOrigin(jsUrl);
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
                    || path.contains("/v3/") || path.contains("/graphql")) {
                    state.addDiscoveredJsEndpoint(path + "  (from " + shortJsName(jsUrl) + ")");
                    changed = true;
                } else if (!origin.isEmpty() && !path.contains("{") && !path.contains(":")
                    && !path.contains("*") && !path.contains("..") && !path.endsWith(".js")) {
                    // Candidate UI route discovered in frontend router/bundle
                    if (state.enqueueUrl(origin + path, config)) {
                        changed = true;
                    }
                    state.addDiscoveredJsEndpoint(path + "  [route] (from " + shortJsName(jsUrl) + ")");
                }
                count++;
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
