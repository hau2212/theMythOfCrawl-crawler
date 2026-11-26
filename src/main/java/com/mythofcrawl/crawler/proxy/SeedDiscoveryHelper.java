package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.MiniJson;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String[] OPENAPI_CANDIDATE_PATHS = {
        "/openapi.json",
        "/swagger.json",
        "/v3/api-docs",
        "/v2/api-docs",
        "/api/openapi.json",
        "/api/swagger.json",
        "/swagger/v1/swagger.json"
    };
    private static final String[] MANIFEST_CANDIDATE_PATHS = {
        "/manifest.json",
        "/site.webmanifest"
    };
    private static final Set<String> HTTP_VERBS = Set.of(
        "get", "post", "put", "patch", "delete", "head", "options"
    );

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
        fetchSeedsAsync(api, seedUrl, config, state, null, logger);
    }

    public static void fetchSeedsAsync(
        MontoyaApi api,
        String seedUrl,
        CrawlConfig config,
        CrawlState state,
        ExcelSpecMatcher matcher,
        Consumer<String> logger
    ) {
        final String cleanSeed = CrawlConfig.unwrapMarkdownLinks(seedUrl).trim();
        if (api == null || cleanSeed.isEmpty()) {
            return;
        }
        final long startGen = state != null ? state.getResetGeneration() : 0L;
        SEED_EXECUTOR.execute(() -> {
            try {
                if (!isStillActive(config, state, startGen)) {
                    return;
                }
                URI uri = URI.create(cleanSeed);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null) {
                    return;
                }
                boolean https = "https".equalsIgnoreCase(scheme);
                int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
                String origin = scheme + "://" + host + (uri.getPort() > 0 ? (":" + uri.getPort()) : "");
                String seedPathPrefix = extractScopedSubPath(uri.getRawPath());
                boolean pathRestricted = config != null && config.hasPathScopeRestriction();
                HttpService service = HttpService.httpService(host, port, https);
                String hostHeader = uri.getPort() > 0 ? (host + ":" + uri.getPort()) : host;

                int added = 0;
                if (!pathRestricted || config.isUrlAllowedForCrawl(origin + "/robots.txt")) {
                    added += inspectRobots(api, service, hostHeader, origin, config, state, startGen);
                }
                if (isStillActive(config, state, startGen)) {
                    String smUrl = origin + "/sitemap.xml";
                    if (!pathRestricted || config.isUrlAllowedForCrawl(smUrl)) {
                        added += inspectSitemap(api, service, hostHeader, smUrl, config, state, 0, startGen);
                    } else if (!seedPathPrefix.isEmpty() && config.isUrlAllowedForCrawl(origin + seedPathPrefix + "/sitemap.xml")) {
                        added += inspectSitemap(api, service, hostHeader, origin + seedPathPrefix + "/sitemap.xml", config, state, 0, startGen);
                    }
                }
                if (isStillActive(config, state, startGen) && (!pathRestricted || config.isUrlAllowedForCrawl(origin + "/sitemap_index.xml"))) {
                    added += inspectSitemap(api, service, hostHeader, origin + "/sitemap_index.xml", config, state, 0, startGen);
                }
                if (isStillActive(config, state, startGen) && config.isDeepCrawlMode()
                    && (!pathRestricted || config.isUrlAllowedForCrawl(origin + "/manifest.json"))) {
                    added += inspectManifests(api, service, hostHeader, origin, config, state, startGen);
                }
                int openApiSpecs = 0;
                if (isStillActive(config, state, startGen) && config.isCaptureJsEndpoints()) {
                    String openApiBase = (pathRestricted && !seedPathPrefix.isEmpty()) ? (origin + seedPathPrefix) : origin;
                    if (!pathRestricted || config.isUrlAllowedForCrawl(openApiBase + "/openapi.json")) {
                        openApiSpecs = inspectOpenApiSpecs(api, service, hostHeader, openApiBase, config, state, matcher, startGen);
                    }
                }
                if ((added > 0 || openApiSpecs > 0) && logger != null && isStillActive(config, state, startGen)) {
                    logger.accept("Seed & OpenAPI discovery thêm " + added + " trang vào hàng đợi"
                        + (openApiSpecs > 0 ? (" và phát hiện " + openApiSpecs + " endpoint từ OpenAPI/Swagger.") : "."));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static String extractScopedSubPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "";
        }
        String p = rawPath.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return "/".equals(p) ? "" : p;
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

    private static int inspectManifests(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String origin,
        CrawlConfig config,
        CrawlState state,
        long startGen
    ) {
        int added = 0;
        for (String manifestPath : MANIFEST_CANDIDATE_PATHS) {
            if (!isStillActive(config, state, startGen)) {
                break;
            }
            String body = fetchBody(api, service, hostHeader, origin, manifestPath, config);
            if (body == null || !body.trim().startsWith("{")) {
                continue;
            }
            try {
                Map<String, Object> manifest = MiniJson.parseObject(body.trim());
                String startUrl = MiniJson.asString(manifest, "start_url", "").trim();
                if (!startUrl.isEmpty() && state.enqueuePriorityUrl(resolveAgainstOrigin(origin, startUrl), config)) {
                    added++;
                }
                Object shortcuts = manifest.get("shortcuts");
                if (shortcuts instanceof List<?>) {
                    for (Object sc : (List<?>) shortcuts) {
                        if (sc instanceof Map<?, ?>) {
                            Object urlVal = ((Map<?, ?>) sc).get("url");
                            if (urlVal != null) {
                                String uStr = String.valueOf(urlVal).trim();
                                if (!uStr.isEmpty() && state.enqueuePriorityUrl(resolveAgainstOrigin(origin, uStr), config)) {
                                    added++;
                                }
                            }
                        }
                    }
                }
                break;
            } catch (Exception ignored) {
            }
        }
        return added;
    }

    private static int inspectOpenApiSpecs(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String origin,
        CrawlConfig config,
        CrawlState state,
        ExcelSpecMatcher matcher,
        long startGen
    ) {
        int totalEndpoints = 0;
        for (String specPath : OPENAPI_CANDIDATE_PATHS) {
            if (!isStillActive(config, state, startGen)) {
                break;
            }
            String body = fetchBody(api, service, hostHeader, origin, specPath, config);
            if (body == null || body.length() < 20 || !body.trim().startsWith("{")) {
                continue;
            }
            int found = parseOpenApiSpecJson(body, origin, specPath, config, state, matcher);
            if (found > 0) {
                totalEndpoints += found;
                break; // Found canonical OpenAPI/Swagger spec on this origin
            }
        }
        return totalEndpoints;
    }

    /**
     * Parses an OpenAPI 3.x / Swagger 2.0 JSON document, recording discovered API operations
     * into {@link CrawlState#addDiscoveredJsEndpoint(String)}, cross-matching Excel hints, and
     * enqueueing eligible non-API GET routes.
     */
    public static int parseOpenApiSpecJson(
        String jsonBody,
        String origin,
        String sourceLabel,
        CrawlConfig config,
        CrawlState state,
        ExcelSpecMatcher matcher
    ) {
        if (jsonBody == null || state == null || !jsonBody.trim().startsWith("{")) {
            return 0;
        }
        try {
            Map<String, Object> doc = MiniJson.parseObject(jsonBody.trim());
            Object pathsObj = doc.get("paths");
            if (!(pathsObj instanceof Map<?, ?>)) {
                return 0;
            }
            if (matcher != null && matcher.isLoaded()) {
                matcher.attachJsHintIfMentioned(jsonBody, origin + sourceLabel);
            }
            String basePath = MiniJson.asString(doc, "basePath", "").trim();
            if ("/".equals(basePath)) {
                basePath = "";
            }
            int count = 0;
            for (Map.Entry<?, ?> pe : ((Map<?, ?>) pathsObj).entrySet()) {
                String rawPath = String.valueOf(pe.getKey()).trim();
                if (rawPath.isEmpty()) {
                    continue;
                }
                String fullPath = basePath.isEmpty()
                    ? rawPath
                    : (rawPath.startsWith("/") ? (basePath + rawPath) : (basePath + "/" + rawPath));
                Object verbsObj = pe.getValue();
                if (verbsObj instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> ve : ((Map<?, ?>) verbsObj).entrySet()) {
                        String verb = String.valueOf(ve.getKey()).trim().toLowerCase(Locale.ROOT);
                        if (!HTTP_VERBS.contains(verb)) {
                            continue;
                        }
                        String upperVerb = verb.toUpperCase(Locale.ROOT);
                        state.addDiscoveredJsEndpoint(
                            "[OpenAPI] " + upperVerb + " " + fullPath + "  (from " + sourceLabel + ")");
                        count++;
                        if ("GET".equals(upperVerb) && config != null && config.isDeepCrawlMode()
                            && origin != null && !origin.isEmpty()) {
                            state.enqueueJsRouteIfEligible(fullPath, origin + sourceLabel, config);
                        }
                        if (count >= 1000) {
                            return count;
                        }
                    }
                }
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String resolveAgainstOrigin(String origin, String pathOrUrl) {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        if (pathOrUrl.startsWith("/")) {
            return origin + pathOrUrl;
        }
        return origin + "/" + pathOrUrl;
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
