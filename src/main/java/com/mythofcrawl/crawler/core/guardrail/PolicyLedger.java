package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.model.MiniJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe CRUD Policy Ledger supporting 5 scopes, soft-delete Trash + Undo restore,
 * reordering (Up/Down/Top), 4-level NONE release overrides, 5 Playbook preset installers,
 * JSON persistence, and 0ms listener notification.
 */
public final class PolicyLedger {

    public static final class TrashEntry {
        public final PolicyRule rule;
        public final int originalIndex;
        public final long deletedAtMs;

        public TrashEntry(PolicyRule rule, int originalIndex) {
            this.rule = rule;
            this.originalIndex = originalIndex;
            this.deletedAtMs = System.currentTimeMillis();
        }
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<PolicyRule> rules = new ArrayList<>();
    private final Deque<TrashEntry> trash = new ArrayDeque<>();
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

    public PolicyRule addRule(PolicyRule rule) {
        if (rule == null) {
            return null;
        }
        lock.writeLock().lock();
        try {
            removeByIdLocked(rule.id);
            rule.updatedAtMs = System.currentTimeMillis();
            rules.add(rule);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
        return rule;
    }

    public PolicyRule upsertRule(PolicyRule rule) {
        if (rule == null) {
            return null;
        }
        lock.writeLock().lock();
        try {
            rule.updatedAtMs = System.currentTimeMillis();
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).id.equals(rule.id)) {
                    rules.set(i, rule);
                    notifyListeners();
                    return rule;
                }
            }
            rules.add(rule);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
        return rule;
    }

