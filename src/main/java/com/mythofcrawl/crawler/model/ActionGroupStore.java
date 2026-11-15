package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe store of action groups, the API calls they triggered, and audit NOTEs.
 *
 * Rules:
 * - One group per action path; repeating an action merges into it.
 * - Inside a group, identical calls (same endpoint + same parameter keys + same status) only bump a hit counter.
 * - Same endpoint with different parameter keys (in the same group or vs. the first time it was seen elsewhere)
 *   produces a PARAM_DIFF note; a new HTTP status for a known endpoint produces STATUS_DIFF.
 * - HTTP 5xx / 4xx / 401-403, APIs outside Excel and Excel primary upgrades are also noted.
 */
public final class ActionGroupStore {

    private static final int MAX_GROUPS = 8000;
    private static final int MAX_CALLS_PER_GROUP = 500;
    private static final int MAX_NOTES = 30000;

    private static final class EndpointStats {
        final Set<String> firstParams;
        final String firstGroupPath;
        final Set<Integer> groupIds = new LinkedHashSet<>();
        final Map<String, String> paramSigToGroupPath = new LinkedHashMap<>();
        final Set<Integer> statuses = new LinkedHashSet<>();

        EndpointStats(Set<String> firstParams, String firstGroupPath) {
            this.firstParams = new LinkedHashSet<>(firstParams);
            this.firstGroupPath = firstGroupPath;
        }
    }

    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicLong version = new AtomicLong(0);
    private final LinkedHashMap<String, ActionGroup> byPath = new LinkedHashMap<>();
    private final Map<Integer, ActionGroup> byId = new HashMap<>();
    private final Map<String, EndpointStats> endpoints = new HashMap<>();
    private final List<AuditNote> notes = new ArrayList<>();
    private int totalCalls = 0;
    private final int[] severityCounts = new int[AuditNote.Severity.values().length];

    public long version() {
        return version.get();
    }

    public synchronized ActionGroup openGroup(String actionPath, String kind, String label, String pageUrl) {
        String key = actionPath != null && !actionPath.trim().isEmpty() ? actionPath.trim() : "/ > (unknown)";
        ActionGroup g = byPath.get(key);
        if (g != null) {
            g.touch();
            version.incrementAndGet();
            return g;
        }
        if (byPath.size() >= MAX_GROUPS) {
            key = "/ > (vượt giới hạn nhóm)";
            g = byPath.get(key);
            if (g != null) {
                return g;
            }
        }
        g = new ActionGroup(seq.incrementAndGet(), key, kind, label, pageUrl, System.currentTimeMillis());
        byPath.put(key, g);
        byId.put(g.id, g);
        version.incrementAndGet();
        return g;
    }

    public synchronized ActionGroup getGroup(int id) {
        return byId.get(id);
    }

    public synchronized ActionGroup findByPath(String actionPath) {
        return actionPath != null ? byPath.get(actionPath.trim()) : null;
    }

