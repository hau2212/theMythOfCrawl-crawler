package com.mythofcrawl.crawler.core.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks cross-branch duplicate lineage for endpoints without ever overwriting
 * their original discovery branch ({@code originBranch}), alongside move history and {@code REFRESH} tags.
 */
public final class DuplicateLineageTracker {

    public static final class MoveEvent {
        public final String fromBranch;
        public final String toBranch;
        public final String reason;
        public final long movedAtMs;

        public MoveEvent(String fromBranch, String toBranch, String reason, long movedAtMs) {
            this.fromBranch = fromBranch != null ? fromBranch : "";
            this.toBranch = toBranch != null ? toBranch : "";
            this.reason = reason != null ? reason : "";
            this.movedAtMs = movedAtMs;
        }
    }

    public static final class EndpointLineage {
        public final String endpointKey;
        public final String originBranch;
        public final Set<String> seenBranches;
        public final List<MoveEvent> moveHistory;
        public final int hitCount;
        public final int refreshCount;
        public final long lastSeenAtMs;

        public EndpointLineage(
            String endpointKey,
            String originBranch,
            Set<String> seenBranches,
            List<MoveEvent> moveHistory,
            int hitCount,
            int refreshCount,
            long lastSeenAtMs
        ) {
            this.endpointKey = endpointKey;
            this.originBranch = originBranch;
            this.seenBranches = Collections.unmodifiableSet(new LinkedHashSet<>(seenBranches));
            this.moveHistory = Collections.unmodifiableList(new ArrayList<>(moveHistory));
            this.hitCount = hitCount;
            this.refreshCount = refreshCount;
            this.lastSeenAtMs = lastSeenAtMs;
        }

        public boolean isCrossBranchDuplicate() {
            return seenBranches.size() > 1;
        }
    }

    private static final class MutableLineage {
        final String endpointKey;
        final String originBranch;
        final Set<String> seenBranches = new LinkedHashSet<>();
        final List<MoveEvent> moveHistory = new ArrayList<>();
        int hitCount = 0;
        int refreshCount = 0;
        long lastSeenAtMs = 0L;

        MutableLineage(String endpointKey, String originBranch) {
            this.endpointKey = endpointKey;
            this.originBranch = originBranch;
            if (!originBranch.isEmpty()) {
                this.seenBranches.add(originBranch);
            }
        }

        EndpointLineage snapshot() {
            return new EndpointLineage(
                endpointKey, originBranch, seenBranches, moveHistory, hitCount, refreshCount, lastSeenAtMs);
        }
    }

    private final Object lock = new Object();
    private final Map<String, MutableLineage> byEndpoint = new LinkedHashMap<>();

    public EndpointLineage recordSeen(String endpointKey, String branchPath, boolean isRefresh) {
        if (endpointKey == null || endpointKey.trim().isEmpty()) {
            return null;
        }
        String key = endpointKey.trim();
        String branch = branchPath != null ? branchPath.trim() : "";
        synchronized (lock) {
            MutableLineage ml = byEndpoint.computeIfAbsent(key, k -> new MutableLineage(k, branch));
            if (!branch.isEmpty()) {
                ml.seenBranches.add(branch);
            }
            ml.hitCount++;
            if (isRefresh) {
                ml.refreshCount++;
            }
            ml.lastSeenAtMs = System.currentTimeMillis();
            return ml.snapshot();
        }
    }

    public EndpointLineage recordMoved(String endpointKey, String fromBranch, String toBranch, String reason) {
        if (endpointKey == null || endpointKey.trim().isEmpty()) {
            return null;
        }
        String key = endpointKey.trim();
        String from = fromBranch != null ? fromBranch.trim() : "";
        String to = toBranch != null ? toBranch.trim() : "";
        synchronized (lock) {
            MutableLineage ml = byEndpoint.computeIfAbsent(key, k -> new MutableLineage(k, from));
            ml.moveHistory.add(new MoveEvent(from, to, reason, System.currentTimeMillis()));
            if (!to.isEmpty()) {
                ml.seenBranches.add(to);
            }
            return ml.snapshot();
        }
    }

    public EndpointLineage getLineage(String endpointKey) {
        if (endpointKey == null || endpointKey.trim().isEmpty()) {
            return null;
        }
        synchronized (lock) {
            MutableLineage ml = byEndpoint.get(endpointKey.trim());
            return ml != null ? ml.snapshot() : null;
        }
    }

    public Map<String, EndpointLineage> snapshotAll() {
        synchronized (lock) {
            Map<String, EndpointLineage> out = new LinkedHashMap<>();
            for (Map.Entry<String, MutableLineage> e : byEndpoint.entrySet()) {
                out.put(e.getKey(), e.getValue().snapshot());
            }
            return out;
        }
    }

    public void clear() {
        synchronized (lock) {
            byEndpoint.clear();
        }
    }
}
