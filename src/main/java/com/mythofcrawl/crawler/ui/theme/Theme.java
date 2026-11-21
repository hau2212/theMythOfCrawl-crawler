package com.mythofcrawl.crawler.ui.theme;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single Color, Spacing & WCAG AA Design System Source of Truth (Wave A - V_1.0_beta).
 * <p>
 * LAW 1: This is the ONLY file under ui/ allowed to construct {@code new Color(...)}.
 * Every {@link Token} provides all 7 Light and Dark variants ({@code base}, {@code fill},
 * {@code fillStrong}, {@code border}, {@code text}, {@code solidFill}, {@code onSolid})
 * tuned for >= 4.5:1 WCAG AA contrast across all 4 base surfaces.
 */
public final class Theme {

    // Spacing scale
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_6 = 24;

    // Row heights tuned for Vietnamese diacritics
    public static final int ROW_TABLE = 26;
    public static final int ROW_TREE = 24;

    // Corner radii
    public static final int RADIUS_SM = 4;
    public static final int RADIUS_MD = 6;
    public static final int RADIUS_PILL = 999;

    public enum Token {
        SUCCESS,
        WARNING,
        DANGER,
        CRITICAL,
        INFO,
        PENDING,
        DUP,
        GHOST,
        ACCENT,
        ORIG,
        NEUTRAL,
        METHOD_GET,
        METHOD_POST,
        METHOD_PUT,
        METHOD_PATCH,
        METHOD_DELETE,
        METHOD_HEAD,
        METHOD_OPTIONS,
        DEPTH_0,
        DEPTH_1,
        DEPTH_2,
        DEPTH_3
    }

    public static final class PaletteEntry {
        private final Color base;
        private final Color fill;
        private final Color fillStrong;
        private final Color border;
        private final Color text;
        private final Color solidFill;
        private final Color onSolid;

        private PaletteEntry(
            Color base,
            Color fill,
            Color fillStrong,
            Color border,
            Color text,
            Color solidFill,
            Color onSolid
        ) {
            this.base = base;
            this.fill = fill;
            this.fillStrong = fillStrong;
            this.border = border;
            this.text = text;
            this.solidFill = solidFill;
            this.onSolid = onSolid;
        }

        public Color base() { return base; }
        public Color fill() { return fill; }
        public Color fillStrong() { return fillStrong; }
        public Color border() { return border; }
        public Color text() { return text; }
        public Color solidFill() { return solidFill; }
        public Color onSolid() { return onSolid; }
    }

    // Core Light palette (strictly WCAG AA)
    private static final Color LIGHT_CANVAS = new Color(0xF4, 0xF5, 0xF7);
    private static final Color LIGHT_SURFACE = new Color(0xFF, 0xFF, 0xFF);
    private static final Color LIGHT_SURFACE_ALT = new Color(0xED, 0xF0, 0xF5);
    private static final Color LIGHT_SURFACE_HOVER = new Color(0xE5, 0xE9, 0xF0);
    private static final Color LIGHT_SELECTION = new Color(0xDC, 0xEB, 0xFC);
    private static final Color LIGHT_SELECTION_TEXT = new Color(0x11, 0x18, 0x27);
    private static final Color LIGHT_BORDER = new Color(0xD0, 0xD5, 0xDD);
    private static final Color LIGHT_BORDER_SUBTLE = new Color(0xE4, 0xE7, 0xEC);
    private static final Color LIGHT_TEXT = new Color(0x11, 0x18, 0x27);
    private static final Color LIGHT_TEXT_SECONDARY = new Color(0x4B, 0x55, 0x63);
    private static final Color LIGHT_FOCUS_RING = new Color(0x1D, 0x4E, 0xD8);
    private static final Color LIGHT_BACKDROP = new Color(0x11, 0x18, 0x27, 0x44);

