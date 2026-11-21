package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import java.awt.Font;

/**
 * Pure non-HTML wrapping multiline text component supporting Vietnamese diacritics
 * cleanly without JLabel HTML wrapping quirks (LAW 1–4 compliant).
 */
public final class WrappedText extends JTextArea {

    private boolean secondary = false;
    private AutoCloseable themeSub;

    public WrappedText(String text) {
        this(text, false, FontManager.uiRegular());
    }

    public WrappedText(String text, boolean secondary) {
        this(text, secondary, FontManager.uiRegular());
    }

    public WrappedText(String text, boolean secondary, Font font) {
        super(text != null ? text : "");
        this.secondary = secondary;
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(BorderFactory.createEmptyBorder());
        setFont(font != null ? font : FontManager.uiRegular());
        refreshColors();
    }

    public void setSecondary(boolean secondary) {
        this.secondary = secondary;
        refreshColors();
    }

    public void refreshColors() {
        setForeground(secondary ? Theme.textSecondary() : Theme.text());
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        refreshColors();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        setForeground(secondary ? Theme.textSecondary() : Theme.text());
        super.paintComponent(g);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::refreshColors);
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
