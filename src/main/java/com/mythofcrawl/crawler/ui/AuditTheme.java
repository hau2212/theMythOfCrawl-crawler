package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.Theme;
import com.mythofcrawl.crawler.model.AuditNote;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * Eye-friendly semantic color palette & table padding helper that adapts smoothly
 * to both Burp Dark Mode and Burp Light Mode.
 */
public final class AuditTheme {

    private AuditTheme() {
    }

    public static boolean isDark(MontoyaApi api, Component comp) {
        if (api != null) {
            try {
                return api.userInterface().currentTheme() == Theme.DARK;
            } catch (Exception ignored) {
            }
        }
        Color bg = comp != null ? comp.getBackground() : UIManager.getColor("Table.background");
        if (bg == null) {
            return false;
        }
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
        return luminance < 0.45;
    }

    public static Color severityForeground(AuditNote.Severity sev, boolean dark) {
        if (sev == null) {
            return okForeground(dark);
        }
        switch (sev) {
            case ERROR:
                return dark ? new Color(248, 113, 113) : new Color(185, 28, 28);
            case WARN:
                return dark ? new Color(251, 191, 36) : new Color(180, 83, 9);
            case INFO:
                return dark ? new Color(96, 165, 250) : new Color(29, 78, 216);
            default:
                return okForeground(dark);
        }
    }

    public static Color severityRowTint(AuditNote.Severity sev, Color baseBg, boolean dark) {
        if (sev == null || baseBg == null) {
            return baseBg;
        }
        Color accent;
        switch (sev) {
            case ERROR:
                accent = dark ? new Color(127, 29, 29) : new Color(254, 226, 226);
                return blend(baseBg, accent, dark ? 0.28f : 0.42f);
            case WARN:
                accent = dark ? new Color(120, 53, 15) : new Color(254, 243, 199);
                return blend(baseBg, accent, dark ? 0.24f : 0.40f);
            case INFO:
                accent = dark ? new Color(30, 58, 138) : new Color(219, 234, 254);
                return blend(baseBg, accent, dark ? 0.20f : 0.34f);
            default:
                return baseBg;
        }
    }

    public static Color okForeground(boolean dark) {
        return dark ? new Color(74, 222, 128) : new Color(21, 128, 61);
    }

    public static Color mutedForeground(boolean dark) {
        return dark ? new Color(156, 163, 175) : new Color(107, 114, 128);
    }

    public static Color accentForeground(boolean dark) {
        return dark ? new Color(103, 232, 249) : new Color(15, 118, 110);
    }

    public static Color methodForeground(String method, boolean dark) {
        if (method == null) {
            return dark ? Color.LIGHT_GRAY : Color.DARK_GRAY;
        }
        switch (method.trim().toUpperCase()) {
            case "GET":
                return dark ? new Color(56, 189, 248) : new Color(3, 105, 161);
            case "POST":
                return dark ? new Color(74, 222, 128) : new Color(21, 128, 61);
            case "PUT":
            case "PATCH":
                return dark ? new Color(251, 191, 36) : new Color(180, 83, 9);
            case "DELETE":
                return dark ? new Color(248, 113, 113) : new Color(185, 28, 28);
            default:
                return dark ? new Color(192, 132, 252) : new Color(109, 40, 217);
        }
    }

    public static Color statusCodeForeground(int status, boolean dark) {
        if (status >= 500) {
            return severityForeground(AuditNote.Severity.ERROR, dark);
        }
        if (status == 401 || status == 403) {
            return severityForeground(AuditNote.Severity.ERROR, dark);
        }
        if (status >= 400) {
            return severityForeground(AuditNote.Severity.WARN, dark);
        }
        if (status >= 300) {
            return severityForeground(AuditNote.Severity.INFO, dark);
        }
        if (status >= 200) {
            return okForeground(dark);
        }
        return mutedForeground(dark);
    }

    public static Color canvasSurface(boolean dark) {
        return dark ? new Color(26, 31, 39) : new Color(238, 242, 247);
    }

    public static Color panelSurface(boolean dark) {
        return dark ? new Color(32, 38, 48) : new Color(245, 247, 250);
    }

    public static Color cardSurface(boolean dark) {
        return dark ? new Color(38, 45, 57) : new Color(231, 236, 243);
    }

    public static Color tableSurface(boolean dark) {
        return dark ? new Color(30, 36, 45) : new Color(247, 249, 252);
    }

    public static Color borderColor(boolean dark) {
        return dark ? new Color(56, 66, 82) : new Color(203, 213, 225);
    }

    public static Color textPrimary(boolean dark) {
        return dark ? new Color(226, 232, 240) : new Color(30, 41, 59);
    }

    public static Color zebraRowBackground(Color baseBg, int viewRow, boolean dark) {
        Color tableBg = tableSurface(dark);
        if (viewRow % 2 == 0) {
            return tableBg;
        }
        return dark ? new Color(36, 43, 54) : new Color(239, 243, 248);
    }

    public static Color blend(Color a, Color b, float ratioB) {
        float ratioA = 1.0f - ratioB;
        return new Color(
            clamp(Math.round(a.getRed() * ratioA + b.getRed() * ratioB)),
            clamp(Math.round(a.getGreen() * ratioA + b.getGreen() * ratioB)),
            clamp(Math.round(a.getBlue() * ratioA + b.getBlue() * ratioB)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public static Border cellPaddingBorder() {
        return BorderFactory.createEmptyBorder(0, 7, 0, 7);
    }

    public static void configureAuditTable(JTable table) {
        configureAuditTable(table, null);
    }

    public static void configureAuditTable(JTable table, MontoyaApi api) {
        boolean dark = isDark(api, table);
        table.setRowHeight(27);
        table.setIntercellSpacing(new java.awt.Dimension(1, 1));
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setBackground(tableSurface(dark));
        table.setForeground(textPrimary(dark));
        table.setGridColor(borderColor(dark));
        if (table.getTableHeader() != null) {
            table.getTableHeader().setBackground(cardSurface(dark));
            table.getTableHeader().setForeground(textPrimary(dark));
            table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
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

    public static void styleTogglePill(AbstractButton btn, boolean active, Color accentFg, boolean dark) {
        Color base = cardSurface(dark);
        Color bg = active ? blend(base, accentFg, dark ? 0.28f : 0.16f) : panelSurface(dark);
        Color fg = active ? accentFg : textPrimary(dark);
        Color border = active ? accentFg : borderColor(dark);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setFocusPainted(false);
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setFont(btn.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border, 1, true),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)));
    }

    public static void styleActionCard(JComponent comp, boolean dark) {
        comp.setOpaque(true);
        comp.setBackground(panelSurface(dark));
        comp.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)));
    }
}