    // Core Dark palette (strictly WCAG AA)
    private static final Color DARK_CANVAS = new Color(0x1E, 0x20, 0x24);
    private static final Color DARK_SURFACE = new Color(0x26, 0x29, 0x2E);
    private static final Color DARK_SURFACE_ALT = new Color(0x2E, 0x32, 0x38);
    private static final Color DARK_SURFACE_HOVER = new Color(0x35, 0x3A, 0x42);
    private static final Color DARK_SELECTION = new Color(0x23, 0x45, 0x7A);
    private static final Color DARK_SELECTION_TEXT = new Color(0xF8, 0xFA, 0xFC);
    private static final Color DARK_BORDER = new Color(0x3E, 0x44, 0x4D);
    private static final Color DARK_BORDER_SUBTLE = new Color(0x32, 0x37, 0x3F);
    private static final Color DARK_TEXT = new Color(0xF3, 0xF4, 0xF6);
    private static final Color DARK_TEXT_SECONDARY = new Color(0xB6, 0xBC, 0xC6);
    private static final Color DARK_FOCUS_RING = new Color(0x60, 0xA5, 0xFA);
    private static final Color DARK_BACKDROP = new Color(0x00, 0x00, 0x00, 0x78);

    private static final Color INK_DARK = new Color(0x11, 0x18, 0x27);
    private static final Color INK_WHITE = new Color(0xFF, 0xFF, 0xFF);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private static final Map<Token, PaletteEntry> LIGHT_TOKENS = new EnumMap<>(Token.class);
    private static final Map<Token, PaletteEntry> DARK_TOKENS = new EnumMap<>(Token.class);

    static {
        // Light tokens: fill luminance >= 0.80, text luminance <= 0.095, solidFill luminance <= 0.16 (with white)
        putPair(Token.SUCCESS,
            new Color(0x15, 0x80, 0x3D), new Color(0xEB, 0xFA, 0xF0), new Color(0xD3, 0xF4, 0xE0),
            new Color(0x86, 0xEF, 0xAC), new Color(0x0F, 0x51, 0x32), new Color(0x15, 0x80, 0x3D), INK_WHITE,
            new Color(0x4A, 0xDE, 0x80), new Color(0x12, 0x29, 0x1D), new Color(0x19, 0x38, 0x28),
            new Color(0x2A, 0x62, 0x44), new Color(0x86, 0xEF, 0xAC), new Color(0x15, 0x80, 0x3D), INK_WHITE);

        putPair(Token.WARNING,
            new Color(0xA1, 0x4A, 0x05), new Color(0xFE, 0xF6, 0xE6), new Color(0xFC, 0xEA, 0xC8),
            new Color(0xF5, 0xB8, 0x66), new Color(0x78, 0x35, 0x0F), new Color(0xA1, 0x4A, 0x05), INK_WHITE,
            new Color(0xFB, 0xBF, 0x24), new Color(0x2B, 0x21, 0x12), new Color(0x39, 0x2B, 0x16),
            new Color(0x78, 0x56, 0x22), new Color(0xFC, 0xD3, 0x4D), new Color(0x92, 0x40, 0x0E), INK_WHITE);

        putPair(Token.DANGER,
            new Color(0xB9, 0x1C, 0x1C), new Color(0xFE, 0xEC, 0xEC), new Color(0xFC, 0xD7, 0xD7),
            new Color(0xF5, 0x98, 0x98), new Color(0x88, 0x13, 0x13), new Color(0xB9, 0x1C, 0x1C), INK_WHITE,
            new Color(0xF8, 0x71, 0x71), new Color(0x2D, 0x16, 0x19), new Color(0x3D, 0x1D, 0x21),
            new Color(0x7F, 0x2D, 0x33), new Color(0xFC, 0xA5, 0xA5), new Color(0xB9, 0x1C, 0x1C), INK_WHITE);

        putPair(Token.CRITICAL,
            new Color(0x7F, 0x1D, 0x1D), new Color(0xFC, 0xE7, 0xEA), new Color(0xF8, 0xCE, 0xD4),
            new Color(0xE8, 0x8B, 0x97), new Color(0x6B, 0x10, 0x1B), new Color(0x7F, 0x1D, 0x1D), INK_WHITE,
            new Color(0xFB, 0x71, 0x85), new Color(0x2E, 0x12, 0x18), new Color(0x3F, 0x18, 0x20),
            new Color(0x88, 0x2A, 0x3A), new Color(0xFD, 0xA4, 0xAF), new Color(0x9F, 0x12, 0x39), INK_WHITE);

        putPair(Token.INFO,
            new Color(0x03, 0x69, 0xA1), new Color(0xEC, 0xF7, 0xFE), new Color(0xD6, 0xEE, 0xFC),
            new Color(0x7D, 0xC8, 0xF0), new Color(0x0C, 0x4A, 0x6E), new Color(0x03, 0x69, 0xA1), INK_WHITE,
            new Color(0x38, 0xBD, 0xF8), new Color(0x13, 0x25, 0x33), new Color(0x1A, 0x33, 0x46),
            new Color(0x2B, 0x5E, 0x82), new Color(0x7D, 0xD3, 0xFC), new Color(0x03, 0x69, 0xA1), INK_WHITE);

        putPair(Token.PENDING,
            new Color(0xB4, 0x4B, 0x09), new Color(0xFF, 0xF4, 0xEB), new Color(0xFE, 0xE4, 0xD2),
            new Color(0xF6, 0xB2, 0x84), new Color(0x7C, 0x2D, 0x12), new Color(0xB8, 0x4A, 0x0E), INK_WHITE,
            new Color(0xFB, 0x92, 0x3C), new Color(0x2C, 0x1D, 0x14), new Color(0x3B, 0x26, 0x19),
            new Color(0x7C, 0x4A, 0x29), new Color(0xFD, 0xBA, 0x74), new Color(0xB8, 0x4A, 0x0E), INK_WHITE);

        putPair(Token.DUP,
            new Color(0x6D, 0x28, 0xD9), new Color(0xF5, 0xF0, 0xFF), new Color(0xE9, 0xDF, 0xFE),
            new Color(0xC4, 0xB5, 0xFD), new Color(0x4C, 0x1D, 0x95), new Color(0x6D, 0x28, 0xD9), INK_WHITE,
            new Color(0xA7, 0x8B, 0xFA), new Color(0x21, 0x1B, 0x35), new Color(0x2C, 0x23, 0x47),
            new Color(0x55, 0x41, 0x8C), new Color(0xC4, 0xB5, 0xFD), new Color(0x6D, 0x28, 0xD9), INK_WHITE);

        putPair(Token.GHOST,
            new Color(0x47, 0x55, 0x69), new Color(0xF1, 0xF5, 0xF9), new Color(0xE2, 0xE8, 0xF0),
            new Color(0xCB, 0xD5, 0xE1), new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69), INK_WHITE,
            new Color(0x94, 0xA3, 0xB8), new Color(0x21, 0x25, 0x2D), new Color(0x2B, 0x30, 0x3B),
            new Color(0x47, 0x55, 0x69), new Color(0xCB, 0xD5, 0xE1), new Color(0x47, 0x55, 0x69), INK_WHITE);

