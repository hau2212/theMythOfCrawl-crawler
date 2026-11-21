package com.mythofcrawl.crawler.ui.theme;

import com.mythofcrawl.crawler.ui.kit.UiDisplayMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Self-painted Swing button & layout factory immune to Burp LAF wash-out
 * (LAW 1–4 compliant: zero direct Color allocations, zero FlatLaf, zero HTML, zero glyphs).
 */
public final class Ui {

    public enum Style {
        SOLID_PRIMARY,
        DANGER,
        TONAL,
        SECONDARY,
        ICON,
        LINK
    }

    private Ui() {
    }

    public static UiButton solidPrimary(String text) {
        return solidPrimary(text, null);
    }

    public static UiButton solidPrimary(String text, Icon icon) {
        return new UiButton(Style.SOLID_PRIMARY, Theme.Token.ACCENT, text, icon);
    }

    public static UiButton dangerBtn(String text) {
        return dangerBtn(text, null);
    }

    public static UiButton dangerBtn(String text, Icon icon) {
        return new UiButton(Style.DANGER, Theme.Token.DANGER, text, icon);
    }

    public static UiButton tonalBtn(String text, Icon icon) {
        return tonalBtn(Theme.Token.ACCENT, text, icon);
    }

    public static UiButton tonalBtn(Theme.Token token, String text, Icon icon) {
        return new UiButton(Style.TONAL, token, text, icon);
    }

    public static UiButton secondaryBtn(String text) {
        return secondaryBtn(text, null);
    }

    public static UiButton secondaryBtn(String text, Icon icon) {
        return new UiButton(Style.SECONDARY, Theme.Token.NEUTRAL, text, icon);
    }

    public static UiButton iconBtn(Icon icon, String tooltip) {
        UiButton b = new UiButton(Style.ICON, Theme.Token.NEUTRAL, "", icon);
        if (tooltip != null && !tooltip.isEmpty()) {
            b.setToolTipText(tooltip);
        }
        return b;
    }

    public static UiButton linkBtn(String text) {
        return new UiButton(Style.LINK, Theme.Token.ACCENT, text, null);
    }

