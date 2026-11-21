package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Self-painted badge Pill and StatusDot primitives with locked {@code maximumSize}
 * so {@code BoxLayout} never stretches or distorts them (LAW 1–4 compliant).
 */
public final class Pill extends JLabel {

    private Theme.Token token;
    private boolean solid;
    private AutoCloseable themeSub;

    public Pill(String text, Theme.Token token) {
        this(text, token, false, null);
    }

    public Pill(String text, Theme.Token token, boolean solid) {
        this(text, token, solid, null);
    }

    public Pill(String text, Theme.Token token, boolean solid, Icon icon) {
        super(text != null ? text : "", icon, SwingConstants.CENTER);
        this.token = token != null ? token : Theme.Token.NEUTRAL;
        this.solid = solid;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        setIconTextGap(4);
        refreshStyle();
    }

    public void setToken(Theme.Token token) {
        this.token = token != null ? token : Theme.Token.NEUTRAL;
        refreshStyle();
    }

    public void setSolid(boolean solid) {
        this.solid = solid;
        refreshStyle();
    }

    public void setPill(String text, Theme.Token token, boolean solid) {
        setText(text != null ? text : "");
        this.token = token != null ? token : Theme.Token.NEUTRAL;
        this.solid = solid;
        refreshStyle();
    }

    public void refreshStyle() {
        setFont(FontManager.uiSmall().deriveFont(java.awt.Font.BOLD));
        Theme.PaletteEntry entry = Theme.token(token);
        setForeground(solid ? entry.onSolid() : entry.text());
        revalidate();
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::refreshStyle);
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
    public void updateUI() {
        super.updateUI();
        if (token != null) {
            refreshStyle();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme.PaletteEntry liveEntry = Theme.token(token);
        setForeground(solid ? liveEntry.onSolid() : liveEntry.text());
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme.PaletteEntry entry = Theme.token(token);
            float w = getWidth() - 1f;
            float h = getHeight() - 1f;
            float arc = Math.min(h, Theme.RADIUS_MD * 2f);
            if (w > 1f && h > 1f) {
                g2.setColor(solid ? entry.solidFill() : entry.fill());
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, arc, arc));
                if (!solid) {
                    g2.setColor(entry.border());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, arc, arc));
                }
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /**
     * Crisp 8px status dot with locked maximumSize for BoxLayout toolbars & tree rows.
     */
    public static final class StatusDot extends JComponent {
        private Theme.Token token;
        private int dotSize;
        private AutoCloseable themeSub;

        public StatusDot(Theme.Token token) {
            this(token, 8);
        }

        public StatusDot(Theme.Token token, int dotSize) {
            this.token = token != null ? token : Theme.Token.NEUTRAL;
            this.dotSize = Math.max(6, dotSize);
            Dimension d = new Dimension(this.dotSize + 4, this.dotSize + 4);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setOpaque(false);
        }

        public void setToken(Theme.Token token) {
            this.token = token != null ? token : Theme.Token.NEUTRAL;
            repaint();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
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
                Color c = Theme.token(token).base();
                float x = (getWidth() - dotSize) / 2f;
                float y = (getHeight() - dotSize) / 2f;
                g2.setColor(c);
                g2.fill(new Ellipse2D.Float(x, y, dotSize, dotSize));
            } finally {
                g2.dispose();
            }
        }
    }
}
