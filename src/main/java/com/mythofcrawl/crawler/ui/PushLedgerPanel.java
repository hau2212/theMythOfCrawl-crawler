package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.ExtenderPushEntry;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Clean user-approved queue & 1-to-1 Push Ledger for replaying APIs via Burp Extender -> Logger++ -> Elastic
 * without modifying HTTP wire packet bytes for naming.
 */
public final class PushLedgerPanel extends JPanel {

    private final MontoyaApi api;
    private final ActionGroupStore groupStore;
    private final ExtenderPushManager pushManager;
    private final Supplier<ActionGroup> selectedGroupSupplier;
    private final Consumer<ExtenderPushEntry> onInspectEntry;
    private final Consumer<ApiCall> onSendRepeater;
    private final Consumer<String> logger;

    private final PushLedgerModel tableModel;
    private final JTable tblPush;
    private final JSpinner spDelayMs = new JSpinner(new SpinnerNumberModel(150, 0, 5000, 50));
    private final JButton btnPushNow = new JButton("📤 Đẩy đã chọn sang Logger++ (Extender)");
    private final JButton btnCancelPush = new JButton("⏹ Hủy Đẩy");
    private final JLabel lblProgress = new JLabel("Hàng đợi: 0 | Đã đẩy: 0");

    public PushLedgerPanel(
        MontoyaApi api,
        ActionGroupStore groupStore,
        ExtenderPushManager pushManager,
        Supplier<ActionGroup> selectedGroupSupplier,
        Consumer<ExtenderPushEntry> onInspectEntry,
        Consumer<ApiCall> onSendRepeater,
        Consumer<String> logger
    ) {
        this.api = api;
        this.groupStore = groupStore;
        this.pushManager = pushManager;
        this.selectedGroupSupplier = selectedGroupSupplier;
        this.onInspectEntry = onInspectEntry;
        this.onSendRepeater = onSendRepeater;
        this.logger = logger;

        this.tableModel = new PushLedgerModel();
        this.tblPush = new JTable(tableModel);

        boolean dark = AuditTheme.isDark(api, this);
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        setBackground(AuditTheme.canvasSurface(dark));

        add(buildToolbar(dark), BorderLayout.NORTH);
        add(buildTablePane(dark), BorderLayout.CENTER);
        refreshView();
    }

