package com.mythofcrawl.crawler.ui.dialogs;

import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.ui.kit.FloatingModelessDialog;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Floating modeless dialog for reviewing, filtering, editing, forwarding, drafting,
 * or dropping HTTP requests held in {@link ProxyInterceptQueue}.
 */
public final class InterceptQueueDialog extends FloatingModelessDialog {

    private final ProxyInterceptQueue queue;
    private final PolicyLedger ledger;
    private final ToastPane toast;

    public static JPanel createQueuePanel(
        ProxyInterceptQueue queue,
        PolicyLedger ledger,
        ToastPane toast
    ) {
        return new QueueViewPanel(queue, ledger, toast);
    }

    private final QueueViewPanel viewPanel;

    public InterceptQueueDialog(
        Window owner,
        ProxyInterceptQueue queue,
        PolicyLedger ledger,
        ToastPane toast
    ) {
        super(owner, "intercept_queue_dialog", "Hàng đợi Duyệt Proxy (Intercept Queue)", 880, 580);
        this.queue = queue;
        this.ledger = ledger;
        this.toast = toast;
        this.viewPanel = new QueueViewPanel(queue, ledger, toast);
        setBodyComponent(this.viewPanel);
    }

    public JPanel getQueueViewPanel() {
        return viewPanel;
    }

    public void refreshQueue() {
        viewPanel.refreshQueue();
    }

    @Override
    protected void onDispose() {
        viewPanel.dispose();
    }

    public static final class QueueViewPanel extends JPanel {
        private final ProxyInterceptQueue queue;
        private final PolicyLedger ledger;
        private final ToastPane toast;

        private final JComboBox<String> cbBranch = new JComboBox<>(new String[]{"Tất cả nhánh"});
        private final JComboBox<String> cbHost = new JComboBox<>(new String[]{"Tất cả Host"});
        private final SearchField searchField = new SearchField("Lọc Method, Path, Lý do giữ...");
        private final Pill countPill = new Pill("0 chờ", Theme.Token.PENDING, false);

        private final QueueTableModel model = new QueueTableModel();
        private final ThemedTable table = new ThemedTable(model);
        private final ThemedTable.EmptyWrap tableWrap;

        private final JTextArea txtRawEditor = new JTextArea();
        private final JScrollPane spEditor = new JScrollPane(txtRawEditor);

        private final Ui.UiButton btnForward;
        private final Ui.UiButton btnDraft;
        private final Ui.UiButton btnDrop;
        private final Ui.UiButton btnDropAll;
        private final Ui.UiButton btnReleaseAll;

        private ProxyInterceptQueue.PendingInterceptItem selectedItem;
        private InstantEventBus.Subscription queueSub;
        private AutoCloseable themeSub;
        private boolean updatingCombos = false;

        public QueueViewPanel(
            ProxyInterceptQueue queue,
            PolicyLedger ledger,
            ToastPane toast
        ) {
            super(new BorderLayout(0, Theme.SPACE_2));
            this.queue = queue;
            this.ledger = ledger;
            this.toast = toast;

            this.btnForward = Ui.solidPrimary("Gửi Server", VectorIcons.send(Theme.inkWhite()));
            this.btnDraft = Ui.secondaryBtn("Lưu nháp", VectorIcons.edit(Theme.text()));
            this.btnDrop = Ui.dangerBtn("Hủy Drop", VectorIcons.cross(Theme.token(Theme.Token.DANGER).text()));
            this.btnDropAll = Ui.dangerBtn("Drop All", VectorIcons.trash(Theme.token(Theme.Token.DANGER).text()));
            this.btnReleaseAll = Ui.tonalBtn(Theme.Token.SUCCESS, "Nhả hết về NONE", VectorIcons.unlock(Theme.token(Theme.Token.SUCCESS).text()));

            this.tableWrap = ThemedTable.wrapWithEmptyState(
                table,
                "Không có yêu cầu chờ duyệt",
                "Các hành động khớp Guardrail INTERCEPT_WAIT sẽ xuất hiện ngay tại đây."
            );

            setOpaque(true);
            setBackground(Theme.canvas());
            setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

            JPanel filterBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
            filterBar.setOpaque(false);
            JPanel leftFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 0));
            leftFilters.setOpaque(false);
            leftFilters.add(new JLabel("Nhánh:"));
            leftFilters.add(cbBranch);
            leftFilters.add(new JLabel("Host:"));
            leftFilters.add(cbHost);
            leftFilters.add(countPill);
            filterBar.add(leftFilters, BorderLayout.WEST);
            filterBar.add(searchField, BorderLayout.EAST);

            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setDefaultRenderer(Object.class, new QueueCellRenderer());
            if (table.getColumnModel().getColumnCount() >= 6) {
                table.getColumnModel().getColumn(0).setPreferredWidth(92);
                table.getColumnModel().getColumn(1).setPreferredWidth(180);
                table.getColumnModel().getColumn(2).setPreferredWidth(140);
                table.getColumnModel().getColumn(3).setPreferredWidth(90);
                table.getColumnModel().getColumn(4).setPreferredWidth(220);
                table.getColumnModel().getColumn(5).setPreferredWidth(150);
            }

