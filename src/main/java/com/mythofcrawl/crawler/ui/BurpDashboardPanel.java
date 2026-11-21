package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ActionStep;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.EventLogEntry;
import com.mythofcrawl.crawler.model.ExtenderPushEntry;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Burp Suite v2026.10.1 Minimalist Master-Detail Dashboard:
 * - Left collapsible "Tasks" sidebar (~265px) with "New crawl" + "+ Nạp Excel",
 *   filter/search, and selectable Task Cards (Live crawl, Excel Spec, Extender Push).
 * - Right (~75%) Detail Workspace with "<" sidebar toggle and underlined sub-tabs:
 *   Summary | Issue activity | Event log | Request / Response.
 * - Zero emojis; uses pure Java 2D vector icons and centered EmptyStatePanels.
 */
public final class BurpDashboardPanel extends JPanel {

    private static final int TASK_CRAWL = 0;
    private static final int TASK_EXCEL = 1;
    private static final int TASK_PUSH = 2;

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private final ExtenderPushManager pushManager;
    private final Supplier<ExcelSpecMatcher> matcherSupplier;
    private final Consumer<Integer> onSwitchMainTab;
    private final BiConsumer<Integer, Integer> onJumpToGroupAndCall;
    private final Consumer<List<ApiCall>> onSendRepeaterCalls;
    private final Consumer<List<ApiCall>> onSendOrganizerCalls;
    private final Consumer<List<ApiCall>> onQueuePushCalls;
    private final Runnable onStartCrawl;
    private final Runnable onTogglePause;
    private final Runnable onStopCrawl;
    private final Runnable onResetCaptures;
    private final Runnable onImportExcel;
    private final Consumer<JButton> onShowExportMenu;
    private final Runnable onConfigMutated;

    // Master-detail split & sidebar state
    private final JSplitPane mainSplitPane;
    private final JPanel sidebarPanel = new JPanel(new BorderLayout(0, 0));
    private final JPanel detailPanel = new JPanel(new BorderLayout(0, 0));
    private boolean sidebarVisible = true;
    private int lastSidebarDivider = 268;
    private int selectedTaskIndex = TASK_CRAWL;
    private String taskFilterMode = "ALL"; // ALL | ACTIVE

    // Sidebar header & controls
    private final JLabel lblTasksHeading = new JLabel("Tasks");
    private final JButton btnSidebarPause;
    private final JButton btnSidebarGear;
    private final JButton btnSidebarHelp;
    private final JButton btnNewCrawl = new JButton("New crawl");
    private final BurpComponents.BurpOrangePillButton btnImportExcel;
    private final JButton btnTaskFilter = new JButton("Filter");
    private final JTextField txtTaskSearch = new JTextField(12);
    private final JPanel tasksStack = new JPanel();
    private final JScrollPane spTasks;

    private final SidebarTaskCard cardCrawl;
    private final SidebarTaskCard cardExcel;
    private final SidebarTaskCard cardPush;

    private final BurpComponents.BurpToggleSwitch swCardCapturing;
    private final BurpComponents.BurpToggleSwitch swCardAutoSend;
    private final BurpComponents.BurpSeverityBadge badgeCrawlErr;
    private final BurpComponents.BurpSeverityBadge badgeCrawlWarn;
    private final BurpComponents.BurpSeverityBadge badgeCrawlInfo;
    private final BurpComponents.BurpSeverityBadge badgeExcelMatched;
    private final BurpComponents.BurpSeverityBadge badgeExcelMissing;
    private final BurpComponents.BurpSeverityBadge badgePushQueued;
    private final BurpComponents.BurpSeverityBadge badgePushSent;
    private final BurpComponents.BurpSeverityBadge badgePushFail;

    // Right detail header & underlined tabs
    private final JPanel detailHeaderBar = new JPanel(new BorderLayout(8, 0));
    private final JButton btnToggleSidebar;
    private final JLabel lblDetailTitle = new JLabel("1. Live crawl & Action mapper");
    private final JLabel lblStatusPill = new JLabel("IDLE");
    private final JButton btnPause = new JButton("Pause");
    private final JButton btnStop = new JButton("Stop");
    private final JButton btnExport = new JButton("Xuất báo cáo");
    private final JTabbedPane detailTabs = new JTabbedPane();

    // Sub-tab 1: Summary
    private final JSplitPane summarySplit;
    private final JPanel summaryLeftPane = new JPanel(new BorderLayout(0, 6));
    private final JLabel lblSummaryApiTitle = new JLabel("Captured APIs & Groups");
    private final JLabel lblSummaryApiCount = new JLabel("(0)");
    private final JLabel lblViewHttpHistory = new JLabel("View HTTP History");
    private final SummaryApiModel summaryApiModel = new SummaryApiModel();
    private final JTable tblSummaryApis = new JTable(summaryApiModel);
    private final JScrollPane spSummaryApis;
    private final JPanel summaryApisCard = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel summaryEmptyState;
    private final boolean[] restoringSummaryApis = {false};

    private final JPanel summaryRightStack = new JPanel();
    private final JScrollPane spSummaryRight;
    private final JPanel cardConfig = new JPanel(new BorderLayout(0, 6));
    private final JPanel cardProgress = new JPanel(new BorderLayout(0, 6));
    private final JPanel cardLog = new JPanel(new BorderLayout(0, 6));

    // Inline configuration fields (kept backing getHostsInput/setHostsInput/getSeedInput)
    private final JTextField txtHosts = new JTextField(16);
    private final JTextField txtSeedUrl = new JTextField(16);
    private final BurpComponents.BurpToggleSwitch swCapturing;
    private final BurpComponents.BurpToggleSwitch swAutoSend;
    private final BurpComponents.BurpToggleSwitch swPassive;
    private final JLabel lblAutoSendState = new JLabel("Tự gửi: KHÓA");

    // Progress metric labels
    private final JLabel valStatus = new JLabel("IDLE");
    private final JLabel valGroups = new JLabel("0");
    private final JLabel valTotalApis = new JLabel("0");
    private final JLabel valPages = new JLabel("0");
    private final JLabel valQueue = new JLabel("0");
    private final JLabel valExcel = new JLabel("0 / 0");
    private final JLabel valPush = new JLabel("0 / 0");
    private final JLabel valNotes = new JLabel("0");
    private final JTextArea txtTaskLogSnippet = new JTextArea(6, 20);
    private final JLabel lblViewAllEvents = new JLabel("View full Event log");

    // Sub-tab 2: Issue activity
    private final JPanel issueFilterBar = new JPanel(new BorderLayout(6, 0));
    private final BurpComponents.BurpFilterPill pillIssHigh;
    private final BurpComponents.BurpFilterPill pillIssMed;
    private final BurpComponents.BurpFilterPill pillIssInfo;
    private final JTextField txtIssueSearch = new JTextField(16);
    private final IssueActivityModel issueModel = new IssueActivityModel();
    private final JTable tblIssues = new JTable(issueModel);
    private final JScrollPane spIssues;
    private final JPanel issuesCardHost = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel issuesEmptyState;
    private final boolean[] restoringIssues = {false};
    private final JPanel issueActionBar = new JPanel(new BorderLayout(6, 2));
    private final JLabel lblIssueSelected = new JLabel("Chọn một Audit NOTE để xem Advisory hoặc mở Request / Response.");
    private final JButton btnIssueLocateWeb = new JButton("Tìm trên Web");
    private final JButton btnIssueInspect = new JButton("Xem Req/Resp");
    private final JButton btnIssueJump = new JButton("HTTP History");

    // Sub-tab 3: Event log
    private final JPanel eventFilterBar = new JPanel(new BorderLayout(6, 0));
    private final BurpComponents.BurpFilterPill pillEvtCrit;
    private final BurpComponents.BurpFilterPill pillEvtErr;
    private final BurpComponents.BurpFilterPill pillEvtInfo;
    private final BurpComponents.BurpFilterPill pillEvtDebug;
    private final BurpComponents.BurpFilterPill pillEvtAgent;
    private final JTextField txtEventSearch = new JTextField(14);
    private final JButton btnClearEvents = new JButton("Xóa Log");
    private final EventLogModel eventModel = new EventLogModel();
    private final JTable tblEvents = new JTable(eventModel);
    private final JScrollPane spEvents;
    private final JPanel eventsCardHost = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel eventsEmptyState;
    private final JTextArea txtEventDetail = new JTextArea(3, 40);
    private final JScrollPane spEventDetail;
    private final boolean[] restoringEvents = {false};
    private long lastEventVersion = -1L;

    // Sub-tab 4: Request / Response + Advisory
    private final JPanel inspectorWrap = new JPanel(new BorderLayout(0, 4));
    private final JPanel advTopBar = new JPanel(new BorderLayout(6, 2));
    private final JLabel lblAdvSummary = new JLabel("Chọn một API hoặc Audit NOTE để xem HTTP Request / Response & Action Trail.");
    private final JButton btnAdvLocateWeb = new JButton("Tìm vị trí trên Web");
    private final JButton btnAdvJump = new JButton("Mở HTTP History");
    private final JButton btnAdvRepeater = new JButton("Repeater");
    private final JButton btnAdvOrganizer = new JButton("Organizer");
    private final JTextArea txtAdvisory = new JTextArea();
    private final JScrollPane spAdvisory;
    private final HttpRequestEditor reqEditor;
    private final HttpResponseEditor respEditor;
    private ApiCall currentAdvisoryCall;
    private boolean syncingSwitches = false;

