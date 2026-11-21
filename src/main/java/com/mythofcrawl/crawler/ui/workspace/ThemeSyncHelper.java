package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.ui.kit.MethodChipBar;
import com.mythofcrawl.crawler.ui.kit.ModernToggleSwitch;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.StructuredAdviceCard;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.WrappedText;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Recursively propagates {@link Theme} Light/Dark surface, input, scroll, table,
 * custom {@link JTabbedPane} UI, and kit component colors so headless offscreen snapshots
 * and live Burp theme flips render every nested widget crisply (LAW 1-4 compliant).
 */
final class ThemeSyncHelper {

    private ThemeSyncHelper() {
    }

    static void applyTree(Container root) {
        if (root == null) {
            return;
        }
        applyNode(root);
    }

    static void styleTabbedPane(JTabbedPane tp) {
        if (tp == null) {
            return;
        }
        tp.setOpaque(true);
        tp.setBackground(Theme.surface());
        tp.setForeground(Theme.text());
        tp.setFont(FontManager.uiRegular());
        if (!(tp.getUI() instanceof ThemedSubTabUI)) {
            tp.setUI(new ThemedSubTabUI());
        }
        tp.repaint();
    }

    private static void applyNode(Component comp) {
        if (comp == null) {
            return;
        }
        if (comp instanceof Ui.UiButton) {
            ((Ui.UiButton) comp).refreshAppearance();
            return;
        }
        if (comp instanceof Pill) {
            ((Pill) comp).refreshStyle();
            return;
        }
        if (comp instanceof SearchField) {
            SearchField sf = (SearchField) comp;
            sf.refreshTheme();
            JTextField tf = sf.getTextField();
            tf.setForeground(Theme.text());
            tf.setCaretColor(Theme.text());
            tf.setSelectionColor(Theme.selection());
            tf.setSelectedTextColor(Theme.selectionText());
            return;
        }
        if (comp instanceof WrappedText) {
            ((WrappedText) comp).refreshColors();
            return;
        }
        if (comp instanceof MethodChipBar) {
            ((MethodChipBar) comp).refreshAll();
            return;
        }
        if (comp instanceof ModernToggleSwitch) {
            comp.repaint();
            return;
        }
        if (comp instanceof StructuredAdviceCard) {
            ((StructuredAdviceCard) comp).refreshStyle();
            for (Component child : ((StructuredAdviceCard) comp).getComponents()) {
                applyAdviceChildren(child);
            }
            return;
        }
        if (comp instanceof ThemedTable.EmptyWrap) {
            ((ThemedTable.EmptyWrap) comp).applyTheme();
            return;
        }
        if (comp instanceof ThemedTable) {
            ((ThemedTable) comp).applyTheme();
            return;
        }
        if (comp instanceof JTextField) {
            styleTextField((JTextField) comp);
            return;
        }
        if (comp instanceof JSpinner) {
            JSpinner sp = (JSpinner) comp;
            sp.setBackground(Theme.surfaceAlt());
            sp.setForeground(Theme.text());
            sp.setBorder(BorderFactory.createLineBorder(Theme.border(), 1, true));
            JComponent ed = sp.getEditor();
            if (ed instanceof JSpinner.DefaultEditor) {
                JTextField tf = ((JSpinner.DefaultEditor) ed).getTextField();
                tf.setBackground(Theme.surfaceAlt());
                tf.setForeground(Theme.text());
                tf.setCaretColor(Theme.text());
            }
            return;
        }
        if (comp instanceof JComboBox<?>) {
            comp.setBackground(Theme.surfaceAlt());
            comp.setForeground(Theme.text());
            return;
        }
        if (comp instanceof JTextArea) {
            JTextArea ta = (JTextArea) comp;
            ta.setBackground(Theme.surfaceAlt());
            ta.setForeground(Theme.text());
            ta.setCaretColor(Theme.text());
            ta.setSelectionColor(Theme.selection());
            ta.setSelectedTextColor(Theme.selectionText());
            return;
        }
        if (comp instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) comp;
            sp.setBackground(Theme.canvas());
            if (sp.getViewport() != null) {
                sp.getViewport().setBackground(Theme.surface());
            }
            sp.setBorder(BorderFactory.createLineBorder(Theme.borderSubtle(), 1));
        } else if (comp instanceof JTabbedPane) {
            styleTabbedPane((JTabbedPane) comp);
        } else if (comp instanceof JSplitPane) {
            comp.setBackground(Theme.canvas());
        } else if (comp instanceof JLabel) {
            JLabel lbl = (JLabel) comp;
            lbl.setForeground("secondary".equals(lbl.getName()) ? Theme.textSecondary() : Theme.text());
        } else if (comp instanceof JPanel && comp.isOpaque()) {
            comp.setBackground("canvas".equals(comp.getName()) ? Theme.canvas() : Theme.surface());
        }

