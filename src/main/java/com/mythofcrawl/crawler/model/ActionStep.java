package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable UI interaction step on a web page leading up to an API call.
 * Captures CSS/Shadow-DOM/iframe selectors, human label, filled form values,
 * and route URL so the browser agent can visually spotlight & replay the trail step-by-step.
 */
public final class ActionStep {

    public final int stepIndex;
    public final String pageUrl;
    public final String framePath;
    public final List<String> shadowSelectors;
    public final String cssSelector;
    public final String tag;
    public final String role;
    public final String kind;
    public final String label;
    public final String inputValue;
    public final String actionPath;
    public final List<Map<String, String>> filledFields;

    public ActionStep(
        int stepIndex,
        String pageUrl,
        String framePath,
        List<String> shadowSelectors,
        String cssSelector,
        String tag,
        String role,
        String kind,
        String label,
        String inputValue,
        String actionPath,
        List<Map<String, String>> filledFields
    ) {
        this.stepIndex = stepIndex;
        this.pageUrl = pageUrl != null ? pageUrl : "";
        this.framePath = framePath != null ? framePath : "";
        this.shadowSelectors = shadowSelectors != null
            ? Collections.unmodifiableList(new ArrayList<>(shadowSelectors))
            : Collections.emptyList();
        this.cssSelector = cssSelector != null ? cssSelector : "";
        this.tag = tag != null ? tag : "";
        this.role = role != null ? role : "";
        this.kind = kind != null ? kind : "";
        this.label = label != null ? label : "";
        this.inputValue = inputValue != null ? inputValue : "";
        this.actionPath = actionPath != null ? actionPath : "";
        if (filledFields != null && !filledFields.isEmpty()) {
            List<Map<String, String>> copy = new ArrayList<>(filledFields.size());
            for (Map<String, String> f : filledFields) {
                if (f != null) {
                    copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(f)));
                }
            }
            this.filledFields = Collections.unmodifiableList(copy);
        } else {
            this.filledFields = Collections.emptyList();
        }
    }

    public ActionStep withStepIndex(int newIndex) {
        return new ActionStep(
            newIndex, pageUrl, framePath, shadowSelectors, cssSelector,
            tag, role, kind, label, inputValue, actionPath, filledFields
        );
    }

    public boolean hasDomLocator() {
        return !cssSelector.isEmpty() || !shadowSelectors.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepIndex", stepIndex);
        m.put("pageUrl", pageUrl);
        m.put("framePath", framePath);
        m.put("shadowSelectors", new ArrayList<>(shadowSelectors));
        m.put("cssSelector", cssSelector);
        m.put("tag", tag);
        m.put("role", role);
        m.put("kind", kind);
        m.put("label", label);
        m.put("inputValue", inputValue);
        m.put("actionPath", actionPath);
        List<Map<String, String>> fieldsOut = new ArrayList<>(filledFields.size());
        for (Map<String, String> f : filledFields) {
            fieldsOut.add(new LinkedHashMap<>(f));
        }
        m.put("filledFields", fieldsOut);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ActionStep fromStepLocatorMap(
        int stepIndex,
        String pageUrl,
        String kind,
        String label,
        String actionPath,
        Map<?, ?> locator
    ) {
        String framePath = "";
        List<String> shadows = new ArrayList<>();
        String css = "";
        String tag = "";
        String role = "";
        String inputVal = "";
        List<Map<String, String>> fields = new ArrayList<>();

        if (locator != null) {
            if (locator.get("framePath") != null) framePath = String.valueOf(locator.get("framePath"));
            if (locator.get("cssSelector") != null) css = String.valueOf(locator.get("cssSelector"));
            if (locator.get("tag") != null) tag = String.valueOf(locator.get("tag"));
            if (locator.get("role") != null) role = String.valueOf(locator.get("role"));
            if (locator.get("inputValue") != null) inputVal = String.valueOf(locator.get("inputValue"));

            Object sh = locator.get("shadowSelectors");
            if (sh instanceof List<?>) {
                for (Object item : (List<?>) sh) {
                    if (item != null && !String.valueOf(item).trim().isEmpty()) {
                        shadows.add(String.valueOf(item).trim());
                    }
                }
            }
            Object ff = locator.get("filledFields");
            if (ff instanceof List<?>) {
                for (Object item : (List<?>) ff) {
                    if (item instanceof Map<?, ?>) {
                        Map<?, ?> fm = (Map<?, ?>) item;
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("selector", fm.get("selector") != null ? String.valueOf(fm.get("selector")) : "");
                        entry.put("label", fm.get("label") != null ? String.valueOf(fm.get("label")) : "");
                        entry.put("value", fm.get("value") != null ? String.valueOf(fm.get("value")) : "");
                        fields.add(entry);
                    }
                }
            }
        }
        return new ActionStep(
            stepIndex, pageUrl, framePath, shadows, css,
            tag, role, kind, label, inputVal, actionPath, fields
        );
    }

    /**
     * Synthesizes a clean fallback ActionStep list from an actionPath string and URL when
     * no DOM stepLocator was recorded (e.g. passive/manual capture or legacy session).
     */
    public static List<ActionStep> fallbackFromActionPath(String actionPath, String pageUrl) {
        String safeUrl = pageUrl != null ? pageUrl : "";
        String safePath = (actionPath != null && !actionPath.trim().isEmpty()) ? actionPath.trim() : "/ > page load";
        List<ActionStep> steps = new ArrayList<>();
        List<String> parts = PathNamer.splitHierarchyPath(safePath);

        steps.add(new ActionStep(
            0, safeUrl, "", Collections.emptyList(), "", "", "",
            "page load", safeUrl.isEmpty() ? "Trang gốc" : safeUrl, "", safePath, Collections.emptyList()
        ));

        for (String seg : parts) {
            String lower = seg.toLowerCase();
            if (seg.equals("/") || lower.equals("toppage") || lower.equals("page load")) {
                continue;
            }
            int q1 = seg.indexOf('\'');
            int q2 = seg.lastIndexOf('\'');
            if (q1 < 0) {
                q1 = seg.indexOf('"');
                q2 = seg.lastIndexOf('"');
            }
            String kind = seg;
            String label = seg;
            if (q1 > 0 && q2 > q1) {
                kind = seg.substring(0, q1).trim();
                label = seg.substring(q1 + 1, q2).trim();
            } else {
                // Route segment like "contact" or container without quotes
                continue;
            }
            steps.add(new ActionStep(
                steps.size(), safeUrl, "", Collections.emptyList(), "", "", "",
                kind, label, "", safePath, Collections.emptyList()
            ));
        }
        return steps;
    }
}