    /**
     * Records one API call into the group and computes audit notes.
     *
     * @return the stored (or de-duplicated existing) call, or null when the group is full.
     */
    public synchronized ApiCall recordCall(
        ActionGroup group,
        CapturedApiEntry cap,
        String endpointKey,
        String graphqlOp,
        String excelStt,
        String excelPath,
        boolean excelLoaded,
        boolean excelUpgraded
    ) {
        if (group == null || cap == null) {
            return null;
        }
        String sig = paramSig(cap.paramKeys);

        ApiCall sameEndpointInGroup = null;
        for (ApiCall c : group.callsView()) {
            if (c.endpointKey.equals(endpointKey)) {
                if (paramSig(c.capture.paramKeys).equals(sig) && c.capture.statusCode == cap.statusCode) {
                    c.incrementHits();
                    version.incrementAndGet();
                    return c;
                }
                if (sameEndpointInGroup == null) {
                    sameEndpointInGroup = c;
                }
            }
        }
        if (group.callCount() >= MAX_CALLS_PER_GROUP) {
            return null;
        }

        ApiCall call = new ApiCall(group.id, group.actionPath, group.callCount() + 1, cap,
            endpointKey, graphqlOp, excelStt, excelPath);
        group.addCall(call);
        totalCalls++;

        EndpointStats es = endpoints.get(endpointKey);
        int status = cap.statusCode;

        if (sameEndpointInGroup != null) {
            Set<String> prev = sameEndpointInGroup.capture.paramKeys;
            if (!paramSig(prev).equals(sig)) {
                addNote(call, AuditNote.Severity.WARN, AuditNote.Type.PARAM_DIFF,
                    "Cùng nhóm, khác tham số so với API #" + sameEndpointInGroup.index + describeDiff(cap.paramKeys, prev));
            } else if (sameEndpointInGroup.capture.statusCode != status) {
                addNote(call, AuditNote.Severity.WARN, AuditNote.Type.STATUS_DIFF,
                    "Cùng nhóm, HTTP " + status + " khác API #" + sameEndpointInGroup.index
                        + " (HTTP " + sameEndpointInGroup.capture.statusCode + ")");
            }
        } else if (es != null) {
            if (!es.paramSigToGroupPath.containsKey(sig)) {
                addNote(call, AuditNote.Severity.WARN, AuditNote.Type.PARAM_DIFF,
                    "Khác tham số so với lần đầu ở '" + es.firstGroupPath + "'" + describeDiff(cap.paramKeys, es.firstParams));
            }
            if (!es.statuses.contains(status)) {
                addNote(call, AuditNote.Severity.WARN, AuditNote.Type.STATUS_DIFF,
                    "HTTP " + status + " khác các lần trước " + es.statuses + " (lần đầu ở '" + es.firstGroupPath + "')");
            }
        }

        if (status >= 500) {
            addNote(call, AuditNote.Severity.ERROR, AuditNote.Type.HTTP_SERVER_ERROR, "Server trả về HTTP " + status);
        } else if (status == 401 || status == 403) {
            addNote(call, AuditNote.Severity.ERROR, AuditNote.Type.AUTH,
                "HTTP " + status + " - thiếu quyền hoặc mất phiên đăng nhập");
        } else if (status >= 400) {
            addNote(call, AuditNote.Severity.WARN, AuditNote.Type.HTTP_CLIENT_ERROR, "HTTP " + status);
        }

        if (excelLoaded && !call.inExcel) {
            addNote(call, AuditNote.Severity.INFO, AuditNote.Type.NOT_IN_EXCEL, "API không có trong file Excel");
        }
        if (excelUpgraded && call.inExcel) {
            addNote(call, AuditNote.Severity.INFO, AuditNote.Type.EXCEL_UPGRADE,
                "Bản chính của STT " + call.excelStt + " được thay bằng lời gọi này (đầy đủ tham số hơn)");
        }

        if (es == null) {
            es = new EndpointStats(cap.paramKeys, group.actionPath);
            endpoints.put(endpointKey, es);
        }
        es.groupIds.add(group.id);
        es.paramSigToGroupPath.putIfAbsent(sig, group.actionPath);
        es.statuses.add(status);

        version.incrementAndGet();
        return call;
    }

    private void addNote(ApiCall call, AuditNote.Severity sev, AuditNote.Type type, String msg) {
        AuditNote n = new AuditNote(sev, type, call.groupId, call.groupPath, call.index,
            call.capture.method, call.displayPath(), msg, System.currentTimeMillis());
        call.addNote(n);
        if (notes.size() < MAX_NOTES) {
            notes.add(n);
        }
        severityCounts[sev.ordinal()]++;
    }

    public synchronized int sharedGroupCount(String endpointKey) {
        EndpointStats es = endpoints.get(endpointKey);
        return es != null ? es.groupIds.size() : 0;
    }

    public synchronized List<String> sharedGroupPaths(String endpointKey) {
        EndpointStats es = endpoints.get(endpointKey);
        List<String> out = new ArrayList<>();
        if (es == null) {
            return out;
        }
        for (Integer id : es.groupIds) {
            ActionGroup g = byId.get(id);
            if (g != null) {
                out.add(g.actionPath);
            }
        }
        return out;
    }

    public synchronized List<ActionGroup> snapshotGroups() {
        return new ArrayList<>(byPath.values());
    }

    public synchronized List<AuditNote> snapshotNotes() {
        return new ArrayList<>(notes);
    }

    public synchronized int groupCount() {
        return byPath.size();
    }

    public synchronized int totalCalls() {
        return totalCalls;
    }

    public synchronized int endpointCount() {
        return endpoints.size();
    }

    public synchronized int noteCount(AuditNote.Severity sev) {
        return severityCounts[sev.ordinal()];
    }

    public synchronized void clear() {
        byPath.clear();
        byId.clear();
        endpoints.clear();
        notes.clear();
        totalCalls = 0;
        for (int i = 0; i < severityCounts.length; i++) {
            severityCounts[i] = 0;
        }
        seq.set(0);
        version.incrementAndGet();
    }

    static String paramSig(Set<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    private static String describeDiff(Set<String> now, Set<String> before) {
        List<String> added = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String k : now) {
            if (!before.contains(k)) {
                added.add(k);
            }
        }
        for (String k : before) {
            if (!now.contains(k)) {
                missing.add(k);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append(" | thêm [").append(String.join(", ", added)).append(']');
        }
        if (!missing.isEmpty()) {
            sb.append(" | thiếu [").append(String.join(", ", missing)).append(']');
        }
        return sb.toString();
    }
}
