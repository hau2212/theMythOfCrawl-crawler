package com.mythofcrawl.crawler.excel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts normalized parameter key sets from Query String, Form URL-encoded body,
 * Multipart form fields, and JSON bodies (flattened key paths like json.filter.status)
 * using zero external dependencies.
 */
public final class ParamExtractor {

    private static final Pattern MULTIPART_NAME = Pattern.compile("name=\"([^\"]+)\"");

    private ParamExtractor() {
    }

    public static Set<String> extractParameterKeys(String rawPathAndQuery, String contentType, byte[] body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();

        // 1. Query string parameters
        if (rawPathAndQuery != null) {
            int q = rawPathAndQuery.indexOf('?');
            if (q >= 0 && q + 1 < rawPathAndQuery.length()) {
                String qs = rawPathAndQuery.substring(q + 1);
                int hash = qs.indexOf('#');
                if (hash >= 0) {
                    qs = qs.substring(0, hash);
                }
                parseFormPairs(qs, "", keys);
            }
        }

        // 2. Body parameters
        if (body != null && body.length > 0) {
            String ct = contentType != null ? contentType.toLowerCase() : "";
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            String trimmed = bodyStr.trim();

            if (ct.contains("application/json")
                || (ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")))) {
                extractJsonKeys(trimmed, "json", keys);
            } else if (ct.contains("application/x-www-form-urlencoded")) {
                parseFormPairs(bodyStr, "body.", keys);
            } else if (ct.contains("multipart/form-data")) {
                extractMultipartKeys(bodyStr, keys);
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("<") && trimmed.contains("=") && !trimmed.contains("\n")) {
                // Heuristic fallback for unlabeled URL-encoded forms
                parseFormPairs(trimmed, "body.", keys);
            }
        }
        return Collections.unmodifiableSet(keys);
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

    private static void extractMultipartKeys(String bodyStr, Set<String> out) {
        // Scan headers of multipart parts without loading binary payloads deeply
        int pos = 0;
        while (pos < bodyStr.length()) {
            int idx = bodyStr.indexOf("Content-Disposition:", pos);
            if (idx < 0) {
                idx = bodyStr.indexOf("content-disposition:", pos);
            }
            if (idx < 0) {
                break;
            }
            int lineEnd = bodyStr.indexOf('\n', idx);
            String line = lineEnd >= 0 ? bodyStr.substring(idx, lineEnd) : bodyStr.substring(idx);
            Matcher m = MULTIPART_NAME.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) {
                    out.add("form." + name);
                }
            }
            pos = (lineEnd >= 0 ? lineEnd + 1 : bodyStr.length());
        }
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
