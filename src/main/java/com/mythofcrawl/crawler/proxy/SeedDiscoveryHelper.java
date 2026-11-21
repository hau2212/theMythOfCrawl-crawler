package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches /robots.txt and /sitemap.xml asynchronously via Montoya HTTP API when crawling starts
 * so hidden entry pages are added into CrawlState's URL frontier without blocking the UI.
 *
 * Uses a managed bounded ExecutorService, applies HeaderFilterProfile rules, and checks
 * crawl status + resetGeneration so stopped/reset crawls never leak stale frontier entries.
 */
public final class SeedDiscoveryHelper {

    private static final Pattern SITEMAP_LOC = Pattern.compile(
        "<loc>\\s*([^<\\s]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService SEED_EXECUTOR = new ThreadPoolExecutor(
        1,
        2,
        30L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(32),
        r -> {
            Thread t = new Thread(r, "MythCrawler-SeedDiscovery");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private SeedDiscoveryHelper() {
    }

    public static void shutdown() {
        SEED_EXECUTOR.shutdownNow();
    }

    public static void fetchSeedsAsync(
        MontoyaApi api,
        String seedUrl,
        CrawlConfig config,
        CrawlState state,
        Consumer<String> logger
    ) {
        if (api == null || seedUrl == null || seedUrl.trim().isEmpty()) {
            return;
        }
        final long startGen = state != null ? state.getResetGeneration() : 0L;
        SEED_EXECUTOR.execute(() -> {
            try {
                if (!isStillActive(config, state, startGen)) {
                    return;
                }
                URI uri = URI.create(seedUrl.trim());
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null) {
                    return;
                }
                boolean https = "https".equalsIgnoreCase(scheme);
                int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
                String origin = scheme + "://" + host + (uri.getPort() > 0 ? (":" + uri.getPort()) : "");
                HttpService service = HttpService.httpService(host, port, https);
                String hostHeader = uri.getPort() > 0 ? (host + ":" + uri.getPort()) : host;

                int added = 0;
                added += inspectRobots(api, service, hostHeader, origin, config, state, startGen);
                if (isStillActive(config, state, startGen)) {
                    added += inspectSitemap(api, service, hostHeader, origin + "/sitemap.xml", config, state, 0, startGen);
                }
                if (added > 0 && logger != null && isStillActive(config, state, startGen)) {
                    logger.accept("Seed discovery (/robots.txt & /sitemap.xml) thêm " + added + " trang vào hàng đợi.");
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static boolean isStillActive(CrawlConfig config, CrawlState state, long startGen) {
        if (config != null && !config.isAllowAutoRequests()) {
            return false;
        }
        if (state == null) {
            return true;
        }
        if (state.getResetGeneration() != startGen) {
            return false;
        }
        return state.getStatus() == CrawlState.Status.RUNNING;
    }

    private static int inspectRobots(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String origin,
        CrawlConfig config,
        CrawlState state,
        long startGen
    ) {
        if (!isStillActive(config, state, startGen)) {
            return 0;
        }
        int added = 0;
        String body = fetchBody(api, service, hostHeader, origin, "/robots.txt", config);
        if (body == null || body.isEmpty() || !isStillActive(config, state, startGen)) {
            return 0;
        }
        for (String line : body.split("\\r?\\n")) {
            if (!isStillActive(config, state, startGen)) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String directive = trimmed.substring(0, colon).trim().toLowerCase();
            String val = trimmed.substring(colon + 1).trim();
            if (val.isEmpty()) {
                continue;
            }
            if (directive.equals("sitemap")) {
                added += inspectSitemap(api, service, hostHeader, val, config, state, 0, startGen);
            } else if ((directive.equals("allow") || directive.equals("disallow")) && val.startsWith("/")) {
                String cleanPath = val.replace("*", "");
                if (!cleanPath.isEmpty() && state.enqueueUrl(origin + cleanPath, config)) {
                    added++;
                }
            }
        }
        return added;
    }

    private static int inspectSitemap(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String sitemapUrl,
        CrawlConfig config,
        CrawlState state,
        int depth,
        long startGen
    ) {
        if (depth > 2 || sitemapUrl == null || sitemapUrl.isEmpty() || !isStillActive(config, state, startGen)) {
            return 0;
        }
        int added = 0;
        try {
            URI u = URI.create(sitemapUrl.trim());
            if (u.getHost() != null && !config.isHostAllowed(u.getHost())) {
                return 0;
            }
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/sitemap.xml" : u.getRawPath();
            if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
                path += "?" + u.getRawQuery();
            }
            String origin = (u.getScheme() != null && u.getHost() != null)
                ? (u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? (":" + u.getPort()) : ""))
                : "";
            String body = fetchBody(api, service, hostHeader, origin, path, config);
            if (body == null || body.isEmpty() || !isStillActive(config, state, startGen)) {
                return 0;
            }
            Matcher m = SITEMAP_LOC.matcher(body);
            int count = 0;
            while (m.find() && count < 300 && isStillActive(config, state, startGen)) {
                String loc = m.group(1).trim();
                if (loc.toLowerCase().endsWith(".xml")) {
                    added += inspectSitemap(api, service, hostHeader, loc, config, state, depth + 1, startGen);
                } else if (state.enqueueUrl(loc, config)) {
                    added++;
                }
                count++;
            }
        } catch (Exception ignored) {
        }
        return added;
    }

    private static String fetchBody(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String origin,
        String path,
        CrawlConfig config
    ) {
        try {
            String rawReq = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0\r\n"
                + "Accept: text/plain, application/xml, text/xml, */*\r\n"
                + "Connection: close\r\n\r\n";
            HttpRequest req = HttpRequest.httpRequest(service, rawReq);
            if (config != null) {
                String fullUrl = (origin != null && !origin.isEmpty()) ? (origin + path) : path;
                req = config.getHeaderFilterProfile()
                    .applyToMontoyaRequest(req, service.host(), fullUrl, false);
            }
            HttpRequestResponse rr = api.http().sendRequest(req, HttpMode.AUTO);
            if (rr != null && rr.response() != null && rr.response().statusCode() == 200) {
                return rr.response().bodyToString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