    public BurpDashboardPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExtenderPushManager pushManager,
        Supplier<ExcelSpecMatcher> matcherSupplier,
        Consumer<Integer> onSwitchMainTab,
        BiConsumer<Integer, Integer> onJumpToGroupAndCall,
        Consumer<List<ApiCall>> onSendRepeaterCalls,
        Consumer<List<ApiCall>> onSendOrganizerCalls,
        Consumer<List<ApiCall>> onQueuePushCalls,
        Runnable onStartCrawl,
        Runnable onTogglePause,
        Runnable onStopCrawl,
        Runnable onResetCaptures,
        Runnable onImportExcel,
        Consumer<JButton> onShowExportMenu,
        Runnable onConfigMutated
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.injector = injector;
        this.pushManager = pushManager;
        this.matcherSupplier = matcherSupplier;
        this.onSwitchMainTab = onSwitchMainTab;
        this.onJumpToGroupAndCall = onJumpToGroupAndCall;
        this.onSendRepeaterCalls = onSendRepeaterCalls;
        this.onSendOrganizerCalls = onSendOrganizerCalls;
        this.onQueuePushCalls = onQueuePushCalls;
        this.onStartCrawl = onStartCrawl;
        this.onTogglePause = onTogglePause;
        this.onStopCrawl = onStopCrawl;
        this.onResetCaptures = onResetCaptures;
        this.onImportExcel = onImportExcel;
        this.onShowExportMenu = onShowExportMenu;
        this.onConfigMutated = onConfigMutated;

        boolean dark = AuditTheme.isDark(api, this);
        Color muted = AuditTheme.mutedForeground(dark);

        this.btnSidebarPause = BurpComponents.createIconButton(api, BurpIcons.pause(muted), "Tạm dừng / Tiếp tục crawl");
        this.btnSidebarGear = BurpComponents.createIconButton(api, BurpIcons.gear(muted), "Cấu hình nhanh Host & Seed URL");
        this.btnSidebarHelp = BurpComponents.createIconButton(api, BurpIcons.helpCircle(muted), "Hướng dẫn sử dụng nhanh");
        this.btnImportExcel = new BurpComponents.BurpOrangePillButton(api, "+ Nạp Excel", null);
        this.btnToggleSidebar = BurpComponents.createIconButton(api, sidebarChevronIcon(true, muted), "Ẩn / Hiện thanh Tasks");

        this.swCardCapturing = new BurpComponents.BurpToggleSwitch(api, injector == null || injector.isAgentArmed());
        this.swCardAutoSend = new BurpComponents.BurpToggleSwitch(api, config.isAllowAutoRequests());
        this.swCapturing = new BurpComponents.BurpToggleSwitch(api, injector == null || injector.isAgentArmed());
        this.swAutoSend = new BurpComponents.BurpToggleSwitch(api, config.isAllowAutoRequests());
        this.swPassive = new BurpComponents.BurpToggleSwitch(api, injector != null && injector.isPassiveCapture());

