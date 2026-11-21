package com.mythofcrawl.crawler.model;

/**
 * One item staged for pushing (or already pushed) through Burp Extender -> Logger++ -> Elasticsearch.
 * Preserves full UI action group metadata ("toppage > contact > button 'Send'") for 1-to-1 name
 * correlation without altering any HTTP packet bytes on the wire.
 */
public final class ExtenderPushEntry {

    public enum Status {
        QUEUED("Chờ đẩy"),
        SENDING("Đang đẩy..."),
        SENT("Đã đẩy"),
        FAILED("Lỗi gửi"),
        CANCELLED("Đã hủy");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    public final int seq;
    public final long callUid;
    public final long sessionEpoch;
    public final int groupId;
    public final int callIndex;
    public final String groupPath;
    public final String excelStt;
    public final String method;
    public final String endpoint;
    public final String paramsSummary;
    public final int originalStatus;
    public final String notesSummary;
    public final CapturedApiEntry capture;

    private boolean selected = true;
    private volatile Status status = Status.QUEUED;
    private volatile int pushedHttpStatus = 0;
    private volatile String pushedAtText = "-";
    private volatile String errorText = "";

    public ExtenderPushEntry(int seq, ApiCall call) {
        this.seq = seq;
        this.callUid = call.callUid;
        this.sessionEpoch = call.sessionEpoch;
        this.groupId = call.groupId;
        this.callIndex = call.index;
        this.groupPath = call.displayName();
        this.excelStt = call.excelStt;
        this.capture = call.capture;
        this.method = call.capture.method;
        this.endpoint = call.displayPath();
        this.paramsSummary = call.capture.paramCount() + " (" + call.capture.paramSignature() + ")";
        this.originalStatus = call.capture.statusCode;
        this.notesSummary = call.notesSummary();
    }

    public synchronized boolean isSelected() {
        return selected;
    }

    public synchronized void setSelected(boolean selected) {
        this.selected = selected;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getPushedHttpStatus() {
        return pushedHttpStatus;
    }

    public void setPushedHttpStatus(int pushedHttpStatus) {
        this.pushedHttpStatus = pushedHttpStatus;
    }

    public String getPushedAtText() {
        return pushedAtText;
    }

    public void setPushedAtText(String pushedAtText) {
        this.pushedAtText = pushedAtText != null ? pushedAtText : "-";
    }

    public String getErrorText() {
        return errorText;
    }

    public void setErrorText(String errorText) {
        this.errorText = errorText != null ? errorText : "";
    }

    /** Metadata attached strictly as Burp Annotations (Comment), zero change to HTTP wire bytes. */
    public String buildBurpComment() {
        StringBuilder sb = new StringBuilder();
        sb.append("[theMythOfCrawl] ").append(groupPath);
        if (!excelStt.isEmpty()) {
            sb.append(" | STT:").append(excelStt);
        }
        if (!notesSummary.isEmpty()) {
            sb.append(" | NOTE:").append(notesSummary);
        }
        return sb.toString();
    }
}
