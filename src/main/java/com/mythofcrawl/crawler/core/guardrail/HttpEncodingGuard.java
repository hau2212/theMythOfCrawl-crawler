package com.mythofcrawl.crawler.core.guardrail;

import com.mythofcrawl.crawler.model.MiniJson;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java HTTP encoding guard (Law #6):
 * - Sanitizes header/display strings to strict 7-bit ASCII without mojibake.
 * - Preserves exact Raw HTTP byte fidelity using ISO-8859-1 round-tripping.
 * - Synthesizes virtual 200 OK responses for HOLD_AT_PROXY without hitting target servers.
 */
public final class HttpEncodingGuard {

    private HttpEncodingGuard() {
    }

    /**
     * Converts an arbitrary string into ASCII-safe text (`0x09..0x7E`, preserving CR/LF/TAB)
     * by decomposing Unicode diacritics (e.g. Vietnamese) and stripping/replacing non-ASCII characters.
     */
    public static String asciiSafe(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String pre = input.replace('\u0111', 'd').replace('\u0110', 'D');
        String decomposed = Normalizer.normalize(pre, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                continue;
            }
            if (c == '\r' || c == '\n' || c == '\t' || (c >= 0x20 && c <= 0x7E)) {
                sb.append(c);
            } else if (c >= 0x80) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /**
     * Converts Raw HTTP text into ISO-8859-1 bytes without UTF-8 re-encoding corruption.
     */
    public static byte[] toIso88591Bytes(String rawHttp) {
        if (rawHttp == null || rawHttp.isEmpty()) {
            return new byte[0];
        }
        return rawHttp.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Decodes Raw HTTP bytes cleanly as ISO-8859-1 so binary/arbitrary 0x80..0xFF bytes survive round-trips.
     */
    public static String fromIso88591Bytes(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return "";
        }
        return new String(rawBytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * Builds the virtual JSON body returned when a request is held safely at Proxy (`HOLD_AT_PROXY`).
     */
    public static byte[] buildVirtual200OkResponseBytes(String reason, String endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.TRUE);
        body.put("heldAtProxy", Boolean.TRUE);
        body.put("virtualResponse", Boolean.TRUE);
        body.put("reason", reason != null ? reason : "Held safely by Action Guardrail");
        body.put("endpoint", endpoint != null ? endpoint : "");
        return MiniJson.stringify(body).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a full raw HTTP/1.1 200 OK wire response including {@code X-Myth-Proxy-Hold: 1}.
     */
    public static byte[] buildFullVirtual200OkHttpMessage(String reason, String endpoint) {
        byte[] json = buildVirtual200OkResponseBytes(reason, endpoint);
        String headers = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "X-Myth-Proxy-Hold: 1\r\n"
            + "Cache-Control: no-store, no-cache\r\n"
            + "Content-Length: " + json.length + "\r\n"
            + "Connection: close\r\n\r\n";
        byte[] hdrBytes = headers.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[hdrBytes.length + json.length];
        System.arraycopy(hdrBytes, 0, out, 0, hdrBytes.length);
        System.arraycopy(json, 0, out, hdrBytes.length, json.length);
        return out;
    }
}
