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
        return "id".equals(kl) || kl.endsWith("id") || kl.endsWith("_id");
    }

    private static boolean isValidIdLiteral(String s) {
        return DIGITS_RX.matcher(s).matches() || UUID_RX.matcher(s).matches();
    }

    private static boolean isIdLikeSegment(String seg) {
        return DIGITS_RX.matcher(seg).matches() || UUID_RX.matcher(seg).matches();
    }
}
