package com.mythofcrawl.crawler.bridge;

import com.mythofcrawl.crawler.ui.theme.Theme;

import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory standalone {@link UiHost} for headless tests and offline visual harness verification.
 */
public final class HeadlessUiHost implements UiHost {

    @FunctionalInterface
    public interface RawSender {
        SendResult send(byte[] rawRequest, String host, int port, boolean https);
    }

    public static final class RecordedAction {
        public final String target;
        public final String host;
        public final int port;
        public final boolean https;
        public final String label;
        public final byte[] rawRequest;
        public final byte[] rawResponse;

        public RecordedAction(
            String target, String host, int port, boolean https, String label, byte[] rawRequest, byte[] rawResponse
        ) {
            this.target = target;
            this.host = host;
            this.port = port;
            this.https = https;
            this.label = label;
            this.rawRequest = rawRequest;
            this.rawResponse = rawResponse;
        }
    }

    private volatile boolean darkTheme;
    private volatile Font displayFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private volatile Font editorFont = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private volatile Frame frame;
    private volatile RawSender rawSender;
    private final Map<String, String> prefs = new ConcurrentHashMap<>();
    private final List<RecordedAction> actions = new CopyOnWriteArrayList<>();

    public HeadlessUiHost() {
        this(false);
    }

    public HeadlessUiHost(boolean darkTheme) {
        this.darkTheme = darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        Theme.setDark(darkTheme);
    }

    public void setDisplayFont(Font font) {
        if (font != null) {
            this.displayFont = font;
        }
    }

    public void setEditorFont(Font font) {
        if (font != null) {
            this.editorFont = font;
        }
    }

    public void setSuiteFrame(Frame frame) {
        this.frame = frame;
    }

    public void setRawSender(RawSender rawSender) {
        this.rawSender = rawSender;
    }

    @Override
    public boolean isDarkTheme() {
        return Theme.isDark();
    }

    @Override
    public Font displayFont() {
        return displayFont;
    }

    @Override
    public Font editorFont() {
        return editorFont;
    }

    @Override
    public void sendToRepeater(byte[] rawRequest, String host, int port, boolean https, String tabCaption) {
        actions.add(new RecordedAction("REPEATER", host, port, https, tabCaption, rawRequest, null));
    }

    @Override
    public void sendToOrganizer(
        byte[] rawRequest, byte[] rawResponse, String host, int port, boolean https, String notes
    ) {
        actions.add(new RecordedAction("ORGANIZER", host, port, https, notes, rawRequest, rawResponse));
    }

    @Override
    public SendResult sendRawRequest(byte[] rawRequest, String host, int port, boolean https) {
        if (rawSender != null) {
            return rawSender.send(rawRequest, host, port, https);
        }
        return new SendResult(true, 200, new byte[0], 1L, "");
    }

    @Override
    public Frame suiteFrame() {
        return frame;
    }

    @Override
    public void savePreference(String key, String value) {
        if (key != null && !key.isEmpty()) {
            if (value == null) {
                prefs.remove(key);
            } else {
                prefs.put(key, value);
            }
        }
    }

    @Override
    public String loadPreference(String key, String defaultValue) {
        if (key == null || key.isEmpty()) {
            return defaultValue;
        }
        return prefs.getOrDefault(key, defaultValue);
    }

    public List<RecordedAction> snapshotActions() {
        return new ArrayList<>(actions);
    }

    public void clearActions() {
        actions.clear();
    }
}
