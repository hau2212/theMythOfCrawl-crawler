package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.ui.Theme;
import com.mythofcrawl.crawler.model.AuditNote;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;

/**
 * Legacy compatibility facade delegating all colors to
 * {@link com.mythofcrawl.crawler.ui.theme.Theme} (LAW 1 compliant: zero direct Color allocations).
 */
public final class AuditTheme {

    public static final Color BURP_ORANGE = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, false).solidFill();
    public static final Color BURP_CTA_ORANGE = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, false).solidFill();
    public static final Color BURP_CTA_ORANGE_HOVER = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, false).base();
    public static final Color BURP_ORANGE_LIGHT_TEXT = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, false).text();
    public static final Color BURP_DARK_SELECTION = com.mythofcrawl.crawler.ui.theme.Theme.selection(true);
    public static final Color BURP_LIGHT_SELECTION = com.mythofcrawl.crawler.ui.theme.Theme.selection(false);
    public static final Color BURP_ACTIVE_PILL_BG = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, true).solidFill();
    public static final Color BURP_SWITCH_ON_TRACK = com.mythofcrawl.crawler.ui.theme.Theme.token(
        com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, false).solidFill();
    public static final Color BURP_SWITCH_OFF_DARK = com.mythofcrawl.crawler.ui.theme.Theme.border(true);
    public static final Color BURP_SWITCH_OFF_LIGHT = com.mythofcrawl.crawler.ui.theme.Theme.border(false);

    private AuditTheme() {
    }

    public static boolean isDark(MontoyaApi api, Component comp) {
        if (api != null) {
            try {
                return api.userInterface().currentTheme() == Theme.DARK;
            } catch (Exception ignored) {
            }
        }
        Color bg = comp != null ? comp.getBackground() : null;
        if (bg == null || !comp.isDisplayable()) {
            Color uiBg = UIManager.getColor("Table.background");
            if (uiBg == null) {
                uiBg = UIManager.getColor("Panel.background");
            }
            if (uiBg != null) {
                bg = uiBg;
            }
        }
        if (bg == null) {
            return com.mythofcrawl.crawler.ui.theme.Theme.isDark();
        }
        return com.mythofcrawl.crawler.ui.theme.Theme.relativeLuminance(bg) < 0.25;
    }

    public static boolean isFlatLaf() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        return laf != null && laf.getClass().getName().toLowerCase().contains("flat");
    }

    public static Font displayFont(MontoyaApi api, Component fallback) {
        if (api != null) {
            try {
                Font f = api.userInterface().currentDisplayFont();
                if (f != null) {
                    return f;
                }
            } catch (Exception ignored) {
            }
        }
        if (fallback != null && fallback.getFont() != null) {
            return fallback.getFont();
        }
        return com.mythofcrawl.crawler.ui.theme.FontManager.uiRegular();
    }

    public static Font editorFont(MontoyaApi api, Component fallback) {
        if (api != null) {
            try {
                Font f = api.userInterface().currentEditorFont();
                if (f != null) {
                    return f;
                }
            } catch (Exception ignored) {
            }
        }
        return com.mythofcrawl.crawler.ui.theme.FontManager.monoRegular();
    }

    public static Runnable installLiveThemeListener(Component root, Runnable onThemeChange) {
        if (root == null || onThemeChange == null) {
            return () -> {};
        }
        WeakReference<Component> ref = new WeakReference<>(root);
        PropertyChangeListener[] holder = new PropertyChangeListener[1];
        holder[0] = evt -> {
            String prop = evt.getPropertyName();
            if (!"lookAndFeel".equals(prop) && !"theme".equals(prop)) {
                return;
            }
            Component target = ref.get();
            if (target == null) {
                UIManager.removePropertyChangeListener(holder[0]);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (ref.get() != null) {
                    onThemeChange.run();
                }
            });
        };
        UIManager.addPropertyChangeListener(holder[0]);
        return () -> UIManager.removePropertyChangeListener(holder[0]);
    }

    public static Color contrastForeground(Color bg) {
        return com.mythofcrawl.crawler.ui.theme.Theme.contrastForeground(bg);
    }

    public static Color burpOrange(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, dark).text();
    }

    public static Color burpSelectionBackground(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.selection(dark);
    }

    public static Color burpSelectionForeground(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.selectionText(dark);
    }

    public static Color severityForeground(AuditNote.Severity sev, boolean dark) {
        if (sev == null) {
            return okForeground(dark);
        }
        switch (sev) {
            case ERROR:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.DANGER, dark).text();
            case WARN:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.WARNING, dark).text();
            case INFO:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).text();
            default:
                return okForeground(dark);
        }
    }

    public static Color severityRowTint(AuditNote.Severity sev, Color baseBg, boolean dark) {
        if (sev == null || baseBg == null) {
            return baseBg;
        }
        switch (sev) {
            case ERROR:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.DANGER, dark).fill();
            case WARN:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.WARNING, dark).fill();
            case INFO:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).fill();
            default:
                return baseBg;
        }
    }

    public static Color highlightRowTint(MontoyaApi api, HighlightColor color, Color baseBg, boolean dark) {
        if (color == null || color == HighlightColor.NONE || baseBg == null) {
            return baseBg;
        }
        Color raw = highlightBadgeColor(api, color, dark);
        if (raw == null) {
            return baseBg;
        }
        return blend(baseBg, raw, dark ? 0.26f : 0.32f);
    }

    public static Color highlightBadgeColor(MontoyaApi api, HighlightColor color, boolean dark) {
        if (color == null || color == HighlightColor.NONE) {
            return null;
        }
        if (api != null) {
            try {
                Color c = api.userInterface().swingUtils().colorForHighLight(color);
                if (c != null) {
                    return c;
                }
            } catch (Exception ignored) {
            }
        }
        return fallbackHighlightColor(color, dark);
    }

    private static Color fallbackHighlightColor(HighlightColor color, boolean dark) {
        switch (color) {
            case RED:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.DANGER, dark).fillStrong();
            case ORANGE:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.PENDING, dark).fillStrong();
            case YELLOW:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.WARNING, dark).fillStrong();
            case GREEN:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.SUCCESS, dark).fillStrong();
            case CYAN:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.ORIG, dark).fillStrong();
            case BLUE:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).fillStrong();
            case PINK:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.CRITICAL, dark).fillStrong();
            case MAGENTA:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.DUP, dark).fillStrong();
            case GRAY:
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.NEUTRAL, dark).fillStrong();
            default:
                return null;
        }
    }

    public static Color okForeground(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.SUCCESS, dark).text();
    }

    public static Color mutedForeground(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.textSecondary(dark);
    }

    public static Color accentForeground(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.ACCENT, dark).text();
    }

    public static Color methodForeground(String method, boolean dark) {
        if (method == null) {
            return mutedForeground(dark);
        }
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.forMethodToken(method), dark).text();
    }

    public static Color statusCodeForeground(int status, boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.forStatusToken(status), dark).text();
    }

    public static Color canvasSurface(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.canvas(dark);
    }

    public static Color panelSurface(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.surface(dark);
    }

    public static Color cardSurface(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.surfaceAlt(dark);
    }

    public static Color tableSurface(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.surface(dark);
    }

    public static Color borderColor(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.border(dark);
    }

    public static Color textPrimary(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.text(dark);
    }

    public static Color zebraRowBackground(Color baseBg, int viewRow, boolean dark) {
        return viewRow % 2 == 0
            ? com.mythofcrawl.crawler.ui.theme.Theme.surface(dark)
            : com.mythofcrawl.crawler.ui.theme.Theme.surfaceAlt(dark);
    }

    public static Color blend(Color a, Color b, float ratioB) {
        return com.mythofcrawl.crawler.ui.theme.Theme.blend(a, b, ratioB);
    }

    public static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Border cellPaddingBorder() {
        return BorderFactory.createEmptyBorder(0, 7, 0, 7);
    }

    public static void configureAuditTable(JTable table) {
        configureAuditTable(table, null);
    }

    public static void configureAuditTable(JTable table, MontoyaApi api) {
        boolean dark = isDark(api, table);
        Font disp = displayFont(api, table);
        table.setFont(disp);
        table.setRowHeight(Math.max(com.mythofcrawl.crawler.ui.theme.Theme.ROW_TABLE, disp.getSize() + 12));
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setBackground(tableSurface(dark));
        table.setForeground(textPrimary(dark));
        table.setSelectionBackground(burpSelectionBackground(dark));
        table.setSelectionForeground(burpSelectionForeground(dark));
        table.setGridColor(borderColor(dark));
        if (table.getTableHeader() != null) {
            table.getTableHeader().setBackground(cardSurface(dark));
            table.getTableHeader().setForeground(textPrimary(dark));
            table.getTableHeader().setFont(disp.deriveFont(Font.BOLD));
        }
    }

    public static void styleStatChip(JLabel label, Color fg, Color bg) {
        label.setOpaque(true);
        label.setForeground(fg);
        label.setBackground(bg);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(blend(bg, fg, 0.35f), 1, true),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)));
    }

    public static Color pastelActiveBg(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).fill();
    }

    public static Color pastelActiveBorder(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).border();
    }

    public static Color pastelActiveFg(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.token(
            com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).text();
    }

    public static Color neutralButtonBg(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.surfaceAlt(dark);
    }

    public static Color neutralButtonBorder(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.border(dark);
    }

    public static Color neutralButtonFg(boolean dark) {
        return com.mythofcrawl.crawler.ui.theme.Theme.text(dark);
    }

    public static void styleTogglePill(AbstractButton btn, boolean active, Color accentFg, boolean dark) {
        Color bg;
        Color fg;
        Color border;
        if (active) {
            if (accentFg != null && !accentFg.equals(accentForeground(dark))) {
                bg = blend(panelSurface(dark), accentFg, dark ? 0.28f : 0.14f);
                fg = dark ? com.mythofcrawl.crawler.ui.theme.Theme.inkWhite()
                    : blend(accentFg, com.mythofcrawl.crawler.ui.theme.Theme.inkDark(), 0.30f);
                border = dark ? accentFg : blend(com.mythofcrawl.crawler.ui.theme.Theme.inkWhite(), accentFg, 0.45f);
            } else {
                bg = pastelActiveBg(dark);
                fg = pastelActiveFg(dark);
                border = pastelActiveBorder(dark);
            }
        } else {
            bg = neutralButtonBg(dark);
            fg = neutralButtonFg(dark);
            border = neutralButtonBorder(dark);
        }
        applyButtonStyle(btn, bg, fg, border, active);
    }

    public static void stylePlainButton(AbstractButton btn, boolean dark) {
        styleNeutralActionButton(btn, dark);
    }

    public static void styleNeutralActionButton(AbstractButton btn, boolean dark) {
        applyButtonStyle(btn, neutralButtonBg(dark), neutralButtonFg(dark), neutralButtonBorder(dark), false);
    }

    public static void stylePrimaryActionButton(AbstractButton btn, Color accentFg, boolean dark) {
        applyButtonStyle(btn, BURP_CTA_ORANGE, com.mythofcrawl.crawler.ui.theme.Theme.inkWhite(), BURP_CTA_ORANGE, true);
    }

    private static void applyButtonStyle(
        AbstractButton btn, Color bg, Color fg, Color border, boolean bold
    ) {
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(true);
        btn.setOpaque(true);
        btn.setForeground(fg);
        btn.setBackground(bg);
        if (btn.getFont() != null) {
            btn.setFont(btn.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN));
        }
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border, 1, true),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        btn.repaint();
    }

    public static void styleSearchField(JTextField field, String placeholder) {
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
    }

    public static void styleActionCard(JComponent comp, boolean dark) {
        comp.setOpaque(true);
        comp.setBackground(panelSurface(dark));
        comp.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(6, 9, 6, 9)));
    }

    public static void styleSlimToolbar(JComponent comp, boolean dark) {
        comp.setOpaque(true);
        comp.setBackground(panelSurface(dark));
        comp.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor(dark)),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)));
    }

    public static void styleBurpTabbedPane(JTabbedPane tabs, boolean dark) {
        if (tabs == null) {
            return;
        }
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        com.mythofcrawl.crawler.ui.theme.Ui.styleTabbedPane(tabs);
    }

    public static void styleTitledBorder(JComponent comp, String title, boolean dark) {
        TitledBorder tb = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(borderColor(dark), 1, true), title);
        tb.setTitleColor(textPrimary(dark));
        comp.setBorder(tb);
    }

    public static void styleScrollPane(JScrollPane sp, boolean dark) {
        if (sp == null) {
            return;
        }
        sp.getViewport().setBackground(tableSurface(dark));
        sp.setBorder(BorderFactory.createLineBorder(borderColor(dark), 1));
    }
}
