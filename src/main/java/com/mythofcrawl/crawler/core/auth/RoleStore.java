package com.mythofcrawl.crawler.core.auth;

import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.model.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe Role Profile CRUD store with JSON persistence and 0ms listener notification.
 */
public final class RoleStore {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<RoleProfile> roles = new ArrayList<>();
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

    public RoleProfile addRole(RoleProfile role) {
        return upsertRole(role);
    }

    public RoleProfile upsertRole(RoleProfile role) {
        if (role == null) {
            return null;
        }
        lock.writeLock().lock();
        try {
            role.updatedAtMs = System.currentTimeMillis();
            for (int i = 0; i < roles.size(); i++) {
                if (roles.get(i).id.equals(role.id)) {
                    roles.set(i, role);
                    notifyListeners();
                    return role;
                }
            }
            roles.add(role);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
        return role;
    }

    public boolean deleteRole(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        boolean removed;
        lock.writeLock().lock();
        try {
            removed = roles.removeIf(r -> r.id.equals(id));
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) {
            notifyListeners();
        }
        return removed;
    }

    public boolean setEnabled(String id, boolean enabled) {
        boolean changed = false;
        lock.writeLock().lock();
        try {
            for (RoleProfile r : roles) {
                if (r.id.equals(id)) {
                    r.enabled = enabled;
                    r.updatedAtMs = System.currentTimeMillis();
                    changed = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (changed) {
            notifyListeners();
        }
        return changed;
    }

    public RoleProfile getRole(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        lock.readLock().lock();
        try {
            for (RoleProfile r : roles) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RoleProfile> snapshotRoles() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(roles);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RoleProfile> matchRolesFor(String branch, String endpoint) {
        lock.readLock().lock();
        try {
            List<RoleProfile> out = new ArrayList<>();
            for (RoleProfile r : roles) {
                if (r.appliesTo(branch, endpoint)) {
                    out.add(r);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String toJson() {
        lock.readLock().lock();
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (RoleProfile r : roles) {
                list.add(r.toMap());
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", Integer.valueOf(1));
            root.put("roles", list);
            return MiniJson.stringify(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void loadFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        Map<String, Object> root = MiniJson.parseObject(json);
        List<RoleProfile> loaded = new ArrayList<>();
        Object raw = root.get("roles");
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map<?, ?>) {
                    loaded.add(RoleProfile.fromMap((Map<?, ?>) item));
                }
            }
        }
        lock.writeLock().lock();
        try {
            roles.clear();
            roles.addAll(loaded);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            roles.clear();
        } finally {
            lock.writeLock().unlock();
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