        this.badgeCrawlErr = new BurpComponents.BurpSeverityBadge("0", AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));
        this.badgeCrawlWarn = new BurpComponents.BurpSeverityBadge("0", AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
        this.badgeCrawlInfo = new BurpComponents.BurpSeverityBadge("0", AuditTheme.severityForeground(AuditNote.Severity.INFO, dark));
        this.badgeExcelMatched = new BurpComponents.BurpSeverityBadge("0", AuditTheme.okForeground(dark));
        this.badgeExcelMissing = new BurpComponents.BurpSeverityBadge("0", muted);
        this.badgePushQueued = new BurpComponents.BurpSeverityBadge("0", AuditTheme.BURP_ACTIVE_PILL_BG);
        this.badgePushSent = new BurpComponents.BurpSeverityBadge("0", AuditTheme.okForeground(dark));
        this.badgePushFail = new BurpComponents.BurpSeverityBadge("0", AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));

        this.cardCrawl = new SidebarTaskCard(api, "1. Live crawl & Action mapper", true,
            () -> { if (onTogglePause != null) onTogglePause.run(); },
            this::showCrawlTaskMenu,
            () -> selectTask(TASK_CRAWL));
        this.cardExcel = new SidebarTaskCard(api, "2. Excel Spec & Coverage", false,
            null,
            this::showExcelTaskMenu,
            () -> selectTask(TASK_EXCEL));
        this.cardPush = new SidebarTaskCard(api, "3. Extender -> Logger++ Push", false,
            null,
            this::showPushTaskMenu,
            () -> selectTask(TASK_PUSH));

        this.spTasks = new JScrollPane(tasksStack);

        this.spSummaryApis = new JScrollPane(tblSummaryApis);
        this.summaryEmptyState = new BurpComponents.EmptyStatePanel(
            api, "No items to show", "Bấm New crawl hoặc mở trình duyệt qua Burp Proxy để ghi nhận API.");
        this.spSummaryRight = new JScrollPane(summaryRightStack);

        this.pillIssHigh = new BurpComponents.BurpFilterPill(api, "High", true);
        this.pillIssMed = new BurpComponents.BurpFilterPill(api, "Medium", true);
        this.pillIssInfo = new BurpComponents.BurpFilterPill(api, "Info", true);
        this.spIssues = new JScrollPane(tblIssues);
        this.issuesEmptyState = new BurpComponents.EmptyStatePanel(
            api, "No items to show", "Chưa có Audit NOTE nào khớp bộ lọc hiện tại.");

        this.pillEvtCrit = new BurpComponents.BurpFilterPill(api, "Critical", true);
        this.pillEvtErr = new BurpComponents.BurpFilterPill(api, "Error", true);
        this.pillEvtInfo = new BurpComponents.BurpFilterPill(api, "Info", true);
        this.pillEvtDebug = new BurpComponents.BurpFilterPill(api, "Debug", false);
        this.pillEvtAgent = new BurpComponents.BurpFilterPill(api, "Agent", false);
        this.spEvents = new JScrollPane(tblEvents);
        this.eventsEmptyState = new BurpComponents.EmptyStatePanel(
            api, "No items to show", "Chưa có sự kiện nào trong nhật ký.");
        this.spEventDetail = new JScrollPane(txtEventDetail);

        this.spAdvisory = new JScrollPane(txtAdvisory);
        this.reqEditor = api != null
            ? api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY) : null;
        this.respEditor = api != null
            ? api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY) : null;

        this.summarySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, summaryLeftPane, spSummaryRight);
        this.summarySplit.setResizeWeight(0.68);
        this.summarySplit.setOneTouchExpandable(false);
        this.summarySplit.setBorder(BorderFactory.createEmptyBorder());

        buildSidebar();
        buildDetailWorkspace();

        this.mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, detailPanel);
        this.mainSplitPane.setResizeWeight(0.24);
        this.mainSplitPane.setDividerLocation(268);
        this.mainSplitPane.setOneTouchExpandable(false);
        this.mainSplitPane.setBorder(BorderFactory.createEmptyBorder());

        setLayout(new BorderLayout(0, 0));
        add(mainSplitPane, BorderLayout.CENTER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = getWidth();
                if (w > 0 && w < 920) {
                    if (summarySplit.getOrientation() != JSplitPane.VERTICAL_SPLIT) {
                        summarySplit.setOrientation(JSplitPane.VERTICAL_SPLIT);
                        summarySplit.setDividerLocation(0.55);
                    }
                } else if (w >= 920) {
                    if (summarySplit.getOrientation() != JSplitPane.HORIZONTAL_SPLIT) {
                        summarySplit.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
                        summarySplit.setDividerLocation(0.68);
                    }
                }
            }
        });

        applyTheme();
        refreshAll();
    }

    private void buildSidebar() {
        sidebarPanel.setPreferredSize(new Dimension(268, 400));
        sidebarPanel.setMinimumSize(new Dimension(215, 240));

        // Row 1: Tasks title + subtle icons
        JPanel headRow = new JPanel(new BorderLayout(6, 0));
        headRow.setOpaque(false);
        headRow.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 8));

        JPanel headIcons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        headIcons.setOpaque(false);
        btnSidebarPause.addActionListener(e -> { if (onTogglePause != null) onTogglePause.run(); });
        btnSidebarGear.addActionListener(e -> showQuickConfigDialog());
        btnSidebarHelp.addActionListener(e -> showHelpDialog());
        headIcons.add(btnSidebarPause);
        headIcons.add(btnSidebarGear);
        headIcons.add(btnSidebarHelp);

        headRow.add(lblTasksHeading, BorderLayout.WEST);
        headRow.add(headIcons, BorderLayout.EAST);

        // Row 2: 2 compact 6px-rounded action buttons ("New crawl" + "+ Nạp Excel")
        JPanel ctaRow = new JPanel(new GridLayout(1, 2, 6, 0));
        ctaRow.setOpaque(false);
        ctaRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));

        btnNewCrawl.addActionListener(e -> {
            commitHostField();
            if (onStartCrawl != null) {
                onStartCrawl.run();
            }
        });
        btnImportExcel.addActionListener(e -> {
            if (onImportExcel != null) {
                onImportExcel.run();
            }
        });
        ctaRow.add(btnNewCrawl);
        ctaRow.add(btnImportExcel);

        // Row 3: Filter dropdown + Search field
        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));

        btnTaskFilter.setHorizontalTextPosition(SwingConstants.LEFT);
        btnTaskFilter.setIconTextGap(4);
        btnTaskFilter.addActionListener(e -> showTaskFilterPopup());

        AuditTheme.styleSearchField(txtTaskSearch, "Search tasks");
        txtTaskSearch.getDocument().addDocumentListener(new SimpleDocListener(this::applyTaskSidebarFilter));

        filterRow.add(btnTaskFilter, BorderLayout.WEST);
        filterRow.add(txtTaskSearch, BorderLayout.CENTER);

        JPanel sidebarNorth = new JPanel();
        sidebarNorth.setOpaque(false);
        sidebarNorth.setLayout(new BoxLayout(sidebarNorth, BoxLayout.Y_AXIS));
        sidebarNorth.add(headRow);
        sidebarNorth.add(ctaRow);
        sidebarNorth.add(filterRow);

        // Cards inside stack
        cardCrawl.addSwitchRow("Capturing", swCardCapturing);
        cardCrawl.addSwitchRow("Auto-Send", swCardAutoSend);
        cardCrawl.addBadge(badgeCrawlErr);
        cardCrawl.addBadge(badgeCrawlWarn);
        cardCrawl.addBadge(badgeCrawlInfo);

        cardExcel.addBadge(badgeExcelMatched);
        cardExcel.addBadge(badgeExcelMissing);

        cardPush.addBadge(badgePushQueued);
        cardPush.addBadge(badgePushSent);
        cardPush.addBadge(badgePushFail);

        swCardCapturing.addActionListener(e -> {
            if (!syncingSwitches) {
                setAgentArmed(swCardCapturing.isSelected());
            }
        });
        swCardAutoSend.addActionListener(e -> {
            if (!syncingSwitches) {
                setAutoRequests(swCardAutoSend.isSelected());
            }
        });

        tasksStack.setLayout(new BoxLayout(tasksStack, BoxLayout.Y_AXIS));
        tasksStack.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
        tasksStack.add(cardCrawl);
        tasksStack.add(Box.createVerticalStrut(6));
        tasksStack.add(cardExcel);
        tasksStack.add(Box.createVerticalStrut(6));
        tasksStack.add(cardPush);
        tasksStack.add(Box.createVerticalGlue());

        spTasks.getVerticalScrollBar().setUnitIncrement(14);
        sidebarPanel.add(sidebarNorth, BorderLayout.NORTH);
        sidebarPanel.add(spTasks, BorderLayout.CENTER);
    }

    private void buildDetailWorkspace() {
        // Detail top bar: "< 1. Live crawl & Action mapper" + status pill + actions
        detailHeaderBar.setBorder(BorderFactory.createEmptyBorder(7, 10, 6, 10));

        btnToggleSidebar.addActionListener(e -> toggleSidebar());

        JPanel titleLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLeft.setOpaque(false);
        titleLeft.add(btnToggleSidebar);
        titleLeft.add(lblDetailTitle);
        titleLeft.add(lblStatusPill);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerRight.setOpaque(false);
        btnPause.addActionListener(e -> { if (onTogglePause != null) onTogglePause.run(); });
        btnStop.addActionListener(e -> { if (onStopCrawl != null) onStopCrawl.run(); });
        btnExport.setHorizontalTextPosition(SwingConstants.LEFT);
        btnExport.setIconTextGap(5);
        btnExport.addActionListener(e -> {
            if (onShowExportMenu != null) {
                onShowExportMenu.accept(btnExport);
            }
        });
        headerRight.add(btnPause);
        headerRight.add(btnStop);
        headerRight.add(btnExport);

        detailHeaderBar.add(titleLeft, BorderLayout.WEST);
        detailHeaderBar.add(headerRight, BorderLayout.EAST);

        buildSummaryTab();
        buildIssuesTab();
        buildEventsTab();
        buildInspectorTab();

        JPanel summaryWrap = new JPanel(new BorderLayout(0, 0));
        summaryWrap.setOpaque(false);
        summaryWrap.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        summaryWrap.add(summarySplit, BorderLayout.CENTER);

        JPanel issuesWrap = new JPanel(new BorderLayout(0, 6));
        issuesWrap.setOpaque(false);
        issuesWrap.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        issuesWrap.add(issueFilterBar, BorderLayout.NORTH);
        issuesWrap.add(issuesCardHost, BorderLayout.CENTER);
        issuesWrap.add(issueActionBar, BorderLayout.SOUTH);

        JPanel eventsWrap = new JPanel(new BorderLayout(0, 6));
        eventsWrap.setOpaque(false);
        eventsWrap.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        eventsWrap.add(eventFilterBar, BorderLayout.NORTH);
        eventsWrap.add(eventsCardHost, BorderLayout.CENTER);
        eventsWrap.add(spEventDetail, BorderLayout.SOUTH);

        detailTabs.addTab("Summary", summaryWrap);
        detailTabs.addTab("Issue activity", issuesWrap);
        detailTabs.addTab("Event log", eventsWrap);
        detailTabs.addTab("Request / Response", inspectorWrap);

        detailPanel.add(detailHeaderBar, BorderLayout.NORTH);
        detailPanel.add(detailTabs, BorderLayout.CENTER);
    }

    private void buildSummaryTab() {
        // Left: Captured APIs & Groups + View HTTP History underlined orange link
        JPanel apisHeader = new JPanel(new BorderLayout(6, 0));
        apisHeader.setOpaque(false);
        JPanel apisTitleLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        apisTitleLeft.setOpaque(false);
        apisTitleLeft.add(lblSummaryApiTitle);
        apisTitleLeft.add(lblSummaryApiCount);

        lblViewHttpHistory.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblViewHttpHistory.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                switchTab(2);
            }
        });
        apisHeader.add(apisTitleLeft, BorderLayout.WEST);
        apisHeader.add(lblViewHttpHistory, BorderLayout.EAST);

        AuditTheme.configureAuditTable(tblSummaryApis, api);
        tblSummaryApis.setAutoCreateRowSorter(true);
        tblSummaryApis.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        tblSummaryApis.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblSummaryApis.setDefaultRenderer(Object.class, new SummaryApiRenderer());
        tblSummaryApis.getColumnModel().getColumn(0).setPreferredWidth(165);
        tblSummaryApis.getColumnModel().getColumn(1).setPreferredWidth(68);
        tblSummaryApis.getColumnModel().getColumn(2).setPreferredWidth(280);
        tblSummaryApis.getColumnModel().getColumn(3).setPreferredWidth(54);
        tblSummaryApis.getColumnModel().getColumn(4).setPreferredWidth(64);

        tblSummaryApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringSummaryApis[0]) {
                ApiCall call = getSelectedSummaryCall();
                if (call != null) {
                    showCallInAdvisory(call, null);
                }
            }
        });
        tblSummaryApis.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int r = tblSummaryApis.rowAtPoint(e.getPoint());
                    if (r >= 0) {
                        tblSummaryApis.setRowSelectionInterval(r, r);
                        showCallPopupMenu(e.getComponent(), e.getX(), e.getY(), getSelectedSummaryCall());
                    }
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ApiCall c = getSelectedSummaryCall();
                    if (c != null && onJumpToGroupAndCall != null) {
                        onJumpToGroupAndCall.accept(c.groupId, c.index);
                    }
                }
            }
        });

        summaryApisCard.add(summaryEmptyState, "EMPTY");
        summaryApisCard.add(spSummaryApis, "TABLE");

        summaryLeftPane.setOpaque(false);
        summaryLeftPane.add(apisHeader, BorderLayout.NORTH);
        summaryLeftPane.add(summaryApisCard, BorderLayout.CENTER);

        // Right: 3 clean bordered cards (Task configuration, Task progress, Task log)
        buildSummaryConfigCard();
        buildSummaryProgressCard();
        buildSummaryLogCard();

        summaryRightStack.setLayout(new BoxLayout(summaryRightStack, BoxLayout.Y_AXIS));
        summaryRightStack.setOpaque(false);
        summaryRightStack.add(cardConfig);
        summaryRightStack.add(Box.createVerticalStrut(8));
        summaryRightStack.add(cardProgress);
        summaryRightStack.add(Box.createVerticalStrut(8));
        summaryRightStack.add(cardLog);
        summaryRightStack.add(Box.createVerticalGlue());

        spSummaryRight.setMinimumSize(new Dimension(245, 200));
        spSummaryRight.setPreferredSize(new Dimension(280, 400));
        spSummaryRight.getVerticalScrollBar().setUnitIncrement(14);
    }

    private void buildSummaryConfigCard() {
        AuditTheme.styleSearchField(txtHosts, "Host(s) hoặc *");
        txtHosts.setText(config.getAllowedHostsCsv());
        txtHosts.addActionListener(e -> commitHostField());

        AuditTheme.styleSearchField(txtSeedUrl, "Seed URL (https://...)");

        swCapturing.addActionListener(e -> {
            if (!syncingSwitches) {
                setAgentArmed(swCapturing.isSelected());
            }
        });
        swAutoSend.addActionListener(e -> {
            if (!syncingSwitches) {
                setAutoRequests(swAutoSend.isSelected());
            }
        });
        swPassive.addActionListener(e -> {
            if (!syncingSwitches && injector != null) {
                injector.setPassiveCapture(swPassive.isSelected());
                state.addEventLog(EventLogEntry.Level.INFO, "Agent",
                    swPassive.isSelected() ? "Chuyển chế độ Bắt thụ động." : "Chuyển chế độ Chủ động.");
                refreshAll();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 0, 3, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        form.add(new JLabel("Host(s):"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(txtHosts, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        form.add(new JLabel("Seed URL:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(txtSeedUrl, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        form.add(new JLabel("Capturing:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(wrapSwitchRow(swCapturing, new JLabel("Bơm JS Agent")), gc);

        gc.gridx = 0; gc.gridy = 3; gc.weightx = 0;
        form.add(new JLabel("Auto-Send:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(wrapSwitchRow(swAutoSend, lblAutoSendState), gc);

        gc.gridx = 0; gc.gridy = 4; gc.weightx = 0;
        form.add(new JLabel("Chế độ:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(wrapSwitchRow(swPassive, new JLabel("Bắt thụ động")), gc);

        JLabel title = new JLabel("Task configuration");
        title.setName("cardTitle");
        cardConfig.add(title, BorderLayout.NORTH);
        cardConfig.add(form, BorderLayout.CENTER);
    }

    private void buildSummaryProgressCard() {
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 4));
        grid.setOpaque(false);
        addKvRow(grid, "Trạng thái:", valStatus);
        addKvRow(grid, "Action Groups:", valGroups);
        addKvRow(grid, "API bắt được:", valTotalApis);
        addKvRow(grid, "Trang đã ghé:", valPages);
        addKvRow(grid, "Queue chờ:", valQueue);
        addKvRow(grid, "Excel khớp:", valExcel);
        addKvRow(grid, "Logger++ đẩy:", valPush);
        addKvRow(grid, "Audit NOTEs:", valNotes);

        JLabel title = new JLabel("Task progress");
        title.setName("cardTitle");
        cardProgress.add(title, BorderLayout.NORTH);
        cardProgress.add(grid, BorderLayout.CENTER);
    }

    private void buildSummaryLogCard() {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Task log");
        title.setName("cardTitle");
        lblViewAllEvents.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblViewAllEvents.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                detailTabs.setSelectedIndex(2);
            }
        });
        header.add(title, BorderLayout.WEST);
        header.add(lblViewAllEvents, BorderLayout.EAST);

        txtTaskLogSnippet.setEditable(false);
        txtTaskLogSnippet.setLineWrap(true);
        txtTaskLogSnippet.setWrapStyleWord(true);
        txtTaskLogSnippet.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        cardLog.add(header, BorderLayout.NORTH);
        cardLog.add(txtTaskLogSnippet, BorderLayout.CENTER);
    }

    private void buildIssuesTab() {
        pillIssHigh.addActionListener(e -> refreshIssuesTable());
        pillIssMed.addActionListener(e -> refreshIssuesTable());
        pillIssInfo.addActionListener(e -> refreshIssuesTable());
        AuditTheme.styleSearchField(txtIssueSearch, "Tìm endpoint, loại NOTE, action path...");
        txtIssueSearch.getDocument().addDocumentListener(new SimpleDocListener(this::refreshIssuesTable));

        JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        pills.setOpaque(false);
        pills.add(pillIssHigh);
        pills.add(pillIssMed);
        pills.add(pillIssInfo);

        issueFilterBar.setOpaque(false);
        issueFilterBar.add(pills, BorderLayout.WEST);
        issueFilterBar.add(txtIssueSearch, BorderLayout.CENTER);

        AuditTheme.configureAuditTable(tblIssues, api);
        tblIssues.setAutoCreateRowSorter(true);
        tblIssues.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        tblIssues.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblIssues.setDefaultRenderer(Object.class, new IssueRenderer());
        tblIssues.getColumnModel().getColumn(0).setPreferredWidth(80);
        tblIssues.getColumnModel().getColumn(1).setPreferredWidth(135);
        tblIssues.getColumnModel().getColumn(2).setPreferredWidth(260);
        tblIssues.getColumnModel().getColumn(3).setPreferredWidth(210);

        tblIssues.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringIssues[0]) {
                AuditNote note = getSelectedIssue();
                if (note != null) {
                    showNoteInAdvisory(note);
                }
            }
        });
        tblIssues.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblIssues.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        tblIssues.setRowSelectionInterval(row, row);
                        showCallPopupMenu(e.getComponent(), e.getX(), e.getY(), getSelectedIssueCall());
                    }
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    AuditNote n = getSelectedIssue();
                    if (n != null && onJumpToGroupAndCall != null) {
                        onJumpToGroupAndCall.accept(n.groupId, n.callIndex);
                    }
                }
            }
        });

        issuesCardHost.add(issuesEmptyState, "EMPTY");
        issuesCardHost.add(spIssues, "TABLE");

        btnIssueLocateWeb.setEnabled(false);
        btnIssueInspect.setEnabled(false);
        btnIssueJump.setEnabled(false);

        btnIssueLocateWeb.addActionListener(e -> {
            ApiCall c = getSelectedIssueCall();
            WebLocatorHelper.showLauncherMenu(btnIssueLocateWeb, state, c, null);
        });
        btnIssueInspect.addActionListener(e -> {
            AuditNote n = getSelectedIssue();
            if (n != null) {
                showNoteInAdvisory(n);
                detailTabs.setSelectedIndex(3);
            }
        });
        btnIssueJump.addActionListener(e -> {
            AuditNote n = getSelectedIssue();
            if (n != null && onJumpToGroupAndCall != null) {
                onJumpToGroupAndCall.accept(n.groupId, n.callIndex);
            }
        });

        JPanel issueBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        issueBtns.setOpaque(false);
        issueBtns.add(btnIssueLocateWeb);
        issueBtns.add(btnIssueInspect);
        issueBtns.add(btnIssueJump);

        issueActionBar.setOpaque(false);
        issueActionBar.add(lblIssueSelected, BorderLayout.CENTER);
        issueActionBar.add(issueBtns, BorderLayout.EAST);
    }

    private void buildEventsTab() {
        pillEvtCrit.addActionListener(e -> refreshEventsTable());
        pillEvtErr.addActionListener(e -> refreshEventsTable());
        pillEvtInfo.addActionListener(e -> refreshEventsTable());
        pillEvtDebug.addActionListener(e -> refreshEventsTable());
        pillEvtAgent.addActionListener(e -> refreshEventsTable());
        AuditTheme.styleSearchField(txtEventSearch, "Tìm trong event log...");
        txtEventSearch.getDocument().addDocumentListener(new SimpleDocListener(this::refreshEventsTable));

        btnClearEvents.addActionListener(e -> {
            state.clearEventLogs();
            lastEventVersion = -1L;
            refreshEventsTable();
            updateTaskLogSnippet();
        });

        JPanel leftPills = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 2));
        leftPills.setOpaque(false);
        leftPills.add(pillEvtCrit);
        leftPills.add(pillEvtErr);
        leftPills.add(pillEvtInfo);
        leftPills.add(pillEvtDebug);
        leftPills.add(pillEvtAgent);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        rightControls.setOpaque(false);
        rightControls.add(txtEventSearch);
        rightControls.add(btnClearEvents);

        eventFilterBar.setOpaque(false);
        eventFilterBar.add(leftPills, BorderLayout.CENTER);
        eventFilterBar.add(rightControls, BorderLayout.EAST);

        AuditTheme.configureAuditTable(tblEvents, api);
        tblEvents.setAutoCreateRowSorter(true);
        tblEvents.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        tblEvents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblEvents.setDefaultRenderer(Object.class, new EventLogRenderer());
        tblEvents.getColumnModel().getColumn(0).setPreferredWidth(76);
        tblEvents.getColumnModel().getColumn(1).setPreferredWidth(78);
        tblEvents.getColumnModel().getColumn(2).setPreferredWidth(76);
        tblEvents.getColumnModel().getColumn(3).setPreferredWidth(460);

        tblEvents.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringEvents[0]) {
                int vr = tblEvents.getSelectedRow();
                if (vr >= 0 && vr < tblEvents.getRowCount()) {
                    EventLogEntry en = eventModel.getEntryAt(tblEvents.convertRowIndexToModel(vr));
                    if (en != null) {
                        showEventEntryInDetail(en);
                    }
                }
            }
        });

        eventsCardHost.add(eventsEmptyState, "EMPTY");
        eventsCardHost.add(spEvents, "TABLE");

        txtEventDetail.setEditable(false);
        txtEventDetail.setLineWrap(true);
        txtEventDetail.setWrapStyleWord(true);
        txtEventDetail.setText("Chọn một dòng Event log để xem đầy đủ nội dung.");
        txtEventDetail.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    private void buildInspectorTab() {
        btnAdvLocateWeb.setEnabled(false);
        btnAdvJump.setEnabled(false);
        btnAdvRepeater.setEnabled(false);
        btnAdvOrganizer.setEnabled(false);

        btnAdvLocateWeb.addActionListener(e -> WebLocatorHelper.showLauncherMenu(btnAdvLocateWeb, state, currentAdvisoryCall, null));
        btnAdvJump.addActionListener(e -> {
            if (currentAdvisoryCall != null && onJumpToGroupAndCall != null) {
                onJumpToGroupAndCall.accept(currentAdvisoryCall.groupId, currentAdvisoryCall.index);
            }
        });
        btnAdvRepeater.addActionListener(e -> {
            if (currentAdvisoryCall != null && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(Collections.singletonList(currentAdvisoryCall));
            }
        });
        btnAdvOrganizer.addActionListener(e -> {
            if (currentAdvisoryCall != null && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(Collections.singletonList(currentAdvisoryCall));
            }
        });

        JPanel advActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        advActions.setOpaque(false);
        advActions.add(btnAdvLocateWeb);
        advActions.add(btnAdvJump);
        advActions.add(btnAdvRepeater);
        advActions.add(btnAdvOrganizer);

        advTopBar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        advTopBar.add(lblAdvSummary, BorderLayout.CENTER);
        advTopBar.add(advActions, BorderLayout.EAST);

        txtAdvisory.setEditable(false);
        txtAdvisory.setLineWrap(true);
        txtAdvisory.setWrapStyleWord(true);
        txtAdvisory.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        txtAdvisory.setText("Chọn một API hoặc Audit NOTE trong Summary / Issue activity để xem Action Trail và HTTP Request / Response.");

        inspectorWrap.setOpaque(false);
        inspectorWrap.add(advTopBar, BorderLayout.NORTH);

        if (reqEditor != null && respEditor != null) {
            JSplitPane editorsSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, reqEditor.uiComponent(), respEditor.uiComponent());
            editorsSplit.setResizeWeight(0.5);
            editorsSplit.setBorder(BorderFactory.createEmptyBorder());

            JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorsSplit, spAdvisory);
            outer.setResizeWeight(0.72);
            outer.setBorder(BorderFactory.createEmptyBorder());
            inspectorWrap.add(outer, BorderLayout.CENTER);
        } else {
            inspectorWrap.add(spAdvisory, BorderLayout.CENTER);
        }
    }

    private void showCallPopupMenu(Component invoker, int x, int y, ApiCall call) {
        if (call == null) {
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miJump = new JMenuItem("Mở trong HTTP History");
        JMenuItem miInspect = new JMenuItem("Xem Request / Response");
        JMenuItem miRep = new JMenuItem("Send to Repeater");
        JMenuItem miOrg = new JMenuItem("Send to Organizer");
        JMenuItem miPush = new JMenuItem("+ Đưa vào hàng đẩy Logger++");

        miJump.addActionListener(e -> {
            if (onJumpToGroupAndCall != null) {
                onJumpToGroupAndCall.accept(call.groupId, call.index);
            }
        });
        miInspect.addActionListener(e -> {
            showCallInAdvisory(call, null);
            detailTabs.setSelectedIndex(3);
        });
        miRep.addActionListener(e -> {
            if (onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(Collections.singletonList(call));
            }
        });
        miOrg.addActionListener(e -> {
            if (onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(Collections.singletonList(call));
            }
        });
        miPush.addActionListener(e -> {
            if (onQueuePushCalls != null) {
                onQueuePushCalls.accept(Collections.singletonList(call));
            }
        });

        popup.add(miJump);
        popup.add(miInspect);
        popup.addSeparator();
        popup.add(miRep);
        popup.add(miOrg);
        popup.add(miPush);
        popup.add(WebLocatorHelper.createSubMenuForCall(this, state, () -> call, null));
        popup.show(invoker, x, y);
    }

    private void showTaskFilterPopup() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miAll = new JMenuItem("Tất cả Tasks (3)");
        JMenuItem miActive = new JMenuItem("Chỉ Task đang hoạt động / có dữ liệu");
        miAll.addActionListener(e -> {
            taskFilterMode = "ALL";
            btnTaskFilter.setText("Filter");
            applyTaskSidebarFilter();
        });
        miActive.addActionListener(e -> {
            taskFilterMode = "ACTIVE";
            btnTaskFilter.setText("Filter: Active");
            applyTaskSidebarFilter();
        });
        m.add(miAll);
        m.add(miActive);
        m.show(btnTaskFilter, 0, btnTaskFilter.getHeight());
    }

    private void showCrawlTaskMenu(Component invoker) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miPause = new JMenuItem(state.getStatus() == CrawlState.Status.PAUSED ? "Tiếp tục Crawl" : "Tạm dừng Crawl");
        JMenuItem miStop = new JMenuItem("Dừng Crawl");
        JMenuItem miCfg = new JMenuItem("Cấu hình Host & Seed...");
        JMenuItem miHistory = new JMenuItem("Mở HTTP History");
        JMenuItem miReset = new JMenuItem("Reset dữ liệu Crawl");
        miPause.addActionListener(e -> { if (onTogglePause != null) onTogglePause.run(); });
        miStop.addActionListener(e -> { if (onStopCrawl != null) onStopCrawl.run(); });
        miCfg.addActionListener(e -> showQuickConfigDialog());
        miHistory.addActionListener(e -> switchTab(2));
        miReset.addActionListener(e -> { if (onResetCaptures != null) onResetCaptures.run(); });
        m.add(miPause);
        m.add(miStop);
        m.addSeparator();
        m.add(miCfg);
        m.add(miHistory);
        m.addSeparator();
        m.add(miReset);
        m.show(invoker, 0, invoker.getHeight());
    }

    private void showExcelTaskMenu(Component invoker) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miImp = new JMenuItem("Nạp file Excel mới...");
        JMenuItem miTab = new JMenuItem("Mở tab Excel Coverage");
        miImp.addActionListener(e -> { if (onImportExcel != null) onImportExcel.run(); });
        miTab.addActionListener(e -> switchTab(1));
        m.add(miImp);
        m.add(miTab);
        m.show(invoker, 0, invoker.getHeight());
    }

    private void showPushTaskMenu(Component invoker) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miTab = new JMenuItem("Mở tab Extender -> Logger++ Push");
        miTab.addActionListener(e -> switchTab(3));
        m.add(miTab);
        m.show(invoker, 0, invoker.getHeight());
    }

    private void showQuickConfigDialog() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        JTextField fHosts = new JTextField(txtHosts.getText(), 24);
        JTextField fSeed = new JTextField(txtSeedUrl.getText(), 24);
        gc.gridx = 0; gc.gridy = 0; p.add(new JLabel("Allowed Host(s):"), gc);
        gc.gridx = 1; p.add(fHosts, gc);
        gc.gridx = 0; gc.gridy = 1; p.add(new JLabel("Seed URL:"), gc);
        gc.gridx = 1; p.add(fSeed, gc);
        int res = JOptionPane.showConfirmDialog(this, p, "Cấu hình Crawl nhanh", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            txtHosts.setText(fHosts.getText());
            txtSeedUrl.setText(fSeed.getText());
            commitHostField();
        }
    }

    private void showHelpDialog() {
        JOptionPane.showMessageDialog(
            this,
            "Dashboard (Burp v2026.10.1 Layout):\n"
                + "• Bấm 'New crawl' để chạy từ Seed URL hoặc duyệt web thủ công qua Burp Proxy.\n"
                + "• Bấm '+ Nạp Excel' để đối chiếu các API trong đặc tả với dữ liệu bắt được.\n"
                + "• Bật Capturing để Agent tự gắn nhóm theo bước thao tác UI (Action Trail).\n"
                + "• Auto-Send mặc định KHÓA (an toàn): bật lên nếu muốn tự mở seed/replay.\n"
                + "• Bấm chuột phải vào bất kỳ API nào -> 'Tìm vị trí trên Web' để mở Firefox/Chrome Spotlight.",
            "Hướng dẫn Dashboard",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        if (sidebarVisible) {
            sidebarPanel.setVisible(true);
            mainSplitPane.setDividerSize(5);
            mainSplitPane.setDividerLocation(Math.max(230, lastSidebarDivider));
        } else {
            int cur = mainSplitPane.getDividerLocation();
            if (cur > 180) {
                lastSidebarDivider = cur;
            }
            sidebarPanel.setVisible(false);
            mainSplitPane.setDividerSize(0);
            mainSplitPane.setDividerLocation(0);
        }
        boolean dark = AuditTheme.isDark(api, this);
        btnToggleSidebar.setIcon(sidebarChevronIcon(sidebarVisible, AuditTheme.mutedForeground(dark)));
        revalidate();
        repaint();
    }

    private void selectTask(int idx) {
        this.selectedTaskIndex = idx;
        cardCrawl.setSelected(idx == TASK_CRAWL);
        cardExcel.setSelected(idx == TASK_EXCEL);
        cardPush.setSelected(idx == TASK_PUSH);
        if (idx == TASK_CRAWL) {
            lblDetailTitle.setText("1. Live crawl & Action mapper");
        } else if (idx == TASK_EXCEL) {
            lblDetailTitle.setText("2. Excel Spec & Coverage");
        } else {
            lblDetailTitle.setText("3. Extender -> Logger++ Push");
        }
    }

    private void applyTaskSidebarFilter() {
        String q = txtTaskSearch.getText().trim().toLowerCase(Locale.ROOT);
        boolean activeOnly = "ACTIVE".equals(taskFilterMode);

        boolean showCrawl = q.isEmpty() || "live crawl action mapper".contains(q);
        boolean showExcel = q.isEmpty() || "excel spec coverage".contains(q);
        boolean showPush = q.isEmpty() || "extender logger++ push".contains(q);

        if (activeOnly) {
            showCrawl &= (state.getStatus() == CrawlState.Status.RUNNING || state.getActionGroups().totalCalls() > 0);
            ExcelSpecMatcher m = matcherSupplier != null ? matcherSupplier.get() : null;
            showExcel &= (m != null && m.getCoverageStats().total > 0);
            showPush &= (pushManager != null && pushManager.totalCount() > 0);
        }

        cardCrawl.setVisible(showCrawl);
        cardExcel.setVisible(showExcel);
        cardPush.setVisible(showPush);
        tasksStack.revalidate();
        tasksStack.repaint();
    }

    private void commitHostField() {
        config.setAllowedHostsCsv(txtHosts.getText());
        if (onConfigMutated != null) {
            onConfigMutated.run();
        }
    }

    private void setAgentArmed(boolean armed) {
        if (injector != null) {
            injector.setAgentArmed(armed);
            state.addEventLog(EventLogEntry.Level.INFO, "Agent",
                armed ? "Đã BẬT Capturing (bơm Agent)." : "Đã TẮT Capturing (chỉ nghe HTTP).");
            refreshAll();
        }
    }

    private void setAutoRequests(boolean allowed) {
        config.setAllowAutoRequests(allowed);
        state.addEventLog(EventLogEntry.Level.INFO, "Suite",
            allowed
                ? "Đã BẬT Tự gửi Request: Agent được phép tự click & fetch Seed."
                : "Đã KHÓA Tự gửi Request: Chế độ an toàn (chỉ ghi nhận tương tác tay).");
        if (onConfigMutated != null) {
            onConfigMutated.run();
        }
        refreshAll();
    }

    private void switchTab(int idx) {
        if (onSwitchMainTab != null) {
            onSwitchMainTab.accept(idx);
        }
    }

    public String getHostsInput() {
        return txtHosts.getText();
    }

    public void setHostsInput(String hosts) {
        txtHosts.setText(hosts != null ? hosts : "");
    }

    public String getSeedInput() {
        return txtSeedUrl.getText();
    }

    public void setSeedInput(String seed) {
        txtSeedUrl.setText(seed != null ? seed : "");
    }

    public void refreshAll() {
        syncingSwitches = true;
        try {
            boolean allowAuto = config.isAllowAutoRequests();
            boolean armed = injector == null || injector.isAgentArmed();
            swAutoSend.setSelected(allowAuto);
            swCardAutoSend.setSelected(allowAuto);
            swCapturing.setSelected(armed);
            swCardCapturing.setSelected(armed);
            swPassive.setSelected(injector != null && injector.isPassiveCapture());

            boolean dark = AuditTheme.isDark(api, this);
            lblAutoSendState.setText(allowAuto ? "BẬT (Tự click/fetch)" : "KHÓA (An toàn)");
            lblAutoSendState.setForeground(allowAuto
                ? AuditTheme.okForeground(dark)
                : AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));

            CrawlState.Status st = state.getStatus();
            lblStatusPill.setText(st.name());
            Color pillColor = st == CrawlState.Status.RUNNING
                ? AuditTheme.okForeground(dark)
                : (st == CrawlState.Status.PAUSED
                    ? AuditTheme.severityForeground(AuditNote.Severity.WARN, dark)
                    : AuditTheme.mutedForeground(dark));
            AuditTheme.styleStatChip(lblStatusPill, pillColor,
                AuditTheme.blend(AuditTheme.panelSurface(dark), pillColor, dark ? 0.22f : 0.12f));

            boolean active = st == CrawlState.Status.RUNNING || st == CrawlState.Status.PAUSED;
            btnPause.setEnabled(active);
            btnPause.setText(st == CrawlState.Status.PAUSED ? "Resume" : "Pause");
            btnStop.setEnabled(active);
            btnSidebarPause.setEnabled(active);
            btnSidebarPause.setIcon(st == CrawlState.Status.PAUSED
                ? BurpIcons.play(AuditTheme.okForeground(dark))
                : BurpIcons.pause(AuditTheme.mutedForeground(dark)));
            cardCrawl.setPausedIcon(st == CrawlState.Status.PAUSED);

            ActionGroupStore store = state.getActionGroups();
            int err = store.noteCount(AuditNote.Severity.ERROR);
            int warn = store.noteCount(AuditNote.Severity.WARN);
            int info = store.noteCount(AuditNote.Severity.INFO);
            int totalCalls = store.totalCalls();
            int groupCount = store.groupCount();

            badgeCrawlErr.setBadge(String.valueOf(err), AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));
            badgeCrawlWarn.setBadge(String.valueOf(warn), AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
            badgeCrawlInfo.setBadge(String.valueOf(info), AuditTheme.severityForeground(AuditNote.Severity.INFO, dark));

            cardCrawl.setSubtitle("Nhóm: " + groupCount + " | API: " + totalCalls);
            String path = state.getCurrentActionPath();
            if (path.length() > 42) {
                path = path.substring(0, 39) + "...";
            }
            cardCrawl.setStatusText("[" + st.name() + "] " + path);

            ExcelSpecMatcher matcher = matcherSupplier != null ? matcherSupplier.get() : null;
            ExcelSpecMatcher.CoverageStats stats = matcher != null
                ? matcher.getCoverageStats()
                : new ExcelSpecMatcher.CoverageStats(0, 0, 0, 0);
            int pct = stats.total > 0 ? (int) Math.round(stats.matched * 100.0 / stats.total) : (totalCalls > 0 ? 100 : 0);
            cardCrawl.setProgress(pct, 100, stats.total > 0 ? ("Excel: " + pct + "%") : (totalCalls + " API"));

            String fn = matcher != null && !matcher.getSourceFileName().isEmpty()
                ? matcher.getSourceFileName() : "Chưa nạp Excel";
            cardExcel.setSubtitle(fn);
            cardExcel.setStatusText("Khớp: " + stats.matched + " / " + stats.total + " API");
            cardExcel.setProgress(stats.matched, Math.max(1, stats.total), stats.matched + "/" + stats.total);
            badgeExcelMatched.setBadge(String.valueOf(stats.matched), AuditTheme.okForeground(dark));
            badgeExcelMissing.setBadge(String.valueOf(stats.missing), AuditTheme.mutedForeground(dark));

            int pushTotal = pushManager != null ? pushManager.totalCount() : 0;
            int pushSent = pushManager != null ? pushManager.sentCount() : 0;
            int pushPending = pushManager != null ? pushManager.pendingSelectedCount() : 0;
            int pushFailed = 0;
            if (pushManager != null) {
                for (ExtenderPushEntry en : pushManager.snapshotEntries()) {
                    if (en.getStatus() == ExtenderPushEntry.Status.FAILED) {
                        pushFailed++;
                    }
                }
            }
            cardPush.setSubtitle("Hàng chờ: " + pushPending + " | Đã đẩy: " + pushSent);
            cardPush.setStatusText(pushManager != null && pushManager.isPushing() ? "Đang đẩy qua Extender..." : "Sẵn sàng đẩy");
            cardPush.setProgress(pushSent, Math.max(1, pushTotal), pushSent + "/" + pushTotal);
            badgePushQueued.setBadge(String.valueOf(pushPending), AuditTheme.BURP_ACTIVE_PILL_BG);
            badgePushSent.setBadge(String.valueOf(pushSent), AuditTheme.okForeground(dark));
            badgePushFail.setBadge(String.valueOf(pushFailed), AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));

            // Progress KV card
            valStatus.setText(st.name());
            valGroups.setText(String.valueOf(groupCount));
            valTotalApis.setText(String.valueOf(totalCalls));
            valPages.setText(String.valueOf(state.getVisitedPagesCount()));
            valQueue.setText(String.valueOf(state.getPendingQueueSize()));
            valExcel.setText(stats.matched + " / " + stats.total);
            valPush.setText(pushSent + " / " + pushTotal);
            valNotes.setText(String.valueOf(err + warn + info));
        } finally {
            syncingSwitches = false;
        }

        refreshSummaryApisTable();
        refreshIssuesTable();
        refreshEventsIfNeeded();
        applyTaskSidebarFilter();
    }

    public void refreshEventsIfNeeded() {
        long ver = state.getEventLogVersion();
        if (ver != lastEventVersion) {
            lastEventVersion = ver;
            refreshEventsTable();
            updateTaskLogSnippet();
        }
    }

    private void updateTaskLogSnippet() {
        List<EventLogEntry> all = state.snapshotEventLogs();
        if (all.isEmpty()) {
            txtTaskLogSnippet.setText("Chưa có sự kiện nào được ghi nhận.");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, all.size() - 7);
        for (int i = all.size() - 1; i >= start; i--) {
            EventLogEntry e = all.get(i);
            String msg = e.message != null ? e.message.replace('\n', ' ') : "";
            if (msg.length() > 74) {
                msg = msg.substring(0, 71) + "...";
            }
            sb.append(fmt.format(new Date(e.timestampMs))).append(" [").append(e.source).append("] ").append(msg);
            if (i > start) {
                sb.append('\n');
            }
        }
        txtTaskLogSnippet.setText(sb.toString());
        txtTaskLogSnippet.setCaretPosition(0);
    }

    private void refreshSummaryApisTable() {
        TableSelectionPreserver.preserveAroundUpdate(tblSummaryApis, summaryApiModel::getKeyAt, restoringSummaryApis, () -> {
            summaryApiModel.refresh(state.getActionGroups().snapshotGroups(), 200);
        });
        lblSummaryApiCount.setText("(" + summaryApiModel.getRowCount() + ")");
        CardLayout cl = (CardLayout) summaryApisCard.getLayout();
        cl.show(summaryApisCard, summaryApiModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private void refreshEventsTable() {
        String q = txtEventSearch.getText();
        TableSelectionPreserver.preserveAroundUpdate(tblEvents, eventModel::getKeyAt, restoringEvents, () -> {
            eventModel.refresh(
                state.snapshotEventLogs(),
                pillEvtCrit.isSelected(),
                pillEvtErr.isSelected(),
                pillEvtInfo.isSelected(),
                pillEvtDebug.isSelected(),
                pillEvtAgent.isSelected(),
                q
            );
        });
        CardLayout cl = (CardLayout) eventsCardHost.getLayout();
        cl.show(eventsCardHost, eventModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private void refreshIssuesTable() {
        String q = txtIssueSearch.getText();
        TableSelectionPreserver.preserveAroundUpdate(tblIssues, issueModel::getKeyAt, restoringIssues, () -> {
            issueModel.refresh(
                state.getActionGroups().snapshotNotes(),
                pillIssHigh.isSelected(),
                pillIssMed.isSelected(),
                pillIssInfo.isSelected(),
                q
            );
        });
        CardLayout cl = (CardLayout) issuesCardHost.getLayout();
        cl.show(issuesCardHost, issueModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private ApiCall getSelectedSummaryCall() {
        int vr = tblSummaryApis.getSelectedRow();
        if (vr < 0 || vr >= tblSummaryApis.getRowCount()) {
            return null;
        }
        return summaryApiModel.getCallAt(tblSummaryApis.convertRowIndexToModel(vr));
    }

    private AuditNote getSelectedIssue() {
        int vr = tblIssues.getSelectedRow();
        if (vr < 0 || vr >= tblIssues.getRowCount()) {
            return null;
        }
        return issueModel.getNoteAt(tblIssues.convertRowIndexToModel(vr));
    }

    private ApiCall getSelectedIssueCall() {
        AuditNote n = getSelectedIssue();
        return findCallForNote(n);
    }

    private ApiCall findCallForNote(AuditNote n) {
        if (n == null) {
            return null;
        }
        ActionGroup g = state.getActionGroups().getGroup(n.groupId);
        if (g == null) {
            return null;
        }
        for (ApiCall c : g.getCalls()) {
            if (c.index == n.callIndex) {
                return c;
            }
        }
        return null;
    }

    private void showNoteInAdvisory(AuditNote note) {
        ApiCall match = findCallForNote(note);
        btnIssueLocateWeb.setEnabled(match != null);
        btnIssueInspect.setEnabled(match != null);
        btnIssueJump.setEnabled(true);
        lblIssueSelected.setText("[" + note.severity.label + "] " + note.method + " " + note.endpoint + " — " + note.message);
        showCallInAdvisory(match, note);
    }

    private void showCallInAdvisory(ApiCall match, AuditNote note) {
        this.currentAdvisoryCall = match;
        btnAdvLocateWeb.setEnabled(match != null);
        btnAdvJump.setEnabled(match != null);
        btnAdvRepeater.setEnabled(match != null && api != null);
        btnAdvOrganizer.setEnabled(match != null && api != null);

        StringBuilder sb = new StringBuilder();
        if (note != null) {
            sb.append("[").append(note.severity.label).append("] ").append(note.type.label).append('\n');
            sb.append("Endpoint: ").append(note.method).append(' ').append(note.endpoint).append('\n');
            sb.append("Nhóm (Action Path): ").append(note.groupPath).append('\n');
            sb.append("Chi tiết: ").append(note.message).append("\n\n");
        }
        if (match != null) {
            CapturedApiEntry cap = match.capture;
            lblAdvSummary.setText(match.capture.method + " " + match.displayPath() + "  (HTTP " + cap.statusCode + ")");
            sb.append("Nhóm: ").append(match.displayName()).append('\n');
            sb.append("URL: ").append(cap.fullUrl).append('\n');
            sb.append("HTTP ").append(cap.statusCode).append(" | ").append(cap.paramCount())
                .append(" params (").append(cap.paramSignature()).append(")\n");
            List<ActionStep> trail = match.getActionTrail();
            if (!trail.isEmpty()) {
                sb.append("\n--- Action Trail (").append(trail.size()).append(" bước) ---\n");
                for (int i = 0; i < trail.size(); i++) {
                    ActionStep st = trail.get(i);
                    sb.append(i + 1).append(". [").append(st.kind).append("] ")
                        .append(st.label.isEmpty() ? st.actionPath : st.label).append('\n');
                }
            }
            if (reqEditor != null && respEditor != null) {
                try {
                    HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
                    reqEditor.setRequest(cap.toMontoyaRequest(svc));
                    respEditor.setResponse(cap.toMontoyaResponse());
                } catch (Exception ignored) {
                }
            }
        }
        txtAdvisory.setText(sb.toString());
        txtAdvisory.setCaretPosition(0);
    }

    private void showEventEntryInDetail(EventLogEntry en) {
        String timeStr = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(en.timestampMs));
        txtEventDetail.setText("[" + timeStr + "] [" + en.level.label + "] [" + en.source + "] " + en.message);
        txtEventDetail.setCaretPosition(0);
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        Color canvas = AuditTheme.canvasSurface(dark);
        Color panel = AuditTheme.panelSurface(dark);
        Color muted = AuditTheme.mutedForeground(dark);
        Color text = AuditTheme.textPrimary(dark);
        Font base = AuditTheme.displayFont(api, this);

        setBackground(canvas);
        sidebarPanel.setBackground(panel);
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, AuditTheme.borderColor(dark)));
        detailPanel.setBackground(canvas);

        lblTasksHeading.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 2.5f));
        lblTasksHeading.setForeground(text);
        AuditTheme.stylePlainButton(btnNewCrawl, dark);
        AuditTheme.stylePlainButton(btnTaskFilter, dark);
        btnTaskFilter.setIcon(BurpIcons.chevronDown(muted));

        btnSidebarGear.setIcon(BurpIcons.gear(muted));
        btnSidebarHelp.setIcon(BurpIcons.helpCircle(muted));
        btnToggleSidebar.setIcon(sidebarChevronIcon(sidebarVisible, muted));

        tasksStack.setBackground(panel);
        AuditTheme.styleScrollPane(spTasks, dark);
        spTasks.setBorder(BorderFactory.createEmptyBorder());
        cardCrawl.applyTheme();
        cardExcel.applyTheme();
        cardPush.applyTheme();

        detailHeaderBar.setBackground(panel);
        detailHeaderBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AuditTheme.borderColor(dark)),
            BorderFactory.createEmptyBorder(7, 10, 6, 10)));
        lblDetailTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 2.0f));
        lblDetailTitle.setForeground(text);

        AuditTheme.stylePlainButton(btnPause, dark);
        AuditTheme.stylePlainButton(btnStop, dark);
        AuditTheme.stylePlainButton(btnExport, dark);
        btnExport.setIcon(BurpIcons.chevronDown(muted));
        AuditTheme.styleBurpTabbedPane(detailTabs, dark);

        // Summary left + right cards
        lblSummaryApiTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 0.5f));
        lblSummaryApiTitle.setForeground(text);
        lblSummaryApiCount.setFont(base);
        lblSummaryApiCount.setForeground(muted);
        styleUnderlinedLink(lblViewHttpHistory, AuditTheme.burpOrange(dark), base);
        styleUnderlinedLink(lblViewAllEvents, AuditTheme.burpOrange(dark), base);

        AuditTheme.configureAuditTable(tblSummaryApis, api);
        AuditTheme.styleScrollPane(spSummaryApis, dark);
        summaryEmptyState.applyTheme();

        spSummaryRight.setBorder(BorderFactory.createEmptyBorder());
        spSummaryRight.getViewport().setBackground(canvas);

        styleSummaryInfoCard(cardConfig, dark, base);
        styleSummaryInfoCard(cardProgress, dark, base);
        styleSummaryInfoCard(cardLog, dark, base);
        txtTaskLogSnippet.setBackground(panel);
        txtTaskLogSnippet.setForeground(muted);
        txtTaskLogSnippet.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 1f)));

        // Issues tab
        AuditTheme.configureAuditTable(tblIssues, api);
        AuditTheme.styleScrollPane(spIssues, dark);
        issuesEmptyState.applyTheme();
        pillIssHigh.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));
        pillIssMed.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
        pillIssInfo.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.INFO, dark));
        btnIssueLocateWeb.setIcon(BurpIcons.locateWeb(AuditTheme.burpOrange(dark)));
        AuditTheme.stylePlainButton(btnIssueLocateWeb, dark);
        AuditTheme.stylePlainButton(btnIssueInspect, dark);
        AuditTheme.stylePlainButton(btnIssueJump, dark);
        lblIssueSelected.setForeground(muted);

        // Events tab
        AuditTheme.configureAuditTable(tblEvents, api);
        AuditTheme.styleScrollPane(spEvents, dark);
        AuditTheme.styleScrollPane(spEventDetail, dark);
        eventsEmptyState.applyTheme();
        AuditTheme.stylePlainButton(btnClearEvents, dark);
        txtEventDetail.setBackground(AuditTheme.tableSurface(dark));
        txtEventDetail.setForeground(text);
        txtEventDetail.setFont(base);
        pillEvtCrit.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark));
        pillEvtErr.setActiveFill(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
        pillEvtInfo.setActiveFill(AuditTheme.BURP_ACTIVE_PILL_BG);
        pillEvtDebug.setActiveFill(muted);
        pillEvtAgent.setActiveFill(AuditTheme.accentForeground(dark));

        // Request / Response tab
        advTopBar.setBackground(panel);
        advTopBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AuditTheme.borderColor(dark)));
        lblAdvSummary.setFont(base.deriveFont(Font.BOLD));
        lblAdvSummary.setForeground(text);
        btnAdvLocateWeb.setIcon(BurpIcons.locateWeb(AuditTheme.burpOrange(dark)));
        AuditTheme.stylePlainButton(btnAdvLocateWeb, dark);
        AuditTheme.stylePlainButton(btnAdvJump, dark);
        AuditTheme.stylePlainButton(btnAdvRepeater, dark);
        AuditTheme.stylePlainButton(btnAdvOrganizer, dark);
        AuditTheme.styleScrollPane(spAdvisory, dark);
        txtAdvisory.setBackground(AuditTheme.tableSurface(dark));
        txtAdvisory.setForeground(text);
        txtAdvisory.setFont(base);
    }

    private static void styleUnderlinedLink(JLabel lbl, Color color, Font base) {
        Font f = base.deriveFont(Font.BOLD);
        @SuppressWarnings("unchecked")
        Map<TextAttribute, Object> attrs = (Map<TextAttribute, Object>) f.getAttributes();
        attrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        lbl.setFont(f.deriveFont(attrs));
        lbl.setForeground(color);
    }

    private static void styleSummaryInfoCard(JPanel card, boolean dark, Font base) {
        card.setOpaque(true);
        card.setBackground(AuditTheme.panelSurface(dark));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AuditTheme.borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(9, 11, 9, 11)));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 60));
        for (Component c : card.getComponents()) {
            if (c instanceof JLabel && "cardTitle".equals(c.getName())) {
                c.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 0.5f));
                c.setForeground(AuditTheme.textPrimary(dark));
            }
        }
    }

    private static JPanel wrapSwitchRow(BurpComponents.BurpToggleSwitch sw, JLabel hint) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        p.add(sw);
        p.add(hint);
        return p;
    }

    private static void addKvRow(JPanel grid, String key, JLabel value) {
        JLabel k = new JLabel(key);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        grid.add(k);
        grid.add(value);
    }

    private static Icon sidebarChevronIcon(boolean collapseLeft, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.translate(x, y);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D p = new Path2D.Float();
                    if (collapseLeft) {
                        p.moveTo(9f, 3f);
                        p.lineTo(4.5f, 7f);
                        p.lineTo(9f, 11f);
                    } else {
                        p.moveTo(5f, 3f);
                        p.lineTo(9.5f, 7f);
                        p.lineTo(5f, 11f);
                    }
                    g2.draw(p);
                } finally {
                    g2.dispose();
                }
            }
            @Override public int getIconWidth() { return 14; }
            @Override public int getIconHeight() { return 14; }
        };
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (sidebarPanel != null && detailHeaderBar != null && lblTasksHeading != null) {
            applyTheme();
        }
    }

    private static final class SimpleDocListener implements DocumentListener {
        private final Runnable cb;
        SimpleDocListener(Runnable cb) { this.cb = cb; }
        @Override public void insertUpdate(DocumentEvent e) { cb.run(); }
        @Override public void removeUpdate(DocumentEvent e) { cb.run(); }
        @Override public void changedUpdate(DocumentEvent e) { cb.run(); }
    }

    /**
     * Selectable Burp v2026.10.1 Task Card in the left sidebar.
     */
    private static final class SidebarTaskCard extends JPanel {
        private final MontoyaApi api;
        private final JLabel lblTitle = new JLabel();
        private final JLabel lblSubtitle = new JLabel();
        private final JLabel lblStatus = new JLabel();
        private final JButton btnPausePlay;
        private final JButton btnMenu;
        private final JPanel switchesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        private final JPanel badgesRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        private final BurpComponents.BurpProgressBar progressBar;
        private boolean selected;

        SidebarTaskCard(
            MontoyaApi api,
            String title,
            boolean initialSelected,
            Runnable onPausePlay,
            Consumer<Component> onMenu,
            Runnable onSelect
        ) {
            super(new BorderLayout(4, 4));
            this.api = api;
            this.selected = initialSelected;
            this.progressBar = new BurpComponents.BurpProgressBar(api);

            lblTitle.setText(title);
            boolean dark = AuditTheme.isDark(api, this);
            Color ic = AuditTheme.mutedForeground(dark);
            btnPausePlay = BurpComponents.createIconButton(api, BurpIcons.pause(ic), "Tạm dừng / Tiếp tục");
            btnMenu = BurpComponents.createIconButton(api, BurpIcons.menuDots(ic), "Tùy chọn Task");

            btnPausePlay.setVisible(onPausePlay != null);
            if (onPausePlay != null) {
                btnPausePlay.addActionListener(e -> onPausePlay.run());
            }
            if (onMenu != null) {
                btnMenu.addActionListener(e -> onMenu.accept(btnMenu));
            }

            JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            topRight.setOpaque(false);
            topRight.add(btnPausePlay);
            topRight.add(btnMenu);

            JPanel topRow = new JPanel(new BorderLayout(4, 0));
            topRow.setOpaque(false);
            topRow.add(lblTitle, BorderLayout.CENTER);
            topRow.add(topRight, BorderLayout.EAST);

            switchesRow.setOpaque(false);
            badgesRow.setOpaque(false);

            JPanel midWrap = new JPanel(new BorderLayout(0, 3));
            midWrap.setOpaque(false);
            midWrap.add(lblSubtitle, BorderLayout.NORTH);
            midWrap.add(switchesRow, BorderLayout.CENTER);

            JPanel botRow = new JPanel(new BorderLayout(4, 0));
            botRow.setOpaque(false);
            botRow.add(lblStatus, BorderLayout.CENTER);
            botRow.add(badgesRow, BorderLayout.EAST);

            JPanel botWrap = new JPanel(new BorderLayout(0, 4));
            botWrap.setOpaque(false);
            botWrap.add(progressBar, BorderLayout.NORTH);
            botWrap.add(botRow, BorderLayout.SOUTH);

            add(topRow, BorderLayout.NORTH);
            add(midWrap, BorderLayout.CENTER);
            add(botWrap, BorderLayout.SOUTH);

            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onSelect != null) {
                        onSelect.run();
                    }
                }
            });
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 152));
            applyTheme();
        }

        void addSwitchRow(String label, JComponent sw) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            p.setOpaque(false);
            p.add(new JLabel(label));
            p.add(sw);
            switchesRow.add(p);
        }

        void addBadge(JComponent badge) {
            badgesRow.add(badge);
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            applyTheme();
        }

        void setSubtitle(String text) {
            lblSubtitle.setText(text);
        }

        void setStatusText(String text) {
            lblStatus.setText(text);
        }

        void setProgress(int val, int max, String tooltip) {
            progressBar.setProgress(val, max, tooltip);
        }

        void setPausedIcon(boolean paused) {
            boolean dark = AuditTheme.isDark(api, this);
            btnPausePlay.setIcon(paused
                ? BurpIcons.play(AuditTheme.okForeground(dark))
                : BurpIcons.pause(AuditTheme.mutedForeground(dark)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean dark = AuditTheme.isDark(api, this);
                Color bg = selected
                    ? com.mythofcrawl.crawler.ui.theme.Theme.selection(dark)
                    : AuditTheme.panelSurface(dark);
                Color border = selected
                    ? com.mythofcrawl.crawler.ui.theme.Theme.token(com.mythofcrawl.crawler.ui.theme.Theme.Token.INFO, dark).border()
                    : AuditTheme.borderColor(dark);

                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 6f, 6f));
                g2.setColor(border);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 6f, 6f));
                if (selected) {
                    g2.setColor(AuditTheme.BURP_DARK_SELECTION);
                    g2.fill(new RoundRectangle2D.Float(1f, 4f, 3f, getHeight() - 8f, 2f, 2f));
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        void applyTheme() {
            boolean dark = AuditTheme.isDark(api, this);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 8));
            Font base = AuditTheme.displayFont(api, this);
            lblTitle.setFont(base.deriveFont(Font.BOLD));
            lblTitle.setForeground(AuditTheme.textPrimary(dark));
            lblSubtitle.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 1f)));
            lblSubtitle.setForeground(AuditTheme.mutedForeground(dark));
            lblStatus.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 1f)));
            lblStatus.setForeground(AuditTheme.mutedForeground(dark));
            btnMenu.setIcon(BurpIcons.menuDots(AuditTheme.mutedForeground(dark)));
            repaint();
        }
    }

    private static final class SummaryApiModel extends AbstractTableModel {
        private static final String[] COLS = {"Host / Nhóm", "Method", "Endpoint / Path", "HTTP", "Params"};
        private List<ApiCall> rows = Collections.emptyList();

        void refresh(List<ActionGroup> groups, int limit) {
            List<ApiCall> all = new ArrayList<>();
            for (ActionGroup g : groups) {
                all.addAll(g.getCalls());
            }
            List<ApiCall> out = new ArrayList<>();
            int start = Math.max(0, all.size() - limit);
            for (int i = all.size() - 1; i >= start; i--) {
                out.add(all.get(i));
            }
            this.rows = out;
            fireTableDataChanged();
        }

        ApiCall getCallAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        Long getKeyAt(int r) {
            ApiCall c = getCallAt(r);
            return c != null ? Long.valueOf(c.callUid) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            ApiCall c = rows.get(row);
            switch (col) {
                case 0: return c.displayName();
                case 1: return c.capture.method;
                case 2: return c.displayPath();
                case 3: return Integer.valueOf(c.capture.statusCode);
                case 4: return c.capture.paramCount() + (c.inExcel ? " (Excel)" : "");
                default: return "";
            }
        }
    }

    private final class SummaryApiRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 1 || column == 3) ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());
            if (!isSelected && table.getModel() instanceof SummaryApiModel) {
                ApiCall call = ((SummaryApiModel) table.getModel()).getCallAt(table.convertRowIndexToModel(row));
                c.setBackground(AuditTheme.zebraRowBackground(table.getBackground(), row, dark));
                if (call != null && column == 1) {
                    c.setForeground(AuditTheme.methodForeground(call.capture.method, dark));
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                } else if (call != null && column == 3) {
                    c.setForeground(AuditTheme.statusCodeForeground(call.capture.statusCode, dark));
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(AuditTheme.textPrimary(dark));
                }
            }
            return c;
        }
    }

    private static final class EventLogModel extends AbstractTableModel {
        private static final String[] COLS = {"Time", "Type", "Source", "Message"};
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        private List<EventLogEntry> rows = Collections.emptyList();

        void refresh(
            List<EventLogEntry> all,
            boolean crit, boolean err, boolean info, boolean debug, boolean agentOnly, String query
        ) {
            String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
            List<EventLogEntry> out = new ArrayList<>();
            boolean anyLevel = crit || err || info || debug;
            for (int i = all.size() - 1; i >= 0; i--) {
                EventLogEntry e = all.get(i);
                if (agentOnly && !"Agent".equalsIgnoreCase(e.source) && !"Replay".equalsIgnoreCase(e.source)) {
                    continue;
                }
                if (anyLevel) {
                    if (e.level == EventLogEntry.Level.CRITICAL && !crit) continue;
                    if (e.level == EventLogEntry.Level.ERROR && !err) continue;
                    if (e.level == EventLogEntry.Level.INFO && !info) continue;
                    if (e.level == EventLogEntry.Level.DEBUG && !debug) continue;
                }
                if (!q.isEmpty()) {
                    String hay = (e.source + " " + e.message).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                out.add(e);
            }
            this.rows = out;
            fireTableDataChanged();
        }

        EventLogEntry getEntryAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        Long getKeyAt(int r) {
            EventLogEntry e = getEntryAt(r);
            return e != null ? Long.valueOf(e.id) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            EventLogEntry e = rows.get(row);
            switch (col) {
                case 0: synchronized (fmt) { return fmt.format(new Date(e.timestampMs)); }
                case 1: return e.level.label;
                case 2: return e.source;
                case 3: return e.message;
                default: return "";
            }
        }
    }

    private final class EventLogRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 2) ? SwingConstants.CENTER : SwingConstants.LEFT);
            if (c instanceof JLabel) {
                ((JLabel) c).setIcon(null);
            }
            if (!isSelected && table.getModel() instanceof EventLogModel) {
                EventLogEntry en = ((EventLogModel) table.getModel()).getEntryAt(table.convertRowIndexToModel(row));
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = AuditTheme.textPrimary(dark);
                if (en != null && column == 1 && c instanceof JLabel) {
                    if (en.level == EventLogEntry.Level.CRITICAL || en.level == EventLogEntry.Level.ERROR) {
                        Color col = AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark);
                        ((JLabel) c).setIcon(BurpIcons.severityCircle(col, "!"));
                        fg = col;
                    } else if (en.level == EventLogEntry.Level.INFO) {
                        Color col = AuditTheme.severityForeground(AuditNote.Severity.INFO, dark);
                        ((JLabel) c).setIcon(BurpIcons.severityCircle(col, "i"));
                    } else {
                        ((JLabel) c).setIcon(BurpIcons.severityCircle(AuditTheme.mutedForeground(dark), "-"));
                        fg = AuditTheme.mutedForeground(dark);
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }

    private static final class IssueActivityModel extends AbstractTableModel {
        private static final String[] COLS = {"Severity", "Loại NOTE", "Endpoint / Path", "Nhóm (Action Path)"};
        private List<AuditNote> rows = Collections.emptyList();

        void refresh(List<AuditNote> all, boolean high, boolean med, boolean info, String query) {
            String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
            List<AuditNote> out = new ArrayList<>();
            boolean any = high || med || info;
            for (int i = all.size() - 1; i >= 0; i--) {
                AuditNote n = all.get(i);
                if (any) {
                    if (n.severity == AuditNote.Severity.ERROR && !high) continue;
                    if (n.severity == AuditNote.Severity.WARN && !med) continue;
                    if (n.severity == AuditNote.Severity.INFO && !info) continue;
                }
                if (!q.isEmpty()) {
                    String hay = (n.type.label + " " + n.method + " " + n.endpoint + " " + n.groupPath + " " + n.message)
                        .toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                out.add(n);
            }
            this.rows = out;
            fireTableDataChanged();
        }

        AuditNote getNoteAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        String getKeyAt(int r) {
            AuditNote n = getNoteAt(r);
            return n != null ? (n.groupId + "#" + n.callIndex + "|" + n.type.name() + "|" + n.endpoint) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            AuditNote n = rows.get(row);
            switch (col) {
                case 0: return n.severity == AuditNote.Severity.ERROR ? "High"
                    : (n.severity == AuditNote.Severity.WARN ? "Medium" : "Info");
                case 1: return n.type.label;
                case 2: return n.method + " " + n.endpoint;
                case 3: return n.groupPath;
                default: return "";
            }
        }
    }

    private final class IssueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment(SwingConstants.LEFT);
            if (c instanceof JLabel) {
                ((JLabel) c).setIcon(null);
            }
            if (!isSelected && table.getModel() instanceof IssueActivityModel) {
                AuditNote n = ((IssueActivityModel) table.getModel()).getNoteAt(table.convertRowIndexToModel(row));
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = AuditTheme.textPrimary(dark);
                if (n != null && column == 0 && c instanceof JLabel) {
                    Color sc = AuditTheme.severityForeground(n.severity, dark);
                    String glyph = n.severity == AuditNote.Severity.INFO ? "i" : "!";
                    ((JLabel) c).setIcon(BurpIcons.severityCircle(sc, glyph));
                    fg = sc;
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }
}
