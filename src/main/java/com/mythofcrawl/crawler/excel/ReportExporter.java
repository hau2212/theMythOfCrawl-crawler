package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.MiniJson;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports Excel coverage, Action Groups + per-API Audit NOTEs, UI action paths,
 * parameter diffs, and `theMythOfCrawl.py` handoff target bundles to CSV (formula-safe UTF-8 BOM),
 * JSON, and Markdown reports.
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    public static void exportCsv(File file, ExcelSpecMatcher matcher, List<CapturedApiEntry> extras) throws Exception {
        exportCsv(file, matcher, extras, null);
    }

    public static void exportCsv(
        File file,
        ExcelSpecMatcher matcher,
        List<CapturedApiEntry> extras,
        ActionGroupStore actionGroups
    ) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("Section,Group / STT,Status / Severity,Method,Path / Endpoint,API Name (Action Path),Matched URL,HTTP Status,Params Count,Params Keys,Hits / Variants,NOTE,JS Hints\n");

        if (actionGroups != null && actionGroups.groupCount() > 0) {
            for (ActionGroup g : actionGroups.snapshotGroups()) {
                for (ApiCall call : g.getCalls()) {
                    CapturedApiEntry cap = call.capture;
                    String sev = call.worstSeverity() != null ? call.worstSeverity().label : "OK";
                    String excelTag = call.inExcel ? ("STT " + call.excelStt) : "Ngoài Excel";
                    sb.append(csvCell("GROUP_API")).append(',')
                      .append(csvCell("G" + g.id + " (" + excelTag + ")")).append(',')
                      .append(csvCell(sev)).append(',')
                      .append(csvCell(cap.method)).append(',')
                      .append(csvCell(call.displayPath())).append(',')
                      .append(csvCell(call.displayName())).append(',')
                      .append(csvCell(cap.fullUrl)).append(',')
                      .append(csvCell(String.valueOf(cap.statusCode))).append(',')
                      .append(csvCell(String.valueOf(cap.paramCount()))).append(',')
                      .append(csvCell(cap.paramSignature())).append(',')
                      .append(csvCell(String.valueOf(call.getHits()))).append(',')
                      .append(csvCell(call.notesSummary())).append(',')
                      .append(csvCell("")).append('\n');
                }
            }
        }

        if (matcher != null) {
            for (ApiSpecEntry entry : matcher.getEntries()) {
                CapturedApiEntry primary = entry.getPrimaryCapture();
                String status = primary != null ? "MATCHED" : "MISSING";
                String actionPath = primary != null ? primary.actionPath : "";
                String fullUrl = primary != null ? primary.fullUrl : "";
                String httpStatus = primary != null ? String.valueOf(primary.statusCode) : "";
                String paramCount = primary != null ? String.valueOf(primary.paramCount()) : "0";
                String paramSig = primary != null ? primary.paramSignature() : "";
                String variantsCount = String.valueOf(entry.getAllStoredCaptures().size());
                String notes = entry.getNotesSummary();
                String jsHints = String.join("; ", entry.getJsHints());

                sb.append(csvCell("EXCEL")).append(',')
                  .append(csvCell(entry.stt)).append(',')
                  .append(csvCell(status)).append(',')
                  .append(csvCell(entry.displayMethod)).append(',')
                  .append(csvCell(entry.rawSpecPath)).append(',')
                  .append(csvCell(actionPath)).append(',')
                  .append(csvCell(fullUrl)).append(',')
                  .append(csvCell(httpStatus)).append(',')
                  .append(csvCell(paramCount)).append(',')
                  .append(csvCell(paramSig)).append(',')
                  .append(csvCell(variantsCount)).append(',')
                  .append(csvCell(notes)).append(',')
                  .append(csvCell(jsHints)).append('\n');

                int altIdx = 1;
                for (CapturedApiEntry alt : entry.getAlternateVariants()) {
                    sb.append(csvCell("EXCEL_VARIANT")).append(',')
                      .append(csvCell(entry.stt + " (Variant #" + altIdx + ")")).append(',')
                      .append(csvCell("VARIANT")).append(',')
                      .append(csvCell(alt.method)).append(',')
                      .append(csvCell(entry.rawSpecPath)).append(',')
                      .append(csvCell(alt.actionPath)).append(',')
                      .append(csvCell(alt.fullUrl)).append(',')
                      .append(csvCell(String.valueOf(alt.statusCode))).append(',')
                      .append(csvCell(String.valueOf(alt.paramCount()))).append(',')
                      .append(csvCell(alt.paramSignature())).append(',')
                      .append(csvCell(variantsCount)).append(',')
                      .append(csvCell("Secondary distinct param variant")).append(',')
                      .append(csvCell("")).append('\n');
                    altIdx++;
                }
            }
        }

        if (extras != null && !extras.isEmpty()) {
            for (CapturedApiEntry ex : extras) {
                sb.append(csvCell("EXTRA")).append(',')
                  .append(csvCell("EXTRA")).append(',')
                  .append(csvCell("OUTSIDE_EXCEL")).append(',')
                  .append(csvCell(ex.method)).append(',')
                  .append(csvCell(ex.requestPathWithQuery)).append(',')
                  .append(csvCell(ex.actionPath)).append(',')
                  .append(csvCell(ex.fullUrl)).append(',')
                  .append(csvCell(String.valueOf(ex.statusCode))).append(',')
                  .append(csvCell(String.valueOf(ex.paramCount()))).append(',')
                  .append(csvCell(ex.paramSignature())).append(',')
                  .append(csvCell("1")).append(',')
                  .append(csvCell("")).append(',')
                  .append(csvCell("")).append('\n');
            }
        }

        writeUtf8(file, sb.toString());
    }

    public static void exportJson(File file, ExcelSpecMatcher matcher, List<CapturedApiEntry> extras) throws Exception {
        exportJson(file, matcher, extras, null);
    }

    public static void exportJson(
        File file,
        ExcelSpecMatcher matcher,
        List<CapturedApiEntry> extras,
        ActionGroupStore actionGroups
    ) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("excelSource", matcher != null ? matcher.getSourceFileName() : "");

        ExcelSpecMatcher.CoverageStats stats = matcher != null
            ? matcher.getCoverageStats()
            : new ExcelSpecMatcher.CoverageStats(0, 0, 0, 0);
        Map<String, Object> statsMap = new LinkedHashMap<>();
        statsMap.put("totalExcel", stats.total);
        statsMap.put("matchedExcel", stats.matched);
        statsMap.put("missingExcel", stats.missing);
        statsMap.put("withParamDiffNotes", stats.withNotes);
        if (actionGroups != null) {
            statsMap.put("actionGroups", actionGroups.groupCount());
            statsMap.put("totalGroupedCalls", actionGroups.totalCalls());
            statsMap.put("auditErrorNotes", actionGroups.noteCount(AuditNote.Severity.ERROR));
            statsMap.put("auditWarnNotes", actionGroups.noteCount(AuditNote.Severity.WARN));
            statsMap.put("auditInfoNotes", actionGroups.noteCount(AuditNote.Severity.INFO));
        }
        root.put("stats", statsMap);

        if (actionGroups != null) {
            List<Map<String, Object>> groupList = new ArrayList<>();
            for (ActionGroup g : actionGroups.snapshotGroups()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("groupId", g.id);
                gm.put("name", g.actionPath);
                gm.put("kind", g.kind);
                gm.put("label", g.label);
                gm.put("pageUrl", g.pageUrl);
                gm.put("triggerCount", g.getTriggerCount());
                gm.put("apiCount", g.callCount());
                List<Map<String, Object>> callList = new ArrayList<>();
                for (ApiCall c : g.getCalls()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("callUid", c.callUid);
                    cm.put("index", c.index);
                    cm.put("apiName", c.displayName());
                    cm.put("method", c.capture.method);
                    cm.put("path", c.displayPath());
                    cm.put("fullUrl", c.capture.fullUrl);
                    cm.put("statusCode", c.capture.statusCode);
                    cm.put("hits", c.getHits());
                    cm.put("excelStt", c.excelStt);
                    cm.put("paramKeys", new ArrayList<>(c.capture.paramKeys));
                    cm.put("highlightColor", c.getHighlightColor().name());
                    cm.put("reviewStatus", c.getReviewStatus().name());
                    cm.put("userComment", c.getUserComment());
                    cm.put("noteSummary", c.notesSummary());
                    callList.add(cm);
                }
                gm.put("apis", callList);
                groupList.add(gm);
            }
            root.put("actionGroups", groupList);
        }

        List<Map<String, Object>> excelList = new ArrayList<>();
        if (matcher != null) {
            for (ApiSpecEntry entry : matcher.getEntries()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stt", entry.stt);
                item.put("excelMethod", entry.displayMethod);
                item.put("excelPath", entry.rawSpecPath);
                item.put("status", entry.isMatched() ? "MATCHED" : "MISSING");
                item.put("totalHits", entry.getTotalHits());
                item.put("paramNotes", entry.getParamNotes());
                item.put("jsHints", entry.getJsHints());

                List<Map<String, Object>> captures = new ArrayList<>();
                for (CapturedApiEntry cap : entry.getAllStoredCaptures()) {
                    Map<String, Object> cMap = new LinkedHashMap<>();
                    cMap.put("isPrimary", cap == entry.getPrimaryCapture());
                    cMap.put("actionPath", cap.actionPath);
                    cMap.put("method", cap.method);
                    cMap.put("fullUrl", cap.fullUrl);
                    cMap.put("statusCode", cap.statusCode);
                    cMap.put("paramCount", cap.paramCount());
                    cMap.put("paramKeys", new ArrayList<>(cap.paramKeys));
                    captures.add(cMap);
                }
                item.put("captures", captures);
                excelList.add(item);
            }
        }
        root.put("excelEntries", excelList);

        List<Map<String, Object>> extraList = new ArrayList<>();
        if (extras != null) {
            for (CapturedApiEntry ex : extras) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("actionPath", ex.actionPath);
                m.put("method", ex.method);
                m.put("fullUrl", ex.fullUrl);
                m.put("statusCode", ex.statusCode);
                m.put("paramKeys", new ArrayList<>(ex.paramKeys));
                extraList.add(m);
            }
        }
        root.put("extraCaptures", extraList);

        writeUtf8(file, MiniJson.stringify(root));
    }

    /**
     * Exports captured APIs and Excel specs in a clean JSON format directly consumable
     * by `theMythOfCrawl.py` (`tmoc_core.TargetList.from_json` schema + raw Base64 requests
     * for multi-role BOLA/IDOR replay).
     */
    public static void exportForTheMythOfCrawlJson(
        File file,
        ExcelSpecMatcher matcher,
        ActionGroupStore actionGroups
    ) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("extension", "theMythOfCrawl");
        root.put("version", 1);
        root.put("source", matcher != null && !matcher.getSourceFileName().isEmpty()
            ? matcher.getSourceFileName() : "theMythOfCrawl-crawler");
        root.put("sheet", "CrawlerExport");
        root.put("headers", List.of("STT", "PROTOCOL", "PATH", "POSITION"));

        List<Map<String, Object>> targetEntries = new ArrayList<>();
        List<Map<String, Object>> capturedRequests = new ArrayList<>();

        int rowNo = 1;
        if (actionGroups != null) {
            for (ActionGroup g : actionGroups.snapshotGroups()) {
                for (ApiCall call : g.getCalls()) {
                    CapturedApiEntry cap = call.capture;
                    if (cap == null || "WS_SEND".equals(cap.method) || "WS_RECV".equals(cap.method)) {
                        continue;
                    }
                    String sttVal = call.inExcel ? call.excelStt : String.valueOf(rowNo);
                    String pathOnly = cap.requestPathWithQuery != null ? cap.requestPathWithQuery : "/";

                    Map<String, Object> te = new LinkedHashMap<>();
                    te.put("stt", sttVal);
                    te.put("methods", Collections.singletonList(cap.method));
                    te.put("path", pathOnly);
                    te.put("position", call.displayName());
                    te.put("row", rowNo);
                    targetEntries.add(te);

                    Map<String, Object> cr = new LinkedHashMap<>();
                    cr.put("stt", sttVal);
                    cr.put("callUid", call.callUid);
                    cr.put("method", cap.method);
                    cr.put("url", cap.fullUrl);
                    cr.put("host", cap.host);
                    cr.put("port", cap.port);
                    cr.put("https", cap.https);
                    cr.put("path", pathOnly);
                    cr.put("position", call.displayName());
                    cr.put("status", cap.statusCode);
                    cr.put("paramKeys", new ArrayList<>(cap.paramKeys));
                    cr.put("rawRequestBase64", cap.requestBytes != null
                        ? Base64.getEncoder().encodeToString(cap.requestBytes) : "");
                    capturedRequests.add(cr);
                    rowNo++;
                }
            }
        }
        if (targetEntries.isEmpty() && matcher != null) {
            for (ApiSpecEntry spec : matcher.getEntries()) {
                Map<String, Object> te = new LinkedHashMap<>();
                te.put("stt", spec.stt);
                te.put("methods", new ArrayList<>(spec.allowedMethods));
                te.put("path", spec.rawSpecPath);
                CapturedApiEntry primary = spec.getPrimaryCapture();
                te.put("position", primary != null ? primary.actionPath : "");
                te.put("row", rowNo++);
                targetEntries.add(te);
            }
        }

        root.put("entries", targetEntries);
        root.put("capturedRequests", capturedRequests);
        writeUtf8(file, MiniJson.stringify(root));
    }

    public static void exportMarkdown(File file, ExcelSpecMatcher matcher, List<CapturedApiEntry> extras) throws Exception {
        exportMarkdown(file, matcher, extras, null);
    }

    public static void exportMarkdown(
        File file,
        ExcelSpecMatcher matcher,
        List<CapturedApiEntry> extras,
        ActionGroupStore actionGroups
    ) throws Exception {
        StringBuilder sb = new StringBuilder();
        ExcelSpecMatcher.CoverageStats stats = matcher != null
            ? matcher.getCoverageStats()
            : new ExcelSpecMatcher.CoverageStats(0, 0, 0, 0);

        sb.append("# theMythOfCrawl - API Coverage, Action Groups & Audit NOTEs Report\n\n");
        sb.append("- **Excel Source:** ").append(matcher != null ? matcher.getSourceFileName() : "(none)").append('\n');
        sb.append("- **Exported At:** ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        sb.append("- **Excel Matched:** ").append(stats.matched).append(" / ").append(stats.total)
          .append(" (Missing: ").append(stats.missing).append(", Param Diff Notes: ").append(stats.withNotes).append(")\n");
        if (actionGroups != null) {
            sb.append("- **Action Groups:** ").append(actionGroups.groupCount())
              .append(" | **API Calls:** ").append(actionGroups.totalCalls())
              .append(" | **NOTE (Lỗi/Cảnh báo/Info):** ")
              .append(actionGroups.noteCount(AuditNote.Severity.ERROR)).append(" / ")
              .append(actionGroups.noteCount(AuditNote.Severity.WARN)).append(" / ")
              .append(actionGroups.noteCount(AuditNote.Severity.INFO)).append('\n');
        }
        sb.append('\n');

        if (actionGroups != null && actionGroups.groupCount() > 0) {
            sb.append("## 1. Nhóm Chức Năng & Các API Kích Hoạt\n\n");
            for (ActionGroup g : actionGroups.snapshotGroups()) {
                if (g.callCount() == 0) {
                    continue;
                }
                sb.append("### [Nhóm #").append(g.id).append("] ").append(g.actionPath)
                  .append(" (").append(g.callCount()).append(" API)\n\n");
                sb.append("| # | Tên API | Method | Endpoint | HTTP | Params | Excel | NOTE |\n");
                sb.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                for (ApiCall c : g.getCalls()) {
                    sb.append("| ").append(c.index)
                      .append(" | ").append(escMd(c.displayName()))
                      .append(" | `").append(c.capture.method).append('`')
                      .append(" | `").append(escMd(c.displayPath())).append('`')
                      .append(" | ").append(c.capture.statusCode)
                      .append(" | ").append(c.capture.paramCount())
                      .append(" | ").append(c.inExcel ? ("STT " + c.excelStt) : "Ngoài Excel")
                      .append(" | ").append(escMd(c.notesSummary()))
                      .append(" |\n");
                }
                sb.append('\n');
            }
        }

        if (actionGroups != null && !actionGroups.snapshotNotes().isEmpty()) {
            sb.append("## 2. Tổng hợp Bảng NOTE Audit\n\n");
            sb.append("| Mức độ | Loại NOTE | Nhóm Thao Tác | Method | Endpoint | Chi tiết |\n");
            sb.append("| --- | --- | --- | --- | --- | --- |\n");
            for (AuditNote n : actionGroups.snapshotNotes()) {
                sb.append("| **").append(n.severity.label).append("**")
                  .append(" | ").append(n.type.label)
                  .append(" | ").append(escMd(n.groupPath))
                  .append(" | `").append(n.method).append('`')
                  .append(" | `").append(escMd(n.endpoint)).append('`')
                  .append(" | ").append(escMd(n.message))
                  .append(" |\n");
            }
            sb.append('\n');
        }

        if (matcher != null && matcher.isLoaded()) {
            List<ApiSpecEntry> matched = new ArrayList<>();
            List<ApiSpecEntry> missing = new ArrayList<>();
            for (ApiSpecEntry entry : matcher.getEntries()) {
                if (entry.isMatched()) {
                    matched.add(entry);
                } else {
                    missing.add(entry);
                }
            }
            if (!matched.isEmpty()) {
                sb.append("## 3. API trong Excel đã khớp (`MATCHED`: ").append(matched.size()).append(")\n\n");
                sb.append("| STT | Method | Path API | Action Path | Params | HTTP | NOTE |\n");
                sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
                for (ApiSpecEntry m : matched) {
                    CapturedApiEntry p = m.getPrimaryCapture();
                    sb.append("| ").append(escMd(m.stt))
                      .append(" | `").append(m.displayMethod).append('`')
                      .append(" | `").append(escMd(m.rawSpecPath)).append('`')
                      .append(" | ").append(escMd(p.actionPath))
                      .append(" | ").append(p.paramCount()).append(" (").append(escMd(p.paramSignature())).append(')')
                      .append(" | ").append(p.statusCode)
                      .append(" | ").append(escMd(m.getNotesSummary()))
                      .append(" |\n");
                    int altIdx = 1;
                    for (CapturedApiEntry alt : m.getAlternateVariants()) {
                        sb.append("| ").append(escMd(m.stt)).append(" (var #").append(altIdx++).append(')')
                          .append(" | `").append(alt.method).append('`')
                          .append(" | `").append(escMd(alt.requestPathWithQuery)).append('`')
                          .append(" | ").append(escMd(alt.actionPath))
                          .append(" | ").append(alt.paramCount()).append(" (").append(escMd(alt.paramSignature())).append(')')
                          .append(" | ").append(alt.statusCode)
                          .append(" | Khác tham số")
                          .append(" |\n");
                    }
                }
                sb.append('\n');
            }
            if (!missing.isEmpty()) {
                sb.append("## 4. API trong Excel chưa gọi được (`MISSING`: ").append(missing.size()).append(")\n\n");
                sb.append("| STT | Method | Path API | JS Hints |\n| --- | --- | --- | --- |\n");
                for (ApiSpecEntry m : missing) {
                    sb.append("| ").append(escMd(m.stt))
                      .append(" | `").append(m.displayMethod).append('`')
                      .append(" | `").append(escMd(m.rawSpecPath)).append('`')
                      .append(" | ").append(escMd(String.join(", ", m.getJsHints()))).append(" |\n");
                }
                sb.append('\n');
            }
        }

        if (extras != null && !extras.isEmpty()) {
            sb.append("## 5. API ngoài Excel (`").append(extras.size()).append("`)\n\n");
            for (CapturedApiEntry ex : extras) {
                sb.append("- `").append(ex.method).append(' ').append(ex.fullUrl)
                  .append("` từ **").append(ex.actionPath).append("**\n");
            }
        }

        writeUtf8(file, sb.toString());
    }

    private static String escMd(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "\"\"";
        }
        String safe = v;
        if (!"-".equals(safe) && !safe.isEmpty()) {
            char first = safe.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                safe = "'" + safe;
            }
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static void writeUtf8(File file, String content) throws Exception {
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
