package com.mythofcrawl.crawler.excel;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zero-dependency, XXE-hardened reader for .xlsx (OOXML ZIP+XML) and .csv/.tsv files.
 * Uses only standard JDK classes so the Burp extension JAR stays lean without Apache POI.
 */
public final class SimpleXlsxReader {

    private SimpleXlsxReader() {
    }

    public static final class WorkbookData {
        private final List<String> sheetNames;
        private final Map<String, List<List<String>>> sheets;

        public WorkbookData(List<String> sheetNames, Map<String, List<List<String>>> sheets) {
            this.sheetNames = Collections.unmodifiableList(new ArrayList<>(sheetNames));
            this.sheets = Collections.unmodifiableMap(new LinkedHashMap<>(sheets));
        }

        public List<String> getSheetNames() {
            return sheetNames;
        }

        public List<List<String>> getRows(String sheetName) {
            List<List<String>> rows = sheets.get(sheetName);
            return rows != null ? rows : Collections.emptyList();
        }
    }

    public static final class ColumnGuess {
        public final int headerRowIndex;
        public final int sttCol;
        public final int methodCol;
        public final int pathCol;

        public ColumnGuess(int headerRowIndex, int sttCol, int methodCol, int pathCol) {
            this.headerRowIndex = headerRowIndex;
            this.sttCol = sttCol;
            this.methodCol = methodCol;
            this.pathCol = pathCol;
        }
    }

