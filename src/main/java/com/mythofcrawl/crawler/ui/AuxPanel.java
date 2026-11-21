package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Auxiliary panel hosting Activity Log, Extra Captures (outside Excel),
 * Danger Guard Skipped Actions, and Passive JS Endpoint Hints.
 */
public final class AuxPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlState state;
    private final Consumer<CapturedApiEntry> onInspectCapture;
    private final Runnable onRevealInspector;
    private final BiConsumer<CapturedApiEntry, String> onSendRepeater;
    private final Consumer<CapturedApiEntry> onSendOrganizer;

    private final JTabbedPane auxTabs = new JTabbedPane();
    private final JTextArea txtLog = new JTextArea();
    private final JButton btnClearLog = new JButton("Xóa Log");

    private final CoverageTableModel.ExtraCaptureTableModel extraModel =
        new CoverageTableModel.ExtraCaptureTableModel();
    private final JTable tblExtras = new JTable(extraModel);
    private final TableSelectionPreserver.ColumnManager extrasColMgr;

    private final CoverageTableModel.SkippedActionTableModel skippedModel =
        new CoverageTableModel.SkippedActionTableModel();
    private final JTable tblSkipped = new JTable(skippedModel);
    private final TableSelectionPreserver.ColumnManager skippedColMgr;

    private final DefaultListModel<String> jsListModel = new DefaultListModel<>();
    private final JList<String> lstJsEndpoints = new JList<>(jsListModel);

    private final JPanel logPanel = new JPanel(new BorderLayout(4, 4));
    private final JPanel skippedWrap = new JPanel(new BorderLayout(4, 4));
    private final JPanel jsWrap = new JPanel(new BorderLayout(4, 4));
    private final JScrollPane spLog;
    private final JScrollPane spExtras;
    private final JScrollPane spSkipped;
    private final JScrollPane spJs;

    public AuxPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        Runnable onPersistConfig,
        Consumer<CapturedApiEntry> onInspectCapture,
        Runnable onRevealInspector,
        BiConsumer<CapturedApiEntry, String> onSendRepeater,
        Consumer<CapturedApiEntry> onSendOrganizer
    ) {
        this.api = api;
        this.state = state;
        this.onInspectCapture = onInspectCapture;
        this.onRevealInspector = onRevealInspector;
        this.onSendRepeater = onSendRepeater;
        this.onSendOrganizer = onSendOrganizer;

        setLayout(new BorderLayout());

        txtLog.setEditable(false);
        JPanel logTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        logTop.setOpaque(false);
        btnClearLog.addActionListener(e -> txtLog.setText(""));
        logTop.add(btnClearLog);
        spLog = new JScrollPane(txtLog);
        logPanel.add(logTop, BorderLayout.NORTH);
        logPanel.add(spLog, BorderLayout.CENTER);

        AuditTheme.configureAuditTable(tblExtras, api);
        tblExtras.setAutoCreateRowSorter(true);
        tblExtras.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblExtras.setDefaultRenderer(Object.class, new CoverageTableModel.SimpleAuditCellRenderer(api, 0, 4, 3));
        tblExtras.setDefaultRenderer(Integer.class, new CoverageTableModel.SimpleAuditCellRenderer(api, 0, 4, 3));
        this.extrasColMgr = new TableSelectionPreserver.ColumnManager(
            tblExtras, "extras", config, onPersistConfig,
            new int[]{68, 280, 260, 66, 62});
        this.spExtras = new JScrollPane(tblExtras);

        AuditTheme.configureAuditTable(tblSkipped, api);
        tblSkipped.setAutoCreateRowSorter(true);
        tblSkipped.setDefaultRenderer(Object.class, new CoverageTableModel.SimpleAuditCellRenderer(api, -1, -1, 2));
        this.skippedColMgr = new TableSelectionPreserver.ColumnManager(
            tblSkipped, "skipped", config, onPersistConfig,
            new int[]{240, 120, 90, 260});
        this.spSkipped = new JScrollPane(tblSkipped);
        skippedWrap.add(spSkipped, BorderLayout.CENTER);

        this.spJs = new JScrollPane(lstJsEndpoints);
        jsWrap.add(spJs, BorderLayout.CENTER);

        JSplitPane dangerJsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, skippedWrap, jsWrap);
        dangerJsSplit.setResizeWeight(0.5);
        dangerJsSplit.setOneTouchExpandable(false);

        auxTabs.addTab("Log hoạt động", logPanel);
        auxTabs.addTab("Ngoài Excel (0)", spExtras);
        auxTabs.addTab("Nút bị né & JS (0/0)", dangerJsSplit);
        add(auxTabs, BorderLayout.CENTER);

        wireEvents();
        applyTheme();
    }

    private void wireEvents() {
        tblExtras.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblExtras.getSelectedRow();
                if (vr >= 0 && vr < tblExtras.getRowCount() && onInspectCapture != null) {
                    CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(vr));
                    if (cap != null) {
                        onInspectCapture.accept(cap);
                    }
                }
            }
        });
        tblExtras.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblExtras.getSelectedRow() >= 0 && onRevealInspector != null) {
                    onRevealInspector.run();
                }
            }
        });

        JPopupMenu extraPopup = new JPopupMenu();
        JMenuItem miRep = new JMenuItem("Send to Repeater (Ctrl+R)");
        JMenuItem miOrg = new JMenuItem("Send to Organizer (Ctrl+O)");
        JMenuItem miLocate = new JMenuItem("Tìm vị trí trên Web...");
        JMenuItem miCopy = new JMenuItem("Copy Full URL");
        miLocate.addActionListener(e -> {
            List<CapturedApiEntry> sel = getSelectedExtras();
            if (!sel.isEmpty()) {
                CapturedApiEntry cap = sel.get(0);
                WebLocatorHelper.showLauncherMenuForCapture(tblExtras, state, cap, cap.actionPath, null);
            }
        });
        miRep.addActionListener(e -> {
            for (CapturedApiEntry cap : getSelectedExtras()) {
                if (onSendRepeater != null) {
                    onSendRepeater.accept(cap, cap.method);
                }
            }
        });
        miOrg.addActionListener(e -> {
            for (CapturedApiEntry cap : getSelectedExtras()) {
                if (onSendOrganizer != null) {
                    onSendOrganizer.accept(cap);
                }
            }
        });
        miCopy.addActionListener(e -> {
            List<CapturedApiEntry> sel = getSelectedExtras();
            if (!sel.isEmpty()) {
                copyText(sel.get(0).fullUrl);
            }
        });
        extraPopup.add(miRep);
        extraPopup.add(miOrg);
        extraPopup.add(miLocate);
        extraPopup.addSeparator();
        extraPopup.add(miCopy);

        tblExtras.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblExtras.rowAtPoint(e.getPoint());
                    if (row >= 0 && !tblExtras.isRowSelected(row)) {
                        tblExtras.setRowSelectionInterval(row, row);
                    }
                    extraPopup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
        });

        JPopupMenu jsPopup = new JPopupMenu();
        JMenuItem miCopyJs = new JMenuItem("Copy Endpoint JS");
        miCopyJs.addActionListener(e -> {
            String sel = lstJsEndpoints.getSelectedValue();
            if (sel != null) {
                copyText(sel);
            }
        });
        jsPopup.add(miCopyJs);
        lstJsEndpoints.setComponentPopupMenu(jsPopup);
    }

    public List<CapturedApiEntry> getSelectedExtras() {
        int[] vrs = tblExtras.getSelectedRows();
        if (vrs == null || vrs.length == 0) {
            return Collections.emptyList();
        }
        List<CapturedApiEntry> out = new ArrayList<>();
        for (int vr : vrs) {
            if (vr >= 0 && vr < tblExtras.getRowCount()) {
                CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(vr));
                if (cap != null) {
                    out.add(cap);
                }
            }
        }
        return out;
    }

    public void appendLogLine(String line) {
        if (txtLog.getLineCount() > 1500) {
            try {
                int end = txtLog.getLineEndOffset(400);
                txtLog.replaceRange("", 0, end);
            } catch (Exception ignored) {
            }
        }
        txtLog.append(line + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }

    public void resetTables() {
        extraModel.setRows(new ArrayList<>());
        skippedModel.setRows(new ArrayList<>());
        jsListModel.clear();
        refreshTabTitles(0, 0, 0);
    }

    public void refreshData() {
        List<CapturedApiEntry> extras = state.getExtraCaptures();
        List<CrawlState.SkippedAction> skipped = state.getSkippedDangerActions();
        List<String> jsItems = state.getDiscoveredJsEndpoints();

        if (extraModel.getRowCount() != extras.size()) {
            extraModel.setRows(extras);
        }
        if (skippedModel.getRowCount() != skipped.size()) {
            skippedModel.setRows(skipped);
        }
        if (jsListModel.size() != jsItems.size()) {
            jsListModel.clear();
            for (String s : jsItems) {
                jsListModel.addElement(s);
            }
        }
        refreshTabTitles(extras.size(), skipped.size(), jsItems.size());
    }

    private void refreshTabTitles(int extrasCount, int skippedCount, int jsCount) {
        String t1 = String.format("Ngoài Excel (%d)", extrasCount);
        if (!t1.equals(auxTabs.getTitleAt(1))) {
            auxTabs.setTitleAt(1, t1);
        }
        String t2 = String.format("Nút bị né & JS (%d/%d)", skippedCount, jsCount);
        if (!t2.equals(auxTabs.getTitleAt(2))) {
            auxTabs.setTitleAt(2, t2);
        }
    }

    private static void copyText(String s) {
        if (s != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.panelSurface(dark));
        AuditTheme.styleBurpTabbedPane(auxTabs, dark);
        logPanel.setBackground(AuditTheme.panelSurface(dark));
        skippedWrap.setBackground(AuditTheme.panelSurface(dark));
        jsWrap.setBackground(AuditTheme.panelSurface(dark));

        txtLog.setBackground(AuditTheme.tableSurface(dark));
        txtLog.setForeground(AuditTheme.textPrimary(dark));
        txtLog.setFont(AuditTheme.editorFont(api, this));

        AuditTheme.configureAuditTable(tblExtras, api);
        AuditTheme.configureAuditTable(tblSkipped, api);
        AuditTheme.styleScrollPane(spLog, dark);
        AuditTheme.styleScrollPane(spExtras, dark);
        AuditTheme.styleScrollPane(spSkipped, dark);
        AuditTheme.styleScrollPane(spJs, dark);
        skippedWrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        jsWrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        AuditTheme.stylePlainButton(btnClearLog, dark);

        lstJsEndpoints.setBackground(AuditTheme.tableSurface(dark));
        lstJsEndpoints.setForeground(AuditTheme.textPrimary(dark));
        lstJsEndpoints.setFont(AuditTheme.displayFont(api, this));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (auxTabs != null && txtLog != null) {
            applyTheme();
        }
    }
}
