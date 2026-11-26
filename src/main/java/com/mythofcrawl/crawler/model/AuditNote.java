package com.mythofcrawl.crawler.model;

/**
 * One audit finding ("NOTE") attached to an API call inside an action group,
 * e.g. different parameter sets for the same endpoint, HTTP errors, or API outside the Excel list.
 */
public final class AuditNote {

    public enum Severity {
        ERROR("Lỗi"),
        WARN("Cảnh báo"),
        INFO("Thông tin");

        public final String label;

        Severity(String label) {
            this.label = label;
        }
    }

    public enum Type {
        PARAM_DIFF("Khác tham số"),
        STATUS_DIFF("Khác HTTP status"),
        HTTP_SERVER_ERROR("HTTP 5xx"),
        HTTP_CLIENT_ERROR("HTTP 4xx"),
        AUTH("401/403 Quyền/Phiên"),
        NOT_IN_EXCEL("Ngoài Excel"),
        EXCEL_UPGRADE("Nâng cấp bản Excel"),
        EXTERNAL_REDIRECT("Chuyển hướng ngoài"),
        REDIRECT_DEFERRED("Tạm NOTE chuyển hướng"),
        GRAPHQL_ERROR("Lỗi GraphQL (HTTP 200)"),
        SOFT_AUTH_OR_ERROR("Lỗi ẩn trong JSON 200");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public final Severity severity;
    public final Type type;
    public final int groupId;
    public final String groupPath;
    public final int callIndex;
    public final String method;
    public final String endpoint;
    public final String message;
    public final long timestampMs;

    public AuditNote(
        Severity severity,
        Type type,
        int groupId,
        String groupPath,
        int callIndex,
        String method,
        String endpoint,
        String message,
        long timestampMs
    ) {
        this.severity = severity;
        this.type = type;
        this.groupId = groupId;
        this.groupPath = groupPath;
        this.callIndex = callIndex;
        this.method = method;
        this.endpoint = endpoint;
        this.message = message;
        this.timestampMs = timestampMs;
    }

    public String shortText() {
        return type.label + ": " + message;
    }

    public static Severity worst(Severity a, Severity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
