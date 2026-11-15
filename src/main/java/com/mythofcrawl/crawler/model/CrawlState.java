package com.mythofcrawl.crawler.model;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Thread-safe crawl frontier, UI action context tracker, clicked DOM signature dedup,
 * Danger-Guard skip log, and JS endpoint discovery store.
 */
public final class CrawlState {

    public enum Status {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPED
    }

    public static final class SkippedAction {
        public final String actionPath;
        public final String reason;
        public final String tag;
        public final String pageUrl;
        public final long timestampMs;

        public SkippedAction(String actionPath, String reason, String tag, String pageUrl, long timestampMs) {
            this.actionPath = actionPath;
            this.reason = reason;
            this.tag = tag;
            this.pageUrl = pageUrl;
            this.timestampMs = timestampMs;
        }
    }

    private static final Pattern STATIC_PAGE_EXT = Pattern.compile(
        ".*\\.(css|js|mjs|map|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot|pdf|zip|mp4|mp3|webm)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE);

    private volatile Status status = Status.IDLE;
    private volatile String currentActionPath = "/ > page load";
    private volatile String currentPageUrl = "";
    private volatile int pagesVisitedCount = 0;
    private volatile int consecutiveAuthRejects = 0;

    private final Deque<String> urlQueue = new ArrayDeque<>();
    private final Set<String> seenQueueUrls = new LinkedHashSet<>();
    private final Set<String> visitedUrls = new LinkedHashSet<>();
    private final Map<String, Integer> patternSampleCounts = new HashMap<>();

    // routeKey -> set of UI action signatures already clicked
    private final Map<String, Set<String>> clickedSignaturesByRoute = new HashMap<>();

    // Correlation from Burp InterceptedRequest.messageId() -> exact actionPath at request time
    private final ConcurrentHashMap<Integer, String> messageActionPathMap = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightTargetRequests = new AtomicInteger(0);

    private final List<SkippedAction> skippedDangerActions = new ArrayList<>();
    private final Set<String> seenSkippedKeys = new LinkedHashSet<>();

    private final Set<String> discoveredJsEndpoints = new LinkedHashSet<>();
    private final List<CapturedApiEntry> extraCaptures = new ArrayList<>();

    public Status getStatus() {
        return status;
    }

    public synchronized void setStatus(Status newStatus) {
        this.status = newStatus;
        if (newStatus == Status.IDLE || newStatus == Status.STOPPED) {
            inFlightTargetRequests.set(0);
        }
    }

    public String getCurrentActionPath() {
        return currentActionPath;
    }

    public String getCurrentPageUrl() {
        return currentPageUrl;
    }

    public void setCurrentContext(String actionPath, String pageUrl) {
        if (actionPath != null && !actionPath.trim().isEmpty()) {
            this.currentActionPath = actionPath.trim();
        }
        if (pageUrl != null && !pageUrl.trim().isEmpty()) {
            this.currentPageUrl = pageUrl.trim();
        }
    }

    public void bindMessageActionPath(int messageId, String actionPath) {
        if (messageActionPathMap.size() > 8000) {
            messageActionPathMap.clear();
        }
        messageActionPathMap.put(messageId, actionPath != null ? actionPath : currentActionPath);
    }

    public String consumeMessageActionPath(int messageId) {
        String p = messageActionPathMap.remove(messageId);
        return p != null ? p : currentActionPath;
    }

    public void incrementInFlight() {
        inFlightTargetRequests.incrementAndGet();
    }

    public void decrementInFlight() {
        inFlightTargetRequests.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public int getInFlightCount() {
        return inFlightTargetRequests.get();
    }

    public synchronized void resetAll(String seedUrl) {
        urlQueue.clear();
        seenQueueUrls.clear();
        visitedUrls.clear();
        patternSampleCounts.clear();
        clickedSignaturesByRoute.clear();
        messageActionPathMap.clear();
        inFlightTargetRequests.set(0);
        skippedDangerActions.clear();
        seenSkippedKeys.clear();
        discoveredJsEndpoints.clear();
        extraCaptures.clear();
        pagesVisitedCount = 0;
        consecutiveAuthRejects = 0;
        currentActionPath = "/ > page load";
        currentPageUrl = seedUrl != null ? seedUrl : "";
        if (seedUrl != null && !seedUrl.trim().isEmpty()) {
            String norm = normalizeCrawlUrl(seedUrl);
            if (!norm.isEmpty()) {
                seenQueueUrls.add(norm);
                urlQueue.add(seedUrl.trim());
            }
        }
    }

    public synchronized boolean enqueueUrl(String rawUrl, CrawlConfig config) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return false;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("javascript:") || trimmed.startsWith("mailto:")
            || trimmed.startsWith("tel:") || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) {
            return false;
        }
        if (STATIC_PAGE_EXT.matcher(trimmed).matches()) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null || (config != null && !config.isHostAllowed(host))) {
                return false;
            }
            if (config != null && config.isDangerGuardEnabled() && isDangerousUrl(trimmed, config)) {
                return false;
            }
            String norm = normalizeCrawlUrl(trimmed);
            if (seenQueueUrls.contains(norm) || visitedUrls.contains(norm)) {
                return false;
            }
            int maxPages = config != null ? config.getMaxPages() : 350;
            if (seenQueueUrls.size() >= maxPages) {
                return false;
            }
            String patternKey = generalizeUrlPattern(norm, config == null || config.isNormalizeRouteIds());
            int cap = config != null ? config.getMaxPaginationSamples() : 3;
            int cnt = patternSampleCounts.getOrDefault(patternKey, 0);
            if (cnt >= cap) {
                return false;
            }
            patternSampleCounts.put(patternKey, cnt + 1);
            seenQueueUrls.add(norm);
            urlQueue.addLast(trimmed);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized void markUrlVisited(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        String norm = normalizeCrawlUrl(rawUrl);
        if (!norm.isEmpty() && visitedUrls.add(norm)) {
            pagesVisitedCount++;
        }
    }

