package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ReportExporter;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.FormFillProfile;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.SeedDiscoveryHelper;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Eye-friendly Burp Suite Audit UI for "theMythOfCrawl Crawler":
 * - Groups every triggered API under its UI action group ("toppage > contact > button 'Send'"),
 *   preserving the group name on every API inside it and highlighting anomalies via Audit NOTEs.
 * - Dedicated tabs for Groups & APIs, Consolidated NOTE Table, Excel STT Coverage,
 *   Hierarchy Tree, Configurable Form Auto-Fill ("TEXT") & Engine Settings, and Aux/Logs.
 */
public final class CrawlerTabPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private final Runnable onPersistConfig;
    private volatile ExcelSpecMatcher matcher;

    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Timer refreshTimer;
    private File lastDirectory;

    // Top Row 1: Primary Workflow Controls
    private final JLabel lblExcelStatus = new JLabel("Chưa nạp Excel");
    private final JTextField txtHosts = new JTextField(16);
    private final JTextField txtSeedUrl = new JTextField(18);
    private final JButton btnStart = new JButton("Bắt đầu Crawl");
    private final JButton btnPause = new JButton("Tạm dừng");
    private final JButton btnStop = new JButton("Dừng");

    // Top Row 2: Quick Toggles & Calm Stat Chips
    private final JCheckBox chkArmAgent = new JCheckBox("Bơm Agent", true);
    private final JCheckBox chkPassive = new JCheckBox("Bắt thụ động", false);
    private final JCheckBox chkStrictQuick = new JCheckBox("Chỉ giữ Excel", true);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel chipGroups = new JLabel("Nhóm: 0");
    private final JLabel chipApis = new JLabel("API: 0");
    private final JLabel chipNotes = new JLabel("NOTE: 0");
    private final JLabel chipExcel = new JLabel("Excel: 0/0");
    private final JLabel chipPages = new JLabel("Trang: 0 | Q: 0");

    // Top Row 3: Live Crawl & Form Profile Strip
    private final JLabel lblCurrentAction = new JLabel(
        "Sẵn sàng - Nhập Host(s), mở Firefox/Chrome qua Burp Proxy rồi bấm Bắt đầu Crawl.");
    private final JLabel lblFormSummary = new JLabel();

    // Main Tabs
    private final JTabbedPane mainTabs = new JTabbedPane();
    private final JTabbedPane auxTabs = new JTabbedPane();

    // Tab 0: Action Groups + APIs inside Group
    private final GroupTableModel.ActionGroupsModel groupsModel;
    private final JTable tblGroups;
    private final JComboBox<String> cbGroupFilter = new JComboBox<>(
        new String[]{"Có API (>0)", "Tất cả nhóm", "Có NOTE (!)"});
    private final JTextField txtGroupSearch = new JTextField(14);

    private final GroupTableModel.GroupApisModel groupApisModel;
    private final JTable tblGroupApis;
    private final JLabel lblGroupHeader = new JLabel("Tất cả API đã nhóm");
    private final JCheckBox chkAllApisWhenUnselected = new JCheckBox("Hiện tất cả khi chưa chọn nhóm", true);
    private final JCheckBox chkOnlyApiNotes = new JCheckBox("Chỉ hiện NOTE", false);
    private final JTextField txtApiSearch = new JTextField(16);

    // Tab 1: Consolidated Audit NOTEs Table
    private final GroupTableModel.AuditNotesTableModel notesModel;
    private final JTable tblNotes;
    private final JComboBox<String> cbNoteSeverity = new JComboBox<>(
        new String[]{"Tất cả (ALL)", "Lỗi (ERROR)", "Cảnh báo (WARN)", "Thông tin (INFO)"});
    private final JTextField txtNoteSearch = new JTextField(20);

    // Tab 2: Excel Checklist Coverage
    private final CoverageTableModel coverageModel = new CoverageTableModel();
    private final JTable tblCoverage = new JTable(coverageModel);
    private final JComboBox<String> cbStatusFilter = new JComboBox<>(
        new String[]{"Tất cả (ALL)", "Đã lấy (MATCHED)", "Còn thiếu (MISSING)", "Có NOTE (!)"});
    private final JTextField txtSearch = new JTextField(20);

    // Tab 3: Hierarchy Tree
    private final DefaultMutableTreeNode treeRoot;
    private final DefaultTreeModel treeModel;
    private final JTree actionTree;

    // Tab 4: Form Auto-Fill & Config
    private FormAndConfigPanel formConfigPanel;

    // Tab 5: Skipped, JS Hints, Extras, Log
    private final CoverageTableModel.ExtraCaptureTableModel extraModel =
        new CoverageTableModel.ExtraCaptureTableModel();
    private final JTable tblExtras = new JTable(extraModel);
    private final CoverageTableModel.SkippedActionTableModel skippedModel =
        new CoverageTableModel.SkippedActionTableModel();
    private final JTable tblSkipped = new JTable(skippedModel);
    private final DefaultListModel<String> jsListModel = new DefaultListModel<>();
    private final JList<String> lstJsEndpoints = new JList<>(jsListModel);
    private final JTextArea txtLog = new JTextArea();

    // Bottom Inspector
    private final JLabel lblSelectedSummary = new JLabel(
        "Chọn một API, NOTE hoặc lá cây để xem chi tiết Request / Response và Tên Nhóm");
    private final JComboBox<VariantItem> cbVariant = new JComboBox<>();
    private final JButton btnShowShared = new JButton("Nhóm dùng chung");
    private final JButton btnSendRepeater = new JButton("Send to Repeater");
    private final JTextArea txtNotesBanner = new JTextArea(2, 40);
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;

    private boolean updatingVariants = false;
    private boolean syncingQuickControls = false;
    private long lastStoreVersion = -1L;
    private int lastNoteCount = -1;
    private int lastExcelMatched = -1;
    private int lastTreeVersion = -1;
    private String currentInspectEndpointKey = "";

    public CrawlerTabPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExcelSpecMatcher initialMatcher
    ) {
        this(api, config, state, injector, initialMatcher, null);
    }

    public CrawlerTabPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExcelSpecMatcher initialMatcher,
        Runnable onPersistConfig
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.injector = injector;
        this.onPersistConfig = onPersistConfig;
        this.matcher = initialMatcher != null ? initialMatcher : ExcelSpecMatcher.empty();

        ActionGroupStore store = state.getActionGroups();
        this.groupsModel = new GroupTableModel.ActionGroupsModel(store);
        this.tblGroups = new JTable(groupsModel);
        this.groupApisModel = new GroupTableModel.GroupApisModel(store);
        this.tblGroupApis = new JTable(groupApisModel);
        this.notesModel = new GroupTableModel.AuditNotesTableModel(store);
        this.tblNotes = new JTable(notesModel);

        this.treeRoot = new DefaultMutableTreeNode(config.getRouteRootLabel());
        this.treeModel = new DefaultTreeModel(treeRoot);
        this.actionTree = new JTree(treeModel);

        this.requestEditor = api != null
            ? api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY) : null;
        this.responseEditor = api != null
            ? api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY) : null;

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        syncQuickCheckboxesFromConfig();
        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(buildMainSplitPane(), BorderLayout.CENTER);

        this.refreshTimer = new Timer(400, e -> {
            if (refreshPending.getAndSet(false)
                || state.getStatus() == CrawlState.Status.RUNNING
                || state.getActionGroups().version() != lastStoreVersion) {
                refreshAllViewsNow();
            }
        });
        this.refreshTimer.start();

        refreshButtonsState();
        refreshAllViewsNow();
        appendLog("theMythOfCrawl Audit Crawler sẵn sàng. Mặc định điền form: \""
            + config.getFormFillProfile().get(FormFillProfile.Field.TEXT)
            + "\" | Gốc cây: \"" + config.getRouteRootLabel() + "\".");
    }

    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    public void scheduleUiRefresh() {
        refreshPending.set(true);
    }

    public void appendLog(String msg) {
        if (msg == null) {
            return;
        }
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        Runnable r = () -> {
            if (txtLog.getLineCount() > 1500) {
                try {
                    int end = txtLog.getLineEndOffset(400);
                    txtLog.replaceRange("", 0, end);
                } catch (Exception ignored) {
                }
            }
            txtLog.append("[" + ts + "] " + msg + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void syncQuickCheckboxesFromConfig() {
        syncingQuickControls = true;
        try {
            chkPassive.setSelected(injector != null && injector.isPassiveCapture());
            chkStrictQuick.setSelected(config.isStrictExcelOnly());
            if (formConfigPanel != null) {
                formConfigPanel.syncFromConfig();
            }
            refreshFormSummaryLabel();
        } finally {
            syncingQuickControls = false;
        }
    }

    private void persistConfigQuietly() {
        if (onPersistConfig != null) {
            try {
                onPersistConfig.run();
            } catch (Exception ex) {
                appendLog("Không lưu được cấu hình vào Burp Preferences: " + ex.getMessage());
            }
        }
    }

    private void refreshFormSummaryLabel() {
        boolean dark = AuditTheme.isDark(api, this);
        String txtVal = config.getFormFillProfile().get(FormFillProfile.Field.TEXT);
        lblFormSummary.setText("Form Text: \"" + txtVal + "\" | Gốc: " + config.getRouteRootLabel());
        lblFormSummary.setForeground(AuditTheme.accentForeground(dark));
    }

    // =========================================================================
    // Header / Toolbar
    // =========================================================================

    private JPanel buildHeaderPanel() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel container = new JPanel(new BorderLayout(4, 4));

        // Row 1: Primary workflow controls
        JPanel row1 = new JPanel(new GridBagLayout());
        row1.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JButton btnImport = new JButton("Import Excel (.xlsx/.csv)");
        btnImport.setToolTipText("Nạp danh sách API chuẩn gồm cột STT, Method, Path API");
        btnImport.addActionListener(e -> onImportExcel());

        lblExcelStatus.setForeground(AuditTheme.mutedForeground(dark));
        lblExcelStatus.setToolTipText("Trạng thái file Excel checklist");

        txtHosts.setToolTipText("Ví dụ: app.example.com,api.example.com (hoặc * để bắt mọi host)");
        txtHosts.setText(config.getAllowedHostsCsv());

        txtSeedUrl.setToolTipText("URL trang chủ để pre-crawl & quét JS (không bắt buộc)");

        btnStart.setFont(btnStart.getFont().deriveFont(Font.BOLD));
        btnStart.addActionListener(e -> onStartCrawl());
        btnPause.addActionListener(e -> onTogglePause());
        btnStop.addActionListener(e -> onStopCrawl());

        JButton btnReset = new JButton("Reset");
        btnReset.setToolTipText("Xóa kết quả crawl hiện tại (giữ lại danh sách Excel & cài đặt)");
        btnReset.addActionListener(e -> onResetCaptures());

        JButton btnExport = new JButton("Xuất báo cáo ▼");
        btnExport.addActionListener(e -> showExportMenu(btnExport));

        int x = 0;
        gbc.gridx = x++; gbc.gridy = 0; gbc.weightx = 0; row1.add(btnImport, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(lblExcelStatus, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(new JLabel("Host(s):"), gbc);
        gbc.gridx = x++; gbc.weightx = 0.45; row1.add(txtHosts, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(new JLabel("Seed URL:"), gbc);
        gbc.gridx = x++; gbc.weightx = 0.55; row1.add(txtSeedUrl, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnStart, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnPause, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnStop, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnReset, gbc);
        gbc.gridx = x;   gbc.weightx = 0; row1.add(btnExport, gbc);

        // Row 2: Quick toggles & Stat Chips
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        chkArmAgent.addActionListener(e -> {
            if (injector != null) {
                injector.setAgentArmed(chkArmAgent.isSelected());
                appendLog(chkArmAgent.isSelected()
                    ? "Đã BẬT bơm Agent auto-crawl vào trình duyệt."
                    : "Đã TẮT bơm Agent (chỉ nghe traffic).");
            }
        });
        chkPassive.addActionListener(e -> {
            if (!syncingQuickControls) {
                boolean passive = chkPassive.isSelected();
                if (injector != null) {
                    injector.setPassiveCapture(passive);
                }
                appendLog(passive
                    ? "Chế độ BẮT THỤ ĐỘNG: chỉ ghi nhận traffic, tạm tắt bơm Agent."
                    : "Chế độ CHỦ ĐỘNG: Agent tự duyệt toàn bộ trang & form.");
            }
        });
        chkStrictQuick.setToolTipText("Khi tắt: lưu mọi API vào Nhóm & đánh NOTE 'Ngoài Excel'. Khi bật: chỉ giữ API khớp Excel.");
        chkStrictQuick.addActionListener(e -> {
            if (!syncingQuickControls) {
                config.setStrictExcelOnly(chkStrictQuick.isSelected());
                if (formConfigPanel != null) {
                    formConfigPanel.syncFromConfig();
                }
                persistConfigQuietly();
            }
        });

        JButton btnFormConfig = new JButton("Form (TEXT) & Cài đặt ↗");
        btnFormConfig.addActionListener(e -> mainTabs.setSelectedIndex(4));

        progressBar.setPreferredSize(new Dimension(110, 20));
        progressBar.setStringPainted(true);
        progressBar.setString("0%");

        row2.add(chkArmAgent);
        row2.add(chkPassive);
        row2.add(chkStrictQuick);
        row2.add(btnFormConfig);
        row2.add(Box.createHorizontalStrut(4));
        row2.add(progressBar);
        row2.add(chipGroups);
        row2.add(chipApis);
        row2.add(chipNotes);
        row2.add(chipExcel);
        row2.add(chipPages);

        // Row 3: Live status strip
        JPanel row3 = new JPanel(new BorderLayout(8, 0));
        row3.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));
        lblCurrentAction.setForeground(AuditTheme.accentForeground(dark));
        lblCurrentAction.setFont(lblCurrentAction.getFont().deriveFont(Font.BOLD));
        row3.add(lblCurrentAction, BorderLayout.CENTER);
        row3.add(lblFormSummary, BorderLayout.EAST);

        JPanel centerWrap = new JPanel(new BorderLayout(2, 2));
        centerWrap.add(row2, BorderLayout.NORTH);
        centerWrap.add(row3, BorderLayout.SOUTH);

        container.add(row1, BorderLayout.NORTH);
        container.add(centerWrap, BorderLayout.CENTER);
        return container;
    }

    // =========================================================================
    // Main Split Pane & Tabs
    // =========================================================================

    private JSplitPane buildMainSplitPane() {
        mainTabs.addTab("Nhóm & API", buildGroupsAndApisTab());
        mainTabs.addTab("Bảng NOTE (0)", buildNotesTab());
        mainTabs.addTab("Tiến độ Excel (0/0)", buildCoverageTab());
        mainTabs.addTab("Cây Phân cấp (0)", buildHierarchyTreeTab());
        mainTabs.addTab("Điền Form & Cài Đặt", buildFormAndConfigTab());
        mainTabs.addTab("Bị né, JS & Log", buildAuxTab());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainTabs, buildInspectorPanel());
        split.setResizeWeight(0.60);
        split.setOneTouchExpandable(true);
        return split;
    }

    // --- Tab 0: Action Groups + APIs inside Group ---

    private JSplitPane buildGroupsAndApisTab() {
        // Left pane: Action Groups
        JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel leftFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        leftFilter.add(new JLabel("Nhóm:"));
        cbGroupFilter.addActionListener(e -> applyGroupFilter());
        leftFilter.add(cbGroupFilter);
        leftFilter.add(new JLabel("Tìm:"));
        attachDocListener(txtGroupSearch, this::applyGroupFilter);
        leftFilter.add(txtGroupSearch);
        JButton btnClearGroup = new JButton("Bỏ chọn");
        btnClearGroup.setToolTipText("Bỏ chọn nhóm để xem toàn bộ API đã bắt");
        btnClearGroup.addActionListener(e -> {
            tblGroups.clearSelection();
            applyGroupApisFilter();
        });
        leftFilter.add(btnClearGroup);

        AuditTheme.configureAuditTable(tblGroups);
        tblGroups.setAutoCreateRowSorter(true);
        tblGroups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblGroups.setDefaultRenderer(Object.class, new GroupTableModel.GroupCellRenderer(api));
        tblGroups.setDefaultRenderer(Integer.class, new GroupTableModel.GroupCellRenderer(api));
        setColumnWidths(tblGroups.getColumnModel(), new int[]{38, 280, 56, 56, 56, 46});

        tblGroups.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyGroupApisFilter();
            }
        });
        installGroupPopupMenu();

        leftPanel.add(leftFilter, BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(tblGroups), BorderLayout.CENTER);

        // Right pane: APIs inside selected group (or all grouped APIs)
        JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel rightHeader = new JPanel(new BorderLayout(4, 2));
        lblGroupHeader.setFont(lblGroupHeader.getFont().deriveFont(Font.BOLD));
        lblGroupHeader.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JPanel rightFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chkAllApisWhenUnselected.addActionListener(e -> applyGroupApisFilter());
        chkOnlyApiNotes.addActionListener(e -> applyGroupApisFilter());
        attachDocListener(txtApiSearch, this::applyGroupApisFilter);
        rightFilter.add(chkAllApisWhenUnselected);
        rightFilter.add(chkOnlyApiNotes);
        rightFilter.add(new JLabel("Tìm API:"));
        rightFilter.add(txtApiSearch);

        rightHeader.add(lblGroupHeader, BorderLayout.NORTH);
        rightHeader.add(rightFilter, BorderLayout.SOUTH);

        AuditTheme.configureAuditTable(tblGroupApis);
        tblGroupApis.setAutoCreateRowSorter(true);
        tblGroupApis.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblGroupApis.setDefaultRenderer(Object.class, new GroupTableModel.GroupApiCellRenderer(api));
        tblGroupApis.setDefaultRenderer(Integer.class, new GroupTableModel.GroupApiCellRenderer(api));
        setColumnWidths(tblGroupApis.getColumnModel(), new int[]{38, 230, 68, 240, 54, 150, 44, 68, 220});

        tblGroupApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ApiCall call = getSelectedGroupApi();
                if (call != null) {
                    showApiCallInInspector(call);
                }
            }
        });
        installGroupApiPopupMenu();

        rightPanel.add(rightHeader, BorderLayout.NORTH);
        rightPanel.add(new JScrollPane(tblGroupApis), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.38);
        split.setOneTouchExpandable(true);
        return split;
    }

    private void installGroupPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miSendAll = new JMenuItem("Send CẢ NHÓM API sang Repeater");
        JMenuItem miCopyPath = new JMenuItem("Copy Tên Nhóm (Action Path)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Page URL");

        miSendAll.addActionListener(e -> {
            ActionGroup g = getSelectedGroup();
            if (g != null) {
                sendGroupToRepeater(g);
            }
        });
        miCopyPath.addActionListener(e -> {
            ActionGroup g = getSelectedGroup();
            if (g != null) {
                copyToClipboard(g.actionPath);
            }
        });
        miCopyUrl.addActionListener(e -> {
            ActionGroup g = getSelectedGroup();
            if (g != null) {
                copyToClipboard(g.pageUrl);
            }
        });

        popup.add(miSendAll);
        popup.addSeparator();
        popup.add(miCopyPath);
        popup.add(miCopyUrl);
        attachTablePopup(tblGroups, popup);
    }

    private void installGroupApiPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miSend = new JMenuItem("Send to Repeater");
        JMenuItem miSendGroup = new JMenuItem("Send CẢ NHÓM chứa API này sang Repeater");
        JMenuItem miShared = new JMenuItem("Xem các nhóm cùng gọi API này");
        JMenuItem miCopyName = new JMenuItem("Copy Tên API (Tên Nhóm)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");

        miSend.addActionListener(e -> {
            ApiCall c = getSelectedGroupApi();
            if (c != null) {
                String tag = c.inExcel ? ("STT " + c.excelStt) : ("G" + c.groupId + "#" + c.index);
                sendToRepeater(c.capture, tag + " " + c.capture.method);
            }
        });
        miSendGroup.addActionListener(e -> {
            ApiCall c = getSelectedGroupApi();
            if (c != null) {
                ActionGroup g = state.getActionGroups().getGroup(c.groupId);
                if (g != null) {
                    sendGroupToRepeater(g);
                }
            }
        });
        miShared.addActionListener(e -> {
            ApiCall c = getSelectedGroupApi();
            if (c != null) {
                showSharedGroupsDialog(c.endpointKey);
            }
        });
        miCopyName.addActionListener(e -> {
            ApiCall c = getSelectedGroupApi();
            if (c != null) {
                copyToClipboard(c.displayName());
            }
        });
        miCopyUrl.addActionListener(e -> {
            ApiCall c = getSelectedGroupApi();
            if (c != null) {
                copyToClipboard(c.capture.fullUrl);
            }
        });

        popup.add(miSend);
        popup.add(miSendGroup);
        popup.add(miShared);
        popup.addSeparator();
        popup.add(miCopyName);
        popup.add(miCopyUrl);
        attachTablePopup(tblGroupApis, popup);
    }

    // --- Tab 1: Consolidated Audit NOTEs Table ---

    private JPanel buildNotesTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("Mức độ NOTE:"));
        cbNoteSeverity.addActionListener(e -> applyNotesFilter());
        bar.add(cbNoteSeverity);
        bar.add(new JLabel("Tìm NOTE / Endpoint / Nhóm:"));
        attachDocListener(txtNoteSearch, this::applyNotesFilter);
        bar.add(txtNoteSearch);

        AuditTheme.configureAuditTable(tblNotes);
        tblNotes.setAutoCreateRowSorter(true);
        tblNotes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblNotes.setDefaultRenderer(Object.class, new GroupTableModel.AuditNoteCellRenderer(api));
        setColumnWidths(tblNotes.getColumnModel(), new int[]{82, 130, 260, 74, 68, 220, 320});

        tblNotes.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                AuditNote note = getSelectedNote();
                if (note != null) {
                    ApiCall call = findApiCall(note.groupId, note.callIndex);
                    if (call != null) {
                        showApiCallInInspector(call);
                    }
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miJump = new JMenuItem("Đi tới Nhóm này (Tab Nhóm & API)");
        JMenuItem miRepeater = new JMenuItem("Send to Repeater");
        JMenuItem miCopy = new JMenuItem("Copy Chi tiết NOTE");

        miJump.addActionListener(e -> {
            AuditNote n = getSelectedNote();
            if (n != null) {
                jumpToGroupAndCall(n.groupId, n.callIndex);
            }
        });
        miRepeater.addActionListener(e -> {
            AuditNote n = getSelectedNote();
            if (n != null) {
                ApiCall call = findApiCall(n.groupId, n.callIndex);
                if (call != null) {
                    sendToRepeater(call.capture, "NOTE G" + n.groupId + "#" + n.callIndex + " " + call.capture.method);
                }
            }
        });
        miCopy.addActionListener(e -> {
            AuditNote n = getSelectedNote();
            if (n != null) {
                copyToClipboard("[" + n.severity.label + " - " + n.type.label + "] "
                    + n.groupPath + " | " + n.method + " " + n.endpoint + " -> " + n.message);
            }
        });

        popup.add(miJump);
        popup.add(miRepeater);
        popup.add(miCopy);
        attachTablePopup(tblNotes, popup);

        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(tblNotes), BorderLayout.CENTER);
        return p;
    }

    // --- Tab 2: Excel Checklist Coverage ---

    private JPanel buildCoverageTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterBar.add(new JLabel("Lọc trạng thái:"));
        cbStatusFilter.addActionListener(e -> applyCoverageFilter());
        filterBar.add(cbStatusFilter);
        filterBar.add(new JLabel("Tìm kiếm:"));
        attachDocListener(txtSearch, this::applyCoverageFilter);
        filterBar.add(txtSearch);

        AuditTheme.configureAuditTable(tblCoverage);
        tblCoverage.setAutoCreateRowSorter(true);
        tblCoverage.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblCoverage.setDefaultRenderer(Object.class, new CoverageTableModel.CoverageCellRenderer(api));
        setColumnWidths(tblCoverage.getColumnModel(), new int[]{56, 108, 72, 240, 260, 82, 54, 240});

        tblCoverage.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ApiSpecEntry entry = getSelectedSpecEntry();
                if (entry != null) {
                    showSpecEntryInInspector(entry);
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miRepeater = new JMenuItem("Send to Repeater");
        JMenuItem miCopyAction = new JMenuItem("Copy Action Path");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");
        miRepeater.addActionListener(e -> {
            ApiSpecEntry entry = getSelectedSpecEntry();
            CapturedApiEntry cap = getSelectedOrPrimaryCapture(entry);
            if (cap != null) {
                sendToRepeater(cap, "STT " + entry.stt + " " + cap.method);
            }
        });
        miCopyAction.addActionListener(e -> {
            ApiSpecEntry entry = getSelectedSpecEntry();
            CapturedApiEntry cap = getSelectedOrPrimaryCapture(entry);
            if (cap != null) {
                copyToClipboard(cap.actionPath);
            }
        });
        miCopyUrl.addActionListener(e -> {
            ApiSpecEntry entry = getSelectedSpecEntry();
            CapturedApiEntry cap = getSelectedOrPrimaryCapture(entry);
            if (cap != null) {
                copyToClipboard(cap.fullUrl);
            }
        });
        popup.add(miRepeater);
        popup.add(miCopyAction);
        popup.add(miCopyUrl);
        attachTablePopup(tblCoverage, popup);

        p.add(filterBar, BorderLayout.NORTH);
        p.add(new JScrollPane(tblCoverage), BorderLayout.CENTER);
        return p;
    }

    // --- Tab 3: Hierarchy Tree ---

    private JPanel buildHierarchyTreeTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnExpand = new JButton("Mở rộng tất cả");
        JButton btnCollapse = new JButton("Thu gọn tất cả");
        JButton btnRefreshTree = new JButton("Làm mới cây");
        btnExpand.addActionListener(e -> expandAllTreeNodes(true));
        btnCollapse.addActionListener(e -> expandAllTreeNodes(false));
        btnRefreshTree.addActionListener(e -> rebuildHierarchyTree());
        bar.add(new JLabel("Cây hành động & nhóm API (root > trang > nút -> API):"));
        bar.add(btnExpand);
        bar.add(btnCollapse);
        bar.add(btnRefreshTree);

        actionTree.setCellRenderer(new AuditTreeRenderer());
        actionTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                showApiCallInInspector(tcn.call);
            }
        });

        JPopupMenu treePopup = new JPopupMenu();
        JMenuItem miTreeRep = new JMenuItem("Send to Repeater");
        JMenuItem miTreeRepGroup = new JMenuItem("Send CẢ NHÓM chứa API này sang Repeater");
        miTreeRep.addActionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                sendToRepeater(tcn.call.capture,
                    "G" + tcn.call.groupId + "#" + tcn.call.index + " " + tcn.call.capture.method);
            }
        });
        miTreeRepGroup.addActionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                ActionGroup g = state.getActionGroups().getGroup(tcn.call.groupId);
                if (g != null) {
                    sendGroupToRepeater(g);
                }
            }
        });
        treePopup.add(miTreeRep);
        treePopup.add(miTreeRepGroup);
        actionTree.setComponentPopupMenu(treePopup);

        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(actionTree), BorderLayout.CENTER);
        return p;
    }

    // --- Tab 4: Configurable Form Auto-Fill ("TEXT") & Crawl Config ---

    private JPanel buildFormAndConfigTab() {
        this.formConfigPanel = new FormAndConfigPanel(
            config,
            this::appendLog,
            () -> {
                syncQuickCheckboxesFromConfig();
                persistConfigQuietly();
                lastTreeVersion = -1;
                refreshAllViewsNow();
            });
        return formConfigPanel;
    }

    // --- Tab 5: Aux (Log, Extra Captures, Danger Guard & JS Hints) ---

    private JPanel buildAuxTab() {
        JPanel p = new JPanel(new BorderLayout());

        // Sub-tab 0: Activity Log
        JPanel logPanel = new JPanel(new BorderLayout(4, 4));
        txtLog.setEditable(false);
        txtLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton btnClearLog = new JButton("Xóa Log");
        btnClearLog.addActionListener(e -> txtLog.setText(""));
        logTop.add(btnClearLog);
        logPanel.add(logTop, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        // Sub-tab 1: Extras (outside Excel)
        AuditTheme.configureAuditTable(tblExtras);
        tblExtras.setAutoCreateRowSorter(true);
        tblExtras.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblExtras.setDefaultRenderer(Object.class, new CoverageTableModel.SimpleAuditCellRenderer(api, 0, 4, 3));
        tblExtras.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblExtras.getSelectedRow();
                if (vr >= 0) {
                    CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(vr));
                    if (cap != null) {
                        showStandaloneCaptureInInspector(cap);
                    }
                }
            }
        });
        JPopupMenu extraPopup = new JPopupMenu();
        JMenuItem miExtraRep = new JMenuItem("Send to Repeater");
        JMenuItem miExtraCopy = new JMenuItem("Copy Full URL");
        miExtraRep.addActionListener(e -> {
            int vr = tblExtras.getSelectedRow();
            if (vr >= 0) {
                CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(vr));
                if (cap != null) {
                    sendToRepeater(cap, cap.method);
                }
            }
        });
        miExtraCopy.addActionListener(e -> {
            int vr = tblExtras.getSelectedRow();
            if (vr >= 0) {
                CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(vr));
                if (cap != null) {
                    copyToClipboard(cap.fullUrl);
                }
            }
        });
        extraPopup.add(miExtraRep);
        extraPopup.add(miExtraCopy);
        attachTablePopup(tblExtras, extraPopup);

        // Sub-tab 2: Skipped Danger Actions & JS Hints
        AuditTheme.configureAuditTable(tblSkipped);
        tblSkipped.setAutoCreateRowSorter(true);
        tblSkipped.setDefaultRenderer(Object.class, new CoverageTableModel.SimpleAuditCellRenderer(api, -1, -1, 2));
        JPanel skippedWrap = new JPanel(new BorderLayout(4, 4));
        skippedWrap.setBorder(BorderFactory.createTitledBorder("Nút Danger Guard đã tự né"));
        skippedWrap.add(new JScrollPane(tblSkipped), BorderLayout.CENTER);

        JPanel jsWrap = new JPanel(new BorderLayout(4, 4));
        jsWrap.setBorder(BorderFactory.createTitledBorder("Endpoint phát hiện thụ động từ file .js"));
        JPopupMenu jsPopup = new JPopupMenu();
        JMenuItem miCopyJs = new JMenuItem("Copy Endpoint JS");
        miCopyJs.addActionListener(e -> {
            String sel = lstJsEndpoints.getSelectedValue();
            if (sel != null) {
                copyToClipboard(sel);
            }
        });
        jsPopup.add(miCopyJs);
        lstJsEndpoints.setComponentPopupMenu(jsPopup);
        jsWrap.add(new JScrollPane(lstJsEndpoints), BorderLayout.CENTER);

        JSplitPane dangerJsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, skippedWrap, jsWrap);
        dangerJsSplit.setResizeWeight(0.5);

        auxTabs.addTab("Log hoạt động", logPanel);
        auxTabs.addTab("Ngoài Excel (0)", new JScrollPane(tblExtras));
        auxTabs.addTab("Nút bị né & JS Hints (0/0)", dangerJsSplit);
        p.add(auxTabs, BorderLayout.CENTER);
        return p;
    }

    // =========================================================================
    // Bottom Inspector Panel
    // =========================================================================

    private JPanel buildInspectorPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Chi tiết API, NOTE & HTTP Message"));

        JPanel top = new JPanel(new BorderLayout(8, 2));
        lblSelectedSummary.setFont(lblSelectedSummary.getFont().deriveFont(Font.BOLD));
        top.add(lblSelectedSummary, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(new JLabel("Phiên bản:"));
        cbVariant.setPreferredSize(new Dimension(250, 24));
        cbVariant.addActionListener(e -> {
            if (!updatingVariants) {
                VariantItem item = (VariantItem) cbVariant.getSelectedItem();
                if (item != null && item.capture != null) {
                    renderHttpMessages(item.specEntry, item.call, item.capture);
                }
            }
        });

        btnShowShared.setEnabled(false);
        btnShowShared.addActionListener(e -> {
            if (!currentInspectEndpointKey.isEmpty()) {
                showSharedGroupsDialog(currentInspectEndpointKey);
            }
        });

        btnSendRepeater.setEnabled(false);
        btnSendRepeater.addActionListener(e -> {
            VariantItem item = (VariantItem) cbVariant.getSelectedItem();
            if (item != null && item.capture != null) {
                String title = item.call != null
                    ? (item.call.inExcel ? ("STT " + item.call.excelStt + " " + item.capture.method)
                                         : ("G" + item.call.groupId + "#" + item.call.index + " " + item.capture.method))
                    : (item.specEntry != null ? ("STT " + item.specEntry.stt + " " + item.capture.method) : item.capture.method);
                sendToRepeater(item.capture, title);
            }
        });

        right.add(cbVariant);
        right.add(btnShowShared);
        right.add(btnSendRepeater);
        top.add(right, BorderLayout.EAST);

        txtNotesBanner.setEditable(false);
        txtNotesBanner.setLineWrap(true);
        txtNotesBanner.setWrapStyleWord(true);
        txtNotesBanner.setOpaque(true);
        txtNotesBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel northWrap = new JPanel(new BorderLayout(2, 2));
        northWrap.add(top, BorderLayout.NORTH);
        northWrap.add(txtNotesBanner, BorderLayout.SOUTH);
        p.add(northWrap, BorderLayout.NORTH);

        if (requestEditor != null && responseEditor != null) {
            JSplitPane msgSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, requestEditor.uiComponent(), responseEditor.uiComponent());
            msgSplit.setResizeWeight(0.5);
            p.add(msgSplit, BorderLayout.CENTER);
        } else {
            p.add(new JLabel("Montoya HTTP Editor chỉ hiển thị khi chạy trong Burp Suite."), BorderLayout.CENTER);
        }
        return p;
    }

    // =========================================================================
    // Workflow Handlers (Import, Crawl Lifecycle, Export)
    // =========================================================================

    private void onImportExcel() {
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setDialogTitle("Chọn file Excel (.xlsx) hoặc CSV danh sách API");
        fc.setFileFilter(new FileNameExtensionFilter(
            "Excel / CSV (*.xlsx, *.xlsm, *.csv, *.tsv)", "xlsx", "xlsm", "csv", "tsv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = fc.getSelectedFile();
        lastDirectory = file.getParentFile();

        try {
            SimpleXlsxReader.WorkbookData wb = SimpleXlsxReader.readFile(file);
            if (wb.getSheetNames().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Không tìm thấy Sheet dữ liệu nào trong file.",
                    "File rỗng", JOptionPane.WARNING_MESSAGE);
                return;
            }
            ExcelColumnMapperDialog.MappingResult mapping =
                ExcelColumnMapperDialog.showDialog(this, file.getName(), wb);
            if (mapping == null) {
                return;
            }

            List<List<String>> rows = wb.getRows(mapping.sheetName);
            ExcelSpecMatcher newMatcher = ExcelSpecMatcher.buildFromRows(
                file.getName(),
                rows,
                mapping.headerRowIndex,
                mapping.sttCol,
                mapping.methodCol,
                mapping.pathCol,
                config.isIgnoreTrailingSlash());

            if (!newMatcher.isLoaded()) {
                JOptionPane.showMessageDialog(this,
                    "Không đọc được dòng API hợp lệ nào từ cột đã chọn. Kiểm tra lại dòng tiêu đề & cột Path API.",
                    "Không có API", JOptionPane.WARNING_MESSAGE);
                return;
            }

            this.matcher = newMatcher;
            if (injector != null) {
                injector.setMatcher(newMatcher);
            }
            coverageModel.setMatcher(newMatcher);
            boolean dark = AuditTheme.isDark(api, this);
            lblExcelStatus.setText(file.getName() + " (" + newMatcher.getEntries().size() + " APIs)");
            lblExcelStatus.setForeground(AuditTheme.okForeground(dark));
            appendLog("Đã nạp " + newMatcher.getEntries().size() + " API từ '" + file.getName()
                + "' [Sheet: " + mapping.sheetName + "].");
            lastExcelMatched = -1;
            refreshAllViewsNow();
        } catch (Exception ex) {
            appendLog("Lỗi đọc Excel: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Lỗi đọc file: " + ex.getMessage(),
                "Lỗi Import", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onStartCrawl() {
        String hostsInput = txtHosts.getText().trim();
        String seedInput = txtSeedUrl.getText().trim();

        if (hostsInput.isEmpty() && !seedInput.isEmpty()) {
            try {
                java.net.URI u = java.net.URI.create(seedInput);
                if (u.getHost() != null) {
                    hostsInput = u.getHost();
                    txtHosts.setText(hostsInput);
                }
            } catch (Exception ignored) {
            }
        }

        config.setAllowedHostsCsv(hostsInput);
        if (config.getAllowedHostsSet().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Vui lòng nhập Target Host (ví dụ: target.example.com hoặc *) hoặc Seed URL đầy đủ.",
                "Thiếu Target Host", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (config.isStrictExcelOnly() && !matcher.isLoaded()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "Bạn chưa Import file Excel API. Tiếp tục ở chế độ bắt & nhóm toàn bộ API?",
                "Chưa có file Excel", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
            config.setStrictExcelOnly(false);
            syncQuickCheckboxesFromConfig();
        }

        if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            appendLog("Tiếp tục Crawl...");
            refreshButtonsState();
            return;
        }

        String seed = seedInput;
        if (!seed.isEmpty() && !seed.startsWith("http://") && !seed.startsWith("https://")) {
            seed = "https://" + seed;
            txtSeedUrl.setText(seed);
        }

        state.resetAll(seed);
        chkArmAgent.setSelected(true);
        if (injector != null) {
            injector.setAgentArmed(true);
        }
        state.setStatus(CrawlState.Status.RUNNING);
        lastStoreVersion = -1L;
        lastNoteCount = -1;
        lastTreeVersion = -1;
        refreshButtonsState();

        if (!seed.isEmpty() && api != null) {
            SeedDiscoveryHelper.fetchSeedsAsync(api, seed, config, state, this::appendLog);
        }

        mainTabs.setSelectedIndex(0);
        appendLog("Crawl RUNNING trên Host(s): " + config.getAllowedHostsCsv()
            + " | Mở Firefox/Chrome qua Burp Proxy (hoặc bấm Refresh trang đang mở).");
        refreshAllViewsNow();
    }

    private void onTogglePause() {
        if (state.getStatus() == CrawlState.Status.RUNNING) {
            state.setStatus(CrawlState.Status.PAUSED);
            appendLog("Đã tạm dừng Crawl (có thể tương tác tay trên trình duyệt rồi bấm Tiếp tục).");
        } else if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            appendLog("Đã tiếp tục Crawl.");
        }
        refreshButtonsState();
    }

    private void onStopCrawl() {
        state.setStatus(CrawlState.Status.STOPPED);
        appendLog("Đã DỪNG Crawl.");
        refreshButtonsState();
        refreshAllViewsNow();
    }

    private void onResetCaptures() {
        state.resetAll("");
        state.setStatus(CrawlState.Status.IDLE);
        matcher.clearRuntimeCaptures();
        lastStoreVersion = -1L;
        lastNoteCount = -1;
        lastExcelMatched = -1;
        lastTreeVersion = -1;
        extraModel.setRows(new ArrayList<>());
        skippedModel.setRows(new ArrayList<>());
        jsListModel.clear();
        clearInspector();
        appendLog("Đã Reset kết quả crawl (giữ nguyên danh sách API Excel & cấu hình Form).");
        refreshButtonsState();
        refreshAllViewsNow();
    }

    private void showExportMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miCsv = new JMenuItem("Xuất CSV (Nhóm + NOTE + Excel UTF-8)...");
        JMenuItem miJson = new JMenuItem("Xuất JSON (Nhóm + NOTE + Excel)...");
        JMenuItem miMd = new JMenuItem("Xuất Markdown (Cây Nhóm + Bảng NOTE)...");

        miCsv.addActionListener(e -> exportReport("csv"));
        miJson.addActionListener(e -> exportReport("json"));
        miMd.addActionListener(e -> exportReport("md"));

        menu.add(miCsv);
        menu.add(miJson);
        menu.add(miMd);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void exportReport(String fmt) {
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setSelectedFile(new File("mythofcrawl_report." + fmt));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        lastDirectory = out.getParentFile();
        try {
            List<CapturedApiEntry> extras = state.getExtraCaptures();
            ActionGroupStore store = state.getActionGroups();
            if ("csv".equals(fmt)) {
                ReportExporter.exportCsv(out, matcher, extras, store);
            } else if ("json".equals(fmt)) {
                ReportExporter.exportJson(out, matcher, extras, store);
            } else {
                ReportExporter.exportMarkdown(out, matcher, extras, store);
            }
            appendLog("Đã xuất báo cáo: " + out.getAbsolutePath());
            int open = JOptionPane.showConfirmDialog(this,
                "Đã lưu báo cáo tại:\n" + out.getAbsolutePath() + "\n\nBạn có muốn mở file ngay?",
                "Xuất báo cáo thành công", JOptionPane.YES_NO_OPTION);
            if (open == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(out);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi xuất báo cáo: " + ex.getMessage(),
                "Lỗi Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Refresh, Filter & Selection Synchronization
    // =========================================================================

    private void refreshButtonsState() {
        CrawlState.Status st = state.getStatus();
        btnStart.setEnabled(st == CrawlState.Status.IDLE || st == CrawlState.Status.STOPPED || st == CrawlState.Status.PAUSED);
        btnStart.setText(st == CrawlState.Status.PAUSED ? "Tiếp tục" : "Bắt đầu Crawl");
        btnPause.setEnabled(st == CrawlState.Status.RUNNING || st == CrawlState.Status.PAUSED);
        btnPause.setText(st == CrawlState.Status.PAUSED ? "Đang Tạm dừng" : "Tạm dừng");
        btnStop.setEnabled(st == CrawlState.Status.RUNNING || st == CrawlState.Status.PAUSED);
    }

    private void refreshAllViewsNow() {
        refreshButtonsState();
        refreshFormSummaryLabel();
        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = getBackground();

        ActionGroupStore store = state.getActionGroups();
        ExcelSpecMatcher.CoverageStats stats = matcher.getCoverageStats();
        int pct = stats.total > 0 ? (int) Math.round(stats.matched * 100.0 / stats.total) : 0;
        progressBar.setValue(pct);
        progressBar.setString(stats.total > 0 ? (pct + "%") : (store.totalCalls() + " API"));

        int errNotes = store.noteCount(AuditNote.Severity.ERROR);
        int warnNotes = store.noteCount(AuditNote.Severity.WARN);
        int infoNotes = store.noteCount(AuditNote.Severity.INFO);
        int totalNotes = errNotes + warnNotes + infoNotes;

        // Calm semantic stat chips
        chipGroups.setText("Nhóm: " + store.groupCount());
        AuditTheme.styleStatChip(chipGroups, AuditTheme.accentForeground(dark), baseBg);

        chipApis.setText("API: " + store.totalCalls());
        AuditTheme.styleStatChip(chipApis,
            store.totalCalls() > 0 ? AuditTheme.okForeground(dark) : AuditTheme.mutedForeground(dark), baseBg);

        chipNotes.setText(String.format("NOTE: %d (%dL/%dCB)", totalNotes, errNotes, warnNotes));
        if (errNotes > 0) {
            AuditTheme.styleStatChip(chipNotes, AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark),
                AuditTheme.severityRowTint(AuditNote.Severity.ERROR, baseBg, dark));
        } else if (warnNotes > 0) {
            AuditTheme.styleStatChip(chipNotes, AuditTheme.severityForeground(AuditNote.Severity.WARN, dark),
                AuditTheme.severityRowTint(AuditNote.Severity.WARN, baseBg, dark));
        } else {
            AuditTheme.styleStatChip(chipNotes, AuditTheme.mutedForeground(dark), baseBg);
        }

        chipExcel.setText(String.format("Excel: %d/%d", stats.matched, stats.total));
        AuditTheme.styleStatChip(chipExcel,
            (stats.total > 0 && stats.matched == stats.total) ? AuditTheme.okForeground(dark) : getForeground(),
            baseBg);

        chipPages.setText(String.format("Trang: %d | Q: %d",
            state.getVisitedPagesCount(), state.getPendingQueueSize()));
        AuditTheme.styleStatChip(chipPages, AuditTheme.mutedForeground(dark), baseBg);

        CrawlState.Status st = state.getStatus();
        lblCurrentAction.setText("[" + st.name() + "] " + state.getCurrentActionPath()
            + (state.getCurrentPageUrl().isEmpty() ? "" : ("  (" + state.getCurrentPageUrl() + ")")));

        List<CapturedApiEntry> extras = state.getExtraCaptures();
        List<CrawlState.SkippedAction> skipped = state.getSkippedDangerActions();
        List<String> jsItems = state.getDiscoveredJsEndpoints();

        mainTabs.setTitleAt(1, String.format("Bảng NOTE (%d)", totalNotes));
        mainTabs.setTitleAt(2, String.format("Tiến độ Excel (%d/%d)", stats.matched, stats.total));
        mainTabs.setTitleAt(3, String.format("Cây Phân cấp (%d)", store.totalCalls()));
        auxTabs.setTitleAt(1, String.format("Ngoài Excel (%d)", extras.size()));
        auxTabs.setTitleAt(2, String.format("Nút bị né & JS (%d/%d)", skipped.size(), jsItems.size()));

        long curVer = store.version();
        boolean storeChanged = curVer != lastStoreVersion || totalNotes != lastNoteCount;
        if (storeChanged) {
            lastStoreVersion = curVer;
            lastNoteCount = totalNotes;

            ActionGroup prevGroup = getSelectedGroup();
            ApiCall prevCall = getSelectedGroupApi();
            AuditNote prevNote = getSelectedNote();

            groupsModel.refresh();
            if (prevGroup != null) {
                reselectGroup(prevGroup.id);
            }
            applyGroupApisFilter();
            if (prevCall != null) {
                reselectGroupApi(prevCall);
            }
            notesModel.refresh();
            if (prevNote != null) {
                reselectNote(prevNote);
            }
            rebuildHierarchyTreeIfChanged(curVer);
        }

        if (stats.matched != lastExcelMatched || storeChanged) {
            lastExcelMatched = stats.matched;
            ApiSpecEntry prevSpec = getSelectedSpecEntry();
            coverageModel.refreshData();
            if (prevSpec != null) {
                reselectSpecEntry(prevSpec);
            }
        }

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
    }

    private void applyGroupFilter() {
        ActionGroup prev = getSelectedGroup();
        int idx = cbGroupFilter.getSelectedIndex();
        String mode = "WITH_APIS";
        if (idx == 1) {
            mode = "ALL";
        } else if (idx == 2) {
            mode = "WITH_NOTES";
        }
        groupsModel.setFilter(mode, txtGroupSearch.getText());
        if (prev != null) {
            reselectGroup(prev.id);
        }
        applyGroupApisFilter();
    }

    private void applyGroupApisFilter() {
        ActionGroup g = getSelectedGroup();
        boolean showAllWhenNull = chkAllApisWhenUnselected.isSelected();
        if (g != null) {
            lblGroupHeader.setText("Nhóm: " + g.actionPath + "  (" + g.callCount() + " API, "
                + g.noteCount() + " NOTE, " + g.getTriggerCount() + " lượt)");
        } else if (showAllWhenNull) {
            lblGroupHeader.setText("Toàn bộ API đã nhóm (" + state.getActionGroups().totalCalls() + " API)");
        } else {
            lblGroupHeader.setText("Chọn một nhóm ở bảng bên trái để xem các API bên trong");
        }
        groupApisModel.setGroupAndFilter(g, showAllWhenNull, chkOnlyApiNotes.isSelected(), txtApiSearch.getText());
    }

    private void applyNotesFilter() {
        int idx = cbNoteSeverity.getSelectedIndex();
        String sev = "ALL";
        if (idx == 1) {
            sev = "ERROR";
        } else if (idx == 2) {
            sev = "WARN";
        } else if (idx == 3) {
            sev = "INFO";
        }
        notesModel.setFilter(sev, txtNoteSearch.getText());
    }

    private void applyCoverageFilter() {
        int idx = cbStatusFilter.getSelectedIndex();
        String mode = "ALL";
        if (idx == 1) {
            mode = "MATCHED";
        } else if (idx == 2) {
            mode = "MISSING";
        } else if (idx == 3) {
            mode = "NOTES";
        }
        coverageModel.setFilter(mode, txtSearch.getText());
    }

    private void rebuildHierarchyTree() {
        lastTreeVersion = -1;
        rebuildHierarchyTreeIfChanged(state.getActionGroups().version());
    }

    private void rebuildHierarchyTreeIfChanged(long curVer) {
        if ((int) curVer == lastTreeVersion && treeRoot.getUserObject().equals(config.getRouteRootLabel())) {
            return;
        }
        lastTreeVersion = (int) curVer;

        Set<String> expandedKeys = getExpandedDescendantKeys();
        String rootLabel = config.getRouteRootLabel();
        treeRoot.setUserObject(rootLabel);
        treeRoot.removeAllChildren();

        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();
        for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
            if (g.callCount() == 0) {
                continue;
            }
            DefaultMutableTreeNode groupParent = resolveTreeFolder(folderNodes, g.actionPath, rootLabel);
            for (ApiCall call : g.getCalls()) {
                groupParent.add(new DefaultMutableTreeNode(new TreeCallNode(call)));
            }
        }
        treeModel.reload();
        restoreExpandedKeys(expandedKeys);
    }

    private DefaultMutableTreeNode resolveTreeFolder(
        Map<String, DefaultMutableTreeNode> folderNodes,
        String actionPath,
        String rootLabel
    ) {
        String[] parts = actionPath.split("\\s*>\\s*");
        DefaultMutableTreeNode parent = treeRoot;
        StringBuilder keyBuilder = new StringBuilder(rootLabel);
        int start = 0;
        if (parts.length > 0) {
            String first = parts[0].trim();
            if (first.equals("/") || first.equalsIgnoreCase(rootLabel)) {
                start = 1;
            }
        }
        for (int i = start; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) {
                continue;
            }
            keyBuilder.append(" > ").append(seg);
            String fullKey = keyBuilder.toString();
            DefaultMutableTreeNode node = folderNodes.get(fullKey);
            if (node == null) {
                node = new DefaultMutableTreeNode(seg);
                folderNodes.put(fullKey, node);
                parent.add(node);
            }
            parent = node;
        }
        return parent;
    }

    private Set<String> getExpandedDescendantKeys() {
        Set<String> keys = new HashSet<>();
        TreePath rootPath = new TreePath(treeRoot);
        Enumeration<TreePath> en = actionTree.getExpandedDescendants(rootPath);
        if (en != null) {
            while (en.hasMoreElements()) {
                keys.add(treePathKey(en.nextElement()));
            }
        }
        return keys;
    }

    private void restoreExpandedKeys(Set<String> expandedKeys) {
        if (expandedKeys.isEmpty()) {
            return;
        }
        Enumeration<TreeNode> en = treeRoot.depthFirstEnumeration();
        while (en.hasMoreElements()) {
            TreeNode tn = en.nextElement();
            if (tn instanceof DefaultMutableTreeNode && !((DefaultMutableTreeNode) tn).isLeaf()) {
                TreePath path = new TreePath(((DefaultMutableTreeNode) tn).getPath());
                if (expandedKeys.contains(treePathKey(path))) {
                    actionTree.expandPath(path);
                }
            }
        }
    }

    private static String treePathKey(TreePath tp) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : tp.getPath()) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(obj);
        }
        return sb.toString();
    }

    private void expandAllTreeNodes(boolean expand) {
        for (int i = 0; i < actionTree.getRowCount(); i++) {
            if (expand) {
                actionTree.expandRow(i);
            } else if (i > 0) {
                actionTree.collapseRow(i);
            }
        }
    }

    // =========================================================================
    // Selection Lookups & Jump Navigation
    // =========================================================================

    private ActionGroup getSelectedGroup() {
        int vr = tblGroups.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupsModel.getGroupAt(tblGroups.convertRowIndexToModel(vr));
    }

    private void reselectGroup(int groupId) {
        for (int r = 0; r < groupsModel.getRowCount(); r++) {
            ActionGroup g = groupsModel.getGroupAt(r);
            if (g != null && g.id == groupId) {
                int vr = tblGroups.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblGroups.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
    }

    private ApiCall getSelectedGroupApi() {
        int vr = tblGroupApis.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupApisModel.getCallAt(tblGroupApis.convertRowIndexToModel(vr));
    }

    private void reselectGroupApi(ApiCall target) {
        for (int r = 0; r < groupApisModel.getRowCount(); r++) {
            if (groupApisModel.getCallAt(r) == target) {
                int vr = tblGroupApis.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblGroupApis.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
    }

    private AuditNote getSelectedNote() {
        int vr = tblNotes.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return notesModel.getNoteAt(tblNotes.convertRowIndexToModel(vr));
    }

    private void reselectNote(AuditNote target) {
        for (int r = 0; r < notesModel.getRowCount(); r++) {
            if (notesModel.getNoteAt(r) == target) {
                int vr = tblNotes.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblNotes.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
    }

    private ApiSpecEntry getSelectedSpecEntry() {
        int vr = tblCoverage.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return coverageModel.getEntryAt(tblCoverage.convertRowIndexToModel(vr));
    }

    private void reselectSpecEntry(ApiSpecEntry target) {
        for (int r = 0; r < coverageModel.getRowCount(); r++) {
            if (coverageModel.getEntryAt(r) == target) {
                int vr = tblCoverage.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblCoverage.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
    }

    private ApiCall findApiCall(int groupId, int callIndex) {
        ActionGroup g = state.getActionGroups().getGroup(groupId);
        if (g == null) {
            return null;
        }
        for (ApiCall c : g.getCalls()) {
            if (c.index == callIndex) {
                return c;
            }
        }
        return null;
    }

    private void jumpToGroupAndCall(int groupId, int callIndex) {
        cbGroupFilter.setSelectedIndex(0);
        txtGroupSearch.setText("");
        txtApiSearch.setText("");
        chkOnlyApiNotes.setSelected(false);
        groupsModel.refresh();
        mainTabs.setSelectedIndex(0);
        reselectGroup(groupId);
        applyGroupApisFilter();
        ApiCall target = findApiCall(groupId, callIndex);
        if (target != null) {
            reselectGroupApi(target);
            showApiCallInInspector(target);
        }
    }

    private void showSharedGroupsDialog(String endpointKey) {
        List<String> paths = state.getActionGroups().sharedGroupPaths(endpointKey);
        if (paths.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Không có thông tin nhóm dùng chung cho endpoint: " + endpointKey,
                "Nhóm dùng chung", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(endpointKey).append("\n");
        sb.append("Được kích hoạt từ ").append(paths.size()).append(" nhóm chức năng:\n\n");
        for (int i = 0; i < paths.size(); i++) {
            sb.append(i + 1).append(". ").append(paths.get(i)).append('\n');
        }
        JTextArea ta = new JTextArea(sb.toString(), Math.min(18, paths.size() + 5), 64);
        ta.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(ta),
            "Các nhóm cùng gọi API này", JOptionPane.INFORMATION_MESSAGE);
    }

    // =========================================================================
    // Inspector Rendering
    // =========================================================================

    private CapturedApiEntry getSelectedOrPrimaryCapture(ApiSpecEntry entry) {
        if (entry == null) {
            return null;
        }
        VariantItem item = (VariantItem) cbVariant.getSelectedItem();
        if (item != null && item.specEntry == entry && item.capture != null) {
            return item.capture;
        }
        return entry.getPrimaryCapture();
    }

    private void showApiCallInInspector(ApiCall call) {
        if (call == null) {
            clearInspector();
            return;
        }
        updatingVariants = true;
        cbVariant.removeAllItems();
        cbVariant.addItem(new VariantItem(null, call, call.capture,
            "[Nhóm #" + call.groupId + " API #" + call.index + "] " + call.displayName()));
        cbVariant.setSelectedIndex(0);
        updatingVariants = false;
        renderHttpMessages(null, call, call.capture);
    }

    private void showStandaloneCaptureInInspector(CapturedApiEntry cap) {
        if (cap == null) {
            clearInspector();
            return;
        }
        updatingVariants = true;
        cbVariant.removeAllItems();
        cbVariant.addItem(new VariantItem(null, null, cap, cap.actionPath));
        cbVariant.setSelectedIndex(0);
        updatingVariants = false;
        renderHttpMessages(null, null, cap);
    }

    private void showSpecEntryInInspector(ApiSpecEntry entry) {
        updatingVariants = true;
        cbVariant.removeAllItems();
        CapturedApiEntry primary = entry.getPrimaryCapture();
        if (primary != null) {
            cbVariant.addItem(new VariantItem(entry, null, primary,
                "Bản chính (" + primary.paramCount() + " params) - " + primary.actionPath));
            int i = 1;
            for (CapturedApiEntry alt : entry.getAlternateVariants()) {
                cbVariant.addItem(new VariantItem(entry, null, alt,
                    "Biến thể #" + (i++) + " (" + alt.paramCount() + " params) - " + alt.actionPath));
            }
            cbVariant.setSelectedIndex(0);
            updatingVariants = false;
            renderHttpMessages(entry, null, primary);
        } else {
            updatingVariants = false;
            clearInspectorForMissing(entry);
        }
    }

    private void renderHttpMessages(ApiSpecEntry specEntry, ApiCall call, CapturedApiEntry cap) {
        if (cap == null) {
            clearInspector();
            return;
        }
        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = getBackground();

        String tag;
        if (call != null) {
            tag = call.inExcel
                ? ("[STT " + call.excelStt + " | G" + call.groupId + "#" + call.index + "] ")
                : ("[G" + call.groupId + "#" + call.index + "] ");
            currentInspectEndpointKey = call.endpointKey;
            int shared = state.getActionGroups().sharedGroupCount(call.endpointKey);
            btnShowShared.setEnabled(shared > 1);
            btnShowShared.setText("Nhóm dùng chung (" + shared + ")");
        } else if (specEntry != null) {
            tag = "[STT " + specEntry.stt + "] ";
            currentInspectEndpointKey = "";
            btnShowShared.setEnabled(false);
            btnShowShared.setText("Nhóm dùng chung");
        } else {
            tag = "[Ngoài Excel] ";
            currentInspectEndpointKey = "";
            btnShowShared.setEnabled(false);
            btnShowShared.setText("Nhóm dùng chung");
        }

        lblSelectedSummary.setText(tag + cap.actionPath + "  ->  " + cap.method + " " + cap.fullUrl
            + "  (HTTP " + cap.statusCode + ", " + cap.paramCount() + " params: " + cap.paramSignature() + ")");

        if (call != null && call.hasNotes()) {
            AuditNote.Severity worst = call.worstSeverity();
            txtNotesBanner.setText("NOTE (" + call.getNotes().size() + "): " + call.notesSummary());
            txtNotesBanner.setForeground(AuditTheme.severityForeground(worst, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(worst, baseBg, dark));
        } else if (specEntry != null && !specEntry.getNotesSummary().isEmpty()) {
            txtNotesBanner.setText("NOTE tham số: " + specEntry.getNotesSummary());
            txtNotesBanner.setForeground(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(AuditNote.Severity.WARN, baseBg, dark));
        } else {
            txtNotesBanner.setText("Params: " + (cap.paramCount() == 0 ? "(không có tham số)" : cap.paramSignature()));
            txtNotesBanner.setForeground(AuditTheme.mutedForeground(dark));
            txtNotesBanner.setBackground(baseBg);
        }

        btnSendRepeater.setEnabled(api != null);
        if (requestEditor != null && responseEditor != null) {
            try {
                HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
                HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(cap.requestBytes));
                requestEditor.setRequest(req);
            } catch (Exception ignored) {
            }
            try {
                HttpResponse resp = HttpResponse.httpResponse(ByteArray.byteArray(cap.responseBytes));
                responseEditor.setResponse(resp);
            } catch (Exception ignored) {
            }
        }
    }

    private void clearInspectorForMissing(ApiSpecEntry entry) {
        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = getBackground();
        currentInspectEndpointKey = "";
        btnShowShared.setEnabled(false);
        btnShowShared.setText("Nhóm dùng chung");

        lblSelectedSummary.setText("[STT " + entry.stt + " - MISSING] " + entry.displayMethod + " " + entry.rawSpecPath);
        List<String> hints = entry.getJsHints();
        if (!hints.isEmpty()) {
            txtNotesBanner.setText("Chưa kích hoạt qua UI. Gợi ý xuất hiện trong file JS: " + String.join(", ", hints));
            txtNotesBanner.setForeground(AuditTheme.severityForeground(AuditNote.Severity.INFO, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(AuditNote.Severity.INFO, baseBg, dark));
        } else {
            txtNotesBanner.setText("Chưa có request nào khớp API này."
                + (entry.extraInfo.isEmpty() ? "" : (" Thông tin thêm từ Excel: " + entry.extraInfo)));
            txtNotesBanner.setForeground(AuditTheme.mutedForeground(dark));
            txtNotesBanner.setBackground(baseBg);
        }
        btnSendRepeater.setEnabled(false);
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    private void clearInspector() {
        currentInspectEndpointKey = "";
        lblSelectedSummary.setText("Chọn một API, NOTE hoặc lá cây để xem chi tiết Request / Response và Tên Nhóm");
        txtNotesBanner.setText("");
        txtNotesBanner.setBackground(getBackground());
        updatingVariants = true;
        cbVariant.removeAllItems();
        updatingVariants = false;
        btnShowShared.setEnabled(false);
        btnShowShared.setText("Nhóm dùng chung");
        btnSendRepeater.setEnabled(false);
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    // =========================================================================
    // Helpers (Repeater, Popups, Utilities)
    // =========================================================================

    private void sendToRepeater(CapturedApiEntry cap, String caption) {
        if (api == null || cap == null) {
            return;
        }
        try {
            HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
            HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(cap.requestBytes));
            api.repeater().sendToRepeater(req, caption);
        } catch (Exception ex) {
            appendLog("Không gửi được Repeater: " + ex.getMessage());
        }
    }

    private void sendGroupToRepeater(ActionGroup g) {
        if (api == null || g == null) {
            return;
        }
        List<ApiCall> calls = g.getCalls();
        if (calls.isEmpty()) {
            return;
        }
        if (calls.size() > 25) {
            int ok = JOptionPane.showConfirmDialog(this,
                "Nhóm này chứa " + calls.size() + " API. Gửi toàn bộ sang Repeater?",
                "Xác nhận gửi cả nhóm", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }
        for (ApiCall c : calls) {
            sendToRepeater(c.capture, "G" + g.id + "#" + c.index + " " + c.capture.method);
        }
        appendLog("Đã gửi " + calls.size() + " API của nhóm [" + g.actionPath + "] sang Repeater.");
    }

    private static void attachDocListener(JTextField field, Runnable onChange) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
        });
    }

    private static void attachTablePopup(JTable table, JPopupMenu popup) {
        table.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                        popup.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
        });
    }

    private static void setColumnWidths(TableColumnModel cm, int[] widths) {
        for (int i = 0; i < widths.length && i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private static void copyToClipboard(String text) {
        if (text != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    private final class AuditTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus
        ) {
            Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (!sel && value instanceof DefaultMutableTreeNode) {
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                boolean dark = AuditTheme.isDark(api, tree);
                if (uo instanceof TreeCallNode) {
                    AuditNote.Severity sev = ((TreeCallNode) uo).call.worstSeverity();
                    if (sev != null) {
                        setForeground(AuditTheme.severityForeground(sev, dark));
                    } else if (((TreeCallNode) uo).call.inExcel) {
                        setForeground(AuditTheme.okForeground(dark));
                    }
                } else {
                    setFont(tree.getFont().deriveFont(Font.BOLD));
                }
            }
            return c;
        }
    }

    private static final class VariantItem {
        final ApiSpecEntry specEntry;
        final ApiCall call;
        final CapturedApiEntry capture;
        final String label;

        VariantItem(ApiSpecEntry specEntry, ApiCall call, CapturedApiEntry capture, String label) {
            this.specEntry = specEntry;
            this.call = call;
            this.capture = capture;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class TreeCallNode {
        final ApiCall call;

        TreeCallNode(ApiCall call) {
            this.call = call;
        }

        @Override
        public String toString() {
            String tag = call.inExcel
                ? ("[STT " + call.excelStt + "]")
                : ("[G" + call.groupId + "#" + call.index + "]");
            String noteTag = call.hasNotes() ? ("  [NOTE: " + call.notesSummary() + "]") : "";
            return tag + " " + call.capture.method + " " + call.displayPath()
                + "  (" + call.capture.paramCount() + " params, HTTP " + call.capture.statusCode
                + ", x" + call.getHits() + ")" + noteTag;
        }
    }
}