        putPair(Token.ACCENT,
            new Color(0xB8, 0x4A, 0x0E), new Color(0xFF, 0xF3, 0xEB), new Color(0xFD, 0xE2, 0xCF),
            new Color(0xF3, 0xA9, 0x7A), new Color(0x7C, 0x2D, 0x12), new Color(0xB8, 0x4A, 0x0E), INK_WHITE,
            new Color(0xFF, 0x8A, 0x4C), new Color(0x2D, 0x1C, 0x14), new Color(0x3D, 0x24, 0x19),
            new Color(0x80, 0x47, 0x28), new Color(0xFD, 0xBA, 0x74), new Color(0xB8, 0x4A, 0x0E), INK_WHITE);

        putPair(Token.ORIG,
            new Color(0x0F, 0x76, 0x6E), new Color(0xEC, 0xFD, 0xFA), new Color(0xD2, 0xF7, 0xF0),
            new Color(0x7E, 0xD9, 0xCD), new Color(0x11, 0x5E, 0x59), new Color(0x0F, 0x76, 0x6E), INK_WHITE,
            new Color(0x2D, 0xD4, 0xBF), new Color(0x12, 0x29, 0x28), new Color(0x18, 0x37, 0x35),
            new Color(0x28, 0x64, 0x5E), new Color(0x5E, 0xEA, 0xD4), new Color(0x0F, 0x76, 0x6E), INK_WHITE);

