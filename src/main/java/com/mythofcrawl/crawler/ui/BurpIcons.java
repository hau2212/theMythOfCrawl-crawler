package com.mythofcrawl.crawler.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Pure Java 2D vector icons (zero external dependencies, crisp under HiDPI scaling)
 * replacing inconsistent OS emoji glyphs across Burp Light & Dark Look-and-Feels.
 */
public final class BurpIcons {

    private BurpIcons() {
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g2, int w, int h, Color color);
    }

    private static final class VectorIcon implements Icon {
        private final int size;
        private final Color color;
        private final Painter painter;

        VectorIcon(int size, Color color, Painter painter) {
            this.size = size;
            this.color = color;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color resolved = color != null ? color : (c != null ? c.getForeground() : Color.GRAY);
                painter.paint(g2, size, size, resolved);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    public static Icon filter(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Float();
            p.moveTo(2f, 3f);
            p.lineTo(12f, 3f);
            p.lineTo(8.2f, 7.6f);
            p.lineTo(8.2f, 11.5f);
            p.lineTo(5.8f, 12.5f);
            p.lineTo(5.8f, 7.6f);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon clear(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(3f, 3f, 11f, 11f));
            g.draw(new Line2D.Float(11f, 3f, 3f, 11f));
        });
    }

    public static Icon search(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(2f, 2f, 7f, 7f));
            g.draw(new Line2D.Float(8f, 8f, 12f, 12f));
        });
    }

    public static Icon regex(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.fill(new Ellipse2D.Float(2f, 9f, 3f, 3f));
            g.draw(new Line2D.Float(9f, 2f, 9f, 8f));
            g.draw(new Line2D.Float(6.4f, 3.5f, 11.6f, 6.5f));
            g.draw(new Line2D.Float(11.6f, 3.5f, 6.4f, 6.5f));
        });
    }

    public static Icon lock(boolean unlocked, Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Float(3f, 6f, 8f, 6f, 2f, 2f));
            if (unlocked) {
                g.draw(new Arc2D.Float(5f, 2f, 5f, 6f, 0f, 155f, Arc2D.OPEN));
            } else {
                g.draw(new Arc2D.Float(4.5f, 2f, 5f, 6f, 0f, 180f, Arc2D.OPEN));
                g.draw(new Line2D.Float(9.5f, 5f, 9.5f, 6f));
            }
            g.fill(new Ellipse2D.Float(6f, 8f, 2f, 2f));
        });
    }

    public static Icon play(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            Path2D p = new Path2D.Float();
            p.moveTo(4f, 2.5f);
            p.lineTo(11.5f, 7f);
            p.lineTo(4f, 11.5f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon pause(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.fill(new RoundRectangle2D.Float(3f, 2.5f, 2.8f, 9f, 1f, 1f));
            g.fill(new RoundRectangle2D.Float(8.2f, 2.5f, 2.8f, 9f, 1f, 1f));
        });
    }

    public static Icon stop(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.fill(new RoundRectangle2D.Float(3f, 3f, 8f, 8f, 1.5f, 1.5f));
        });
    }

    public static Icon folder(boolean open, Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (open) {
                Path2D back = new Path2D.Float();
                back.moveTo(2f, 4f);
                back.lineTo(5.5f, 4f);
                back.lineTo(6.8f, 5.4f);
                back.lineTo(11.5f, 5.4f);
                back.lineTo(11.5f, 7f);
                g.draw(back);
                Path2D front = new Path2D.Float();
                front.moveTo(2f, 4f);
                front.lineTo(2f, 11.2f);
                front.lineTo(10.8f, 11.2f);
                front.lineTo(12.5f, 7f);
                front.lineTo(4f, 7f);
                front.lineTo(2f, 11.2f);
                g.draw(front);
            } else {
                Path2D p = new Path2D.Float();
                p.moveTo(2f, 3.5f);
                p.lineTo(5.5f, 3.5f);
                p.lineTo(7f, 5f);
                p.lineTo(12f, 5f);
                p.lineTo(12f, 11f);
                p.lineTo(2f, 11f);
                p.closePath();
                g.draw(p);
            }
        });
    }

    public static Icon expandAll(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Rectangle2D.Float(2f, 2f, 10f, 10f));
            g.draw(new Line2D.Float(7f, 4.5f, 7f, 9.5f));
            g.draw(new Line2D.Float(4.5f, 7f, 9.5f, 7f));
        });
    }

    public static Icon collapseAll(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Rectangle2D.Float(2f, 2f, 10f, 10f));
            g.draw(new Line2D.Float(4.5f, 7f, 9.5f, 7f));
        });
    }

    public static Icon tree(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.fill(new Rectangle2D.Float(2f, 2f, 3f, 3f));
            g.draw(new Line2D.Float(3.5f, 5f, 3.5f, 11f));
            g.draw(new Line2D.Float(3.5f, 7f, 8f, 7f));
            g.draw(new Line2D.Float(3.5f, 11f, 8f, 11f));
            g.fill(new Rectangle2D.Float(8f, 5.5f, 4f, 2.8f));
            g.fill(new Rectangle2D.Float(8f, 9.5f, 4f, 2.8f));
        });
    }

    public static Icon table(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Rectangle2D.Float(2f, 2.5f, 10f, 9f));
            g.draw(new Line2D.Float(2f, 5.5f, 12f, 5.5f));
            g.draw(new Line2D.Float(2f, 8.5f, 12f, 8.5f));
            g.draw(new Line2D.Float(5.5f, 5.5f, 5.5f, 11.5f));
        });
    }

    public static Icon inspector(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Rectangle2D.Float(2f, 2f, 10f, 10f));
            g.draw(new Line2D.Float(2f, 7.5f, 12f, 7.5f));
            g.fill(new Rectangle2D.Float(2.5f, 7.8f, 9f, 3.8f));
        });
    }

    public static Icon sidebar(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Rectangle2D.Float(2f, 2f, 10f, 10f));
            g.draw(new Line2D.Float(6f, 2f, 6f, 12f));
            g.fill(new Rectangle2D.Float(2.5f, 2.5f, 3.2f, 9f));
        });
    }

    public static Icon maximize(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(2.5f, 5.5f, 2.5f, 2.5f));
            g.draw(new Line2D.Float(2.5f, 2.5f, 5.5f, 2.5f));
            g.draw(new Line2D.Float(8.5f, 2.5f, 11.5f, 2.5f));
            g.draw(new Line2D.Float(11.5f, 2.5f, 11.5f, 5.5f));
            g.draw(new Line2D.Float(2.5f, 8.5f, 2.5f, 11.5f));
            g.draw(new Line2D.Float(2.5f, 11.5f, 5.5f, 11.5f));
            g.draw(new Line2D.Float(8.5f, 11.5f, 11.5f, 11.5f));
            g.draw(new Line2D.Float(11.5f, 11.5f, 11.5f, 8.5f));
        });
    }

    public static Icon bolt(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            Path2D p = new Path2D.Float();
            p.moveTo(8f, 1.5f);
            p.lineTo(3.5f, 7.5f);
            p.lineTo(7f, 7.5f);
            p.lineTo(6f, 12.5f);
            p.lineTo(10.5f, 6.2f);
            p.lineTo(7f, 6.2f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon send(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Float();
            p.moveTo(2f, 7f);
            p.lineTo(12f, 2.5f);
            p.lineTo(9f, 12f);
            p.lineTo(6.5f, 8.2f);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Float(6.5f, 8.2f, 12f, 2.5f));
        });
    }

    public static Icon gear(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(4.5f, 4.5f, 5f, 5f));
            g.draw(new Line2D.Float(7f, 1.8f, 7f, 3.5f));
            g.draw(new Line2D.Float(7f, 10.5f, 7f, 12.2f));
            g.draw(new Line2D.Float(1.8f, 7f, 3.5f, 7f));
            g.draw(new Line2D.Float(10.5f, 7f, 12.2f, 7f));
            g.draw(new Line2D.Float(3.3f, 3.3f, 4.6f, 4.6f));
            g.draw(new Line2D.Float(9.4f, 9.4f, 10.7f, 10.7f));
            g.draw(new Line2D.Float(10.7f, 3.3f, 9.4f, 4.6f));
            g.draw(new Line2D.Float(4.6f, 9.4f, 3.3f, 10.7f));
        });
    }

    public static Icon dot(Color color) {
        return new VectorIcon(10, color, (g, w, h, c) -> {
            g.setColor(c);
            g.fill(new Ellipse2D.Float(1f, 1f, 8f, 8f));
            g.setColor(c.darker());
            g.setStroke(new BasicStroke(1.0f));
            g.draw(new Ellipse2D.Float(1f, 1f, 8f, 8f));
        });
    }

    public static Icon check(Color color) {
        return new VectorIcon(12, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Float();
            p.moveTo(2.2f, 6.2f);
            p.lineTo(4.9f, 9.0f);
            p.lineTo(9.8f, 3.2f);
            g.draw(p);
        });
    }

    public static Icon plusCircle(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(1.8f, 1.8f, 10.4f, 10.4f));
            g.draw(new Line2D.Float(7f, 4.3f, 7f, 9.7f));
            g.draw(new Line2D.Float(4.3f, 7f, 9.7f, 7f));
        });
    }

    public static Icon helpCircle(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(1.8f, 1.8f, 10.4f, 10.4f));
            g.draw(new Arc2D.Float(5.1f, 4.0f, 3.8f, 3.4f, 180f, -220f, Arc2D.OPEN));
            g.draw(new Line2D.Float(7f, 7.4f, 7f, 8.4f));
            g.fill(new Ellipse2D.Float(6.2f, 9.6f, 1.6f, 1.6f));
        });
    }

    public static Icon expandPane(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(8.2f, 5.8f, 11.5f, 2.5f));
            g.draw(new Line2D.Float(8.5f, 2.5f, 11.5f, 2.5f));
            g.draw(new Line2D.Float(11.5f, 2.5f, 11.5f, 5.5f));
            g.draw(new Line2D.Float(5.8f, 8.2f, 2.5f, 11.5f));
            g.draw(new Line2D.Float(2.5f, 8.5f, 2.5f, 11.5f));
            g.draw(new Line2D.Float(2.5f, 11.5f, 5.5f, 11.5f));
        });
    }

    public static Icon collapsePane(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(11.5f, 2.5f, 8.0f, 6.0f));
            g.draw(new Line2D.Float(8.0f, 3.2f, 8.0f, 6.0f));
            g.draw(new Line2D.Float(8.0f, 6.0f, 10.8f, 6.0f));
            g.draw(new Line2D.Float(2.5f, 11.5f, 6.0f, 8.0f));
            g.draw(new Line2D.Float(3.2f, 8.0f, 6.0f, 8.0f));
            g.draw(new Line2D.Float(6.0f, 8.0f, 6.0f, 10.8f));
        });
    }

    public static Icon trash(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(2.5f, 3.8f, 11.5f, 3.8f));
            g.draw(new Line2D.Float(5.2f, 2.2f, 8.8f, 2.2f));
            g.draw(new RoundRectangle2D.Float(3.5f, 3.8f, 7.0f, 8.0f, 1.5f, 1.5f));
            g.draw(new Line2D.Float(5.8f, 6.0f, 5.8f, 9.8f));
            g.draw(new Line2D.Float(8.2f, 6.0f, 8.2f, 9.8f));
        });
    }

    public static Icon filterFunnel(Color color) {
        return filter(color);
    }

    public static Icon severityCircle(Color bg, String glyph) {
        return new VectorIcon(14, bg, (g, w, h, c) -> {
            g.setColor(c);
            g.fill(new Ellipse2D.Float(1f, 1f, 12f, 12f));
            g.setColor(AuditTheme.contrastForeground(c));
            String text = (glyph == null || glyph.isEmpty()) ? "!" : glyph;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            FontMetrics fm = g.getFontMetrics();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, tx, ty);
        });
    }

    public static Icon locateWeb(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Float(2.5f, 2.5f, 9f, 9f));
            g.fill(new Ellipse2D.Float(5.7f, 5.7f, 2.6f, 2.6f));
            g.draw(new Line2D.Float(7f, 1.2f, 7f, 3.4f));
            g.draw(new Line2D.Float(7f, 10.6f, 7f, 12.8f));
            g.draw(new Line2D.Float(1.2f, 7f, 3.4f, 7f));
            g.draw(new Line2D.Float(10.6f, 7f, 12.8f, 7f));
        });
    }

    public static Icon emptyTray(Color color) {
        return new VectorIcon(32, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Float(4f, 6f, 24f, 20f, 4f, 4f));
            Path2D tray = new Path2D.Float();
            tray.moveTo(4.5f, 18f);
            tray.lineTo(11f, 18f);
            tray.lineTo(13f, 21.5f);
            tray.lineTo(19f, 21.5f);
            tray.lineTo(21f, 18f);
            tray.lineTo(27.5f, 18f);
            g.draw(tray);
        });
    }

    public static Icon chevronDown(Color color) {
        return new VectorIcon(12, color, (g, w, h, c) -> {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Float();
            p.moveTo(2.8f, 4.4f);
            p.lineTo(6f, 7.8f);
            p.lineTo(9.2f, 4.4f);
            g.draw(p);
        });
    }

    public static Icon menuDots(Color color) {
        return new VectorIcon(14, color, (g, w, h, c) -> {
            g.setColor(c);
            g.fill(new Ellipse2D.Float(5.8f, 2.2f, 2.4f, 2.4f));
            g.fill(new Ellipse2D.Float(5.8f, 5.8f, 2.4f, 2.4f));
            g.fill(new Ellipse2D.Float(5.8f, 9.4f, 2.4f, 2.4f));
        });
    }
}
