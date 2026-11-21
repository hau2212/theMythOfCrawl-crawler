package com.mythofcrawl.crawler.model;

import java.util.Locale;

/**
 * Structured event log record for the Burp 2026 Dashboard Event Log panel.
 */
public final class EventLogEntry {

    public enum Level {
        CRITICAL("Critical"),
        ERROR("Error"),
        INFO("Info"),
        DEBUG("Debug");

        public final String label;

        Level(String label) {
            this.label = label;
        }
    }

    public final long id;
    public final long timestampMs;
    public final Level level;
    public final String source;
    public final String message;

    public EventLogEntry(long id, long timestampMs, Level level, String source, String message) {
        this.id = id;
        this.timestampMs = timestampMs;
        this.level = level != null ? level : Level.INFO;
        this.source = (source != null && !source.trim().isEmpty()) ? source.trim() : "Suite";
        this.message = message != null ? message : "";
    }

    public static Level inferLevel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Level.INFO;
        }
        String l = raw.toLowerCase(Locale.ROOT);
        if (l.contains("critical") || l.contains("outofmemory") || l.contains("failed to initialize")) {
            return Level.CRITICAL;
        }
        if (l.contains("lỗi") || l.contains("error") || l.contains("cảnh báo")
            || l.contains("tạm dừng") || l.contains("captcha") || l.contains("chặn")) {
            return Level.ERROR;
        }
        if (l.contains("[debug]") || l.contains("ignored")) {
            return Level.DEBUG;
        }
        return Level.INFO;
    }

    public static String inferSource(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "Suite";
        }
        String l = raw.toLowerCase(Locale.ROOT);
        if (l.contains("[cdp") || l.contains("cdp sidecar")) return "CDP";
        if (l.contains("[agent]") || l.contains("nhóm thao tác") || l.contains("captcha")) return "Agent";
        if (l.contains("excel") || l.contains("[stt ")) return "Excel";
        if (l.contains("extender") || l.contains("logger++") || l.contains("elastic")) return "Push";
        if (l.contains("[replay") || l.contains("tìm vị trí") || l.contains("[redirect")) return "Replay";
        if (l.contains("proxy") || l.contains("seed") || l.contains("[extra]")) return "Proxy";
        return "Suite";
    }
}
