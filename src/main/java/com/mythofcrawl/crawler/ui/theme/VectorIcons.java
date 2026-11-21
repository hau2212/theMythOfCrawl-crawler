package com.mythofcrawl.crawler.ui.theme;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * Pure Java2D HiDPI vector icon library (LAW 1 & LAW 4 compliant:
 * zero direct Color allocations and zero Unicode symbol glyphs).
 */
public final class VectorIcons {

    private VectorIcons() {
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g, Color c);
    }

    private static final class ScaledVectorIcon implements Icon {
        private final int size;
        private final Color fixedColor;
        private final Painter painter;

        ScaledVectorIcon(int size, Color color, Painter painter) {
            this.size = size;
            this.fixedColor = color;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component comp, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                double scale = size / 16.0;
                if (Math.abs(scale - 1.0) > 0.001) {
                    g2.scale(scale, scale);
                }
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color resolved = fixedColor != null
                    ? fixedColor
                    : (comp != null && comp.getForeground() != null ? comp.getForeground() : Theme.text());
                painter.paint(g2, resolved);
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private static Icon make(int size, Color color, Painter painter) {
        return new ScaledVectorIcon(size, color, painter);
    }

    private static BasicStroke stroke(float width) {
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    public static Icon play(Color c) { return play(14, c); }
    public static Icon play(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            Path2D p = new Path2D.Float();
            p.moveTo(4.5f, 3.0f);
            p.lineTo(13.0f, 8.0f);
            p.lineTo(4.5f, 13.0f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon pause(Color c) { return pause(14, c); }
    public static Icon pause(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.fill(new RoundRectangle2D.Float(3.5f, 3.0f, 3.2f, 10.0f, 1.2f, 1.2f));
            g.fill(new RoundRectangle2D.Float(9.3f, 3.0f, 3.2f, 10.0f, 1.2f, 1.2f));
        });
    }

    public static Icon stop(Color c) { return stop(14, c); }
    public static Icon stop(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.fill(new RoundRectangle2D.Float(3.5f, 3.5f, 9.0f, 9.0f, 2.0f, 2.0f));
        });
    }

    public static Icon lock(Color c) { return lock(14, c); }
    public static Icon lock(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new RoundRectangle2D.Float(3.2f, 7.0f, 9.6f, 6.8f, 2.0f, 2.0f));
            g.draw(new Arc2D.Float(5.0f, 2.4f, 6.0f, 6.5f, 0f, 180f, Arc2D.OPEN));
            g.fill(new Ellipse2D.Float(7.0f, 9.4f, 2.0f, 2.0f));
        });
    }

    public static Icon unlock(Color c) { return unlock(14, c); }
    public static Icon unlock(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new RoundRectangle2D.Float(3.2f, 7.0f, 9.6f, 6.8f, 2.0f, 2.0f));
            g.draw(new Arc2D.Float(5.2f, 2.4f, 6.0f, 6.5f, 10f, 165f, Arc2D.OPEN));
            g.fill(new Ellipse2D.Float(7.0f, 9.4f, 2.0f, 2.0f));
        });
    }

    public static Icon shield(Color c) { return shield(14, c); }
    public static Icon shield(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(shieldPath());
        });
    }

    public static Icon shieldCheck(Color c) { return shieldCheck(14, c); }
    public static Icon shieldCheck(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(shieldPath());
            Path2D chk = new Path2D.Float();
            chk.moveTo(5.4f, 8.2f);
            chk.lineTo(7.3f, 10.1f);
            chk.lineTo(10.8f, 6.2f);
            g.draw(chk);
        });
    }

    public static Icon shieldAlert(Color c) { return shieldAlert(14, c); }
    public static Icon shieldAlert(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(shieldPath());
            g.draw(new Line2D.Float(8.0f, 5.2f, 8.0f, 8.4f));
            g.fill(new Ellipse2D.Float(7.2f, 9.8f, 1.6f, 1.6f));
        });
    }

    private static Path2D shieldPath() {
        Path2D p = new Path2D.Float();
        p.moveTo(8.0f, 2.0f);
        p.lineTo(13.2f, 4.0f);
        p.lineTo(13.2f, 8.2f);
        p.curveTo(13.2f, 11.4f, 10.6f, 13.4f, 8.0f, 14.3f);
        p.curveTo(5.4f, 13.4f, 2.8f, 11.4f, 2.8f, 8.2f);
        p.lineTo(2.8f, 4.0f);
        p.closePath();
        return p;
    }

    public static Icon check(Color c) { return check(14, c); }
    public static Icon check(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.7f));
            Path2D p = new Path2D.Float();
            p.moveTo(3.2f, 8.2f);
            p.lineTo(6.4f, 11.4f);
            p.lineTo(12.8f, 4.6f);
            g.draw(p);
        });
    }

    public static Icon cross(Color c) { return cross(14, c); }
    public static Icon cross(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.6f));
            g.draw(new Line2D.Float(3.8f, 3.8f, 12.2f, 12.2f));
            g.draw(new Line2D.Float(12.2f, 3.8f, 3.8f, 12.2f));
        });
    }

    public static Icon plus(Color c) { return plus(14, c); }
    public static Icon plus(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.6f));
            g.draw(new Line2D.Float(8.0f, 3.2f, 8.0f, 12.8f));
            g.draw(new Line2D.Float(3.2f, 8.0f, 12.8f, 8.0f));
        });
    }

    public static Icon minus(Color c) { return minus(14, c); }
    public static Icon minus(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.6f));
            g.draw(new Line2D.Float(3.2f, 8.0f, 12.8f, 8.0f));
        });
    }

    public static Icon trash(Color c) { return trash(14, c); }
    public static Icon trash(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            g.draw(new Line2D.Float(3.0f, 4.5f, 13.0f, 4.5f));
            g.draw(new Line2D.Float(6.0f, 2.5f, 10.0f, 2.5f));
            g.draw(new RoundRectangle2D.Float(4.2f, 4.5f, 7.6f, 9.0f, 1.5f, 1.5f));
            g.draw(new Line2D.Float(6.6f, 6.8f, 6.6f, 11.2f));
            g.draw(new Line2D.Float(9.4f, 6.8f, 9.4f, 11.2f));
        });
    }

    public static Icon undo(Color c) { return undo(14, c); }
    public static Icon undo(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            Path2D arr = new Path2D.Float();
            arr.moveTo(6.2f, 3.2f);
            arr.lineTo(3.2f, 5.8f);
            arr.lineTo(6.2f, 8.4f);
            g.draw(arr);
            g.draw(new Arc2D.Float(3.4f, 5.8f, 9.6f, 7.2f, 125f, -210f, Arc2D.OPEN));
        });
    }

    public static Icon copy(Color c) { return copy(14, c); }
    public static Icon copy(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            g.draw(new RoundRectangle2D.Float(5.4f, 5.4f, 7.8f, 8.0f, 1.6f, 1.6f));
            Path2D back = new Path2D.Float();
            back.moveTo(10.6f, 5.2f);
            back.lineTo(10.6f, 3.2f);
            back.lineTo(3.0f, 3.2f);
            back.lineTo(3.0f, 11.0f);
            back.lineTo(5.2f, 11.0f);
            g.draw(back);
        });
    }

    public static Icon edit(Color c) { return edit(14, c); }
    public static Icon edit(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            Path2D p = new Path2D.Float();
            p.moveTo(3.0f, 13.0f);
            p.lineTo(3.6f, 9.8f);
            p.lineTo(10.8f, 2.6f);
            p.lineTo(13.4f, 5.2f);
            p.lineTo(6.2f, 12.4f);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Float(9.2f, 4.2f, 11.8f, 6.8f));
        });
    }

    public static Icon search(Color c) { return search(14, c); }
    public static Icon search(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.5f));
            g.draw(new Ellipse2D.Float(2.4f, 2.4f, 8.2f, 8.2f));
            g.draw(new Line2D.Float(9.4f, 9.4f, 13.4f, 13.4f));
        });
    }

    public static Icon filter(Color c) { return filter(14, c); }
    public static Icon filter(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            Path2D p = new Path2D.Float();
            p.moveTo(2.4f, 3.4f);
            p.lineTo(13.6f, 3.4f);
            p.lineTo(9.3f, 8.6f);
            p.lineTo(9.3f, 13.0f);
            p.lineTo(6.7f, 14.0f);
            p.lineTo(6.7f, 8.6f);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon chevronRight(Color c) { return chevronRight(12, c); }
    public static Icon chevronRight(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.7f));
            Path2D p = new Path2D.Float();
            p.moveTo(5.6f, 3.4f);
            p.lineTo(10.6f, 8.0f);
            p.lineTo(5.6f, 12.6f);
            g.draw(p);
        });
    }

    public static Icon chevronDown(Color c) { return chevronDown(12, c); }
    public static Icon chevronDown(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.7f));
            Path2D p = new Path2D.Float();
            p.moveTo(3.4f, 5.6f);
            p.lineTo(8.0f, 10.6f);
            p.lineTo(12.6f, 5.6f);
            g.draw(p);
        });
    }

    public static Icon chevronLeft(Color c) { return chevronLeft(12, c); }
    public static Icon chevronLeft(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.7f));
            Path2D p = new Path2D.Float();
            p.moveTo(10.4f, 3.4f);
            p.lineTo(5.4f, 8.0f);
            p.lineTo(10.4f, 12.6f);
            g.draw(p);
        });
    }

    public static Icon folder(Color c) { return folder(14, c); }
    public static Icon folder(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            Path2D p = new Path2D.Float();
            p.moveTo(2.2f, 4.0f);
            p.lineTo(6.2f, 4.0f);
            p.lineTo(7.8f, 5.6f);
            p.lineTo(13.8f, 5.6f);
            p.lineTo(13.8f, 12.6f);
            p.lineTo(2.2f, 12.6f);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon folderOpen(Color c) { return folderOpen(14, c); }
    public static Icon folderOpen(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            Path2D back = new Path2D.Float();
            back.moveTo(2.2f, 4.2f);
            back.lineTo(6.0f, 4.2f);
            back.lineTo(7.5f, 5.7f);
            back.lineTo(13.2f, 5.7f);
            back.lineTo(13.2f, 7.6f);
            g.draw(back);
            Path2D front = new Path2D.Float();
            front.moveTo(2.2f, 4.2f);
            front.lineTo(2.2f, 12.6f);
            front.lineTo(12.2f, 12.6f);
            front.lineTo(14.2f, 7.6f);
            front.lineTo(4.4f, 7.6f);
            front.lineTo(2.2f, 12.6f);
            g.draw(front);
        });
    }

    public static Icon branch(Color c) { return branch(14, c); }
    public static Icon branch(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new Ellipse2D.Float(3.0f, 2.2f, 3.2f, 3.2f));
            g.draw(new Ellipse2D.Float(3.0f, 10.6f, 3.2f, 3.2f));
            g.draw(new Ellipse2D.Float(10.0f, 5.6f, 3.2f, 3.2f));
            g.draw(new Line2D.Float(4.6f, 5.4f, 4.6f, 10.6f));
            Path2D curve = new Path2D.Float();
            curve.moveTo(4.6f, 10.2f);
            curve.curveTo(4.6f, 7.6f, 11.6f, 9.4f, 11.6f, 8.8f);
            g.draw(curve);
        });
    }

    public static Icon globe(Color c) { return globe(14, c); }
    public static Icon globe(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.3f));
            g.draw(new Ellipse2D.Float(2.2f, 2.2f, 11.6f, 11.6f));
            g.draw(new Ellipse2D.Float(5.4f, 2.2f, 5.2f, 11.6f));
            g.draw(new Line2D.Float(2.4f, 8.0f, 13.6f, 8.0f));
        });
    }

    public static Icon send(Color c) { return send(14, c); }
    public static Icon send(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            Path2D p = new Path2D.Float();
            p.moveTo(2.2f, 8.0f);
            p.lineTo(13.8f, 2.6f);
            p.lineTo(10.2f, 13.6f);
            p.lineTo(7.4f, 9.2f);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Float(7.4f, 9.2f, 13.8f, 2.6f));
        });
    }

    public static Icon organizer(Color c) { return organizer(14, c); }
    public static Icon organizer(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            g.draw(new RoundRectangle2D.Float(2.4f, 2.6f, 11.2f, 10.8f, 1.8f, 1.8f));
            g.draw(new Line2D.Float(2.4f, 6.2f, 13.6f, 6.2f));
            g.draw(new Line2D.Float(6.2f, 6.2f, 6.2f, 13.4f));
        });
    }

    public static Icon repeater(Color c) { return repeater(14, c); }
    public static Icon repeater(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new Line2D.Float(3.0f, 5.4f, 12.6f, 5.4f));
            Path2D topArr = new Path2D.Float();
            topArr.moveTo(10.2f, 3.2f);
            topArr.lineTo(12.8f, 5.4f);
            topArr.lineTo(10.2f, 7.6f);
            g.draw(topArr);
            g.draw(new Line2D.Float(13.0f, 10.6f, 3.4f, 10.6f));
            Path2D botArr = new Path2D.Float();
            botArr.moveTo(5.8f, 8.4f);
            botArr.lineTo(3.2f, 10.6f);
            botArr.lineTo(5.8f, 12.8f);
            g.draw(botArr);
        });
    }

    public static Icon locate(Color c) { return locate(14, c); }
    public static Icon locate(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(new Ellipse2D.Float(3.0f, 3.0f, 10.0f, 10.0f));
            g.fill(new Ellipse2D.Float(6.5f, 6.5f, 3.0f, 3.0f));
            g.draw(new Line2D.Float(8.0f, 1.4f, 8.0f, 3.8f));
            g.draw(new Line2D.Float(8.0f, 12.2f, 8.0f, 14.6f));
            g.draw(new Line2D.Float(1.4f, 8.0f, 3.8f, 8.0f));
            g.draw(new Line2D.Float(12.2f, 8.0f, 14.6f, 8.0f));
        });
    }

    public static Icon eye(Color c) { return eye(14, c); }
    public static Icon eye(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            Path2D p = new Path2D.Float();
            p.moveTo(1.8f, 8.0f);
            p.curveTo(4.4f, 3.8f, 11.6f, 3.8f, 14.2f, 8.0f);
            p.curveTo(11.6f, 12.2f, 4.4f, 12.2f, 1.8f, 8.0f);
            g.draw(p);
            g.fill(new Ellipse2D.Float(6.4f, 6.4f, 3.2f, 3.2f));
        });
    }

    public static Icon gear(Color c) { return gear(14, c); }
    public static Icon gear(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(new Ellipse2D.Float(5.2f, 5.2f, 5.6f, 5.6f));
            g.draw(new Line2D.Float(8.0f, 2.0f, 8.0f, 3.9f));
            g.draw(new Line2D.Float(8.0f, 12.1f, 8.0f, 14.0f));
            g.draw(new Line2D.Float(2.0f, 8.0f, 3.9f, 8.0f));
            g.draw(new Line2D.Float(12.1f, 8.0f, 14.0f, 8.0f));
            g.draw(new Line2D.Float(3.8f, 3.8f, 5.2f, 5.2f));
            g.draw(new Line2D.Float(10.8f, 10.8f, 12.2f, 12.2f));
            g.draw(new Line2D.Float(12.2f, 3.8f, 10.8f, 5.2f));
            g.draw(new Line2D.Float(5.2f, 10.8f, 3.8f, 12.2f));
        });
    }

    public static Icon book(Color c) { return book(14, c); }
    public static Icon book(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            Path2D p = new Path2D.Float();
            p.moveTo(2.4f, 3.4f);
            p.lineTo(7.4f, 4.4f);
            p.lineTo(7.4f, 13.0f);
            p.lineTo(2.4f, 12.0f);
            p.closePath();
            g.draw(p);
            Path2D q = new Path2D.Float();
            q.moveTo(13.6f, 3.4f);
            q.lineTo(8.6f, 4.4f);
            q.lineTo(8.6f, 13.0f);
            q.lineTo(13.6f, 12.0f);
            q.closePath();
            g.draw(q);
        });
    }

    public static Icon sparkle(Color c) { return sparkle(14, c); }
    public static Icon sparkle(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            Path2D p = new Path2D.Float();
            p.moveTo(8.0f, 1.8f);
            p.lineTo(9.4f, 6.6f);
            p.lineTo(14.2f, 8.0f);
            p.lineTo(9.4f, 9.4f);
            p.lineTo(8.0f, 14.2f);
            p.lineTo(6.6f, 9.4f);
            p.lineTo(1.8f, 8.0f);
            p.lineTo(6.6f, 6.6f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon refresh(Color c) { return refresh(14, c); }
    public static Icon refresh(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new Arc2D.Float(2.8f, 2.8f, 10.4f, 10.4f, 40f, 280f, Arc2D.OPEN));
            Path2D arr = new Path2D.Float();
            arr.moveTo(11.0f, 2.6f);
            arr.lineTo(13.2f, 4.6f);
            arr.lineTo(10.6f, 6.2f);
            g.draw(arr);
        });
    }

    public static Icon clock(Color c) { return clock(14, c); }
    public static Icon clock(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(new Ellipse2D.Float(2.4f, 2.4f, 11.2f, 11.2f));
            g.draw(new Line2D.Float(8.0f, 4.8f, 8.0f, 8.2f));
            g.draw(new Line2D.Float(8.0f, 8.2f, 10.6f, 9.8f));
        });
    }

    public static Icon pin(Color c) { return pin(14, c); }
    public static Icon pin(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(new Line2D.Float(5.0f, 3.0f, 11.0f, 3.0f));
            g.draw(new Line2D.Float(6.2f, 3.0f, 5.4f, 8.6f));
            g.draw(new Line2D.Float(9.8f, 3.0f, 10.6f, 8.6f));
            g.draw(new Line2D.Float(3.8f, 8.6f, 12.2f, 8.6f));
            g.draw(new Line2D.Float(8.0f, 8.6f, 8.0f, 13.8f));
        });
    }

    public static Icon detach(Color c) { return detach(14, c); }
    public static Icon detach(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            Path2D box = new Path2D.Float();
            box.moveTo(7.4f, 3.2f);
            box.lineTo(3.0f, 3.2f);
            box.lineTo(3.0f, 13.0f);
            box.lineTo(12.8f, 13.0f);
            box.lineTo(12.8f, 8.6f);
            g.draw(box);
            g.draw(new Line2D.Float(7.8f, 8.2f, 13.2f, 2.8f));
            g.draw(new Line2D.Float(9.6f, 2.8f, 13.2f, 2.8f));
            g.draw(new Line2D.Float(13.2f, 2.8f, 13.2f, 6.4f));
        });
    }

    public static Icon expand(Color c) { return expand(14, c); }
    public static Icon expand(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new Line2D.Float(9.2f, 6.8f, 13.2f, 2.8f));
            g.draw(new Line2D.Float(9.8f, 2.8f, 13.2f, 2.8f));
            g.draw(new Line2D.Float(13.2f, 2.8f, 13.2f, 6.2f));
            g.draw(new Line2D.Float(6.8f, 9.2f, 2.8f, 13.2f));
            g.draw(new Line2D.Float(2.8f, 9.8f, 2.8f, 13.2f));
            g.draw(new Line2D.Float(2.8f, 13.2f, 6.2f, 13.2f));
        });
    }

    public static Icon collapse(Color c) { return collapse(14, c); }
    public static Icon collapse(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.45f));
            g.draw(new Line2D.Float(13.2f, 2.8f, 9.0f, 7.0f));
            g.draw(new Line2D.Float(9.0f, 3.8f, 9.0f, 7.0f));
            g.draw(new Line2D.Float(9.0f, 7.0f, 12.2f, 7.0f));
            g.draw(new Line2D.Float(2.8f, 13.2f, 7.0f, 9.0f));
            g.draw(new Line2D.Float(3.8f, 9.0f, 7.0f, 9.0f));
            g.draw(new Line2D.Float(7.0f, 9.0f, 7.0f, 12.2f));
        });
    }

    public static Icon menuDots(Color c) { return menuDots(14, c); }
    public static Icon menuDots(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.fill(new Ellipse2D.Float(6.6f, 2.4f, 2.8f, 2.8f));
            g.fill(new Ellipse2D.Float(6.6f, 6.6f, 2.8f, 2.8f));
            g.fill(new Ellipse2D.Float(6.6f, 10.8f, 2.8f, 2.8f));
        });
    }

    public static Icon dot(Color c) { return dot(10, c); }
    public static Icon dot(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.fill(new Ellipse2D.Float(3.5f, 3.5f, 9.0f, 9.0f));
        });
    }

    public static Icon alert(Color c) { return alert(14, c); }
    public static Icon alert(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            Path2D tri = new Path2D.Float();
            tri.moveTo(8.0f, 2.4f);
            tri.lineTo(14.0f, 13.2f);
            tri.lineTo(2.0f, 13.2f);
            tri.closePath();
            g.draw(tri);
            g.draw(new Line2D.Float(8.0f, 6.2f, 8.0f, 9.4f));
            g.fill(new Ellipse2D.Float(7.2f, 10.8f, 1.6f, 1.6f));
        });
    }

    public static Icon info(Color c) { return info(14, c); }
    public static Icon info(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            g.draw(new Ellipse2D.Float(2.2f, 2.2f, 11.6f, 11.6f));
            g.fill(new Ellipse2D.Float(7.1f, 4.6f, 1.8f, 1.8f));
            g.draw(new Line2D.Float(8.0f, 7.6f, 8.0f, 11.4f));
        });
    }

    public static Icon emptyTray(Color c) { return emptyTray(32, c); }
    public static Icon emptyTray(int sz, Color c) {
        return make(sz, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.25f));
            g.draw(new RoundRectangle2D.Float(2.0f, 3.0f, 12.0f, 10.0f, 2.0f, 2.0f));
            Path2D tray = new Path2D.Float();
            tray.moveTo(2.2f, 9.0f);
            tray.lineTo(5.6f, 9.0f);
            tray.lineTo(6.6f, 10.8f);
            tray.lineTo(9.4f, 10.8f);
            tray.lineTo(10.4f, 9.0f);
            tray.lineTo(13.8f, 9.0f);
            g.draw(tray);
        });
    }

    // Node-type semantic icons for Tree-First Workspace
    public static Icon screenNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.3f));
            g.draw(new RoundRectangle2D.Float(2.0f, 2.6f, 12.0f, 8.6f, 1.6f, 1.6f));
            g.draw(new Line2D.Float(5.4f, 13.4f, 10.6f, 13.4f));
            g.draw(new Line2D.Float(8.0f, 11.2f, 8.0f, 13.4f));
        });
    }

    public static Icon menuNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.5f));
            g.draw(new Line2D.Float(2.6f, 4.2f, 13.4f, 4.2f));
            g.draw(new Line2D.Float(2.6f, 8.0f, 13.4f, 8.0f));
            g.draw(new Line2D.Float(2.6f, 11.8f, 10.2f, 11.8f));
        });
    }

    public static Icon buttonNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.35f));
            g.draw(new RoundRectangle2D.Float(2.0f, 4.6f, 12.0f, 6.8f, 3.0f, 3.0f));
            g.fill(new Ellipse2D.Float(6.8f, 6.8f, 2.4f, 2.4f));
        });
    }

    public static Icon modalNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.3f));
            g.draw(new RoundRectangle2D.Float(2.2f, 3.0f, 11.6f, 10.0f, 1.8f, 1.8f));
            g.draw(new Line2D.Float(2.2f, 6.0f, 13.8f, 6.0f));
        });
    }

    public static Icon formNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.3f));
            g.draw(new RoundRectangle2D.Float(2.6f, 2.2f, 10.8f, 11.6f, 1.6f, 1.6f));
            g.draw(new Line2D.Float(4.8f, 5.4f, 11.2f, 5.4f));
            g.draw(new Line2D.Float(4.8f, 8.2f, 11.2f, 8.2f));
            g.draw(new Line2D.Float(4.8f, 11.0f, 9.0f, 11.0f));
        });
    }

    public static Icon apiNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.4f));
            Path2D l = new Path2D.Float();
            l.moveTo(5.6f, 4.2f);
            l.lineTo(2.4f, 8.0f);
            l.lineTo(5.6f, 11.8f);
            g.draw(l);
            Path2D r = new Path2D.Float();
            r.moveTo(10.4f, 4.2f);
            r.lineTo(13.6f, 8.0f);
            r.lineTo(10.4f, 11.8f);
            g.draw(r);
            g.draw(new Line2D.Float(9.0f, 3.6f, 7.0f, 12.4f));
        });
    }

    public static Icon wsNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            Path2D bolt = new Path2D.Float();
            bolt.moveTo(9.2f, 1.8f);
            bolt.lineTo(4.0f, 8.8f);
            bolt.lineTo(7.8f, 8.8f);
            bolt.lineTo(6.8f, 14.2f);
            bolt.lineTo(12.0f, 7.2f);
            bolt.lineTo(8.2f, 7.2f);
            bolt.closePath();
            g.fill(bolt);
        });
    }

    public static Icon ghostNode(Color c) {
        return make(14, c, (g, col) -> {
            g.setColor(col);
            g.setStroke(stroke(1.3f));
            Path2D p = new Path2D.Float();
            p.moveTo(3.4f, 13.4f);
            p.lineTo(3.4f, 7.2f);
            p.curveTo(3.4f, 3.4f, 12.6f, 3.4f, 12.6f, 7.2f);
            p.lineTo(12.6f, 13.4f);
            p.lineTo(10.4f, 11.8f);
            p.lineTo(8.0f, 13.4f);
            p.lineTo(5.6f, 11.8f);
            p.closePath();
            g.draw(p);
            g.fill(new Ellipse2D.Float(5.4f, 6.6f, 1.5f, 1.5f));
            g.fill(new Ellipse2D.Float(9.1f, 6.6f, 1.5f, 1.5f));
        });
    }

    public static Icon forNodeType(String typeKey, Color c) {
        if (typeKey == null) {
            return branch(c);
        }
        String k = typeKey.trim().toUpperCase(Locale.ROOT);
        switch (k) {
            case "SCREEN":
            case "PAGE":
                return screenNode(c);
            case "MENU":
            case "NAV":
                return menuNode(c);
            case "BUTTON":
            case "CLICK":
                return buttonNode(c);
            case "MODAL":
            case "DIALOG":
                return modalNode(c);
            case "FORM":
            case "SUBMIT":
                return formNode(c);
            case "API":
            case "HTTP":
                return apiNode(c);
            case "WEBSOCKET":
            case "WS":
                return wsNode(c);
            case "GHOST":
            case "JS_HINT":
                return ghostNode(c);
            case "HELD_PENDING":
            case "HELD":
                return shieldAlert(c);
            case "CLUSTER":
                return folder(c);
            default:
                return branch(c);
        }
    }
}
