package com.mythofcrawl.crawler.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime configuration for the Browser + Excel Burp Auto-Crawler.
 * Serializable to/from JSON so settings, column preferences, and custom FormFill values
 * persist across Burp restarts.
 */
public final class CrawlConfig {

    /**
     * Keep default Danger Guard minimal (session logout only) so business actions like
     * delete/save/order/checkout are NOT accidentally skipped unless the user explicitly adds them.
     */
    public static final String DEFAULT_DANGER_KEYWORDS =
        "logout,log out,sign out,signout,đăng xuất";

    private static final Pattern MARKDOWN_LINK_RX = Pattern.compile("\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");

    /**
     * Parsed scope rule supporting both host-wide scope ({@code vnexpress.net}) and
     * sub-path URL prefix scope ({@code vnexpress.net/the-gioi} or {@code https://vnexpress.net/the-gioi}),
     * where {@code https://vnexpress.net/} and sibling paths are strictly out-of-scope and blocked.
     */
    public static final class ScopeRule {
        public final String hostPattern;
        public final String pathPrefix;
        public final boolean wildcardSuffix;

        public ScopeRule(String hostPattern, String pathPrefix, boolean wildcardSuffix) {
            this.hostPattern = hostPattern != null ? hostPattern.trim().toLowerCase(Locale.ROOT) : "";
            this.pathPrefix = pathPrefix != null ? pathPrefix : "";
            this.wildcardSuffix = wildcardSuffix;
        }

