package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * 2- or 3-option segmented switch (e.g. Dừng | Capture | Auto) obeying {@link Theme}.
 */
public final class SegmentedToggle extends JPanel {

    public static final class Option {
        public final String label;
        public final Icon icon;
        public final Theme.Token activeToken;
        public final String tooltip;

        public Option(String label) {
            this(label, null, Theme.Token.ACCENT, null);
        }

        public Option(String label, Theme.Token activeToken) {
            this(label, null, activeToken, null);
        }

        public Option(String label, Icon icon, Theme.Token activeToken, String tooltip) {
            this.label = label != null ? label : "";
            this.icon = icon;
            this.activeToken = activeToken != null ? activeToken : Theme.Token.ACCENT;
            this.tooltip = tooltip;
        }
    }

    private final List<Option> options = new ArrayList<>();
    private final List<SegmentButton> buttons = new ArrayList<>();
    private final List<IntConsumer> listeners = new CopyOnWriteArrayList<>();
    private int selectedIndex = 0;
    private AutoCloseable themeSub;

    public SegmentedToggle(Option... opts) {
        super(new GridLayout(1, Math.max(1, opts != null ? opts.length : 1), 2, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        if (opts != null) {
            for (int i = 0; i < opts.length; i++) {
                Option o = opts[i];
                options.add(o);
                final int idx = i;
                SegmentButton btn = new SegmentButton(o, idx);
                btn.addActionListener(e -> setSelectedIndex(idx, true));
                buttons.add(btn);
                add(btn);
            }
        }
        refreshStyles();
    }

    public SegmentedToggle(String... labels) {
        this(toOptions(labels));
    }

    private static Option[] toOptions(String... labels) {
        if (labels == null) {
            return new Option[0];
        }
        Option[] out = new Option[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = new Option(labels[i]);
        }
        return out;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int idx) {
        setSelectedIndex(idx, false);
    }

    private void setSelectedIndex(int idx, boolean fire) {
        if (idx < 0 || idx >= buttons.size()) {
            return;
        }
        boolean changed = (this.selectedIndex != idx);
        this.selectedIndex = idx;
        refreshStyles();
        if (fire && changed) {
            for (IntConsumer l : listeners) {
                l.accept(idx);
            }
        }
    }

    public void onChange(IntConsumer listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void refreshStyles() {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).refresh(i == selectedIndex);
        }
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
            themeSub = Theme.onThemeChange(this::refreshStyles);
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
        if (buttons != null) {
            refreshStyles();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (buttons != null) {
            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).setForeground((i == selectedIndex)
                    ? Theme.token(buttons.get(i).opt.activeToken).text()
                    : Theme.muted());
            }
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float w = getWidth() - 1f;
            float h = getHeight() - 1f;
            g2.setColor(Theme.surfaceAlt());
            g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
            g2.setColor(Theme.border());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    private final class SegmentButton extends JButton {
        private final Option opt;
        private boolean selected;

        SegmentButton(Option opt, int idx) {
            super(opt.label, opt.icon);
            this.opt = opt;
            if (opt.tooltip != null) {
                setToolTipText(opt.tooltip);
            }
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            setIconTextGap(5);
            refresh(idx == selectedIndex);
        }

        void refresh(boolean sel) {
            this.selected = sel;
            setFont(sel ? FontManager.uiSemiBold() : FontManager.uiRegular());
            setForeground(sel ? Theme.token(opt.activeToken).text() : Theme.muted());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float w = getWidth() - 1f;
                float h = getHeight() - 1f;
                if (selected) {
                    Theme.PaletteEntry entry = Theme.token(opt.activeToken);
                    g2.setColor(entry.fill());
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                    g2.setColor(entry.border());
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                } else if (getModel().isRollover()) {
                    g2.setColor(Theme.surfaceHover());
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