        putPair(Token.NEUTRAL,
            new Color(0x4B, 0x55, 0x63), new Color(0xF3, 0xF4, 0xF6), new Color(0xE5, 0xE7, 0xEB),
            new Color(0xD1, 0xD5, 0xDB), new Color(0x1F, 0x29, 0x37), new Color(0x37, 0x41, 0x51), INK_WHITE,
            new Color(0x9C, 0xA3, 0xAF), new Color(0x27, 0x2A, 0x30), new Color(0x32, 0x36, 0x3E),
            new Color(0x4B, 0x55, 0x63), new Color(0xE5, 0xE7, 0xEB), new Color(0x4B, 0x55, 0x63), INK_WHITE);

        // HTTP Method tokens
        putPair(Token.METHOD_GET,
            new Color(0x1D, 0x4E, 0xD8), new Color(0xEF, 0xF6, 0xFF), new Color(0xDC, 0xEB, 0xFE),
            new Color(0x93, 0xC5, 0xFD), new Color(0x1E, 0x3A, 0x8A), new Color(0x1D, 0x4E, 0xD8), INK_WHITE,
            new Color(0x60, 0xA5, 0xFA), new Color(0x14, 0x22, 0x38), new Color(0x1C, 0x30, 0x4D),
            new Color(0x2E, 0x56, 0x8C), new Color(0x93, 0xC5, 0xFD), new Color(0x1D, 0x4E, 0xD8), INK_WHITE);

        putPair(Token.METHOD_POST,
            new Color(0x15, 0x80, 0x3D), new Color(0xEC, 0xFD, 0xF5), new Color(0xD1, 0xFA, 0xE5),
            new Color(0x6E, 0xE7, 0xB7), new Color(0x06, 0x5F, 0x46), new Color(0x15, 0x80, 0x3D), INK_WHITE,
            new Color(0x34, 0xD3, 0x99), new Color(0x12, 0x29, 0x1F), new Color(0x19, 0x38, 0x2A),
            new Color(0x27, 0x63, 0x48), new Color(0x6E, 0xE7, 0xB7), new Color(0x15, 0x80, 0x3D), INK_WHITE);

        putPair(Token.METHOD_PUT,
            new Color(0xA1, 0x4A, 0x05), new Color(0xFF, 0xF7, 0xED), new Color(0xFE, 0xEA, 0xD2),
            new Color(0xFD, 0xBA, 0x74), new Color(0x7C, 0x2D, 0x12), new Color(0xA1, 0x4A, 0x05), INK_WHITE,
            new Color(0xFB, 0xBF, 0x24), new Color(0x2B, 0x21, 0x12), new Color(0x39, 0x2B, 0x16),
            new Color(0x78, 0x56, 0x22), new Color(0xFC, 0xD3, 0x4D), new Color(0x92, 0x40, 0x0E), INK_WHITE);

        putPair(Token.METHOD_PATCH,
            new Color(0x0F, 0x76, 0x6E), new Color(0xEC, 0xFD, 0xFA), new Color(0xCC, 0xFB, 0xF1),
            new Color(0x5E, 0xEA, 0xD4), new Color(0x11, 0x5E, 0x59), new Color(0x0F, 0x76, 0x6E), INK_WHITE,
            new Color(0x2D, 0xD4, 0xBF), new Color(0x12, 0x29, 0x28), new Color(0x18, 0x37, 0x35),
            new Color(0x28, 0x64, 0x5E), new Color(0x5E, 0xEA, 0xD4), new Color(0x0F, 0x76, 0x6E), INK_WHITE);

        putPair(Token.METHOD_DELETE,
            new Color(0xB9, 0x1C, 0x1C), new Color(0xFE, 0xF2, 0xF2), new Color(0xFE, 0xE2, 0xE2),
            new Color(0xFC, 0xA5, 0xA5), new Color(0x7F, 0x1D, 0x1D), new Color(0xB9, 0x1C, 0x1C), INK_WHITE,
            new Color(0xF8, 0x71, 0x71), new Color(0x2D, 0x16, 0x19), new Color(0x3D, 0x1D, 0x21),
            new Color(0x7F, 0x2D, 0x33), new Color(0xFC, 0xA5, 0xA5), new Color(0xB9, 0x1C, 0x1C), INK_WHITE);

