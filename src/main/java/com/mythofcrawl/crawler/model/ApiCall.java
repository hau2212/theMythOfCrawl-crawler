package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One API request/response captured inside an {@link ActionGroup}.
 * Its display name is always the group name (e.g. "/ > contact > button 'Send'"),
 * differences are expressed as {@link AuditNote}s.
 */
public final class ApiCall {

    public final int groupId;
    public final String groupPath;
    public final int index;
    public final CapturedApiEntry capture;
    public final String endpointKey;
    public final String graphqlOperation;
    public final String excelStt;
    public final String excelPath;
    public final boolean inExcel;

    private int hits = 1;
    private final List<AuditNote> notes = new ArrayList<>();

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
        this.groupId = groupId;
        this.groupPath = groupPath;
        this.index = index;
        this.capture = capture;
        this.endpointKey = endpointKey;
        this.graphqlOperation = graphqlOperation != null ? graphqlOperation : "";
        this.excelStt = excelStt != null ? excelStt : "";
        this.excelPath = excelPath != null ? excelPath : "";
        this.inExcel = !this.excelStt.isEmpty();
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

    public synchronized int getHits() {
        return hits;
    }

    synchronized void incrementHits() {
        hits++;
    }

    synchronized void addNote(AuditNote note) {
        notes.add(note);
    }

    public synchronized List<AuditNote> getNotes() {
        return new ArrayList<>(notes);
    }

    public synchronized boolean hasNotes() {
        return !notes.isEmpty();
    }

    public synchronized AuditNote.Severity worstSeverity() {
        AuditNote.Severity w = null;
        for (AuditNote n : notes) {
            w = AuditNote.worst(w, n.severity);
        }
        return w;
    }

    public synchronized String notesSummary() {
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
