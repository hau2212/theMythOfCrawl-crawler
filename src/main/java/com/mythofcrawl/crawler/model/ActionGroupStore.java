package com.mythofcrawl.crawler.model;

import burp.api.montoya.core.HighlightColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe store of action groups, the API calls they triggered, and audit NOTEs.
 *
 * Improvements over v1:
 * - {@link ReentrantReadWriteLock} allows concurrent EDT reads without blocking each other.
 * - O(1) per-group dedup lookup using cached {@link ApiCall#paramSig} and index map.
 * - Monotonic {@code sessionEpoch} and {@code callUid} across {@link #clear()} resets so
 *   ExtenderPushManager never collides with pre-reset ledger rows.
 * - Strict {@code MAX_NOTES} accounting: {@code severityCounts} only increments when a note
 *   is actually retained, with {@code overflowNoteCount} and {@code droppedCallCount} tracked.
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

    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicLong version = new AtomicLong(0);
    private final AtomicLong sessionEpoch = new AtomicLong(1);
    private final AtomicLong callUidSeq = new AtomicLong(0);

    private final LinkedHashMap<String, ActionGroup> byPath = new LinkedHashMap<>();
    private final Map<Integer, ActionGroup> byId = new HashMap<>();
    private final Map<Long, ApiCall> byCallUid = new HashMap<>();
    private final Map<String, EndpointStats> endpoints = new HashMap<>();
    private final List<AuditNote> notes = new ArrayList<>();
    private int totalCalls = 0;
    private final int[] severityCounts = new int[AuditNote.Severity.values().length];
    private int overflowNoteCount = 0;
    private int droppedCallCount = 0;

    public long version() {
        return version.get();
    }

    public long getSessionEpoch() {
        return sessionEpoch.get();
    }

    public void touchVersion() {
        version.incrementAndGet();
    }

    public ActionGroup openGroup(String actionPath, String kind, String label, String pageUrl) {
        String key = actionPath != null && !actionPath.trim().isEmpty() ? actionPath.trim() : "/ > (unknown)";
        rwl.writeLock().lock();
        try {
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
                    g.touch();
                    version.incrementAndGet();
                    return g;
                }
            }
            g = new ActionGroup(seq.incrementAndGet(), key, kind, label, pageUrl, System.currentTimeMillis());
            byPath.put(key, g);
            byId.put(g.id, g);
            version.incrementAndGet();
            return g;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    public ActionGroup getGroup(int id) {
        rwl.readLock().lock();
        try {
            return byId.get(id);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public ActionGroup findByPath(String actionPath) {
        if (actionPath == null) {
            return null;
        }
        String key = actionPath.trim();
        rwl.readLock().lock();
        try {
            return byPath.get(key);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public ApiCall findCallByUid(long callUid) {
        if (callUid <= 0) {
            return null;
        }
        rwl.readLock().lock();
        try {
            return byCallUid.get(callUid);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public void updateCallTriage(ApiCall call, HighlightColor color, ReviewStatus status, String comment) {
        if (call == null) {
            return;
        }
        if (color != null) {
            call.setHighlightColor(color);
        }
        if (status != null) {
            call.setReviewStatus(status);
        }
        if (comment != null) {
            call.setUserComment(comment);
        }
        version.incrementAndGet();
    }

    public ApiCall recordCall(
        ActionGroup group,
        CapturedApiEntry cap,
        String endpointKey,
        String graphqlOp,
        String excelStt,
        String excelPath,
        boolean excelLoaded,
        boolean excelUpgraded
    ) {
        return recordCall(group, cap, endpointKey, graphqlOp, excelStt, excelPath, excelLoaded, excelUpgraded, "");
    }

    /**
     * Records one API call into the group and computes audit notes.
     *
     * @return the stored (or de-duplicated existing) call, or null when the group is full.
     */
    public ApiCall recordCall(
        ActionGroup group,
        CapturedApiEntry cap,
        String endpointKey,
        String graphqlOp,
        String excelStt,
        String excelPath,
        boolean excelLoaded,
        boolean excelUpgraded,
        String mimeType
    ) {
        if (group == null || cap == null) {
            return null;
        }
        String sig = paramSig(cap.paramKeys);

        rwl.writeLock().lock();
        try {
            ApiCall exactDup = group.findDuplicateCall(endpointKey, sig, cap.statusCode);
            if (exactDup != null) {
                exactDup.incrementHits();
                version.incrementAndGet();
                return exactDup;
            }
            if (group.callCount() >= MAX_CALLS_PER_GROUP) {
                droppedCallCount++;
                return null;
            }

            ApiCall sameEndpointInGroup = group.firstCallForEndpoint(endpointKey);
            long uid = callUidSeq.incrementAndGet();
            long epoch = sessionEpoch.get();
            ApiCall call = new ApiCall(
                uid, epoch, group.id, group.actionPath, group.callCount() + 1, cap,
                endpointKey, graphqlOp, excelStt, excelPath, mimeType, version::incrementAndGet,
                group.getActionTrail());
            group.addCall(call);
            byCallUid.put(uid, call);
            totalCalls++;

            EndpointStats es = endpoints.get(endpointKey);
            int status = cap.statusCode;

            if (sameEndpointInGroup != null) {
                Set<String> prev = sameEndpointInGroup.capture.paramKeys;
                if (!sameEndpointInGroup.paramSig.equals(sig)) {
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
        } finally {
            rwl.writeLock().unlock();
        }
    }

    private void addNote(ApiCall call, AuditNote.Severity sev, AuditNote.Type type, String msg) {
        AuditNote n = new AuditNote(sev, type, call.groupId, call.groupPath, call.index,
            call.capture.method, call.displayPath(), msg, System.currentTimeMillis());
        call.addNote(n);
        if (notes.size() < MAX_NOTES) {
            notes.add(n);
            severityCounts[sev.ordinal()]++;
        } else {
            overflowNoteCount++;
        }
    }

    /**
     * Thread-safely attaches an additional semantic audit NOTE (such as GraphQL 200 errors or
     * disguised JSON 401/500 envelopes) to a recorded {@link ApiCall}, deduplicating by {@link AuditNote.Type}.
     */
    public void attachCallNoteIfAbsent(ApiCall call, AuditNote.Severity sev, AuditNote.Type type, String msg) {
        if (call == null || sev == null || type == null || msg == null || msg.trim().isEmpty()) {
            return;
        }
        rwl.writeLock().lock();
        try {
            for (AuditNote existing : call.getNotes()) {
                if (existing.type == type) {
                    return;
                }
            }
            addNote(call, sev, type, msg.trim());
            version.incrementAndGet();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Records a standalone group-level audit NOTE (e.g., external redirect link or deferred redirect trap
     * that did not yield a normal backend API call, so it still appears in Bảng NOTE and on the Group).
     */
    public AuditNote recordStandaloneNote(
        ActionGroup group,
        AuditNote.Severity sev,
        AuditNote.Type type,
        String method,
        String endpoint,
        String msg
    ) {
        if (group == null || type == null || msg == null || msg.trim().isEmpty()) {
            return null;
        }
        String safeMethod = (method != null && !method.trim().isEmpty()) ? method.trim() : "NAV";
        String safeEndpoint = (endpoint != null && !endpoint.trim().isEmpty()) ? endpoint.trim() : group.pageUrl;
        AuditNote.Severity safeSev = sev != null ? sev : AuditNote.Severity.INFO;

        rwl.writeLock().lock();
        try {
            for (AuditNote existing : group.getGroupNotes()) {
                if (existing.type == type && existing.endpoint.equals(safeEndpoint)) {
                    return existing;
                }
            }
            AuditNote n = new AuditNote(safeSev, type, group.id, group.actionPath, 0,
                safeMethod, safeEndpoint, msg.trim(), System.currentTimeMillis());
            group.addGroupNote(n);
            if (notes.size() < MAX_NOTES) {
                notes.add(n);
                severityCounts[safeSev.ordinal()]++;
            } else {
                overflowNoteCount++;
            }
            version.incrementAndGet();
            return n;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    public int sharedGroupCount(String endpointKey) {
        rwl.readLock().lock();
        try {
            EndpointStats es = endpoints.get(endpointKey);
            return es != null ? es.groupIds.size() : 0;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public List<String> sharedGroupPaths(String endpointKey) {
        rwl.readLock().lock();
        try {
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
        } finally {
            rwl.readLock().unlock();
        }
    }

    public List<ActionGroup> snapshotGroups() {
        rwl.readLock().lock();
        try {
            return new ArrayList<>(byPath.values());
        } finally {
            rwl.readLock().unlock();
        }
    }

    public List<AuditNote> snapshotNotes() {
        rwl.readLock().lock();
        try {
            return new ArrayList<>(notes);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int groupCount() {
        rwl.readLock().lock();
        try {
            return byPath.size();
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int totalCalls() {
        rwl.readLock().lock();
        try {
            return totalCalls;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int endpointCount() {
        rwl.readLock().lock();
        try {
            return endpoints.size();
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int noteCount(AuditNote.Severity sev) {
        rwl.readLock().lock();
        try {
            return severityCounts[sev.ordinal()];
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int getOverflowNoteCount() {
        rwl.readLock().lock();
        try {
            return overflowNoteCount;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int getDroppedCallCount() {
        rwl.readLock().lock();
        try {
            return droppedCallCount;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public void clear() {
        rwl.writeLock().lock();
        try {
            byPath.clear();
            byId.clear();
            byCallUid.clear();
            endpoints.clear();
            notes.clear();
            totalCalls = 0;
            overflowNoteCount = 0;
            droppedCallCount = 0;
            for (int i = 0; i < severityCounts.length; i++) {
                severityCounts[i] = 0;
            }
            seq.set(0);
            sessionEpoch.incrementAndGet();
            version.incrementAndGet();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    static String paramSig(Set<String> keys) {
        return ApiCall.computeParamSig(keys);
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
