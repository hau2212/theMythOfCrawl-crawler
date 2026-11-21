package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.core.sync.InstantEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe bounded ring buffer (max 2000) recording every Action Guardrail decision
 * (ALLOW, INTERCEPT_WAIT, HOLD_AT_PROXY, DROP, FORWARD, SAVE_DRAFT, LOCK_2XX, LIVE_ID_POOL, PRODUCER_RECREATE).
 */
public final class ActionGateAuditLog {

    public static final int MAX_ENTRIES = 2000;

    public enum Outcome {
        ALLOW,
        INTERCEPT_WAIT,
        HOLD_AT_PROXY,
        DROP,
        FORWARD,
        SAVE_DRAFT,
        LOCK_2XX,
        LIVE_ID_POOL,
        PRODUCER_RECREATE
    }

    public static final class AuditEntry {
        public final long id;
        public final long timestampMs;
        public final Outcome outcome;
        public final String branchPath;
        public final String host;
        public final String method;
        public final String path;
        public final String uiAction;
        public final String winningRuleId;
        public final String winningRuleName;
        public final String reason;

        public AuditEntry(
            long id,
            long timestampMs,
            Outcome outcome,
            String branchPath,
            String host,
            String method,
            String path,
            String uiAction,
            String winningRuleId,
            String winningRuleName,
            String reason
        ) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.outcome = outcome;
            this.branchPath = branchPath != null ? branchPath : "";
            this.host = host != null ? host : "";
            this.method = method != null ? method : "";
            this.path = path != null ? path : "";
            this.uiAction = uiAction != null ? uiAction : "";
            this.winningRuleId = winningRuleId != null ? winningRuleId : "";
            this.winningRuleName = winningRuleName != null ? winningRuleName : "";
            this.reason = reason != null ? reason : "";
        }
    }

    private final Object lock = new Object();
    private final List<AuditEntry> entries = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<AuditEntry>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final int maxCapacity;

    public ActionGateAuditLog() {
        this(MAX_ENTRIES);
    }

    public ActionGateAuditLog(int maxCapacity) {
        this.maxCapacity = Math.max(10, maxCapacity);
    }

    public InstantEventBus.Subscription subscribe(Consumer<AuditEntry> listener) {
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

    public AuditEntry record(
        Outcome outcome,
        String branchPath,
        String host,
        String method,
        String path,
        String uiAction,
        PolicyRule winningRule,
        String reason
    ) {
        AuditEntry entry = new AuditEntry(
            seq.getAndIncrement(),
            System.currentTimeMillis(),
            outcome != null ? outcome : Outcome.ALLOW,
            branchPath,
            host,
            method,
            path,
            uiAction,
            winningRule != null ? winningRule.id : "",
            winningRule != null ? winningRule.name : "",
            reason
        );
        synchronized (lock) {
            entries.add(entry);
            while (entries.size() > maxCapacity) {
                entries.remove(0);
            }
        }
        for (Consumer<AuditEntry> l : listeners) {
            try {
                l.accept(entry);
            } catch (Exception ignored) {
            }
        }
        return entry;
    }

    public List<AuditEntry> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(entries);
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }
}
