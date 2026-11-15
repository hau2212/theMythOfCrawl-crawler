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
import com.mythofcrawl.crawler.model.ExtenderPushEntry;
import com.mythofcrawl.crawler.model.FormFillProfile;
import com.mythofcrawl.crawler.model.HeaderFilterProfile;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
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
import javax.swing.JToggleButton;
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
import java.awt.Cursor;
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
 * Eye-friendly Burp Suite Audit UI optimized for 14-inch screens:
 * - Slim 1-row primary control bar with explicit Request-Sending Lock ("Tự gửi Request")
 *   and collapsible secondary tools/Excel drawer (hidden by default).
 * - Replaces tiny Swing SplitPane < > arrows with explicit labeled toggle pills so main tables
 *   can occupy up to ~90% of the screen, with bottom Req/Resp inspector collapsed by default.
 * - Integrates Extender -> Logger++ -> Elasticsearch Push Ledger (zero wire packet edits)
 *   and Custom HTTP Header Filter/Modifier (e.g. X-Account: a).
 */
public final class CrawlerTabPanel extends JPanel {

    private static final int TAB_GROUPS = 0;
    private static final int TAB_NOTES = 1;
    private static final int TAB_PUSH = 2;
    private static final int TAB_HEADERS = 3;
    private static final int TAB_COVERAGE = 4;
    private static final int TAB_TREE = 5;
    private static final int TAB_FORM_CONFIG = 6;
    private static final int TAB_AUX = 7;

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private final ExtenderPushManager pushManager;
    private final Runnable onPersistConfig;
    private volatile ExcelSpecMatcher matcher;

    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Timer refreshTimer;
    private File lastDirectory;

    // Top Row (Always visible slim control bar)
    private final JTextField txtHosts = new JTextField(14);
    private final JButton btnStart = new JButton("Bắt đầu Crawl");
    private final JButton btnPause = new JButton("Tạm dừng");
    private final JButton btnStop = new JButton("Dừng");
    private final JToggleButton btnAllowAuto = new JToggleButton();
    private final JToggleButton btnToggleDrawer = new JToggleButton("⚙ Công cụ & Excel ▼");

    // Calm clickable/compact stat chips on primary bar
    private final JLabel chipGroups = new JLabel("Nhóm: 0");
    private final JLabel chipApis = new JLabel("API: 0");
    private final JLabel chipNotes = new JLabel("NOTE: 0");
    private final JLabel chipHeaders = new JLabel("Header: Tắt");
    private final JLabel chipExcel = new JLabel("Excel: 0/0");

    // Collapsible Secondary Drawer (hidden by default on 14" laptop)
    private final JPanel pnlSecondaryDrawer = new JPanel(new BorderLayout(4, 4));
    private final JLabel lblExcelStatus = new JLabel("Chưa nạp Excel");
    private final JTextField txtSeedUrl = new JTextField(18);
    private final JCheckBox chkArmAgent = new JCheckBox("Bơm Agent", true);
    private final JCheckBox chkPassive = new JCheckBox("Bắt thụ động", false);
    private final JCheckBox chkStrictQuick = new JCheckBox("Chỉ giữ Excel", true);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel chipPages = new JLabel("Trang: 0 | Q: 0");
    private final JLabel lblFormSummary = new JLabel();

    // Explicit Panel Toggle Strip (eliminating tiny < > arrows)
    private final JToggleButton btnToggleGroupsPane = new JToggleButton("◧ Ẩn danh sách Nhóm");
    private final JToggleButton btnToggleInspector = new JToggleButton("⬓ Hiện HTTP Req/Resp");
    private final JToggleButton btnMaximizeTables = new JToggleButton("⛶ Phóng to Bảng");
    private final JLabel lblCurrentAction = new JLabel(
        "Sẵn sàng [Khóa tự gửi] - Nhập Host(s), thao tác trình duyệt qua Proxy hoặc bấm Bắt đầu.");

    // Split Panes & Layout state
    private JSplitPane mainSplitPane;
    private JSplitPane groupsSplitPane;
    private JPanel leftGroupsPanel;
    private JPanel inspectorPanel;
    private boolean groupsPaneVisible = true;
    private boolean inspectorVisible = false;
    private double lastGroupsDividerRatio = 0.36;
    private double lastInspectorDividerRatio = 0.58;

    // Main Tabs & Aux Tabs
    private final JTabbedPane mainTabs = new JTabbedPane();
    private final JTabbedPane auxTabs = new JTabbedPane();

    // Tab 0: Action Groups + APIs inside Group
    private boolean treeViewMode = true;
    private final JToggleButton btnViewTree = new JToggleButton("🌳 Cây thư mục & Gộp Path", true);
    private final JToggleButton btnViewFlat = new JToggleButton("📋 Bảng phẳng (Hiện tại)", false);
    private final JButton btnTreeExpandTables = new JButton("⊞ Sổ hết");
    private final JButton btnTreeCollapseTables = new JButton("⊟ Thu gọn");
    private final GroupTableModel.ActionGroupsModel groupsModel;
    private final JTable tblGroups;
    private final JComboBox<String> cbGroupFilter = new JComboBox<>(
        new String[]{"Có API (>0)", "Tất cả nhóm", "Có NOTE (!)"});
    private final JTextField txtGroupSearch = new JTextField(12);

    private final GroupTableModel.GroupApisModel groupApisModel;
    private final JTable tblGroupApis;
    private final JLabel lblGroupHeader = new JLabel("Tất cả API đã nhóm");
    private final JCheckBox chkAllApisWhenUnselected = new JCheckBox("Hiện tất cả khi chưa chọn nhóm", true);
    private final JCheckBox chkOnlyApiNotes = new JCheckBox("Chỉ hiện NOTE", false);
    private final JTextField txtApiSearch = new JTextField(14);

    // Tab 1: Consolidated Audit NOTEs Table
    private final GroupTableModel.AuditNotesTableModel notesModel;
    private final JTable tblNotes;
    private final JComboBox<String> cbNoteSeverity = new JComboBox<>(
        new String[]{"Tất cả (ALL)", "Lỗi (ERROR)", "Cảnh báo (WARN)", "Thông tin (INFO)"});
    private final JTextField txtNoteSearch = new JTextField(18);

    // Tab 2: Push Logger++ -> Elastic Ledger
    private PushLedgerPanel pushLedgerPanel;

    // Tab 3: Custom HTTP Header Filter
    private HeaderFilterPanel headerFilterPanel;

    // Tab 4: Excel Checklist Coverage
    private final CoverageTableModel coverageModel = new CoverageTableModel();
    private final JTable tblCoverage = new JTable(coverageModel);
    private final JComboBox<String> cbStatusFilter = new JComboBox<>(
        new String[]{"Tất cả (ALL)", "Đã lấy (MATCHED)", "Còn thiếu (MISSING)", "Có NOTE (!)"});
    private final JTextField txtSearch = new JTextField(18);

    // Tab 5: Hierarchy Tree
    private boolean treeTabByApiPath = false;
    private final JToggleButton btnTreeByAction = new JToggleButton("🌳 Theo Nhóm (Gộp Path trùng)", true);
    private final JToggleButton btnTreeByPath = new JToggleButton("📂 Theo Cây Path API (/...)", false);
    private final DefaultMutableTreeNode treeRoot;
    private final DefaultTreeModel treeModel;
    private final JTree actionTree;

    // Tab 6: Form Auto-Fill & Config
    private FormAndConfigPanel formConfigPanel;

