package com.mythofcrawl.crawler.model;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Thread-safe crawl frontier, UI action context tracker, clicked DOM signature dedup,
 * Danger-Guard skip log, and JS endpoint discovery store.
 *
 * Uses split domain locks (frontier, trail, clicks, discovery, auth) and 60s TTL-evicted
 * messageId correlation so concurrent Proxy, ControlServer, and EDT threads never contend
 * on a single monitor or leak in-flight counters.
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

    public static final class ActionContextSnapshot {
        public final String actionPath;
        public final ActionGroup group;
        public final long updatedAtMs;

        public ActionContextSnapshot(String actionPath, ActionGroup group, long updatedAtMs) {
            this.actionPath = actionPath;
            this.group = group;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static final class AgentTelemetry {
        public final boolean connected;
        public final String phase;
        public final String currentUrl;
        public final int actionsDoneOnPage;
        public final int totalTargetsOnPage;
        public final long lastHeartbeatMs;

        public AgentTelemetry(
            boolean connected,
            String phase,
            String currentUrl,
            int actionsDoneOnPage,
            int totalTargetsOnPage,
            long lastHeartbeatMs
        ) {
            this.connected = connected;
            this.phase = (phase != null && !phase.isEmpty()) ? phase : "IDLE";
            this.currentUrl = currentUrl != null ? currentUrl : "";
            this.actionsDoneOnPage = Math.max(0, actionsDoneOnPage);
            this.totalTargetsOnPage = Math.max(0, totalTargetsOnPage);
            this.lastHeartbeatMs = lastHeartbeatMs;
        }
    }

    private static final long TELEMETRY_CONNECTED_WINDOW_MS = 10_000L;
    private static final Pattern JS_ROUTE_STATIC_EXT = Pattern.compile(
        ".*\\.(js|mjs|cjs|css|scss|less|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot|map|pdf|mp4|mp3|json|xml)$",
        Pattern.CASE_INSENSITIVE);
    private final Object telemetryLock = new Object();
    private String telemetryPhase = "IDLE";
    private String telemetryUrl = "";
    private int telemetryDone = 0;
    private int telemetryTotal = 0;
    private long telemetryLastHeartbeatMs = 0L;
    private volatile java.util.function.IntSupplier pendingInterceptCountSupplier;

    private static final class PendingMessageContext {
        final String actionPath;
        final ActionGroup group;
        final long createdAtMs;
        volatile boolean trackedInFlight;

        PendingMessageContext(String actionPath, ActionGroup group, boolean trackedInFlight) {
            this.actionPath = actionPath;
            this.group = group;
            this.createdAtMs = System.currentTimeMillis();
            this.trackedInFlight = trackedInFlight;
        }
    }

    private static final long MESSAGE_TTL_MS = 60_000L;
    private static final int MAX_PENDING_MESSAGES = 8192;
    private static final int MAX_CLICKED_ROUTES = 4000;
    private static final int MAX_SIGS_PER_ROUTE = 2000;
    private static final int MAX_GLOBAL_SIGS = 4000;
    private static final int MAX_SKIPPED_DANGER = 2000;
    private static final int MAX_TRAIL_STEPS = 25;
    private static final int MAX_EVENT_LOGS = 1500;

    private static final Pattern STATIC_PAGE_EXT = Pattern.compile(
        ".*\\.(css|js|mjs|map|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot|pdf|zip|mp4|mp3|webm)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE);

    private final Object statusLock = new Object();
    private final Object frontierLock = new Object();
    private final Object trailLock = new Object();
    private final Object clicksLock = new Object();
    private final Object discoveryLock = new Object();
    private final Object authLock = new Object();
    private final Object eventLogLock = new Object();
    private final Object replayLock = new Object();

    private volatile Status status = Status.IDLE;
    private final AtomicLong resetGeneration = new AtomicLong(0);

    // Structured Event Log ring buffer
    private final Deque<EventLogEntry> eventLogs = new ArrayDeque<>();
    private final AtomicLong eventLogSeq = new AtomicLong(0);
    private final AtomicLong eventLogVersion = new AtomicLong(0);

    // Visual Action Trail Replay state
    private final AtomicLong replayPlanSeq = new AtomicLong(0);
    private volatile ReplayPlan activeReplayPlan = null;

    // Trail & current action context (guarded by trailLock; volatile mirrors for lock-free reads)
    private volatile String currentActionPath = "/ > page load";
    private volatile String currentPageUrl = "";
    private volatile ActionGroup currentGroup;
    private volatile long lastContextActionAtMs = 0L;
    private volatile long lastClickActionAtMs = 0L;
    private final Deque<TrailLevel> trailStack = new ArrayDeque<>();
    private final Set<String> deferredUrls = new LinkedHashSet<>();
    private final Set<String> completedUrls = new LinkedHashSet<>();
    private final Map<String, Integer> redirectCountsByNorm = new HashMap<>();
    private final LinkedHashMap<String, ActionContextSnapshot> pageContextCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ActionContextSnapshot> eldest) {
            return size() > 64;
        }
    };
    private final List<ActionStep> activeSessionTrail = new ArrayList<>();

    // Frontier (guarded by frontierLock)
    private volatile int pagesVisitedCount = 0;
    private final Deque<String> urlQueue = new ArrayDeque<>();
    private final Set<String> seenQueueUrls = new LinkedHashSet<>();
    private final Set<String> visitedUrls = new LinkedHashSet<>();
    private final Map<String, Integer> patternSampleCounts = new HashMap<>();

    // Auth reject counter (guarded by authLock)
    private volatile int consecutiveAuthRejects = 0;

    // Clicked signatures (guarded by clicksLock)
    private final LinkedHashMap<String, Set<String>> clickedSignaturesByRoute = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > MAX_CLICKED_ROUTES;
        }
    };
    private final Set<String> globalClickedSignatures = new LinkedHashSet<>();

    // Correlation from Burp InterceptedRequest.messageId() -> exact actionPath + ActionGroup at request time
    private final ConcurrentHashMap<Integer, PendingMessageContext> pendingMessages = new ConcurrentHashMap<>();
    private final AtomicInteger bindSweepCounter = new AtomicInteger(0);
    private final AtomicInteger inFlightTargetRequests = new AtomicInteger(0);

    private final ActionGroupStore actionGroups = new ActionGroupStore();

    // Discovery & skipped actions (guarded by discoveryLock)
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

    public long getResetGeneration() {
        return resetGeneration.get();
    }

    public void setStatus(Status newStatus) {
        synchronized (statusLock) {
            this.status = newStatus;
            if (newStatus == Status.IDLE || newStatus == Status.STOPPED) {
                inFlightTargetRequests.set(0);
                pendingMessages.clear();
            }
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
        setCurrentContext(actionPath, "", "", pageUrl, null);
    }

    public ActionGroup setCurrentContext(String actionPath, String kind, String label, String pageUrl) {
        return setCurrentContext(actionPath, kind, label, pageUrl, null);
    }

    public ActionGroup setCurrentContext(
        String actionPath,
        String kind,
        String label,
        String pageUrl,
        ActionStep stepLocator
    ) {
        long now = System.currentTimeMillis();
        String trimmedAction = actionPath != null ? actionPath.trim() : "";
        String resolvedPath = !trimmedAction.isEmpty() ? trimmedAction : this.currentActionPath;
        boolean isPageLoad = "page load".equalsIgnoreCase(kind)
            || resolvedPath.toLowerCase().endsWith("> page load");

        String normPage = (pageUrl != null && !pageUrl.trim().isEmpty())
            ? normalizeCrawlUrl(pageUrl.trim())
            : "";

        // Open group outside trailLock to keep lock ordering clean
        String effectivePage = (pageUrl != null && !pageUrl.trim().isEmpty())
            ? pageUrl.trim()
            : this.currentPageUrl;
        ActionGroup g = actionGroups.openGroup(resolvedPath, kind, label, effectivePage);

        List<ActionStep> trailSnapshot;
        synchronized (trailLock) {
            this.lastContextActionAtMs = now;
            if (!isPageLoad) {
                this.lastClickActionAtMs = now;
            }
            this.currentActionPath = resolvedPath;
            this.currentGroup = g;
            if (pageUrl != null && !pageUrl.trim().isEmpty()) {
                this.currentPageUrl = pageUrl.trim();
                if (isPageLoad) {
                    enterPageLevelLocked(this.currentPageUrl);
                }
            }
            if (!normPage.isEmpty()) {
                pageContextCache.put(normPage, new ActionContextSnapshot(resolvedPath, g, now));
            }
            trailSnapshot = appendTrailStepLocked(
                isPageLoad, effectivePage, normPage, kind, label, resolvedPath, stepLocator);
        }
        if (g != null && trailSnapshot != null && !trailSnapshot.isEmpty()) {
            g.setActionTrail(trailSnapshot);
        }
        return g;
    }

    private List<ActionStep> appendTrailStepLocked(
        boolean isPageLoad,
        String effectivePage,
        String normPage,
        String kind,
        String label,
        String resolvedPath,
        ActionStep stepLocator
    ) {
        ActionStep incoming = stepLocator != null
            ? stepLocator
            : new ActionStep(
                0,
                effectivePage,
                "",
                Collections.emptyList(),
                "",
                "",
                "",
                isPageLoad ? "page load" : (kind != null ? kind : ""),
                isPageLoad ? effectivePage : (label != null ? label : ""),
                "",
                resolvedPath,
                Collections.emptyList()
            );

        if (isPageLoad) {
            if (!activeSessionTrail.isEmpty()) {
                ActionStep last = activeSessionTrail.get(activeSessionTrail.size() - 1);
                String lastNorm = normalizeCrawlUrl(last.pageUrl);
                if ("page load".equalsIgnoreCase(last.kind) && !normPage.isEmpty() && normPage.equals(lastNorm)) {
                    activeSessionTrail.set(activeSessionTrail.size() - 1,
                        incoming.withStepIndex(activeSessionTrail.size() - 1));
                    return reindexTrailLocked();
                }
            }
            // Start a clean page load anchor if this is the initial load or came from a non-navigational loop
            if (activeSessionTrail.isEmpty()) {
                activeSessionTrail.add(incoming.withStepIndex(0));
            } else {
                while (activeSessionTrail.size() >= MAX_TRAIL_STEPS) {
                    activeSessionTrail.remove(0);
                }
                activeSessionTrail.add(incoming.withStepIndex(activeSessionTrail.size()));
            }
            return reindexTrailLocked();
        }

        if (activeSessionTrail.isEmpty() && effectivePage != null && !effectivePage.isEmpty()) {
            activeSessionTrail.add(new ActionStep(
                0, effectivePage, "", Collections.emptyList(), "", "", "",
                "page load", effectivePage, "", resolvedPath, Collections.emptyList()
            ));
        }

        if (!activeSessionTrail.isEmpty()) {
            ActionStep last = activeSessionTrail.get(activeSessionTrail.size() - 1);
            boolean sameTarget = !incoming.cssSelector.isEmpty()
                && incoming.cssSelector.equals(last.cssSelector)
                && normalizeCrawlUrl(incoming.pageUrl).equals(normalizeCrawlUrl(last.pageUrl))
                && incoming.kind.equalsIgnoreCase(last.kind);
            if (sameTarget) {
                activeSessionTrail.set(activeSessionTrail.size() - 1,
                    incoming.withStepIndex(activeSessionTrail.size() - 1));
                return reindexTrailLocked();
            }
        }

        while (activeSessionTrail.size() >= MAX_TRAIL_STEPS) {
            // Keep index 0 page-load anchor if possible by removing step 1
            if (activeSessionTrail.size() > 1) {
                activeSessionTrail.remove(1);
            } else {
                activeSessionTrail.remove(0);
            }
        }
        activeSessionTrail.add(incoming.withStepIndex(activeSessionTrail.size()));
        return reindexTrailLocked();
    }

    private List<ActionStep> reindexTrailLocked() {
        List<ActionStep> out = new ArrayList<>(activeSessionTrail.size());
        for (int i = 0; i < activeSessionTrail.size(); i++) {
            ActionStep reindexed = activeSessionTrail.get(i).withStepIndex(i);
            activeSessionTrail.set(i, reindexed);
            out.add(reindexed);
        }
        return out;
    }

    public List<ActionStep> getActiveTrailSnapshot() {
        synchronized (trailLock) {
            return new ArrayList<>(activeSessionTrail);
        }
    }

    /**
     * Resolves the best matching action context for an incoming proxy request, preferring
     * a recent context recorded for the request's Referer page (within 4s) to reduce
     * cross-tab misattribution, falling back to the global current context.
     */
    public ActionContextSnapshot resolveContextForReferer(String referer) {
        long now = System.currentTimeMillis();
        String normRef = normalizeCrawlUrl(referer);
        synchronized (trailLock) {
            if (!normRef.isEmpty()) {
                ActionContextSnapshot snap = pageContextCache.get(normRef);
                if (snap != null && (now - snap.updatedAtMs) <= 4000L) {
                    return snap;
                }
            }
            return new ActionContextSnapshot(currentActionPath, currentGroup, lastContextActionAtMs);
        }
    }

    public void touchContextAction() {
        this.lastContextActionAtMs = System.currentTimeMillis();
    }

    public long getLastContextActionAtMs() {
        return lastContextActionAtMs;
    }

    public long getLastClickActionAtMs() {
        return lastClickActionAtMs;
    }

    public void bindMessageActionPath(int messageId, String actionPath) {
        bindMessageContext(messageId, actionPath, this.currentGroup, false);
    }

    public void bindMessageContext(int messageId, String actionPath, ActionGroup group) {
        bindMessageContext(messageId, actionPath, group, false);
    }

    public void bindMessageContext(int messageId, String actionPath, ActionGroup group, boolean trackInFlight) {
        if ((bindSweepCounter.incrementAndGet() & 0x7F) == 0 || pendingMessages.size() > MAX_PENDING_MESSAGES) {
            sweepExpiredPendingMessages();
        }
        String resolvedPath = actionPath != null ? actionPath : currentActionPath;
        PendingMessageContext ctx = new PendingMessageContext(resolvedPath, group, trackInFlight);
        PendingMessageContext prev = pendingMessages.put(messageId, ctx);
        if (prev != null && prev.trackedInFlight) {
            decrementInFlight();
        }
        if (trackInFlight) {
            incrementInFlight();
        }
    }

    public boolean consumeTrackedInFlight(int messageId) {
        PendingMessageContext ctx = pendingMessages.get(messageId);
        if (ctx != null && ctx.trackedInFlight) {
            ctx.trackedInFlight = false;
            return true;
        }
        return false;
    }

    public String consumeMessageActionPath(int messageId) {
        PendingMessageContext ctx = pendingMessages.remove(messageId);
        if (ctx != null) {
            if (ctx.trackedInFlight) {
                ctx.trackedInFlight = false;
                decrementInFlight();
            }
            return ctx.actionPath != null ? ctx.actionPath : currentActionPath;
        }
        return currentActionPath;
    }

    public ActionGroup consumeMessageGroup(int messageId) {
        PendingMessageContext ctx = pendingMessages.get(messageId);
        return ctx != null ? ctx.group : null;
    }

    /**
     * Atomically consumes both actionPath and group for a messageId in a single lookup,
     * ensuring any remaining trackedInFlight flag is cleared cleanly.
     */
    public ActionContextSnapshot consumeMessageFullContext(int messageId) {
        PendingMessageContext ctx = pendingMessages.remove(messageId);
        if (ctx != null) {
            if (ctx.trackedInFlight) {
                ctx.trackedInFlight = false;
                decrementInFlight();
            }
            return new ActionContextSnapshot(
                ctx.actionPath != null ? ctx.actionPath : currentActionPath,
                ctx.group,
                ctx.createdAtMs);
        }
        return new ActionContextSnapshot(currentActionPath, currentGroup, 0L);
    }

    private void sweepExpiredPendingMessages() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, PendingMessageContext>> it = pendingMessages.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PendingMessageContext> e = it.next();
            PendingMessageContext ctx = e.getValue();
            if (now - ctx.createdAtMs > MESSAGE_TTL_MS) {
                if (pendingMessages.remove(e.getKey(), ctx) && ctx.trackedInFlight) {
                    ctx.trackedInFlight = false;
                    decrementInFlight();
                }
            }
        }
        // Hard cap fallback if huge concurrent burst exceeds MAX_PENDING_MESSAGES within 60s
        if (pendingMessages.size() > MAX_PENDING_MESSAGES) {
            int toDrop = pendingMessages.size() - (MAX_PENDING_MESSAGES / 2);
            Iterator<Map.Entry<Integer, PendingMessageContext>> evictIt = pendingMessages.entrySet().iterator();
            while (evictIt.hasNext() && toDrop > 0) {
                Map.Entry<Integer, PendingMessageContext> e = evictIt.next();
                PendingMessageContext ctx = e.getValue();
                if (pendingMessages.remove(e.getKey(), ctx)) {
                    if (ctx.trackedInFlight) {
                        ctx.trackedInFlight = false;
                        decrementInFlight();
                    }
                    toDrop--;
                }
            }
        }
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

    public void resetAll(String seedUrl) {
        resetGeneration.incrementAndGet();
        pendingMessages.clear();
        inFlightTargetRequests.set(0);
        actionGroups.clear();

        synchronized (frontierLock) {
            urlQueue.clear();
            seenQueueUrls.clear();
            visitedUrls.clear();
            patternSampleCounts.clear();
            pagesVisitedCount = 0;
            if (seedUrl != null && !seedUrl.trim().isEmpty()) {
                String norm = normalizeCrawlUrl(seedUrl);
                if (!norm.isEmpty()) {
                    seenQueueUrls.add(norm);
                    urlQueue.add(seedUrl.trim());
                }
            }
        }
        synchronized (clicksLock) {
            clickedSignaturesByRoute.clear();
            globalClickedSignatures.clear();
        }
        synchronized (discoveryLock) {
            skippedDangerActions.clear();
            seenSkippedKeys.clear();
            discoveredJsEndpoints.clear();
            extraCaptures.clear();
        }
        synchronized (trailLock) {
            trailStack.clear();
            deferredUrls.clear();
            completedUrls.clear();
            redirectCountsByNorm.clear();
            pageContextCache.clear();
            activeSessionTrail.clear();
            lastContextActionAtMs = 0L;
            lastClickActionAtMs = 0L;
            currentActionPath = "/ > page load";
            currentPageUrl = seedUrl != null ? seedUrl : "";
            currentGroup = null;
        }
        synchronized (authLock) {
            consecutiveAuthRejects = 0;
        }
    }

    public boolean enqueueUrl(String rawUrl, CrawlConfig config) {
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
            boolean isDeferred;
            synchronized (trailLock) {
                isDeferred = deferredUrls.contains(norm);
            }
            if (isDeferred) {
                return false;
            }
            synchronized (frontierLock) {
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
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public void markUrlVisited(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        String norm = normalizeCrawlUrl(rawUrl);
        if (norm.isEmpty()) {
            return;
        }
        synchronized (frontierLock) {
            if (visitedUrls.add(norm)) {
                pagesVisitedCount++;
            }
        }
    }

    public String pollNextUnvisitedUrl() {
        synchronized (frontierLock) {
            while (!urlQueue.isEmpty()) {
                String candidate = urlQueue.pollFirst();
                String norm = normalizeCrawlUrl(candidate);
                boolean deferred;
                synchronized (trailLock) {
                    deferred = deferredUrls.contains(norm);
                }
                if (!visitedUrls.contains(norm) && !deferred) {
                    return candidate;
                }
            }
            return null;
        }
    }

    public void markUrlDeferred(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        if (norm.isEmpty()) {
            return;
        }
        synchronized (trailLock) {
            deferredUrls.add(norm);
            completedUrls.add(norm);
        }
        synchronized (frontierLock) {
            visitedUrls.add(norm);
        }
    }

    public void markUrlCompleted(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        if (norm.isEmpty()) {
            return;
        }
        synchronized (trailLock) {
            completedUrls.add(norm);
        }
        synchronized (frontierLock) {
            if (visitedUrls.add(norm)) {
                pagesVisitedCount++;
            }
        }
    }

    public void markGlobalActionClicked(String signature) {
        if (signature == null || signature.trim().isEmpty()) {
            return;
        }
        synchronized (clicksLock) {
            if (globalClickedSignatures.size() < MAX_GLOBAL_SIGS) {
                globalClickedSignatures.add(signature.trim());
            }
        }
    }

    public List<String> getGlobalClickedSignatures() {
        synchronized (clicksLock) {
            return new ArrayList<>(globalClickedSignatures);
        }
    }

    public boolean isUrlDeferred(String rawUrl) {
        String norm = normalizeCrawlUrl(rawUrl);
        if (norm.isEmpty()) {
            return false;
        }
        synchronized (trailLock) {
            return deferredUrls.contains(norm);
        }
    }

    public void enterPageLevel(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        synchronized (trailLock) {
            enterPageLevelLocked(rawUrl);
        }
    }

    private void enterPageLevelLocked(String rawUrl) {
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

    public String getLastKnownSafeUrl() {
        synchronized (trailLock) {
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
    }

    public int getTrailDepth() {
        synchronized (trailLock) {
            return trailStack.size();
        }
    }

    public BacktrackResult recordRedirectBounce(String fromUrl, String destUrl, boolean markFromDeferred) {
        if (destUrl != null && !destUrl.trim().isEmpty()) {
            markUrlDeferred(destUrl);
        }
        String fromNormToVisit = null;
        synchronized (trailLock) {
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
                fromNormToVisit = fromNorm;
            }
        }
        if (fromNormToVisit != null) {
            synchronized (frontierLock) {
                visitedUrls.add(fromNormToVisit);
            }
        }
        return resolveBacktrackOrNextPage(
            (fromUrl != null && !fromUrl.trim().isEmpty()) ? fromUrl.trim() : this.currentPageUrl,
            true,
            false);
    }

    public BacktrackResult resolveBacktrackOrNextPage(String currentUrl, boolean preferBacktrack) {
        return resolveBacktrackOrNextPage(currentUrl, preferBacktrack, !preferBacktrack);
    }

    public BacktrackResult resolveBacktrackOrNextPage(
        String currentUrl,
        boolean preferBacktrack,
        boolean popCurrentLevel
    ) {
        int levelsBack = 0;
        String curNorm = normalizeCrawlUrl(currentUrl);
        synchronized (trailLock) {
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
        }
        String nextFromQueue = pollNextUnvisitedUrl();
        if (nextFromQueue != null && !nextFromQueue.isEmpty()) {
            return new BacktrackResult(nextFromQueue, levelsBack, true, false);
        }
        synchronized (trailLock) {
            if (preferBacktrack && !curNorm.isEmpty()
                && !deferredUrls.contains(curNorm) && !completedUrls.contains(curNorm)) {
                return new BacktrackResult(currentUrl, 0, false, false);
            }
        }
        return new BacktrackResult("", levelsBack, false, true);
    }

    public int getPendingQueueSize() {
        synchronized (frontierLock) {
            return urlQueue.size();
        }
    }

    public int getVisitedPagesCount() {
        return pagesVisitedCount;
    }

    public boolean markActionClicked(String routeKey, String signature) {
        if (routeKey == null || signature == null || signature.isEmpty()) {
            return false;
        }
        synchronized (clicksLock) {
            Set<String> set = clickedSignaturesByRoute.computeIfAbsent(routeKey, k -> new LinkedHashSet<>());
            if (set.size() >= MAX_SIGS_PER_ROUTE) {
                return false;
            }
            return set.add(signature);
        }
    }

    public List<String> getClickedSignatures(String routeKey) {
        synchronized (clicksLock) {
            Set<String> s = clickedSignaturesByRoute.get(routeKey);
            return s != null ? new ArrayList<>(s) : new ArrayList<>();
        }
    }

    public void recordSkippedDanger(String actionPath, String reason, String tag, String pageUrl) {
        String key = actionPath + "|" + reason;
        synchronized (discoveryLock) {
            if (skippedDangerActions.size() < MAX_SKIPPED_DANGER && seenSkippedKeys.add(key)) {
                skippedDangerActions.add(
                    new SkippedAction(actionPath, reason, tag, pageUrl, System.currentTimeMillis()));
            }
        }
    }

    public List<SkippedAction> getSkippedDangerActions() {
        synchronized (discoveryLock) {
            return new ArrayList<>(skippedDangerActions);
        }
    }

    public void addDiscoveredJsEndpoint(String item) {
        if (item == null || item.trim().isEmpty()) {
            return;
        }
        synchronized (discoveryLock) {
            if (discoveredJsEndpoints.size() < 3000) {
                discoveredJsEndpoints.add(item.trim());
            }
        }
    }

    public List<String> getDiscoveredJsEndpoints() {
        synchronized (discoveryLock) {
            return new ArrayList<>(discoveredJsEndpoints);
        }
    }

    public void addExtraCapture(CapturedApiEntry entry) {
        if (entry == null) {
            return;
        }
        synchronized (discoveryLock) {
            if (extraCaptures.size() < 2000) {
                extraCaptures.add(entry);
            }
        }
    }

    public List<CapturedApiEntry> getExtraCaptures() {
        synchronized (discoveryLock) {
            return new ArrayList<>(extraCaptures);
        }
    }

    public void noteAuthStatus(int statusCode, String locationHeader) {
        boolean authDrop = (statusCode == 401)
            || (statusCode >= 300 && statusCode < 400 && locationHeader != null
                && locationHeader.toLowerCase().contains("login"));
        synchronized (authLock) {
            if (authDrop) {
                consecutiveAuthRejects++;
            } else if (statusCode >= 200 && statusCode < 300) {
                consecutiveAuthRejects = 0;
            }
        }
    }

    public int getConsecutiveAuthRejects() {
        return consecutiveAuthRejects;
    }

    // =========================================================================
    // Structured Event Log Ring Buffer (Burp 2026 Dashboard)
    // =========================================================================

    public EventLogEntry addEventLog(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return null;
        }
        return addEventLog(
            EventLogEntry.inferLevel(rawMessage),
            EventLogEntry.inferSource(rawMessage),
            rawMessage.trim());
    }

    public EventLogEntry addEventLog(EventLogEntry.Level level, String source, String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        EventLogEntry entry = new EventLogEntry(
            eventLogSeq.incrementAndGet(),
            System.currentTimeMillis(),
            level,
            source,
            message.trim()
        );
        synchronized (eventLogLock) {
            while (eventLogs.size() >= MAX_EVENT_LOGS) {
                eventLogs.pollFirst();
            }
            eventLogs.addLast(entry);
            eventLogVersion.incrementAndGet();
        }
        return entry;
    }

    public List<EventLogEntry> snapshotEventLogs() {
        synchronized (eventLogLock) {
            return new ArrayList<>(eventLogs);
        }
    }

    public long getEventLogVersion() {
        return eventLogVersion.get();
    }

    public void clearEventLogs() {
        synchronized (eventLogLock) {
            eventLogs.clear();
            eventLogVersion.incrementAndGet();
        }
    }

    // =========================================================================
    // Visual Action Trail Replay Plan ("Tìm vị trí trên Web")
    // =========================================================================

    public ReplayPlan startReplayPlan(ApiCall call, boolean autoPlay) {
        if (call == null) {
            return null;
        }
        List<ActionStep> steps = call.getActionTrail();
        String fallbackUrl = call.capture != null ? call.capture.fullUrl : "";
        String startUrl = resolveStartUrl(steps, fallbackUrl);
        if (steps.isEmpty()) {
            steps = ActionStep.fallbackFromActionPath(call.displayName(), startUrl);
        }
        String endpoint = call.capture != null
            ? (call.capture.method + " " + call.displayPath())
            : call.displayPath();
        return installReplayPlan(call.displayName(), endpoint, startUrl, steps, autoPlay);
    }

    public ReplayPlan startReplayPlan(
        ActionGroup group,
        String label,
        String endpoint,
        String fallbackUrl,
        boolean autoPlay
    ) {
        List<ActionStep> steps = group != null
            ? group.getActionTrail()
            : ActionStep.fallbackFromActionPath(label, fallbackUrl);
        String groupUrl = group != null ? group.pageUrl : "";
        String startUrl = resolveStartUrl(steps, !groupUrl.isEmpty() ? groupUrl : fallbackUrl);
        if (steps.isEmpty()) {
            steps = ActionStep.fallbackFromActionPath(label, startUrl);
        }
        String title = (label != null && !label.trim().isEmpty())
            ? label.trim()
            : (group != null ? group.actionPath : "");
        return installReplayPlan(title, endpoint != null ? endpoint : "", startUrl, steps, autoPlay);
    }

    private ReplayPlan installReplayPlan(
        String title,
        String endpoint,
        String startUrl,
        List<ActionStep> steps,
        boolean autoPlay
    ) {
        String id = "replay-" + replayPlanSeq.incrementAndGet();
        ReplayPlan plan = new ReplayPlan(id, title, endpoint, startUrl, steps, autoPlay);
        synchronized (replayLock) {
            this.activeReplayPlan = plan;
        }
        addEventLog(EventLogEntry.Level.INFO, "Replay",
            "Khởi tạo Replay Spotlight (" + (autoPlay ? "Tự chạy" : "Thủ công")
                + ", " + steps.size() + " bước): " + endpoint);
        return plan;
    }

    private static String resolveStartUrl(List<ActionStep> steps, String fallbackUrl) {
        if (steps != null) {
            for (ActionStep s : steps) {
                if (s.pageUrl != null && (s.pageUrl.startsWith("http://") || s.pageUrl.startsWith("https://"))) {
                    return s.pageUrl;
                }
            }
        }
        return fallbackUrl != null ? fallbackUrl : "";
    }

    public ReplayPlan getActiveReplayPlan() {
        return activeReplayPlan;
    }

    public boolean hasActiveReplayPlan() {
        ReplayPlan rp = activeReplayPlan;
        return rp != null && rp.isActive();
    }

    public boolean isReplayHostAllowed(String host) {
        ReplayPlan rp = activeReplayPlan;
        if (rp == null || !rp.isActive() || host == null || host.trim().isEmpty()) {
            return false;
        }
        String h = host.trim().toLowerCase();
        if (urlMatchesHost(rp.getStartUrl(), h)) {
            return true;
        }
        for (ActionStep s : rp.getSteps()) {
            if (urlMatchesHost(s.pageUrl, h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean urlMatchesHost(String rawUrl, String hostLower) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return false;
        }
        try {
            URI u = URI.create(rawUrl);
            String uh = u.getHost();
            return uh != null && uh.equalsIgnoreCase(hostLower);
        } catch (Exception ignored) {
            return false;
        }
    }

    public ReplayPlan updateReplayStep(String planId, int index, boolean autoPlay) {
        return updateReplayStep(planId, Integer.valueOf(index), Boolean.valueOf(autoPlay));
    }

    public ReplayPlan updateReplayStep(String planId, Integer index, Boolean autoPlay) {
        synchronized (replayLock) {
            ReplayPlan rp = activeReplayPlan;
            if (rp == null || !rp.isActive()) {
                return null;
            }
            if (planId != null && !planId.isEmpty() && !planId.equals(rp.getPlanId())) {
                return rp;
            }
            if (index != null) {
                rp.setCurrentIndex(index);
            }
            if (autoPlay != null) {
                rp.setAutoPlay(autoPlay);
            }
            return rp;
        }
    }

    public void stopReplayPlan(String planId) {
        synchronized (replayLock) {
            ReplayPlan rp = activeReplayPlan;
            if (rp == null) {
                return;
            }
            if (planId == null || planId.isEmpty() || planId.equals(rp.getPlanId())) {
                rp.setActive(false);
                activeReplayPlan = null;
            }
        }
    }

    public void updateAgentTelemetry(String phase, String currentUrl, int actionsDoneOnPage, int totalTargetsOnPage) {
        long now = System.currentTimeMillis();
        synchronized (telemetryLock) {
            if (phase != null && !phase.trim().isEmpty()) {
                this.telemetryPhase = phase.trim().toUpperCase();
            }
            if (currentUrl != null && !currentUrl.trim().isEmpty()) {
                this.telemetryUrl = currentUrl.trim();
            }
            this.telemetryDone = Math.max(0, actionsDoneOnPage);
            this.telemetryTotal = Math.max(0, totalTargetsOnPage);
            this.telemetryLastHeartbeatMs = now;
        }
    }

    public void touchAgentHeartbeat(String currentUrl) {
        long now = System.currentTimeMillis();
        synchronized (telemetryLock) {
            if (currentUrl != null && !currentUrl.trim().isEmpty()) {
                this.telemetryUrl = currentUrl.trim();
            }
            this.telemetryLastHeartbeatMs = now;
        }
    }

    public AgentTelemetry getAgentTelemetrySnapshot() {
        long now = System.currentTimeMillis();
        synchronized (telemetryLock) {
            boolean connected = telemetryLastHeartbeatMs > 0L
                && (now - telemetryLastHeartbeatMs) < TELEMETRY_CONNECTED_WINDOW_MS;
            return new AgentTelemetry(
                connected,
                telemetryPhase,
                telemetryUrl,
                telemetryDone,
                telemetryTotal,
                telemetryLastHeartbeatMs
            );
        }
    }

    public void setPendingInterceptCountSupplier(java.util.function.IntSupplier supplier) {
        this.pendingInterceptCountSupplier = supplier;
    }

    public int getPendingInterceptCount() {
        java.util.function.IntSupplier s = this.pendingInterceptCountSupplier;
        return s != null ? Math.max(0, s.getAsInt()) : 0;
    }

    /**
     * Resolves a clean SPA page route discovered in a JS bundle against {@code sourceJsUrl}'s origin
     * and enqueues it into the crawl frontier when {@code deepCrawlMode} is enabled.
     */
    public boolean enqueueJsRouteIfEligible(String discoveredPath, String sourceJsUrl, CrawlConfig cfg) {
        if (cfg == null || !cfg.isDeepCrawlMode() || discoveredPath == null || sourceJsUrl == null) {
            return false;
        }
        String p = discoveredPath.trim();
        if (p.length() < 2 || p.length() > 140 || p.startsWith("//")) {
            return false;
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String lower = p.toLowerCase();
        if (lower.startsWith("/api/") || lower.contains("/v1/") || lower.contains("/v2/")
            || lower.contains("/v3/") || lower.contains("/graphql") || lower.contains("/rest/")
            || p.contains("{") || p.contains(":") || p.contains("*") || p.contains("..")
            || JS_ROUTE_STATIC_EXT.matcher(p).matches()) {
            return false;
        }
        try {
            URI src = URI.create(sourceJsUrl.trim());
            if (src.getScheme() == null || src.getHost() == null || !cfg.isHostAllowed(src.getHost())) {
                return false;
            }
            String portPart = src.getPort() > 0 ? (":" + src.getPort()) : "";
            String origin = src.getScheme() + "://" + src.getHost() + portPart;
            return enqueueUrl(origin + p, cfg);
        } catch (Exception ignored) {
            return false;
        }
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