        putPair(Token.METHOD_HEAD,
            new Color(0x43, 0x38, 0xCA), new Color(0xEE, 0xF2, 0xFF), new Color(0xE0, 0xE7, 0xFF),
            new Color(0xA5, 0xB4, 0xFC), new Color(0x31, 0x2E, 0x81), new Color(0x43, 0x38, 0xCA), INK_WHITE,
            new Color(0x81, 0x8C, 0xF8), new Color(0x1A, 0x1C, 0x34), new Color(0x23, 0x26, 0x45),
            new Color(0x45, 0x4C, 0x8C), new Color(0xA5, 0xB4, 0xFC), new Color(0x43, 0x38, 0xCA), INK_WHITE);

        putPair(Token.METHOD_OPTIONS,
            new Color(0x4B, 0x55, 0x63), new Color(0xF3, 0xF4, 0xF6), new Color(0xE5, 0xE7, 0xEB),
            new Color(0xD1, 0xD5, 0xDB), new Color(0x37, 0x41, 0x51), new Color(0x4B, 0x55, 0x63), INK_WHITE,
            new Color(0x9C, 0xA3, 0xAF), new Color(0x24, 0x27, 0x2D), new Color(0x2F, 0x33, 0x3B),
            new Color(0x4B, 0x55, 0x63), new Color(0xD1, 0xD5, 0xDB), new Color(0x4B, 0x55, 0x63), INK_WHITE);

        // Tree depth stripe tokens
        putPair(Token.DEPTH_0,
            new Color(0x1D, 0x4E, 0xD8), new Color(0xEF, 0xF6, 0xFF), new Color(0xDC, 0xEB, 0xFE),
            new Color(0x93, 0xC5, 0xFD), new Color(0x1E, 0x3A, 0x8A), new Color(0x1D, 0x4E, 0xD8), INK_WHITE,
            new Color(0x60, 0xA5, 0xFA), new Color(0x14, 0x22, 0x38), new Color(0x1C, 0x30, 0x4D),
            new Color(0x2E, 0x56, 0x8C), new Color(0x93, 0xC5, 0xFD), new Color(0x1D, 0x4E, 0xD8), INK_WHITE);

        putPair(Token.DEPTH_1,
            new Color(0x0F, 0x76, 0x6E), new Color(0xEC, 0xFD, 0xFA), new Color(0xD2, 0xF7, 0xF0),
            new Color(0x7E, 0xD9, 0xCD), new Color(0x11, 0x5E, 0x59), new Color(0x0F, 0x76, 0x6E), INK_WHITE,
            new Color(0x2D, 0xD4, 0xBF), new Color(0x12, 0x29, 0x28), new Color(0x18, 0x37, 0x35),
            new Color(0x28, 0x64, 0x5E), new Color(0x5E, 0xEA, 0xD4), new Color(0x0F, 0x76, 0x6E), INK_WHITE);

        putPair(Token.DEPTH_2,
            new Color(0x6D, 0x28, 0xD9), new Color(0xF5, 0xF0, 0xFF), new Color(0xE9, 0xDF, 0xFE),
            new Color(0xC4, 0xB5, 0xFD), new Color(0x4C, 0x1D, 0x95), new Color(0x6D, 0x28, 0xD9), INK_WHITE,
            new Color(0xA7, 0x8B, 0xFA), new Color(0x21, 0x1B, 0x35), new Color(0x2C, 0x23, 0x47),
            new Color(0x55, 0x41, 0x8C), new Color(0xC4, 0xB5, 0xFD), new Color(0x6D, 0x28, 0xD9), INK_WHITE);