    public static WorkbookData readFile(File file) throws Exception {
        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return readXlsx(file);
        }
        if (lower.endsWith(".xls")) {
            throw new IllegalArgumentException(
                "Định dạng .xls nhị phân (Excel 97-2003) chưa được hỗ trợ trực tiếp. " +
                "Vui lòng Save As sang .xlsx (hoặc .csv UTF-8) rồi Import lại.");
        }
        return readDelimitedText(file, lower.endsWith(".tsv") ? '\t' : detectCsvDelimiter(file));
    }

    public static WorkbookData readXlsx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            List<String> sharedStrings = readSharedStrings(zip);
            LinkedHashMap<String, String> sheetTargets = readSheetTargets(zip);

            List<String> names = new ArrayList<>();
            Map<String, List<List<String>>> data = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : sheetTargets.entrySet()) {
                String sheetName = entry.getKey();
                String entryPath = entry.getValue();
                ZipEntry ze = zip.getEntry(entryPath);
                if (ze == null && entryPath.startsWith("/")) {
                    ze = zip.getEntry(entryPath.substring(1));
                }
                if (ze == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(ze)) {
                    List<List<String>> rows = parseWorksheetXml(in, sharedStrings);
                    names.add(sheetName);
                    data.put(sheetName, rows);
                }
            }
            if (names.isEmpty()) {
                ZipEntry fallback = zip.getEntry("xl/worksheets/sheet1.xml");
                if (fallback != null) {
                    try (InputStream in = zip.getInputStream(fallback)) {
                        names.add("Sheet1");
                        data.put("Sheet1", parseWorksheetXml(in, sharedStrings));
                    }
                }
            }
            return new WorkbookData(names, data);
        }
    }

    private static Document parseSecureXml(InputStream in) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(in);
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry ze = zip.getEntry("xl/sharedStrings.xml");
        if (ze == null) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        try (InputStream in = zip.getInputStream(ze)) {
            Document doc = parseSecureXml(in);
            NodeList siNodes = doc.getElementsByTagNameNS("*", "si");
            if (siNodes.getLength() == 0) {
                siNodes = doc.getElementsByTagName("si");
            }
            for (int i = 0; i < siNodes.getLength(); i++) {
                Element si = (Element) siNodes.item(i);
                list.add(extractTextContentFromCellElement(si));
            }
        }
        return list;
    }

    private static String extractTextContentFromCellElement(Element el) {
        NodeList tNodes = el.getElementsByTagNameNS("*", "t");
        if (tNodes.getLength() == 0) {
            tNodes = el.getElementsByTagName("t");
        }
        if (tNodes.getLength() == 0) {
            return el.getTextContent() != null ? el.getTextContent() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < tNodes.getLength(); j++) {
            Node t = tNodes.item(j);
            if (t.getTextContent() != null) {
                sb.append(t.getTextContent());
            }
        }
        return sb.toString();
    }

    private static LinkedHashMap<String, String> readSheetTargets(ZipFile zip) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Map<String, String> ridToTarget = new HashMap<>();

        ZipEntry relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (relsEntry != null) {
            try (InputStream in = zip.getInputStream(relsEntry)) {
                Document doc = parseSecureXml(in);
                NodeList rels = doc.getElementsByTagNameNS("*", "Relationship");
                if (rels.getLength() == 0) {
                    rels = doc.getElementsByTagName("Relationship");
                }
                for (int i = 0; i < rels.getLength(); i++) {
                    Element rel = (Element) rels.item(i);
                    String id = rel.getAttribute("Id");
                    String target = rel.getAttribute("Target");
                    if (!id.isEmpty() && !target.isEmpty()) {
                        String normalized = target.startsWith("/") ? target.substring(1) :
                            (target.startsWith("xl/") ? target : "xl/" + target);
                        ridToTarget.put(id, normalized);
                    }
                }
            }
        }

        ZipEntry wbEntry = zip.getEntry("xl/workbook.xml");
        if (wbEntry != null) {
            try (InputStream in = zip.getInputStream(wbEntry)) {
                Document doc = parseSecureXml(in);
                NodeList sheets = doc.getElementsByTagNameNS("*", "sheet");
                if (sheets.getLength() == 0) {
                    sheets = doc.getElementsByTagName("sheet");
                }
                for (int i = 0; i < sheets.getLength(); i++) {
                    Element sh = (Element) sheets.item(i);
                    String name = sh.getAttribute("name");
                    String rid = sh.getAttributeNS(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                    if (rid == null || rid.isEmpty()) {
                        rid = sh.getAttribute("r:id");
                    }
                    String target = ridToTarget.get(rid);
                    if (target == null) {
                        target = "xl/worksheets/sheet" + (i + 1) + ".xml";
                    }
                    result.put(name.isEmpty() ? ("Sheet" + (i + 1)) : name, target);
                }
            }
        }
        return result;
    }

    private static List<List<String>> parseWorksheetXml(InputStream in, List<String> sharedStrings) throws Exception {
        Document doc = parseSecureXml(in);
        NodeList rowNodes = doc.getElementsByTagNameNS("*", "row");
        if (rowNodes.getLength() == 0) {
            rowNodes = doc.getElementsByTagName("row");
        }
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            NodeList cellNodes = rowEl.getElementsByTagNameNS("*", "c");
            if (cellNodes.getLength() == 0) {
                cellNodes = rowEl.getElementsByTagName("c");
            }
            Map<Integer, String> sparseRow = new HashMap<>();
            int rowMaxCol = -1;
            int fallbackCol = 0;
            for (int c = 0; c < cellNodes.getLength(); c++) {
                Element cell = (Element) cellNodes.item(c);
                String ref = cell.getAttribute("r");
                int colIdx = !ref.isEmpty() ? columnIndexFromRef(ref) : fallbackCol;
                fallbackCol = colIdx + 1;
                if (colIdx > rowMaxCol) {
                    rowMaxCol = colIdx;
                }
                String value = readCellValue(cell, sharedStrings);
                sparseRow.put(colIdx, value);
            }
            if (rowMaxCol < 0) {
                continue;
            }
            if (rowMaxCol + 1 > maxCols) {
                maxCols = rowMaxCol + 1;
            }
            List<String> dense = new ArrayList<>(rowMaxCol + 1);
            boolean anyNonEmpty = false;
            for (int col = 0; col <= rowMaxCol; col++) {
                String v = sparseRow.getOrDefault(col, "");
                if (!v.isEmpty()) {
                    anyNonEmpty = true;
                }
                dense.add(v);
            }
            if (anyNonEmpty) {
                rows.add(dense);
            }
        }
        for (List<String> r : rows) {
            while (r.size() < maxCols) {
                r.add("");
            }
        }
        return rows;
    }

    private static String readCellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return extractTextContentFromCellElement(cell).trim();
        }
        NodeList vNodes = cell.getElementsByTagNameNS("*", "v");
        if (vNodes.getLength() == 0) {
            vNodes = cell.getElementsByTagName("v");
        }
        if (vNodes.getLength() == 0) {
            return "";
        }
        String raw = vNodes.item(0).getTextContent();
        if (raw == null) {
            return "";
        }
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                if (idx >= 0 && idx < sharedStrings.size()) {
                    return sharedStrings.get(idx).trim();
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if ("str".equals(type) || "b".equals(type) || "e".equals(type)) {
            return raw.trim();
        }
        // Numeric cell: clean trailing .0 for integers like STT 1.0 -> 1
        String trimmed = raw.trim();
        if (trimmed.endsWith(".0")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2);
            if (prefix.matches("-?\\d+")) {
                return prefix;
            }
        }
        return trimmed;
    }

    public static int columnIndexFromRef(String cellRef) {
        int col = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = cellRef.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(0, col - 1);
    }

    private static char detectCsvDelimiter(File file) {
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) {
                return ',';
            }
            int commas = countOutsideQuotes(line, ',');
            int semis = countOutsideQuotes(line, ';');
            int tabs = countOutsideQuotes(line, '\t');
            if (tabs > commas && tabs > semis) {
                return '\t';
            }
            if (semis > commas) {
                return ';';
            }
        } catch (Exception ignored) {
        }
        return ',';
    }

    private static int countOutsideQuotes(String line, char target) {
        boolean inQuotes = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == target) {
                count++;
            }
        }
        return count;
    }

    public static WorkbookData readDelimitedText(File file, char delimiter) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder record = new StringBuilder();
            boolean inQuotes = false;
            String line;
            while ((line = br.readLine()) != null) {
                if (rows.isEmpty() && record.length() == 0 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                if (record.length() > 0) {
                    record.append('\n');
                }
                record.append(line);
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == '"') {
                        inQuotes = !inQuotes;
                    }
                }
                if (!inQuotes) {
                    List<String> parsed = parseCsvRecord(record.toString(), delimiter);
                    record.setLength(0);
                    if (parsed.stream().anyMatch(s -> !s.isEmpty())) {
                        rows.add(parsed);
                        maxCols = Math.max(maxCols, parsed.size());
                    }
                }
            }
            if (record.length() > 0) {
                List<String> parsed = parseCsvRecord(record.toString(), delimiter);
                if (parsed.stream().anyMatch(s -> !s.isEmpty())) {
                    rows.add(parsed);
                    maxCols = Math.max(maxCols, parsed.size());
                }
            }
        }
        for (List<String> r : rows) {
            while (r.size() < maxCols) {
                r.add("");
            }
        }
        return new WorkbookData(Collections.singletonList("CSV"), Collections.singletonMap("CSV", rows));
    }

    private static List<String> parseCsvRecord(String rec, char delimiter) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < rec.length(); i++) {
            char c = rec.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < rec.length() && rec.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    cols.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        cols.add(cur.toString().trim());
        return cols;
    }

    /**
     * Automatically identifies the most likely header row and STT / Method / Path API column indices.
     */
    public static ColumnGuess guessColumns(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ColumnGuess(0, -1, -1, -1);
        }
        int bestHeaderRow = 0;
        int bestScore = -1;
        int bestStt = -1;
        int bestMethod = -1;
        int bestPath = -1;

        int scanLimit = Math.min(rows.size(), 15);
        for (int r = 0; r < scanLimit; r++) {
            List<String> row = rows.get(r);
            int stt = -1;
            int method = -1;
            int path = -1;
            for (int c = 0; c < row.size(); c++) {
                String h = normalizeHeader(row.get(c));
                if (h.isEmpty()) {
                    continue;
                }
                if (stt < 0 && (h.equals("stt") || h.equals("no") || h.equals("no.") || h.equals("#")
                    || h.equals("index") || h.equals("id") || h.contains("số thứ tự"))) {
                    stt = c;
                } else if (method < 0 && (h.equals("method") || h.contains("http method")
                    || h.equals("verb") || h.contains("phương thức"))) {
                    method = c;
                } else if (path < 0 && (h.contains("path") || h.contains("endpoint") || h.contains("api")
                    || h.contains("url") || h.contains("uri") || h.contains("route")
                    || h.contains("đường dẫn"))) {
                    path = c;
                }
            }
            int score = (stt >= 0 ? 1 : 0) + (method >= 0 ? 2 : 0) + (path >= 0 ? 3 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestHeaderRow = r;
                bestStt = stt;
                bestMethod = method;
                bestPath = path;
            }
        }

        // Fallback heuristic from data row values if headers were missing or ambiguous
        if (bestMethod < 0 || bestPath < 0) {
            int sampleRow = Math.min(rows.size() - 1, bestHeaderRow + 1);
            List<String> sample = rows.get(sampleRow);
            for (int c = 0; c < sample.size(); c++) {
                String val = sample.get(c).trim();
                if (bestStt < 0 && val.matches("\\d+")) {
                    bestStt = c;
                } else if (bestMethod < 0 && val.toUpperCase().matches("(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)")) {
                    bestMethod = c;
                } else if (bestPath < 0 && (val.startsWith("/") || val.contains("/api"))) {
                    bestPath = c;
                }
            }
        }
        return new ColumnGuess(bestHeaderRow, bestStt, bestMethod, bestPath);
    }

    private static String normalizeHeader(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase().replace('_', ' ').replaceAll("\\s+", " ");
    }
}
