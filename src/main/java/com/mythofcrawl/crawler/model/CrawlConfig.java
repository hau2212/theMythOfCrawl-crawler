package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime configuration for the Browser + Excel Burp Auto-Crawler.
 * Serializable to/from JSON so settings and custom FormFill values persist across Burp restarts.
 */
public final class CrawlConfig {

    /**
     * Keep default Danger Guard minimal (session logout only) so business actions like
     * delete/save/order/checkout are NOT accidentally skipped unless the user explicitly adds them.
     */
    public static final String DEFAULT_DANGER_KEYWORDS =
        "logout,log out,sign out,signout,đăng xuất";

    private volatile String allowedHostsCsv = "";
    private volatile String apiBasePrefix = "";
    private volatile String routeRootLabel = "toppage";
    private volatile boolean strictExcelOnly = true;
    private volatile boolean ignoreTrailingSlash = true;
    private volatile boolean normalizeRouteIds = true;
    private volatile boolean dangerGuardEnabled = true;
    private volatile String dangerKeywordsCsv = DEFAULT_DANGER_KEYWORDS;
    private volatile boolean autoFillForms = true;
    private volatile boolean deepCrawlMode = true;
    private volatile boolean relaxCspWhileCrawling = true;
    private volatile boolean captureJsEndpoints = true;
    /** Safe-by-default request gate: never auto-clicks or fires seed fetches until user allows it. */
    private volatile boolean allowAutoRequests = false;
    private volatile int actionWaitMs = 550;
    private volatile int maxActionsPerPage = 300;
    private volatile int maxPages = 500;
    private volatile int maxPaginationSamples = 5;

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

    public String getAllowedHostsCsv() {
        return allowedHostsCsv;
    }

    public void setAllowedHostsCsv(String allowedHostsCsv) {
        this.allowedHostsCsv = allowedHostsCsv != null ? allowedHostsCsv.trim() : "";
    }

    public Set<String> getAllowedHostsSet() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (allowedHostsCsv != null && !allowedHostsCsv.trim().isEmpty()) {
            for (String part : allowedHostsCsv.split("[,;\\s]+")) {
                String h = part.trim().toLowerCase();
                if (!h.isEmpty()) {
                    if (h.startsWith("http://")) {
                        h = h.substring(7);
                    } else if (h.startsWith("https://")) {
                        h = h.substring(8);
                    }
                    int slash = h.indexOf('/');
                    if (slash >= 0) {
                        h = h.substring(0, slash);
                    }
                    int colon = h.indexOf(':');
                    if (colon >= 0) {
                        h = h.substring(0, colon);
                    }
                    if (!h.isEmpty()) {
                        set.add(h);
                    }
                }
            }
        }
        return set;
    }

    public boolean isHostAllowed(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String h = host.trim().toLowerCase();
        Set<String> allowed = getAllowedHostsSet();
        if (allowed.isEmpty()) {
            return false;
        }
        for (String pattern : allowed) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (h.endsWith(suffix) || h.equals(pattern.substring(2))) {
                    return true;
                }
            } else if (h.equals(pattern)) {
                return true;
            }
        }
        return false;
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
        this.routeRootLabel = clean.isEmpty() ? "toppage" : clean;
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

    public int getMaxPaginationSamples() {
        return maxPaginationSamples;
    }

    public void setMaxPaginationSamples(int maxPaginationSamples) {
        this.maxPaginationSamples = Math.max(1, Math.min(100, maxPaginationSamples));
    }

    public String toPersistJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowedHostsCsv", allowedHostsCsv);
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
        m.put("actionWaitMs", actionWaitMs);
        m.put("maxActionsPerPage", maxActionsPerPage);
        m.put("maxPages", maxPages);
        m.put("maxPaginationSamples", maxPaginationSamples);
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
            if (m.containsKey("apiBasePrefix")) setApiBasePrefix(MiniJson.asString(m, "apiBasePrefix", ""));
            if (m.containsKey("routeRootLabel")) setRouteRootLabel(MiniJson.asString(m, "routeRootLabel", "toppage"));
            strictExcelOnly = MiniJson.asBoolean(m, "strictExcelOnly", strictExcelOnly);
            ignoreTrailingSlash = MiniJson.asBoolean(m, "ignoreTrailingSlash", ignoreTrailingSlash);
            normalizeRouteIds = MiniJson.asBoolean(m, "normalizeRouteIds", normalizeRouteIds);
            dangerGuardEnabled = MiniJson.asBoolean(m, "dangerGuardEnabled", dangerGuardEnabled);
            if (m.containsKey("dangerKeywordsCsv")) setDangerKeywordsCsv(MiniJson.asString(m, "dangerKeywordsCsv", DEFAULT_DANGER_KEYWORDS));
            autoFillForms = MiniJson.asBoolean(m, "autoFillForms", autoFillForms);
            deepCrawlMode = MiniJson.asBoolean(m, "deepCrawlMode", deepCrawlMode);
            relaxCspWhileCrawling = MiniJson.asBoolean(m, "relaxCspWhileCrawling", relaxCspWhileCrawling);
            captureJsEndpoints = MiniJson.asBoolean(m, "captureJsEndpoints", captureJsEndpoints);
            setActionWaitMs(asInt(m.get("actionWaitMs"), actionWaitMs));
            setMaxActionsPerPage(asInt(m.get("maxActionsPerPage"), maxActionsPerPage));
            setMaxPages(asInt(m.get("maxPages"), maxPages));
            setMaxPaginationSamples(asInt(m.get("maxPaginationSamples"), maxPaginationSamples));
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
