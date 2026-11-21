package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Clean self-painted SearchField with search icon, placeholder, clear button,
 * and optional 160ms debounced text change stream (LAW 1–4 compliant).
 */
public final class SearchField extends JPanel {

    private final JLabel lblSearch = new JLabel();
    private final JTextField input = new JTextField();
    private final JButton btnClear = new JButton();
    private final Timer debounceTimer;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private String placeholder;
    private boolean focused = false;
    private boolean suppressing = false;
    private AutoCloseable themeSub;

    public SearchField(String placeholder) {
        this(placeholder, 160);
    }

    public SearchField(String placeholder, int debounceMs) {
        this.placeholder = placeholder != null ? placeholder : "Tìm kiếm...";
        this.debounceTimer = new Timer(Math.max(10, debounceMs), e -> fireQuery());
        this.debounceTimer.setRepeats(false);

        setOpaque(false);
        setLayout(new BorderLayout(Theme.SPACE_1, 0));
        setBorder(BorderFactory.createEmptyBorder(3, Theme.SPACE_2, 3, Theme.SPACE_1));
        setPreferredSize(new Dimension(220, 28));
        setMinimumSize(new Dimension(110, 26));

        input.setOpaque(false);
        input.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        input.setFont(FontManager.uiRegular());

        btnClear.setOpaque(false);
        btnClear.setContentAreaFilled(false);
        btnClear.setBorderPainted(false);
        btnClear.setFocusPainted(false);
        btnClear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnClear.setPreferredSize(new Dimension(18, 18));
        btnClear.setVisible(false);
        btnClear.addActionListener(e -> clear());

        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onEdited(); }
        });
        input.addActionListener(e -> {
            debounceTimer.stop();
            fireQuery();
        });
        input.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                focused = true;
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                focused = false;
                repaint();
            }
        });

        add(lblSearch, BorderLayout.WEST);
        add(input, BorderLayout.CENTER);
        add(btnClear, BorderLayout.EAST);
        refreshTheme();
    }

    private void onEdited() {
        btnClear.setVisible(!input.getText().isEmpty());
        repaint();
        if (!suppressing) {
            debounceTimer.restart();
        }
    }

    private void fireQuery() {
        String q = getText();
        for (Consumer<String> l : listeners) {
            l.accept(q);
        }
    }

    public JTextField getTextField() {
        return input;
    }

    public String getText() {
        return input.getText() != null ? input.getText() : "";
    }

    public void setText(String text) {
        suppressing = true;
        debounceTimer.stop();
        try {
            input.setText(text != null ? text : "");
            btnClear.setVisible(!input.getText().isEmpty());
        } finally {
            suppressing = false;
        }
        fireQuery();
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
        repaint();
    }

    public void clear() {
        setText("");
        focusInput();
    }

    public void focusInput() {
        input.requestFocusInWindow();
        input.selectAll();
    }

    public void onSearchChange(Consumer<String> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void refreshTheme() {
        lblSearch.setIcon(VectorIcons.search(13, Theme.muted()));
        btnClear.setIcon(VectorIcons.cross(11, Theme.muted()));
        input.setFont(FontManager.uiRegular());
        input.setForeground(Theme.text());
        input.setCaretColor(Theme.text());
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::refreshTheme);
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
        if (input != null && lblSearch != null && btnClear != null) {
            refreshTheme();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        input.setForeground(Theme.text());
        input.setCaretColor(Theme.text());
        lblSearch.setIcon(VectorIcons.search(13, Theme.muted()));
        btnClear.setIcon(VectorIcons.cross(11, Theme.muted()));
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float w = getWidth() - 1f;
            float h = getHeight() - 1f;
            g2.setColor(Theme.surface());
            g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
            g2.setColor(focused ? Theme.focusRing() : Theme.border());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));

            if (input.getText().isEmpty() && placeholder != null && !placeholder.isEmpty()) {
                g2.setFont(FontManager.uiRegular());
                g2.setColor(Theme.muted());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int px = input.getX() + 2;
                int py = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(placeholder, px, py);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
