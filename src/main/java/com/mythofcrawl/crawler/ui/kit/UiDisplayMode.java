package com.mythofcrawl.crawler.ui.kit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Toolbar / button presentation density mode supporting runtime switching
 * across ICON_TEXT, ICON_ONLY, and TEXT_ONLY.
 */
public enum UiDisplayMode {
    ICON_TEXT,
    ICON_ONLY,
    TEXT_ONLY;

    private static volatile UiDisplayMode globalMode = ICON_TEXT;
    private static final List<Consumer<UiDisplayMode>> LISTENERS = new CopyOnWriteArrayList<>();

    public static UiDisplayMode global() {
        return globalMode;
    }

    public static void setGlobal(UiDisplayMode mode) {
        if (mode != null && globalMode != mode) {
            globalMode = mode;
            for (Consumer<UiDisplayMode> l : LISTENERS) {
                try {
                    l.accept(mode);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static AutoCloseable onChange(Consumer<UiDisplayMode> listener) {
        if (listener == null) {
            return () -> {};
        }
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }
}
