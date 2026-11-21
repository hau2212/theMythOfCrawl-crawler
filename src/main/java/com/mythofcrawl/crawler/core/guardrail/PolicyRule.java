package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.model.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Pure-Java Action Guardrail policy rule supporting 5 scopes, Stage-1/Stage-2 execution modes,
 * explicit #1 ALLOW precedence, CRUD timestamps, hit telemetry, and JSON serialization.
 */
public final class PolicyRule {

    public String id;
    public String name;
    public int priority;
    public boolean enabled;
    public boolean allow;
    public PolicyScope scope;
    public final Set<String> methods = new LinkedHashSet<>();
    public final Set<String> uiActions = new LinkedHashSet<>();
    public String branchPrefix;
    public String pathPattern;
    public String exactEndpoint;
    public GuardrailMode mode;
    public String notes;
    public final long createdAtMs;
    public volatile long updatedAtMs;
    public volatile long lastMatchedAtMs;
    private final AtomicLong hits = new AtomicLong(0);

    private transient String cachedPatternRaw;
    private transient Pattern cachedRegex;

    public PolicyRule() {
        this(UUID.randomUUID().toString().substring(0, 8), "New Policy", 100, true, false,
            PolicyScope.GLOBAL, GuardrailMode.HOLD_AT_PROXY);
    }

    public PolicyRule(
        String id,
        String name,
        int priority,
        boolean enabled,
        boolean allow,
        PolicyScope scope,
        GuardrailMode mode
    ) {
        long now = System.currentTimeMillis();
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : UUID.randomUUID().toString().substring(0, 8);
        this.name = name != null ? name.trim() : "Unnamed Policy";
        this.priority = priority;
        this.enabled = enabled;
        this.allow = allow;
        this.scope = scope != null ? scope : PolicyScope.GLOBAL;
        this.mode = allow ? GuardrailMode.NONE : (mode != null ? mode : GuardrailMode.HOLD_AT_PROXY);
        this.branchPrefix = "";
        this.pathPattern = "";
        this.exactEndpoint = "";
        this.notes = "";
        this.createdAtMs = now;
        this.updatedAtMs = now;
    }