    private JPanel buildToolbar(boolean dark) {
        JPanel wrap = new JPanel(new BorderLayout(4, 4));
        wrap.setOpaque(false);

        JLabel guide = new JLabel(
            "Luồng chuẩn Logger++: Bỏ tick Proxy & All Tools bên Logger++, CHỈ tick [Extender] -> Start Elastic Exporter -> Chọn API sạch ở đây và bấm Đẩy (giữ nguyên 100% gói tin).");
        guide.setForeground(AuditTheme.accentForeground(dark));
        guide.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        AuditTheme.styleActionCard(row1, dark);

        JButton btnAddSelGroup = new JButton("+ Nạp Nhóm đang chọn");
        btnAddSelGroup.addActionListener(e -> {
            ActionGroup g = selectedGroupSupplier != null ? selectedGroupSupplier.get() : null;
            if (g == null || g.callCount() == 0) {
                JOptionPane.showMessageDialog(this,
                    "Hãy chọn một Nhóm có API trong tab 'Nhóm & API' trước.",
                    "Chưa chọn nhóm", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int added = pushManager.enqueueGroup(g);
            if (logger != null) {
                logger.accept("Đã nạp " + added + " API từ nhóm '" + g.actionPath + "' vào hàng chờ đẩy Extender.");
            }
            refreshView();
        });

        JButton btnAddAllClean = new JButton("+ Nạp toàn bộ API đã nhóm");
        btnAddAllClean.addActionListener(e -> {
            List<ApiCall> all = new ArrayList<>();
            for (ActionGroup g : groupStore.snapshotGroups()) {
                all.addAll(g.getCalls());
            }
            int added = pushManager.enqueueCalls(all);
            if (logger != null) {
                logger.accept("Đã nạp " + added + " API sạch vào hàng chờ đẩy Extender.");
            }
            refreshView();
        });

        JButton btnSelectAll = new JButton("Chọn hết");
        btnSelectAll.addActionListener(e -> { pushManager.selectAll(true); refreshView(); });

        JButton btnDeselectAll = new JButton("Bỏ chọn hết");
        btnDeselectAll.addActionListener(e -> { pushManager.selectAll(false); refreshView(); });

        JButton btnRequeue = new JButton("Đặt lại 'Chờ đẩy'");
        btnRequeue.setToolTipText("Cho phép đẩy lại các dòng đã chọn (kể cả dòng Đã đẩy/Lỗi trước đó)");
        btnRequeue.addActionListener(e -> { pushManager.requeueSelected(); refreshView(); });

        JButton btnClearAll = new JButton("Xóa danh sách");
        btnClearAll.addActionListener(e -> { pushManager.clearAll(); refreshView(); });

        row1.add(btnAddSelGroup);
        row1.add(btnAddAllClean);
        row1.add(btnSelectAll);
        row1.add(btnDeselectAll);
        row1.add(btnRequeue);
        row1.add(btnClearAll);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        AuditTheme.styleActionCard(row2, dark);

        btnPushNow.setFont(btnPushNow.getFont().deriveFont(Font.BOLD));
        btnPushNow.addActionListener(e -> onConfirmAndPush());

        btnCancelPush.setEnabled(false);
        btnCancelPush.addActionListener(e -> pushManager.requestCancel());

        JButton btnExportLedger = new JButton("Xuất Bảng Đối Chiếu CSV");
        btnExportLedger.setToolTipText("Xuất bảng đối chiếu # | Giờ đẩy | Tên Nhóm | Endpoint để điền tên lên Server");
        btnExportLedger.addActionListener(e -> onExportCsv());

        row2.add(btnPushNow);
        row2.add(btnCancelPush);
        row2.add(new JLabel("Delay mỗi request (ms):"));
        row2.add(spDelayMs);
        row2.add(lblProgress);
        row2.add(btnExportLedger);

        JPanel rows = new JPanel(new BorderLayout(4, 4));
        rows.setOpaque(false);
        rows.add(row1, BorderLayout.NORTH);
        rows.add(row2, BorderLayout.SOUTH);

        wrap.add(guide, BorderLayout.NORTH);
        wrap.add(rows, BorderLayout.CENTER);
        return wrap;
    }

    private JScrollPane buildTablePane(boolean dark) {
        AuditTheme.configureAuditTable(tblPush, api);
        tblPush.setAutoCreateRowSorter(true);
        tblPush.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblPush.setDefaultRenderer(Object.class, new LedgerRenderer(api));
        tblPush.setDefaultRenderer(Integer.class, new LedgerRenderer(api));

        TableColumnModel cm = tblPush.getColumnModel();
        int[] widths = {44, 38, 88, 92, 250, 66, 220, 62, 66, 68, 170};
        for (int i = 0; i < widths.length && i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
        }

        tblPush.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && onInspectEntry != null) {
                ExtenderPushEntry entry = getSelectedEntry();
                if (entry != null) {
                    onInspectEntry.accept(entry);
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miCopyName = new JMenuItem("Copy Tên Nhóm (Action Path)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");
        JMenuItem miSendRep = new JMenuItem("Send to Repeater");
        miCopyName.addActionListener(e -> {
            ExtenderPushEntry en = getSelectedEntry();
            if (en != null) {
                copyText(en.groupPath);
            }
        });
        miCopyUrl.addActionListener(e -> {
            ExtenderPushEntry en = getSelectedEntry();
            if (en != null && en.capture != null) {
                copyText(en.capture.fullUrl);
            }
        });
        miSendRep.addActionListener(e -> {
            ExtenderPushEntry en = getSelectedEntry();
            if (en != null && onSendRepeater != null) {
                onSendRepeater.accept(findMatchingApiCall(en));
            }
        });
        popup.add(miCopyName);
        popup.add(miCopyUrl);
        popup.addSeparator();
        popup.add(miSendRep);

        tblPush.addMouseListener(new MouseAdapter() {
            private void maybePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int r = tblPush.rowAtPoint(e.getPoint());
                    if (r >= 0) {
                        tblPush.setRowSelectionInterval(r, r);
                        popup.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
        });

        return new JScrollPane(tblPush);
    }

    private ApiCall findMatchingApiCall(ExtenderPushEntry en) {
        ActionGroup g = groupStore.getGroup(en.groupId);
        if (g != null) {
            for (ApiCall c : g.getCalls()) {
                if (c.index == en.callIndex) {
                    return c;
                }
            }
        }
        return null;
    }

    private void onConfirmAndPush() {
        int pending = pushManager.pendingSelectedCount();
        if (pending <= 0) {
            JOptionPane.showMessageDialog(this,
                "Không có API nào ở trạng thái 'Chờ đẩy' đang được tick chọn.",
                "Danh sách trống", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int delay = ((Number) spDelayMs.getValue()).intValue();
        int choice = JOptionPane.showConfirmDialog(this,
            "Xác nhận gửi THỰC TẾ " + pending + " HTTP request qua kênh Burp Extender -> Logger++?\n"
                + "• Gói tin HTTP được giữ nguyên bản 100%, đính Tên Nhóm qua Burp Comment (Annotations).\n"
                + "• Hãy chắc chắn bên Logger++ đã tick [Extender] và bật Start Elastic Exporter.\n"
                + "• Độ trễ giữa mỗi request: " + delay + " ms.",
            "Xác nhận Đẩy sang Logger++ -> Elastic",
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        pushManager.startPushAsync(delay);
        refreshView();
    }

    private void onExportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("mythofcrawl_push_ledger.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            pushManager.exportLedgerCsv(fc.getSelectedFile());
            JOptionPane.showMessageDialog(this,
                "Đã lưu Bảng Đối Chiếu đẩy Elastic tại:\n" + fc.getSelectedFile().getAbsolutePath(),
                "Xuất CSV thành công", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi xuất file: " + ex.getMessage(),
                "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void refreshView() {
        boolean running = pushManager.isPushing();
        btnPushNow.setEnabled(!running);
        btnCancelPush.setEnabled(running);
        lblProgress.setText("Tổng: " + pushManager.totalCount()
            + " | Chờ: " + pushManager.pendingSelectedCount()
            + " | Đã đẩy: " + pushManager.sentCount());
        int prevRow = tblPush.getSelectedRow();
        tableModel.refresh();
        if (prevRow >= 0 && prevRow < tblPush.getRowCount()) {
            tblPush.setRowSelectionInterval(prevRow, prevRow);
        }
    }

    private ExtenderPushEntry getSelectedEntry() {
        int vr = tblPush.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return tableModel.getEntryAt(tblPush.convertRowIndexToModel(vr));
    }

    private static void copyText(String s) {
        if (s != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
    }

    private final class PushLedgerModel extends AbstractTableModel {
        private final String[] COLS = {
            "Chọn", "#", "Giờ Đẩy", "Trạng thái", "Tên Nhóm (Action Path)", "Method",
            "Endpoint / Path", "Excel", "HTTP Gốc", "HTTP Đẩy", "NOTE"
        };
        private List<ExtenderPushEntry> rows = pushManager.snapshotEntries();

        void refresh() {
            this.rows = pushManager.snapshotEntries();
            fireTableDataChanged();
        }

        ExtenderPushEntry getEntryAt(int mr) {
            return mr >= 0 && mr < rows.size() ? rows.get(mr) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 0) return Boolean.class;
            if (col == 1 || col == 8) return Integer.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 0 && !pushManager.isPushing();
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col == 0 && value instanceof Boolean && row >= 0 && row < rows.size()) {
                rows.get(row).setSelected((Boolean) value);
                lblProgress.setText("Tổng: " + pushManager.totalCount()
                    + " | Chờ: " + pushManager.pendingSelectedCount()
                    + " | Đã đẩy: " + pushManager.sentCount());
            }
        }

        @Override
        public Object getValueAt(int row, int col) {
            ExtenderPushEntry e = rows.get(row);
            switch (col) {
                case 0: return e.isSelected();
                case 1: return e.seq;
                case 2: return e.getPushedAtText();
                case 3: return e.getStatus().label;
                case 4: return e.groupPath;
                case 5: return e.method;
                case 6: return e.endpoint;
                case 7: return e.excelStt.isEmpty() ? "-" : ("STT " + e.excelStt);
                case 8: return e.originalStatus;
                case 9: return e.getPushedHttpStatus() > 0 ? String.valueOf(e.getPushedHttpStatus()) : "-";
                case 10: return !e.getErrorText().isEmpty() ? e.getErrorText() : e.notesSummary;
                default: return "";
            }
        }
    }

    private static final class LedgerRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        LedgerRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 1 || column == 2 || column == 3 || column == 5
                || column == 7 || column == 8 || column == 9) ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected && table.getModel() instanceof PushLedgerModel) {
                ExtenderPushEntry en = ((PushLedgerModel) table.getModel())
                    .getEntryAt(table.convertRowIndexToModel(row));
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = AuditTheme.textPrimary(dark);
                if (en != null) {
                    if (en.getStatus() == ExtenderPushEntry.Status.FAILED) {
                        bg = AuditTheme.severityRowTint(AuditNote.Severity.ERROR, AuditTheme.tableSurface(dark), dark);
                    } else if (en.getStatus() == ExtenderPushEntry.Status.SENT) {
                        bg = AuditTheme.blend(AuditTheme.tableSurface(dark),
                            AuditTheme.okForeground(dark), dark ? 0.14f : 0.10f);
                    }
                    if (column == 3) {
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                        if (en.getStatus() == ExtenderPushEntry.Status.SENT) {
                            fg = AuditTheme.okForeground(dark);
                        } else if (en.getStatus() == ExtenderPushEntry.Status.FAILED) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark);
                        } else if (en.getStatus() == ExtenderPushEntry.Status.SENDING) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.INFO, dark);
                        }
                    } else if (column == 5) {
                        fg = AuditTheme.methodForeground(en.method, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 8) {
                        fg = AuditTheme.statusCodeForeground(en.originalStatus, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 9 && en.getPushedHttpStatus() > 0) {
                        fg = AuditTheme.statusCodeForeground(en.getPushedHttpStatus(), dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 4) {
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }
}
