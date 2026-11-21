package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A UI action (page load, button, link, tab, select, form submit, search input, scroll...)
 * and every API it triggered. Groups are keyed by their action path, so repeating the
 * same action merges into the same group.
 *
 * Uses a {@link ReentrantReadWriteLock} and an O(1) internal dedup index so concurrent
 * EDT snapshots and proxy capture threads do not contend on coarse monitors.
 */
public final class ActionGroup {

    public final int id;
    public final String actionPath;
    public final String kind;
    public final String label;
    public final String pageUrl;
    public final long firstSeenMs;

    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private volatile long lastSeenMs;
    private int triggerCount = 1;
    private final List<ApiCall> calls = new ArrayList<>();
    private final Map<String, ApiCall> dedupIndex = new HashMap<>();
    private final Map<String, ApiCall> firstByEndpoint = new HashMap<>();
    private final List<AuditNote> groupNotes = new ArrayList<>();
    private volatile List<ActionStep> actionTrail = Collections.emptyList();

    public ActionGroup(int id, String actionPath, String kind, String label, String pageUrl, long nowMs) {
        this.id = id;
        this.actionPath = actionPath;
        this.kind = kind != null ? kind : "";
        this.label = label != null ? label : "";
        this.pageUrl = pageUrl != null ? pageUrl : "";
        this.firstSeenMs = nowMs;
        this.lastSeenMs = nowMs;
    }

    void touch() {
        rwl.writeLock().lock();
        try {
            triggerCount++;
            lastSeenMs = System.currentTimeMillis();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    ApiCall findDuplicateCall(String endpointKey, String paramSig, int statusCode) {
        String key = dedupKey(endpointKey, paramSig, statusCode);
        rwl.readLock().lock();
        try {
            return dedupIndex.get(key);
        } finally {
            rwl.readLock().unlock();
        }
    }

    ApiCall firstCallForEndpoint(String endpointKey) {
        rwl.readLock().lock();
        try {
            return firstByEndpoint.get(endpointKey);
        } finally {
            rwl.readLock().unlock();
        }
    }

    void addCall(ApiCall call) {
        rwl.writeLock().lock();
        try {
            calls.add(call);
            String dKey = dedupKey(call.endpointKey, call.paramSig, call.capture.statusCode);
            dedupIndex.putIfAbsent(dKey, call);
            firstByEndpoint.putIfAbsent(call.endpointKey, call);
            lastSeenMs = System.currentTimeMillis();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    void addGroupNote(AuditNote note) {
        if (note == null) {
            return;
        }
        rwl.writeLock().lock();
        try {
            groupNotes.add(note);
            lastSeenMs = System.currentTimeMillis();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    public List<AuditNote> getGroupNotes() {
        rwl.readLock().lock();
        try {
            return new ArrayList<>(groupNotes);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public List<ApiCall> getCalls() {
        rwl.readLock().lock();
        try {
            return new ArrayList<>(calls);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int callCount() {
        rwl.readLock().lock();
        try {
            return calls.size();
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int getTriggerCount() {
        rwl.readLock().lock();
        try {
            return triggerCount;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    public void setActionTrail(List<ActionStep> trail) {
        if (trail == null || trail.isEmpty()) {
            return;
        }
        List<ActionStep> existing = this.actionTrail;
        boolean incomingHasLocator = trail.stream().anyMatch(ActionStep::hasDomLocator);
        boolean existingHasLocator = existing.stream().anyMatch(ActionStep::hasDomLocator);
        if (existing.isEmpty() || (incomingHasLocator && !existingHasLocator) || trail.size() >= existing.size()) {
            this.actionTrail = Collections.unmodifiableList(new ArrayList<>(trail));
        }
    }

    public List<ActionStep> getActionTrail() {
        List<ActionStep> t = this.actionTrail;
        if (t != null && !t.isEmpty()) {
            return t;
        }
        return ActionStep.fallbackFromActionPath(actionPath, pageUrl);
    }

    public int noteCount() {
        rwl.readLock().lock();
        try {
            int n = groupNotes.size();
            for (ApiCall c : calls) {
                n += c.noteCount();
            }
            return n;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int excelMatchedCount() {
        rwl.readLock().lock();
        try {
            int n = 0;
            for (ApiCall c : calls) {
                if (c.inExcel) {
                    n++;
                }
            }
            return n;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public AuditNote.Severity worstSeverity() {
        rwl.readLock().lock();
        try {
            AuditNote.Severity w = null;
            for (AuditNote n : groupNotes) {
                w = AuditNote.worst(w, n.severity);
            }
            for (ApiCall c : calls) {
                w = AuditNote.worst(w, c.worstSeverity());
            }
            return w;
        } finally {
            rwl.readLock().unlock();
        }
    }

    private static String dedupKey(String endpointKey, String paramSig, int statusCode) {
        return (endpointKey != null ? endpointKey : "")
            + "\u0000" + (paramSig != null ? paramSig : "")
            + "\u0000" + statusCode;
    }
}
