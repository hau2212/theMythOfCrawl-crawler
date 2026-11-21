package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Anti-aliased vector pill toggle switch obeying {@link Theme} (LAW 1–4 compliant).
 */
public final class ModernToggleSwitch extends JToggleButton {

    private static final int TRACK_W = 34;
    private static final int TRACK_H = 18;

    private Theme.Token activeToken;
    private String caption;
    private AutoCloseable themeSub;

    public ModernToggleSwitch(boolean initialSelected) {
        this("", initialSelected, Theme.Token.INFO);
    }

    public ModernToggleSwitch(String caption, boolean initialSelected) {
        this(caption, initialSelected, Theme.Token.INFO);
    }

    public ModernToggleSwitch(String caption, boolean initialSelected, Theme.Token activeToken) {
        super("", initialSelected);
        this.caption = caption != null ? caption : "";
        this.activeToken = activeToken != null ? activeToken : Theme.Token.INFO;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        setFont(FontManager.uiRegular());
        updateSizing();
    }

    public void setCaption(String caption) {
        this.caption = caption != null ? caption : "";
        updateSizing();
        revalidate();
        repaint();
    }

    public String getCaption() {
        return caption;
    }

    public void setActiveToken(Theme.Token token) {
        this.activeToken = token != null ? token : Theme.Token.INFO;
        repaint();
    }

    private void updateSizing() {
        int textW = 0;
        if (!caption.isEmpty()) {
            FontMetrics fm = getFontMetrics(getFont() != null ? getFont() : FontManager.uiRegular());
            textW = Theme.SPACE_2 + fm.stringWidth(caption);
        }
        Dimension d = new Dimension(TRACK_W + 4 + textW, Math.max(TRACK_H + 4, 22));
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::repaint);
        }
    }

    @Override
    public void removeNotify() {
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = isSelected();
            Color track = on
                ? Theme.token(activeToken).solidFill()
                : Theme.surfaceAlt();
            Color border = on
                ? Theme.token(activeToken).solidFill()
                : Theme.border();

            if (!isEnabled()) {
                track = Theme.surfaceAlt();
                border = Theme.borderSubtle();
            } else if (getModel().isRollover()) {
                track = Theme.blend(track, Theme.text(), on ? 0.10f : 0.06f);
            }

            int tx = 2;
            int ty = Math.max(0, (getHeight() - TRACK_H) / 2);

            g2.setColor(track);
            g2.fill(new RoundRectangle2D.Float(tx, ty, TRACK_W, TRACK_H, TRACK_H, TRACK_H));
            g2.setColor(border);
            g2.draw(new RoundRectangle2D.Float(tx, ty, TRACK_W - 0.5f, TRACK_H - 0.5f, TRACK_H, TRACK_H));

            int knob = 12;
            int kx = on ? (tx + TRACK_W - knob - 3) : (tx + 3);
            int ky = ty + 3;
            g2.setColor(on ? Theme.token(activeToken).onSolid() : Theme.surface());
            g2.fill(new Ellipse2D.Float(kx, ky, knob, knob));
            if (!on) {
                g2.setColor(Theme.border());
                g2.draw(new Ellipse2D.Float(kx, ky, knob, knob));
            }

            if (!caption.isEmpty()) {
                g2.setFont(FontManager.uiRegular());
                g2.setColor(isEnabled() ? Theme.text() : Theme.muted());
                FontMetrics fm = g2.getFontMetrics();
                int textX = tx + TRACK_W + Theme.SPACE_2;
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(caption, textX, textY);
            }
        } finally {
            g2.dispose();
        }
    }
}
