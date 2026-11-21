package com.mythofcrawl.crawler.model;

/**
 * Triage review status assigned by the pentester to a captured API call
 * (aligned with Burp Organizer review workflow).
 */
public enum ReviewStatus {
    UNREVIEWED("Chưa kiểm"),
    INTERESTING("Đáng chú ý"),
    TESTED("Đã test"),
    FALSE_POSITIVE("Bỏ qua (FP)");

    public final String label;

    ReviewStatus(String label) {
        this.label = label;
    }

    public static ReviewStatus fromString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return UNREVIEWED;
        }
        String t = raw.trim();
        for (ReviewStatus s : values()) {
            if (s.name().equalsIgnoreCase(t) || s.label.equalsIgnoreCase(t)) {
                return s;
            }
        }
        return UNREVIEWED;
    }
}
