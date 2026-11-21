package com.mythofcrawl.crawler.model;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snapshot of one HTTP request/response captured during crawl.
 *
 * Keeps bounded inline preview byte[] arrays (max 256 KB on heap) to prevent JVM OOM
 * on large downloads/JSON payloads, while optionally retaining a Montoya temp-file-backed
 * {@link HttpRequestResponse} handle for lossless inspection, Repeater, and Extender replay.
 */
public final class CapturedApiEntry {

    public static final int MAX_INLINE_REQUEST_BYTES = 256 * 1024;
    public static final int MAX_INLINE_RESPONSE_BYTES = 256 * 1024;

    public final String stt;
    public final String method;
    public final String fullUrl;
    public final String requestPathWithQuery;
    public final String host;
    public final int port;
    public final boolean https;
    public final String actionPath; // e.g. "/ > contact > button 'Send'"
    public final byte[] requestBytes;
    public final byte[] responseBytes;
    public final int statusCode;
    public final int responseLength;
    public final Set<String> paramKeys;
    public final long capturedAtMillis;
    public final HttpMode httpMode;
    public final String responseSha256;
    public final boolean requestTruncated;
    public final boolean responseTruncated;

    private final HttpRequestResponse tempFileStore;

    public CapturedApiEntry(
        String stt,
        String method,
        String fullUrl,
        String requestPathWithQuery,
        String host,
        int port,
        boolean https,
        String actionPath,
        byte[] requestBytes,
        byte[] responseBytes,
        int statusCode,
        int responseLength,
        Set<String> paramKeys,
        long capturedAtMillis
    ) {
        this(
            stt, method, fullUrl, requestPathWithQuery, host, port, https, actionPath,
            requestBytes, responseBytes, statusCode, responseLength, paramKeys, capturedAtMillis,
            null, HttpMode.AUTO, ""
        );
    }

    public CapturedApiEntry(
        String stt,
        String method,
        String fullUrl,
        String requestPathWithQuery,
        String host,
        int port,
        boolean https,
        String actionPath,
        byte[] requestBytes,
        byte[] responseBytes,
        int statusCode,
        int responseLength,
        Set<String> paramKeys,
        long capturedAtMillis,
        HttpRequestResponse tempFileStore,
        HttpMode httpMode,
        String responseSha256
    ) {
        this.stt = stt;
        this.method = method;
        this.fullUrl = fullUrl;
        this.requestPathWithQuery = requestPathWithQuery;
        this.host = host;
        this.port = port;
        this.https = https;
        this.actionPath = actionPath;

        byte[] safeReq = requestBytes != null ? requestBytes : new byte[0];
        this.requestTruncated = safeReq.length > MAX_INLINE_REQUEST_BYTES;
        this.requestBytes = this.requestTruncated
            ? Arrays.copyOf(safeReq, MAX_INLINE_REQUEST_BYTES)
            : safeReq;

        byte[] safeResp = responseBytes != null ? responseBytes : new byte[0];
        this.responseTruncated = safeResp.length > MAX_INLINE_RESPONSE_BYTES;
        this.responseBytes = this.responseTruncated
            ? Arrays.copyOf(safeResp, MAX_INLINE_RESPONSE_BYTES)
            : safeResp;

        this.statusCode = statusCode;
        this.responseLength = responseLength;
        this.paramKeys = Collections.unmodifiableSet(
            paramKeys != null ? new LinkedHashSet<>(paramKeys) : Collections.emptySet());
        this.capturedAtMillis = capturedAtMillis;
        this.tempFileStore = tempFileStore;
        this.httpMode = httpMode != null ? httpMode : HttpMode.AUTO;
        this.responseSha256 = responseSha256 != null ? responseSha256 : "";
    }

    public boolean hasTempFileBacking() {
        return tempFileStore != null;
    }

    public HttpMode getHttpMode() {
        return httpMode;
    }

    public HttpRequest toMontoyaRequest(HttpService fallbackService) {
        if (tempFileStore != null && tempFileStore.request() != null) {
            return tempFileStore.request();
        }
        HttpService svc = fallbackService != null
            ? fallbackService
            : HttpService.httpService(host, port, https);
        return HttpRequest.httpRequest(svc, ByteArray.byteArray(requestBytes));
    }

    public HttpResponse toMontoyaResponse() {
        if (tempFileStore != null && tempFileStore.response() != null) {
            return tempFileStore.response();
        }
        return HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
    }

    public int paramCount() {
        return paramKeys.size();
    }

    public String paramSignature() {
        if (paramKeys.isEmpty()) {
            return "(0 params)";
        }
        return String.join(", ", paramKeys);
    }

    public static byte[] capBytes(byte[] raw, int maxBytes) {
        if (raw == null) {
            return new byte[0];
        }
        if (raw.length <= maxBytes) {
            return raw;
        }
        return Arrays.copyOf(raw, maxBytes);
    }

    public static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