    public PolicyRule getRule(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        lock.readLock().lock();
        try {
            for (PolicyRule r : rules) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PolicyRule> snapshotRules() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(rules);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TrashEntry> snapshotTrash() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(trash);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean setEnabled(String id, boolean enabled) {
        boolean changed = false;
        lock.writeLock().lock();
        try {
            for (PolicyRule r : rules) {
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

    public boolean deleteRule(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        boolean removed = false;
        lock.writeLock().lock();
        try {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).id.equals(id)) {
                    PolicyRule target = rules.remove(i);
                    trash.push(new TrashEntry(target, i));
                    while (trash.size() > 50) {
                        trash.removeLast();
                    }
                    removed = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) {
            notifyListeners();
        }
        return removed;
    }

    public PolicyRule undoLastDelete() {
        PolicyRule restored = null;
        lock.writeLock().lock();
        try {
            if (!trash.isEmpty()) {
                TrashEntry entry = trash.pop();
                int idx = Math.max(0, Math.min(rules.size(), entry.originalIndex));
                rules.add(idx, entry.rule);
                restored = entry.rule;
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (restored != null) {
            notifyListeners();
        }
        return restored;
    }

    public PolicyRule restoreFromTrash(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        PolicyRule restored = null;
        lock.writeLock().lock();
        try {
            TrashEntry found = null;
            for (TrashEntry te : trash) {
                if (te.rule.id.equals(id)) {
                    found = te;
                    break;
                }
            }
            if (found != null) {
                trash.remove(found);
                int idx = Math.max(0, Math.min(rules.size(), found.originalIndex));
                rules.add(idx, found.rule);
                restored = found.rule;
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (restored != null) {
            notifyListeners();
        }
        return restored;
    }

    public void emptyTrash() {
        lock.writeLock().lock();
        try {
            trash.clear();
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    public PolicyRule duplicateRule(String id) {
        PolicyRule dup = null;
        lock.writeLock().lock();
        try {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).id.equals(id)) {
                    PolicyRule src = rules.get(i);
                    dup = src.copy();
                    dup.id = java.util.UUID.randomUUID().toString().substring(0, 8);
                    dup.name = src.name + " (Copy)";
                    dup.setHits(0);
                    rules.add(i + 1, dup);
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (dup != null) {
            notifyListeners();
        }
        return dup;
    }

    public boolean moveUp(String id) {
        boolean moved = false;
        lock.writeLock().lock();
        try {
            for (int i = 1; i < rules.size(); i++) {
                if (rules.get(i).id.equals(id)) {
                    PolicyRule tmp = rules.get(i - 1);
                    rules.set(i - 1, rules.get(i));
                    rules.set(i, tmp);
                    moved = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (moved) {
            notifyListeners();
        }
        return moved;
    }

    public boolean moveDown(String id) {
        boolean moved = false;
        lock.writeLock().lock();
        try {
            for (int i = 0; i < rules.size() - 1; i++) {
                if (rules.get(i).id.equals(id)) {
                    PolicyRule tmp = rules.get(i + 1);
                    rules.set(i + 1, rules.get(i));
                    rules.set(i, tmp);
                    moved = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (moved) {
            notifyListeners();
        }
        return moved;
    }

    public boolean moveToTop(String id) {
        boolean moved = false;
        lock.writeLock().lock();
        try {
            for (int i = 1; i < rules.size(); i++) {
                if (rules.get(i).id.equals(id)) {
                    PolicyRule r = rules.remove(i);
                    r.priority = Math.max(r.priority, topPriorityLocked() + 10);
                    rules.add(0, r);
                    moved = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (moved) {
            notifyListeners();
        }
        return moved;
    }

    /**
     * Level 1 Release: Releases ALL guardrails to NONE by disabling active holding/blocking rules.
     */
    public void releaseAllToNone() {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            for (PolicyRule r : rules) {
                if (!r.allow) {
                    r.mode = GuardrailMode.NONE;
                    r.enabled = false;
                    r.updatedAtMs = now;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    /**
     * Level 2 Release: Unlocks a specific UI branch even if broader GLOBAL or METHOD policies exist.
     */
    public PolicyRule releaseBranchToNone(String branchPrefix) {
        String norm = PolicyRule.normalizeBranch(branchPrefix);
        if (norm.isEmpty()) {
            return null;
        }
        PolicyRule allowRule;
        lock.writeLock().lock();
        try {
            for (PolicyRule r : rules) {
                if (!r.allow && r.scope == PolicyScope.BRANCH
                    && norm.equalsIgnoreCase(PolicyRule.normalizeBranch(r.branchPrefix))) {
                    r.enabled = false;
                    r.mode = GuardrailMode.NONE;
                }
            }
            String allowId = "allow-branch-" + Integer.toHexString(norm.toLowerCase().hashCode());
            removeByIdLocked(allowId);
            allowRule = new PolicyRule(allowId, "ALLOW Nhánh: " + norm, 900, true, true,
                PolicyScope.BRANCH, GuardrailMode.NONE)
                .withBranchPrefix(norm)
                .withNotes("Mở khóa toàn bộ nhánh về NONE (ưu tiên #1 ALLOW).");
            rules.add(0, allowRule);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
        return allowRule;
    }

    /**
     * Level 3 Release: Unlocks a specific endpoint (e.g. "DELETE /api/orders/1" or "/api/orders/1") to NONE.
     */
    public PolicyRule releaseEndpointToNone(String exactEndpoint) {
        if (exactEndpoint == null || exactEndpoint.trim().isEmpty()) {
            return null;
        }
        String norm = exactEndpoint.trim();
        PolicyRule allowRule;
        lock.writeLock().lock();
        try {
            for (PolicyRule r : rules) {
                if (!r.allow && r.scope == PolicyScope.EXACT_API
                    && norm.equalsIgnoreCase(r.exactEndpoint != null ? r.exactEndpoint.trim() : "")) {
                    r.enabled = false;
                    r.mode = GuardrailMode.NONE;
                }
            }
            String allowId = "allow-api-" + Integer.toHexString(norm.toLowerCase().hashCode());
            removeByIdLocked(allowId);
            allowRule = new PolicyRule(allowId, "ALLOW API: " + norm, 950, true, true,
                PolicyScope.EXACT_API, GuardrailMode.NONE)
                .withExactEndpoint(norm)
                .withNotes("Mở khóa API cụ thể về NONE (ưu tiên #1 ALLOW).");
            rules.add(0, allowRule);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
        return allowRule;
    }

    /**
     * Playbook Preset #1: Safe Read-Only Crawl (holds POST/PUT/PATCH/DELETE virtually at proxy with 200 OK).
     */
    public void installSafeReadOnlyPlaybook() {
        upsertRule(new PolicyRule(
            "pb-safe-readonly",
            "Playbook #1: Safe Read-Only (Hold 200 ghi/xóa)",
            150, true, false, PolicyScope.METHOD_OR_ACTION, GuardrailMode.HOLD_AT_PROXY)
            .withMethods("POST", "PUT", "PATCH", "DELETE")
            .withNotes("Chặn ảo các request thay đổi dữ liệu và trả 200 OK giả lập để SPA không vỡ."));
    }

    /**
     * Playbook Preset #2: Production Guard (Intercept Wait on DELETE/submit/delete actions).
     */
    public void installProductionGuardPlaybook() {
        upsertRule(new PolicyRule(
            "pb-prod-guard",
            "Playbook #2: Production Guard (Chờ duyệt xóa/submit)",
            220, true, false, PolicyScope.METHOD_OR_ACTION, GuardrailMode.INTERCEPT_WAIT)
            .withMethods("DELETE", "PUT", "PATCH")
            .withUiActions("delete", "submit")
            .withNotes("Giữ tại Proxy Intercept Queue để duyệt thủ công trước khi gửi thật."));
    }

    /**
     * Playbook Preset #3: Create-Then-Delete Safe Loop (Stage-2 Producer Recreate).
     */
    public void installCreateDeleteLoopPlaybook() {
        upsertRule(new PolicyRule(
            "pb-producer-recreate",
            "Playbook #3: Create -> Mutate/Delete Safe Loop",
            200, true, false, PolicyScope.METHOD_OR_ACTION, GuardrailMode.PRODUCER_RECREATE)
            .withMethods("PUT", "PATCH", "DELETE")
            .withNotes("Tự gọi POST tạo dữ liệu nháp trước khi sửa/xóa để không đụng dữ liệu thật."));
    }

    /**
     * Playbook Preset #4: Live ID Pool Substitution (Stage-2).
     */
    public void installLiveIdTestPlaybook() {
        upsertRule(new PolicyRule(
            "pb-live-id-pool",
            "Playbook #4: Auto Live ID Substitution",
            180, true, false, PolicyScope.GLOBAL, GuardrailMode.LIVE_ID_POOL)
            .withMethods("GET", "PUT", "PATCH", "DELETE")
            .withNotes("Thu hoạch ID sống từ response và tự thay vào path/body khi test."));
    }

    /**
     * Playbook Preset #5: Strict 2xx Lock on destructive mutations.
     */
    public void installStrict2xxLockPlaybook() {
        upsertRule(new PolicyRule(
            "pb-lock-2xx",
            "Playbook #5: Lock Non-2xx Retry on Mutations",
            170, true, false, PolicyScope.METHOD_OR_ACTION, GuardrailMode.LOCK_2XX)
            .withMethods("POST", "PUT", "DELETE")
            .withNotes("Khóa gửi lại mutation nếu phiên trước trả lỗi non-2xx."));
    }

    public String toJson() {
        lock.readLock().lock();
        try {
            List<Map<String, Object>> ruleMaps = new ArrayList<>();
            for (PolicyRule r : rules) {
                ruleMaps.add(r.toMap());
            }
            List<Map<String, Object>> trashMaps = new ArrayList<>();
            for (TrashEntry te : trash) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("originalIndex", Integer.valueOf(te.originalIndex));
                tm.put("rule", te.rule.toMap());
                trashMaps.add(tm);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", Integer.valueOf(1));
            root.put("rules", ruleMaps);
            root.put("trash", trashMaps);
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
        List<PolicyRule> loadedRules = new ArrayList<>();
        Object rawRules = root.get("rules");
        if (rawRules instanceof List<?>) {
            for (Object item : (List<?>) rawRules) {
                if (item instanceof Map<?, ?>) {
                    loadedRules.add(PolicyRule.fromMap((Map<?, ?>) item));
                }
            }
        }
        List<TrashEntry> loadedTrash = new ArrayList<>();
        Object rawTrash = root.get("trash");
        if (rawTrash instanceof List<?>) {
            for (Object item : (List<?>) rawTrash) {
                if (item instanceof Map<?, ?>) {
                    Map<?, ?> tm = (Map<?, ?>) item;
                    Object rObj = tm.get("rule");
                    if (rObj instanceof Map<?, ?>) {
                        Object idxObj = tm.get("originalIndex");
                        int idx = idxObj instanceof Number ? ((Number) idxObj).intValue() : 0;
                        loadedTrash.add(new TrashEntry(
                            PolicyRule.fromMap((Map<?, ?>) rObj),
                            idx));
                    }
                }
            }
        }
        lock.writeLock().lock();
        try {
            rules.clear();
            rules.addAll(loadedRules);
            trash.clear();
            for (int i = loadedTrash.size() - 1; i >= 0; i--) {
                trash.push(loadedTrash.get(i));
            }
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    public void clearAll() {
        lock.writeLock().lock();
        try {
            rules.clear();
            trash.clear();
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    private void removeByIdLocked(String id) {
        if (id == null) {
            return;
        }
        rules.removeIf(r -> id.equals(r.id));
    }

    private int topPriorityLocked() {
        int max = 100;
        for (PolicyRule r : rules) {
            if (r.priority > max) {
                max = r.priority;
            }
        }
        return max;
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