        if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) {
                applyNode(child);
            }
        }
    }

    private static void applyAdviceChildren(Component comp) {
        if (comp instanceof WrappedText) {
            ((WrappedText) comp).refreshColors();
            return;
        }
        if (comp instanceof Pill) {
            ((Pill) comp).refreshStyle();
            return;
        }
        if (comp instanceof JLabel) {
            ((JLabel) comp).setForeground(Theme.text());
            return;
        }
        if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) {
                applyAdviceChildren(child);
            }
        }
    }

    private static void styleTextField(JTextField tf) {
        tf.setBackground(Theme.surfaceAlt());
        tf.setForeground(Theme.text());
        tf.setCaretColor(Theme.text());
        tf.setSelectionColor(Theme.selection());
        tf.setSelectedTextColor(Theme.selectionText());
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.border(), 1, true),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    }

    /**
     * Form container that always tracks its enclosing JScrollPane viewport width
     * so right-side form panels never clip horizontally or show horizontal scrollbars.
     */
    static final class ViewportWidthFormPanel extends JPanel implements Scrollable {
        ViewportWidthFormPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class ThemedSubTabUI extends BasicTabbedPaneUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(2, 4, 0, 4);
            contentBorderInsets = new Insets(1, 0, 0, 0);
            tabInsets = new Insets(5, 11, 5, 11);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }

        @Override
        protected Insets getContentBorderInsets(int tabPlacement) {
            return new Insets(1, 0, 0, 0);
        }

        @Override
        protected void paintTabBackground(
            Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected
        ) {
            g.setColor(isSelected ? Theme.surface() : Theme.canvas());
            g.fillRect(x, y, w, h);
        }

        @Override
        protected void paintTabBorder(
            Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected
        ) {
            if (isSelected) {
                g.setColor(Theme.token(Theme.Token.ACCENT).solidFill());
                g.fillRect(x + 2, y + h - 2, Math.max(0, w - 4), 2);
            } else {
                g.setColor(Theme.borderSubtle());
                g.fillRect(x, y + h - 1, w, 1);
            }
        }

        @Override
        protected void paintText(
            Graphics g,
            int tabPlacement,
            Font font,
            FontMetrics metrics,
            int tabIndex,
            String title,
            Rectangle textRect,
            boolean isSelected
        ) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font f = isSelected ? FontManager.uiSemiBold() : FontManager.uiRegular();
                g2.setFont(f);
                g2.setColor(isSelected ? Theme.text() : Theme.textSecondary());
                FontMetrics fm = g2.getFontMetrics(f);
                g2.drawString(title, textRect.x, textRect.y + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintFocusIndicator(
            Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
            Rectangle iconRect, Rectangle textRect, boolean isSelected
        ) {
        }

        @Override
        protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
            int width = tabPane.getWidth();
            Insets insets = tabPane.getInsets();
            int tabAreaH = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight);
            int y = insets.top + tabAreaH;
            g.setColor(Theme.border());
            g.fillRect(insets.left, y, Math.max(0, width - insets.left - insets.right), 1);
        }
    }
}
