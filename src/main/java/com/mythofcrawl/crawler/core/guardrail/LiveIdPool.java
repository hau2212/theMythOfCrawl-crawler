package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.model.MiniJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stage-2 Live ID Pool: harvests fresh resource IDs from GET/POST JSON responses
 * and substitutes stale numeric/UUID IDs on replay without modifying production data blindly.
 */
public final class LiveIdPool {

    private static final int MAX_IDS_PER_RESOURCE = 32;
    private static final Pattern UUID_RX = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DIGITS_RX = Pattern.compile("^\\d+$");
    private static final Pattern CODE_ID_RX = Pattern.compile("^[A-Za-z0-9_-]{3,36}$");
    private static final Pattern ROUTE_PARAM_SEG = Pattern.compile(
        "^(?:\\$\\{([^}]+)\\}|\\{([^}]+)\\}|:([A-Za-z_][A-Za-z0-9_]*)|<([^>]+)>)$");

    private final Object lock = new Object();
    private final Map<String, Deque<String>> pool = new LinkedHashMap<>();

    public void putId(String resourceKey, String idValue) {
        if (resourceKey == null || resourceKey.trim().isEmpty() || idValue == null || idValue.trim().isEmpty()) {
            return;
        }
        String key = resourceKey.trim().toLowerCase(Locale.ROOT);
        String val = idValue.trim();
        synchronized (lock) {
            Deque<String> q = pool.computeIfAbsent(key, k -> new ArrayDeque<>());
            q.remove(val);
            q.addFirst(val);
            while (q.size() > MAX_IDS_PER_RESOURCE) {
                q.removeLast();
            }
        }
    }

