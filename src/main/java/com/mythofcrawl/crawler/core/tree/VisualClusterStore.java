package com.mythofcrawl.crawler.core.tree;

import com.mythofcrawl.crawler.core.sync.InstantEventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-Java store for visual clusters inside a UI branch without mutating canonical Action Paths.
 */
public final class VisualClusterStore {

    public static final class VisualCluster {
        public final String id;
        public final String branchPath;
        public final String name;
        public final Set<String> endpointKeys;
        public final long createdAtMs;

        public VisualCluster(String id, String branchPath, String name, Set<String> endpointKeys, long createdAtMs) {
            this.id = id;
            this.branchPath = branchPath;
            this.name = name;
            this.endpointKeys = Collections.unmodifiableSet(new LinkedHashSet<>(endpointKeys));
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class MutableCluster {
        final String id;
        final String branchPath;
        String name;
        final Set<String> endpointKeys = new LinkedHashSet<>();
        final long createdAtMs = System.currentTimeMillis();

        MutableCluster(String id, String branchPath, String name) {
            this.id = id;
            this.branchPath = branchPath;
            this.name = name;
        }

        VisualCluster snapshot() {
            return new VisualCluster(id, branchPath, name, endpointKeys, createdAtMs);
        }
    }

    private final Object lock = new Object();
    private final Map<String, MutableCluster> clusters = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public InstantEventBus.Subscription subscribe(Runnable listener) {
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

    public VisualCluster createCluster(String branchPath, String name) {
        String id = "cl-" + UUID.randomUUID().toString().substring(0, 8);
        String branch = branchPath != null ? branchPath.trim() : "/";
        String clusterName = (name != null && !name.trim().isEmpty()) ? name.trim() : "New Cluster";
        VisualCluster snap;
        synchronized (lock) {
            MutableCluster mc = new MutableCluster(id, branch, clusterName);
            clusters.put(id, mc);
            snap = mc.snapshot();
        }
        notifyListeners();
        return snap;
    }

    public boolean assignEndpoint(String clusterId, String endpointKey) {
        if (clusterId == null || endpointKey == null || endpointKey.trim().isEmpty()) {
            return false;
        }
        boolean changed;
        synchronized (lock) {
            MutableCluster mc = clusters.get(clusterId);
            if (mc == null) {
                return false;
            }
            changed = mc.endpointKeys.add(endpointKey.trim());
        }
        if (changed) {
            notifyListeners();
        }
        return changed;
    }

    public boolean removeEndpoint(String clusterId, String endpointKey) {
        if (clusterId == null || endpointKey == null) {
            return false;
        }
        boolean changed;
        synchronized (lock) {
            MutableCluster mc = clusters.get(clusterId);
            if (mc == null) {
                return false;
            }
            changed = mc.endpointKeys.remove(endpointKey.trim());
        }
        if (changed) {
            notifyListeners();
        }
        return changed;
    }

    public boolean renameCluster(String clusterId, String newName) {
        if (clusterId == null || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        boolean changed = false;
        synchronized (lock) {
            MutableCluster mc = clusters.get(clusterId);
            if (mc != null) {
                mc.name = newName.trim();
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
        return changed;
    }

    public boolean deleteCluster(String clusterId) {
        boolean removed;
        synchronized (lock) {
            removed = clusters.remove(clusterId) != null;
        }
        if (removed) {
            notifyListeners();
        }
        return removed;
    }

    public List<VisualCluster> clustersForBranch(String branchPath) {
        String b = branchPath != null ? branchPath.trim() : "";
        synchronized (lock) {
            List<VisualCluster> out = new ArrayList<>();
            for (MutableCluster mc : clusters.values()) {
                if (b.isEmpty() || mc.branchPath.equalsIgnoreCase(b)) {
                    out.add(mc.snapshot());
                }
            }
            return out;
        }
    }

    public List<VisualCluster> snapshotAll() {
        synchronized (lock) {
            List<VisualCluster> out = new ArrayList<>(clusters.size());
            for (MutableCluster mc : clusters.values()) {
                out.add(mc.snapshot());
            }
            return out;
        }
    }

    public void clear() {
        synchronized (lock) {
            clusters.clear();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }
}
