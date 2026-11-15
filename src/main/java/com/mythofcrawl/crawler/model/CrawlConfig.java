package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime configuration for the Firefox + Excel Burp Auto-Crawler.
 */
public final class CrawlConfig {

    public static final String DEFAULT_DANGER_KEYWORDS =
        "logout,log out,sign out,signout,đăng xuất,thoát," +
        "delete,remove,destroy,drop,xóa,xoá,huỷ tài khoản,hủy tài khoản,cancel account,deactivate," +
        "pay,checkout,thanh toán,purchase,đặt hàng";

    private volatile String allowedHostsCsv = "";
    private volatile String apiBasePrefix = "";
    private volatile boolean strictExcelOnly = true;
    private volatile boolean ignoreTrailingSlash = true;
    private volatile boolean normalizeRouteIds = true;
    private volatile boolean dangerGuardEnabled = true;
    private volatile String dangerKeywordsCsv = DEFAULT_DANGER_KEYWORDS;
    private volatile boolean autoFillForms = true;
    private volatile boolean relaxCspWhileCrawling = true;
    private volatile boolean captureJsEndpoints = true;
    private volatile int actionWaitMs = 550;
    private volatile int maxActionsPerPage = 180;
    private volatile int maxPages = 350;
    private volatile int maxPaginationSamples = 3;

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
                    // Strip scheme/port if user pasted a full URL
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
                String suffix = pattern.substring(1); // ".example.com"
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
        this.maxActionsPerPage = Math.max(5, Math.min(2000, maxActionsPerPage));
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = Math.max(1, Math.min(5000, maxPages));
    }

    public int getMaxPaginationSamples() {
        return maxPaginationSamples;
    }

    public void setMaxPaginationSamples(int maxPaginationSamples) {
        this.maxPaginationSamples = Math.max(1, Math.min(50, maxPaginationSamples));
    }
}
