package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking bottom-right slide-in toast notification layer anchored onto a host
 * {@link JRootPane} (replaces modal {@code JOptionPane} clutter). LAW 1–4 compliant.
 */
public final class ToastPane extends JPanel {

    private static final int MAX_TOASTS = 4;
    private static final int TOAST_WIDTH = 340;

    private final JLayeredPane layeredHost;
    private final JPanel stack = new JPanel();
    private final List<ToastItem> active = new ArrayList<>();
    private final ComponentAdapter resizeWatcher;

    private ToastPane(JLayeredPane layeredHost) {
        this.layeredHost = layeredHost;
        setOpaque(false);
        setLayout(new BorderLayout());
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        add(stack, BorderLayout.SOUTH);

        this.resizeWatcher = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                reposition();
            }
        };
        if (layeredHost != null) {
            layeredHost.addComponentListener(resizeWatcher);
        }
    }

    public static ToastPane attach(Component anyChildInWindow) {
        if (anyChildInWindow == null) {
            return new ToastPane(null);
        }
        JRootPane root = SwingUtilities.getRootPane(anyChildInWindow);
        if (root == null) {
            return new ToastPane(null);
        }
        JLayeredPane lp = root.getLayeredPane();
        for (Component c : lp.getComponentsInLayer(JLayeredPane.POPUP_LAYER)) {
            if (c instanceof ToastPane) {
                return (ToastPane) c;
            }
        }
        ToastPane pane = new ToastPane(lp);
        lp.add(pane, JLayeredPane.POPUP_LAYER);
        pane.reposition();
        return pane;
    }

    public void show(Theme.Token token, String title, String message) {
        show(token, title, message, 3600);
    }

    public void show(Theme.Token token, String title, String message, int durationMs) {
        Runnable task = () -> {
            Theme.Token tok = token != null ? token : Theme.Token.INFO;
            while (active.size() >= MAX_TOASTS) {
                ToastItem oldest = active.remove(0);
                oldest.disposeToast();
                stack.remove(oldest);
            }
            ToastItem item = new ToastItem(tok, title, message, durationMs);
            item.setAlignmentX(Component.RIGHT_ALIGNMENT);
            active.add(item);
            stack.add(Box.createVerticalStrut(Theme.SPACE_2));
            stack.add(item);
            reposition();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void showSuccess(String title, String message) {
        show(Theme.Token.SUCCESS, title, message);
    }

    public void showWarning(String title, String message) {
        show(Theme.Token.WARNING, title, message, 4800);
    }

    public void showError(String title, String message) {
        show(Theme.Token.DANGER, title, message, 5500);
    }

    public void showInfo(String title, String message) {
        show(Theme.Token.INFO, title, message);
    }

    public void dismissAll() {
        for (ToastItem item : new ArrayList<>(active)) {
            item.disposeToast();
        }
        active.clear();
        stack.removeAll();
        reposition();
    }

    public void detach() {
        dismissAll();
        if (layeredHost != null) {
            layeredHost.removeComponentListener(resizeWatcher);
            layeredHost.remove(this);
        }
    }

    private void removeItem(ToastItem item) {
        active.remove(item);
        stack.remove(item);
        reposition();
    }

    private void reposition() {
        if (layeredHost == null) {
            return;
        }
        stack.revalidate();
        Dimension pref = stack.getPreferredSize();
        int w = Math.min(TOAST_WIDTH, Math.max(220, layeredHost.getWidth() - Theme.SPACE_4 * 2));
        int h = Math.max(0, pref.height + Theme.SPACE_2);
        int x = Math.max(Theme.SPACE_2, layeredHost.getWidth() - w - Theme.SPACE_4);
        int y = Math.max(Theme.SPACE_2, layeredHost.getHeight() - h - Theme.SPACE_4);
        setBounds(x, y, w, h);
        setVisible(!active.isEmpty());
        revalidate();
        repaint();
    }

    private final class ToastItem extends JPanel {
        private final Theme.Token token;
        private final Timer autoTimer;

        ToastItem(Theme.Token token, String title, String message, int durationMs) {
            this.token = token;
            setOpaque(false);
            setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
            setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_2));

            JLabel icon = new JLabel();
            java.awt.Color icCol = Theme.token(token).text();
            if (token == Theme.Token.SUCCESS) {
                icon.setIcon(VectorIcons.check(icCol));
            } else if (token == Theme.Token.WARNING || token == Theme.Token.DANGER || token == Theme.Token.CRITICAL) {
                icon.setIcon(VectorIcons.alert(icCol));
            } else {
                icon.setIcon(VectorIcons.info(icCol));
            }
            icon.setVerticalAlignment(JLabel.TOP);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            if (title != null && !title.trim().isEmpty()) {
                JLabel lblT = new JLabel(title);
                lblT.setFont(FontManager.uiSemiBold());
                lblT.setForeground(Theme.text());
                lblT.setAlignmentX(Component.LEFT_ALIGNMENT);
                center.add(lblT);
            }
            if (message != null && !message.trim().isEmpty()) {
                WrappedText txt = new WrappedText(message, true);
                txt.setAlignmentX(Component.LEFT_ALIGNMENT);
                center.add(txt);
            }

            JButton btnClose = new JButton(VectorIcons.cross(11, Theme.muted()));
            btnClose.setOpaque(false);
            btnClose.setContentAreaFilled(false);
            btnClose.setBorderPainted(false);
            btnClose.setFocusPainted(false);
            btnClose.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btnClose.setPreferredSize(new Dimension(20, 20));
            btnClose.addActionListener(e -> {
                disposeToast();
                removeItem(this);
            });

            add(icon, BorderLayout.WEST);
            add(center, BorderLayout.CENTER);
            add(btnClose, BorderLayout.EAST);

            setPreferredSize(new Dimension(TOAST_WIDTH, Math.max(46, getPreferredSize().height)));
            setMaximumSize(new Dimension(TOAST_WIDTH, 160));

            this.autoTimer = new Timer(Math.max(1200, durationMs), e -> {
                disposeToast();
                removeItem(this);
            });
            this.autoTimer.setRepeats(false);
            this.autoTimer.start();
        }

        void disposeToast() {
            if (autoTimer != null) {
                autoTimer.stop();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Theme.PaletteEntry entry = Theme.token(token);
                float w = getWidth() - 1f;
                float h = getHeight() - 1f;
                g2.setColor(Theme.surface());
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
                g2.setColor(entry.solidFill());
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, 4f, h, Theme.RADIUS_SM, Theme.RADIUS_SM));
                g2.setColor(entry.border());
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w, h, Theme.RADIUS_MD, Theme.RADIUS_MD));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
