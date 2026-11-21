package com.mythofcrawl.crawler.core.sync;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 200ms coalesced event batcher for high-rate crawl updates.
 * Guarantees that both {@code treeStale} and {@code tableStale} are marked {@code true}
 * whenever {@code dirty}, {@code added}, or {@code structure} fires.
 */
public final class ChangesBatch {

    public static final long DEFAULT_INTERVAL_MS = 200L;

    public static final class BatchSnapshot {
        public final int addedCount;
        public final boolean dirty;
        public final boolean structureChanged;
        public final boolean statusChanged;
        public final boolean logAppended;
        public final boolean treeStale;
        public final boolean tableStale;
        public final long flushedAtMs;

        public BatchSnapshot(
            int addedCount,
            boolean dirty,
            boolean structureChanged,
            boolean statusChanged,
            boolean logAppended,
            boolean treeStale,
            boolean tableStale,
            long flushedAtMs
        ) {
            this.addedCount = addedCount;
            this.dirty = dirty;
            this.structureChanged = structureChanged;
            this.statusChanged = statusChanged;
            this.logAppended = logAppended;
            this.treeStale = treeStale;
            this.tableStale = tableStale;
            this.flushedAtMs = flushedAtMs;
        }

        public boolean hasAnyChange() {
            return addedCount > 0 || dirty || structureChanged || statusChanged || logAppended
                || treeStale || tableStale;
        }
    }

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Consumer<BatchSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile long intervalMs;
    private ScheduledFuture<?> scheduledFlush;

    private int addedCount = 0;
    private boolean dirty = false;
    private boolean structureChanged = false;
    private boolean statusChanged = false;
    private boolean logAppended = false;
    private boolean treeStale = false;
    private boolean tableStale = false;

    public ChangesBatch() {
        this(DEFAULT_INTERVAL_MS);
    }

    public ChangesBatch(long intervalMs) {
        this.intervalMs = Math.max(10L, intervalMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MythCrawler-ChangesBatch");
            t.setDaemon(true);
            return t;
        });
    }

    public InstantEventBus.Subscription onFlush(Consumer<BatchSnapshot> listener) {
        if (listener == null) {
            return () -> {};
        }
        listeners.add(listener);
        AtomicBoolean removed = new AtomicBoolean(false);
        return () -> {
            if (removed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        };
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(10L, intervalMs);
    }

    public void markAdded(int count) {
        synchronized (lock) {
            addedCount += Math.max(1, count);
            dirty = true;
            treeStale = true;
            tableStale = true;
            scheduleLocked();
        }
    }

    public void markDirty() {
        synchronized (lock) {
            dirty = true;
            treeStale = true;
            tableStale = true;
            scheduleLocked();
        }
    }

    public void markStructure() {
        synchronized (lock) {
            structureChanged = true;
            dirty = true;
            treeStale = true;
            tableStale = true;
            scheduleLocked();
        }
    }

    public void markStatus() {
        synchronized (lock) {
            statusChanged = true;
            scheduleLocked();
        }
    }

    public void markLog() {
        synchronized (lock) {
            logAppended = true;
            scheduleLocked();
        }
    }

    public BatchSnapshot peekPending() {
        synchronized (lock) {
            return new BatchSnapshot(
                addedCount, dirty, structureChanged, statusChanged, logAppended,
                treeStale, tableStale, System.currentTimeMillis());
        }
    }

    public BatchSnapshot flushNow() {
        BatchSnapshot snap;
        synchronized (lock) {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            snap = new BatchSnapshot(
                addedCount, dirty, structureChanged, statusChanged, logAppended,
                treeStale, tableStale, System.currentTimeMillis());
            addedCount = 0;
            dirty = false;
            structureChanged = false;
            statusChanged = false;
            logAppended = false;
            treeStale = false;
            tableStale = false;
        }
        if (snap.hasAnyChange()) {
            for (Consumer<BatchSnapshot> l : listeners) {
                try {
                    l.accept(snap);
                } catch (Exception ignored) {
                }
            }
        }
        return snap;
    }

    private void scheduleLocked() {
        if (closed.get() || scheduledFlush != null) {
            return;
        }
        scheduledFlush = scheduler.schedule(this::flushNow, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            synchronized (lock) {
                if (scheduledFlush != null) {
                    scheduledFlush.cancel(false);
                    scheduledFlush = null;
                }
            }
            scheduler.shutdownNow();
            listeners.clear();
        }
    }
}