            txtRawEditor.setFont(FontManager.monoRegular());
            txtRawEditor.setBackground(Theme.surface());
            txtRawEditor.setForeground(Theme.text());
            txtRawEditor.setCaretColor(Theme.text());
            spEditor.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
            spEditor.setPreferredSize(new Dimension(200, 210));

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWrap, spEditor);
            split.setResizeWeight(0.54);

            JPanel footer = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
            footer.setOpaque(false);
            JPanel footLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
            footLeft.setOpaque(false);
            footLeft.add(btnForward);
            footLeft.add(btnDraft);
            footLeft.add(btnDrop);
            JPanel footRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
            footRight.setOpaque(false);
            footRight.add(btnDropAll);
            footRight.add(btnReleaseAll);
            footer.add(footLeft, BorderLayout.WEST);
            footer.add(footRight, BorderLayout.EAST);

            add(filterBar, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);

            wireEvents();
            this.themeSub = Theme.onThemeChange(this::applyTheme);
            applyTheme();
            refreshQueue();
        }

        private void wireEvents() {
            cbBranch.addActionListener(e -> {
                if (!updatingCombos) {
                    refreshFilteredTable();
                }
            });
            cbHost.addActionListener(e -> {
                if (!updatingCombos) {
                    refreshFilteredTable();
                }
            });
            searchField.onSearchChange(q -> refreshFilteredTable());

            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    int vr = table.getSelectedRow();
                    if (vr >= 0) {
                        selectedItem = model.getItemAt(table.convertRowIndexToModel(vr));
                    } else {
                        selectedItem = null;
                    }
                    syncEditorFromSelection();
                }
            });

            btnForward.addActionListener(e -> {
                if (selectedItem != null && queue != null) {
                    queue.resolveForward(selectedItem.id, txtRawEditor.getText());
                    if (toast != null) {
                        toast.showSuccess("Đã gửi Server", selectedItem.method + " " + selectedItem.path);
                    }
                }
            });
            btnDraft.addActionListener(e -> {
                if (selectedItem != null && queue != null) {
                    queue.resolveSaveDraft(selectedItem.id, txtRawEditor.getText());
                    if (toast != null) {
                        toast.showInfo("Đã lưu nháp", selectedItem.method + " " + selectedItem.path);
                    }
                }
            });
            btnDrop.addActionListener(e -> {
                if (selectedItem != null && queue != null) {
                    queue.resolveDrop(selectedItem.id);
                }
            });
            btnDropAll.addActionListener(e -> {
                if (queue != null) {
                    int n = queue.dropAll();
                    if (toast != null) {
                        toast.showWarning("Đã Drop All", n + " yêu cầu chờ duyệt");
                    }
                }
            });
            btnReleaseAll.addActionListener(e -> {
                if (ledger != null) {
                    ledger.releaseAllToNone();
                }
                int fwd = queue != null ? queue.forwardAll() : 0;
                if (toast != null) {
                    toast.showSuccess("Đã nhả về NONE", "Toàn bộ rule -> NONE, gửi " + fwd + " chờ duyệt");
                }
            });

            if (queue != null) {
                queueSub = queue.subscribe(ev -> SwingUtilities.invokeLater(this::refreshQueue));
            }
        }

        public void refreshQueue() {
            setBackground(Theme.canvas());
            txtRawEditor.setBackground(Theme.surface());
            txtRawEditor.setForeground(Theme.text());
            List<ProxyInterceptQueue.PendingInterceptItem> all = queue != null
                ? queue.snapshotPending() : List.of();
            countPill.setPill(all.size() + " chờ", all.isEmpty() ? Theme.Token.NEUTRAL : Theme.Token.PENDING, !all.isEmpty());

            updatingCombos = true;
            try {
                Object prevB = cbBranch.getSelectedItem();
                Object prevH = cbHost.getSelectedItem();
                Set<String> branches = new LinkedHashSet<>();
                Set<String> hosts = new LinkedHashSet<>();
                for (ProxyInterceptQueue.PendingInterceptItem p : all) {
                    if (!p.branchPath.isEmpty()) branches.add(p.branchPath);
                    if (!p.host.isEmpty()) hosts.add(p.host);
                }
                cbBranch.removeAllItems();
                cbBranch.addItem("Tất cả nhánh");
                for (String b : branches) cbBranch.addItem(b);
                if (prevB != null) cbBranch.setSelectedItem(prevB);

                cbHost.removeAllItems();
                cbHost.addItem("Tất cả Host");
                for (String h : hosts) cbHost.addItem(h);
                if (prevH != null) cbHost.setSelectedItem(prevH);
            } finally {
                updatingCombos = false;
            }
            refreshFilteredTable();
        }

        private void refreshFilteredTable() {
            List<ProxyInterceptQueue.PendingInterceptItem> all = queue != null
                ? queue.snapshotPending() : List.of();
            String branchFilter = cbBranch.getSelectedIndex() > 0 && cbBranch.getSelectedItem() != null
                ? String.valueOf(cbBranch.getSelectedItem()) : "";
            String hostFilter = cbHost.getSelectedIndex() > 0 && cbHost.getSelectedItem() != null
                ? String.valueOf(cbHost.getSelectedItem()) : "";
            String q = searchField.getText().trim().toLowerCase(Locale.ROOT);

            List<ProxyInterceptQueue.PendingInterceptItem> filtered = new ArrayList<>();
            for (ProxyInterceptQueue.PendingInterceptItem p : all) {
                if (!branchFilter.isEmpty() && !p.branchPath.equals(branchFilter)) continue;
                if (!hostFilter.isEmpty() && !p.host.equals(hostFilter)) continue;
                if (!q.isEmpty()
                    && !p.method.toLowerCase(Locale.ROOT).contains(q)
                    && !p.path.toLowerCase(Locale.ROOT).contains(q)
                    && !p.reason.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                filtered.add(p);
            }

            model.setItems(filtered);
            tableWrap.refreshEmptyState();
            if (filtered.isEmpty()) {
                selectedItem = null;
            } else if (table.getSelectedRow() < 0) {
                table.setRowSelectionInterval(0, 0);
                selectedItem = filtered.get(0);
            }
            syncEditorFromSelection();
        }

        private void syncEditorFromSelection() {
            boolean has = selectedItem != null;
            btnForward.setEnabled(has);
            btnDraft.setEnabled(has);
            btnDrop.setEnabled(has);
            btnDropAll.setEnabled(model.getRowCount() > 0);
            if (has) {
                txtRawEditor.setText(selectedItem.rawRequestIso88591);
                txtRawEditor.setCaretPosition(0);
                txtRawEditor.setEditable(true);
            } else {
                txtRawEditor.setText("");
                txtRawEditor.setEditable(false);
            }
        }

        public void applyTheme() {
            setBackground(Theme.canvas());
            txtRawEditor.setBackground(Theme.surface());
            txtRawEditor.setForeground(Theme.text());
            txtRawEditor.setCaretColor(Theme.text());
            spEditor.getViewport().setBackground(Theme.surface());
            spEditor.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
            tableWrap.applyTheme();
            cbBranch.setBackground(Theme.surface());
            cbBranch.setForeground(Theme.text());
            cbHost.setBackground(Theme.surface());
            cbHost.setForeground(Theme.text());
            Ui.syncTreeColors(this);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (txtRawEditor != null && tableWrap != null) {
                applyTheme();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (txtRawEditor != null && tableWrap != null) {
                applyTheme();
            }
            super.paintComponent(g);
        }

        public void dispose() {
            if (queueSub != null) {
                try {
                    queueSub.close();
                } catch (Exception ignored) {
                }
                queueSub = null;
            }
            if (themeSub != null) {
                try {
                    themeSub.close();
                } catch (Exception ignored) {
                }
                themeSub = null;
            }
        }
    }

    private static final class QueueTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Trạng thái", "Nhánh đích", "Host", "Method/Action", "Path", "Lý do giữ"};
        private List<ProxyInterceptQueue.PendingInterceptItem> items = new ArrayList<>();

        void setItems(List<ProxyInterceptQueue.PendingInterceptItem> items) {
            this.items = items != null ? items : new ArrayList<>();
            fireTableDataChanged();
        }

        ProxyInterceptQueue.PendingInterceptItem getItemAt(int mr) {
            return mr >= 0 && mr < items.size() ? items.get(mr) : null;
        }

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            ProxyInterceptQueue.PendingInterceptItem p = items.get(row);
            switch (col) {
                case 0: return "CHỜ DUYỆT";
                case 1: return p.branchPath;
                case 2: return p.host;
                case 3: return p.method;
                case 4: return p.path;
                case 5: return p.winningRuleName.isEmpty() ? p.reason : (p.winningRuleName + ": " + p.reason);
                default: return "";
            }
        }
    }

    private static final class QueueCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            javax.swing.JTable tbl, Object val, boolean sel, boolean focus, int row, int col
        ) {
            Component c = super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            c.setFont(col == 3 || col == 4 ? FontManager.monoRegular() : FontManager.uiRegular());
            if (!sel) {
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                c.setForeground(col == 0 ? Theme.token(Theme.Token.PENDING).text() : Theme.text());
                if (col == 0) {
                    c.setFont(FontManager.uiSemiBold());
                }
            } else {
                c.setBackground(Theme.selection());
                c.setForeground(Theme.selectionText());
            }
            return c;
        }
    }
}
