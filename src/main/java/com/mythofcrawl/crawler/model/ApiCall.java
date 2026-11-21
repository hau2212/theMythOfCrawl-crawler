package com.mythofcrawl.crawler.model;

import burp.api.montoya.core.HighlightColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One API request/response captured inside an {@link ActionGroup}.
 * Its display name is always the group name (e.g. "/ > contact > button 'Send'"),
 * differences are expressed as {@link AuditNote}s, and triage metadata
 * ({@link HighlightColor}, {@link ReviewStatus}, userComment) supports Burp Organizer workflows.
 */
public final class ApiCall {

    private static final AtomicLong FALLBACK_UID_SEQ = new AtomicLong(-1L);

    public final long callUid;
    public final long sessionEpoch;
    public final int groupId;
    public final String groupPath;
    public final int index;
    public final CapturedApiEntry capture;
    public final String endpointKey;
    public final String graphqlOperation;
    public final String excelStt;
    public final String excelPath;
    public final boolean inExcel;
    public final String paramSig;
    public final String mimeType;

    private final AtomicInteger hits = new AtomicInteger(1);
    private final List<AuditNote> notes = new ArrayList<>();
    private final List<ActionStep> actionTrail;
    private volatile AuditNote.Severity cachedWorstSeverity = null;
    private volatile HighlightColor highlightColor = HighlightColor.NONE;
    private volatile ReviewStatus reviewStatus = ReviewStatus.UNREVIEWED;
    private volatile String userComment = "";
    private volatile Runnable onMutated;

    public ApiCall(
        int groupId,
        String groupPath,
        int index,
        CapturedApiEntry capture,
        String endpointKey,
        String graphqlOperation,
        String excelStt,
        String excelPath
    ) {
        this(
            FALLBACK_UID_SEQ.getAndDecrement(),
            0L,
            groupId,
            groupPath,
            index,
            capture,
            endpointKey,
            graphqlOperation,
            excelStt,
            excelPath,
            "",
            null,
            null
        );
    }

    public ApiCall(
        long callUid,
        long sessionEpoch,
        int groupId,
        String groupPath,
        int index,
        CapturedApiEntry capture,
        String endpointKey,
        String graphqlOperation,
        String excelStt,
        String excelPath,
        String mimeType,
        Runnable onMutated
    ) {
        this(
            callUid, sessionEpoch, groupId, groupPath, index, capture, endpointKey,
            graphqlOperation, excelStt, excelPath, mimeType, onMutated, null
        );
    }

    public ApiCall(
        long callUid,
        long sessionEpoch,
        int groupId,
        String groupPath,
        int index,
        CapturedApiEntry capture,
        String endpointKey,
        String graphqlOperation,
        String excelStt,
        String excelPath,
        String mimeType,
        Runnable onMutated,
        List<ActionStep> actionTrail
    ) {
        this.callUid = callUid;
        this.sessionEpoch = sessionEpoch;
        this.groupId = groupId;
        this.groupPath = groupPath;
        this.index = index;
        this.capture = capture;
        this.endpointKey = endpointKey;
        this.graphqlOperation = graphqlOperation != null ? graphqlOperation : "";
        this.excelStt = excelStt != null ? excelStt : "";
        this.excelPath = excelPath != null ? excelPath : "";
        this.inExcel = !this.excelStt.isEmpty();
        this.paramSig = computeParamSig(capture != null ? capture.paramKeys : Collections.emptySet());
        this.mimeType = mimeType != null ? mimeType : "";
        this.onMutated = onMutated;
        this.actionTrail = (actionTrail != null && !actionTrail.isEmpty())
            ? Collections.unmodifiableList(new ArrayList<>(actionTrail))
            : Collections.emptyList();
    }

    public List<ActionStep> getRawActionTrail() {
        return actionTrail;
    }

    public List<ActionStep> getActionTrail() {
        if (!actionTrail.isEmpty()) {
            return actionTrail;
        }
        String fallbackUrl = capture != null ? capture.fullUrl : "";
        return ActionStep.fallbackFromActionPath(groupPath, fallbackUrl);
    }

    public static String computeParamSig(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    /** API name = action group name (user requirement). */
    public String displayName() {
        return groupPath;
    }

    /** Path shown in tables, enriched with GraphQL operation name when present. */
    public String displayPath() {
        String p = capture.requestPathWithQuery;
        return graphqlOperation.isEmpty() ? p : (p + "  [op: " + graphqlOperation + "]");
    }

    public int getHits() {
        return hits.get();
    }

    int incrementHits() {
        return hits.incrementAndGet();
    }

    void addNote(AuditNote note) {
        if (note == null) {
            return;
        }
        synchronized (notes) {
            notes.add(note);
            cachedWorstSeverity = AuditNote.worst(cachedWorstSeverity, note.severity);
        }
    }

    public List<AuditNote> getNotes() {
        synchronized (notes) {
            return new ArrayList<>(notes);
        }
    }

    public int noteCount() {
        synchronized (notes) {
            return notes.size();
        }
    }

    public boolean hasNotes() {
        synchronized (notes) {
            return !notes.isEmpty();
        }
    }

    public AuditNote.Severity worstSeverity() {
        return cachedWorstSeverity;
    }

    public String notesSummary() {
        synchronized (notes) {
            if (notes.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (AuditNote n : notes) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(n.shortText());
            }
            return sb.toString();
        }
    }

    public HighlightColor getHighlightColor() {
        return highlightColor;
    }

    public void setHighlightColor(HighlightColor color) {
        this.highlightColor = color != null ? color : HighlightColor.NONE;
        fireMutated();
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(ReviewStatus status) {
        this.reviewStatus = status != null ? status : ReviewStatus.UNREVIEWED;
        fireMutated();
    }

    public String getUserComment() {
        return userComment;
    }

    public void setUserComment(String comment) {
        this.userComment = comment != null ? comment.trim() : "";
        fireMutated();
    }

    void setOnMutated(Runnable onMutated) {
        this.onMutated = onMutated;
    }

    private void fireMutated() {
        Runnable cb = this.onMutated;
        if (cb != null) {
            cb.run();
        }
    }
}
