package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Clean non-HTML structured guidance & troubleshooting card built from
 * {@link BoxLayout}, {@link WrappedText}, {@link Pill}, and {@link VectorIcons}.
 */
public final class StructuredAdviceCard extends JPanel {

    private final Theme.Token token;
    private final JLabel lblIcon = new JLabel();
    private final JLabel lblTitle = new JLabel();
    private final Pill badge;
    private final WrappedText summaryText;
    private final JPanel stepsBox = new JPanel();
    private final java.util.List<JLabel> stepNumLabels = new java.util.ArrayList<>();
    private final java.util.List<WrappedText> stepTexts = new java.util.ArrayList<>();
    private AutoCloseable themeSub;

    public StructuredAdviceCard(
        Theme.Token token,
        String title,
        String badgeText,
        String summary,
        List<String> bulletSteps
    ) {
        this.token = token != null ? token : Theme.Token.INFO;
        this.badge = (badgeText != null && !badgeText.trim().isEmpty())
            ? new Pill(badgeText, this.token, false) : null;
        this.summaryText = new WrappedText(summary != null ? summary : "", false);

        setOpaque(false);
        setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3));

        JPanel header = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        header.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 0));
        left.setOpaque(false);
        lblTitle.setText(title != null ? title : "");
        left.add(lblIcon);
        left.add(lblTitle);
        header.add(left, BorderLayout.WEST);
        if (this.badge != null) {
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(this.badge);
            header.add(right, BorderLayout.EAST);
        }

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        summaryText.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (summary != null && !summary.trim().isEmpty()) {
            body.add(summaryText);
        }

        stepsBox.setOpaque(false);
        stepsBox.setLayout(new BoxLayout(stepsBox, BoxLayout.Y_AXIS));
        stepsBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (bulletSteps != null && !bulletSteps.isEmpty()) {
            if (summary != null && !summary.trim().isEmpty()) {
                body.add(Box.createVerticalStrut(Theme.SPACE_2));
            }
            for (int i = 0; i < bulletSteps.size(); i++) {
                String step = bulletSteps.get(i);
                if (step == null || step.trim().isEmpty()) {
                    continue;
                }
                JPanel row = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                JLabel num = new JLabel((i + 1) + ".");
                num.setFont(FontManager.monoBold());
                num.setForeground(Theme.token(this.token).text());
                num.setVerticalAlignment(JLabel.TOP);
                WrappedText stepTxt = new WrappedText(step, true);
                stepNumLabels.add(num);
                stepTexts.add(stepTxt);
                row.add(num, BorderLayout.WEST);
                row.add(stepTxt, BorderLayout.CENTER);
                stepsBox.add(row);
                if (i < bulletSteps.size() - 1) {
                    stepsBox.add(Box.createVerticalStrut(Theme.SPACE_1));
                }
            }
            body.add(stepsBox);
        }

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        refreshStyle();
    }

    private Icon resolveIcon(Theme.Token t) {
        java.awt.Color c = Theme.token(t).text();
        if (t == Theme.Token.SUCCESS) {
            return VectorIcons.shieldCheck(c);
        }
        if (t == Theme.Token.DANGER || t == Theme.Token.CRITICAL || t == Theme.Token.WARNING) {
            return VectorIcons.alert(c);
        }
        return VectorIcons.info(c);
    }

    public void refreshStyle() {
        Theme.PaletteEntry entry = Theme.token(token);
        lblIcon.setIcon(resolveIcon(token));
        lblTitle.setFont(FontManager.uiSemiBold());
        lblTitle.setForeground(entry.text());
        summaryText.setForeground(Theme.text());
        for (JLabel num : stepNumLabels) {
            num.setForeground(entry.text());
        }
        for (WrappedText st : stepTexts) {
            st.refreshColors();
        }
        if (badge != null) {
            badge.refreshStyle();
        }
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (token != null && summaryText != null) {
            refreshStyle();
        }
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
    protected void paintComponent(Graphics g) {
        Theme.PaletteEntry liveEntry = Theme.token(token);
        lblTitle.setForeground(liveEntry.text());
        summaryText.setForeground(Theme.text());
        for (JLabel num : stepNumLabels) {
            num.setForeground(liveEntry.text());
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme.PaletteEntry entry = Theme.token(token);
            float w = getWidth() - 1f;
            float h = getHeight() - 1f;
            g2.setColor(entry.fill());
            g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
            g2.setColor(entry.border());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
