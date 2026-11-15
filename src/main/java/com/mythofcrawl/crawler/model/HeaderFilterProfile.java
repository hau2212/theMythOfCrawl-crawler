package com.mythofcrawl.crawler.model;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Custom HTTP Header Filter & Modifier rules (e.g., X-Account: a, Authorization, Cookie, remove headers).
 *
 * Rules can be scoped globally across all Target Hosts or filtered by host/path substring or /regex/.
 * By default, when enabled, rules apply during Browser Crawl via Proxy while leaving Extender replay
 * packets unmodified unless the user explicitly toggles applyOnExtenderPush.
 */
public final class HeaderFilterProfile {

    public enum Action {
        SET("Gắn / Ghi đè"),
        ADD_IF_MISSING("Thêm nếu chưa có"),
        REMOVE("Xóa Header");

        public final String label;

        Action(String label) {
            this.label = label;
        }

        public static Action fromString(String s) {
            if (s == null) {
                return SET;
            }
            for (Action a : values()) {
                if (a.name().equalsIgnoreCase(s.trim()) || a.label.equalsIgnoreCase(s.trim())) {
                    return a;
                }
            }
            return SET;
        }
    }

    public static final class Rule {
        public final boolean enabled;
        public final Action action;
        public final String headerName;
        public final String headerValue;
        public final String scopePattern;

        public Rule(boolean enabled, Action action, String headerName, String headerValue, String scopePattern) {
            this.enabled = enabled;
            this.action = action != null ? action : Action.SET;
            this.headerName = sanitizeHeaderName(headerName);
            this.headerValue = sanitizeHeaderValue(headerValue);
            this.scopePattern = scopePattern != null ? scopePattern.trim() : "";
        }

        public boolean matchesTarget(String host, String urlOrPath) {
            String p = scopePattern;
            if (p.isEmpty() || "*".equals(p)) {
                return true;
            }
            String h = host != null ? host.toLowerCase(Locale.ROOT) : "";
            String u = urlOrPath != null ? urlOrPath.toLowerCase(Locale.ROOT) : "";
            String rawUrl = urlOrPath != null ? urlOrPath : "";
            String combined = h + " " + rawUrl;
            if (p.length() >= 2 && p.startsWith("/") && p.endsWith("/")) {
                String expr = p.substring(1, p.length() - 1);
                try {
                    Pattern pat = Pattern.compile(expr, Pattern.CASE_INSENSITIVE);
                    return pat.matcher(combined).find() || pat.matcher(rawUrl).find() || pat.matcher(h).find();
                } catch (Exception ignored) {
                    return false;
                }
            }
            String pl = p.toLowerCase(Locale.ROOT);
            return h.contains(pl) || u.contains(pl);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("action", action.name());
            m.put("headerName", headerName);
            m.put("headerValue", headerValue);
            m.put("scopePattern", scopePattern);
            return m;
        }

        public static Rule fromMap(Map<?, ?> m) {
            if (m == null) {
                return null;
            }
            Object enObj = m.get("enabled");
            boolean en = enObj == null || Boolean.TRUE.equals(enObj)
                || "true".equalsIgnoreCase(String.valueOf(enObj));
            Action act = Action.fromString(m.get("action") != null ? String.valueOf(m.get("action")) : "SET");
            String name = m.get("headerName") != null ? String.valueOf(m.get("headerName")) : "";
            String val = m.get("headerValue") != null ? String.valueOf(m.get("headerValue")) : "";
            String scope = m.get("scopePattern") != null ? String.valueOf(m.get("scopePattern")) : "";
            Rule r = new Rule(en, act, name, val, scope);
            return r.headerName.isEmpty() ? null : r;
        }
    }

    private volatile boolean enabled = false;
    private volatile boolean applyOnProxyCrawl = true;
    private volatile boolean applyOnExtenderPush = false;
    private final List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isApplyOnProxyCrawl() {
        return applyOnProxyCrawl;
    }

    public void setApplyOnProxyCrawl(boolean applyOnProxyCrawl) {
        this.applyOnProxyCrawl = applyOnProxyCrawl;
    }

    public boolean isApplyOnExtenderPush() {
        return applyOnExtenderPush;
    }

    public void setApplyOnExtenderPush(boolean applyOnExtenderPush) {
        this.applyOnExtenderPush = applyOnExtenderPush;
    }

    public synchronized List<Rule> getRulesSnapshot() {
        return new ArrayList<>(rules);
    }

    public synchronized void setRules(List<Rule> newRules) {
        rules.clear();
        if (newRules != null) {
            for (Rule r : newRules) {
                if (r != null && !r.headerName.isEmpty()) {
                    rules.add(r);
                }
            }
        }
    }

    public synchronized void addRule(Rule r) {
        if (r != null && !r.headerName.isEmpty()) {
            rules.add(r);
        }
    }

    public synchronized void replaceRule(int idx, Rule r) {
        if (idx >= 0 && idx < rules.size() && r != null && !r.headerName.isEmpty()) {
            rules.set(idx, r);
        }
    }

    public synchronized void removeRule(int idx) {
        if (idx >= 0 && idx < rules.size()) {
            rules.remove(idx);
        }
    }

    public synchronized void toggleRuleEnabled(int idx, boolean en) {
        if (idx >= 0 && idx < rules.size()) {
            Rule old = rules.get(idx);
            rules.set(idx, new Rule(en, old.action, old.headerName, old.headerValue, old.scopePattern));
        }
    }

    public synchronized int activeRuleCount() {
        if (!enabled) {
            return 0;
        }
        int c = 0;
        for (Rule r : rules) {
            if (r.enabled && !r.headerName.isEmpty()) {
                c++;
            }
        }
        return c;
    }

