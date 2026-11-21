package com.mythofcrawl.crawler.ui.theme;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Typography manager loading bundled OFL fonts (/fonts/BeVietnamPro-*.ttf and
 * /fonts/JetBrainsMono-*.ttf) with graceful system fallback for full Vietnamese diacritics.
 */
public final class FontManager {

    private static final String[] UI_FONT_RESOURCES = {
        "/fonts/BeVietnamPro-Regular.ttf",
        "/fonts/BeVietnamPro-Medium.ttf",
        "/fonts/BeVietnamPro-SemiBold.ttf",
        "/fonts/BeVietnamPro-Bold.ttf"
    };

    private static final String[] MONO_FONT_RESOURCES = {
        "/fonts/JetBrainsMono-Regular.ttf",
        "/fonts/JetBrainsMono-Medium.ttf",
        "/fonts/JetBrainsMono-Bold.ttf"
    };

    private static volatile boolean initialized = false;
    private static volatile boolean usingBundledUi = false;
    private static volatile boolean usingBundledMono = false;
    private static volatile Font bundledUiBase;
    private static volatile Font bundledUiMedium;
    private static volatile Font bundledUiSemiBold;
    private static volatile Font bundledUiBold;
    private static volatile Font bundledMonoBase;
    private static volatile Font bundledMonoMedium;
    private static volatile Font bundledMonoBold;

    private static volatile String activeUiFamily = Font.SANS_SERIF;
    private static volatile String activeMonoFamily = Font.MONOSPACED;

    private FontManager() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

        bundledUiBase = loadResourceFont(ge, UI_FONT_RESOURCES[0]);
        bundledUiMedium = loadResourceFont(ge, UI_FONT_RESOURCES[1]);
        bundledUiSemiBold = loadResourceFont(ge, UI_FONT_RESOURCES[2]);
        bundledUiBold = loadResourceFont(ge, UI_FONT_RESOURCES[3]);
        usingBundledUi = bundledUiBase != null;

        bundledMonoBase = loadResourceFont(ge, MONO_FONT_RESOURCES[0]);
        bundledMonoMedium = loadResourceFont(ge, MONO_FONT_RESOURCES[1]);
        bundledMonoBold = loadResourceFont(ge, MONO_FONT_RESOURCES[2]);
        usingBundledMono = bundledMonoBase != null;

        Set<String> available = new HashSet<>(Arrays.asList(ge.getAvailableFontFamilyNames()));
        if (usingBundledUi) {
            activeUiFamily = bundledUiBase.getFamily();
        } else if (available.contains("Segoe UI")) {
            activeUiFamily = "Segoe UI";
        } else if (available.contains("Inter")) {
            activeUiFamily = "Inter";
        } else {
            activeUiFamily = Font.SANS_SERIF;
        }

        if (usingBundledMono) {
            activeMonoFamily = bundledMonoBase.getFamily();
        } else if (available.contains("JetBrains Mono")) {
            activeMonoFamily = "JetBrains Mono";
        } else if (available.contains("Consolas")) {
            activeMonoFamily = "Consolas";
        } else {
            activeMonoFamily = Font.MONOSPACED;
        }
    }

    private static Font loadResourceFont(GraphicsEnvironment ge, String path) {
        try (InputStream in = FontManager.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            if (f != null) {
                ge.registerFont(f);
            }
            return f;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Font ui(int sizePt) {
        return ui((float) sizePt, Font.PLAIN);
    }

    public static Font ui(float sizePt, int style) {
        ensureInitialized();
        Font chosen = null;
        if (usingBundledUi) {
            if ((style & Font.BOLD) != 0 && bundledUiBold != null) {
                chosen = bundledUiBold;
            } else if (bundledUiBase != null) {
                chosen = bundledUiBase;
            }
        }
        if (chosen != null) {
            return chosen.deriveFont(style, sizePt);
        }
        return new Font(activeUiFamily, style, Math.round(sizePt));
    }

    public static Font uiMedium(float sizePt) {
        ensureInitialized();
        if (bundledUiMedium != null) {
            return bundledUiMedium.deriveFont(Font.PLAIN, sizePt);
        }
        return ui(sizePt, Font.PLAIN);
    }

    public static Font uiSemiBold(float sizePt) {
        ensureInitialized();
        if (bundledUiSemiBold != null) {
            return bundledUiSemiBold.deriveFont(Font.BOLD, sizePt);
        }
        return ui(sizePt, Font.BOLD);
    }

    public static Font uiSmall() { return ui(11.5f, Font.PLAIN); }
    public static Font uiRegular() { return ui(12.5f, Font.PLAIN); }
    public static Font uiMedium() { return uiMedium(12.5f); }
    public static Font uiSemiBold() { return uiSemiBold(12.5f); }
    public static Font uiBold() { return ui(12.5f, Font.BOLD); }
    public static Font uiTitle() { return uiSemiBold(14.5f); }
    public static Font uiHeading() { return ui(17f, Font.BOLD); }

    public static Font mono(int sizePt) {
        return mono((float) sizePt, Font.PLAIN);
    }

    public static Font mono(float sizePt, int style) {
        ensureInitialized();
        Font chosen = null;
        if (usingBundledMono) {
            if ((style & Font.BOLD) != 0 && bundledMonoBold != null) {
                chosen = bundledMonoBold;
            } else if (bundledMonoBase != null) {
                chosen = bundledMonoBase;
            }
        }
        if (chosen != null) {
            return chosen.deriveFont(style, sizePt);
        }
        return new Font(activeMonoFamily, style, Math.round(sizePt));
    }

    public static Font monoSmall() { return mono(11f, Font.PLAIN); }
    public static Font monoRegular() { return mono(12f, Font.PLAIN); }
    public static Font monoMedium() {
        ensureInitialized();
        if (bundledMonoMedium != null) {
            return bundledMonoMedium.deriveFont(Font.PLAIN, 12f);
        }
        return mono(12f, Font.PLAIN);
    }
    public static Font monoBold() { return mono(12f, Font.BOLD); }

    public static String activeUiFamily() {
        ensureInitialized();
        return activeUiFamily;
    }

    public static String activeMonoFamily() {
        ensureInitialized();
        return activeMonoFamily;
    }

    public static boolean isUsingBundledFonts() {
        ensureInitialized();
        return usingBundledUi || usingBundledMono;
    }
}
