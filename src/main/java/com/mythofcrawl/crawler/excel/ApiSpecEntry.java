package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.CapturedApiEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One API row loaded from the customer's standard Excel checklist (STT, Method, Path API).
 *
 * Implements User Requirement #3:
 * - Keep the first matched capture by default.
 * - Automatically upgrade the primary capture when another page/button triggers the same API
 *   with strictly more parameters (e.g. 2 params -> 8 params).
 * - When Page A and Page B have disjoint/different parameter keys, record an explicit
 *   parameter difference note and retain secondary variants so nothing is lost.
 */
public final class ApiSpecEntry {

    public enum RecordOutcome {
        FIRST_MATCH,
        UPGRADED_SUPERSET,
        ADDED_DIFF_VARIANT,
        IGNORED_SAME_OR_SUBSET
    }

    public final String stt;
    public final String displayMethod;
    public final Set<String> allowedMethods; // uppercase HTTP methods or "*"
    public final String rawSpecPath;
    public final String normalizedSpecPath;
    public final String extraInfo;
    public final boolean hasWildcards;
    public final int literalSegmentCount;
    public final Pattern pathRegex;

    private CapturedApiEntry primaryCapture;
    private final List<CapturedApiEntry> alternateVariants = new ArrayList<>();
    private final List<String> paramNotes = new ArrayList<>();
    private final Set<String> seenVariantSignatures = new LinkedHashSet<>();
    private final Set<String> jsHints = new LinkedHashSet<>();
    private int totalHits = 0;

    public ApiSpecEntry(
        String stt,
        String displayMethod,
        Set<String> allowedMethods,
        String rawSpecPath,
        String normalizedSpecPath,
        String extraInfo,
        boolean hasWildcards,
        int literalSegmentCount,
        Pattern pathRegex
    ) {
        this.stt = stt;
        this.displayMethod = displayMethod;
        this.allowedMethods = Collections.unmodifiableSet(new LinkedHashSet<>(allowedMethods));
        this.rawSpecPath = rawSpecPath;
        this.normalizedSpecPath = normalizedSpecPath;
        this.extraInfo = extraInfo != null ? extraInfo : "";
        this.hasWildcards = hasWildcards;
        this.literalSegmentCount = literalSegmentCount;
        this.pathRegex = pathRegex;
    }

    public synchronized boolean isMatched() {
        return primaryCapture != null;
    }

    public synchronized CapturedApiEntry getPrimaryCapture() {
        return primaryCapture;
    }

    public synchronized List<CapturedApiEntry> getAlternateVariants() {
        return new ArrayList<>(alternateVariants);
    }

    public synchronized List<CapturedApiEntry> getAllStoredCaptures() {
        List<CapturedApiEntry> all = new ArrayList<>();
        if (primaryCapture != null) {
            all.add(primaryCapture);
        }
        all.addAll(alternateVariants);
        return all;
    }

    public synchronized List<String> getParamNotes() {
        return new ArrayList<>(paramNotes);
    }

    public synchronized String getNotesSummary() {
        return String.join(" | ", paramNotes);
    }

    public synchronized boolean hasParamDiffNote() {
        for (String n : paramNotes) {
            if (n.startsWith("[Khác bộ tham số]")) {
                return true;
            }
        }
        return false;
    }

    public synchronized int getTotalHits() {
        return totalHits;
    }

    public synchronized void addJsHint(String jsUrl) {
        if (jsUrl != null && !jsUrl.isEmpty()) {
            jsHints.add(jsUrl);
        }
    }

    public synchronized List<String> getJsHints() {
        return new ArrayList<>(jsHints);
    }

    public synchronized void clearRuntimeCaptures() {
        primaryCapture = null;
        alternateVariants.clear();
        paramNotes.clear();
        seenVariantSignatures.clear();
        jsHints.clear();
        totalHits = 0;
    }

    public synchronized RecordOutcome recordCapture(CapturedApiEntry incoming) {
        totalHits++;
        if (primaryCapture == null) {
            primaryCapture = incoming;
            seenVariantSignatures.add(sigKey(incoming.paramKeys));
            return RecordOutcome.FIRST_MATCH;
        }

        Set<String> pCur = primaryCapture.paramKeys;
        Set<String> pNew = incoming.paramKeys;

        List<String> onlyNew = diffKeys(pNew, pCur);
        List<String> onlyCur = diffKeys(pCur, pNew);

        if (onlyNew.isEmpty()) {
            // Exact same parameter set or strict subset -> keep the existing/first capture
            return RecordOutcome.IGNORED_SAME_OR_SUBSET;
        }

        if (onlyCur.isEmpty()) {
            // Strictly more complete superset (e.g. 2 params -> 8 params)!
            String oldPath = primaryCapture.actionPath;
            int oldCount = pCur.size();
            primaryCapture = incoming;
            seenVariantSignatures.add(sigKey(pNew));
            addNoteOnce(String.format(
                "[Đầy đủ hơn] Nâng cấp %d -> %d params (từ %s; bản trước ở %s)",
                oldCount, pNew.size(), incoming.actionPath, oldPath));
            return RecordOutcome.UPGRADED_SUPERSET;
        }

        // Distinct/disjoint parameter keys on two different pages/buttons!
        String newSig = sigKey(pNew);
        if (pNew.size() > pCur.size()) {
            CapturedApiEntry oldPrimary = primaryCapture;
            String oldSig = sigKey(oldPrimary.paramKeys);
            if (!seenVariantSignatures.contains(oldSig) || !hasVariantWithSig(oldSig)) {
                alternateVariants.add(oldPrimary);
                seenVariantSignatures.add(oldSig);
            }
            primaryCapture = incoming;
            seenVariantSignatures.add(newSig);
            addNoteOnce(String.format(
                "[Khác bộ tham số] Bản chính %s (%d params) thiếu [%s] từ %s (thêm [%s]); đã giữ cả 2 biến thể",
                incoming.actionPath, pNew.size(), String.join(", ", onlyCur),
                oldPrimary.actionPath, String.join(", ", onlyNew)));
        } else {
            // Keep first / fuller capture as primary, store distinct variant + note
            if (!seenVariantSignatures.contains(newSig)) {
                alternateVariants.add(incoming);
                seenVariantSignatures.add(newSig);
            }
            addNoteOnce(String.format(
                "[Khác bộ tham số] %s (%d params) có thêm [%s] mà bản chính %s (%d params) không có (thiếu [%s])",
                incoming.actionPath, pNew.size(), String.join(", ", onlyNew),
                primaryCapture.actionPath, pCur.size(), String.join(", ", onlyCur)));
        }
        return RecordOutcome.ADDED_DIFF_VARIANT;
    }

    private boolean hasVariantWithSig(String sig) {
        for (CapturedApiEntry v : alternateVariants) {
            if (sigKey(v.paramKeys).equals(sig)) {
                return true;
            }
        }
        return false;
    }

    private void addNoteOnce(String note) {
        if (!paramNotes.contains(note) && paramNotes.size() < 20) {
            paramNotes.add(note);
        }
    }

    private static List<String> diffKeys(Set<String> a, Set<String> b) {
        List<String> diff = new ArrayList<>();
        for (String k : a) {
            if (!b.contains(k)) {
                diff.add(k);
            }
        }
        return diff;
    }

    private static String sigKey(Set<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }
}