        public Map<String, Object> toAgentMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", hostPattern);
            m.put("pathPrefix", pathPrefix);
            m.put("wildcardSuffix", wildcardSuffix);
            return m;
        }
    }

    private volatile String allowedHostsCsv = "";
    private volatile String seedUrl = "";
    private volatile boolean strictSeedPathScope = false;
    private volatile String apiBasePrefix = "";
    private volatile String routeRootLabel = "/";
    private volatile boolean strictExcelOnly = true;
    private volatile boolean ignoreTrailingSlash = true;
    private volatile boolean normalizeRouteIds = true;
    private volatile boolean dangerGuardEnabled = true;
    private volatile String dangerKeywordsCsv = DEFAULT_DANGER_KEYWORDS;
    private volatile boolean autoFillForms = true;
    private volatile boolean deepCrawlMode = true;
    private volatile boolean relaxCspWhileCrawling = true;
    private volatile boolean captureJsEndpoints = true;
    private volatile boolean bypassServiceWorker = true;
    private volatile boolean cdpSidecarEnabled = false;
    private volatile int cdpPort = 9222;
    private volatile String columnPrefsJson = "";
    /** Safe-by-default request gate: never auto-clicks or fires seed fetches until user allows it. */
    private volatile boolean allowAutoRequests = false;
    private volatile int actionWaitMs = 550;
    private volatile int maxActionsPerPage = 300;
    private volatile int maxPages = 500;
    private volatile int maxPaginationSamples = 5;
    private volatile int maxPageDurationSec = 90;

    private final FormFillProfile formFillProfile = new FormFillProfile();
    private final HeaderFilterProfile headerFilterProfile = new HeaderFilterProfile();

    public FormFillProfile getFormFillProfile() {
        return formFillProfile;
    }

    public HeaderFilterProfile getHeaderFilterProfile() {
        return headerFilterProfile;
    }

    public boolean isAllowAutoRequests() {
        return allowAutoRequests;
    }

    public void setAllowAutoRequests(boolean allowAutoRequests) {
        this.allowAutoRequests = allowAutoRequests;
    }

    public boolean isBypassServiceWorker() {
        return bypassServiceWorker;
    }

    public void setBypassServiceWorker(boolean bypassServiceWorker) {
        this.bypassServiceWorker = bypassServiceWorker;
    }

    public boolean isCdpSidecarEnabled() {
        return cdpSidecarEnabled;
    }

    public void setCdpSidecarEnabled(boolean cdpSidecarEnabled) {
        this.cdpSidecarEnabled = cdpSidecarEnabled;
    }

    public int getCdpPort() {
        return cdpPort;
    }

    public void setCdpPort(int cdpPort) {
        if (cdpPort >= 1 && cdpPort <= 65535) {
            this.cdpPort = cdpPort;
        } else {
            this.cdpPort = 9222;
        }
    }

    public String getColumnPrefsJson() {
        return columnPrefsJson;
    }

    public void setColumnPrefsJson(String columnPrefsJson) {
        this.columnPrefsJson = columnPrefsJson != null ? columnPrefsJson : "";
    }

    public String getAllowedHostsCsv() {
        return allowedHostsCsv;
    }

    public void setAllowedHostsCsv(String allowedHostsCsv) {
        this.allowedHostsCsv = allowedHostsCsv != null ? unwrapMarkdownLinks(allowedHostsCsv).trim() : "";
    }

    public boolean isStrictSeedPathScope() {
        return strictSeedPathScope;
    }

    public void setStrictSeedPathScope(boolean strictSeedPathScope) {
        this.strictSeedPathScope = strictSeedPathScope;
    }

    public static String unwrapMarkdownLinks(String raw) {
        if (raw == null || !raw.contains("](")) {
            return raw != null ? raw : "";
        }
        Matcher m = MARKDOWN_LINK_RX.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Parses configured scope rules (Host + optional Path Prefix), applying {@code seedUrl}'s
     * sub-path prefix as well whenever {@link #isStrictSeedPathScope()} is enabled.
     */
    public List<ScopeRule> getEffectiveScopeRules() {
        List<ScopeRule> parsed = new ArrayList<>();
        String cleanedCsv = unwrapMarkdownLinks(allowedHostsCsv);
        if (cleanedCsv != null && !cleanedCsv.trim().isEmpty()) {
            for (String part : cleanedCsv.split("[,;\\s]+")) {
                ScopeRule r = parseSingleScopeToken(part);
                if (r != null && !r.hostPattern.isEmpty()) {
                    parsed.add(r);
                }
            }
        }

        ScopeRule seedRule = strictSeedPathScope ? parseSingleScopeToken(seedUrl) : null;
        if (seedRule != null && !seedRule.hostPattern.isEmpty() && !seedRule.pathPrefix.isEmpty()) {
            if (parsed.isEmpty()) {
                parsed.add(seedRule);
            } else {
                List<ScopeRule> merged = new ArrayList<>(parsed.size());
                boolean matchedHost = false;
                for (ScopeRule r : parsed) {
                    if (r.pathPrefix.isEmpty() && hostMatchesPattern(seedRule.hostPattern, r.hostPattern)) {
                        merged.add(new ScopeRule(r.hostPattern, seedRule.pathPrefix, seedRule.wildcardSuffix));
                        matchedHost = true;
                    } else {
                        merged.add(r);
                    }
                }
                if (!matchedHost && !isHostInRuleList(seedRule.hostPattern, merged)) {
                    merged.add(seedRule);
                }
                return merged;
            }
        }
        return parsed;
    }

    public List<Map<String, Object>> getEffectiveScopeRulesForAgent() {
        List<ScopeRule> rules = getEffectiveScopeRules();
        List<Map<String, Object>> out = new ArrayList<>(rules.size());
        for (ScopeRule r : rules) {
            out.add(r.toAgentMap());
        }
        return out;
    }

    public boolean hasPathScopeRestriction() {
        for (ScopeRule r : getEffectiveScopeRules()) {
            if (!r.pathPrefix.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a safe in-scope entry URL (preferring {@code seedUrl} if in scope, or synthesizing
     * {@code https://<host><pathPrefix>} from the first concrete path-scoped rule).
     */
    public String getPrimaryAllowedEntryUrl() {
        String s = seedUrl != null ? seedUrl.trim() : "";
        if (!s.isEmpty()) {
            String fullSeed = (s.startsWith("http://") || s.startsWith("https://")) ? s : ("https://" + s);
            if (isUrlAllowedForCrawl(fullSeed)) {
                return fullSeed;
            }
        }
        for (ScopeRule r : getEffectiveScopeRules()) {
            if (!r.hostPattern.isEmpty() && !r.hostPattern.contains("*")) {
                String path = r.pathPrefix.isEmpty() ? "/" : r.pathPrefix;
                return "https://" + r.hostPattern + path;
            }
        }
        return "";
    }

    public Set<String> getAllowedHostsSet() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (ScopeRule r : getEffectiveScopeRules()) {
            if (!r.hostPattern.isEmpty()) {
                set.add(r.hostPattern);
            }
        }
        return set;
    }

    public boolean isHostAllowed(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        List<ScopeRule> rules = getEffectiveScopeRules();
        if (rules.isEmpty()) {
            return false;
        }
        for (ScopeRule rule : rules) {
            if (hostMatchesPattern(h, rule.hostPattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks BOTH host scope AND URL sub-path scope (e.g. when configured with
     * {@code https://vnexpress.net/the-gioi}, {@code https://vnexpress.net/the-gioi} and
     * {@code https://vnexpress.net/the-gioi/quan-su} return {@code true}, while
     * {@code https://vnexpress.net/} and {@code https://vnexpress.net/the-thao} return {@code false}).
     */
    public boolean isUrlAllowedForCrawl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return false;
        }
        String trimmed = unwrapMarkdownLinks(rawUrl).trim();
        try {
            URI u = URI.create(trimmed);
            String scheme = u.getScheme();
            if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = u.getHost();
            if (host == null || host.isEmpty()) {
                return false;
            }
            String hLower = host.toLowerCase(Locale.ROOT);
            String rawPath = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
            String frag = u.getRawFragment();
            String spaHashRoute = "";
            if (frag != null && (frag.startsWith("/") || frag.startsWith("!/"))) {
                spaHashRoute = frag.startsWith("!/") ? frag.substring(1) : frag;
                int qInHash = spaHashRoute.indexOf('?');
                if (qInHash >= 0) {
                    spaHashRoute = spaHashRoute.substring(0, qInHash);
                }
            }

            List<ScopeRule> rules = getEffectiveScopeRules();
            if (rules.isEmpty()) {
                return false;
            }
            for (ScopeRule r : rules) {
                if (!hostMatchesPattern(hLower, r.hostPattern)) {
                    continue;
                }
                if (r.pathPrefix.isEmpty()) {
                    return true;
                }
                if (pathMatchesPrefix(rawPath, r.pathPrefix, r.wildcardSuffix)) {
                    return true;
                }
                if (!spaHashRoute.isEmpty() && ("/".equals(rawPath) || rawPath.isEmpty())
                    && pathMatchesPrefix(spaHashRoute, r.pathPrefix, r.wildcardSuffix)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean pathMatchesPrefix(String candidatePath, String requiredPrefix, boolean wildcardSuffix) {
        if (requiredPrefix == null || requiredPrefix.isEmpty() || "/".equals(requiredPrefix)) {
            return true;
        }
        String p = (candidatePath == null || candidatePath.isEmpty()) ? "/" : candidatePath;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String pLower = p.toLowerCase(Locale.ROOT);
        String prefLower = requiredPrefix.toLowerCase(Locale.ROOT);
        if (prefLower.length() > 1 && prefLower.endsWith("/")) {
            prefLower = prefLower.substring(0, prefLower.length() - 1);
        }
        if (pLower.equals(prefLower) || pLower.startsWith(prefLower + "/")) {
            return true;
        }
        if (wildcardSuffix && pLower.startsWith(prefLower)) {
            return true;
        }
        // Support CMS numeric pagination suffixes such as /the-gioi-p2 while rejecting /the-gioi-moi
        if (pLower.startsWith(prefLower + "-p") && pLower.length() > prefLower.length() + 2) {
            int idx = prefLower.length() + 2;
            int digits = 0;
            while (idx < pLower.length() && Character.isDigit(pLower.charAt(idx))) {
                idx++;
                digits++;
            }
            if (digits > 0 && (idx == pLower.length() || pLower.charAt(idx) == '/' || pLower.charAt(idx) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostInRuleList(String hostLower, List<ScopeRule> list) {
        for (ScopeRule r : list) {
            if (hostMatchesPattern(hostLower, r.hostPattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostMatchesPattern(String h, String pattern) {
        if (h == null || pattern == null || h.isEmpty() || pattern.isEmpty()) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return h.endsWith(suffix) || h.equals(pattern.substring(2));
        }
        if (pattern.startsWith("*") && pattern.length() > 1) {
            String root = pattern.substring(1);
            if (root.startsWith(".")) {
                root = root.substring(1);
            }
            return h.equals(root) || h.endsWith("." + root);
        }
        return h.equals(pattern);
    }

    private static ScopeRule parseSingleScopeToken(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        String tok = unwrapMarkdownLinks(rawToken).trim();
        if (tok.isEmpty()) {
            return null;
        }
        if ("*".equals(tok)) {
            return new ScopeRule("*", "", false);
        }
        String working = tok;
        if (working.regionMatches(true, 0, "http://", 0, 7)) {
            working = working.substring(7);
        } else if (working.regionMatches(true, 0, "https://", 0, 8)) {
            working = working.substring(8);
        }

        int hashIdx = working.indexOf('#');
        String fragPart = "";
        if (hashIdx >= 0) {
            fragPart = working.substring(hashIdx + 1).trim();
            working = working.substring(0, hashIdx);
        }
        int qIdx = working.indexOf('?');
        if (qIdx >= 0) {
            working = working.substring(0, qIdx);
        }

        int slashIdx = working.indexOf('/');
        String hostPart = slashIdx >= 0 ? working.substring(0, slashIdx) : working;
        String pathPart = slashIdx >= 0 ? working.substring(slashIdx) : "";

        int colon = hostPart.indexOf(':');
        if (colon >= 0) {
            hostPart = hostPart.substring(0, colon);
        }
        hostPart = hostPart.trim().toLowerCase(Locale.ROOT);
        if (hostPart.isEmpty()) {
            return null;
        }

        // If SPA hash route was specified on root (e.g. app.com/#/the-gioi), use hash route as prefix
        if ((pathPart.isEmpty() || "/".equals(pathPart))
            && (fragPart.startsWith("/") || fragPart.startsWith("!/"))) {
            pathPart = fragPart.startsWith("!/") ? fragPart.substring(1) : fragPart;
            int qh = pathPart.indexOf('?');
            if (qh >= 0) {
                pathPart = pathPart.substring(0, qh);
            }
        }

        boolean wild = false;
        pathPart = pathPart.trim();
        if (pathPart.endsWith("*") && pathPart.length() > 1) {
            wild = true;
            pathPart = pathPart.substring(0, pathPart.length() - 1);
        }
        if (pathPart.length() > 1 && pathPart.endsWith("/")) {
            pathPart = pathPart.substring(0, pathPart.length() - 1);
        }
        if ("/".equals(pathPart)) {
            pathPart = "";
        }
        return new ScopeRule(hostPart, pathPart, wild);
    }

    public String getApiBasePrefix() {
        return apiBasePrefix;
    }

    public void setApiBasePrefix(String apiBasePrefix) {
        this.apiBasePrefix = apiBasePrefix != null ? apiBasePrefix.trim() : "";
    }

    public String getRouteRootLabel() {
        return routeRootLabel;
    }

    public void setRouteRootLabel(String routeRootLabel) {
        String clean = routeRootLabel != null ? routeRootLabel.trim() : "";
        this.routeRootLabel = clean.isEmpty() ? "/" : clean;
    }

    public boolean isStrictExcelOnly() {
        return strictExcelOnly;
    }

    public void setStrictExcelOnly(boolean strictExcelOnly) {
        this.strictExcelOnly = strictExcelOnly;
    }

    public boolean isIgnoreTrailingSlash() {
        return ignoreTrailingSlash;
    }

    public void setIgnoreTrailingSlash(boolean ignoreTrailingSlash) {
        this.ignoreTrailingSlash = ignoreTrailingSlash;
    }

    public boolean isNormalizeRouteIds() {
        return normalizeRouteIds;
    }

    public void setNormalizeRouteIds(boolean normalizeRouteIds) {
        this.normalizeRouteIds = normalizeRouteIds;
    }

    public boolean isDangerGuardEnabled() {
        return dangerGuardEnabled;
    }

    public void setDangerGuardEnabled(boolean dangerGuardEnabled) {
        this.dangerGuardEnabled = dangerGuardEnabled;
    }

    public String getDangerKeywordsCsv() {
        return dangerKeywordsCsv;
    }

    public void setDangerKeywordsCsv(String dangerKeywordsCsv) {
        this.dangerKeywordsCsv = dangerKeywordsCsv != null ? dangerKeywordsCsv : "";
    }

    public List<String> getDangerKeywordsList() {
        List<String> list = new ArrayList<>();
        if (dangerKeywordsCsv == null || dangerKeywordsCsv.trim().isEmpty()) {
            return list;
        }
        for (String s : dangerKeywordsCsv.split("[,;\\r\\n]+")) {
            String k = s.trim().toLowerCase();
            if (!k.isEmpty()) {
                list.add(k);
            }
        }
        return list;
    }

    public boolean isAutoFillForms() {
        return autoFillForms;
    }

    public void setAutoFillForms(boolean autoFillForms) {
        this.autoFillForms = autoFillForms;
    }

    public boolean isDeepCrawlMode() {
        return deepCrawlMode;
    }

    public void setDeepCrawlMode(boolean deepCrawlMode) {
        this.deepCrawlMode = deepCrawlMode;
    }

    public boolean isRelaxCspWhileCrawling() {
        return relaxCspWhileCrawling;
    }

    public void setRelaxCspWhileCrawling(boolean relaxCspWhileCrawling) {
        this.relaxCspWhileCrawling = relaxCspWhileCrawling;
    }

    public boolean isCaptureJsEndpoints() {
        return captureJsEndpoints;
    }

    public void setCaptureJsEndpoints(boolean captureJsEndpoints) {
        this.captureJsEndpoints = captureJsEndpoints;
    }

    public int getActionWaitMs() {
        return actionWaitMs;
    }

    public void setActionWaitMs(int actionWaitMs) {
        this.actionWaitMs = Math.max(150, Math.min(10000, actionWaitMs));
    }

    public int getMaxActionsPerPage() {
        return maxActionsPerPage;
    }

    public void setMaxActionsPerPage(int maxActionsPerPage) {
        this.maxActionsPerPage = Math.max(5, Math.min(5000, maxActionsPerPage));
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = Math.max(1, Math.min(10000, maxPages));
    }

    public String getSeedUrl() {
        return seedUrl;
    }

    public void setSeedUrl(String seedUrl) {
        this.seedUrl = seedUrl != null ? unwrapMarkdownLinks(seedUrl).trim() : "";
    }

    public int getMaxPaginationSamples() {
        return maxPaginationSamples;
    }

    public void setMaxPaginationSamples(int maxPaginationSamples) {
        this.maxPaginationSamples = Math.max(1, Math.min(100, maxPaginationSamples));
    }

    public int getMaxPageDurationSec() {
        return maxPageDurationSec;
    }

    public void setMaxPageDurationSec(int maxPageDurationSec) {
        this.maxPageDurationSec = Math.max(15, Math.min(600, maxPageDurationSec));
    }

    public String toPersistJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pathStyleV2", true);
        m.put("allowedHostsCsv", allowedHostsCsv);
        m.put("seedUrl", seedUrl);
        m.put("strictSeedPathScope", strictSeedPathScope);
        m.put("apiBasePrefix", apiBasePrefix);
        m.put("routeRootLabel", routeRootLabel);
        m.put("strictExcelOnly", strictExcelOnly);
        m.put("ignoreTrailingSlash", ignoreTrailingSlash);
        m.put("normalizeRouteIds", normalizeRouteIds);
        m.put("dangerGuardEnabled", dangerGuardEnabled);
        m.put("dangerKeywordsCsv", dangerKeywordsCsv);
        m.put("autoFillForms", autoFillForms);
        m.put("deepCrawlMode", deepCrawlMode);
        m.put("relaxCspWhileCrawling", relaxCspWhileCrawling);
        m.put("captureJsEndpoints", captureJsEndpoints);
        m.put("bypassServiceWorker", bypassServiceWorker);
        m.put("cdpSidecarEnabled", cdpSidecarEnabled);
        m.put("cdpPort", cdpPort);
        m.put("columnPrefsJson", columnPrefsJson);
        m.put("actionWaitMs", actionWaitMs);
        m.put("maxActionsPerPage", maxActionsPerPage);
        m.put("maxPages", maxPages);
        m.put("maxPaginationSamples", maxPaginationSamples);
        m.put("maxPageDurationSec", maxPageDurationSec);
        m.put("formFill", formFillProfile.toPersistMap());
        m.put("headerFilter", headerFilterProfile.toPersistMap());
        return MiniJson.stringify(m);
    }

    @SuppressWarnings("unchecked")
    public void loadFromPersistJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            Map<String, Object> m = MiniJson.parseObject(json);
            if (m.containsKey("allowedHostsCsv")) setAllowedHostsCsv(MiniJson.asString(m, "allowedHostsCsv", ""));
            if (m.containsKey("seedUrl")) setSeedUrl(MiniJson.asString(m, "seedUrl", ""));
            strictSeedPathScope = MiniJson.asBoolean(m, "strictSeedPathScope", strictSeedPathScope);
            if (m.containsKey("apiBasePrefix")) setApiBasePrefix(MiniJson.asString(m, "apiBasePrefix", ""));
            if (m.containsKey("routeRootLabel")) {
                String savedRoot = MiniJson.asString(m, "routeRootLabel", "/");
                // One-time upgrade from legacy default "toppage" to readable "/" unless user explicitly saved v2 prefs
                if (!m.containsKey("pathStyleV2") && "toppage".equalsIgnoreCase(savedRoot)) {
                    savedRoot = "/";
                }
                setRouteRootLabel(savedRoot);
            }
            strictExcelOnly = MiniJson.asBoolean(m, "strictExcelOnly", strictExcelOnly);
            ignoreTrailingSlash = MiniJson.asBoolean(m, "ignoreTrailingSlash", ignoreTrailingSlash);
            normalizeRouteIds = MiniJson.asBoolean(m, "normalizeRouteIds", normalizeRouteIds);
            dangerGuardEnabled = MiniJson.asBoolean(m, "dangerGuardEnabled", dangerGuardEnabled);
            if (m.containsKey("dangerKeywordsCsv")) setDangerKeywordsCsv(MiniJson.asString(m, "dangerKeywordsCsv", DEFAULT_DANGER_KEYWORDS));
            autoFillForms = MiniJson.asBoolean(m, "autoFillForms", autoFillForms);
            deepCrawlMode = MiniJson.asBoolean(m, "deepCrawlMode", deepCrawlMode);
            relaxCspWhileCrawling = MiniJson.asBoolean(m, "relaxCspWhileCrawling", relaxCspWhileCrawling);
            captureJsEndpoints = MiniJson.asBoolean(m, "captureJsEndpoints", captureJsEndpoints);
            bypassServiceWorker = MiniJson.asBoolean(m, "bypassServiceWorker", bypassServiceWorker);
            cdpSidecarEnabled = MiniJson.asBoolean(m, "cdpSidecarEnabled", cdpSidecarEnabled);
            setCdpPort(asInt(m.get("cdpPort"), cdpPort));
            if (m.containsKey("columnPrefsJson")) setColumnPrefsJson(MiniJson.asString(m, "columnPrefsJson", ""));
            setActionWaitMs(asInt(m.get("actionWaitMs"), actionWaitMs));
            setMaxActionsPerPage(asInt(m.get("maxActionsPerPage"), maxActionsPerPage));
            setMaxPages(asInt(m.get("maxPages"), maxPages));
            setMaxPaginationSamples(asInt(m.get("maxPaginationSamples"), maxPaginationSamples));
            setMaxPageDurationSec(asInt(m.get("maxPageDurationSec"), maxPageDurationSec));
            Object ff = m.get("formFill");
            if (ff instanceof Map<?, ?>) {
                formFillProfile.applyPersistMap((Map<String, Object>) ff);
            }
            Object hf = m.get("headerFilter");
            if (hf instanceof Map<?, ?>) {
                headerFilterProfile.applyPersistMap((Map<?, ?>) hf);
            }
        } catch (Exception ignored) {
        }
    }

    private static int asInt(Object val, int def) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val != null) {
            try {
                return Integer.parseInt(String.valueOf(val).trim());
            } catch (Exception ignored) {
            }
        }
        return def;
    }
}
