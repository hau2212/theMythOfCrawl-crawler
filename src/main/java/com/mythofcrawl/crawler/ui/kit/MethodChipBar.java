package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Multi-select HTTP Method filter chip bar (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS)
 * with semantic method tokens and two quick preset buttons ("Chỉ Action" & "Tất cả").
 */
public final class MethodChipBar extends JPanel {

    public static final String[] METHODS = {
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    };

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Map<String, MethodChip> chips = new LinkedHashMap<>();
    private final Ui.UiButton btnActionOnly;
    private final Ui.UiButton btnAll;
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
    private boolean suppressing = false;
    private AutoCloseable themeSub;

    public MethodChipBar() {
        super(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
        setOpaque(false);

        for (String m : METHODS) {
            MethodChip chip = new MethodChip(m);
            chip.addActionListener(e -> {
                if (!suppressing) {
                    chip.refreshStyle();
                    fireChange();
                }
            });
            chips.put(m, chip);
            add(chip);
        }

        btnActionOnly = Ui.secondaryBtn("Chỉ Action");
        btnActionOnly.setToolTipText("Chọn POST, PUT, PATCH, DELETE");
        btnActionOnly.addActionListener(e -> selectOnly(MUTATING_METHODS));

        btnAll = Ui.secondaryBtn("Tất cả");
        btnAll.setToolTipText("Chọn tất cả 7 HTTP Method");
        btnAll.addActionListener(e -> selectAll());

        add(btnActionOnly);
        add(btnAll);
        refreshAll();
    }

    public void selectAll() {
        suppressing = true;
        try {
            for (MethodChip c : chips.values()) {
                c.setSelected(true);
            }
        } finally {
            suppressing = false;
        }
        refreshAll();
        fireChange();
    }

    public void selectOnly(Set<String> targetMethods) {
        suppressing = true;
        try {
            for (Map.Entry<String, MethodChip> en : chips.entrySet()) {
                en.getValue().setSelected(targetMethods != null && targetMethods.contains(en.getKey()));
            }
        } finally {
            suppressing = false;
        }
        refreshAll();
        fireChange();
    }

    public Set<String> selectedMethods() {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, MethodChip> en : chips.entrySet()) {
            if (en.getValue().isSelected()) {
                out.add(en.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public boolean matches(String method) {
        String norm = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
        Set<String> sel = selectedMethods();
        if (sel.size() == chips.size()) {
            return true;
        }
        return sel.contains(norm);
    }

    public void onChange(Consumer<Set<String>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void fireChange() {
        Set<String> snap = selectedMethods();
        for (Consumer<Set<String>> l : listeners) {
            l.accept(snap);
        }
    }

    public void refreshAll() {
        for (MethodChip c : chips.values()) {
            c.refreshStyle();
        }
        boolean isActionOnly = selectedMethods().equals(MUTATING_METHODS);
        boolean isAll = selectedMethods().size() == chips.size();
        btnActionOnly.setSelectedVisual(isActionOnly);
        btnAll.setSelectedVisual(isAll);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::refreshAll);
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
        if (chips != null && btnActionOnly != null && btnAll != null) {
            refreshAll();
        }
    }

    private static final class MethodChip extends JToggleButton {
        private final Theme.Token token;

        MethodChip(String method) {
            super(method, true);
            this.token = Theme.forMethodToken(method);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            refreshStyle();
        }

        void refreshStyle() {
            setFont(isSelected() ? FontManager.monoBold() : FontManager.monoSmall());
            setForeground(isSelected() ? Theme.token(token).text() : Theme.muted());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            setForeground(isSelected() ? Theme.token(token).text() : Theme.muted());
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Theme.PaletteEntry entry = Theme.token(token);
                float w = getWidth() - 1f;
                float h = getHeight() - 1f;
                if (isSelected()) {
                    g2.setColor(getModel().isRollover() ? entry.fillStrong() : entry.fill());
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                    g2.setColor(entry.border());
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                } else {
                    g2.setColor(getModel().isRollover() ? Theme.surfaceHover() : Theme.surface());
                    g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                    g2.setColor(Theme.borderSubtle());
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