    public static JPanel row(int gap, Component... children) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        if (children != null) {
            for (Component c : children) {
                if (c != null) {
                    p.add(c);
                }
            }
        }
        return p;
    }

    public static JPanel col(int gap, Component... children) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                Component c = children[i];
                if (c == null) {
                    continue;
                }
                if (c instanceof JComponent) {
                    ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
                }
                p.add(c);
                if (gap > 0 && i < children.length - 1) {
                    p.add(Box.createVerticalStrut(gap));
                }
            }
        }
        return p;
    }

    public static JPanel cardPanel(Component content) {
        JPanel card = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2)) {
            @Override
            public void updateUI() {
                super.updateUI();
                refreshCardStyle(this);
            }
        };
        if (content != null) {
            card.add(content, BorderLayout.CENTER);
        }
        refreshCardStyle(card);
        return card;
    }

    private static void refreshCardStyle(JPanel card) {
        card.setOpaque(true);
        card.setBackground(Theme.surface());
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.border(), 1, true),
            BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3)));
    }

    public static JComponent hairline(boolean horizontal) {
        JPanel sep = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.borderSubtle());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        sep.setOpaque(false);
        if (horizontal) {
            sep.setPreferredSize(new Dimension(1, 1));
            sep.setMinimumSize(new Dimension(1, 1));
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        } else {
            sep.setPreferredSize(new Dimension(1, 1));
            sep.setMinimumSize(new Dimension(1, 1));
            sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        }
        return sep;
    }

    public static <T extends JComponent> T pad(T comp, int v, int h) {
        comp.setBorder(BorderFactory.createEmptyBorder(v, h, v, h));
        return comp;
    }

    public static void styleTabbedPane(JTabbedPane tabs) {
        if (tabs == null) {
            return;
        }
        tabs.setOpaque(true);
        tabs.setBackground(Theme.canvas());
        tabs.setForeground(Theme.text());
        tabs.setFont(FontManager.uiMedium());
        tabs.setUI(new BasicTabbedPaneUI() {
            @Override
            protected Insets getContentBorderInsets(int tabPlacement) {
                return new Insets(1, 0, 0, 0);
            }

            @Override
            protected Insets getTabInsets(int tabPlacement, int tabIndex) {
                return new Insets(6, 12, 6, 12);
            }

            @Override
            protected void paintTabBackground(
                Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected
            ) {
                g.setColor(isSelected ? Theme.surface() : Theme.canvas());
                g.fillRect(x, y, w, h);
            }

            @Override
            protected void paintTabBorder(
                Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected
            ) {
                if (isSelected) {
                    g.setColor(Theme.token(Theme.Token.ACCENT).solidFill());
                    g.fillRect(x, y + h - 2, w, 2);
                } else {
                    g.setColor(Theme.borderSubtle());
                    g.fillRect(x, y + h - 1, w, 1);
                }
                g.setColor(Theme.borderSubtle());
                g.fillRect(x + w - 1, y + 4, 1, Math.max(0, h - 8));
            }

            @Override
            protected void paintText(
                Graphics g,
                int tabPlacement,
                Font font,
                FontMetrics metrics,
                int tabIndex,
                String title,
                Rectangle textRect,
                boolean isSelected
            ) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font f = isSelected ? FontManager.uiSemiBold() : FontManager.uiRegular();
                g2.setFont(f);
                FontMetrics fm = g2.getFontMetrics(f);
                g2.setColor(isSelected ? Theme.text() : Theme.textSecondary());
                g2.drawString(title, textRect.x, textRect.y + fm.getAscent());
                g2.dispose();
            }

            @Override
            protected void paintFocusIndicator(
                Graphics g,
                int tabPlacement,
                Rectangle[] rects,
                int tabIndex,
                Rectangle iconRect,
                Rectangle textRect,
                boolean isSelected
            ) {
                // Clean flat design: no Metal dotted focus box
            }

            @Override
            protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                int width = tabPane.getWidth();
                Insets insets = tabPane.getInsets();
                int top = insets.top + calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight);
                g.setColor(Theme.border());
                g.fillRect(insets.left, top, Math.max(0, width - insets.left - insets.right), 1);
            }
        });
    }

    public static void syncTreeColors(Component root) {
        if (root == null) {
            return;
        }
        if (root instanceof UiButton) {
            ((UiButton) root).refreshAppearance();
        } else if (root instanceof JTabbedPane) {
            styleTabbedPane((JTabbedPane) root);
        } else if (root instanceof javax.swing.JScrollPane) {
            javax.swing.JScrollPane sp = (javax.swing.JScrollPane) root;
            if (sp.getViewport() != null) {
                sp.getViewport().setBackground(Theme.surface());
            }
        } else if (root instanceof javax.swing.JTextField || root instanceof javax.swing.JTextArea) {
            if (root.isOpaque()) {
                root.setBackground(Theme.surface());
            }
            root.setForeground(Theme.text());
        } else if (root instanceof javax.swing.JComboBox) {
            root.setBackground(Theme.surface());
            root.setForeground(Theme.text());
        } else if (root instanceof javax.swing.JLabel && !(root instanceof com.mythofcrawl.crawler.ui.kit.Pill)) {
            root.setForeground(Theme.text());
        }
        if (root instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) root).getComponents()) {
                syncTreeColors(child);
            }
        }
    }

    public static final class UiButton extends JButton {
        private Style style;
        private Theme.Token token;
        private String fullText;
        private Icon baseIcon;
        private UiDisplayMode modeOverride;
        private boolean selectedVisual = false;
        private AutoCloseable modeUnsubscribe;
        private AutoCloseable themeUnsubscribe;

        public UiButton(Style style, Theme.Token token, String text, Icon icon) {
            super(text != null ? text : "", icon);
            this.style = style != null ? style : Style.SECONDARY;
            this.token = token != null ? token : Theme.Token.NEUTRAL;
            this.fullText = text != null ? text : "";
            this.baseIcon = icon;

            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setIconTextGap(6);
            setHorizontalAlignment(SwingConstants.CENTER);
            refreshAppearance();
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (modeUnsubscribe == null) {
                modeUnsubscribe = UiDisplayMode.onChange(m -> refreshAppearance());
            }
            if (themeUnsubscribe == null) {
                themeUnsubscribe = Theme.onThemeChange(this::refreshAppearance);
            }
        }

        @Override
        public void removeNotify() {
            closeQuietly(modeUnsubscribe);
            closeQuietly(themeUnsubscribe);
            modeUnsubscribe = null;
            themeUnsubscribe = null;
            super.removeNotify();
        }

        public void setDisplayMode(UiDisplayMode mode) {
            this.modeOverride = mode;
            refreshAppearance();
        }

        public UiDisplayMode effectiveDisplayMode() {
            if (style == Style.ICON) {
                return UiDisplayMode.ICON_ONLY;
            }
            if (style == Style.LINK) {
                return UiDisplayMode.TEXT_ONLY;
            }
            return modeOverride != null ? modeOverride : UiDisplayMode.global();
        }

        public void setStyle(Style style) {
            this.style = style != null ? style : Style.SECONDARY;
            refreshAppearance();
        }

        public void setToken(Theme.Token token) {
            this.token = token != null ? token : Theme.Token.NEUTRAL;
            refreshAppearance();
        }

        public void setSelectedVisual(boolean selected) {
            this.selectedVisual = selected;
            refreshAppearance();
        }

        public boolean isSelectedVisual() {
            return selectedVisual;
        }

        public void setLabelAndIcon(String text, Icon icon) {
            this.fullText = text != null ? text : "";
            this.baseIcon = icon;
            refreshAppearance();
        }

        public void refreshAppearance() {
            UiDisplayMode m = effectiveDisplayMode();
            if (m == UiDisplayMode.ICON_ONLY && baseIcon != null) {
                super.setText("");
                super.setIcon(baseIcon);
                if (getToolTipText() == null && !fullText.isEmpty()) {
                    setToolTipText(fullText);
                }
                setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
            } else if (m == UiDisplayMode.TEXT_ONLY && !fullText.isEmpty()) {
                super.setText(fullText);
                super.setIcon(null);
                setBorder(style == Style.LINK
                    ? BorderFactory.createEmptyBorder(2, 4, 2, 4)
                    : BorderFactory.createEmptyBorder(5, 11, 5, 11));
            } else {
                super.setText(fullText);
                super.setIcon(baseIcon);
                setBorder(style == Style.ICON
                    ? BorderFactory.createEmptyBorder(4, 6, 4, 6)
                    : (style == Style.LINK
                        ? BorderFactory.createEmptyBorder(2, 4, 2, 4)
                        : BorderFactory.createEmptyBorder(5, 11, 5, 11)));
            }

            Theme.PaletteEntry entry = Theme.token(token);
            Color fg;
            switch (style) {
                case SOLID_PRIMARY:
                    fg = entry.onSolid();
                    setFont(FontManager.uiSemiBold());
                    break;
                case DANGER:
                    fg = selectedVisual ? entry.onSolid() : entry.text();
                    setFont(FontManager.uiSemiBold());
                    break;
                case TONAL:
                    fg = entry.text();
                    setFont(FontManager.uiMedium());
                    break;
                case LINK:
                    fg = entry.text();
                    setFont(FontManager.uiMedium());
                    break;
                case ICON:
                case SECONDARY:
                default:
                    fg = selectedVisual ? Theme.token(Theme.Token.ACCENT).text() : Theme.text();
                    setFont(selectedVisual ? FontManager.uiSemiBold() : FontManager.uiRegular());
                    break;
            }
            if (!isEnabled()) {
                fg = Theme.muted();
            }
            setForeground(fg);
            revalidate();
            repaint();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            refreshAppearance();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (style != null && token != null) {
                refreshAppearance();
            }
        }

        private Color resolveLiveForeground() {
            Theme.PaletteEntry entry = Theme.token(token);
            Color fg;
            switch (style) {
                case SOLID_PRIMARY:
                    fg = entry.onSolid();
                    break;
                case DANGER:
                    fg = selectedVisual ? entry.onSolid() : entry.text();
                    break;
                case TONAL:
                case LINK:
                    fg = entry.text();
                    break;
                case ICON:
                case SECONDARY:
                default:
                    fg = selectedVisual ? Theme.token(Theme.Token.ACCENT).text() : Theme.text();
                    break;
            }
            return isEnabled() ? fg : Theme.muted();
        }

        @Override
        protected void paintComponent(Graphics g) {
            setForeground(resolveLiveForeground());
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (w > 1 && h > 1 && style != Style.LINK) {
                    Theme.PaletteEntry entry = Theme.token(token);
                    boolean hover = isEnabled() && getModel().isRollover();
                    boolean pressed = isEnabled() && getModel().isPressed();

                    Color bg = null;
                    Color border = null;

                    if (style == Style.SOLID_PRIMARY) {
                        bg = entry.solidFill();
                        border = entry.solidFill();
                    } else if (style == Style.DANGER) {
                        bg = selectedVisual ? entry.solidFill() : entry.fill();
                        border = entry.border();
                    } else if (style == Style.TONAL) {
                        bg = selectedVisual ? entry.fillStrong() : entry.fill();
                        border = entry.border();
                    } else if (style == Style.ICON) {
                        if (selectedVisual || hover || pressed) {
                            bg = selectedVisual ? entry.fill() : Theme.surfaceHover();
                            border = selectedVisual ? entry.border() : Theme.borderSubtle();
                        }
                    } else {
                        bg = selectedVisual ? Theme.token(Theme.Token.ACCENT).fill() : Theme.surface();
                        border = selectedVisual ? Theme.token(Theme.Token.ACCENT).border() : Theme.border();
                    }

                    if (!isEnabled()) {
                        bg = style == Style.ICON ? null : Theme.surfaceAlt();
                        border = style == Style.ICON ? null : Theme.borderSubtle();
                    } else if (bg != null) {
                        if (pressed) {
                            bg = Theme.blend(bg, Theme.text(), 0.14f);
                        } else if (hover) {
                            bg = Theme.blend(bg, style == Style.SOLID_PRIMARY ? Theme.inkWhite() : Theme.text(), 0.08f);
                        }
                    }

                    if (bg != null) {
                        g2.setColor(bg);
                        g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, Theme.RADIUS_MD, Theme.RADIUS_MD));
                    }
                    if (border != null) {
                        g2.setColor(border);
                        g2.setStroke(new BasicStroke(1.0f));
                        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, Theme.RADIUS_MD, Theme.RADIUS_MD));
                    }
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
            if (style == Style.LINK && isEnabled() && getModel().isRollover()) {
                Font f = getFont();
                int w = getFontMetrics(f).stringWidth(getText());
                int x = Math.max(0, (getWidth() - w) / 2);
                int y = getHeight() - 3;
                Graphics2D g2u = (Graphics2D) g.create();
                try {
                    g2u.setColor(getForeground());
                    g2u.drawLine(x, y, x + w, y);
                } finally {
                    g2u.dispose();
                }
            }
        }

        private static void closeQuietly(AutoCloseable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
