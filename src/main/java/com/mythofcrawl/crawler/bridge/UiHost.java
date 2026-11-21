package com.mythofcrawl.crawler.bridge;

import java.awt.Font;
import java.awt.Frame;

/**
 * Host abstraction decoupling the UI & workflow layer from Burp Suite's {@code MontoyaApi}
 * so the entire UI can run inside Burp ({@link BurpUiHost}) or standalone headless/visual test harnesses
 * ({@link HeadlessUiHost}).
 */
public interface UiHost {

    final class SendResult {
        public final boolean ok;
        public final int statusCode;
        public final byte[] rawResponseBytes;
        public final long elapsedMs;
        public final String error;

        public SendResult(boolean ok, int statusCode, byte[] rawResponseBytes, long elapsedMs, String error) {
            this.ok = ok;
            this.statusCode = statusCode;
            this.rawResponseBytes = rawResponseBytes != null ? rawResponseBytes : new byte[0];
            this.elapsedMs = elapsedMs;
            this.error = error != null ? error : "";
        }
    }

    boolean isDarkTheme();

    Font displayFont();

    Font editorFont();

    void sendToRepeater(byte[] rawRequest, String host, int port, boolean https, String tabCaption);

    void sendToOrganizer(byte[] rawRequest, byte[] rawResponse, String host, int port, boolean https, String notes);

    SendResult sendRawRequest(byte[] rawRequest, String host, int port, boolean https);

    Frame suiteFrame();

    void savePreference(String key, String value);

    String loadPreference(String key, String defaultValue);

    default void logInfo(String message) {
    }

    default void logError(String message, Throwable error) {
    }
}
