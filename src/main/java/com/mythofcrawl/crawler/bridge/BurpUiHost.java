package com.mythofcrawl.crawler.bridge;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Theme;

import java.awt.Font;
import java.awt.Frame;

/**
 * Burp Suite {@link MontoyaApi}-backed implementation of {@link UiHost}.
 */
public final class BurpUiHost implements UiHost {

    private final MontoyaApi api;

    public BurpUiHost(MontoyaApi api) {
        this.api = api;
    }

    public MontoyaApi getMontoyaApi() {
        return api;
    }

    @Override
    public boolean isDarkTheme() {
        if (api == null) {
            return false;
        }
        try {
            return api.userInterface().currentTheme() == Theme.DARK;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public Font displayFont() {
        if (api != null) {
            try {
                Font f = api.userInterface().currentDisplayFont();
                if (f != null) {
                    return f;
                }
            } catch (Exception ignored) {
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    @Override
    public Font editorFont() {
        if (api != null) {
            try {
                Font f = api.userInterface().currentEditorFont();
                if (f != null) {
                    return f;
                }
            } catch (Exception ignored) {
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    @Override
    public void sendToRepeater(byte[] rawRequest, String host, int port, boolean https, String tabCaption) {
        if (api == null || rawRequest == null || rawRequest.length == 0 || host == null || host.isEmpty()) {
            return;
        }
        HttpService svc = HttpService.httpService(host, normalizePort(port, https), https);
        HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(rawRequest));
        if (tabCaption != null && !tabCaption.trim().isEmpty()) {
            api.repeater().sendToRepeater(req, tabCaption.trim());
        } else {
            api.repeater().sendToRepeater(req);
        }
    }

    @Override
    public void sendToOrganizer(
        byte[] rawRequest,
        byte[] rawResponse,
        String host,
        int port,
        boolean https,
        String notes
    ) {
        if (api == null || rawRequest == null || rawRequest.length == 0 || host == null || host.isEmpty()) {
            return;
        }
        HttpService svc = HttpService.httpService(host, normalizePort(port, https), https);
        HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(rawRequest));
        if (rawResponse != null && rawResponse.length > 0) {
            HttpResponse resp = HttpResponse.httpResponse(ByteArray.byteArray(rawResponse));
            Annotations ann = (notes != null && !notes.isEmpty()) ? Annotations.annotations(notes) : Annotations.annotations();
            api.organizer().sendToOrganizer(HttpRequestResponse.httpRequestResponse(req, resp, ann));
        } else {
            api.organizer().sendToOrganizer(req);
        }
    }

    @Override
    public SendResult sendRawRequest(byte[] rawRequest, String host, int port, boolean https) {
        if (api == null) {
            return new SendResult(false, 0, null, 0L, "MontoyaApi unavailable");
        }
        if (rawRequest == null || rawRequest.length == 0 || host == null || host.isEmpty()) {
            return new SendResult(false, 0, null, 0L, "Empty request or host");
        }
        long start = System.currentTimeMillis();
        try {
            HttpService svc = HttpService.httpService(host, normalizePort(port, https), https);
            HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(rawRequest));
            HttpRequestResponse rr = api.http().sendRequest(req);
            long elapsed = System.currentTimeMillis() - start;
            if (rr != null && rr.hasResponse() && rr.response() != null) {
                return new SendResult(
                    true,
                    rr.response().statusCode(),
                    rr.response().toByteArray().getBytes(),
                    elapsed,
                    ""
                );
            }
            return new SendResult(false, 0, null, elapsed, "No response received");
        } catch (Exception ex) {
            return new SendResult(false, 0, null, System.currentTimeMillis() - start, ex.getMessage());
        }
    }

    @Override
    public Frame suiteFrame() {
        if (api == null) {
            return null;
        }
        try {
            return api.userInterface().swingUtils().suiteFrame();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void savePreference(String key, String value) {
        if (api == null || key == null || key.isEmpty()) {
            return;
        }
        try {
            if (value == null) {
                api.persistence().preferences().deleteString(key);
            } else {
                api.persistence().preferences().setString(key, value);
            }
        } catch (Exception ex) {
            logError("Could not save preference " + key, ex);
        }
    }

    @Override
    public String loadPreference(String key, String defaultValue) {
        if (api == null || key == null || key.isEmpty()) {
            return defaultValue;
        }
        try {
            String val = api.persistence().preferences().getString(key);
            return val != null ? val : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @Override
    public void logInfo(String message) {
        if (api != null && message != null) {
            api.logging().logToOutput("[theMythOfCrawl] " + message);
        }
    }

    @Override
    public void logError(String message, Throwable error) {
        if (api != null) {
            if (error != null) {
                api.logging().logToError("[theMythOfCrawl] " + (message != null ? message : ""), error);
            } else if (message != null) {
                api.logging().logToError("[theMythOfCrawl] " + message);
            }
        }
    }

    private static int normalizePort(int port, boolean https) {
        return port > 0 ? port : (https ? 443 : 80);
    }
}
