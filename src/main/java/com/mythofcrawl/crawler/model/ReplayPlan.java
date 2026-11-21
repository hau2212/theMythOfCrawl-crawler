package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active visual replay & UI spotlight locator plan shared between Burp UI,
 * {@link CrawlState}, {@code LocalControlServer}, and {@code crawler-agent.js}.
 */
public final class ReplayPlan {

    public final String planId;
    public final String targetTitle;
    public final String targetEndpoint;
    public final String startUrl;
    public final List<ActionStep> steps;
    public final long createdAtMs;

    private volatile boolean autoPlay;
    private volatile int currentIndex;
    private volatile boolean active;

    public ReplayPlan(
        String planId,
        String targetTitle,
        String targetEndpoint,
        String startUrl,
        List<ActionStep> steps,
        boolean autoPlay
    ) {
        this.planId = planId != null ? planId : "replay-1";
        this.targetTitle = targetTitle != null ? targetTitle : "";
        this.targetEndpoint = targetEndpoint != null ? targetEndpoint : "";
        this.startUrl = startUrl != null ? startUrl : "";
        this.steps = steps != null
            ? Collections.unmodifiableList(new ArrayList<>(steps))
            : Collections.emptyList();
        this.autoPlay = autoPlay;
        this.currentIndex = 0;
        this.active = !this.steps.isEmpty();
        this.createdAtMs = System.currentTimeMillis();
    }

    public String getPlanId() {
        return planId;
    }

    public String getTargetTitle() {
        return targetTitle;
    }

    public String getTargetEndpoint() {
        return targetEndpoint;
    }

    public String getStartUrl() {
        return startUrl;
    }

    public List<ActionStep> getSteps() {
        return steps;
    }

    public boolean isAutoPlay() {
        return autoPlay;
    }

    public void setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        if (steps.isEmpty()) {
            this.currentIndex = 0;
        } else {
            this.currentIndex = Math.max(0, Math.min(steps.size() - 1, currentIndex));
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", planId);
        m.put("targetTitle", targetTitle);
        m.put("targetEndpoint", targetEndpoint);
        m.put("startUrl", startUrl);
        m.put("autoPlay", autoPlay);
        m.put("currentIndex", currentIndex);
        m.put("active", active);
        List<Map<String, Object>> stepMaps = new ArrayList<>(steps.size());
        for (ActionStep s : steps) {
            stepMaps.add(s.toMap());
        }
        m.put("steps", stepMaps);
        return m;
    }
}
