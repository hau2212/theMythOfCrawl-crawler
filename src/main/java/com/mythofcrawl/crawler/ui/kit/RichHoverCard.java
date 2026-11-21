package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Rich non-HTML explanatory hover card anchored below important controls
 * (LAW 1–4 compliant: pure Swing {@link JWindow} + {@link WrappedText}).
 */
public final class RichHoverCard {

    private RichHoverCard() {
    }

    public static AutoCloseable attach(
        JComponent target,
        String title,
        String description,
        String shortcutHint
    ) {
        if (target == null) {
            return () -> {};
        }
        final JWindow[] popupHolder = new JWindow[1];
        final Timer showTimer = new Timer(420, null);
        showTimer.setRepeats(false);

        showTimer.addActionListener(e -> {
            if (!target.isShowing()) {
                return;
            }
            dismiss(popupHolder);
            Window owner = SwingUtilities.getWindowAncestor(target);
            JWindow win = new JWindow(owner);
            win.setFocusableWindowState(false);

            JPanel card = new JPanel(new BorderLayout(0, Theme.SPACE_1));
            card.setOpaque(true);
            card.setBackground(Theme.surface());
            card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.border(), 1),
                BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3)));

            JPanel top = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
            top.setOpaque(false);
            JLabel lblTitle = new JLabel(title != null ? title : "");
            lblTitle.setFont(FontManager.uiSemiBold());
            lblTitle.setForeground(Theme.text());
            top.add(lblTitle, BorderLayout.CENTER);
            if (shortcutHint != null && !shortcutHint.trim().isEmpty()) {
                top.add(new Pill(shortcutHint, Theme.Token.NEUTRAL, false), BorderLayout.EAST);
            }

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            top.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(top);
            if (description != null && !description.trim().isEmpty()) {
                body.add(Box.createVerticalStrut(Theme.SPACE_1));
                WrappedText txt = new WrappedText(description, true);
                txt.setAlignmentX(Component.LEFT_ALIGNMENT);
                txt.setSize(new Dimension(280, Short.MAX_VALUE));
                body.add(txt);
            }

            card.add(body, BorderLayout.CENTER);
            win.setContentPane(card);
            win.pack();

            Dimension pref = win.getSize();
            if (pref.width > 320) {
                win.setSize(320, pref.height);
            }

            Point loc = target.getLocationOnScreen();
            int x = loc.x;
            int y = loc.y + target.getHeight() + Theme.SPACE_1;
            Rectangle screen = target.getGraphicsConfiguration() != null
                ? target.getGraphicsConfiguration().getBounds()
                : new Rectangle(0, 0, 1920, 1080);
            if (x + win.getWidth() > screen.x + screen.width - 8) {
                x = Math.max(screen.x + 8, screen.x + screen.width - win.getWidth() - 8);
            }
            if (y + win.getHeight() > screen.y + screen.height - 8) {
                y = Math.max(screen.y + 8, loc.y - win.getHeight() - Theme.SPACE_1);
            }
            win.setLocation(x, y);
            win.setVisible(true);
            popupHolder[0] = win;
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                showTimer.restart();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                showTimer.stop();
                PointerInfo pi = MouseInfo.getPointerInfo();
                if (pi == null || target.getVisibleRect().isEmpty()) {
                    dismiss(popupHolder);
                } else {
                    dismiss(popupHolder);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showTimer.stop();
                dismiss(popupHolder);
            }
        };

        target.addMouseListener(mouse);
        return () -> {
            showTimer.stop();
            target.removeMouseListener(mouse);
            dismiss(popupHolder);
        };
    }

    private static void dismiss(JWindow[] holder) {
        if (holder[0] != null) {
            holder[0].setVisible(false);
            holder[0].dispose();
            holder[0] = null;
        }
    }
}
