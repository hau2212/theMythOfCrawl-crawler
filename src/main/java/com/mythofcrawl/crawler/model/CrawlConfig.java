package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private volatile String allowedHostsCsv = "";
    private volatile String seedUrl = "";
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
            } else if (pattern.startsWith("*") && pattern.length() > 1) {
                // Support "*example.com" shorthand the same as "*.example.com"
                String root = pattern.substring(1);
                if (root.startsWith(".")) {
                    root = root.substring(1);
                }
                if (h.equals(root) || h.endsWith("." + root)) {
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
        this.seedUrl = seedUrl != null ? seedUrl.trim() : "";
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
