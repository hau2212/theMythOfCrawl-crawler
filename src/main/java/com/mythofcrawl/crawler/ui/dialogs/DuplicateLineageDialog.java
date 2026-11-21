package com.mythofcrawl.crawler.ui.dialogs;

import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.ui.kit.FloatingModelessDialog;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Floating modeless dialog inspecting cross-branch duplicate API lineage,
 * original discovery branch ({@code originBranch}), move history, and REFRESH counts.
 */
public final class DuplicateLineageDialog extends FloatingModelessDialog {

    private final DuplicateLineageTracker tracker;
    private final SearchField searchField = new SearchField("Tìm Endpoint hoặc nhánh...");
    private final LineageTableModel model = new LineageTableModel();
    private final ThemedTable table = new ThemedTable(model);
    private final ThemedTable.EmptyWrap tableWrap;
    private final JTextArea txtDetails = new JTextArea();
    private final JScrollPane spDetails = new JScrollPane(txtDetails);
    private final Ui.UiButton btnRefresh;

    public DuplicateLineageDialog(Window owner, DuplicateLineageTracker tracker, String initialEndpointFilter) {
        super(owner, "duplicate_lineage_dialog", "Dòng dõi API trùng lặp (Lineage)", 780, 520);
        this.tracker = tracker;
        this.btnRefresh = Ui.secondaryBtn("Làm mới", VectorIcons.refresh(Theme.text()));
        this.tableWrap = ThemedTable.wrapWithEmptyState(
            table,
            "Chưa có dữ liệu Lineage",
            "Các API xuất hiện trên nhiều nhánh sẽ hiển thị đầy đủ lịch sử tại đây."
        );

        JPanel content = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel top = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        top.setOpaque(false);
        top.add(searchField, BorderLayout.CENTER);
        top.add(btnRefresh, BorderLayout.EAST);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new LineageCellRenderer());
        table.setDefaultRenderer(Integer.class, new LineageCellRenderer());

        txtDetails.setEditable(false);
        txtDetails.setLineWrap(true);
        txtDetails.setWrapStyleWord(true);
        txtDetails.setFont(FontManager.monoRegular());
        txtDetails.setBackground(Theme.surface());
        txtDetails.setForeground(Theme.text());
        spDetails.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spDetails.setPreferredSize(new Dimension(200, 180));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWrap, spDetails);
        split.setResizeWeight(0.58);

        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        setBodyComponent(content);

        searchField.onSearchChange(q -> refreshData());
        btnRefresh.addActionListener(e -> refreshData());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = table.getSelectedRow();
                if (vr >= 0) {
                    showLineageDetail(model.getAt(table.convertRowIndexToModel(vr)));
                }
            }
        });

        if (initialEndpointFilter != null && !initialEndpointFilter.trim().isEmpty()) {
            searchField.setText(initialEndpointFilter.trim());
        } else {
            refreshData();
        }
    }

    public void refreshData() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<DuplicateLineageTracker.EndpointLineage> out = new ArrayList<>();
        if (tracker != null) {
            for (Map.Entry<String, DuplicateLineageTracker.EndpointLineage> en : tracker.snapshotAll().entrySet()) {
                DuplicateLineageTracker.EndpointLineage lin = en.getValue();
                if (!q.isEmpty()
                    && !lin.endpointKey.toLowerCase(Locale.ROOT).contains(q)
                    && !lin.originBranch.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                out.add(lin);
            }
        }
        model.setRows(out);
        tableWrap.refreshEmptyState();
        if (!out.isEmpty() && table.getSelectedRow() < 0) {
            table.setRowSelectionInterval(0, 0);
            showLineageDetail(out.get(0));
        } else if (out.isEmpty()) {
            txtDetails.setText("");
        }
    }

    private void showLineageDetail(DuplicateLineageTracker.EndpointLineage lin) {
        if (lin == null) {
            txtDetails.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(lin.endpointKey).append('\n');
        sb.append("Nhánh phát hiện đầu tiên (Origin): ").append(lin.originBranch).append('\n');
        sb.append("Tổng hits: ").append(lin.hitCount).append(" | REFRESH: ").append(lin.refreshCount).append('\n');
        sb.append("\nCác nhánh đã xuất hiện (").append(lin.seenBranches.size()).append("):\n");
        for (String b : lin.seenBranches) {
            sb.append("  - ").append(b).append('\n');
        }
        if (!lin.moveHistory.isEmpty()) {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            sb.append("\nLịch sử Move History:\n");
            for (DuplicateLineageTracker.MoveEvent mv : lin.moveHistory) {
                sb.append("  [").append(fmt.format(new Date(mv.movedAtMs))).append("] ")
                    .append(mv.fromBranch).append(" -> ").append(mv.toBranch)
                    .append(" (").append(mv.reason).append(")\n");
            }
        }
        txtDetails.setText(sb.toString());
        txtDetails.setCaretPosition(0);
    }

    private static final class LineageTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Endpoint", "Nhánh gốc (Origin)", "Số nhánh", "Hits", "Refresh"};
        private List<DuplicateLineageTracker.EndpointLineage> rows = new ArrayList<>();

        void setRows(List<DuplicateLineageTracker.EndpointLineage> rows) {
            this.rows = rows != null ? rows : new ArrayList<>();
            fireTableDataChanged();
        }

        DuplicateLineageTracker.EndpointLineage getAt(int mr) {
            return mr >= 0 && mr < rows.size() ? rows.get(mr) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return (col == 2 || col == 3 || col == 4) ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            DuplicateLineageTracker.EndpointLineage l = rows.get(row);
            switch (col) {
                case 0: return l.endpointKey;
                case 1: return l.originBranch;
                case 2: return Integer.valueOf(l.seenBranches.size());
                case 3: return Integer.valueOf(l.hitCount);
                case 4: return Integer.valueOf(l.refreshCount);
                default: return "";
            }
        }
    }

    private static final class LineageCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            javax.swing.JTable tbl, Object val, boolean sel, boolean focus, int row, int col
        ) {
            Component c = super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            c.setFont(col == 0 ? FontManager.monoRegular() : FontManager.uiRegular());
            if (!sel) {
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                c.setForeground(Theme.text());
            } else {
                c.setBackground(Theme.selection());
                c.setForeground(Theme.selectionText());
            }
            return c;
        }
    }
}
