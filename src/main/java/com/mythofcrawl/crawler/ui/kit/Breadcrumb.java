package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Clickable hierarchical breadcrumb path bar with automatic middle ellipsis
 * when available width cannot fit all segments (LAW 1–4 compliant).
 */
public final class Breadcrumb extends JPanel {

    private List<String> segments = new ArrayList<>();
    private IntConsumer onSegmentClick;
    private AutoCloseable themeSub;

    public Breadcrumb() {
        super(new FlowLayout(FlowLayout.LEFT, 2, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rebuild();
            }
        });
    }

    public void setPath(List<String> segments, IntConsumer onSegmentClick) {
        this.segments = segments != null ? new ArrayList<>(segments) : new ArrayList<>();
        this.onSegmentClick = onSegmentClick;
        rebuild();
    }

    public List<String> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    public void rebuild() {
        removeAll();
        if (segments.isEmpty()) {
            revalidate();
            repaint();
            return;
        }

        int avail = getWidth();
        List<Integer> visibleIndices = computeVisibleIndices(avail);
        boolean insertedEllipsis = false;

        for (int pos = 0; pos < visibleIndices.size(); pos++) {
            int segIdx = visibleIndices.get(pos);
            if (pos > 0) {
                add(createSeparator());
            }
            if (segIdx < 0) {
                if (!insertedEllipsis) {
                    JLabel ell = new JLabel("...");
                    ell.setFont(FontManager.uiRegular());
                    ell.setForeground(Theme.muted());
                    add(ell);
                    insertedEllipsis = true;
                }
                continue;
            }
            boolean isLast = (segIdx == segments.size() - 1);
            add(createCrumbButton(segments.get(segIdx), segIdx, isLast));
        }

        revalidate();
        repaint();
    }

    private List<Integer> computeVisibleIndices(int availWidth) {
        int n = segments.size();
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        if (n <= 3 || availWidth <= 0) {
            return all;
        }

        int estWidth = 0;
        for (String s : segments) {
            estWidth += Math.max(36, s.length() * 7) + 22;
        }
        if (estWidth <= availWidth) {
            return all;
        }

        // Collapse middle segments cleanly while preserving first + trailing segments
        List<Integer> collapsed = new ArrayList<>();
        collapsed.add(0);
        collapsed.add(-1);
        int used = Math.max(40, segments.get(0).length() * 7) + 46;
        int startTail = n - 1;
        while (startTail > 1) {
            int segW = Math.max(40, segments.get(startTail - 1).length() * 7) + 24;
            if (used + segW + Math.max(40, segments.get(n - 1).length() * 7) > availWidth) {
                break;
            }
            used += segW;
            startTail--;
        }
        for (int i = Math.max(1, startTail); i < n; i++) {
            collapsed.add(i);
        }
        return collapsed;
    }

    private JLabel createSeparator() {
        JLabel sep = new JLabel(VectorIcons.chevronRight(10, Theme.muted()));
        sep.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        return sep;
    }

    private JButton createCrumbButton(String label, int index, boolean isLast) {
        JButton b = new JButton(label);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        b.setFont(isLast ? FontManager.uiSemiBold() : FontManager.uiRegular());
        b.setForeground(isLast ? Theme.text() : Theme.muted());
        if (!isLast && onSegmentClick != null) {
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> onSegmentClick.accept(index));
        }
        return b;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (segments != null) {
            rebuild();
        }
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        for (int i = 0; i < getComponentCount(); i++) {
            java.awt.Component c = getComponent(i);
            if (c instanceof JButton) {
                boolean isLast = (i == getComponentCount() - 1);
                c.setForeground(isLast ? Theme.text() : Theme.muted());
            } else if (c instanceof JLabel) {
                JLabel l = (JLabel) c;
                if (l.getIcon() != null) {
                    l.setIcon(VectorIcons.chevronRight(10, Theme.muted()));
                } else {
                    l.setForeground(Theme.muted());
                }
            }
        }
        super.paintComponent(g);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::rebuild);
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
}
