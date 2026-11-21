package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.TableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Theme-synchronized {@link JTable} enforcing {@code ROW_TABLE = 26} for crisp
 * Vietnamese diacritics, hairline borders, and optional empty state container (LAW 1–4 compliant).
 */
public class ThemedTable extends JTable {

    private AutoCloseable themeSub;

    public ThemedTable() {
        super();
        initStyle();
    }

    public ThemedTable(TableModel model) {
        super(model);
        initStyle();
    }

    private void initStyle() {
        setAutoCreateRowSorter(true);
        setFillsViewportHeight(true);
        setShowHorizontalLines(true);
        setShowVerticalLines(false);
        setIntercellSpacing(new Dimension(0, 1));
        applyTheme();
    }

    public void applyTheme() {
        setFont(FontManager.uiRegular());
        setRowHeight(Theme.ROW_TABLE);
        setBackground(Theme.surface());
        setForeground(Theme.text());
        setSelectionBackground(Theme.selection());
        setSelectionForeground(Theme.selectionText());
        setGridColor(Theme.borderSubtle());
        if (getTableHeader() != null) {
            getTableHeader().setBackground(Theme.surfaceAlt());
            getTableHeader().setForeground(Theme.text());
            getTableHeader().setFont(FontManager.uiSemiBold());
            getTableHeader().setPreferredSize(new Dimension(0, Theme.ROW_TABLE + 2));
            getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()));
        }
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::applyTheme);
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
        applyTheme();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        setBackground(Theme.surface());
        setForeground(Theme.text());
        setSelectionBackground(Theme.selection());
        setSelectionForeground(Theme.selectionText());
        setGridColor(Theme.borderSubtle());
        if (getTableHeader() != null) {
            getTableHeader().setBackground(Theme.surfaceAlt());
            getTableHeader().setForeground(Theme.text());
        }
        super.paintComponent(g);
    }

    /**
     * Wraps this table inside a themed scroll pane + CardLayout empty state panel.
     */
    public static EmptyWrap wrapWithEmptyState(ThemedTable table, String emptyTitle, String emptySubtitle) {
        return new EmptyWrap(table, emptyTitle, emptySubtitle);
    }

    public static final class EmptyWrap extends JPanel {
        private final ThemedTable table;
        private final JScrollPane scrollPane;
        private final JPanel emptyPanel = new JPanel(new java.awt.GridBagLayout());
        private final JLabel lblIcon = new JLabel("", SwingConstants.CENTER);
        private final JLabel lblTitle = new JLabel("", SwingConstants.CENTER);
        private final JLabel lblSub = new JLabel("", SwingConstants.CENTER);
        private AutoCloseable themeSub;

        EmptyWrap(ThemedTable table, String title, String subtitle) {
            super(new CardLayout());
            this.table = table;
            this.scrollPane = new JScrollPane(table);
            lblTitle.setText(title != null && !title.trim().isEmpty() ? title : "Không có dữ liệu");
            lblSub.setText(subtitle != null ? subtitle : "");
            lblSub.setVisible(subtitle != null && !subtitle.trim().isEmpty());

            JPanel box = new JPanel();
            box.setOpaque(false);
            box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
            lblIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
            lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            lblSub.setAlignmentX(Component.CENTER_ALIGNMENT);
            box.add(lblIcon);
            box.add(Box.createVerticalStrut(Theme.SPACE_2));
            box.add(lblTitle);
            if (lblSub.isVisible()) {
                box.add(Box.createVerticalStrut(Theme.SPACE_1));
                box.add(lblSub);
            }
            emptyPanel.add(box);

            add(scrollPane, "TABLE");
            add(emptyPanel, "EMPTY");
            applyTheme();
            refreshEmptyState();
        }

        public JScrollPane getScrollPane() {
            return scrollPane;
        }

        public void refreshEmptyState() {
            boolean empty = table.getModel() == null || table.getRowCount() == 0;
            ((CardLayout) getLayout()).show(this, empty ? "EMPTY" : "TABLE");
        }

        public void applyTheme() {
            scrollPane.getViewport().setBackground(Theme.surface());
            scrollPane.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
            emptyPanel.setBackground(Theme.surface());
            lblIcon.setIcon(VectorIcons.emptyTray(30, Theme.muted()));
            lblTitle.setFont(FontManager.uiSemiBold());
            lblTitle.setForeground(Theme.text());
            lblSub.setFont(FontManager.uiSmall());
            lblSub.setForeground(Theme.muted());
            table.applyTheme();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (scrollPane != null && emptyPanel != null && table != null) {
                applyTheme();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (scrollPane != null && emptyPanel != null) {
                scrollPane.getViewport().setBackground(Theme.surface());
                emptyPanel.setBackground(Theme.surface());
                lblTitle.setForeground(Theme.text());
                lblSub.setForeground(Theme.muted());
            }
            super.paintComponent(g);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (themeSub == null) {
                themeSub = Theme.onThemeChange(this::applyTheme);
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
}