    public synchronized String pollNextUnvisitedUrl() {
        while (!urlQueue.isEmpty()) {
            String candidate = urlQueue.pollFirst();
            String norm = normalizeCrawlUrl(candidate);
            if (!visitedUrls.contains(norm)) {
                return candidate;
            }
        }
        return null;
    }

    public synchronized int getPendingQueueSize() {
        return urlQueue.size();
    }

    public synchronized int getVisitedPagesCount() {
        return pagesVisitedCount;
    }

    public synchronized boolean markActionClicked(String routeKey, String signature) {
        if (routeKey == null || signature == null || signature.isEmpty()) {
            return false;
        }
        return clickedSignaturesByRoute
            .computeIfAbsent(routeKey, k -> new LinkedHashSet<>())
            .add(signature);
    }

    public synchronized List<String> getClickedSignatures(String routeKey) {
        Set<String> s = clickedSignaturesByRoute.get(routeKey);
        return s != null ? new ArrayList<>(s) : new ArrayList<>();
    }

    public synchronized void recordSkippedDanger(String actionPath, String reason, String tag, String pageUrl) {
        String key = actionPath + "|" + reason;
        if (seenSkippedKeys.add(key)) {
            skippedDangerActions.add(
                new SkippedAction(actionPath, reason, tag, pageUrl, System.currentTimeMillis()));
        }
    }

    public synchronized List<SkippedAction> getSkippedDangerActions() {
        return new ArrayList<>(skippedDangerActions);
    }

    public synchronized void addDiscoveredJsEndpoint(String item) {
        if (item != null && !item.trim().isEmpty() && discoveredJsEndpoints.size() < 3000) {
            discoveredJsEndpoints.add(item.trim());
        }
    }

    public synchronized List<String> getDiscoveredJsEndpoints() {
        return new ArrayList<>(discoveredJsEndpoints);
    }

    public synchronized void addExtraCapture(CapturedApiEntry entry) {
        if (extraCaptures.size() < 2000) {
            extraCaptures.add(entry);
        }
    }

    public synchronized List<CapturedApiEntry> getExtraCaptures() {
        return new ArrayList<>(extraCaptures);
    }

    public synchronized void noteAuthStatus(int statusCode, String locationHeader) {
        boolean authDrop = (statusCode == 401)
            || (statusCode >= 300 && statusCode < 400 && locationHeader != null
                && locationHeader.toLowerCase().contains("login"));
        if (authDrop) {
            consecutiveAuthRejects++;
        } else if (statusCode >= 200 && statusCode < 300) {
            consecutiveAuthRejects = 0;
        }
    }

    public synchronized int getConsecutiveAuthRejects() {
        return consecutiveAuthRejects;
    }

    public static String routeKeyForUrl(String rawUrl, boolean normalizeRouteIds) {
        List<String> segs = PathNamer.extractRouteSegments(rawUrl, normalizeRouteIds);
        return segs.isEmpty() ? "/" : ("/" + String.join("/", segs));
    }

    private static boolean isDangerousUrl(String url, CrawlConfig cfg) {
        String lower = url.toLowerCase();
        for (String kw : cfg.getDangerKeywordsList()) {
            if (kw.equals("logout") || kw.equals("log out") || kw.equals("sign out")
                || kw.equals("signout") || kw.equals("đăng xuất")) {
                if (lower.contains(kw.replace(" ", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String normalizeCrawlUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            int port = uri.getPort();
            String portPart = (port > 0 && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)))
                ? (":" + port) : "";
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery();
            String frag = uri.getRawFragment();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host).append(portPart).append(path);
            if (query != null && !query.isEmpty()) {
                sb.append('?').append(query);
            }
            // Keep SPA hash routes (#/...) while discarding anchor-only fragments (#top)
            if (frag != null && (frag.startsWith("/") || frag.startsWith("!/"))) {
                sb.append('#').append(frag);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return rawUrl.trim();
        }
    }

    private static String generalizeUrlPattern(String normUrl, boolean normalizeIds) {
        try {
            URI uri = URI.create(normUrl);
            String path = uri.getRawPath() != null ? uri.getRawPath() : "/";
            StringBuilder p = new StringBuilder();
            for (String seg : path.split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                p.append('/').append(normalizeIds && PathNamer.isDynamicIdSegment(seg) ? "{id}" : seg);
            }
            if (p.length() == 0) {
                p.append('/');
            }
            String q = uri.getRawQuery();
            if (q != null && !q.isEmpty()) {
                p.append('?');
                List<String> keys = new ArrayList<>();
                for (String pair : q.split("&")) {
                    int eq = pair.indexOf('=');
                    String k = eq >= 0 ? pair.substring(0, eq) : pair;
                    String v = eq >= 0 ? pair.substring(eq + 1) : "";
                    String kl = k.toLowerCase();
                    if (kl.equals("page") || kl.equals("p") || kl.equals("offset")
                        || kl.equals("cursor") || kl.equals("id")) {
                        keys.add(k + "={#}");
                    } else if (v.matches("\\d+")) {
                        keys.add(k + "={#}");
                    } else {
                        keys.add(k + "=" + v);
                    }
                }
                p.append(String.join("&", keys));
            }
            return p.toString();
        } catch (Exception ignored) {
            return normUrl;
        }
    }
}
