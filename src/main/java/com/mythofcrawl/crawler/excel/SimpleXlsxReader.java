package com.mythofcrawl.crawler.excel;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zero-dependency, XXE-hardened streaming StAX reader for .xlsx (OOXML ZIP+XML) and .csv/.tsv files.
 * Includes a 40 MB per-entry zip-bomb guard, relative .rels target resolution (`../`),
 * `<rPh>` phonetic run filtering, and configurable CSV charset + BOM detection.
 */
public final class SimpleXlsxReader {

    public static final long MAX_ZIP_ENTRY_BYTES = 40L * 1024L * 1024L;

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

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("OOXML entry exceeds 40 MB zip-bomb safety limit");
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                throw new IOException("OOXML entry exceeds 40 MB zip-bomb safety limit");
            }
            int toRead = (int) Math.min(len, remaining);
            int n = super.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
                if (remaining == 0) {
                    // Probe 1 more byte to detect exact overflow beyond cap
                    int extra = super.read();
                    if (extra >= 0) {
                        throw new IOException("OOXML entry exceeds 40 MB zip-bomb safety limit");
                    }
                }
            }
            return n;
        }
    }

    public static WorkbookData readFile(File file) throws Exception {
        return readFile(file, null);
    }

    public static WorkbookData readFile(File file, Charset csvCharset) throws Exception {
        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return readXlsx(file);
        }
        if (lower.endsWith(".xls")) {
            throw new IllegalArgumentException(
                "Định dạng .xls nhị phân (Excel 97-2003) chưa được hỗ trợ trực tiếp. "
                + "Vui lòng Save As sang .xlsx (hoặc .csv UTF-8) rồi Import lại.");
        }
        Charset effectiveCharset = csvCharset != null ? csvCharset : detectBomOrUtf8(file);
        return readDelimitedText(file, lower.endsWith(".tsv") ? '\t' : detectCsvDelimiter(file, effectiveCharset), effectiveCharset);
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
                try (InputStream in = openGuardedEntry(zip, ze)) {
                    List<List<String>> rows = parseWorksheetXml(in, sharedStrings);
                    names.add(sheetName);
                    data.put(sheetName, rows);
                }
            }
            if (names.isEmpty()) {
                ZipEntry fallback = zip.getEntry("xl/worksheets/sheet1.xml");
                if (fallback != null) {
                    try (InputStream in = openGuardedEntry(zip, fallback)) {
                        names.add("Sheet1");
                        data.put("Sheet1", parseWorksheetXml(in, sharedStrings));
                    }
                }
            }
            return new WorkbookData(names, data);
        }
    }

    private static InputStream openGuardedEntry(ZipFile zip, ZipEntry ze) throws IOException {
        long declared = ze.getSize();
        if (declared > MAX_ZIP_ENTRY_BYTES) {
            throw new IOException("OOXML entry '" + ze.getName() + "' exceeds 40 MB safety limit (" + declared + " bytes)");
        }
        return new BoundedInputStream(new BufferedInputStream(zip.getInputStream(ze)), MAX_ZIP_ENTRY_BYTES);
    }

    private static XMLInputFactory createSecureStaxFactory() {
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        try {
            xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        } catch (IllegalArgumentException ignored) {
        }
        return xif;
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry ze = zip.getEntry("xl/sharedStrings.xml");
        if (ze == null) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        XMLInputFactory xif = createSecureStaxFactory();
        try (InputStream in = openGuardedEntry(zip, ze)) {
            XMLStreamReader xr = xif.createXMLStreamReader(in);
            try {
                boolean inSi = false;
                boolean inT = false;
                int rPhDepth = 0;
                StringBuilder curSi = new StringBuilder();
                while (xr.hasNext()) {
                    int ev = xr.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String local = xr.getLocalName();
                        if ("si".equals(local)) {
                            inSi = true;
                            curSi.setLength(0);
                            rPhDepth = 0;
                        } else if (inSi && "rPh".equals(local)) {
                            rPhDepth++;
                        } else if (inSi && rPhDepth == 0 && "t".equals(local)) {
                            inT = true;
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                        if (inSi && inT && rPhDepth == 0) {
                            curSi.append(xr.getText());
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        String local = xr.getLocalName();
                        if ("t".equals(local)) {
                            inT = false;
                        } else if ("rPh".equals(local) && rPhDepth > 0) {
                            rPhDepth--;
                        } else if ("si".equals(local)) {
                            list.add(curSi.toString());
                            inSi = false;
                            inT = false;
                            rPhDepth = 0;
                        }
                    }
                }
            } finally {
                xr.close();
            }
        }
        return list;
    }

    private static LinkedHashMap<String, String> readSheetTargets(ZipFile zip) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Map<String, String> ridToTarget = new HashMap<>();
        XMLInputFactory xif = createSecureStaxFactory();

        ZipEntry relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (relsEntry != null) {
            try (InputStream in = openGuardedEntry(zip, relsEntry)) {
                XMLStreamReader xr = xif.createXMLStreamReader(in);
                try {
                    while (xr.hasNext()) {
                        if (xr.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(xr.getLocalName())) {
                            String id = attrValue(xr, "Id");
                            String target = attrValue(xr, "Target");
                            if (!id.isEmpty() && !target.isEmpty()) {
                                ridToTarget.put(id, resolveWorkbookRelTarget(target));
                            }
                        }
                    }
                } finally {
                    xr.close();
                }
            }
        }

        ZipEntry wbEntry = zip.getEntry("xl/workbook.xml");
        if (wbEntry != null) {
            try (InputStream in = openGuardedEntry(zip, wbEntry)) {
                XMLStreamReader xr = xif.createXMLStreamReader(in);
                try {
                    int idx = 0;
                    while (xr.hasNext()) {
                        if (xr.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(xr.getLocalName())) {
                            String name = attrValue(xr, "name");
                            String rid = attrValueNS(xr,
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                            if (rid.isEmpty()) {
                                rid = attrValue(xr, "id");
                            }
                            String target = ridToTarget.get(rid);
                            if (target == null) {
                                target = "xl/worksheets/sheet" + (idx + 1) + ".xml";
                            }
                            result.put(name.isEmpty() ? ("Sheet" + (idx + 1)) : name, target);
                            idx++;
                        }
                    }
                } finally {
                    xr.close();
                }
            }
        }
        return result;
    }

    static String resolveWorkbookRelTarget(String rawTarget) {
        String norm = rawTarget.replace('\\', '/').trim();
        Deque<String> stack = new ArrayDeque<>();
        if (!norm.startsWith("/")) {
            stack.addLast("xl");
        }
        for (String seg : norm.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!stack.isEmpty()) {
                    stack.pollLast();
                }
            } else {
                stack.addLast(seg);
            }
        }
        return String.join("/", stack);
    }

    private static List<List<String>> parseWorksheetXml(InputStream in, List<String> sharedStrings) throws Exception {
        XMLInputFactory xif = createSecureStaxFactory();
        XMLStreamReader xr = xif.createXMLStreamReader(in);
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;
        try {
            Map<Integer, String> sparseRow = null;
            int rowMaxCol = -1;
            int fallbackCol = 0;

            boolean inCell = false;
            int curColIdx = 0;
            String cellType = "";
            boolean inV = false;
            boolean inInlineT = false;
            int rPhDepth = 0;
            StringBuilder cellText = new StringBuilder();

            while (xr.hasNext()) {
                int ev = xr.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = xr.getLocalName();
                    if ("row".equals(local)) {
                        sparseRow = new HashMap<>();
                        rowMaxCol = -1;
                        fallbackCol = 0;
                    } else if (sparseRow != null && "c".equals(local)) {
                        inCell = true;
                        String ref = attrValue(xr, "r");
                        curColIdx = !ref.isEmpty() ? columnIndexFromRef(ref) : fallbackCol;
                        fallbackCol = curColIdx + 1;
                        if (curColIdx > rowMaxCol) {
                            rowMaxCol = curColIdx;
                        }
                        cellType = attrValue(xr, "t");
                        inV = false;
                        inInlineT = false;
                        rPhDepth = 0;
                        cellText.setLength(0);
                    } else if (inCell) {
                        if ("rPh".equals(local)) {
                            rPhDepth++;
                        } else if ("v".equals(local) && !"inlineStr".equals(cellType)) {
                            inV = true;
                            cellText.setLength(0);
                        } else if ("t".equals(local) && "inlineStr".equals(cellType) && rPhDepth == 0) {
                            inInlineT = true;
                        }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (inCell && (inV || (inInlineT && rPhDepth == 0))) {
                        cellText.append(xr.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = xr.getLocalName();
                    if ("v".equals(local)) {
                        inV = false;
                    } else if ("t".equals(local)) {
                        inInlineT = false;
                    } else if ("rPh".equals(local) && rPhDepth > 0) {
                        rPhDepth--;
                    } else if ("c".equals(local) && inCell) {
                        String val = formatCellValue(cellType, cellText.toString(), sharedStrings);
                        sparseRow.put(curColIdx, val);
                        inCell = false;
                        inV = false;
                        inInlineT = false;
                        rPhDepth = 0;
                    } else if ("row".equals(local) && sparseRow != null) {
                        if (rowMaxCol >= 0) {
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
                        sparseRow = null;
                    }
                }
            }
        } finally {
            xr.close();
        }
        for (List<String> r : rows) {
            while (r.size() < maxCols) {
                r.add("");
            }
        }
        return rows;
    }

    private static String formatCellValue(String type, String raw, List<String> sharedStrings) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if ("inlineStr".equals(type)) {
            return raw.trim();
        }
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                if (idx >= 0 && idx < sharedStrings.size()) {
                    return sharedStrings.get(idx).trim();
                }
            } catch (NumberFormatException ignored) {
            }
            return "";
        }
        if ("str".equals(type) || "b".equals(type) || "e".equals(type)) {
            return raw.trim();
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith(".0")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2);
            if (prefix.matches("-?\\d+")) {
                return prefix;
            }
        }
        return trimmed;
    }

    private static String attrValue(XMLStreamReader xr, String localName) {
        for (int i = 0; i < xr.getAttributeCount(); i++) {
            if (localName.equals(xr.getAttributeLocalName(i))) {
                String v = xr.getAttributeValue(i);
                return v != null ? v : "";
            }
        }
        return "";
    }

    private static String attrValueNS(XMLStreamReader xr, String nsUri, String localName) {
        String v = xr.getAttributeValue(nsUri, localName);
        return v != null ? v : "";
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

    private static Charset detectBomOrUtf8(File file) {
        byte[] bom = new byte[4];
        try (InputStream is = new FileInputStream(file)) {
            int n = is.read(bom);
            if (n >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
            if (n >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (n >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private static char detectCsvDelimiter(File file, Charset charset) {
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), charset))) {
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
        return readDelimitedText(file, delimiter, StandardCharsets.UTF_8);
    }

    public static WorkbookData readDelimitedText(File file, char delimiter, Charset charset) throws Exception {
        Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), cs))) {
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