    /**
     * Applies matching enabled rules onto a Burp Montoya {@link HttpRequest}.
     * Skips restricted pseudo/framing headers (Host, Content-Length, Transfer-Encoding).
     */
    public HttpRequest applyToMontoyaRequest(HttpRequest req, String host, String urlOrPath, boolean isExtenderPush) {
        if (req == null || !enabled) {
            return req;
        }
        if (isExtenderPush && !applyOnExtenderPush) {
            return req;
        }
        if (!isExtenderPush && !applyOnProxyCrawl) {
            return req;
        }
        List<Rule> snapshot = getRulesSnapshot();
        HttpRequest current = req;
        for (Rule r : snapshot) {
            if (!r.enabled || r.headerName.isEmpty() || isProtectedHeader(r.headerName)) {
                continue;
            }
            if (!r.matchesTarget(host, urlOrPath)) {
                continue;
            }
            boolean exists = hasHeaderIgnoreCase(current, r.headerName);
            switch (r.action) {
                case REMOVE:
                    if (exists) {
                        current = removeAllIgnoreCase(current, r.headerName);
                    }
                    break;
                case ADD_IF_MISSING:
                    if (!exists) {
                        current = current.withAddedHeader(r.headerName, r.headerValue);
                    }
                    break;
                case SET:
                    if (exists) {
                        current = removeAllIgnoreCase(current, r.headerName);
                    }
                    current = current.withAddedHeader(r.headerName, r.headerValue);
                    break;
                default:
                    break;
            }
        }
        return current;
    }

    /** Pure-Java header transformation for preview & unit tests without requiring live Burp runtime. */
    public List<Map.Entry<String, String>> applyToHeaderList(
        List<Map.Entry<String, String>> inputHeaders,
        String host,
        String urlOrPath
    ) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (inputHeaders != null) {
            for (Map.Entry<String, String> e : inputHeaders) {
                out.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
            }
        }
        if (!enabled) {
            return out;
        }
        for (Rule r : getRulesSnapshot()) {
            if (!r.enabled || r.headerName.isEmpty() || isProtectedHeader(r.headerName)) {
                continue;
            }
            if (!r.matchesTarget(host, urlOrPath)) {
                continue;
            }
            boolean exists = false;
            for (Map.Entry<String, String> e : out) {
                if (e.getKey().equalsIgnoreCase(r.headerName)) {
                    exists = true;
                    break;
                }
            }
            switch (r.action) {
                case REMOVE:
                    out.removeIf(e -> e.getKey().equalsIgnoreCase(r.headerName));
                    break;
                case ADD_IF_MISSING:
                    if (!exists) {
                        out.add(new AbstractMap.SimpleEntry<>(r.headerName, r.headerValue));
                    }
                    break;
                case SET:
                    out.removeIf(e -> e.getKey().equalsIgnoreCase(r.headerName));
                    out.add(new AbstractMap.SimpleEntry<>(r.headerName, r.headerValue));
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static boolean hasHeaderIgnoreCase(HttpRequest req, String name) {
        for (HttpHeader h : req.headers()) {
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static HttpRequest removeAllIgnoreCase(HttpRequest req, String name) {
        HttpRequest current = req;
        List<String> matchingNames = new ArrayList<>();
        for (HttpHeader h : current.headers()) {
            if (h.name() != null && h.name().equalsIgnoreCase(name) && !matchingNames.contains(h.name())) {
                matchingNames.add(h.name());
            }
        }
        for (String exact : matchingNames) {
            current = current.withRemovedHeader(exact);
        }
        return current;
    }

    private static boolean isProtectedHeader(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        return l.equals("host") || l.equals("content-length") || l.equals("transfer-encoding")
            || name.startsWith(":");
    }

    public static String sanitizeHeaderName(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = raw.replaceAll("[\\r\\n\\t: ]+", "").trim();
        return clean;
    }

    public static String sanitizeHeaderValue(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r", "").replace("\n", "").trim();
    }

    public synchronized Map<String, Object> toPersistMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("applyOnProxyCrawl", applyOnProxyCrawl);
        m.put("applyOnExtenderPush", applyOnExtenderPush);
        List<Map<String, Object>> ruleList = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            ruleList.add(r.toMap());
        }
        m.put("rules", ruleList);
        return m;
    }

    public synchronized void applyPersistMap(Map<?, ?> m) {
        if (m == null) {
            return;
        }
        if (m.containsKey("enabled")) {
            enabled = Boolean.TRUE.equals(m.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(m.get("enabled")));
        }
        if (m.containsKey("applyOnProxyCrawl")) {
            applyOnProxyCrawl = Boolean.TRUE.equals(m.get("applyOnProxyCrawl"))
                || "true".equalsIgnoreCase(String.valueOf(m.get("applyOnProxyCrawl")));
        }
        if (m.containsKey("applyOnExtenderPush")) {
            applyOnExtenderPush = Boolean.TRUE.equals(m.get("applyOnExtenderPush"))
                || "true".equalsIgnoreCase(String.valueOf(m.get("applyOnExtenderPush")));
        }
        Object rawRules = m.get("rules");
        if (rawRules instanceof List<?>) {
            List<Rule> loaded = new ArrayList<>();
            for (Object item : (List<?>) rawRules) {
                if (item instanceof Map<?, ?>) {
                    Rule r = Rule.fromMap((Map<?, ?>) item);
                    if (r != null) {
                        loaded.add(r);
                    }
                }
            }
            setRules(loaded);
        }
    }
}
