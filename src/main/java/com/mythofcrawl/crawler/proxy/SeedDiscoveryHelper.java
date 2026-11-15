package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import java.net.URI;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches /robots.txt and /sitemap.xml asynchronously via Montoya HTTP API when crawling starts
 * so hidden entry pages are added into CrawlState's URL frontier without blocking the UI.
 */
public final class SeedDiscoveryHelper {

    private static final Pattern SITEMAP_LOC = Pattern.compile(
        "<loc>\\s*([^<\\s]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    private SeedDiscoveryHelper() {
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
        Thread t = new Thread(() -> {
            try {
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
                added += inspectRobots(api, service, hostHeader, origin, config, state);
                added += inspectSitemap(api, service, hostHeader, origin + "/sitemap.xml", config, state, 0);
                if (added > 0 && logger != null) {
                    logger.accept("Seed discovery (/robots.txt & /sitemap.xml) thêm " + added + " trang vào hàng đợi.");
                }
            } catch (Exception ignored) {
            }
        }, "MythCrawler-SeedDiscovery");
        t.setDaemon(true);
        t.start();
    }

    private static int inspectRobots(
        MontoyaApi api,
        HttpService service,
        String hostHeader,
        String origin,
        CrawlConfig config,
        CrawlState state
    ) {
        int added = 0;
        String body = fetchBody(api, service, hostHeader, "/robots.txt");
        if (body == null || body.isEmpty()) {
            return 0;
        }
        for (String line : body.split("\\r?\\n")) {
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
                added += inspectSitemap(api, service, hostHeader, val, config, state, 0);
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
        int depth
    ) {
        if (depth > 2 || sitemapUrl == null || sitemapUrl.isEmpty()) {
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
            String body = fetchBody(api, service, hostHeader, path);
            if (body == null || body.isEmpty()) {
                return 0;
            }
            Matcher m = SITEMAP_LOC.matcher(body);
            int count = 0;
            while (m.find() && count < 300) {
                String loc = m.group(1).trim();
                if (loc.toLowerCase().endsWith(".xml")) {
                    added += inspectSitemap(api, service, hostHeader, loc, config, state, depth + 1);
                } else if (state.enqueueUrl(loc, config)) {
                    added++;
                }
                count++;
            }
        } catch (Exception ignored) {
        }
        return added;
    }

    private static String fetchBody(MontoyaApi api, HttpService service, String hostHeader, String path) {
        try {
            String rawReq = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0\r\n"
                + "Accept: text/plain, application/xml, text/xml, */*\r\n"
                + "Connection: close\r\n\r\n";
            HttpRequest req = HttpRequest.httpRequest(service, rawReq);
            HttpRequestResponse rr = api.http().sendRequest(req);
            if (rr != null && rr.response() != null && rr.response().statusCode() == 200) {
                return rr.response().bodyToString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
