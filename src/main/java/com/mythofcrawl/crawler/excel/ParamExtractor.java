package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.MiniJson;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts normalized parameter key sets from:
 * - Query String (including GET GraphQL JSON `variables`)
 * - Form URL-encoded body
 * - Binary-safe Multipart form-data (boundary scan on raw byte[], never allocating multi-MB Strings)
 * - JSON & batched GraphQL arrays (`[{operationName, query, variables}, ...]`)
 * - gRPC-Web (`application/grpc-web+proto` and Base64 `application/grpc-web-text`, 5-byte frame + schema-less protobuf wire decoder)
 * - XML / SOAP payloads
 * Zero external dependencies.
 */
public final class ParamExtractor {

    private static final Pattern MULTIPART_NAME = Pattern.compile(
        "(?i)\\bname\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^;\\s\\r\\n]+))");
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile(
        "(?i)boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))");
    private static final Pattern XML_OPEN_TAG = Pattern.compile("<([a-zA-Z0-9_:-]+)(?:\\s[^>]*)?>");
    private static final Pattern GRAPHQL_QUERY_OP = Pattern.compile(
        "(?i)\\b(query|mutation|subscription)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private ParamExtractor() {
    }

    public static Set<String> extractParameterKeys(String rawPathAndQuery, String contentType, byte[] body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();

        // 1. Query string parameters (including stringified JSON `variables` for GET GraphQL)
        if (rawPathAndQuery != null) {
            int q = rawPathAndQuery.indexOf('?');
            if (q >= 0 && q + 1 < rawPathAndQuery.length()) {
                String qs = rawPathAndQuery.substring(q + 1);
                int hash = qs.indexOf('#');
                if (hash >= 0) {
                    qs = qs.substring(0, hash);
                }
                parseFormPairsAndGraphqlVars(qs, "", keys);
            }
        }

        // 2. Body parameters
        if (body != null && body.length > 0) {
            String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";

            if (ct.contains("multipart/form-data")) {
                extractMultipartKeysBinary(contentType, body, keys);
            } else if (ct.contains("grpc-web")) {
                extractGrpcWebKeys(ct, body, keys);
            } else {
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                String trimmed = bodyStr.trim();

                if (ct.contains("application/json") || ct.contains("application/graphql")
                    || (ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")))) {
                    extractJsonKeys(trimmed, "json", keys);
                } else if (ct.contains("application/x-www-form-urlencoded")) {
                    parseFormPairs(bodyStr, "body.", keys);
                } else if (ct.contains("xml") || ct.contains("soap") || trimmed.startsWith("<")) {
                    extractXmlKeys(trimmed, keys);
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("<") && trimmed.contains("=") && !trimmed.contains("\n")) {
                    // Heuristic fallback for unlabeled URL-encoded forms
                    parseFormPairs(trimmed, "body.", keys);
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    /**
     * Extracts GraphQL operation name(s) from POST JSON objects, batched JSON arrays `[{...}, ...]`,
     * or returns "" otherwise.
     */
    public static String extractGraphqlOperation(String contentType, byte[] body) {
        return extractGraphqlOperation(null, contentType, body);
    }

    /**
     * Extracts GraphQL operation name(s) from GET query parameters (`?operationName=...&query=...`)
     * as well as POST JSON objects and batched JSON arrays `[{...}, ...]`.
     */
    public static String extractGraphqlOperation(String pathWithQuery, String contentType, byte[] body) {
        // 1. Check POST body first
        if (body != null && body.length > 0) {
            String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
            String str = new String(body, StandardCharsets.UTF_8).trim();
            if (ct.contains("json") || ct.contains("graphql") || str.startsWith("{") || str.startsWith("[")) {
                if (str.startsWith("[")) {
                    List<String> ops = extractBatchedGraphqlOps(str);
                    if (!ops.isEmpty()) {
                        return String.join(" + ", ops);
                    }
                } else if (str.startsWith("{")) {
                    try {
                        Map<String, Object> obj = MiniJson.parseObject(str);
                        String op = extractGraphqlOpFromMap(obj);
                        if (!op.isEmpty()) {
                            return op;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 2. Check GET /graphql?operationName=...&query=...
        if (pathWithQuery != null) {
            int qIdx = pathWithQuery.indexOf('?');
            if (qIdx >= 0 && qIdx + 1 < pathWithQuery.length()) {
                String qs = pathWithQuery.substring(qIdx + 1);
                int hash = qs.indexOf('#');
                if (hash >= 0) {
                    qs = qs.substring(0, hash);
                }
                String opName = "";
                String gqlQuery = "";
                for (String pair : qs.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = urlDecodeSafe(pair.substring(0, eq)).trim();
                    String v = urlDecodeSafe(pair.substring(eq + 1)).trim();
                    if ("operationName".equals(k) && !v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                        opName = v;
                    } else if ("query".equals(k) && !v.isEmpty()) {
                        gqlQuery = v;
                    }
                }
                if (!opName.isEmpty()) {
                    return opName;
                }
                if (!gqlQuery.isEmpty()) {
                    return extractGraphqlOpFromQueryString(gqlQuery);
                }
            }
        }
        return "";
    }

    /**
     * Inspects an HTTP 200 JSON response body for GraphQL `"errors":[...]` entries so
     * caller can attach an AuditNote for GraphQL errors disguised as HTTP 200.
     */
    public static List<String> extractGraphqlResponseErrors(String contentType, byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0 || responseBytes.length > 2_000_000) {
            return Collections.emptyList();
        }
        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        String str = new String(responseBytes, StandardCharsets.UTF_8).trim();
        if (!(ct.contains("json") || str.startsWith("{"))) {
            return Collections.emptyList();
        }
        if (!str.contains("\"errors\"")) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> obj = MiniJson.parseObject(str);
            Object errs = obj.get("errors");
            if (!(errs instanceof List<?>)) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>();
            for (Object item : (List<?>) errs) {
                if (item instanceof Map<?, ?>) {
                    Object msg = ((Map<?, ?>) item).get("message");
                    if (msg != null && !String.valueOf(msg).trim().isEmpty()) {
                        out.add(String.valueOf(msg).trim());
                    }
                } else if (item != null) {
                    out.add(String.valueOf(item));
                }
                if (out.size() >= 10) {
                    break;
                }
            }
            return out;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<String> extractBatchedGraphqlOps(String jsonArray) {
        LinkedHashSet<String> ops = new LinkedHashSet<>();
        int n = jsonArray.length();
        int i = 0;
        while (i < n) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                int end = findMatchingBrace(jsonArray, i);
                if (end > i) {
                    String slice = jsonArray.substring(i, end + 1);
                    try {
                        String op = extractGraphqlOpFromMap(MiniJson.parseObject(slice));
                        if (!op.isEmpty()) {
                            ops.add(op);
                        }
                    } catch (Exception ignored) {
                    }
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }
        return new ArrayList<>(ops);
    }

    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else {
                if (c == '"') {
                    inStr = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String extractGraphqlOpFromMap(Map<String, Object> obj) {
        if (obj == null || obj.isEmpty()) {
            return "";
        }
        String op = MiniJson.asString(obj, "operationName", "").trim();
        if (!op.isEmpty() && !"null".equalsIgnoreCase(op)) {
            return op;
        }
        String q = MiniJson.asString(obj, "query", "");
        return q.isEmpty() ? "" : extractGraphqlOpFromQueryString(q);
    }

    private static String extractGraphqlOpFromQueryString(String q) {
        Matcher m = GRAPHQL_QUERY_OP.matcher(q);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT) + " " + m.group(2);
        }
        String qTrim = q.trim().toLowerCase(Locale.ROOT);
        if (qTrim.startsWith("mutation")) {
            return "mutation";
        }
        if (qTrim.startsWith("subscription")) {
            return "subscription";
        }
        if (qTrim.startsWith("query") || qTrim.startsWith("{")) {
            return "query";
        }
        return "";
    }

    private static void extractXmlKeys(String xml, Set<String> out) {
        Matcher m = XML_OPEN_TAG.matcher(xml);
        int count = 0;
        while (m.find() && count < 200) {
            String tag = m.group(1);
            if (tag.startsWith("?") || tag.startsWith("!") || tag.equalsIgnoreCase("soap:Envelope")
                || tag.equalsIgnoreCase("soapenv:Envelope") || tag.equalsIgnoreCase("Envelope")) {
                continue;
            }
            int colon = tag.indexOf(':');
            String local = colon >= 0 ? tag.substring(colon + 1) : tag;
            if (!local.isEmpty()) {
                out.add("xml." + local);
                count++;
            }
        }
    }

    private static void parseFormPairsAndGraphqlVars(String encoded, String prefix, Set<String> out) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String key = urlDecodeSafe(rawKey).trim();
            if (!key.isEmpty()) {
                out.add(prefix + key);
                if ("variables".equals(key) && eq >= 0) {
                    String val = urlDecodeSafe(pair.substring(eq + 1)).trim();
                    if (val.startsWith("{") || val.startsWith("[")) {
                        extractJsonKeys(val, "json.variables", out);
                    }
                }
            }
        }
    }

    private static void parseFormPairs(String encoded, String prefix, Set<String> out) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String key = urlDecodeSafe(rawKey).trim();
            if (!key.isEmpty()) {
                out.add(prefix + key);
            }
        }
    }

    /**
     * Binary-safe multipart/form-data parser: scans raw byte[] by --boundary markers,
     * decoding only part header blocks (up to 8 KB) and never allocating multi-MB Strings.
     */
    private static void extractMultipartKeysBinary(String contentType, byte[] body, Set<String> out) {
        byte[] boundaryMarker = extractBoundaryBytes(contentType, body);
        if (boundaryMarker == null || boundaryMarker.length == 0) {
            return;
        }
        int pos = indexOfBytes(body, boundaryMarker, 0);
        int parts = 0;
        while (pos >= 0 && parts < 200) {
            int hdrStart = pos + boundaryMarker.length;
            if (hdrStart + 1 < body.length && body[hdrStart] == '-' && body[hdrStart + 1] == '-') {
                break; // closing --boundary--
            }
            if (hdrStart < body.length && body[hdrStart] == '\r') hdrStart++;
            if (hdrStart < body.length && body[hdrStart] == '\n') hdrStart++;

            int nextBoundary = indexOfBytes(body, boundaryMarker, hdrStart);
            int partEnd = nextBoundary >= 0 ? nextBoundary : body.length;

            int hdrEnd = findHeaderEnd(body, hdrStart, Math.min(partEnd, hdrStart + 8192));
            if (hdrEnd > hdrStart) {
                String hdrText = new String(body, hdrStart, hdrEnd - hdrStart, StandardCharsets.UTF_8);
                // Unfold CRLF + WSP header continuation lines
                String unfolded = hdrText.replaceAll("\\r?\\n[ \\t]+", " ");
                String partName = "";
                boolean isJsonPart = false;
                boolean isFilePart = false;
                for (String line : unfolded.split("\\r?\\n")) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-disposition:")) {
                        Matcher m = MULTIPART_NAME.matcher(line);
                        if (m.find()) {
                            partName = m.group(1) != null ? m.group(1)
                                : (m.group(2) != null ? m.group(2) : m.group(3));
                            if (partName != null) {
                                partName = partName.trim();
                            }
                        }
                        if (lower.contains("filename=") || lower.contains("filename*=")) {
                            isFilePart = true;
                        }
                    } else if (lower.startsWith("content-type:") && lower.contains("application/json")) {
                        isJsonPart = true;
                    }
                }
                if (partName != null && !partName.isEmpty()) {
                    out.add("form." + partName);
                    if (isJsonPart && !isFilePart) {
                        int bodyStart = skipDoubleNewline(body, hdrEnd, partEnd);
                        int bodyLen = partEnd - bodyStart;
                        if (bodyLen > 0 && bodyLen <= 65536) {
                            String partJson = new String(body, bodyStart, bodyLen, StandardCharsets.UTF_8).trim();
                            if (partJson.startsWith("{") || partJson.startsWith("[")) {
                                extractJsonKeys(partJson, "form." + partName, out);
                            }
                        }
                    }
                }
            }
            pos = nextBoundary;
            parts++;
        }
    }

    private static byte[] extractBoundaryBytes(String contentType, byte[] body) {
        if (contentType != null) {
            Matcher m = MULTIPART_BOUNDARY.matcher(contentType);
            if (m.find()) {
                String b = m.group(1) != null ? m.group(1) : m.group(2);
                if (b != null && !b.trim().isEmpty()) {
                    return ("--" + b.trim()).getBytes(StandardCharsets.ISO_8859_1);
                }
            }
        }
        // Fallback: infer boundary from first line starting with "--"
        if (body.length > 4 && body[0] == '-' && body[1] == '-') {
            int eol = 2;
            while (eol < body.length && eol < 128 && body[eol] != '\r' && body[eol] != '\n') {
                eol++;
            }
            if (eol > 2) {
                return Arrays.copyOfRange(body, 0, eol);
            }
        }
        return null;
    }

    private static int findHeaderEnd(byte[] buf, int start, int limit) {
        for (int i = start; i + 1 < limit; i++) {
            if (buf[i] == '\r' && i + 3 < limit && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
            if (buf[i] == '\n' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int skipDoubleNewline(byte[] buf, int hdrEnd, int limit) {
        if (hdrEnd + 3 < limit && buf[hdrEnd] == '\r' && buf[hdrEnd + 1] == '\n'
            && buf[hdrEnd + 2] == '\r' && buf[hdrEnd + 3] == '\n') {
            return hdrEnd + 4;
        }
        if (hdrEnd + 1 < limit && buf[hdrEnd] == '\n' && buf[hdrEnd + 1] == '\n') {
            return hdrEnd + 2;
        }
        return hdrEnd;
    }

    private static int indexOfBytes(byte[] hay, byte[] needle, int from) {
        if (needle.length == 0 || from < 0 || from + needle.length > hay.length) {
            return -1;
        }
        byte first = needle[0];
        int max = hay.length - needle.length;
        for (int i = from; i <= max; i++) {
            if (hay[i] != first) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Zero-dependency gRPC-Web frame & schema-less protobuf wire-type field extractor.
     * Supports `application/grpc-web+proto` (binary) and `application/grpc-web-text` (Base64).
     */
    private static void extractGrpcWebKeys(String contentTypeLower, byte[] rawBody, Set<String> out) {
        byte[] payload = rawBody;
        if (contentTypeLower.contains("grpc-web-text")) {
            try {
                String ascii = new String(rawBody, StandardCharsets.US_ASCII).replaceAll("\\s+", "");
                if (ascii.isEmpty()) {
                    return;
                }
                payload = Base64.getDecoder().decode(ascii);
            } catch (Exception ignored) {
                return;
            }
        }
        int off = 0;
        int frames = 0;
        while (off + 5 <= payload.length && frames < 32) {
            int flags = payload[off] & 0xFF;
            long frameLen = ((long) (payload[off + 1] & 0xFF) << 24)
                | ((long) (payload[off + 2] & 0xFF) << 16)
                | ((long) (payload[off + 3] & 0xFF) << 8)
                | ((long) (payload[off + 4] & 0xFF));
            off += 5;
            if (frameLen < 0 || off + frameLen > payload.length) {
                break;
            }
            int len = (int) frameLen;
            if ((flags & 0x80) != 0) {
                // Trailer frame (ASCII "grpc-status: 0\r\n...")
                String trailers = new String(payload, off, Math.min(len, 4096), StandardCharsets.UTF_8);
                for (String line : trailers.split("\\r?\\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String tk = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        if (!tk.isEmpty()) {
                            out.add("grpc.trailer." + tk);
                        }
                    }
                }
            } else {
                extractProtobufWireKeys(payload, off, off + len, "pb", 0, out);
            }
            off += len;
            frames++;
        }
    }

    private static boolean extractProtobufWireKeys(
        byte[] buf,
        int start,
        int end,
        String prefix,
        int depth,
        Set<String> out
    ) {
        if (depth > 6 || start >= end) {
            return false;
        }
        int pos = start;
        int fieldsParsed = 0;
        while (pos < end && fieldsParsed < 200) {
            long[] tagRes = readVarint(buf, pos, end);
            if (tagRes == null) {
                return false;
            }
            long tag = tagRes[0];
            pos = (int) tagRes[1];
            int fieldNum = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x07);
            if (fieldNum <= 0 || fieldNum > 536_870_911) {
                return false;
            }
            String fieldKey = prefix + ".f" + fieldNum;
            switch (wireType) {
                case 0: { // Varint
                    long[] valRes = readVarint(buf, pos, end);
                    if (valRes == null) return false;
                    pos = (int) valRes[1];
                    out.add(fieldKey);
                    break;
                }
                case 1: { // 64-bit
                    if (pos + 8 > end) return false;
                    pos += 8;
                    out.add(fieldKey);
                    break;
                }
                case 2: { // Length-delimited (string / bytes / embedded message)
                    long[] lenRes = readVarint(buf, pos, end);
                    if (lenRes == null) return false;
                    long l = lenRes[0];
                    pos = (int) lenRes[1];
                    if (l < 0 || pos + l > end) return false;
                    int subLen = (int) l;
                    boolean nestedOk = false;
                    if (subLen >= 2 && depth < 5 && !looksLikePrintableAscii(buf, pos, subLen)) {
                        Set<String> nestedKeys = new LinkedHashSet<>();
                        if (extractProtobufWireKeys(buf, pos, pos + subLen, fieldKey, depth + 1, nestedKeys)
                            && !nestedKeys.isEmpty()) {
                            out.addAll(nestedKeys);
                            nestedOk = true;
                        }
                    }
                    if (!nestedOk) {
                        out.add(fieldKey);
                    }
                    pos += subLen;
                    break;
                }
                case 5: { // 32-bit
                    if (pos + 4 > end) return false;
                    pos += 4;
                    out.add(fieldKey);
                    break;
                }
                default:
                    return false;
            }
            fieldsParsed++;
        }
        return pos == end && fieldsParsed > 0;
    }

    private static long[] readVarint(byte[] buf, int pos, int end) {
        long result = 0;
        int shift = 0;
        while (pos < end && shift < 64) {
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[] { result, pos };
            }
            shift += 7;
        }
        return null;
    }

    private static boolean looksLikePrintableAscii(byte[] buf, int start, int len) {
        int check = Math.min(len, 64);
        int printable = 0;
        for (int i = 0; i < check; i++) {
            int b = buf[start + i] & 0xFF;
            if ((b >= 0x20 && b <= 0x7E) || b == '\t' || b == '\r' || b == '\n') {
                printable++;
            }
        }
        return check > 0 && printable == check;
    }

    private static String urlDecodeSafe(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return s;
        }
    }

    // Minimal recursive-descent JSON key extractor (handles nested objects and arrays cleanly)
    public static void extractJsonKeys(String json, String prefix, Set<String> out) {
        if (json == null || json.isEmpty()) {
            return;
        }
        JsonKeyScanner scanner = new JsonKeyScanner(json, out);
        scanner.scanValue(prefix, 0);
    }

    private static final class JsonKeyScanner {
        private final String s;
        private final int n;
        private final Set<String> out;
        private int i = 0;

        JsonKeyScanner(String s, Set<String> out) {
            this.s = s;
            this.n = s.length();
            this.out = out;
        }

        void skipWs() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    i++;
                } else {
                    break;
                }
            }
        }

        void scanValue(String prefix, int depth) {
            if (depth > 20) {
                skipAny();
                return;
            }
            skipWs();
            if (i >= n) {
                return;
            }
            char c = s.charAt(i);
            if (c == '{') {
                scanObject(prefix, depth);
            } else if (c == '[') {
                scanArray(prefix, depth);
            } else if (c == '"') {
                readString();
            } else {
                skipPrimitive();
            }
        }

        void scanObject(String prefix, int depth) {
            i++; // consume '{'
            skipWs();
            if (i < n && s.charAt(i) == '}') {
                i++;
                return;
            }
            while (i < n) {
                skipWs();
                if (i >= n) {
                    break;
                }
                if (s.charAt(i) == '}') {
                    i++;
                    break;
                }
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (s.charAt(i) != '"') {
                    break;
                }
                String key = readString();
                String fullKey = (prefix == null || prefix.isEmpty()) ? key : (prefix + "." + key);
                skipWs();
                if (i < n && s.charAt(i) == ':') {
                    i++;
                }
                skipWs();
                if (i < n) {
                    char next = s.charAt(i);
                    if (next == '{') {
                        scanObject(fullKey, depth + 1);
                    } else if (next == '[') {
                        out.add(fullKey);
                        scanArray(fullKey, depth + 1);
                    } else {
                        out.add(fullKey);
                        scanValue(fullKey, depth + 1);
                    }
                }
            }
        }

        void scanArray(String prefix, int depth) {
            i++; // consume '['
            String elemPrefix = (prefix == null || prefix.isEmpty()) ? "[]" : (prefix + "[]");
            while (i < n) {
                skipWs();
                if (i >= n) {
                    break;
                }
                char c = s.charAt(i);
                if (c == ']') {
                    i++;
                    break;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                scanValue(elemPrefix, depth + 1);
            }
        }

        String readString() {
            if (i >= n || s.charAt(i) != '"') {
                return "";
            }
            i++; // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '\\' && i < n) {
                    char esc = s.charAt(i++);
                    if (esc == 'u' && i + 4 <= n) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        } catch (Exception ignored) {
                        }
                    } else {
                        sb.append(esc);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        void skipPrimitive() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    break;
                }
                i++;
            }
        }

        void skipAny() {
            int braces = 0;
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '"') {
                    i--;
                    readString();
                } else if (c == '{' || c == '[') {
                    braces++;
                } else if (c == '}' || c == ']') {
                    if (braces <= 0) {
                        break;
                    }
                    braces--;
                }
            }
        }
    }
}
