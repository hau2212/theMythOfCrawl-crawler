package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
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
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Clean user-approved queue & 1-to-1 Push Ledger styled after Burp Suite Logger:
 * - Full-row semantic colorization by push status / HTTP status with auto-contrast text
 * - BurpFilterPill status filter strip (Tất cả, Chờ đẩy, Đã đẩy, Lỗi) + sticky BurpFilterBar
 * - Zero HTTP wire packet byte edits; optional Visual Spotlight Web Locator
 */
public final class PushLedgerPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlState state;
    private final ActionGroupStore groupStore;
    private final ExtenderPushManager pushManager;
    private final Supplier<ActionGroup> selectedGroupSupplier;
    private final Consumer<ExtenderPushEntry> onInspectEntry;
    private final Consumer<ExtenderPushEntry> onRevealInspector;
    private final Consumer<ApiCall> onSendRepeater;
    private final Consumer<String> logger;

    private final PushLedgerModel tableModel;
    private final JTable tblPush;
    private final TableSelectionPreserver.ColumnManager colMgr;
    private final JScrollPane spPush;
    private final BurpFilterBar filterBar;

    private final JPanel slimToolbar = new JPanel(new BorderLayout(8, 2));
    private final JPanel leftControls = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel rightControls = new JPanel(new WrapLayout(FlowLayout.RIGHT, 6, 2));
    private final JButton btnActions = new JButton("Actions");
    private final JLabel lblHelpGuide = new JLabel();
    private final JSpinner spDelayMs = new JSpinner(new SpinnerNumberModel(150, 0, 5000, 50));
    private final BurpComponents.BurpOrangePillButton btnPushNow;
    private final JButton btnCancelPush = new JButton("Hủy");
    private final JLabel lblProgress = new JLabel("0/0");
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel emptyState;

    private final BurpComponents.BurpFilterPill pillAll;
    private final BurpComponents.BurpFilterPill pillQueued;
    private final BurpComponents.BurpFilterPill pillSent;
    private final BurpComponents.BurpFilterPill pillFailed;
    private String statusFilterMode = "ALL";
    private final boolean[] restoring = {false};

    public PushLedgerPanel(
        MontoyaApi api,
        ActionGroupStore groupStore,
        ExtenderPushManager pushManager,
        Supplier<ActionGroup> selectedGroupSupplier,
        Consumer<ExtenderPushEntry> onInspectEntry,
        Consumer<ApiCall> onSendRepeater,
        Consumer<String> logger
    ) {
        this(api, null, null, null, groupStore, pushManager, selectedGroupSupplier,
            onInspectEntry, null, onSendRepeater, logger);
    }

    public PushLedgerPanel(
        MontoyaApi api,
        CrawlConfig config,
        Runnable onPersistConfig,
        ActionGroupStore groupStore,
        ExtenderPushManager pushManager,
        Supplier<ActionGroup> selectedGroupSupplier,
        Consumer<ExtenderPushEntry> onInspectEntry,
        Consumer<ExtenderPushEntry> onRevealInspector,
        Consumer<ApiCall> onSendRepeater,
        Consumer<String> logger
    ) {
        this(api, null, config, onPersistConfig, groupStore, pushManager, selectedGroupSupplier,
            onInspectEntry, onRevealInspector, onSendRepeater, logger);
    }

    public PushLedgerPanel(
        MontoyaApi api,
        CrawlState state,
        CrawlConfig config,
        Runnable onPersistConfig,
        ActionGroupStore groupStore,
        ExtenderPushManager pushManager,
        Supplier<ActionGroup> selectedGroupSupplier,
        Consumer<ExtenderPushEntry> onInspectEntry,
        Consumer<ExtenderPushEntry> onRevealInspector,
        Consumer<ApiCall> onSendRepeater,
        Consumer<String> logger
    ) {
        this.api = api;
        this.state = state;
        this.groupStore = groupStore;
        this.pushManager = pushManager;
        this.selectedGroupSupplier = selectedGroupSupplier;
        this.onInspectEntry = onInspectEntry;
        this.onRevealInspector = onRevealInspector;
        this.onSendRepeater = onSendRepeater;
        this.logger = logger;

        this.btnPushNow = new BurpComponents.BurpOrangePillButton(
            api, "Đẩy đã chọn sang Logger++", BurpIcons.send(Color.WHITE));
        this.pillAll = new BurpComponents.BurpFilterPill(api, "Tất cả", true);
        this.pillQueued = new BurpComponents.BurpFilterPill(api, "Chờ đẩy", false);
        this.pillSent = new BurpComponents.BurpFilterPill(api, "Đã đẩy", false);
        this.pillFailed = new BurpComponents.BurpFilterPill(api, "Lỗi", false);

        this.filterBar = new BurpFilterBar(
            api,
            EnumSet.of(BurpFilterBar.QuickChip.ERRORS_4XX_5XX, BurpFilterBar.QuickChip.MUTATING),
            true,
            false
        );
        this.tableModel = new PushLedgerModel();
        this.tblPush = new JTable(tableModel);
        this.spPush = new JScrollPane(tblPush);
        this.emptyState = new BurpComponents.EmptyStatePanel(
            api,
            "No items to show",
            "Queue APIs from HTTP History or use Actions -> Add All to push clean requests to Logger++."
        );

        setLayout(new BorderLayout(0, 0));

        add(buildToolbar(), BorderLayout.NORTH);
        buildTablePane();
        this.colMgr = new TableSelectionPreserver.ColumnManager(
            tblPush, "pushLedger", config, onPersistConfig,
            new int[]{46, 42, 88, 92, 240, 66, 220, 62, 66, 68, 170}, 2, 7);
        centerCards.add(spPush, "TABLE");
        centerCards.add(emptyState, "EMPTY");
        add(centerCards, BorderLayout.CENTER);

        applyTheme();
        refreshView();
    }

    private JPanel buildToolbar() {
        JPanel wrap = new JPanel(new BorderLayout(0, 0));
        wrap.setOpaque(false);

        lblHelpGuide.setToolTipText(
            "Luồng chuẩn Logger++: Bỏ tick Proxy & All Tools bên Logger++, CHỈ tick [Extender] -> Start Elastic Exporter -> Chọn API sạch ở đây và bấm Đẩy.");

        pillAll.addActionListener(e -> setStatusFilter("ALL"));
        pillQueued.addActionListener(e -> setStatusFilter("QUEUED"));
        pillSent.addActionListener(e -> setStatusFilter("SENT"));
        pillFailed.addActionListener(e -> setStatusFilter("FAILED"));

        btnActions.addActionListener(e -> showActionsMenu());

        leftControls.setOpaque(false);
        leftControls.add(lblHelpGuide);
        leftControls.add(pillAll);
        leftControls.add(pillQueued);
        leftControls.add(pillSent);
        leftControls.add(pillFailed);
        leftControls.add(btnActions);

        btnPushNow.addActionListener(e -> onConfirmAndPush());
        btnCancelPush.setVisible(false);
        btnCancelPush.addActionListener(e -> pushManager.requestCancel());

        rightControls.setOpaque(false);
        rightControls.add(lblProgress);
        rightControls.add(new JLabel("Delay:"));
        rightControls.add(spDelayMs);
        rightControls.add(btnCancelPush);
        rightControls.add(btnPushNow);

        slimToolbar.add(leftControls, BorderLayout.WEST);
        slimToolbar.add(rightControls, BorderLayout.EAST);

        wrap.add(slimToolbar, BorderLayout.NORTH);
        wrap.add(filterBar, BorderLayout.SOUTH);
        return wrap;
    }

    private void showActionsMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miAddGrp = new JMenuItem("Nạp Nhóm đang chọn từ HTTP History");
        JMenuItem miAddAll = new JMenuItem("Nạp toàn bộ API đã bắt");
        JMenuItem miSelAll = new JMenuItem("Chọn tất cả");
        JMenuItem miDeselAll = new JMenuItem("Bỏ chọn tất cả");
        JMenuItem miRequeue = new JMenuItem("Đặt lại 'Chờ đẩy' cho dòng đã chọn");
        JMenuItem miClear = new JMenuItem("Xóa danh sách");
        JMenuItem miExport = new JMenuItem("Xuất CSV Đối Chiếu Elastic...");

        miAddGrp.addActionListener(e -> {
            ActionGroup g = selectedGroupSupplier != null ? selectedGroupSupplier.get() : null;
            if (g == null || g.callCount() == 0) {
                JOptionPane.showMessageDialog(this,
                    "Hãy chọn một Nhóm có API trong tab 'HTTP History' trước.",
                    "Chưa chọn nhóm", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int added = pushManager.enqueueGroup(g);
            if (logger != null) {
                logger.accept("Đã nạp " + added + " API từ nhóm '" + g.actionPath + "' vào hàng chờ đẩy Extender.");
            }
            if (added == 0) {
                refreshView();
            }
        });
        miAddAll.addActionListener(e -> {
            List<ApiCall> all = new ArrayList<>();
            for (ActionGroup g : groupStore.snapshotGroups()) {
                all.addAll(g.getCalls());
            }
            int added = pushManager.enqueueCalls(all);
            if (logger != null) {
                logger.accept("Đã nạp " + added + " API sạch vào hàng chờ đẩy Extender.");
            }
            if (added == 0) {
                refreshView();
            }
        });
        miSelAll.addActionListener(e -> pushManager.selectAll(true));
        miDeselAll.addActionListener(e -> pushManager.selectAll(false));
        miRequeue.addActionListener(e -> pushManager.requeueSelected());
        miClear.addActionListener(e -> pushManager.clearAll());
        miExport.addActionListener(e -> onExportCsv());

        menu.add(miAddGrp);
        menu.add(miAddAll);
        menu.addSeparator();
        menu.add(miSelAll);
        menu.add(miDeselAll);
        menu.add(miRequeue);
        menu.add(miClear);
        menu.addSeparator();
        menu.add(miExport);
        menu.show(btnActions, 0, btnActions.getHeight());
    }

    private void setStatusFilter(String mode) {
        this.statusFilterMode = mode;
        pillAll.setSelected("ALL".equals(mode));
        pillQueued.setSelected("QUEUED".equals(mode));
        pillSent.setSelected("SENT".equals(mode));
        pillFailed.setSelected("FAILED".equals(mode));
        refreshView();
    }

    private void buildTablePane() {
        AuditTheme.configureAuditTable(tblPush, api);
        tblPush.setAutoCreateRowSorter(true);
        tblPush.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblPush.setDefaultRenderer(Object.class, new LedgerRenderer(api));
        tblPush.setDefaultRenderer(Integer.class, new LedgerRenderer(api));
        tblPush.setDefaultRenderer(Boolean.class, new TableSelectionPreserver.BooleanCellRenderer(api, (table, vr, mr, dark) -> {
            ExtenderPushEntry en = tableModel.getEntryAt(mr);
            if (en != null) {
                return resolveLoggerRowBg(en, vr, dark);
            }
            return AuditTheme.zebraRowBackground(table.getBackground(), vr, dark);
        }));

        filterBar.addFilterChangeListener(c -> refreshView());

        tblPush.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoring[0] && onInspectEntry != null) {
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
        if (state != null) {
            popup.add(WebLocatorHelper.createSubMenuForCall(this, state, () -> {
                ExtenderPushEntry en = getSelectedEntry();
                return en != null ? findMatchingApiCall(en) : null;
            }, logger));
        }

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
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblPush.getSelectedRow() >= 0) {
                    ExtenderPushEntry entry = getSelectedEntry();
                    if (entry != null) {
                        if (onRevealInspector != null) {
                            onRevealInspector.accept(entry);
                        } else if (onInspectEntry != null) {
                            onInspectEntry.accept(entry);
                        }
                    }
                }
            }
        });
    }

    private static Color resolveLoggerRowBg(ExtenderPushEntry en, int viewRow, boolean dark) {
        Color base = AuditTheme.tableSurface(dark);
        if (en.getStatus() == ExtenderPushEntry.Status.FAILED) {
            return com.mythofcrawl.crawler.ui.theme.Theme.token(
                com.mythofcrawl.crawler.ui.theme.Theme.Token.DANGER, dark).fillStrong();
        }
        if (en.getStatus() == ExtenderPushEntry.Status.SENDING) {
            return com.mythofcrawl.crawler.ui.theme.Theme.token(
                com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).fillStrong();
        }
        if (en.getStatus() == ExtenderPushEntry.Status.SENT) {
            int st = en.getPushedHttpStatus() > 0 ? en.getPushedHttpStatus() : en.originalStatus;
            if (st >= 500) {
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.CRITICAL, dark).fillStrong();
            }
            if (st == 401 || st == 403 || st >= 400) {
                return com.mythofcrawl.crawler.ui.theme.Theme.token(
                    com.mythofcrawl.crawler.ui.theme.Theme.Token.WARNING, dark).fillStrong();
            }
            return com.mythofcrawl.crawler.ui.theme.Theme.token(
                com.mythofcrawl.crawler.ui.theme.Theme.Token.SUCCESS, dark).fillStrong();
        }
        return AuditTheme.zebraRowBackground(base, viewRow, dark);
    }

    private ApiCall findMatchingApiCall(ExtenderPushEntry en) {
        ActionGroup g = groupStore.getGroup(en.groupId);
        if (g != null) {
            for (ApiCall c : g.getCalls()) {
                if (c.callUid == en.callUid || c.index == en.callIndex) {
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
        File target = fc.getSelectedFile();
        btnActions.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                pushManager.exportLedgerCsv(target);
                return null;
            }
            @Override
            protected void done() {
                btnActions.setEnabled(true);
                try {
                    get();
                    JOptionPane.showMessageDialog(PushLedgerPanel.this,
                        "Đã lưu Bảng Đối Chiếu đẩy Elastic tại:\n" + target.getAbsolutePath(),
                        "Xuất CSV thành công", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(PushLedgerPanel.this,
                        "Lỗi xuất file: " + ex.getMessage(),
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void refreshView() {
        boolean running = pushManager.isPushing();
        btnPushNow.setEnabled(!running);
        btnCancelPush.setVisible(running);
        btnCancelPush.setEnabled(running);
        lblProgress.setText("Tổng: " + pushManager.totalCount()
            + " | Chờ: " + pushManager.pendingSelectedCount()
            + " | Đã đẩy: " + pushManager.sentCount());

        TableSelectionPreserver.preserveAroundUpdate(tblPush, tableModel::getKeyAt, restoring, () -> {
            tableModel.refresh(statusFilterMode, filterBar.getCriteria());
        });
        filterBar.setCounts(tableModel.getRowCount(), tableModel.getTotalSourceCount());
        ((CardLayout) centerCards.getLayout()).show(centerCards, tableModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    public void focusSearch() {
        filterBar.focusSearch();
    }

    public void clearActiveFilter() {
        filterBar.clearFilters();
    }

    private ExtenderPushEntry getSelectedEntry() {
        int vr = tblPush.getSelectedRow();
        if (vr < 0 || vr >= tblPush.getRowCount()) {
            return null;
        }
        return tableModel.getEntryAt(tblPush.convertRowIndexToModel(vr));
    }

    private static void copyText(String s) {
        if (s != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.canvasSurface(dark));
        AuditTheme.styleSlimToolbar(slimToolbar, dark);
        lblHelpGuide.setIcon(BurpIcons.helpCircle(AuditTheme.mutedForeground(dark)));
        lblProgress.setForeground(AuditTheme.mutedForeground(dark));
        lblProgress.setFont(AuditTheme.displayFont(api, this));

        pillAll.refreshStyle();
        pillQueued.setActiveFill(AuditTheme.BURP_ACTIVE_PILL_BG);
        pillSent.setActiveFill(AuditTheme.okForeground(dark));
        pillFailed.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));

        btnActions.setIcon(BurpIcons.chevronDown(AuditTheme.neutralButtonFg(dark)));
        btnActions.setHorizontalTextPosition(SwingConstants.LEFT);
        AuditTheme.stylePlainButton(btnActions, dark);
        AuditTheme.stylePlainButton(btnCancelPush, dark);
        AuditTheme.configureAuditTable(tblPush, api);
        AuditTheme.styleScrollPane(spPush, dark);
        emptyState.applyTheme();
        filterBar.applyTheme(api);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (slimToolbar != null && filterBar != null) {
            applyTheme();
        }
    }

    private final class PushLedgerModel extends AbstractTableModel {
        private final String[] COLS = {
            "Chọn", "#", "Giờ Đẩy", "Trạng thái", "Tên Nhóm (Action Path)", "Method",
            "Endpoint / Path", "Excel", "HTTP Gốc", "HTTP Đẩy", "NOTE"
        };
        private List<ExtenderPushEntry> rows = new ArrayList<>();
        private int totalSourceCount = 0;

        int getTotalSourceCount() {
            return totalSourceCount;
        }

        void refresh(String statusMode, BurpFilterBar.FilterCriteria criteria) {
            List<ExtenderPushEntry> all = pushManager.snapshotEntries();
            this.totalSourceCount = all.size();
            List<ExtenderPushEntry> out = new ArrayList<>(all.size());
            for (ExtenderPushEntry en : all) {
                if ("QUEUED".equals(statusMode) && en.getStatus() != ExtenderPushEntry.Status.QUEUED
                    && en.getStatus() != ExtenderPushEntry.Status.SENDING) {
                    continue;
                }
                if ("SENT".equals(statusMode) && en.getStatus() != ExtenderPushEntry.Status.SENT) {
                    continue;
                }
                if ("FAILED".equals(statusMode) && en.getStatus() != ExtenderPushEntry.Status.FAILED) {
                    continue;
                }
                if (criteria != null && criteria.isAnyFilterActive()) {
                    int st = en.getPushedHttpStatus() > 0 ? en.getPushedHttpStatus() : en.originalStatus;
                    boolean hit = criteria.matches(
                        en.method, st, "", 0, !en.notesSummary.isEmpty(), !en.excelStt.isEmpty(), false,
                        en.groupPath, en.endpoint, en.method, en.getStatus().label, en.notesSummary, en.getErrorText());
                    if (!hit) {
                        continue;
                    }
                }
                out.add(en);
            }
            this.rows = out;
            fireTableDataChanged();
        }

        ExtenderPushEntry getEntryAt(int mr) {
            return mr >= 0 && mr < rows.size() ? rows.get(mr) : null;
        }

        Integer getKeyAt(int mr) {
            ExtenderPushEntry en = getEntryAt(mr);
            return en != null ? Integer.valueOf(en.seq) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 0) return Boolean.class;
            if (col == 1 || col == 8 || col == 9) return Integer.class;
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
                fireTableCellUpdated(row, col);
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
                case 1: return Integer.valueOf(e.seq);
                case 2: return e.getPushedAtText();
                case 3: return e.getStatus().label;
                case 4: return e.groupPath;
                case 5: return e.method;
                case 6: return e.endpoint;
                case 7: return e.excelStt.isEmpty() ? "-" : ("STT " + e.excelStt);
                case 8: return Integer.valueOf(e.originalStatus);
                case 9: return Integer.valueOf(e.getPushedHttpStatus());
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
            Object displayVal = value;
            int modelCol = table.convertColumnIndexToModel(column);
            if (modelCol == 9 && value instanceof Integer && ((Integer) value).intValue() <= 0) {
                displayVal = "-";
            }
            Component c = super.getTableCellRendererComponent(table, displayVal, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((modelCol == 1 || modelCol == 2 || modelCol == 3 || modelCol == 5
                || modelCol == 7 || modelCol == 8 || modelCol == 9) ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected && table.getModel() instanceof PushLedgerModel) {
                ExtenderPushEntry en = ((PushLedgerModel) table.getModel())
                    .getEntryAt(table.convertRowIndexToModel(row));
                Color bg = en != null ? resolveLoggerRowBg(en, row, dark) : AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                boolean coloredRow = en != null && (en.getStatus() == ExtenderPushEntry.Status.SENT
                    || en.getStatus() == ExtenderPushEntry.Status.FAILED
                    || en.getStatus() == ExtenderPushEntry.Status.SENDING);
                Color fg = coloredRow ? AuditTheme.contrastForeground(bg) : AuditTheme.textPrimary(dark);

                if (en != null && (modelCol == 3 || modelCol == 4 || modelCol == 5 || modelCol == 8 || modelCol == 9)) {
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                    if (!coloredRow) {
                        if (modelCol == 5) {
                            fg = AuditTheme.methodForeground(en.method, dark);
                        } else if (modelCol == 8) {
                            fg = AuditTheme.statusCodeForeground(en.originalStatus, dark);
                        }
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }
}
