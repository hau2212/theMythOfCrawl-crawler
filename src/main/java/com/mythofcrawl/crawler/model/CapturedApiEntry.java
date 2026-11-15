package com.mythofcrawl.crawler.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable snapshot of one HTTP request/response captured for a matched Excel API entry.
 */
public final class CapturedApiEntry {
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
        this.stt = stt;
        this.method = method;
        this.fullUrl = fullUrl;
        this.requestPathWithQuery = requestPathWithQuery;
        this.host = host;
        this.port = port;
        this.https = https;
        this.actionPath = actionPath;
        this.requestBytes = requestBytes;
        this.responseBytes = responseBytes;
        this.statusCode = statusCode;
        this.responseLength = responseLength;
        this.paramKeys = Collections.unmodifiableSet(new LinkedHashSet<>(paramKeys));
        this.capturedAtMillis = capturedAtMillis;
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
}
