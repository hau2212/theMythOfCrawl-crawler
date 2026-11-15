package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A UI action (page load, button, link, tab, select, form submit, search input, scroll...)
 * and every API it triggered. Groups are keyed by their action path, so repeating the
 * same action merges into the same group.
 */
public final class ActionGroup {

    public final int id;
    public final String actionPath;
    public final String kind;
    public final String label;
    public final String pageUrl;
    public final long firstSeenMs;

    private long lastSeenMs;
    private int triggerCount = 1;
    private final List<ApiCall> calls = new ArrayList<>();

    public ActionGroup(int id, String actionPath, String kind, String label, String pageUrl, long nowMs) {
        this.id = id;
        this.actionPath = actionPath;
        this.kind = kind != null ? kind : "";
        this.label = label != null ? label : "";
        this.pageUrl = pageUrl != null ? pageUrl : "";
        this.firstSeenMs = nowMs;
        this.lastSeenMs = nowMs;
    }

    synchronized void touch() {
        triggerCount++;
        lastSeenMs = System.currentTimeMillis();
    }

    synchronized void addCall(ApiCall call) {
        calls.add(call);
        lastSeenMs = System.currentTimeMillis();
    }

    synchronized List<ApiCall> callsView() {
        return calls;
    }

    public synchronized List<ApiCall> getCalls() {
        return new ArrayList<>(calls);
    }

    public synchronized int callCount() {
        return calls.size();
    }

    public synchronized int getTriggerCount() {
        return triggerCount;
    }

    public synchronized long getLastSeenMs() {
        return lastSeenMs;
    }

    public synchronized int noteCount() {
        int n = 0;
        for (ApiCall c : calls) {
            n += c.getNotes().size();
        }
        return n;
    }

    public synchronized int excelMatchedCount() {
        int n = 0;
        for (ApiCall c : calls) {
            if (c.inExcel) {
                n++;
            }
        }
        return n;
    }

    public synchronized AuditNote.Severity worstSeverity() {
        AuditNote.Severity w = null;
        for (ApiCall c : calls) {
            w = AuditNote.worst(w, c.worstSeverity());
        }
        return w;
    }
}