    public PolicyRule withMethods(String... m) {
        if (m != null) {
            for (String s : m) {
                if (s != null && !s.trim().isEmpty()) {
                    methods.add(s.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return this;
    }

    public PolicyRule withUiActions(String... actions) {
        if (actions != null) {
            for (String a : actions) {
                if (a != null && !a.trim().isEmpty()) {
                    uiActions.add(a.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return this;
    }

    public PolicyRule withBranchPrefix(String prefix) {
        this.branchPrefix = prefix != null ? prefix.trim() : "";
        return this;
    }

    public PolicyRule withPathPattern(String pattern) {
        this.pathPattern = pattern != null ? pattern.trim() : "";
        this.cachedPatternRaw = null;
        this.cachedRegex = null;
        return this;
    }

    public PolicyRule withExactEndpoint(String endpoint) {
        this.exactEndpoint = endpoint != null ? endpoint.trim() : "";
        return this;
    }

    public PolicyRule withNotes(String notes) {
        this.notes = notes != null ? notes : "";
        return this;
    }

    public long getHits() {
        return hits.get();
    }

    public void setHits(long count) {
        hits.set(Math.max(0L, count));
    }

    public void recordHit() {
        hits.incrementAndGet();
        lastMatchedAtMs = System.currentTimeMillis();
    }

    public boolean matches(String branch, String host, String method, String path, String uiAction) {
        if (!enabled) {
            return false;
        }
        String normMethod = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
        String normUiAction = uiAction != null ? uiAction.trim().toLowerCase(Locale.ROOT) : "";
        String normBranch = normalizeBranch(branch);
        String normPath = normalizePathOnly(path);

        if (!methods.isEmpty() && !methods.contains("*") && !methods.contains(normMethod)) {
            return false;
        }
        if (!uiActions.isEmpty() && !uiActions.contains("*")
            && (normUiAction.isEmpty() || !uiActions.contains(normUiAction))) {
            return false;
        }

        if (branchPrefix != null && !branchPrefix.isEmpty()) {
            String pref = normalizeBranch(branchPrefix);
            if (!pref.isEmpty() && !normBranch.equalsIgnoreCase(pref)
                && !normBranch.toLowerCase(Locale.ROOT).startsWith((pref + " >").toLowerCase(Locale.ROOT))
                && !normBranch.toLowerCase(Locale.ROOT).startsWith((pref + "/").toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        if (pathPattern != null && !pathPattern.isEmpty() && !matchesPathPattern(pathPattern, path, normPath)) {
            return false;
        }

        if (exactEndpoint != null && !exactEndpoint.isEmpty() && !matchesExact(exactEndpoint, normMethod, path, normPath)) {
            return false;
        }

        // Guard against misconfigured empty scope-specific rules matching everything unintentionally
        if (scope == PolicyScope.BRANCH && (branchPrefix == null || branchPrefix.trim().isEmpty())) {
            return false;
        }
        if (scope == PolicyScope.PATH_PATTERN && (pathPattern == null || pathPattern.trim().isEmpty())) {
            return false;
        }
        if (scope == PolicyScope.EXACT_API && (exactEndpoint == null || exactEndpoint.trim().isEmpty())) {
            return false;
        }
        if (scope == PolicyScope.METHOD_OR_ACTION && methods.isEmpty() && uiActions.isEmpty()) {
            return false;
        }
        return true;
    }

    private boolean matchesPathPattern(String patRaw, String rawPath, String pathNoQuery) {
        String p = patRaw.trim();
        String target = rawPath != null ? rawPath.trim() : "";
        String cleanTarget = pathNoQuery;
        if (p.startsWith("re:")) {
            try {
                Pattern rx = Pattern.compile(p.substring(3));
                return rx.matcher(target).find() || rx.matcher(cleanTarget).find();
            } catch (Exception ignored) {
                return false;
            }
        }
        if (p.contains("*") || p.contains("{")) {
            Pattern rx = compileGlob(p);
            return rx.matcher(target).matches() || rx.matcher(cleanTarget).matches();
        }
        String normPat = normalizePathOnly(p).toLowerCase(Locale.ROOT);
        String lcTarget = cleanTarget.toLowerCase(Locale.ROOT);
        return lcTarget.equals(normPat)
            || lcTarget.startsWith(normPat.endsWith("/") ? normPat : (normPat + "/"))
            || lcTarget.contains(normPat);
    }

    private Pattern compileGlob(String pat) {
        if (cachedRegex != null && pat.equals(cachedPatternRaw)) {
            return cachedRegex;
        }
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pat.length(); i++) {
            char c = pat.charAt(i);
            if (c == '*') {
                if (i + 1 < pat.length() && pat.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '{') {
                int close = pat.indexOf('}', i + 1);
                if (close > i) {
                    sb.append("[^/]+");
                    i = close;
                } else {
                    sb.append("\\{");
                }
            } else if ("\\.^$|?+()[]".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("(?:\\?.*)?$");
        cachedPatternRaw = pat;
        cachedRegex = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        return cachedRegex;
    }

    private static boolean matchesExact(String exact, String normMethod, String rawPath, String pathNoQuery) {
        String e = exact.trim();
        String expectedMethod = "";
        String expectedPath = e;
        int sp = e.indexOf(' ');
        if (sp > 0) {
            expectedMethod = e.substring(0, sp).trim().toUpperCase(Locale.ROOT);
            expectedPath = e.substring(sp + 1).trim();
        }
        if (!expectedMethod.isEmpty() && !expectedMethod.equals(normMethod)) {
            return false;
        }
        String normExpected = normalizePathOnly(expectedPath);
        if (expectedPath.contains("?") && rawPath != null) {
            return stripQueryNormalized(rawPath).equalsIgnoreCase(stripQueryNormalized(expectedPath));
        }
        return pathNoQuery.equalsIgnoreCase(normExpected);
    }

    private static String stripQueryNormalized(String p) {
        String t = p.trim();
        int q = t.indexOf('?');
        String base = q >= 0 ? normalizePathOnly(t.substring(0, q)) : normalizePathOnly(t);
        String query = q >= 0 ? t.substring(q) : "";
        return base + query;
    }

    public static String normalizePathOnly(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String p = rawPath.trim();
        int scheme = p.indexOf("://");
        if (scheme > 0) {
            int slash = p.indexOf('/', scheme + 3);
            p = slash >= 0 ? p.substring(slash) : "/";
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static String normalizeBranch(String rawBranch) {
        if (rawBranch == null) {
            return "";
        }
        return rawBranch.trim().replaceAll("\\s+", " ");
    }

    public PolicyRule copy() {
        PolicyRule c = new PolicyRule(id, name, priority, enabled, allow, scope, mode);
        c.methods.addAll(methods);
        c.uiActions.addAll(uiActions);
        c.branchPrefix = branchPrefix;
        c.pathPattern = pathPattern;
        c.exactEndpoint = exactEndpoint;
        c.notes = notes;
        c.hits.set(hits.get());
        c.updatedAtMs = updatedAtMs;
        c.lastMatchedAtMs = lastMatchedAtMs;
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("priority", Integer.valueOf(priority));
        m.put("enabled", Boolean.valueOf(enabled));
        m.put("allow", Boolean.valueOf(allow));
        m.put("scope", scope.name());
        m.put("methods", new ArrayList<>(methods));
        m.put("uiActions", new ArrayList<>(uiActions));
        m.put("branchPrefix", branchPrefix != null ? branchPrefix : "");
        m.put("pathPattern", pathPattern != null ? pathPattern : "");
        m.put("exactEndpoint", exactEndpoint != null ? exactEndpoint : "");
        m.put("mode", mode.name());
        m.put("notes", notes != null ? notes : "");
        m.put("hits", Long.valueOf(hits.get()));
        m.put("createdAtMs", Long.valueOf(createdAtMs));
        m.put("updatedAtMs", Long.valueOf(updatedAtMs));
        m.put("lastMatchedAtMs", Long.valueOf(lastMatchedAtMs));
        return m;
    }

    public String toJson() {
        return MiniJson.stringify(toMap());
    }

    public static PolicyRule fromMap(Map<?, ?> rawMap) {
        if (rawMap == null) {
            return new PolicyRule();
        }
        Map<String, Object> m = toStringMap(rawMap);
        String id = MiniJson.asString(m, "id", "");
        String name = MiniJson.asString(m, "name", "Policy");
        int priority = asInt(m.get("priority"), 100);
        boolean enabled = MiniJson.asBoolean(m, "enabled", true);
        boolean allow = MiniJson.asBoolean(m, "allow", false);
        PolicyScope scope = PolicyScope.fromString(MiniJson.asString(m, "scope", "GLOBAL"), PolicyScope.GLOBAL);
        GuardrailMode mode = GuardrailMode.fromString(MiniJson.asString(m, "mode", "HOLD_AT_PROXY"), GuardrailMode.HOLD_AT_PROXY);
        PolicyRule rule = new PolicyRule(id, name, priority, enabled, allow, scope, mode);
        for (String s : MiniJson.asStringList(m, "methods")) {
            rule.withMethods(s);
        }
        for (String a : MiniJson.asStringList(m, "uiActions")) {
            rule.withUiActions(a);
        }
        rule.branchPrefix = MiniJson.asString(m, "branchPrefix", "");
        rule.pathPattern = MiniJson.asString(m, "pathPattern", "");
        rule.exactEndpoint = MiniJson.asString(m, "exactEndpoint", "");
        rule.notes = MiniJson.asString(m, "notes", "");
        Object hitsObj = m.get("hits");
        if (hitsObj instanceof Number) {
            rule.setHits(((Number) hitsObj).longValue());
        }
        Object upObj = m.get("updatedAtMs");
        if (upObj instanceof Number) {
            rule.updatedAtMs = ((Number) upObj).longValue();
        }
        Object lmObj = m.get("lastMatchedAtMs");
        if (lmObj instanceof Number) {
            rule.lastMatchedAtMs = ((Number) lmObj).longValue();
        }
        return rule;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public Set<String> getMethodsView() {
        return Collections.unmodifiableSet(methods);
    }

    public Set<String> getUiActionsView() {
        return Collections.unmodifiableSet(uiActions);
    }
}
