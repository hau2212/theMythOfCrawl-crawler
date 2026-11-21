package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
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
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Authentic Burp Suite 2025/2026 custom Swing primitives:
 * - {@link BurpToggleSwitch} (32x16 iOS-style pill toggle matching Burp "Capturing:" / "Filter on" switches)
 * - {@link BurpOrangePillButton} (rounded #FF6633 CTA pill matching "+ New scan")
 * - {@link BurpFilterPill} (rounded #2B5B84 active pill matching "Running", "High", "Critical", etc.)
 * - {@link BurpSeverityBadge} (circular count badge for High/Warn/Info/Total)
 * - {@link BurpProgressBar} (slim rounded #FF6633 progress bar)
 * - {@link BurpPanelHeader} (title + gear / help / expand-restore header strip)
 * - {@link BurpTaskCard} (Burp Dashboard task card with switches, badges, progress & "View details >>")
 */
public final class BurpComponents {

    private BurpComponents() {
    }

    /**
     * Smooth 32x16 iOS-style pill toggle switch (Burp Dashboard / Proxy "Filter on" style).
     */
    public static final class BurpToggleSwitch extends JToggleButton {
        private final MontoyaApi api;

        public BurpToggleSwitch(MontoyaApi api, boolean initialSelected) {
            super("", initialSelected);
            this.api = api;
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(34, 18));
            setMinimumSize(new Dimension(34, 18));
            setMaximumSize(new Dimension(34, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean dark = AuditTheme.isDark(api, this);
                boolean on = isSelected();
                Color track = on
                    ? AuditTheme.BURP_SWITCH_ON_TRACK
                    : (dark ? AuditTheme.BURP_SWITCH_OFF_DARK : AuditTheme.BURP_SWITCH_OFF_LIGHT);
                if (!isEnabled()) {
                    track = AuditTheme.blend(track, AuditTheme.canvasSurface(dark), 0.45f);
                } else if (getModel().isRollover()) {
                    track = AuditTheme.blend(track, Color.WHITE, 0.10f);
                }

                int w = 32;
                int h = 16;
                int x = Math.max(0, (getWidth() - w) / 2);
                int y = Math.max(0, (getHeight() - h) / 2);

                g2.setColor(track);
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));

                int knobSize = 12;
                int kx = on ? (x + w - knobSize - 2) : (x + 2);
                int ky = y + 2;
                g2.setColor(Color.WHITE);
                g2.fill(new Ellipse2D.Float(kx, ky, knobSize, knobSize));
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Burp v2026.10.1 Burnt Orange (#D9621C) 6px-rounded CTA button ("New crawl", "+ Nạp Excel", etc.).
     */
    public static final class BurpOrangePillButton extends JButton {
        private final MontoyaApi api;

        public BurpOrangePillButton(MontoyaApi api, String text, Icon icon) {
            super(text, icon);
            this.api = api;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(Color.WHITE);
            setFont(AuditTheme.displayFont(api, this).deriveFont(Font.BOLD));
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            setIconTextGap(6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = AuditTheme.BURP_CTA_ORANGE;
                if (!isEnabled()) {
                    bg = AuditTheme.blend(bg, AuditTheme.canvasSurface(AuditTheme.isDark(api, this)), 0.55f);
                } else if (getModel().isPressed()) {
                    bg = bg.darker();
                } else if (getModel().isRollover()) {
                    bg = AuditTheme.BURP_CTA_ORANGE_HOVER;
                }
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6f, 6f));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Clean 6px-rounded filter toggle ("Running", "High", "Critical", etc.)
     * using subtle pastel active fills (#DBEAFE / #1E3A8A in Light, #264F78 / #FFFFFF in Dark).
     */
    public static final class BurpFilterPill extends JToggleButton {
        private final MontoyaApi api;
        private Color activeFill;

        public BurpFilterPill(MontoyaApi api, String text, boolean selected) {
            super(text, selected);
            this.api = api;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
            refreshStyle();
            addItemListener(e -> refreshStyle());
        }

        public void setActiveFill(Color activeFill) {
            this.activeFill = activeFill;
            refreshStyle();
        }

        public void refreshStyle() {
            boolean dark = AuditTheme.isDark(api, this);
            Font base = AuditTheme.displayFont(api, this);
            float sz = Math.max(11f, base.getSize2D() - 0.5f);
            setFont(base.deriveFont(isSelected() ? Font.BOLD : Font.PLAIN, sz));
            if (isSelected()) {
                if (activeFill != null && !activeFill.equals(AuditTheme.BURP_ACTIVE_PILL_BG)) {
                    Color fill = dark
                        ? AuditTheme.blend(com.mythofcrawl.crawler.ui.theme.Theme.surface(true), activeFill, 0.30f)
                        : AuditTheme.blend(Color.WHITE, activeFill, 0.16f);
                    setForeground(dark ? Color.WHITE : AuditTheme.blend(activeFill, Color.BLACK, 0.32f));
                } else {
                    setForeground(AuditTheme.pastelActiveFg(dark));
                }
            } else {
                setForeground(AuditTheme.neutralButtonFg(dark));
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean dark = AuditTheme.isDark(api, this);
                float w = getWidth() - 1f;
                float h = getHeight() - 1f;
                if (isSelected()) {
                    Color bg;
                    Color border;
                    if (activeFill != null && !activeFill.equals(AuditTheme.BURP_ACTIVE_PILL_BG)) {
                        bg = dark
                            ? AuditTheme.blend(com.mythofcrawl.crawler.ui.theme.Theme.surface(true), activeFill, 0.30f)
                            : AuditTheme.blend(Color.WHITE, activeFill, 0.16f);
                        border = dark ? activeFill : AuditTheme.blend(Color.WHITE, activeFill, 0.45f);
                    } else {
                        bg = AuditTheme.pastelActiveBg(dark);
                        border = AuditTheme.pastelActiveBorder(dark);
                    }
                    if (getModel().isRollover()) {
                        bg = AuditTheme.blend(bg, Color.WHITE, dark ? 0.10f : 0.25f);
                    }
                    g2.setColor(bg);
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, 6f, 6f));
                    g2.setColor(border);
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, 6f, 6f));
                } else {
                    Color bg = getModel().isRollover()
                        ? AuditTheme.cardSurface(dark)
                        : AuditTheme.panelSurface(dark);
                    g2.setColor(bg);
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, 6f, 6f));
                    g2.setColor(AuditTheme.neutralButtonBorder(dark));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, 6f, 6f));
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (api != null || isDisplayable()) {
                refreshStyle();
            }
        }
    }

    /**
     * Circular/pill severity count badge (e.g. 1, 13, 61 in Burp Red / Amber / Blue / Grey).
     */
    public static final class BurpSeverityBadge extends JLabel {
        private Color badgeBg;

        public BurpSeverityBadge(String text, Color bg) {
            super(text, SwingConstants.CENTER);
            this.badgeBg = bg;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
            setFont(getFont().deriveFont(Font.BOLD, Math.max(11f, getFont().getSize2D() - 0.5f)));
            setForeground(AuditTheme.contrastForeground(bg));
        }

        public void setBadge(String text, Color bg) {
            setText(text);
            this.badgeBg = bg;
            setForeground(AuditTheme.contrastForeground(bg));
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            int h = Math.max(18, d.height);
            int w = Math.max(h, d.width);
            return new Dimension(w, h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getHeight();
                g2.setColor(badgeBg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, h, h));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Slim rounded Burp Orange (#FF6633) progress bar.
     */
    public static final class BurpProgressBar extends JComponent {
        private final MontoyaApi api;
        private int value = 0;
        private int max = 100;
        private String label = "";

        public BurpProgressBar(MontoyaApi api) {
            this.api = api;
            setPreferredSize(new Dimension(140, 10));
            setMinimumSize(new Dimension(70, 8));
        }

        public void setProgress(int value, int max, String label) {
            this.max = Math.max(1, max);
            this.value = Math.max(0, Math.min(this.max, value));
            this.label = label != null ? label : "";
            setToolTipText(this.label.isEmpty() ? null : this.label);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean dark = AuditTheme.isDark(api, this);
                int w = getWidth();
                int h = Math.min(10, Math.max(6, getHeight()));
                int y = (getHeight() - h) / 2;

                g2.setColor(com.mythofcrawl.crawler.ui.theme.Theme.border(dark));
                g2.fill(new RoundRectangle2D.Float(0, y, w, h, h, h));

                double ratio = value / (double) max;
                int fw = (int) Math.round(w * ratio);
                if (fw > 0) {
                    g2.setColor(AuditTheme.BURP_ORANGE);
                    g2.fill(new RoundRectangle2D.Float(0, y, Math.max(h, Math.min(w, fw)), h, h, h));
                }
            } finally {
                g2.dispose();
            }
        }
    }

    public static JButton createIconButton(MontoyaApi api, Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(24, 24));
        b.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        return b;
    }

    /**
     * Burp Dashboard panel header: bold title on left, optional right actions + gear / help / expand-restore icons.
     */
    public static final class BurpPanelHeader extends JPanel {
        private final MontoyaApi api;
        private final JLabel lblTitle = new JLabel();
        private final JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        private final JButton btnGear;
        private final JButton btnHelp;
        private final JButton btnExpand;
        private boolean expanded = false;

        public BurpPanelHeader(
            MontoyaApi api,
            String title,
            Runnable onGear,
            Runnable onHelp,
            Runnable onToggleExpand
        ) {
            super(new BorderLayout(8, 0));
            this.api = api;
            lblTitle.setText(title);

            boolean dark = AuditTheme.isDark(api, this);
            Color iconColor = AuditTheme.mutedForeground(dark);
            btnGear = createIconButton(api, BurpIcons.gear(iconColor), "Cài đặt");
            btnHelp = createIconButton(api, BurpIcons.helpCircle(iconColor), "Hướng dẫn");
            btnExpand = createIconButton(api, BurpIcons.expandPane(iconColor), "Phóng to / Thu gọn bảng này");

            btnGear.setVisible(onGear != null);
            if (onGear != null) {
                btnGear.addActionListener(e -> onGear.run());
            }
            btnHelp.setVisible(onHelp != null);
            if (onHelp != null) {
                btnHelp.addActionListener(e -> onHelp.run());
            }
            btnExpand.setVisible(onToggleExpand != null);
            if (onToggleExpand != null) {
                btnExpand.addActionListener(e -> onToggleExpand.run());
            }

            rightActions.setOpaque(false);
            rightActions.add(btnGear);
            rightActions.add(btnHelp);
            rightActions.add(btnExpand);

            add(lblTitle, BorderLayout.WEST);
            add(rightActions, BorderLayout.EAST);
            applyTheme();
        }

        public void setTitle(String title) {
            lblTitle.setText(title);
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
            refreshIcons();
        }

        public void addCustomAction(JComponent comp) {
            rightActions.add(comp, 0);
        }

        private void refreshIcons() {
            boolean dark = AuditTheme.isDark(api, this);
            Color iconColor = AuditTheme.mutedForeground(dark);
            btnGear.setIcon(BurpIcons.gear(iconColor));
            btnHelp.setIcon(BurpIcons.helpCircle(iconColor));
            btnExpand.setIcon(expanded ? BurpIcons.collapsePane(iconColor) : BurpIcons.expandPane(iconColor));
        }

        public void applyTheme() {
            boolean dark = AuditTheme.isDark(api, this);
            setOpaque(true);
            setBackground(AuditTheme.panelSurface(dark));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AuditTheme.borderColor(dark)),
                BorderFactory.createEmptyBorder(5, 10, 5, 8)));
            Font base = AuditTheme.displayFont(api, this);
            lblTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.5f));
            lblTitle.setForeground(AuditTheme.textPrimary(dark));
            refreshIcons();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (lblTitle != null) {
                applyTheme();
            }
        }
    }

    /**
     * Rounded Burp Dashboard Task Card ("Crawl Task", "Extender Push", etc.)
     * with pause/play, gear, delete, expand buttons, toggle switches, severity badges,
     * orange progress bar, and clickable "View details >>" link.
     */
    public static final class BurpTaskCard extends JPanel {
        private final MontoyaApi api;
        private final JLabel lblTitle = new JLabel();
        private final JLabel lblSubtitle = new JLabel();
        private final JButton btnPausePlay;
        private final JButton btnConfig;
        private final JButton btnTrash;
        private final JButton btnExpand;
        private final JPanel controlsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 2));
        private final JPanel badgesRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        private final BurpProgressBar progressBar;
        private final JLabel lblStatus = new JLabel();
        private final JLabel lblViewDetails = new JLabel("View details >>");

        public BurpTaskCard(
            MontoyaApi api,
            String title,
            Runnable onPausePlay,
            Runnable onConfig,
            Runnable onTrash,
            Runnable onExpand,
            Runnable onViewDetails
        ) {
            super(new BorderLayout(6, 6));
            this.api = api;
            this.progressBar = new BurpProgressBar(api);

            lblTitle.setText(title);
            boolean dark = AuditTheme.isDark(api, this);
            Color ic = AuditTheme.mutedForeground(dark);
            btnPausePlay = createIconButton(api, BurpIcons.pause(ic), "Tạm dừng / Tiếp tục");
            btnConfig = createIconButton(api, BurpIcons.gear(ic), "Cấu hình");
            btnTrash = createIconButton(api, BurpIcons.trash(ic), "Xóa / Reset");
            btnExpand = createIconButton(api, BurpIcons.expandPane(ic), "Mở rộng");

            btnPausePlay.setVisible(onPausePlay != null);
            if (onPausePlay != null) btnPausePlay.addActionListener(e -> onPausePlay.run());
            btnConfig.setVisible(onConfig != null);
            if (onConfig != null) btnConfig.addActionListener(e -> onConfig.run());
            btnTrash.setVisible(onTrash != null);
            if (onTrash != null) btnTrash.addActionListener(e -> onTrash.run());
            btnExpand.setVisible(onExpand != null);
            if (onExpand != null) btnExpand.addActionListener(e -> onExpand.run());

            JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            topLeft.setOpaque(false);
            topLeft.add(lblTitle);
            topLeft.add(btnPausePlay);
            topLeft.add(btnConfig);
            topLeft.add(btnTrash);
            topLeft.add(btnExpand);

            JPanel topRow = new JPanel(new BorderLayout(6, 0));
            topRow.setOpaque(false);
            topRow.add(topLeft, BorderLayout.WEST);
            topRow.add(lblSubtitle, BorderLayout.EAST);

            controlsRow.setOpaque(false);
            badgesRow.setOpaque(false);
            JPanel midRow = new JPanel(new BorderLayout(6, 0));
            midRow.setOpaque(false);
            midRow.add(controlsRow, BorderLayout.CENTER);
            midRow.add(badgesRow, BorderLayout.EAST);

            lblViewDetails.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            lblViewDetails.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onViewDetails != null) {
                        onViewDetails.run();
                    }
                }
            });

            JPanel footerRow = new JPanel(new BorderLayout(6, 0));
            footerRow.setOpaque(false);
            footerRow.add(lblStatus, BorderLayout.CENTER);
            footerRow.add(lblViewDetails, BorderLayout.EAST);

            JPanel bottomWrap = new JPanel(new BorderLayout(0, 5));
            bottomWrap.setOpaque(false);
            bottomWrap.add(progressBar, BorderLayout.NORTH);
            bottomWrap.add(footerRow, BorderLayout.SOUTH);

            add(topRow, BorderLayout.NORTH);
            add(midRow, BorderLayout.CENTER);
            add(bottomWrap, BorderLayout.SOUTH);
            applyTheme();
        }

        public void addControlItem(JComponent comp) {
            controlsRow.add(comp);
        }

        public void addBadge(JComponent badge) {
            badgesRow.add(badge);
        }

        public void setSubtitle(String text) {
            lblSubtitle.setText(text);
        }

        public void setStatusText(String text) {
            lblStatus.setText(text);
        }

        public void setProgress(int value, int max, String tooltip) {
            progressBar.setProgress(value, max, tooltip);
        }

        public void setPausedIcon(boolean paused) {
            boolean dark = AuditTheme.isDark(api, this);
            Color ic = AuditTheme.mutedForeground(dark);
            btnPausePlay.setIcon(paused ? BurpIcons.play(AuditTheme.okForeground(dark)) : BurpIcons.pause(ic));
        }

        public void applyTheme() {
            boolean dark = AuditTheme.isDark(api, this);
            AuditTheme.styleActionCard(this, dark);
            setBackground(AuditTheme.cardSurface(dark));
            Font base = AuditTheme.displayFont(api, this);
            lblTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 0.5f));
            lblTitle.setForeground(AuditTheme.textPrimary(dark));
            lblSubtitle.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 0.5f)));
            lblSubtitle.setForeground(AuditTheme.mutedForeground(dark));
            lblStatus.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 0.5f)));
            lblStatus.setForeground(AuditTheme.mutedForeground(dark));
            lblViewDetails.setFont(base.deriveFont(Font.BOLD, Math.max(11f, base.getSize2D() - 0.5f)));
            lblViewDetails.setForeground(AuditTheme.accentForeground(dark));
            Color ic = AuditTheme.mutedForeground(dark);
            btnConfig.setIcon(BurpIcons.gear(ic));
            btnTrash.setIcon(BurpIcons.trash(ic));
            btnExpand.setIcon(BurpIcons.expandPane(ic));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (lblTitle != null && progressBar != null) {
                applyTheme();
            }
        }
    }

    /**
     * Minimalist Burp v2026.10.1 centered empty state panel ("No items to show").
     */
    public static final class EmptyStatePanel extends JPanel {
        private final MontoyaApi api;
        private final JLabel lblIcon = new JLabel("", SwingConstants.CENTER);
        private final JLabel lblTitle = new JLabel("No items to show", SwingConstants.CENTER);
        private final JLabel lblSubtitle = new JLabel("", SwingConstants.CENTER);

        public EmptyStatePanel(MontoyaApi api, String title, String subtitle) {
            super(new java.awt.GridBagLayout());
            this.api = api;
            if (title != null && !title.trim().isEmpty()) {
                lblTitle.setText(title);
            }
            lblSubtitle.setText(subtitle != null ? subtitle : "");
            lblSubtitle.setVisible(subtitle != null && !subtitle.trim().isEmpty());

            JPanel box = new JPanel();
            box.setOpaque(false);
            box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
            lblIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
            lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            lblSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            box.add(lblIcon);
            box.add(javax.swing.Box.createVerticalStrut(8));
            box.add(lblTitle);
            if (lblSubtitle.isVisible()) {
                box.add(javax.swing.Box.createVerticalStrut(4));
                box.add(lblSubtitle);
            }
            add(box);
            applyTheme();
        }

        public void setMessages(String title, String subtitle) {
            lblTitle.setText(title != null && !title.trim().isEmpty() ? title : "No items to show");
            lblSubtitle.setText(subtitle != null ? subtitle : "");
            lblSubtitle.setVisible(subtitle != null && !subtitle.trim().isEmpty());
        }

        public void applyTheme() {
            boolean dark = AuditTheme.isDark(api, this);
            setOpaque(true);
            setBackground(AuditTheme.tableSurface(dark));
            lblIcon.setIcon(BurpIcons.emptyTray(AuditTheme.mutedForeground(dark)));
            Font base = AuditTheme.displayFont(api, this);
            lblTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.0f));
            lblTitle.setForeground(AuditTheme.textPrimary(dark));
            lblSubtitle.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 0.5f)));
            lblSubtitle.setForeground(AuditTheme.mutedForeground(dark));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (lblTitle != null) {
                applyTheme();
            }
        }
    }
}