    // Tab 7: Skipped, JS Hints, Extras, Log
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
        "Chọn một API, NOTE hoặc lá cây (double-click hoặc bấm 'Hiện HTTP Req/Resp' để soi gói tin)");
    private final JComboBox<VariantItem> cbVariant = new JComboBox<>();
    private final JButton btnShowShared = new JButton("Nhóm dùng chung");
    private final JButton btnQueueInspector = new JButton("📤 + Hàng đẩy Logger++");
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
        this(api, config, state, injector, null, initialMatcher, null);
    }

    public CrawlerTabPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExcelSpecMatcher initialMatcher,
        Runnable onPersistConfig
    ) {
        this(api, config, state, injector, null, initialMatcher, onPersistConfig);
    }

    public CrawlerTabPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExtenderPushManager pushManager,
        ExcelSpecMatcher initialMatcher,
        Runnable onPersistConfig
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.injector = injector;
        this.onPersistConfig = onPersistConfig;
        this.matcher = initialMatcher != null ? initialMatcher : ExcelSpecMatcher.empty();
        this.pushManager = pushManager != null
            ? pushManager
            : new ExtenderPushManager(api, config, this::onPushLedgerUpdated, this::appendLog);

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

        boolean dark = AuditTheme.isDark(api, this);
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        setBackground(AuditTheme.canvasSurface(dark));

        syncQuickCheckboxesFromConfig();
        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(buildWorkspaceArea(), BorderLayout.CENTER);

        // Start Inspector collapsed & Drawer hidden for max 14" workspace
        setSecondaryDrawerVisible(false);
        setInspectorVisible(false);

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
        appendLog("theMythOfCrawl Crawler sẵn sàng [Giao diện tối ưu 14\" | Tự gửi Request: "
            + (config.isAllowAutoRequests() ? "ĐANG BẬT" : "ĐANG KHÓA")
            + "]. Form TEXT: \"" + config.getFormFillProfile().get(FormFillProfile.Field.TEXT) + "\".");
    }

    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    public void scheduleUiRefresh() {
        refreshPending.set(true);
    }

    public void onPushLedgerUpdated() {
        Runnable task = () -> {
            if (pushLedgerPanel != null) {
                pushLedgerPanel.refreshView();
            }
            refreshPushAndHeaderTitles();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
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
            btnAllowAuto.setSelected(config.isAllowAutoRequests());
            updateAllowAutoButtonStyle();
            if (formConfigPanel != null) {
                formConfigPanel.syncFromConfig();
            }
            if (headerFilterPanel != null) {
                headerFilterPanel.syncFromConfig();
            }
            refreshFormSummaryLabel();
            refreshPushAndHeaderTitles();
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

    private void updateAllowAutoButtonStyle() {
        boolean dark = AuditTheme.isDark(api, this);
        boolean allowed = config.isAllowAutoRequests();
        btnAllowAuto.setSelected(allowed);
        if (allowed) {
            btnAllowAuto.setText("🔓 Tự gửi Request: ĐANG BẬT");
            btnAllowAuto.setToolTipText(
                "ĐANG BẬT: Cho phép Agent tự click/nộp form trên trình duyệt và tự fetch Seed URL (/robots.txt, /sitemap.xml). Bấm để KHÓA lại.");
            AuditTheme.styleTogglePill(btnAllowAuto, true, AuditTheme.okForeground(dark), dark);
        } else {
            btnAllowAuto.setText("🔒 Tự gửi Request: ĐANG KHÓA");
            btnAllowAuto.setToolTipText(
                "CHẾ ĐỘ AN TOÀN: Không tự click, không tự submit form, không tự fetch Seed. Chỉ ghi nhận & gom nhóm khi BẠN bấm trên trình duyệt. Bấm để cho phép.");
            AuditTheme.styleTogglePill(
                btnAllowAuto, true, AuditTheme.severityForeground(AuditNote.Severity.WARN, dark), dark);
        }
    }

    // =========================================================================
    // Compact 1-Row Primary Header + Collapsible Tools/Excel Drawer
    // =========================================================================

    private JPanel buildHeaderPanel() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel container = new JPanel(new BorderLayout(4, 4));
        container.setOpaque(false);

        // Row 1: Single slim always-visible bar
        JPanel row1 = new JPanel(new GridBagLayout());
        AuditTheme.styleActionCard(row1, dark);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(1, 3, 1, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        txtHosts.setToolTipText("Target Host(s): ví dụ app.example.com,api.example.com (hoặc * để bắt mọi host)");
        txtHosts.setText(config.getAllowedHostsCsv());

        btnStart.setFont(btnStart.getFont().deriveFont(Font.BOLD));
        btnStart.addActionListener(e -> onStartCrawl());
        btnPause.addActionListener(e -> onTogglePause());
        btnStop.addActionListener(e -> onStopCrawl());

        btnAllowAuto.addActionListener(e -> {
            if (!syncingQuickControls) {
                boolean enabled = btnAllowAuto.isSelected();
                config.setAllowAutoRequests(enabled);
                updateAllowAutoButtonStyle();
                appendLog(enabled
                    ? "ĐÃ BẬT 'Tự gửi Request': Agent được phép tự duyệt & gửi seed request khi Crawl RUNNING."
                    : "ĐÃ KHÓA 'Tự gửi Request': Tạm dừng mọi thao tác tự động — chỉ ghi nhận & gom nhóm từ tương tác tay của bạn.");
                if (enabled && state.getStatus() == CrawlState.Status.RUNNING) {
                    String seed = txtSeedUrl.getText().trim();
                    if (!seed.isEmpty() && api != null) {
                        if (!seed.startsWith("http://") && !seed.startsWith("https://")) {
                            seed = "https://" + seed;
                            txtSeedUrl.setText(seed);
                        }
                        SeedDiscoveryHelper.fetchSeedsAsync(api, seed, config, state, this::appendLog);
                    }
                }
            }
        });
        updateAllowAutoButtonStyle();

        // Clickable compact chips
        JPanel chipsBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        chipsBar.setOpaque(false);
        makeChipJumpable(chipGroups, TAB_GROUPS, "Xem tab Nhóm & API");
        makeChipJumpable(chipApis, TAB_GROUPS, "Xem danh sách API đã nhóm");
        makeChipJumpable(chipNotes, TAB_NOTES, "Mở Bảng NOTE kiểm tra bất thường");
        makeChipJumpable(chipHeaders, TAB_HEADERS, "Mở Bộ lọc & Tùy chỉnh HTTP Header");
        makeChipJumpable(chipExcel, TAB_COVERAGE, "Mở Tiến độ Excel Checklist");
        chipsBar.add(chipGroups);
        chipsBar.add(chipApis);
        chipsBar.add(chipNotes);
        chipsBar.add(chipHeaders);
        chipsBar.add(chipExcel);

        btnToggleDrawer.addActionListener(e -> setSecondaryDrawerVisible(btnToggleDrawer.isSelected()));
        AuditTheme.styleTogglePill(btnToggleDrawer, false, AuditTheme.accentForeground(dark), dark);

        int x = 0;
        gbc.gridx = x++; gbc.gridy = 0; gbc.weightx = 0; row1.add(new JLabel("Host(s):"), gbc);
        gbc.gridx = x++; gbc.weightx = 1.0; row1.add(txtHosts, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnStart, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnPause, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnStop, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(btnAllowAuto, gbc);
        gbc.gridx = x++; gbc.weightx = 0; row1.add(chipsBar, gbc);
        gbc.gridx = x;   gbc.weightx = 0; row1.add(btnToggleDrawer, gbc);

        // Collapsible Secondary Drawer (Import Excel, Seed URL, Agent Toggles, Reset, Export, Progress)
        pnlSecondaryDrawer.setOpaque(true);
        AuditTheme.styleActionCard(pnlSecondaryDrawer, dark);

        JPanel drawerRowA = new JPanel(new GridBagLayout());
        drawerRowA.setOpaque(false);
        GridBagConstraints da = new GridBagConstraints();
        da.insets = new Insets(2, 4, 2, 4);
        da.anchor = GridBagConstraints.WEST;
        da.fill = GridBagConstraints.HORIZONTAL;

        JButton btnImport = new JButton("Import Excel (.xlsx/.csv)");
        btnImport.setToolTipText("Nạp danh sách API chuẩn gồm cột STT, Method, Path API");
        btnImport.addActionListener(e -> onImportExcel());
        lblExcelStatus.setForeground(AuditTheme.mutedForeground(dark));
        txtSeedUrl.setToolTipText("URL trang chủ để quét sitemap/robots khi 'Tự gửi Request' được BẬT");

        JButton btnReset = new JButton("Reset kết quả");
        btnReset.setToolTipText("Xóa kết quả crawl hiện tại (giữ lại Excel & cấu hình Form/Header)");
        btnReset.addActionListener(e -> onResetCaptures());

        JButton btnExport = new JButton("Xuất báo cáo ▼");
        btnExport.addActionListener(e -> showExportMenu(btnExport));

        int dx = 0;
        da.gridx = dx++; da.gridy = 0; da.weightx = 0; drawerRowA.add(btnImport, da);
        da.gridx = dx++; da.weightx = 0; drawerRowA.add(lblExcelStatus, da);
        da.gridx = dx++; da.weightx = 0; drawerRowA.add(new JLabel("Seed URL:"), da);
        da.gridx = dx++; da.weightx = 1.0; drawerRowA.add(txtSeedUrl, da);
        da.gridx = dx++; da.weightx = 0; drawerRowA.add(btnReset, da);
        da.gridx = dx;   da.weightx = 0; drawerRowA.add(btnExport, da);

        JPanel drawerRowB = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        drawerRowB.setOpaque(false);
        chkArmAgent.setOpaque(false);
        chkArmAgent.addActionListener(e -> {
            if (injector != null) {
                injector.setAgentArmed(chkArmAgent.isSelected());
                appendLog(chkArmAgent.isSelected()
                    ? "Đã BẬT bơm Agent vào trình duyệt (ghi nhận Action Path khi click/auto-crawl)."
                    : "Đã TẮT bơm Agent (chỉ nghe HTTP traffic).");
            }
        });
        chkPassive.setOpaque(false);
        chkPassive.addActionListener(e -> {
            if (!syncingQuickControls) {
                boolean passive = chkPassive.isSelected();
                if (injector != null) {
                    injector.setPassiveCapture(passive);
                }
                appendLog(passive
                    ? "Chế độ BẮT THỤ ĐỘNG: chỉ ghi nhận traffic, tạm tắt bơm Agent."
                    : "Chế độ CHỦ ĐỘNG: Agent hỗ trợ gom nhóm & tự duyệt (nếu Tự gửi Request được BẬT).");
            }
        });
        chkStrictQuick.setOpaque(false);
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
        btnFormConfig.addActionListener(e -> mainTabs.setSelectedIndex(TAB_FORM_CONFIG));

        progressBar.setPreferredSize(new Dimension(115, 20));
        progressBar.setStringPainted(true);
        progressBar.setString("0%");

        drawerRowB.add(chkArmAgent);
        drawerRowB.add(chkPassive);
        drawerRowB.add(chkStrictQuick);
        drawerRowB.add(btnFormConfig);
        drawerRowB.add(Box.createHorizontalStrut(4));
        drawerRowB.add(progressBar);
        drawerRowB.add(chipPages);
        drawerRowB.add(Box.createHorizontalStrut(6));
        drawerRowB.add(lblFormSummary);

        pnlSecondaryDrawer.add(drawerRowA, BorderLayout.NORTH);
        pnlSecondaryDrawer.add(drawerRowB, BorderLayout.SOUTH);

        container.add(row1, BorderLayout.NORTH);
        container.add(pnlSecondaryDrawer, BorderLayout.SOUTH);
        return container;
    }

    private void makeChipJumpable(JLabel chip, int tabIndex, String tooltip) {
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.setToolTipText(tooltip);
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (tabIndex >= 0 && tabIndex < mainTabs.getTabCount()) {
                    mainTabs.setSelectedIndex(tabIndex);
                }
            }
        });
    }

    private void setSecondaryDrawerVisible(boolean visible) {
        boolean dark = AuditTheme.isDark(api, this);
        pnlSecondaryDrawer.setVisible(visible);
        btnToggleDrawer.setSelected(visible);
        btnToggleDrawer.setText(visible ? "⚙ Công cụ & Excel ▲" : "⚙ Công cụ & Excel ▼");
        AuditTheme.styleTogglePill(btnToggleDrawer, visible, AuditTheme.accentForeground(dark), dark);
        syncMaximizeToggleButtonState();
        revalidate();
    }

    // =========================================================================
    // Center Workspace: Explicit Panel Toggle Strip + Main Split & Tabs
    // =========================================================================

    private JPanel buildWorkspaceArea() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel wrap = new JPanel(new BorderLayout(4, 4));
        wrap.setOpaque(false);

        // Explicit Panel Toggle Strip (replaces tiny < > / ^ v SplitPane arrows)
        JPanel strip = new JPanel(new BorderLayout(8, 2));
        strip.setOpaque(true);
        strip.setBackground(AuditTheme.panelSurface(dark));
        strip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AuditTheme.borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(3, 6, 3, 8)));

        JPanel toggleBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toggleBtns.setOpaque(false);

        btnToggleGroupsPane.addActionListener(e -> {
            if (mainTabs.getSelectedIndex() != TAB_GROUPS) {
                mainTabs.setSelectedIndex(TAB_GROUPS);
                if (!groupsPaneVisible) {
                    setGroupsPaneVisible(true);
                    return;
                }
            }
            setGroupsPaneVisible(!groupsPaneVisible);
        });

        btnToggleInspector.addActionListener(e -> setInspectorVisible(!inspectorVisible));

        btnMaximizeTables.addActionListener(e -> {
            boolean currentlyMaximized = !inspectorVisible && !pnlSecondaryDrawer.isVisible();
            if (currentlyMaximized) {
                // Restore inspector so user can inspect immediately
                setInspectorVisible(true);
            } else {
                setSecondaryDrawerVisible(false);
                setInspectorVisible(false);
            }
            syncMaximizeToggleButtonState();
        });

        toggleBtns.add(btnToggleGroupsPane);
        toggleBtns.add(btnToggleInspector);
        toggleBtns.add(btnMaximizeTables);

        lblCurrentAction.setForeground(AuditTheme.accentForeground(dark));
        lblCurrentAction.setFont(lblCurrentAction.getFont().deriveFont(Font.BOLD));
        lblCurrentAction.setHorizontalAlignment(JLabel.RIGHT);

        strip.add(toggleBtns, BorderLayout.WEST);
        strip.add(lblCurrentAction, BorderLayout.CENTER);

        wrap.add(strip, BorderLayout.NORTH);
        wrap.add(buildMainSplitPane(), BorderLayout.CENTER);
        return wrap;
    }

    private JSplitPane buildMainSplitPane() {
        boolean dark = AuditTheme.isDark(api, this);

        this.pushLedgerPanel = new PushLedgerPanel(
            api,
            state.getActionGroups(),
            pushManager,
            this::getSelectedGroup,
            this::inspectPushEntry,
            call -> {
                if (call != null && call.capture != null) {
                    String tag = (call.excelStt != null && !call.excelStt.isEmpty())
                        ? ("STT " + call.excelStt)
                        : ("G" + call.groupId + "#" + call.index);
                    sendToRepeater(call.capture, tag + " " + call.capture.method);
                }
            },
            this::appendLog);

        this.headerFilterPanel = new HeaderFilterPanel(
            api,
            config,
            this::appendLog,
            () -> {
                persistConfigQuietly();
                refreshPushAndHeaderTitles();
            });

        mainTabs.setBackground(AuditTheme.panelSurface(dark));
        mainTabs.addTab("Nhóm & API", buildGroupsAndApisTab());
        mainTabs.addTab("Bảng NOTE (0)", buildNotesTab());
        mainTabs.addTab("Đẩy Logger++ (0)", pushLedgerPanel);
        mainTabs.addTab("Bộ lọc Header HTTP (0)", headerFilterPanel);
        mainTabs.addTab("Tiến độ Excel (0/0)", buildCoverageTab());
        mainTabs.addTab("Cây Phân cấp (0)", buildHierarchyTreeTab());
        mainTabs.addTab("Điền Form & Cài Đặt", buildFormAndConfigTab());
        mainTabs.addTab("Bị né, JS & Log", buildAuxTab());

        this.inspectorPanel = buildInspectorPanel();
        this.mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainTabs, inspectorPanel);
        this.mainSplitPane.setResizeWeight(0.62);
        this.mainSplitPane.setOneTouchExpandable(false); // Replaced by explicit toggle strip
        this.mainSplitPane.setBackground(AuditTheme.canvasSurface(dark));
        return this.mainSplitPane;
    }

    private void setGroupsPaneVisible(boolean visible) {
        if (groupsSplitPane == null || leftGroupsPanel == null) {
            return;
        }
        boolean dark = AuditTheme.isDark(api, this);
        if (!visible && groupsPaneVisible && groupsSplitPane.getWidth() > 200) {
            lastGroupsDividerRatio = Math.max(0.20, Math.min(0.60,
                groupsSplitPane.getDividerLocation() / (double) groupsSplitPane.getWidth()));
        }
        groupsPaneVisible = visible;
        leftGroupsPanel.setVisible(visible);
        if (visible) {
            groupsSplitPane.setDividerSize(7);
            SwingUtilities.invokeLater(() -> {
                if (groupsSplitPane.getWidth() > 100) {
                    groupsSplitPane.setDividerLocation(lastGroupsDividerRatio);
                }
            });
            btnToggleGroupsPane.setText("◧ Ẩn danh sách Nhóm");
        } else {
            groupsSplitPane.setDividerSize(0);
            groupsSplitPane.setDividerLocation(0);
            btnToggleGroupsPane.setText("◧ Hiện danh sách Nhóm");
        }
        AuditTheme.styleTogglePill(btnToggleGroupsPane, visible, AuditTheme.accentForeground(dark), dark);
        groupsSplitPane.revalidate();
        groupsSplitPane.repaint();
    }

    private void setInspectorVisible(boolean visible) {
        if (mainSplitPane == null || inspectorPanel == null) {
            return;
        }
        boolean dark = AuditTheme.isDark(api, this);
        if (!visible && inspectorVisible && mainSplitPane.getHeight() > 200) {
            lastInspectorDividerRatio = Math.max(0.35, Math.min(0.80,
                mainSplitPane.getDividerLocation() / (double) mainSplitPane.getHeight()));
        }
        inspectorVisible = visible;
        inspectorPanel.setVisible(visible);
        if (visible) {
            mainSplitPane.setDividerSize(7);
            SwingUtilities.invokeLater(() -> {
                if (mainSplitPane.getHeight() > 120) {
                    mainSplitPane.setDividerLocation(lastInspectorDividerRatio);
                }
            });
            btnToggleInspector.setText("⬓ Ẩn HTTP Req/Resp");
        } else {
            mainSplitPane.setDividerSize(0);
            mainSplitPane.setDividerLocation(1.0);
            btnToggleInspector.setText("⬓ Hiện HTTP Req/Resp");
        }
        AuditTheme.styleTogglePill(btnToggleInspector, visible, AuditTheme.accentForeground(dark), dark);
        syncMaximizeToggleButtonState();
        mainSplitPane.revalidate();
        mainSplitPane.repaint();
    }

    private void syncMaximizeToggleButtonState() {
        boolean dark = AuditTheme.isDark(api, this);
        boolean maximized = !inspectorVisible && !pnlSecondaryDrawer.isVisible();
        btnMaximizeTables.setSelected(maximized);
        btnMaximizeTables.setText(maximized ? "⛶ Thu gọn Bảng" : "⛶ Phóng to Bảng");
        AuditTheme.styleTogglePill(btnMaximizeTables, maximized, AuditTheme.accentForeground(dark), dark);
    }

    // --- Tab 0: Action Groups + APIs inside Group ---

    private JSplitPane buildGroupsAndApisTab() {
        boolean dark = AuditTheme.isDark(api, this);

        // Left pane: Action Groups
        leftGroupsPanel = new JPanel(new BorderLayout(4, 4));
        leftGroupsPanel.setBackground(AuditTheme.panelSurface(dark));
        leftGroupsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel leftFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        leftFilter.setOpaque(false);
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

        AuditTheme.configureAuditTable(tblGroups, api);
        tblGroups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblGroups.setDefaultRenderer(Object.class, new GroupTableModel.GroupCellRenderer(api));
        tblGroups.setDefaultRenderer(Integer.class, new GroupTableModel.GroupCellRenderer(api));

        tblGroups.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyGroupApisFilter();
            }
        });
        tblGroups.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int vr = tblGroups.rowAtPoint(e.getPoint());
                int vc = tblGroups.columnAtPoint(e.getPoint());
                if (vr < 0) {
                    return;
                }
                int mr = tblGroups.convertRowIndexToModel(vr);
                GroupTableModel.ActionGroupsModel.GroupRow row = groupsModel.getRowAt(mr);
                if (row != null && row.isFolder && ((vc == 0 && e.getClickCount() == 1) || e.getClickCount() == 2)) {
                    String folderKey = row.folderKey;
                    groupsModel.toggleFolderExpanded(mr);
                    reselectFolderKey(folderKey);
                    applyGroupApisFilter();
                }
            }
        });
        installGroupPopupMenu();

        leftGroupsPanel.add(leftFilter, BorderLayout.NORTH);
        JScrollPane spGroups = new JScrollPane(tblGroups);
        spGroups.getViewport().setBackground(AuditTheme.tableSurface(dark));
        leftGroupsPanel.add(spGroups, BorderLayout.CENTER);

        // Right pane: APIs inside selected group/folder (or all grouped APIs)
        JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
        rightPanel.setBackground(AuditTheme.panelSurface(dark));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel rightHeader = new JPanel(new BorderLayout(4, 2));
        rightHeader.setOpaque(false);
        lblGroupHeader.setFont(lblGroupHeader.getFont().deriveFont(Font.BOLD));
        lblGroupHeader.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        JPanel viewModeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        viewModeBar.setOpaque(false);
        btnViewTree.setToolTipText("Chế độ cây: gộp nhóm cùng nhánh và gộp >= 2 API cùng path (sổ ra vẫn giữ nguyên tên & thụt lề)");
        btnViewFlat.setToolTipText("Chế độ bảng phẳng như ban đầu (cho phép sort theo từng cột)");
        btnTreeExpandTables.setToolTipText("Mở rộng toàn bộ thư mục nhóm và nhóm path trùng");
        btnTreeCollapseTables.setToolTipText("Thu gọn toàn bộ thư mục nhóm và nhóm path trùng");
        AuditTheme.styleTogglePill(btnTreeExpandTables, false, AuditTheme.mutedForeground(dark), dark);
        AuditTheme.styleTogglePill(btnTreeCollapseTables, false, AuditTheme.mutedForeground(dark), dark);
        btnViewTree.addActionListener(e -> setGroupViewMode(true));
        btnViewFlat.addActionListener(e -> setGroupViewMode(false));
        btnTreeExpandTables.addActionListener(e -> {
            groupsModel.expandAllFolders();
            groupApisModel.expandAllPathGroups();
        });
        btnTreeCollapseTables.addActionListener(e -> {
            groupsModel.collapseAllFolders();
            groupApisModel.collapseAllPathGroups();
        });
        viewModeBar.add(btnViewTree);
        viewModeBar.add(btnViewFlat);
        viewModeBar.add(btnTreeExpandTables);
        viewModeBar.add(btnTreeCollapseTables);

        JPanel rightTopLine = new JPanel(new BorderLayout(6, 0));
        rightTopLine.setOpaque(false);
        rightTopLine.add(lblGroupHeader, BorderLayout.CENTER);
        rightTopLine.add(viewModeBar, BorderLayout.EAST);

        JPanel rightFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        rightFilter.setOpaque(false);
        chkAllApisWhenUnselected.setOpaque(false);
        chkOnlyApiNotes.setOpaque(false);
        chkAllApisWhenUnselected.addActionListener(e -> applyGroupApisFilter());
        chkOnlyApiNotes.addActionListener(e -> applyGroupApisFilter());
        attachDocListener(txtApiSearch, this::applyGroupApisFilter);
        rightFilter.add(chkAllApisWhenUnselected);
        rightFilter.add(chkOnlyApiNotes);
        rightFilter.add(new JLabel("Tìm API:"));
        rightFilter.add(txtApiSearch);

        rightHeader.add(rightTopLine, BorderLayout.NORTH);
        rightHeader.add(rightFilter, BorderLayout.SOUTH);

        AuditTheme.configureAuditTable(tblGroupApis, api);
        tblGroupApis.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblGroupApis.setDefaultRenderer(Object.class, new GroupTableModel.GroupApiCellRenderer(api));
        tblGroupApis.setDefaultRenderer(Integer.class, new GroupTableModel.GroupApiCellRenderer(api));

        applyGroupViewModeToTables(dark);

        tblGroupApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedGroupApiRow();
                if (row == null) {
                    return;
                }
                ApiCall call = row.call != null ? row.call : pickRepresentativeCall(row.groupedCalls);
                if (call != null) {
                    showApiCallInInspector(call);
                }
            }
        });
        tblGroupApis.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int vr = tblGroupApis.rowAtPoint(e.getPoint());
                int vc = tblGroupApis.columnAtPoint(e.getPoint());
                if (vr < 0) {
                    return;
                }
                int mr = tblGroupApis.convertRowIndexToModel(vr);
                GroupTableModel.GroupApisModel.ApiDisplayRow row = groupApisModel.getRowAt(mr);
                if (row == null) {
                    return;
                }
                if (row.isGroupHeader) {
                    if ((vc == 0 && e.getClickCount() == 1) || e.getClickCount() == 2) {
                        String pathKey = row.pathKey;
                        groupApisModel.togglePathGroupExpanded(mr);
                        reselectPathGroupHeader(pathKey);
                    }
                } else if (e.getClickCount() == 2) {
                    setInspectorVisible(true);
                }
            }
        });
        installGroupApiPopupMenu();

        rightPanel.add(rightHeader, BorderLayout.NORTH);
        JScrollPane spApis = new JScrollPane(tblGroupApis);
        spApis.getViewport().setBackground(AuditTheme.tableSurface(dark));
        rightPanel.add(spApis, BorderLayout.CENTER);

        groupsSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftGroupsPanel, rightPanel);
        groupsSplitPane.setResizeWeight(0.36);
        groupsSplitPane.setOneTouchExpandable(false); // Replaced by explicit pill button
        groupsSplitPane.setBackground(AuditTheme.canvasSurface(dark));
        return groupsSplitPane;
    }

    public boolean isGroupTreeViewMode() {
        return treeViewMode;
    }

    public void setGroupViewMode(boolean treeMode) {
        this.treeViewMode = treeMode;
        GroupTableModel.ActionGroupsModel.GroupRow prevGroupRow = getSelectedGroupRow();
        ApiCall prevCall = getSelectedGroupApi();
        boolean dark = AuditTheme.isDark(api, this);
        applyGroupViewModeToTables(dark);
        if (prevGroupRow != null) {
            if (prevGroupRow.directGroup != null) {
                if (treeMode) {
                    groupsModel.ensureGroupVisible(prevGroupRow.directGroup);
                }
                reselectGroup(prevGroupRow.directGroup.id);
            } else if (treeMode && prevGroupRow.folderKey != null) {
                reselectFolderKey(prevGroupRow.folderKey);
            }
        }
        applyGroupApisFilter();
        if (prevCall != null) {
            if (treeMode) {
                groupApisModel.ensureCallVisible(prevCall);
            }
            reselectGroupApi(prevCall);
        }
    }

    private void applyGroupViewModeToTables(boolean dark) {
        groupsModel.setTreeMode(treeViewMode);
        groupApisModel.setTreeMode(treeViewMode);

        tblGroups.setAutoCreateRowSorter(!treeViewMode);
        tblGroupApis.setAutoCreateRowSorter(!treeViewMode);
        if (treeViewMode) {
            tblGroups.setRowSorter(null);
            tblGroupApis.setRowSorter(null);
        }
        setColumnWidths(tblGroups.getColumnModel(), new int[]{42, 280, 56, 56, 56, 46});
        setColumnWidths(tblGroupApis.getColumnModel(), new int[]{44, 300, 66, 230, 52, 210, 44, 64, 210});

        btnViewTree.setSelected(treeViewMode);
        btnViewFlat.setSelected(!treeViewMode);
        AuditTheme.styleTogglePill(btnViewTree, treeViewMode, AuditTheme.accentForeground(dark), dark);
        AuditTheme.styleTogglePill(btnViewFlat, !treeViewMode, AuditTheme.accentForeground(dark), dark);
        btnTreeExpandTables.setVisible(treeViewMode);
        btnTreeCollapseTables.setVisible(treeViewMode);
    }

    private void installGroupPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miPushGroup = new JMenuItem("📤 Đưa NHÓM / NHÁNH này vào hàng đẩy Logger++ (Extender)");
        JMenuItem miSendAll = new JMenuItem("Send tất cả API trong nhóm / nhánh sang Repeater");
        JMenuItem miCopyPath = new JMenuItem("Copy Tên Nhóm (Action Path)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Page URL");

        miPushGroup.addActionListener(e -> {
            GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
            if (row == null) {
                return;
            }
            if (row.directGroup != null) {
                enqueueGroupWithFeedback(row.directGroup, true);
            } else {
                List<ApiCall> calls = collectCallsFromGroups(row.subtreeGroups);
                enqueueCallsWithFeedback(calls, "Nhánh " + row.folderKey, true);
            }
        });
        miSendAll.addActionListener(e -> {
            GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
            if (row == null) {
                return;
            }
            if (row.directGroup != null) {
                sendGroupToRepeater(row.directGroup);
            } else {
                sendCallsToRepeater(collectCallsFromGroups(row.subtreeGroups), "nhánh " + row.folderKey);
            }
        });
        miCopyPath.addActionListener(e -> {
            GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
            if (row != null) {
                copyToClipboard(row.directGroup != null ? row.directGroup.actionPath : row.folderKey);
            }
        });
        miCopyUrl.addActionListener(e -> {
            GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
            if (row != null && !row.subtreeGroups.isEmpty()) {
                copyToClipboard(row.subtreeGroups.get(0).pageUrl);
            }
        });

        popup.add(miPushGroup);
        popup.add(miSendAll);
        popup.addSeparator();
        popup.add(miCopyPath);
        popup.add(miCopyUrl);
        attachTablePopup(tblGroups, popup);
    }

    private void installGroupApiPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miPushApi = new JMenuItem("📤 Đưa API (hoặc nhóm path) này vào hàng đẩy Logger++ (Extender)");
        JMenuItem miPushGroup = new JMenuItem("📤 Đưa CẢ NHÓM chứa API này vào hàng đẩy Logger++");
        JMenuItem miSend = new JMenuItem("Send to Repeater");
        JMenuItem miSendGroup = new JMenuItem("Send CẢ NHÓM chứa API này sang Repeater");
        JMenuItem miShared = new JMenuItem("Xem các nhóm cùng gọi API này");
        JMenuItem miCopyName = new JMenuItem("Copy Tên API (Tên Nhóm)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");

        miPushApi.addActionListener(e -> {
            GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedGroupApiRow();
            if (row == null) {
                return;
            }
            if (row.isGroupHeader) {
                enqueueCallsWithFeedback(row.groupedCalls, "Path " + row.pathKey + " (" + row.groupedCalls.size() + " API)", true);
            } else if (row.call != null) {
                enqueueCallsWithFeedback(Collections.singletonList(row.call), "API " + row.call.displayName(), true);
            }
        });
        miPushGroup.addActionListener(e -> {
            ApiCall c = getRepresentativeSelectedApiCall();
            if (c != null) {
                ActionGroup g = state.getActionGroups().getGroup(c.groupId);
                if (g != null) {
                    enqueueGroupWithFeedback(g, true);
                }
            }
        });
        miSend.addActionListener(e -> {
            GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedGroupApiRow();
            if (row == null) {
                return;
            }
            if (row.isGroupHeader) {
                sendCallsToRepeater(row.groupedCalls, "nhóm path " + row.pathKey);
            } else if (row.call != null) {
                ApiCall c = row.call;
                String tag = c.inExcel ? ("STT " + c.excelStt) : ("G" + c.groupId + "#" + c.index);
                sendToRepeater(c.capture, tag + " " + c.capture.method);
            }
        });
        miSendGroup.addActionListener(e -> {
            ApiCall c = getRepresentativeSelectedApiCall();
            if (c != null) {
                ActionGroup g = state.getActionGroups().getGroup(c.groupId);
                if (g != null) {
                    sendGroupToRepeater(g);
                }
            }
        });
        miShared.addActionListener(e -> {
            ApiCall c = getRepresentativeSelectedApiCall();
            if (c != null) {
                showSharedGroupsDialog(c.endpointKey);
            }
        });
        miCopyName.addActionListener(e -> {
            GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedGroupApiRow();
            if (row != null) {
                copyToClipboard(row.call != null ? row.call.displayName() : row.pathKey);
            }
        });
        miCopyUrl.addActionListener(e -> {
            ApiCall c = getRepresentativeSelectedApiCall();
            if (c != null) {
                copyToClipboard(c.capture.fullUrl);
            }
        });

        popup.add(miPushApi);
        popup.add(miPushGroup);
        popup.addSeparator();
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
        boolean dark = AuditTheme.isDark(api, this);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(AuditTheme.panelSurface(dark));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        bar.add(new JLabel("Mức độ NOTE:"));
        cbNoteSeverity.addActionListener(e -> applyNotesFilter());
        bar.add(cbNoteSeverity);
        bar.add(new JLabel("Tìm NOTE / Endpoint / Nhóm:"));
        attachDocListener(txtNoteSearch, this::applyNotesFilter);
        bar.add(txtNoteSearch);

        AuditTheme.configureAuditTable(tblNotes, api);
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
        attachDoubleClickToRevealInspector(tblNotes);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miJump = new JMenuItem("Đi tới Nhóm này (Tab Nhóm & API)");
        JMenuItem miPush = new JMenuItem("📤 Đưa API này vào hàng đẩy Logger++ (Extender)");
        JMenuItem miRepeater = new JMenuItem("Send to Repeater");
        JMenuItem miCopy = new JMenuItem("Copy Chi tiết NOTE");

        miJump.addActionListener(e -> {
            AuditNote n = getSelectedNote();
            if (n != null) {
                jumpToGroupAndCall(n.groupId, n.callIndex);
            }
        });
        miPush.addActionListener(e -> {
            AuditNote n = getSelectedNote();
            if (n != null) {
                ApiCall call = findApiCall(n.groupId, n.callIndex);
                if (call != null) {
                    enqueueCallsWithFeedback(Collections.singletonList(call), "NOTE " + call.displayName(), true);
                }
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
        popup.add(miPush);
        popup.add(miRepeater);
        popup.add(miCopy);
        attachTablePopup(tblNotes, popup);

        p.add(bar, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tblNotes);
        sp.getViewport().setBackground(AuditTheme.tableSurface(dark));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    // --- Tab 4: Excel Checklist Coverage ---

    private JPanel buildCoverageTab() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(AuditTheme.panelSurface(dark));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterBar.setOpaque(false);
        filterBar.add(new JLabel("Lọc trạng thái:"));
        cbStatusFilter.addActionListener(e -> applyCoverageFilter());
        filterBar.add(cbStatusFilter);
        filterBar.add(new JLabel("Tìm kiếm:"));
        attachDocListener(txtSearch, this::applyCoverageFilter);
        filterBar.add(txtSearch);

        AuditTheme.configureAuditTable(tblCoverage, api);
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
        attachDoubleClickToRevealInspector(tblCoverage);

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
        JScrollPane sp = new JScrollPane(tblCoverage);
        sp.getViewport().setBackground(AuditTheme.tableSurface(dark));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    // --- Tab 5: Hierarchy Tree ---

    private JPanel buildHierarchyTreeTab() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(AuditTheme.panelSurface(dark));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        JButton btnExpand = new JButton("Mở rộng tất cả");
        JButton btnCollapse = new JButton("Thu gọn tất cả");
        JButton btnRefreshTree = new JButton("Làm mới cây");
        btnExpand.addActionListener(e -> expandAllTreeNodes(true));
        btnCollapse.addActionListener(e -> expandAllTreeNodes(false));
        btnRefreshTree.addActionListener(e -> rebuildHierarchyTree());

        btnTreeByAction.setSelected(!treeTabByApiPath);
        btnTreeByPath.setSelected(treeTabByApiPath);
        AuditTheme.styleTogglePill(btnTreeByAction, !treeTabByApiPath, AuditTheme.accentForeground(dark), dark);
        AuditTheme.styleTogglePill(btnTreeByPath, treeTabByApiPath, AuditTheme.accentForeground(dark), dark);
        btnTreeByAction.setToolTipText("Nhóm theo hành động UI (gộp >= 2 API cùng path ngay bên trong mỗi nhánh)");
        btnTreeByPath.setToolTipText("Xếp theo cây thư mục URL Path (/api/v1/...) gộp mọi API cùng path & giữ tên nhóm ở từng lá");
        btnTreeByAction.addActionListener(e -> {
            treeTabByApiPath = false;
            boolean isDark = AuditTheme.isDark(api, this);
            btnTreeByAction.setSelected(true);
            btnTreeByPath.setSelected(false);
            AuditTheme.styleTogglePill(btnTreeByAction, true, AuditTheme.accentForeground(isDark), isDark);
            AuditTheme.styleTogglePill(btnTreeByPath, false, AuditTheme.accentForeground(isDark), isDark);
            rebuildHierarchyTree();
        });
        btnTreeByPath.addActionListener(e -> {
            treeTabByApiPath = true;
            boolean isDark = AuditTheme.isDark(api, this);
            btnTreeByAction.setSelected(false);
            btnTreeByPath.setSelected(true);
            AuditTheme.styleTogglePill(btnTreeByAction, false, AuditTheme.accentForeground(isDark), isDark);
            AuditTheme.styleTogglePill(btnTreeByPath, true, AuditTheme.accentForeground(isDark), isDark);
            rebuildHierarchyTree();
        });

        bar.add(new JLabel("Chế độ cây:"));
        bar.add(btnTreeByAction);
        bar.add(btnTreeByPath);
        bar.add(btnExpand);
        bar.add(btnCollapse);
        bar.add(btnRefreshTree);

        actionTree.setBackground(AuditTheme.tableSurface(dark));
        actionTree.setCellRenderer(new AuditTreeRenderer());
        actionTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                showApiCallInInspector(tcn.call);
            }
        });
        actionTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
                    if (node != null && node.getUserObject() instanceof TreeCallNode) {
                        setInspectorVisible(true);
                    }
                }
            }
        });

        JPopupMenu treePopup = new JPopupMenu();
        JMenuItem miTreePush = new JMenuItem("📤 Đưa API (hoặc cả nhánh) vào hàng đẩy Logger++ (Extender)");
        JMenuItem miTreeRep = new JMenuItem("Send to Repeater");
        JMenuItem miTreeRepGroup = new JMenuItem("Send CẢ NHÓM chứa API này sang Repeater");
        miTreePush.addActionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node == null) {
                return;
            }
            if (node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                enqueueCallsWithFeedback(Collections.singletonList(tcn.call), tcn.call.displayName(), true);
            } else {
                List<ApiCall> calls = collectTreeCalls(node);
                enqueueCallsWithFeedback(calls, "Nhánh cây " + node.getUserObject(), true);
            }
        });
        miTreeRep.addActionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node == null) {
                return;
            }
            if (node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                sendToRepeater(tcn.call.capture,
                    "G" + tcn.call.groupId + "#" + tcn.call.index + " " + tcn.call.capture.method);
            } else {
                List<ApiCall> calls = collectTreeCalls(node);
                sendCallsToRepeater(calls, "nhánh cây " + node.getUserObject());
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
        treePopup.add(miTreePush);
        treePopup.add(miTreeRep);
        treePopup.add(miTreeRepGroup);
        actionTree.setComponentPopupMenu(treePopup);

        p.add(bar, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(actionTree);
        sp.getViewport().setBackground(AuditTheme.tableSurface(dark));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    // --- Tab 6: Configurable Form Auto-Fill ("TEXT") & Crawl Config ---

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

    // --- Tab 7: Aux (Log, Extra Captures, Danger Guard & JS Hints) ---

    private JPanel buildAuxTab() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(AuditTheme.panelSurface(dark));

        // Sub-tab 0: Activity Log
        JPanel logPanel = new JPanel(new BorderLayout(4, 4));
        logPanel.setBackground(AuditTheme.panelSurface(dark));
        txtLog.setEditable(false);
        txtLog.setBackground(AuditTheme.tableSurface(dark));
        txtLog.setForeground(AuditTheme.textPrimary(dark));
        txtLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        logTop.setOpaque(false);
        JButton btnClearLog = new JButton("Xóa Log");
        btnClearLog.addActionListener(e -> txtLog.setText(""));
        logTop.add(btnClearLog);
        logPanel.add(logTop, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        // Sub-tab 1: Extras (outside Excel)
        AuditTheme.configureAuditTable(tblExtras, api);
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
        attachDoubleClickToRevealInspector(tblExtras);
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
        AuditTheme.configureAuditTable(tblSkipped, api);
        tblSkipped.setAutoCreateRowSorter(true);
        tblSkipped.setDefaultRenderer(Object.class, new CoverageTableModel.SimpleAuditCellRenderer(api, -1, -1, 2));
        JPanel skippedWrap = new JPanel(new BorderLayout(4, 4));
        skippedWrap.setBackground(AuditTheme.panelSurface(dark));
        skippedWrap.setBorder(BorderFactory.createTitledBorder("Nút Danger Guard đã tự né"));
        JScrollPane spSkipped = new JScrollPane(tblSkipped);
        spSkipped.getViewport().setBackground(AuditTheme.tableSurface(dark));
        skippedWrap.add(spSkipped, BorderLayout.CENTER);

        JPanel jsWrap = new JPanel(new BorderLayout(4, 4));
        jsWrap.setBackground(AuditTheme.panelSurface(dark));
        jsWrap.setBorder(BorderFactory.createTitledBorder("Endpoint phát hiện thụ động từ file .js"));
        lstJsEndpoints.setBackground(AuditTheme.tableSurface(dark));
        lstJsEndpoints.setForeground(AuditTheme.textPrimary(dark));
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
        dangerJsSplit.setOneTouchExpandable(false);

        JScrollPane spExtras = new JScrollPane(tblExtras);
        spExtras.getViewport().setBackground(AuditTheme.tableSurface(dark));

        auxTabs.setBackground(AuditTheme.panelSurface(dark));
        auxTabs.addTab("Log hoạt động", logPanel);
        auxTabs.addTab("Ngoài Excel (0)", spExtras);
        auxTabs.addTab("Nút bị né & JS Hints (0/0)", dangerJsSplit);
        p.add(auxTabs, BorderLayout.CENTER);
        return p;
    }

    // =========================================================================
    // Bottom Inspector Panel
    // =========================================================================

    private JPanel buildInspectorPanel() {
        boolean dark = AuditTheme.isDark(api, this);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(AuditTheme.panelSurface(dark));
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AuditTheme.borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        JPanel top = new JPanel(new BorderLayout(8, 2));
        top.setOpaque(false);
        lblSelectedSummary.setFont(lblSelectedSummary.getFont().deriveFont(Font.BOLD));
        lblSelectedSummary.setForeground(AuditTheme.textPrimary(dark));
        top.add(lblSelectedSummary, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(new JLabel("Phiên bản:"));
        cbVariant.setPreferredSize(new Dimension(230, 24));
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

        btnQueueInspector.setEnabled(false);
        btnQueueInspector.setToolTipText("Đưa API đang xem vào hàng đẩy Logger++ (Extender)");
        btnQueueInspector.addActionListener(e -> {
            VariantItem item = (VariantItem) cbVariant.getSelectedItem();
            if (item != null && item.call != null) {
                enqueueCallsWithFeedback(
                    Collections.singletonList(item.call), item.call.displayName(), false);
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
        right.add(btnQueueInspector);
        right.add(btnSendRepeater);
        top.add(right, BorderLayout.EAST);

        txtNotesBanner.setEditable(false);
        txtNotesBanner.setLineWrap(true);
        txtNotesBanner.setWrapStyleWord(true);
        txtNotesBanner.setOpaque(true);
        txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        txtNotesBanner.setForeground(AuditTheme.textPrimary(dark));
        txtNotesBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel northWrap = new JPanel(new BorderLayout(2, 2));
        northWrap.setOpaque(false);
        northWrap.add(top, BorderLayout.NORTH);
        northWrap.add(txtNotesBanner, BorderLayout.SOUTH);
        p.add(northWrap, BorderLayout.NORTH);

        if (requestEditor != null && responseEditor != null) {
            JSplitPane msgSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, requestEditor.uiComponent(), responseEditor.uiComponent());
            msgSplit.setResizeWeight(0.5);
            msgSplit.setOneTouchExpandable(false);
            p.add(msgSplit, BorderLayout.CENTER);
        } else {
            p.add(new JLabel("Montoya HTTP Editor chỉ hiển thị khi chạy trong Burp Suite."), BorderLayout.CENTER);
        }
        return p;
    }

    // =========================================================================
    // Workflow Handlers (Import, Crawl Lifecycle, Export, Logger++ Push)
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
            appendLog("Tiếp tục Crawl (" + (config.isAllowAutoRequests() ? "Auto ON" : "Khóa tự gửi - Thao tác tay") + ")...");
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

        // Strict Permission Gate: NEVER auto-fetch robots.txt/sitemap.xml while locked
        if (!seed.isEmpty() && api != null) {
            if (config.isAllowAutoRequests()) {
                SeedDiscoveryHelper.fetchSeedsAsync(api, seed, config, state, this::appendLog);
            } else {
                appendLog("Tạm giữ Seed URL (chế độ Khóa tự gửi): Extension KHÔNG tự gửi /robots.txt hay /sitemap.xml cho tới khi bạn BẬT 'Tự gửi Request'.");
            }
        }

        mainTabs.setSelectedIndex(TAB_GROUPS);
        appendLog("Crawl RUNNING trên Host(s): " + config.getAllowedHostsCsv()
            + (config.isAllowAutoRequests()
                ? " | Tự gửi Request ĐANG BẬT: Agent tự click & duyệt."
                : " | Khóa tự gửi ĐANG BẬT: Bạn tự bấm trên trình duyệt để gom nhóm API an toàn."));
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
        appendLog("Đã Reset kết quả crawl (giữ nguyên danh sách API Excel, Header Filter & Push Ledger).");
        refreshButtonsState();
        refreshAllViewsNow();
    }

    private void enqueueGroupWithFeedback(ActionGroup group, boolean switchToPushTab) {
        if (group == null) {
            return;
        }
        int added = pushManager.enqueueGroup(group);
        onPushLedgerUpdated();
        appendLog("Đã đưa " + added + " API từ nhóm [" + group.actionPath + "] vào hàng đẩy Logger++ (Extender).");
        if (switchToPushTab) {
            mainTabs.setSelectedIndex(TAB_PUSH);
        }
    }

    private void enqueueCallsWithFeedback(List<ApiCall> calls, String label, boolean switchToPushTab) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        int added = pushManager.enqueueCalls(calls);
        onPushLedgerUpdated();
        appendLog("Đã đưa " + added + " API [" + label + "] vào hàng đẩy Logger++ (Extender).");
        if (switchToPushTab) {
            mainTabs.setSelectedIndex(TAB_PUSH);
        }
    }

    private void inspectPushEntry(ExtenderPushEntry entry) {
        if (entry == null) {
            return;
        }
        ApiCall call = findApiCall(entry.groupId, entry.callIndex);
        if (call != null) {
            showApiCallInInspector(call);
        } else {
            showStandaloneCaptureInInspector(entry.capture);
        }
        setInspectorVisible(true);
    }

    private void showExportMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miCsv = new JMenuItem("Xuất CSV (Nhóm + NOTE + Excel UTF-8)...");
        JMenuItem miJson = new JMenuItem("Xuất JSON (Nhóm + NOTE + Excel)...");
        JMenuItem miMd = new JMenuItem("Xuất Markdown (Cây Nhóm + Bảng NOTE)...");
        JMenuItem miLedger = new JMenuItem("Xuất Bảng Đối Chiếu Đẩy Elastic (CSV)...");

        miCsv.addActionListener(e -> exportReport("csv"));
        miJson.addActionListener(e -> exportReport("json"));
        miMd.addActionListener(e -> exportReport("md"));
        miLedger.addActionListener(e -> exportPushLedgerCsv());

        menu.add(miCsv);
        menu.add(miJson);
        menu.add(miMd);
        menu.addSeparator();
        menu.add(miLedger);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void exportPushLedgerCsv() {
        if (pushManager.totalCount() == 0) {
            JOptionPane.showMessageDialog(this, "Bảng Đối Chiếu đẩy Elastic chưa có dữ liệu.",
                "Trống", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setSelectedFile(new File("mythofcrawl_elastic_ledger.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        lastDirectory = out.getParentFile();
        try {
            pushManager.exportLedgerCsv(out);
            appendLog("Đã xuất Bảng Đối Chiếu đẩy Elastic: " + out.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi xuất Bảng Đối Chiếu: " + ex.getMessage(),
                "Lỗi Export", JOptionPane.ERROR_MESSAGE);
        }
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

    private void refreshPushAndHeaderTitles() {
        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = AuditTheme.panelSurface(dark);

        int pushTotal = pushManager != null ? pushManager.totalCount() : 0;
        int pushSent = pushManager != null ? pushManager.sentCount() : 0;
        if (mainTabs.getTabCount() > TAB_PUSH) {
            mainTabs.setTitleAt(TAB_PUSH, pushSent > 0
                ? String.format("Đẩy Logger++ (%d/%d)", pushSent, pushTotal)
                : String.format("Đẩy Logger++ (%d)", pushTotal));
        }

        HeaderFilterProfile hfp = config.getHeaderFilterProfile();
        int activeRules = 0;
        for (HeaderFilterProfile.Rule r : hfp.getRulesSnapshot()) {
            if (r.enabled) {
                activeRules++;
            }
        }
        if (mainTabs.getTabCount() > TAB_HEADERS) {
            mainTabs.setTitleAt(TAB_HEADERS, hfp.isEnabled()
                ? String.format("Bộ lọc Header HTTP (%d BẬT)", activeRules)
                : String.format("Bộ lọc Header HTTP (%d tắt)", activeRules));
        }
        if (hfp.isEnabled() && activeRules > 0) {
            chipHeaders.setText("Header: " + activeRules + " BẬT");
            AuditTheme.styleStatChip(chipHeaders, AuditTheme.accentForeground(dark), baseBg);
        } else {
            chipHeaders.setText("Header: Tắt");
            AuditTheme.styleStatChip(chipHeaders, AuditTheme.mutedForeground(dark), baseBg);
        }
    }

    private void refreshAllViewsNow() {
        refreshButtonsState();
        refreshFormSummaryLabel();
        refreshPushAndHeaderTitles();

        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = AuditTheme.panelSurface(dark);

        ActionGroupStore store = state.getActionGroups();
        ExcelSpecMatcher.CoverageStats stats = matcher.getCoverageStats();
        int pct = stats.total > 0 ? (int) Math.round(stats.matched * 100.0 / stats.total) : 0;
        progressBar.setValue(pct);
        progressBar.setString(stats.total > 0 ? (pct + "%") : (store.totalCalls() + " API"));

        int errNotes = store.noteCount(AuditNote.Severity.ERROR);
        int warnNotes = store.noteCount(AuditNote.Severity.WARN);
        int infoNotes = store.noteCount(AuditNote.Severity.INFO);
        int totalNotes = errNotes + warnNotes + infoNotes;

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
            (stats.total > 0 && stats.matched == stats.total) ? AuditTheme.okForeground(dark) : AuditTheme.textPrimary(dark),
            baseBg);

        chipPages.setText(String.format("Trang: %d | Q: %d",
            state.getVisitedPagesCount(), state.getPendingQueueSize()));
        AuditTheme.styleStatChip(chipPages, AuditTheme.mutedForeground(dark), baseBg);

        CrawlState.Status st = state.getStatus();
        String gateTag = config.isAllowAutoRequests() ? "Auto" : "Tay/An toàn";
        lblCurrentAction.setText("[" + st.name() + " | " + gateTag + "] " + state.getCurrentActionPath()
            + (state.getCurrentPageUrl().isEmpty() ? "" : ("  (" + state.getCurrentPageUrl() + ")")));

        List<CapturedApiEntry> extras = state.getExtraCaptures();
        List<CrawlState.SkippedAction> skipped = state.getSkippedDangerActions();
        List<String> jsItems = state.getDiscoveredJsEndpoints();

        mainTabs.setTitleAt(TAB_NOTES, String.format("Bảng NOTE (%d)", totalNotes));
        mainTabs.setTitleAt(TAB_COVERAGE, String.format("Tiến độ Excel (%d/%d)", stats.matched, stats.total));
        mainTabs.setTitleAt(TAB_TREE, String.format("Cây Phân cấp (%d)", store.totalCalls()));
        auxTabs.setTitleAt(1, String.format("Ngoài Excel (%d)", extras.size()));
        auxTabs.setTitleAt(2, String.format("Nút bị né & JS (%d/%d)", skipped.size(), jsItems.size()));

        long curVer = store.version();
        boolean storeChanged = curVer != lastStoreVersion || totalNotes != lastNoteCount;
        if (storeChanged) {
            lastStoreVersion = curVer;
            lastNoteCount = totalNotes;

            GroupTableModel.ActionGroupsModel.GroupRow prevGroupRow = getSelectedGroupRow();
            GroupTableModel.GroupApisModel.ApiDisplayRow prevApiRow = getSelectedGroupApiRow();
            AuditNote prevNote = getSelectedNote();

            groupsModel.refresh();
            reselectGroupOrFolder(prevGroupRow);
            applyGroupApisFilter();
            if (prevApiRow != null) {
                if (prevApiRow.call != null) {
                    reselectGroupApi(prevApiRow.call);
                } else if (prevApiRow.isGroupHeader) {
                    reselectPathGroupHeader(prevApiRow.pathKey);
                }
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
        GroupTableModel.ActionGroupsModel.GroupRow prevRow = getSelectedGroupRow();
        int idx = cbGroupFilter.getSelectedIndex();
        String mode = "WITH_APIS";
        if (idx == 1) {
            mode = "ALL";
        } else if (idx == 2) {
            mode = "WITH_NOTES";
        }
        groupsModel.setFilter(mode, txtGroupSearch.getText());
        reselectGroupOrFolder(prevRow);
        applyGroupApisFilter();
    }

    private void applyGroupApisFilter() {
        GroupTableModel.ActionGroupsModel.GroupRow rowMeta = getSelectedGroupRow();
        boolean showAllWhenNull = chkAllApisWhenUnselected.isSelected();
        List<ActionGroup> selectedGroups = Collections.emptyList();
        if (rowMeta != null) {
            selectedGroups = rowMeta.subtreeGroups;
            if (rowMeta.isFolder) {
                lblGroupHeader.setText("Nhánh: " + rowMeta.folderKey + "  ("
                    + rowMeta.subtreeGroups.size() + " nhóm, "
                    + rowMeta.totalApis + " API, " + rowMeta.totalNotes + " NOTE)");
            } else if (rowMeta.directGroup != null) {
                ActionGroup g = rowMeta.directGroup;
                lblGroupHeader.setText("Nhóm: " + g.actionPath + "  (" + g.callCount() + " API, "
                    + g.noteCount() + " NOTE, " + g.getTriggerCount() + " lượt)");
            }
        } else if (showAllWhenNull) {
            lblGroupHeader.setText("Toàn bộ API đã nhóm (" + state.getActionGroups().totalCalls() + " API)");
        } else {
            lblGroupHeader.setText("Chọn một nhóm ở bảng bên trái để xem các API bên trong");
        }
        groupApisModel.setGroupsAndFilter(selectedGroups, showAllWhenNull, chkOnlyApiNotes.isSelected(), txtApiSearch.getText());
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

    private boolean lastBuiltTreeByPath = false;

    private void rebuildHierarchyTreeIfChanged(long curVer) {
        String rootLabel = config.getRouteRootLabel();
        if ((int) curVer == lastTreeVersion
            && treeTabByApiPath == lastBuiltTreeByPath
            && (treeTabByApiPath || rootLabel.equals(treeRoot.getUserObject()))) {
            return;
        }
        lastTreeVersion = (int) curVer;
        lastBuiltTreeByPath = treeTabByApiPath;

        Set<String> expandedKeys = getExpandedDescendantKeys();
        treeRoot.removeAllChildren();

        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();
        if (treeTabByApiPath) {
            treeRoot.setUserObject("API Path Tree (" + state.getActionGroups().totalCalls() + " API)");
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                for (ApiCall call : g.getCalls()) {
                    String normPath = GroupTableModel.normalizeApiGroupPath(call);
                    DefaultMutableTreeNode endpointFolder = resolveApiPathTreeFolder(folderNodes, normPath);
                    endpointFolder.add(new DefaultMutableTreeNode(new TreeCallNode(call, true)));
                }
            }
        } else {
            treeRoot.setUserObject(rootLabel);
            Map<DefaultMutableTreeNode, List<ApiCall>> nodeCalls = new java.util.IdentityHashMap<>();
            List<DefaultMutableTreeNode> orderedFolders = new ArrayList<>();
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                if (g.callCount() == 0) {
                    continue;
                }
                DefaultMutableTreeNode groupParent = resolveTreeFolder(folderNodes, g.actionPath, rootLabel);
                List<ApiCall> list = nodeCalls.get(groupParent);
                if (list == null) {
                    list = new ArrayList<>();
                    nodeCalls.put(groupParent, list);
                    orderedFolders.add(groupParent);
                }
                list.addAll(g.getCalls());
            }
            for (DefaultMutableTreeNode folder : orderedFolders) {
                List<ApiCall> calls = nodeCalls.get(folder);
                Map<String, List<ApiCall>> byPath = new LinkedHashMap<>();
                for (ApiCall call : calls) {
                    byPath.computeIfAbsent(GroupTableModel.normalizeApiGroupPath(call), k -> new ArrayList<>()).add(call);
                }
                for (Map.Entry<String, List<ApiCall>> entry : byPath.entrySet()) {
                    List<ApiCall> bucket = entry.getValue();
                    if (bucket.size() >= 2) {
                        DefaultMutableTreeNode pathGroup = new DefaultMutableTreeNode(
                            "📁 " + entry.getKey() + " (" + bucket.size() + " API)");
                        for (ApiCall call : bucket) {
                            pathGroup.add(new DefaultMutableTreeNode(new TreeCallNode(call, true)));
                        }
                        folder.add(pathGroup);
                    } else {
                        folder.add(new DefaultMutableTreeNode(new TreeCallNode(bucket.get(0), false)));
                    }
                }
            }
        }
        treeModel.reload();
        restoreExpandedKeys(expandedKeys);
    }

    private DefaultMutableTreeNode resolveApiPathTreeFolder(
        Map<String, DefaultMutableTreeNode> folderNodes,
        String normPath
    ) {
        String[] parts = normPath.split("/");
        DefaultMutableTreeNode parent = treeRoot;
        StringBuilder keyBuilder = new StringBuilder();
        boolean addedAny = false;
        for (String rawPart : parts) {
            String seg = rawPart.trim();
            if (seg.isEmpty()) {
                continue;
            }
            addedAny = true;
            keyBuilder.append('/').append(seg);
            String fullKey = keyBuilder.toString();
            DefaultMutableTreeNode node = folderNodes.get(fullKey);
            if (node == null) {
                node = new DefaultMutableTreeNode("/" + seg);
                folderNodes.put(fullKey, node);
                parent.add(node);
            }
            parent = node;
        }
        if (!addedAny) {
            DefaultMutableTreeNode rootPath = folderNodes.get("/");
            if (rootPath == null) {
                rootPath = new DefaultMutableTreeNode("/");
                folderNodes.put("/", rootPath);
                treeRoot.add(rootPath);
            }
            return rootPath;
        }
        return parent;
    }

    private static List<ApiCall> collectTreeCalls(DefaultMutableTreeNode root) {
        List<ApiCall> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        Enumeration<TreeNode> en = root.depthFirstEnumeration();
        while (en.hasMoreElements()) {
            TreeNode tn = en.nextElement();
            if (tn instanceof DefaultMutableTreeNode) {
                Object uo = ((DefaultMutableTreeNode) tn).getUserObject();
                if (uo instanceof TreeCallNode) {
                    out.add(((TreeCallNode) uo).call);
                }
            }
        }
        return out;
    }

    private DefaultMutableTreeNode resolveTreeFolder(
        Map<String, DefaultMutableTreeNode> folderNodes,
        String actionPath,
        String rootLabel
    ) {
        List<String> parts = GroupTableModel.treeSegmentsForActionPath(actionPath);
        DefaultMutableTreeNode parent = treeRoot;
        StringBuilder keyBuilder = new StringBuilder(rootLabel);
        int start = 0;
        if (!parts.isEmpty()) {
            String first = parts.get(0).trim();
            if ("/".equals(first) || (!"/".equals(rootLabel) && first.equalsIgnoreCase(rootLabel))) {
                start = 1;
            }
        }
        for (int i = start; i < parts.size(); i++) {
            String seg = parts.get(i).trim();
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

    private GroupTableModel.ActionGroupsModel.GroupRow getSelectedGroupRow() {
        int vr = tblGroups.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupsModel.getRowAt(tblGroups.convertRowIndexToModel(vr));
    }

    private ActionGroup getSelectedGroup() {
        GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
        return row != null ? row.directGroup : null;
    }

    private void reselectGroupOrFolder(GroupTableModel.ActionGroupsModel.GroupRow prevRow) {
        if (prevRow == null) {
            return;
        }
        if (prevRow.directGroup != null) {
            reselectGroup(prevRow.directGroup.id);
        } else if (prevRow.isFolder && prevRow.folderKey != null) {
            reselectFolderKey(prevRow.folderKey);
        }
    }

    private void reselectFolderKey(String folderKey) {
        if (folderKey == null) {
            return;
        }
        for (int r = 0; r < groupsModel.getRowCount(); r++) {
            GroupTableModel.ActionGroupsModel.GroupRow row = groupsModel.getRowAt(r);
            if (row != null && row.isFolder && folderKey.equals(row.folderKey)) {
                int vr = tblGroups.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblGroups.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
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

    private GroupTableModel.GroupApisModel.ApiDisplayRow getSelectedGroupApiRow() {
        int vr = tblGroupApis.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupApisModel.getRowAt(tblGroupApis.convertRowIndexToModel(vr));
    }

    private ApiCall getSelectedGroupApi() {
        int vr = tblGroupApis.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupApisModel.getCallAt(tblGroupApis.convertRowIndexToModel(vr));
    }

    private ApiCall getRepresentativeSelectedApiCall() {
        GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedGroupApiRow();
        if (row == null) {
            return null;
        }
        return row.call != null ? row.call : pickRepresentativeCall(row.groupedCalls);
    }

    private static ApiCall pickRepresentativeCall(List<ApiCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        for (ApiCall c : calls) {
            if (c.hasNotes()) {
                return c;
            }
        }
        return calls.get(0);
    }

    private void reselectPathGroupHeader(String pathKey) {
        if (pathKey == null) {
            return;
        }
        for (int r = 0; r < groupApisModel.getRowCount(); r++) {
            GroupTableModel.GroupApisModel.ApiDisplayRow row = groupApisModel.getRowAt(r);
            if (row != null && row.isGroupHeader && pathKey.equals(row.pathKey)) {
                int vr = tblGroupApis.convertRowIndexToView(r);
                if (vr >= 0) {
                    tblGroupApis.setRowSelectionInterval(vr, vr);
                }
                break;
            }
        }
    }

    private void reselectGroupApi(ApiCall target) {
        int r = groupApisModel.findRowForCall(target);
        if (r >= 0) {
            int vr = tblGroupApis.convertRowIndexToView(r);
            if (vr >= 0) {
                tblGroupApis.setRowSelectionInterval(vr, vr);
            }
        }
    }

    private static List<ApiCall> collectCallsFromGroups(List<ActionGroup> groups) {
        List<ApiCall> out = new ArrayList<>();
        if (groups == null) {
            return out;
        }
        for (ActionGroup g : groups) {
            if (g != null) {
                out.addAll(g.getCalls());
            }
        }
        return out;
    }

    private void sendCallsToRepeater(List<ApiCall> calls, String label) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        if (calls.size() > 25) {
            int ok = JOptionPane.showConfirmDialog(this,
                "Nhánh/nhóm [" + label + "] chứa " + calls.size() + " API. Gửi toàn bộ sang Repeater?",
                "Xác nhận gửi sang Repeater", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }
        for (ApiCall c : calls) {
            String tag = c.inExcel ? ("STT " + c.excelStt) : ("G" + c.groupId + "#" + c.index);
            sendToRepeater(c.capture, tag + " " + c.capture.method);
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
        ActionGroup targetGroup = state.getActionGroups().getGroup(groupId);
        if (targetGroup != null) {
            groupsModel.ensureGroupVisible(targetGroup);
        }
        groupsModel.refresh();
        mainTabs.setSelectedIndex(TAB_GROUPS);
        setGroupsPaneVisible(true);
        reselectGroup(groupId);
        applyGroupApisFilter();
        ApiCall target = findApiCall(groupId, callIndex);
        if (target != null) {
            groupApisModel.ensureCallVisible(target);
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
        Color baseBg = AuditTheme.panelSurface(dark);

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

        if (!inspectorVisible) {
            String pDisp = cap.requestPathWithQuery != null ? cap.requestPathWithQuery : "";
            if (pDisp.length() > 42) {
                pDisp = pDisp.substring(0, 39) + "...";
            }
            btnToggleInspector.setText("⬓ Hiện HTTP Req/Resp (" + cap.method + " " + pDisp + ")");
        }

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
            txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        }

        btnQueueInspector.setEnabled(call != null);
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
        Color baseBg = AuditTheme.panelSurface(dark);
        currentInspectEndpointKey = "";
        btnShowShared.setEnabled(false);
        btnShowShared.setText("Nhóm dùng chung");
        btnQueueInspector.setEnabled(false);

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
            txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        }
        btnSendRepeater.setEnabled(false);
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    private void clearInspector() {
        boolean dark = AuditTheme.isDark(api, this);
        currentInspectEndpointKey = "";
        lblSelectedSummary.setText("Chọn một API, NOTE hoặc lá cây (double-click hoặc bấm 'Hiện HTTP Req/Resp' để soi gói tin)");
        txtNotesBanner.setText("");
        txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        updatingVariants = true;
        cbVariant.removeAllItems();
        updatingVariants = false;
        btnShowShared.setEnabled(false);
        btnShowShared.setText("Nhóm dùng chung");
        btnQueueInspector.setEnabled(false);
        btnSendRepeater.setEnabled(false);
        if (!inspectorVisible) {
            btnToggleInspector.setText("⬓ Hiện HTTP Req/Resp");
        }
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

    private void attachDoubleClickToRevealInspector(JTable table) {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    setInspectorVisible(true);
                }
            }
        });
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
            boolean dark = AuditTheme.isDark(api, tree);
            setBackgroundNonSelectionColor(AuditTheme.tableSurface(dark));
            if (!sel && value instanceof DefaultMutableTreeNode) {
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                if (uo instanceof TreeCallNode) {
                    TreeCallNode tcn = (TreeCallNode) uo;
                    AuditNote.Severity sev = tcn.call.worstSeverity();
                    setFont(tree.getFont());
                    if (sev != null) {
                        setForeground(AuditTheme.severityForeground(sev, dark));
                    } else if (tcn.call.inExcel) {
                        setForeground(AuditTheme.okForeground(dark));
                    } else {
                        setForeground(AuditTheme.textPrimary(dark));
                    }
                } else {
                    String txt = uo != null ? String.valueOf(uo) : "";
                    setForeground(txt.startsWith("📁 ") ? AuditTheme.accentForeground(dark) : AuditTheme.textPrimary(dark));
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
        final boolean includeGroupName;

        TreeCallNode(ApiCall call, boolean includeGroupName) {
            this.call = call;
            this.includeGroupName = includeGroupName;
        }

        @Override
        public String toString() {
            String tag = call.inExcel
                ? ("[STT " + call.excelStt + "]")
                : ("[G" + call.groupId + "#" + call.index + "]");
            String noteTag = call.hasNotes() ? ("  [NOTE: " + call.notesSummary() + "]") : "";
            String groupTag = includeGroupName ? ("  —  Nhóm: " + call.displayName()) : "";
            return tag + " " + call.capture.method + " " + call.displayPath()
                + "  (" + call.capture.paramCount() + " params, HTTP " + call.capture.statusCode
                + ", x" + call.getHits() + ")" + groupTag + noteTag;
        }
    }
}
