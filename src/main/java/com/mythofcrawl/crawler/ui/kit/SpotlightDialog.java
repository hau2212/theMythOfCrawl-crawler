package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Ctrl+K Spotlight Global Search Dialog across branches, APIs, rules, and commands
 * (LAW 1–4 compliant).
 */
public final class SpotlightDialog extends JDialog {

    public static final class SearchEntry {
        public final String category;
        public final String title;
        public final String subtitle;
        public final Theme.Token token;
        public final Runnable onSelect;

        public SearchEntry(
            String category,
            String title,
            String subtitle,
            Theme.Token token,
            Runnable onSelect
        ) {
            this.category = category != null ? category : "";
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.token = token != null ? token : Theme.Token.NEUTRAL;
            this.onSelect = onSelect;
        }
    }

    private final Supplier<List<SearchEntry>> entriesSupplier;
    private final SearchField searchField = new SearchField("Tìm nhanh nhánh, API, rule hoặc lệnh... (Enter để mở)", 40);
    private final DefaultListModel<SearchEntry> listModel = new DefaultListModel<>();
    private final JList<SearchEntry> resultList = new JList<>(listModel);
    private final JScrollPane spResults = new JScrollPane(resultList);
    private final JLabel lblCount = new JLabel("0 kết quả");
    private final JPanel container = new JPanel(new BorderLayout(0, Theme.SPACE_2));

    public SpotlightDialog(Window owner, Supplier<List<SearchEntry>> entriesSupplier) {
        super(owner instanceof Frame ? (Frame) owner : null, "Tìm nhanh (Ctrl+K)", false);
        this.entriesSupplier = entriesSupplier;

        container.setOpaque(true);
        container.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3));

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new EntryRenderer());
        resultList.setFixedCellHeight(38);

        searchField.onSearchChange(this::filterEntries);
        searchField.getTextField().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_DOWN) {
                    moveSelection(1);
                    e.consume();
                } else if (code == KeyEvent.VK_UP) {
                    moveSelection(-1);
                    e.consume();
                } else if (code == KeyEvent.VK_ENTER) {
                    executeSelected();
                    e.consume();
                }
            }
        });

        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    executeSelected();
                }
            }
        });

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        lblCount.setFont(FontManager.uiSmall());
        footer.add(lblCount, BorderLayout.WEST);

        JLabel hint = new JLabel("Enter: Chọn  |  ESC: Đóng");
        hint.setFont(FontManager.uiSmall());
        hint.setForeground(Theme.muted());
        footer.add(hint, BorderLayout.EAST);

        container.add(searchField, BorderLayout.NORTH);
        container.add(spResults, BorderLayout.CENTER);
        container.add(footer, BorderLayout.SOUTH);
        setContentPane(container);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSpotlight");
        getRootPane().getActionMap().put("closeSpotlight", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });

        setSize(new Dimension(560, 400));
        setMinimumSize(new Dimension(380, 280));
        setLocationRelativeTo(owner);
        refreshTheme();
    }

    public void openAndFocus() {
        refreshTheme();
        searchField.setText("");
        filterEntries("");
        setVisible(true);
        searchField.focusInput();
    }

    private void filterEntries(String query) {
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<SearchEntry> all = entriesSupplier != null ? entriesSupplier.get() : new ArrayList<>();
        listModel.clear();
        if (all != null) {
            for (SearchEntry en : all) {
                if (en == null) {
                    continue;
                }
                if (q.isEmpty()
                    || en.title.toLowerCase(Locale.ROOT).contains(q)
                    || en.subtitle.toLowerCase(Locale.ROOT).contains(q)
                    || en.category.toLowerCase(Locale.ROOT).contains(q)) {
                    listModel.addElement(en);
                    if (listModel.size() >= 150) {
                        break;
                    }
                }
            }
        }
        if (listModel.size() > 0) {
            resultList.setSelectedIndex(0);
        }
        lblCount.setText(listModel.size() + " kết quả");
    }

    private void moveSelection(int delta) {
        int cnt = listModel.size();
        if (cnt == 0) {
            return;
        }
        int cur = resultList.getSelectedIndex();
        int next = Math.max(0, Math.min(cnt - 1, (cur < 0 ? 0 : cur) + delta));
        resultList.setSelectedIndex(next);
        resultList.ensureIndexIsVisible(next);
    }

    private void executeSelected() {
        SearchEntry sel = resultList.getSelectedValue();
        if (sel != null) {
            setVisible(false);
            if (sel.onSelect != null) {
                sel.onSelect.run();
            }
        }
    }

    public void refreshTheme() {
        container.setBackground(Theme.canvas());
        resultList.setBackground(Theme.surface());
        resultList.setForeground(Theme.text());
        spResults.getViewport().setBackground(Theme.surface());
        spResults.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        lblCount.setForeground(Theme.muted());
        searchField.refreshTheme();
    }

    private static final class EntryRenderer implements ListCellRenderer<SearchEntry> {
        private final JPanel row = new JPanel(new BorderLayout(Theme.SPACE_2, 2));
        private final Pill badge = new Pill("", Theme.Token.NEUTRAL, false);
        private final JLabel lblTitle = new JLabel();
        private final JLabel lblSub = new JLabel();

        EntryRenderer() {
            row.setOpaque(true);
            row.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2));
            JPanel textCol = new JPanel(new BorderLayout(0, 1));
            textCol.setOpaque(false);
            lblTitle.setFont(FontManager.uiMedium());
            lblSub.setFont(FontManager.uiSmall());
            textCol.add(lblTitle, BorderLayout.NORTH);
            textCol.add(lblSub, BorderLayout.SOUTH);

            JPanel west = new JPanel(new BorderLayout());
            west.setOpaque(false);
            west.add(badge, BorderLayout.CENTER);
            row.add(west, BorderLayout.WEST);
            row.add(textCol, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends SearchEntry> list,
            SearchEntry value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            row.setBackground(isSelected ? Theme.selection() : Theme.surface());
            if (value != null) {
                badge.setPill(value.category.isEmpty() ? "ITEM" : value.category, value.token, false);
                lblTitle.setText(value.title);
                lblTitle.setForeground(isSelected ? Theme.selectionText() : Theme.text());
                lblTitle.setIcon(VectorIcons.chevronRight(10, isSelected ? Theme.selectionText() : Theme.muted()));
                lblSub.setText(value.subtitle);
                lblSub.setForeground(isSelected ? Theme.selectionText() : Theme.muted());
            }
            return row;
        }
    }
}