        putPair(Token.DEPTH_3,
            new Color(0xB8, 0x4A, 0x0E), new Color(0xFF, 0xF3, 0xEB), new Color(0xFD, 0xE2, 0xCF),
            new Color(0xF3, 0xA9, 0x7A), new Color(0x7C, 0x2D, 0x12), new Color(0xB8, 0x4A, 0x0E), INK_WHITE,
            new Color(0xFF, 0x8A, 0x4C), new Color(0x2D, 0x1C, 0x14), new Color(0x3D, 0x24, 0x19),
            new Color(0x80, 0x47, 0x28), new Color(0xFD, 0xBA, 0x74), new Color(0xB8, 0x4A, 0x0E), INK_WHITE);
    }

    private static volatile boolean darkMode = detectFromUiManager();
    private static final List<WeakReference<Runnable>> THEME_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> STRONG_LISTENERS = new CopyOnWriteArrayList<>();

    static {
        UIManager.addPropertyChangeListener(evt -> {
            String prop = evt.getPropertyName();
            if ("lookAndFeel".equals(prop) || "theme".equals(prop)) {
                boolean detected = detectFromUiManager();
                if (detected != darkMode) {
                    setDark(detected);
                } else {
                    notifyListeners();
                }
            }
        });
    }

    private Theme() {
    }

    private static void putPair(
        Token tok,
        Color lBase, Color lFill, Color lFillStrong, Color lBorder, Color lText, Color lSolid, Color lOnSolid,
        Color dBase, Color dFill, Color dFillStrong, Color dBorder, Color dText, Color dSolid, Color dOnSolid
    ) {
        LIGHT_TOKENS.put(tok, new PaletteEntry(lBase, lFill, lFillStrong, lBorder, lText, lSolid, lOnSolid));
        DARK_TOKENS.put(tok, new PaletteEntry(dBase, dFill, dFillStrong, dBorder, dText, dSolid, dOnSolid));
    }

    public static boolean isDark() {
        return darkMode;
    }

    public static void setDark(boolean dark) {
        if (darkMode != dark) {
            darkMode = dark;
            notifyListeners();
        }
    }

    public static boolean detectFromUiManager() {
        Color bg = UIManager.getColor("Table.background");
        if (bg == null) {
            bg = UIManager.getColor("Panel.background");
        }
        return bg != null && relativeLuminance(bg) < 0.25;
    }

    public static void syncFromComponent(Component comp) {
        if (comp == null) {
            return;
        }
        Color bg = comp.getBackground();
        if (bg == null) {
            bg = UIManager.getColor("Panel.background");
        }
        if (bg != null) {
            setDark(relativeLuminance(bg) < 0.25);
        }
    }

    public static AutoCloseable onThemeChange(Runnable listener) {
        if (listener == null) {
            return () -> {};
        }
        STRONG_LISTENERS.add(listener);
        return () -> STRONG_LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        Runnable fire = () -> {
            for (Runnable r : STRONG_LISTENERS) {
                try {
                    r.run();
                } catch (Exception ignored) {
                }
            }
            for (WeakReference<Runnable> ref : THEME_LISTENERS) {
                Runnable r = ref.get();
                if (r != null) {
                    try {
                        r.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        };
        fire.run();
    }

    public static Color canvas() { return canvas(darkMode); }
    public static Color canvas(boolean dark) { return dark ? DARK_CANVAS : LIGHT_CANVAS; }

    public static Color surface() { return surface(darkMode); }
    public static Color surface(boolean dark) { return dark ? DARK_SURFACE : LIGHT_SURFACE; }

    public static Color surfaceAlt() { return surfaceAlt(darkMode); }
    public static Color surfaceAlt(boolean dark) { return dark ? DARK_SURFACE_ALT : LIGHT_SURFACE_ALT; }

    public static Color surfaceHover() { return surfaceHover(darkMode); }
    public static Color surfaceHover(boolean dark) { return dark ? DARK_SURFACE_HOVER : LIGHT_SURFACE_HOVER; }

    public static Color selection() { return selection(darkMode); }
    public static Color selection(boolean dark) { return dark ? DARK_SELECTION : LIGHT_SELECTION; }

    public static Color selectionText() { return selectionText(darkMode); }
    public static Color selectionText(boolean dark) { return dark ? DARK_SELECTION_TEXT : LIGHT_SELECTION_TEXT; }

    public static Color border() { return border(darkMode); }
    public static Color border(boolean dark) { return dark ? DARK_BORDER : LIGHT_BORDER; }

    public static Color borderSubtle() { return borderSubtle(darkMode); }
    public static Color borderSubtle(boolean dark) { return dark ? DARK_BORDER_SUBTLE : LIGHT_BORDER_SUBTLE; }

    public static Color text() { return text(darkMode); }
    public static Color text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }

    public static Color textSecondary() { return textSecondary(darkMode); }
    public static Color textSecondary(boolean dark) { return dark ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY; }

    public static Color muted() { return textSecondary(darkMode); }
    public static Color muted(boolean dark) { return textSecondary(dark); }

    public static Color focusRing() { return focusRing(darkMode); }
    public static Color focusRing(boolean dark) { return dark ? DARK_FOCUS_RING : LIGHT_FOCUS_RING; }

    public static Color backdrop() { return backdrop(darkMode); }
    public static Color backdrop(boolean dark) { return dark ? DARK_BACKDROP : LIGHT_BACKDROP; }

    public static Color transparent() { return TRANSPARENT; }
    public static Color inkWhite() { return INK_WHITE; }
    public static Color inkDark() { return INK_DARK; }

    public static PaletteEntry token(Token t) {
        return token(t, darkMode);
    }

    public static PaletteEntry token(Token t, boolean dark) {
        Token key = t != null ? t : Token.NEUTRAL;
        return (dark ? DARK_TOKENS : LIGHT_TOKENS).get(key);
    }

    public static Token forMethodToken(String method) {
        if (method == null) {
            return Token.NEUTRAL;
        }
        switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "GET": return Token.METHOD_GET;
            case "POST": return Token.METHOD_POST;
            case "PUT": return Token.METHOD_PUT;
            case "PATCH": return Token.METHOD_PATCH;
            case "DELETE": return Token.METHOD_DELETE;
            case "HEAD": return Token.METHOD_HEAD;
            case "OPTIONS": return Token.METHOD_OPTIONS;
            default: return Token.INFO;
        }
    }

    public static PaletteEntry forMethod(String method) {
        return token(forMethodToken(method));
    }

    public static Token forDepthToken(int depth) {
        int d = Math.max(0, depth) % 4;
        switch (d) {
            case 0: return Token.DEPTH_0;
            case 1: return Token.DEPTH_1;
            case 2: return Token.DEPTH_2;
            default: return Token.DEPTH_3;
        }
    }

    public static PaletteEntry forDepth(int depth) {
        return token(forDepthToken(depth));
    }

    public static Token forStatusToken(int status) {
        if (status <= 0) {
            return Token.PENDING;
        }
        if (status >= 500 || status == 401 || status == 403) {
            return Token.CRITICAL;
        }
        if (status >= 400) {
            return Token.WARNING;
        }
        if (status >= 300) {
            return Token.INFO;
        }
        if (status >= 200) {
            return Token.SUCCESS;
        }
        return Token.NEUTRAL;
    }

    public static PaletteEntry forStatus(int status) {
        return token(forStatusToken(status));
    }

    public static Color alpha(Color c, int alpha0to255) {
        Color base = c != null ? c : INK_DARK;
        int a = Math.max(0, Math.min(255, alpha0to255));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
    }

    public static Color blend(Color a, Color b, float ratioB) {
        Color ca = a != null ? a : INK_DARK;
        Color cb = b != null ? b : INK_WHITE;
        float rb = Math.max(0f, Math.min(1f, ratioB));
        float ra = 1.0f - rb;
        return new Color(
            clamp(Math.round(ca.getRed() * ra + cb.getRed() * rb)),
            clamp(Math.round(ca.getGreen() * ra + cb.getGreen() * rb)),
            clamp(Math.round(ca.getBlue() * ra + cb.getBlue() * rb)));
    }

    public static Color contrastForeground(Color bg) {
        if (bg == null) {
            return INK_WHITE;
        }
        double rDark = contrastRatio(INK_DARK, bg);
        double rWhite = contrastRatio(INK_WHITE, bg);
        return rDark >= rWhite ? INK_DARK : INK_WHITE;
    }

    public static double relativeLuminance(Color c) {
        if (c == null) {
            return 0.0;
        }
        double r = linearizeChannel(c.getRed() / 255.0);
        double g = linearizeChannel(c.getGreen() / 255.0);
        double b = linearizeChannel(c.getBlue() / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    public static double contrastRatio(Color fg, Color bg) {
        double l1 = relativeLuminance(fg);
        double l2 = relativeLuminance(bg);
        double bright = Math.max(l1, l2);
        double dark = Math.min(l1, l2);
        return (bright + 0.05) / (dark + 0.05);
    }

    private static double linearizeChannel(double c) {
        return c <= 0.03928 ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
