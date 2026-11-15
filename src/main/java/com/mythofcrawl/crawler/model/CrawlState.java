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
    private volatile ActionGroup currentGroup;
    private volatile int pagesVisitedCount = 0;
    private volatile int consecutiveAuthRejects = 0;

    private final Deque<String> urlQueue = new ArrayDeque<>();
    private final Set<String> seenQueueUrls = new LinkedHashSet<>();
    private final Set<String> visitedUrls = new LinkedHashSet<>();
    private final Map<String, Integer> patternSampleCounts = new HashMap<>();

    // routeKey -> set of UI action signatures already clicked
    private final Map<String, Set<String>> clickedSignaturesByRoute = new HashMap<>();
    // Global header/footer/nav UI signatures already clicked once across the whole crawl session
    private final Set<String> globalClickedSignatures = new LinkedHashSet<>();

    // Multi-level trail stack & redirect loop protection
    private volatile long lastContextActionAtMs = 0L;
    private final Deque<TrailLevel> trailStack = new ArrayDeque<>();
    private final Set<String> deferredUrls = new LinkedHashSet<>();
    private final Set<String> completedUrls = new LinkedHashSet<>();
    private final Map<String, Integer> redirectCountsByNorm = new HashMap<>();

    public static final class TrailLevel {
        public final String normUrl;
        public volatile String rawUrl;
        public final long enteredAtMs;

        public TrailLevel(String normUrl, String rawUrl) {
            this.normUrl = normUrl;
            this.rawUrl = rawUrl;
            this.enteredAtMs = System.currentTimeMillis();
        }
    }

    public static final class BacktrackResult {
        public final String targetUrl;
        public final int levelsBack;
        public final boolean fromQueue;
        public final boolean exhausted;

        public BacktrackResult(String targetUrl, int levelsBack, boolean fromQueue, boolean exhausted) {
            this.targetUrl = targetUrl != null ? targetUrl : "";
            this.levelsBack = levelsBack;
            this.fromQueue = fromQueue;
            this.exhausted = exhausted;
        }
    }

    // Correlation from Burp InterceptedRequest.messageId() -> exact actionPath + ActionGroup at request time
    private final ConcurrentHashMap<Integer, String> messageActionPathMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ActionGroup> messageGroupMap = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightTargetRequests = new AtomicInteger(0);

    private final ActionGroupStore actionGroups = new ActionGroupStore();
    private final List<SkippedAction> skippedDangerActions = new ArrayList<>();
    private final Set<String> seenSkippedKeys = new LinkedHashSet<>();

    private final Set<String> discoveredJsEndpoints = new LinkedHashSet<>();
    private final List<CapturedApiEntry> extraCaptures = new ArrayList<>();

    public ActionGroupStore getActionGroups() {
        return actionGroups;
    }

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

    public ActionGroup getCurrentGroup() {
        return currentGroup;
    }

    public void setCurrentContext(String actionPath, String pageUrl) {
        setCurrentContext(actionPath, "", "", pageUrl);
    }

    public ActionGroup setCurrentContext(String actionPath, String kind, String label, String pageUrl) {
        this.lastContextActionAtMs = System.currentTimeMillis();
        String resolvedPath = (actionPath != null && !actionPath.trim().isEmpty())
            ? actionPath.trim() : this.currentActionPath;
        this.currentActionPath = resolvedPath;
        if (pageUrl != null && !pageUrl.trim().isEmpty()) {
            this.currentPageUrl = pageUrl.trim();
            boolean isPageLoad = "page load".equalsIgnoreCase(kind)
                || resolvedPath.toLowerCase().endsWith("> page load");
            if (isPageLoad) {
                enterPageLevel(this.currentPageUrl);
            }
        }
        ActionGroup g = actionGroups.openGroup(resolvedPath, kind, label, this.currentPageUrl);
        this.currentGroup = g;
        return g;
    }

    public void touchContextAction() {
        this.lastContextActionAtMs = System.currentTimeMillis();
    }

    public long getLastContextActionAtMs() {
        return lastContextActionAtMs;
    }

    public void bindMessageActionPath(int messageId, String actionPath) {
        bindMessageContext(messageId, actionPath, this.currentGroup);
    }

    public void bindMessageContext(int messageId, String actionPath, ActionGroup group) {
        if (messageActionPathMap.size() > 8000) {
            messageActionPathMap.clear();
            messageGroupMap.clear();
        }
        messageActionPathMap.put(messageId, actionPath != null ? actionPath : currentActionPath);
        if (group != null) {
            messageGroupMap.put(messageId, group);
        }
    }

    public String consumeMessageActionPath(int messageId) {
        String p = messageActionPathMap.remove(messageId);
        return p != null ? p : currentActionPath;
    }

    public ActionGroup consumeMessageGroup(int messageId) {
        return messageGroupMap.remove(messageId);
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
        globalClickedSignatures.clear();
        messageActionPathMap.clear();
        messageGroupMap.clear();
        actionGroups.clear();
        inFlightTargetRequests.set(0);
        skippedDangerActions.clear();
        seenSkippedKeys.clear();
        discoveredJsEndpoints.clear();
        extraCaptures.clear();
        trailStack.clear();
        deferredUrls.clear();
        completedUrls.clear();
        redirectCountsByNorm.clear();
        lastContextActionAtMs = 0L;
        pagesVisitedCount = 0;
        consecutiveAuthRejects = 0;
        currentActionPath = "/ > page load";
        currentPageUrl = seedUrl != null ? seedUrl : "";
        currentGroup = null;
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
            if (seenQueueUrls.contains(norm) || visitedUrls.contains(norm) || deferredUrls.contains(norm)) {
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
            if (!visitedUrls.contains(norm) && !deferredUrls.contains(norm)) {
                return candidate;
            }
        }
        return null;
    }

    public synchronized void markUrlDeferred(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        if (!norm.isEmpty()) {
            deferredUrls.add(norm);
            completedUrls.add(norm);
            visitedUrls.add(norm);
        }
    }

    public synchronized void markUrlCompleted(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        if (!norm.isEmpty()) {
            completedUrls.add(norm);
            if (visitedUrls.add(norm)) {
                pagesVisitedCount++;
            }
        }
    }

    public synchronized void markGlobalActionClicked(String signature) {
        if (signature != null && !signature.trim().isEmpty()) {
            globalClickedSignatures.add(signature.trim());
        }
    }

    public synchronized List<String> getGlobalClickedSignatures() {
        return new ArrayList<>(globalClickedSignatures);
    }

    public synchronized boolean isUrlDeferred(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        return !norm.isEmpty() && deferredUrls.contains(norm);
    }

    public synchronized void enterPageLevel(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        String trimmed = rawUrl.trim();
        String norm = normalizeCrawlUrl(trimmed);
        if (norm.isEmpty() || deferredUrls.contains(norm)) {
            return;
        }
        TrailLevel existing = null;
        for (TrailLevel lvl : trailStack) {
            if (lvl.normUrl.equals(norm)) {
                existing = lvl;
                break;
            }
        }
        if (existing != null) {
            while (!trailStack.isEmpty() && !trailStack.peekLast().normUrl.equals(norm)) {
                trailStack.pollLast();
            }
            if (!trailStack.isEmpty()) {
                trailStack.peekLast().rawUrl = trimmed;
            }
            return;
        }
        while (trailStack.size() >= 32) {
            trailStack.pollFirst();
        }
        trailStack.addLast(new TrailLevel(norm, trimmed));
    }

    public synchronized String getLastKnownSafeUrl() {
        TrailLevel top = null;
        for (TrailLevel lvl : trailStack) {
            if (!deferredUrls.contains(lvl.normUrl)) {
                top = lvl;
            }
        }
        if (top != null && top.rawUrl != null && !top.rawUrl.isEmpty()) {
            return top.rawUrl;
        }
        String curNorm = normalizeCrawlUrl(currentPageUrl);
        if (!curNorm.isEmpty() && !deferredUrls.contains(curNorm)) {
            return currentPageUrl;
        }
        return "";
    }

    public synchronized int getTrailDepth() {
        return trailStack.size();
    }

    public synchronized BacktrackResult recordRedirectBounce(String fromUrl, String destUrl, boolean markFromDeferred) {
        if (destUrl != null && !destUrl.trim().isEmpty()) {
            markUrlDeferred(destUrl);
        }
        String effectiveFrom = (fromUrl != null && !fromUrl.trim().isEmpty()) ? fromUrl.trim() : this.currentPageUrl;
        String fromNorm = normalizeCrawlUrl(effectiveFrom);
        String curNorm = normalizeCrawlUrl(this.currentPageUrl);
        if (!fromNorm.isEmpty()) {
            redirectCountsByNorm.merge(fromNorm, 1, Integer::sum);
        }
        boolean isPageLoadState = this.currentActionPath != null
            && this.currentActionPath.toLowerCase().endsWith("> page load");
        boolean jumpedFromDifferentPage = !fromNorm.isEmpty() && !curNorm.isEmpty() && !fromNorm.equals(curNorm);
        if (!fromNorm.isEmpty() && (markFromDeferred || isPageLoadState || jumpedFromDifferentPage)) {
            deferredUrls.add(fromNorm);
            completedUrls.add(fromNorm);
            visitedUrls.add(fromNorm);
        }
        return resolveBacktrackOrNextPage(effectiveFrom, true, false);
    }

    public synchronized BacktrackResult resolveBacktrackOrNextPage(String currentUrl, boolean preferBacktrack) {
        return resolveBacktrackOrNextPage(currentUrl, preferBacktrack, !preferBacktrack);
    }

    public synchronized BacktrackResult resolveBacktrackOrNextPage(
        String currentUrl,
        boolean preferBacktrack,
        boolean popCurrentLevel
    ) {
        int levelsBack = 0;
        String curNorm = normalizeCrawlUrl(currentUrl);
        while (!trailStack.isEmpty()) {
            TrailLevel top = trailStack.peekLast();
            boolean shouldPop = deferredUrls.contains(top.normUrl)
                || completedUrls.contains(top.normUrl)
                || (popCurrentLevel && !curNorm.isEmpty() && top.normUrl.equals(curNorm));
            if (shouldPop) {
                trailStack.pollLast();
                levelsBack++;
                continue;
            }
            return new BacktrackResult(top.rawUrl, levelsBack, false, false);
        }
        String nextFromQueue = pollNextUnvisitedUrl();
        if (nextFromQueue != null && !nextFromQueue.isEmpty()) {
            return new BacktrackResult(nextFromQueue, levelsBack, true, false);
        }
        if (preferBacktrack && !curNorm.isEmpty() && !deferredUrls.contains(curNorm) && !completedUrls.contains(curNorm)) {
            return new BacktrackResult(currentUrl, 0, false, false);
        }
        return new BacktrackResult("", levelsBack, false, true);
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
