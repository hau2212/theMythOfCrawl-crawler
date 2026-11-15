package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON parser and serializer for /__myth_crawl/* control plane
 * messages and JSON report export.
 */
public final class MiniJson {

    private MiniJson() {
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("null");
        } else if (val instanceof Boolean || val instanceof Number) {
            sb.append(val.toString());
        } else if (val instanceof CharSequence) {
            writeString(sb, val.toString());
        } else if (val instanceof Map<?, ?>) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) val).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (val instanceof Iterable<?>) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) val) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, val.toString());
        }
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        writeString(sb, s != null ? s : "");
        return sb.toString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object parsed = new Parser(json).parse();
        if (parsed instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static String asString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : def;
    }

    public static boolean asBoolean(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<String> asStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?>) {
            for (Object item : (List<?>) v) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static final class Parser {
        private final String s;
        private final int n;
        private int i = 0;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
        }

        Object parse() {
            skipWs();
            return readValue();
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

        Object readValue() {
            skipWs();
            if (i >= n) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' && s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < n) {
                char ch = s.charAt(i);
                if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (i > start) {
                String num = s.substring(start, i);
                try {
                    if (num.contains(".") || num.contains("e") || num.contains("E")) {
                        return Double.parseDouble(num);
                    }
                    return Long.parseLong(num);
                } catch (NumberFormatException ignored) {
                    return num;
                }
            }
            return null;
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // '{'
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
                skipWs();
                if (i < n && s.charAt(i) == ':') {
                    i++;
                }
                Object val = readValue();
                map.put(key, val);
            }
            return map;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            i++; // '['
            while (i < n) {
                skipWs();
                if (i >= n) {
                    break;
                }
                if (s.charAt(i) == ']') {
                    i++;
                    break;
                }
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                list.add(readValue());
            }
            return list;
        }

        String readString() {
            i++; // '"'
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '\\' && i < n) {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 <= n) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                    i += 4;
                                } catch (Exception ignored) {
                                }
                            }
                            break;
                        default:
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
    }
}
