package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.MiniJson;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports Excel coverage, UI action paths, matched URLs, and parameter diff notes
 * to CSV (Excel UTF-8 BOM compatible), JSON, and Markdown hierarchy reports.
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    public static void exportCsv(File file, ExcelSpecMatcher matcher, List<CapturedApiEntry> extras) throws Exception {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM so Microsoft Excel on Windows displays Vietnamese headers/notes properly
        sb.append('\uFEFF');
        sb.append("STT,Status,Excel Method,Excel Path,UI Action Path,Matched URL,HTTP Status,Params Count,Params Keys,Variants Count,Param Diff Note,JS Hints\n");

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

                sb.append(csvCell(entry.stt)).append(',')
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
                    sb.append(csvCell(entry.stt + " (Variant #" + altIdx + ")")).append(',')
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("excelSource", matcher != null ? matcher.getSourceFileName() : "");

        ExcelSpecMatcher.CoverageStats stats = matcher != null
            ? matcher.getCoverageStats()
            : new ExcelSpecMatcher.CoverageStats(0, 0, 0, 0);
        Map<String, Object> statsMap = new LinkedHashMap<>();
        statsMap.put("total", stats.total);
        statsMap.put("matched", stats.matched);
        statsMap.put("missing", stats.missing);
        statsMap.put("withParamDiffNotes", stats.withNotes);
        root.put("stats", statsMap);

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

    public static void exportMarkdown(File file, ExcelSpecMatcher matcher, List<CapturedApiEntry> extras) throws Exception {
        StringBuilder sb = new StringBuilder();
        ExcelSpecMatcher.CoverageStats stats = matcher != null
            ? matcher.getCoverageStats()
            : new ExcelSpecMatcher.CoverageStats(0, 0, 0, 0);

        sb.append("# theMythOfCrawl - API Coverage & Provenance Report\n\n");
        sb.append("- **Excel Source:** ").append(matcher != null ? matcher.getSourceFileName() : "(none)").append('\n');
        sb.append("- **Exported At:** ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        sb.append("- **Matched:** ").append(stats.matched).append(" / ").append(stats.total)
          .append(" (Missing: ").append(stats.missing).append(", Param Diff Notes: ").append(stats.withNotes).append(")\n\n");

        sb.append("## 1. Danh sách API theo cây thao tác UI (`Action Path`)\n\n");
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (matcher != null) {
            for (ApiSpecEntry entry : matcher.getEntries()) {
                for (CapturedApiEntry cap : entry.getAllStoredCaptures()) {
                    String role = (cap == entry.getPrimaryCapture()) ? "Primary" : "Variant";
                    String line = String.format(
                        "- **[STT %s - %s]** `%s %s` -> `%s` (HTTP %d, %d params: `%s`)%s",
                        entry.stt, role, cap.method, entry.rawSpecPath, cap.fullUrl,
                        cap.statusCode, cap.paramCount(), cap.paramSignature(),
                        (cap == entry.getPrimaryCapture() && !entry.getNotesSummary().isEmpty())
                            ? ("\n  - _Note:_ " + entry.getNotesSummary()) : "");
                    grouped.computeIfAbsent(cap.actionPath, k -> new ArrayList<>()).add(line);
                }
            }
        }

        if (grouped.isEmpty()) {
            sb.append("_Chưa bắt được API nào khớp Excel._\n\n");
        } else {
            for (Map.Entry<String, List<String>> g : grouped.entrySet()) {
                sb.append("### ").append(g.getKey()).append("\n\n");
                for (String line : g.getValue()) {
                    sb.append(line).append('\n');
                }
                sb.append('\n');
            }
        }

        if (matcher != null) {
            List<ApiSpecEntry> missing = new ArrayList<>();
            for (ApiSpecEntry entry : matcher.getEntries()) {
                if (!entry.isMatched()) {
                    missing.add(entry);
                }
            }
            if (!missing.isEmpty()) {
                sb.append("## 2. API trong Excel chưa gọi được (`MISSING`: ").append(missing.size()).append(")\n\n");
                sb.append("| STT | Method | Path API | JS Hints |\n| --- | --- | --- | --- |\n");
                for (ApiSpecEntry m : missing) {
                    sb.append("| ").append(m.stt.replace("|", "\\|"))
                      .append(" | `").append(m.displayMethod).append('`')
                      .append(" | `").append(m.rawSpecPath.replace("|", "\\|")).append('`')
                      .append(" | ").append(String.join(", ", m.getJsHints()).replace("|", "\\|")).append(" |\n");
                }
                sb.append('\n');
            }
        }

        if (extras != null && !extras.isEmpty()) {
            sb.append("## 3. API ngoài Excel (`").append(extras.size()).append("`)\n\n");
            for (CapturedApiEntry ex : extras) {
                sb.append("- `").append(ex.method).append(' ').append(ex.fullUrl)
                  .append("` từ **").append(ex.actionPath).append("**\n");
            }
        }

        writeUtf8(file, sb.toString());
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "\"\"";
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static void writeUtf8(File file, String content) throws Exception {
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