    public String getLatestId(String resourceKey) {
        if (resourceKey == null || resourceKey.trim().isEmpty()) {
            return null;
        }
        String key = resourceKey.trim().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Deque<String> q = pool.get(key);
            return (q != null && !q.isEmpty()) ? q.peekFirst() : null;
        }
    }

    public List<String> snapshotIds(String resourceKey) {
        if (resourceKey == null || resourceKey.trim().isEmpty()) {
            return List.of();
        }
        String key = resourceKey.trim().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Deque<String> q = pool.get(key);
            return q != null ? new ArrayList<>(q) : List.of();
        }
    }

    public int harvestFromJson(String endpointPath, String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return 0;
        }
        String defaultResource = inferResourceFromPath(endpointPath);
        Object parsed;
        String trimmed = jsonBody.trim();
        if (trimmed.startsWith("{")) {
            parsed = MiniJson.parseObject(trimmed);
        } else if (trimmed.startsWith("[")) {
            parsed = MiniJson.parseObject("{\"items\":" + trimmed + "}").get("items");
        } else {
            return 0;
        }
        return harvestRecursive(defaultResource, parsed, 0);
    }

    private int harvestRecursive(String defaultResource, Object node, int depth) {
        if (node == null || depth > 5) {
            return 0;
        }
        int count = 0;
        if (node instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                String kl = k.toLowerCase(Locale.ROOT);
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    String nextRes = (kl.equals("data") || kl.equals("items") || kl.equals("result") || kl.equals("records"))
                        ? defaultResource : normalizeResourceName(kl);
                    count += harvestRecursive(nextRes, v, depth + 1);
                } else {
                    String sv = String.valueOf(v).trim();
                    if (isCandidateIdField(kl) && isValidIdLiteral(sv)) {
                        String res = deriveResourceForField(kl, defaultResource);
                        putId(res, sv);
                        count++;
                    }
                }
            }
        } else if (node instanceof List<?>) {
            for (Object item : (List<?>) node) {
                count += harvestRecursive(defaultResource, item, depth + 1);
            }
        }
        return count;
    }

    /**
     * Replaces numeric or UUID segments in {@code rawPath} when a fresher live ID exists
     * for the preceding resource segment (e.g. {@code /api/orders/999} -> {@code /api/orders/<liveId>}).
     */
    public String substituteInPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        int q = rawPath.indexOf('?');
        String pathOnly = q >= 0 ? rawPath.substring(0, q) : rawPath;
        String query = q >= 0 ? rawPath.substring(q) : "";
        String[] parts = pathOnly.split("/", -1);
        boolean changed = false;
        String prevSeg = "";
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.isEmpty()) {
                continue;
            }
            if (isIdLikeSegment(seg) && !prevSeg.isEmpty()) {
                String live = getLatestId(prevSeg);
                if (live != null && !live.equals(seg)) {
                    parts[i] = live;
                    changed = true;
                }
            } else {
                prevSeg = normalizeResourceName(seg);
            }
        }
        return changed ? (String.join("/", parts) + query) : rawPath;
    }

    public int resourceCount() {
        synchronized (lock) {
            return pool.size();
        }
    }

    public int totalHarvestedIds() {
        synchronized (lock) {
            int sum = 0;
            for (Deque<String> q : pool.values()) {
                sum += q.size();
            }
            return sum;
        }
    }

    /**
     * Returns a bounded map of {@code resourceKey -> latestId} so the browser agent
     * can populate foreign-key inputs and parameterized routes with real harvested IDs.
     */
    public Map<String, String> snapshotLatestIds(int maxResources) {
        int cap = Math.max(1, maxResources);
        Map<String, String> out = new LinkedHashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, Deque<String>> e : pool.entrySet()) {
                Deque<String> q = e.getValue();
                if (q != null && !q.isEmpty()) {
                    out.put(e.getKey(), q.peekFirst());
                    if (out.size() >= cap) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Concretizes a parameterized route template (such as {@code /orders/{orderId}/detail},
     * {@code /users/:id}, or {@code /projects/${projectId}/settings}) using harvested IDs from
     * this pool, or {@code "1"} when {@code fallbackNumeric} is true. Returns {@code null} if
     * any placeholder remains unresolved.
     */
    public String concretizeRouteTemplate(String routeTemplate, boolean fallbackNumeric) {
        if (routeTemplate == null || routeTemplate.trim().isEmpty()) {
            return null;
        }
        int q = routeTemplate.indexOf('?');
        String pathOnly = q >= 0 ? routeTemplate.substring(0, q).trim() : routeTemplate.trim();
        String query = q >= 0 ? routeTemplate.substring(q) : "";
        if (!pathOnly.startsWith("/")) {
            pathOnly = "/" + pathOnly;
        }
        String[] segs = pathOnly.split("/", -1);
        String prevStatic = "";
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (seg.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = ROUTE_PARAM_SEG.matcher(seg);
            if (m.matches()) {
                String rawName = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2)
                    : (m.group(3) != null ? m.group(3) : m.group(4)));
                String resolved = resolveIdForParam(rawName, prevStatic);
                if (resolved == null && fallbackNumeric) {
                    resolved = "1";
                }
                if (resolved == null) {
                    return null;
                }
                segs[i] = resolved;
            } else if (seg.contains("${") || seg.contains("{") || seg.contains("*")) {
                return null;
            } else {
                prevStatic = normalizeResourceName(seg);
            }
        }
        return String.join("/", segs) + query;
    }

    private String resolveIdForParam(String paramName, String prevStaticSegment) {
        if (paramName != null && !paramName.isEmpty()) {
            String lower = paramName.trim().toLowerCase(Locale.ROOT);
            String derived = deriveResourceForField(lower, prevStaticSegment);
            String hit = getLatestId(derived);
            if (hit != null) {
                return hit;
            }
            String norm = normalizeResourceName(lower);
            hit = getLatestId(norm);
            if (hit != null) {
                return hit;
            }
            if (!norm.endsWith("s")) {
                hit = getLatestId(norm + "s");
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (prevStaticSegment != null && !prevStaticSegment.isEmpty()) {
            String hit = getLatestId(prevStaticSegment);
            if (hit != null) {
                return hit;
            }
            if (prevStaticSegment.endsWith("s") && prevStaticSegment.length() > 2) {
                hit = getLatestId(prevStaticSegment.substring(0, prevStaticSegment.length() - 1));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    public void clear() {
        synchronized (lock) {
            pool.clear();
        }
    }

    public static String inferResourceFromPath(String rawPath) {
        String clean = PolicyRule.normalizePathOnly(rawPath);
        String[] parts = clean.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isEmpty() && !isIdLikeSegment(p) && !p.startsWith("{")) {
                return normalizeResourceName(p);
            }
        }
        return "default";
    }

    private static String normalizeResourceName(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.endsWith("id") && l.length() > 2) {
            l = l.substring(0, l.length() - 2);
        }
        if (l.endsWith("_")) {
            l = l.substring(0, l.length() - 1);
        }
        return l.isEmpty() ? "default" : l;
    }

    private static String deriveResourceForField(String fieldLower, String fallbackResource) {
        if ("id".equals(fieldLower)) {
            return fallbackResource;
        }
        String base = fieldLower.replaceAll("(_id|id)$", "");
        if (base.isEmpty()) {
            return fallbackResource;
        }
        return base + "s";
    }

    private static boolean isCandidateIdField(String kl) {
        return "id".equals(kl) || "uuid".equals(kl) || kl.endsWith("id") || kl.endsWith("_id") || kl.endsWith("uuid");
    }

    private static boolean isValidIdLiteral(String s) {
        if (s == null || s.isEmpty() || "null".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return false;
        }
        return DIGITS_RX.matcher(s).matches()
            || UUID_RX.matcher(s).matches()
            || (s.length() >= 8 && s.length() <= 32 && CODE_ID_RX.matcher(s).matches() && containsDigit(s));
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdLikeSegment(String seg) {
        return DIGITS_RX.matcher(seg).matches() || UUID_RX.matcher(seg).matches();
    }
}
