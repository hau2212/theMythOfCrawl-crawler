package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.core.sync.InstantEventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe Proxy Intercept Queue for {@link GuardrailMode#INTERCEPT_WAIT}.
 * Holds proxy request threads via {@link CompletableFuture} with a mandatory fail-safe
 * timeout resolving to {@link ResolutionAction#DROP}, supports 0ms UI push notifications,
 * raw HTTP editing (Forward / Save Draft / Drop), Drop All / Forward All, and defaults
 * {@code autoOpenWindow = false} so popups never ambush the user.
 */
public final class ProxyInterceptQueue {

    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    public enum ResolutionAction {
        FORWARD,
        SAVE_DRAFT,
        DROP
    }

    public static final class InterceptResolution {
        public final ResolutionAction action;
        public final byte[] editedRawBytes;
        public final String note;
        public final long resolvedAtMs;

        public InterceptResolution(ResolutionAction action, byte[] editedRawBytes, String note) {
            this.action = action != null ? action : ResolutionAction.DROP;
            this.editedRawBytes = editedRawBytes;
            this.note = note != null ? note : "";
            this.resolvedAtMs = System.currentTimeMillis();
        }
    }

    public static final class PendingInterceptItem {
        public final String id;
        public final String branchPath;
        public final String host;
        public final String method;
        public final String path;
        public final String rawRequestIso88591;
        public final String reason;
        public final String winningRuleName;
        public final long createdAtMs;
        public final long timeoutMs;

        public PendingInterceptItem(
            String id,
            String branchPath,
            String host,
            String method,
            String path,
            String rawRequestIso88591,
            String reason,
            String winningRuleName,
            long createdAtMs,
            long timeoutMs
        ) {
            this.id = id;
            this.branchPath = branchPath != null ? branchPath : "";
            this.host = host != null ? host : "";
            this.method = method != null ? method : "GET";
            this.path = path != null ? path : "/";
            this.rawRequestIso88591 = rawRequestIso88591 != null ? rawRequestIso88591 : "";
            this.reason = reason != null ? reason : "";
            this.winningRuleName = winningRuleName != null ? winningRuleName : "";
            this.createdAtMs = createdAtMs;
            this.timeoutMs = timeoutMs;
        }
    }

    public static final class SavedDraftItem {
        public final String id;
        public final String branchPath;
        public final String host;
        public final String method;
        public final String path;
        public final byte[] rawRequestBytes;
        public final String rawRequestIso88591;
        public final long savedAtMs;

        public SavedDraftItem(
            String id,
            String branchPath,
            String host,
            String method,
            String path,
            byte[] rawRequestBytes
        ) {
            this.id = id;
            this.branchPath = branchPath != null ? branchPath : "";
            this.host = host != null ? host : "";
            this.method = method != null ? method : "GET";
            this.path = path != null ? path : "/";
            this.rawRequestBytes = rawRequestBytes != null ? rawRequestBytes : new byte[0];
            this.rawRequestIso88591 = HttpEncodingGuard.fromIso88591Bytes(this.rawRequestBytes);
            this.savedAtMs = System.currentTimeMillis();
        }
    }

    public enum QueueEventKind {
        ENQUEUED,
        RESOLVED,
        TIMEOUT_DROP,
        CLEARED
    }

    public static final class QueueEvent {
        public final QueueEventKind kind;
        public final PendingInterceptItem item;
        public final InterceptResolution resolution;
        public final int remainingCount;

        public QueueEvent(
            QueueEventKind kind,
            PendingInterceptItem item,
            InterceptResolution resolution,
            int remainingCount
        ) {
            this.kind = kind;
            this.item = item;
            this.resolution = resolution;
            this.remainingCount = remainingCount;
        }
    }

    private static final class Entry {
        final PendingInterceptItem item;
        final CompletableFuture<InterceptResolution> future;

        Entry(PendingInterceptItem item, CompletableFuture<InterceptResolution> future) {
            this.item = item;
            this.future = future;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private final List<SavedDraftItem> drafts = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<QueueEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);
    private volatile boolean autoOpenWindow = false;

    public boolean isAutoOpenWindow() {
        return autoOpenWindow;
    }

    public void setAutoOpenWindow(boolean autoOpenWindow) {
        this.autoOpenWindow = autoOpenWindow;
    }

    public InstantEventBus.Subscription subscribe(Consumer<QueueEvent> listener) {
        if (listener == null) {
            return () -> {};
        }
        listeners.add(listener);
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        };
    }

    public CompletableFuture<InterceptResolution> enqueue(
        String branchPath,
        String host,
        String method,
        String path,
        byte[] rawRequestBytes,
        String reason,
        String winningRuleName,
        long timeoutMs
    ) {
        String id = "ic-" + seq.getAndIncrement();
        long effTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        String rawIso = HttpEncodingGuard.fromIso88591Bytes(rawRequestBytes);
        PendingInterceptItem item = new PendingInterceptItem(
            id, branchPath, host, method, path, rawIso, reason, winningRuleName,
            System.currentTimeMillis(), effTimeout);
        CompletableFuture<InterceptResolution> fut = new CompletableFuture<>();
        int count;
        synchronized (lock) {
            pending.put(id, new Entry(item, fut));
            count = pending.size();
        }
        emit(new QueueEvent(QueueEventKind.ENQUEUED, item, null, count));
        return fut;
    }

    /**
     * Enqueues a request and blocks the calling proxy worker up to {@code timeoutMs}.
     * On timeout or interruption, fail-safe completes with {@link ResolutionAction#DROP}.
     */
    public InterceptResolution enqueueAndWait(
        String branchPath,
        String host,
        String method,
        String path,
        byte[] rawRequestBytes,
        String reason,
        String winningRuleName,
        long timeoutMs
    ) {
        long effTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        CompletableFuture<InterceptResolution> fut = enqueue(
            branchPath, host, method, path, rawRequestBytes, reason, winningRuleName, effTimeout);
        try {
            return fut.get(effTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            InterceptResolution timeoutDrop = new InterceptResolution(
                ResolutionAction.DROP, null, "Fail-safe timeout/interrupted -> DROP");
            PendingInterceptItem removedItem = null;
            int count;
            synchronized (lock) {
                for (Map.Entry<String, Entry> e : pending.entrySet()) {
                    if (e.getValue().future == fut) {
                        removedItem = e.getValue().item;
                        pending.remove(e.getKey());
                        break;
                    }
                }
                count = pending.size();
            }
            fut.complete(timeoutDrop);
            if (removedItem != null) {
                emit(new QueueEvent(QueueEventKind.TIMEOUT_DROP, removedItem, timeoutDrop, count));
            }
            return timeoutDrop;
        }
    }

    public boolean resolveForward(String id, byte[] editedRawBytes) {
        return resolveInternal(id, ResolutionAction.FORWARD, editedRawBytes, "Forwarded by user");
    }

    public boolean resolveForward(String id, String editedRawIso88591) {
        byte[] bytes = HttpEncodingGuard.toIso88591Bytes(editedRawIso88591);
        return resolveInternal(id, ResolutionAction.FORWARD, bytes, "Forwarded by user");
    }

    public boolean resolveSaveDraft(String id, byte[] editedRawBytes) {
        return resolveInternal(id, ResolutionAction.SAVE_DRAFT, editedRawBytes, "Saved as draft (dropped from proxy)");
    }

    public boolean resolveSaveDraft(String id, String editedRawIso88591) {
        byte[] bytes = HttpEncodingGuard.toIso88591Bytes(editedRawIso88591);
        return resolveInternal(id, ResolutionAction.SAVE_DRAFT, bytes, "Saved as draft (dropped from proxy)");
    }

    public boolean resolveDrop(String id) {
        return resolveInternal(id, ResolutionAction.DROP, null, "Dropped by user");
    }

    public int dropAll() {
        List<Entry> all;
        synchronized (lock) {
            all = new ArrayList<>(pending.values());
            pending.clear();
        }
        InterceptResolution res = new InterceptResolution(ResolutionAction.DROP, null, "Drop All");
        for (Entry e : all) {
            e.future.complete(res);
        }
        if (!all.isEmpty()) {
            emit(new QueueEvent(QueueEventKind.CLEARED, all.get(0).item, res, 0));
        }
        return all.size();
    }

    public int forwardAll() {
        List<Entry> all;
        synchronized (lock) {
            all = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (Entry e : all) {
            byte[] raw = HttpEncodingGuard.toIso88591Bytes(e.item.rawRequestIso88591);
            e.future.complete(new InterceptResolution(ResolutionAction.FORWARD, raw, "Forward All"));
        }
        if (!all.isEmpty()) {
            emit(new QueueEvent(QueueEventKind.CLEARED, all.get(0).item, null, 0));
        }
        return all.size();
    }

    private boolean resolveInternal(String id, ResolutionAction action, byte[] rawBytes, String note) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        Entry removed;
        int count;
        synchronized (lock) {
            removed = pending.remove(id);
            if (removed != null && action == ResolutionAction.SAVE_DRAFT) {
                byte[] effectiveBytes = (rawBytes != null && rawBytes.length > 0)
                    ? rawBytes
                    : HttpEncodingGuard.toIso88591Bytes(removed.item.rawRequestIso88591);
                drafts.add(new SavedDraftItem(
                    removed.item.id, removed.item.branchPath, removed.item.host,
                    removed.item.method, removed.item.path, effectiveBytes));
                while (drafts.size() > 200) {
                    drafts.remove(0);
                }
            }
            count = pending.size();
        }
        if (removed == null) {
            return false;
        }
        InterceptResolution res = new InterceptResolution(action, rawBytes, note);
        removed.future.complete(res);
        emit(new QueueEvent(QueueEventKind.RESOLVED, removed.item, res, count));
        return true;
    }

    public PendingInterceptItem getPending(String id) {
        synchronized (lock) {
            Entry e = pending.get(id);
            return e != null ? e.item : null;
        }
    }

    public List<PendingInterceptItem> snapshotPending() {
        synchronized (lock) {
            List<PendingInterceptItem> out = new ArrayList<>(pending.size());
            for (Entry e : pending.values()) {
                out.add(e.item);
            }
            return out;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public List<SavedDraftItem> snapshotDrafts() {
        synchronized (lock) {
            return new ArrayList<>(drafts);
        }
    }

    public void clearDrafts() {
        synchronized (lock) {
            drafts.clear();
        }
    }

    private void emit(QueueEvent ev) {
        for (Consumer<QueueEvent> l : listeners) {
            try {
                l.accept(ev);
            } catch (Exception ignored) {
            }
        }
    }
}
