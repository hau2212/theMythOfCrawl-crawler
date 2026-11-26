package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.bridge.BurpUiHost;
import com.mythofcrawl.crawler.bridge.HeadlessUiHost;
import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.core.auth.RoleStore;
import com.mythofcrawl.crawler.core.guardrail.ActionGateAuditLog;
import com.mythofcrawl.crawler.core.guardrail.GuardrailMode;
import com.mythofcrawl.crawler.core.guardrail.PolicyEvaluator;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.ChangesBatch;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.core.tree.VisualClusterStore;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.FormFillProfile;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.ui.dialogs.DetachedTreeDialog;
import com.mythofcrawl.crawler.ui.dialogs.DuplicateLineageDialog;
import com.mythofcrawl.crawler.ui.dialogs.InterceptQueueDialog;
import com.mythofcrawl.crawler.ui.dialogs.RequestComposerDialog;
import com.mythofcrawl.crawler.ui.kit.Breadcrumb;
import com.mythofcrawl.crawler.ui.kit.ModernToggleSwitch;
import com.mythofcrawl.crawler.ui.kit.SegmentedToggle;
import com.mythofcrawl.crawler.ui.kit.SpotlightDialog;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.kit.UiDisplayMode;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;
import com.mythofcrawl.crawler.ui.workspace.GuardrailLedgerTab;
import com.mythofcrawl.crawler.ui.workspace.PlaybooksGuideTab;
import com.mythofcrawl.crawler.ui.workspace.RolesTab;
import com.mythofcrawl.crawler.ui.workspace.TargetExcelTab;
import com.mythofcrawl.crawler.ui.workspace.TreeWorkspacePanel;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V_1.0_beta Tree-First Blueprint Workspace Shell:
 * - Single-row Top Bar (preferredWidth <= 1100px, zero hidden state)
 * - 7 Blueprint Tabs: Cây làm việc (default), Luật & Chặn, Roles & Auth, Mục tiêu Excel,
 *   Đẩy Logger++, Hướng dẫn & Playbooks, Cài đặt
 * - 0ms ProxyInterceptQueue push + 200ms ChangesBatch sync + Light/Dark Theme parity
 * - Preserves Crawl-First, Explicit Batch Push Later on Logger++
 */
public final class CrawlerTabPanel extends JPanel {

    public static final int TAB_TREE = 0;
    public static final int TAB_GUARDRAILS = 1;
    public static final int TAB_ROLES = 2;
    public static final int TAB_EXCEL = 3;
    public static final int TAB_PUSH = 4;
    public static final int TAB_PLAYBOOKS = 5;
    public static final int TAB_SETTINGS = 6;

    private final MontoyaApi api;
    private final UiHost uiHost;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private final ExtenderPushManager pushManager;
    private final Runnable onPersistConfig;
    private final CrawlSessionController controller;

    private final PolicyLedger policyLedger;
    private final ProxyInterceptQueue interceptQueue;
    private final ActionGateAuditLog gateAuditLog;
    private final RoleStore roleStore;
    private final DuplicateLineageTracker lineageTracker;
    private final VisualClusterStore clusterStore;
    private final InstantEventBus eventBus;
    private final ChangesBatch changesBatch;

    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Timer refreshTimer;
    private final Runnable unlistenLaf;
    private AutoCloseable themeSub;
    private InstantEventBus.Subscription queueSub;
    private InstantEventBus.Subscription ledgerSub;

    // Single-row Top Bar (<= 1100px)
    private final JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final ModernToggleSwitch swAgentProxy;
    private final SegmentedToggle segMode;
    private final Ui.UiButton btnSafetyScope;
    private final Breadcrumb topBreadcrumb = new Breadcrumb();
    private final Ui.UiButton btnPendingQueue;
    private final Ui.UiButton btnReleaseNone;
    private final Ui.UiButton btnSpotlight;
    private final Ui.UiButton btnMoreMenu;

    private final JTabbedPane mainTabs = new JTabbedPane();
    private final ToastPane toast;

    private final TreeWorkspacePanel treeWorkspacePanel;
    private final GuardrailLedgerTab guardrailLedgerTab;
    private final RolesTab rolesTab;
    private final TargetExcelTab targetExcelTab;
    private final PushLedgerPanel pushLedgerPanel;
    private final PlaybooksGuideTab playbooksGuideTab;
    private final HeaderFilterPanel headerFilterPanel;
    private final AuxPanel settingsAuxPanel;
    private final BurpSettingsPanel settingsPanel;

    private InterceptQueueDialog interceptDialog;
    private DuplicateLineageDialog lineageDialog;
    private RequestComposerDialog composerDialog;
    private DetachedTreeDialog detachedTreeDialog;
    private SpotlightDialog spotlightDialog;

    private boolean treeViewMode = true;
    private boolean syncingControls = false;
    private long lastStoreVersion = -1L;

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
        this(
            api,
            api != null ? new BurpUiHost(api) : new HeadlessUiHost(),
            config,
            state,
            injector,
            pushManager,
            initialMatcher,
            new PolicyLedger(),
            new ProxyInterceptQueue(),
            new ActionGateAuditLog(),
            new RoleStore(),
            new DuplicateLineageTracker(),
            new VisualClusterStore(),
            new InstantEventBus(),
            new ChangesBatch(),
            onPersistConfig
        );
    }

    public CrawlerTabPanel(
        UiHost uiHost,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExtenderPushManager pushManager,
        ExcelSpecMatcher initialMatcher,
        PolicyLedger policyLedger,
        ProxyInterceptQueue interceptQueue,
        ActionGateAuditLog gateAuditLog,
        RoleStore roleStore,
        DuplicateLineageTracker lineageTracker,
        VisualClusterStore clusterStore,
        InstantEventBus eventBus,
        ChangesBatch changesBatch,
        Runnable onPersistConfig
    ) {
        this(
            uiHost instanceof BurpUiHost ? ((BurpUiHost) uiHost).getMontoyaApi() : null,
            uiHost, config, state, injector, pushManager, initialMatcher,
            policyLedger, interceptQueue, gateAuditLog, roleStore, lineageTracker,
            clusterStore, eventBus, changesBatch, onPersistConfig
        );
    }

    private CrawlerTabPanel(
        MontoyaApi api,
        UiHost uiHost,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExtenderPushManager pushManager,
        ExcelSpecMatcher initialMatcher,
        PolicyLedger policyLedger,
        ProxyInterceptQueue interceptQueue,
        ActionGateAuditLog gateAuditLog,
        RoleStore roleStore,
        DuplicateLineageTracker lineageTracker,
        VisualClusterStore clusterStore,
        InstantEventBus eventBus,
        ChangesBatch changesBatch,
        Runnable onPersistConfig
    ) {
        this.api = api;
        this.uiHost = uiHost != null ? uiHost : new HeadlessUiHost();
        this.config = config != null ? config : new CrawlConfig();
        this.state = state != null ? state : new CrawlState();
        this.injector = injector;
        this.onPersistConfig = onPersistConfig;
        this.policyLedger = policyLedger != null ? policyLedger : new PolicyLedger();
        this.interceptQueue = interceptQueue != null ? interceptQueue : new ProxyInterceptQueue();
        this.gateAuditLog = gateAuditLog != null ? gateAuditLog : new ActionGateAuditLog();
        this.roleStore = roleStore != null ? roleStore : new RoleStore();
        this.lineageTracker = lineageTracker != null ? lineageTracker : new DuplicateLineageTracker();
        this.clusterStore = clusterStore != null ? clusterStore : new VisualClusterStore();
        this.eventBus = eventBus != null ? eventBus : new InstantEventBus();
        this.changesBatch = changesBatch != null ? changesBatch : new ChangesBatch();

        Theme.setDark(this.uiHost.isDarkTheme());

        this.pushManager = pushManager != null
            ? pushManager
            : new ExtenderPushManager(api, this.config, this::onPushLedgerUpdated, this::logFromUi);
        this.controller = new CrawlSessionController(
            api, this.config, this.state, injector, this.pushManager, initialMatcher, this::logFromUi);

        this.toast = ToastPane.attach(this);

        this.swAgentProxy = new ModernToggleSwitch("Agent", injector == null || injector.isAgentArmed(), Theme.Token.SUCCESS);
        this.segMode = new SegmentedToggle(
            new SegmentedToggle.Option("Dừng", Theme.Token.NEUTRAL),
            new SegmentedToggle.Option("Capture", Theme.Token.INFO),
            new SegmentedToggle.Option("Auto", Theme.Token.ACCENT)
        );
        this.btnSafetyScope = Ui.tonalBtn(Theme.Token.INFO, "Nguyên bản NONE", VectorIcons.shield(Theme.token(Theme.Token.INFO).text()));
        this.btnPendingQueue = Ui.tonalBtn(Theme.Token.PENDING, "Chờ duyệt (0)", VectorIcons.shieldAlert(Theme.token(Theme.Token.PENDING).text()));
        this.btnReleaseNone = Ui.secondaryBtn("Nhả NONE", VectorIcons.unlock(Theme.text()));
        this.btnSpotlight = Ui.secondaryBtn("Ctrl+K", VectorIcons.search(Theme.text()));
        this.btnMoreMenu = Ui.secondaryBtn("Thêm", VectorIcons.chevronDown(Theme.text()));

        this.treeWorkspacePanel = new TreeWorkspacePanel(
            this.state,
            this.config,
            this.policyLedger,
            this.interceptQueue,
            this.lineageTracker,
            this.clusterStore,
            this.eventBus,
            this.changesBatch,
            this.uiHost,
            this.toast,
            this::locateCallOnWeb,
            this::openDuplicateLineageDialog,
            this::openRequestComposerDialog,
            branch -> updateTopBreadcrumb(branch),
            this::startAutoCrawlFromUi,
            this::togglePauseCrawlFromUi,
            this::persistConfigQuietly
        );

        this.guardrailLedgerTab = new GuardrailLedgerTab(
            this.state,
            this.policyLedger,
            null,
            this.gateAuditLog,
            this.toast,
            () -> {
                refreshSafetySummary();
                persistConfigQuietly();
            }
        );

        this.rolesTab = new RolesTab(
            this.state,
            this.roleStore,
            this.uiHost,
            this.toast,
            this::persistConfigQuietly
        );

        this.targetExcelTab = new TargetExcelTab(
            this.state,
            this.config,
            this.controller.getMatcher(),
            this.uiHost,
            this.toast,
            this::onImportExcel,
            call -> {
                treeWorkspacePanel.getInlineDock().showApiCall(call);
                treeWorkspacePanel.getInlineDock().setExpanded(true);
                mainTabs.setSelectedIndex(TAB_TREE);
            },
            this::locateCallOnWeb
        );

        this.pushLedgerPanel = new PushLedgerPanel(
            api,
            this.state,
            this.config,
            this::persistConfigQuietly,
            this.state.getActionGroups(),
            this.pushManager,
            () -> null,
            entry -> {
                if (entry != null && entry.capture != null) {
                    toast.showInfo("Logger++", entry.method + " " + entry.endpoint);
                }
            },
            null,
            call -> {
                if (call != null && call.capture != null) {
                    controller.sendToRepeater(call.capture, call.capture.method);
                }
            },
            this::logFromUi
        );

        this.playbooksGuideTab = new PlaybooksGuideTab(
            this.state,
            this.config,
            this.policyLedger,
            this.toast,
            () -> {
                refreshSafetySummary();
                guardrailLedgerTab.refreshData();
            }
        );

        this.headerFilterPanel = new HeaderFilterPanel(
            api,
            this.config,
            this::logFromUi,
            this::persistConfigQuietly
        );
        this.settingsAuxPanel = new AuxPanel(
            api,
            this.config,
            this.state,
            this::persistConfigQuietly,
            cap -> {},
            () -> {},
            controller::sendToRepeater,
            controller::sendCaptureToOrganizer
        );
        this.settingsPanel = new BurpSettingsPanel(
            api,
            this.config,
            injector,
            this.headerFilterPanel,
            this.settingsAuxPanel,
            this::logFromUi,
            this::persistConfigQuietly
        );

        setLayout(new BorderLayout(0, 0));
        buildTopBar();
        buildTabs();

        add(topBar, BorderLayout.NORTH);
        add(mainTabs, BorderLayout.CENTER);

        wireTopBarListeners();
        installHotkeys();
        applyTheme();

        this.themeSub = Theme.onThemeChange(() -> {
            SwingUtilities.updateComponentTreeUI(this);
            applyTheme();
        });
        this.unlistenLaf = AuditTheme.installLiveThemeListener(this, () -> {
            Theme.setDark(AuditTheme.isDark(api, this));
            SwingUtilities.updateComponentTreeUI(this);
            applyTheme();
        });

        this.refreshTimer = new Timer(450, e -> {
            treeWorkspacePanel.refreshAutoCrawlTelemetry();
            long curVer = this.state.getActionGroups().version();
            if (refreshPending.getAndSet(false) || curVer != lastStoreVersion) {
                lastStoreVersion = curVer;
                refreshActiveTab();
                refreshPendingCount();
            }
        });
        this.refreshTimer.start();

        refreshModeFromState();
        refreshSafetySummary();
        refreshPendingCount();
        logFromUi("theMythOfCrawl Crawler V_1.1_beta sẵn sàng [Form TEXT: \""
            + this.config.getFormFillProfile().get(FormFillProfile.Field.TEXT) + "\"].");
    }

    private void buildTopBar() {
        topBar.setOpaque(true);
        topBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
            BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2)));

        JPanel left = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        left.setOpaque(false);
        left.setMinimumSize(new Dimension(0, 28));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
        leftBtns.setOpaque(false);
        leftBtns.add(swAgentProxy);
        leftBtns.add(segMode);
        leftBtns.add(btnSafetyScope);

        topBreadcrumb.setPath(List.of("Root (/)"), i -> treeWorkspacePanel.clearFocusBranch());
        topBreadcrumb.setMinimumSize(new Dimension(40, 24));

        left.add(leftBtns, BorderLayout.WEST);
        left.add(topBreadcrumb, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        right.setOpaque(false);
        right.add(btnPendingQueue);
        right.add(btnReleaseNone);
        right.add(btnSpotlight);
        right.add(btnMoreMenu);

        topBar.add(left, BorderLayout.CENTER);
        topBar.add(right, BorderLayout.EAST);
    }

    private void buildTabs() {
        mainTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        Ui.styleTabbedPane(mainTabs);
        mainTabs.addTab("Cây làm việc", treeWorkspacePanel);
        mainTabs.addTab("Luật & Chặn", guardrailLedgerTab);
        mainTabs.addTab("Roles & Auth", rolesTab);
        mainTabs.addTab("Mục tiêu Excel", targetExcelTab);
        mainTabs.addTab("Đẩy Logger++", pushLedgerPanel);
        mainTabs.addTab("Hướng dẫn & Playbooks", playbooksGuideTab);
        mainTabs.addTab("Cài đặt", settingsPanel);
        mainTabs.setSelectedIndex(TAB_TREE);
        mainTabs.addChangeListener(e -> refreshActiveTab());
    }

    private void wireTopBarListeners() {
        swAgentProxy.addActionListener(e -> {
            if (!syncingControls && injector != null) {
                injector.setAgentArmed(swAgentProxy.isSelected());
                logFromUi(swAgentProxy.isSelected() ? "Đã BẬT Agent Proxy." : "Đã TẮT Agent Proxy.");
            }
        });

        segMode.onChange(idx -> {
            if (syncingControls) {
                return;
            }
            if (idx == 0) {
                controller.stopCrawl();
                config.setAllowAutoRequests(false);
                refreshModeFromState();
                treeWorkspacePanel.refreshAutoCrawlTelemetry();
            } else if (idx == 1) {
                startCrawlModeFromUi(false);
            } else {
                startAutoCrawlFromUi();
            }
        });

        btnSafetyScope.addActionListener(e -> showQuickSafetyMenu());
        btnPendingQueue.addActionListener(e -> openInterceptQueueDialog());
        btnReleaseNone.addActionListener(e -> {
            policyLedger.releaseAllToNone();
            int f = interceptQueue.forwardAll();
            refreshSafetySummary();
            refreshPendingCount();
            toast.showSuccess("Đã nhả về NONE", "Toàn bộ rule -> NONE, giải phóng " + f + " chờ duyệt");
        });
        btnSpotlight.addActionListener(e -> openSpotlightDialog());
        btnMoreMenu.addActionListener(e -> showMoreMenu());

        this.queueSub = interceptQueue.subscribe(ev -> {
            Runnable onEdt = this::refreshPendingCount;
            if (SwingUtilities.isEventDispatchThread()) {
                onEdt.run();
            } else {
                SwingUtilities.invokeLater(onEdt);
            }
        });
        this.ledgerSub = policyLedger.subscribe(() -> SwingUtilities.invokeLater(this::refreshSafetySummary));
    }

    private void showQuickSafetyMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miReadOnly = new JMenuItem("Chỉ đọc GET (HOLD_AT_PROXY mọi POST/PUT/PATCH/DELETE)");
        JMenuItem miInterceptMut = new JMenuItem("Chặn duyệt POST/PUT/PATCH/DELETE (INTERCEPT_WAIT)");
        JMenuItem miNone = new JMenuItem("Nguyên bản (Nhả hết về NONE)");
        JMenuItem miOpenTab = new JMenuItem("Mở Bảng Luật & Chặn...");

        miReadOnly.addActionListener(e -> {
            policyLedger.installSafeReadOnlyPlaybook();
            refreshSafetySummary();
            toast.showInfo("Guardrail", "Đã bật Chỉ đọc GET (HOLD_AT_PROXY).");
        });
        miInterceptMut.addActionListener(e -> {
            policyLedger.installCreateDeleteLoopPlaybook();
            refreshSafetySummary();
            toast.showInfo("Guardrail", "Đã bật Chặn duyệt POST/PUT/PATCH/DELETE.");
        });
        miNone.addActionListener(e -> {
            policyLedger.releaseAllToNone();
            interceptQueue.forwardAll();
            refreshSafetySummary();
            refreshPendingCount();
        });
        miOpenTab.addActionListener(e -> mainTabs.setSelectedIndex(TAB_GUARDRAILS));

        m.add(miReadOnly);
        m.add(miInterceptMut);
        m.add(miNone);
        m.addSeparator();
        m.add(miOpenTab);
        m.show(btnSafetyScope, 0, btnSafetyScope.getHeight());
    }

    private void showMoreMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miImport = new JMenuItem("Nạp Excel (.xlsx/.csv)...");
        miImport.addActionListener(e -> onImportExcel());

        JMenu mExport = new JMenu("Xuất báo cáo");
        JMenuItem miCsv = new JMenuItem("CSV (Nhóm + NOTE + Excel)...");
        JMenuItem miJson = new JMenuItem("JSON...");
        JMenuItem miMd = new JMenuItem("Markdown...");
        JMenuItem miTmoc = new JMenuItem("JSON cho theMythOfCrawl.py...");
        JMenuItem miLedger = new JMenuItem("Push Ledger Elastic CSV...");
        miCsv.addActionListener(e -> controller.exportReportAsync(this, "csv"));
        miJson.addActionListener(e -> controller.exportReportAsync(this, "json"));
        miMd.addActionListener(e -> controller.exportReportAsync(this, "md"));
        miTmoc.addActionListener(e -> controller.exportReportAsync(this, "tmoc"));
        miLedger.addActionListener(e -> controller.exportPushLedgerCsvAsync(this));
        mExport.add(miCsv);
        mExport.add(miJson);
        mExport.add(miMd);
        mExport.add(miTmoc);
        mExport.add(miLedger);

        JMenuItem miHost = new JMenuItem("Host mục tiêu...");
        miHost.addActionListener(e -> promptHost());

        JMenuItem miDetachTree = new JMenuItem("Tách Cây làm việc (Cửa sổ nổi)");
        miDetachTree.addActionListener(e -> openDetachedTreeDialog());

        JMenuItem miLineage = new JMenuItem("Xem Dòng dõi trùng lặp");
        miLineage.addActionListener(e -> openDuplicateLineageDialog(""));

        JMenuItem miComposer = new JMenuItem("Mở Request Composer đa Role");
        miComposer.addActionListener(e -> openRequestComposerDialog(null));

        JMenu mMode = new JMenu("Hiển thị nút");
        JMenuItem miIconText = new JMenuItem("Icon + Chữ");
        JMenuItem miIconOnly = new JMenuItem("Chỉ Icon");
        JMenuItem miTextOnly = new JMenuItem("Chỉ Chữ");
        miIconText.addActionListener(e -> UiDisplayMode.setGlobal(UiDisplayMode.ICON_TEXT));
        miIconOnly.addActionListener(e -> UiDisplayMode.setGlobal(UiDisplayMode.ICON_ONLY));
        miTextOnly.addActionListener(e -> UiDisplayMode.setGlobal(UiDisplayMode.TEXT_ONLY));
        mMode.add(miIconText);
        mMode.add(miIconOnly);
        mMode.add(miTextOnly);

        JMenuItem miTheme = new JMenuItem(Theme.isDark() ? "Chuyển sang Light Mode" : "Chuyển sang Dark Mode");
        miTheme.addActionListener(e -> Theme.setDark(!Theme.isDark()));

        JMenuItem miReset = new JMenuItem("Reset kết quả Crawl");
        miReset.addActionListener(e -> {
            controller.resetCaptures();
            treeWorkspacePanel.rebuildTree();
            treeWorkspacePanel.refreshTableRows();
            refreshActiveTab();
        });

        menu.add(miImport);
        menu.add(mExport);
        menu.add(miHost);
        menu.addSeparator();
        menu.add(miDetachTree);
        menu.add(miLineage);
        menu.add(miComposer);
        menu.addSeparator();
        menu.add(mMode);
        menu.add(miTheme);
        menu.addSeparator();
        menu.add(miReset);
        menu.show(btnMoreMenu, 0, btnMoreMenu.getHeight());
    }

    private void startAutoCrawlFromUi() {
        startCrawlModeFromUi(true);
    }

    private void startCrawlModeFromUi(boolean autoMode) {
        String hosts = CrawlConfig.unwrapMarkdownLinks(treeWorkspacePanel.getTargetHostInput()).trim();
        if (hosts.isEmpty()) {
            hosts = config.getAllowedHostsCsv();
        }
        String seed = CrawlConfig.unwrapMarkdownLinks(treeWorkspacePanel.getSeedUrlInput()).trim();
        if (seed.isEmpty()) {
            seed = config.getSeedUrl();
        }
        if (hosts.isEmpty() && !seed.isEmpty()) {
            String trimmedSeed = seed.trim();
            if (trimmedSeed.startsWith("http://") || trimmedSeed.startsWith("https://")) {
                try {
                    java.net.URI u = java.net.URI.create(trimmedSeed);
                    if (u.getHost() != null && !u.getHost().trim().isEmpty()) {
                        hosts = u.getHost().trim();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (hosts.isEmpty()) {
            mainTabs.setSelectedIndex(TAB_TREE);
            treeWorkspacePanel.focusTargetHostInput();
            toast.showWarning(
                "Thiếu Target Host",
                "Nhập Target Host / URL nhánh (ví dụ: vnexpress.net/the-gioi hoặc *) hoặc dán Seed URL trước khi bật "
                    + (autoMode ? "Auto-Crawl." : "Capture."));
            refreshModeFromState();
            treeWorkspacePanel.refreshAutoCrawlTelemetry();
            return;
        }
        config.setAllowedHostsCsv(hosts);
        if (seed.isEmpty() && config.hasPathScopeRestriction()) {
            seed = config.getPrimaryAllowedEntryUrl();
        }
        config.setSeedUrl(seed);
        config.setAllowAutoRequests(autoMode);
        treeWorkspacePanel.syncInputsFromConfig();
        settingsPanel.syncFromConfig();
        persistConfigQuietly();

        if (state.getStatus() != CrawlState.Status.RUNNING) {
            boolean started = controller.startOrResumeCrawl(this, hosts, seed, null, () -> {
                if (autoMode) {
                    toast.showSuccess("Auto-Crawl đang chạy", "Target Host: " + config.getAllowedHostsCsv());
                } else {
                    toast.showInfo("Capture đang chạy", "Target Host: " + config.getAllowedHostsCsv());
                }
            });
            if (!started) {
                config.setAllowAutoRequests(false);
            }
        } else {
            toast.showInfo(
                autoMode ? "Đã chuyển sang Auto-Crawl" : "Đã chuyển sang Capture",
                "Target Host: " + config.getAllowedHostsCsv());
        }
        refreshModeFromState();
        treeWorkspacePanel.refreshAutoCrawlTelemetry();
    }

    private void togglePauseCrawlFromUi() {
        controller.togglePause();
        refreshModeFromState();
        treeWorkspacePanel.refreshAutoCrawlTelemetry();
    }

    private void promptHost() {
        JTextField tf = new JTextField(config.getAllowedHostsCsv(), 24);
        int res = JOptionPane.showConfirmDialog(this, tf, "Target Host(s) (hoặc *)",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            config.setAllowedHostsCsv(tf.getText());
            treeWorkspacePanel.syncInputsFromConfig();
            settingsPanel.syncFromConfig();
            persistConfigQuietly();
        }
    }

    private void installHotkeys() {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "spotlight");
        getActionMap().put("spotlight", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSpotlightDialog();
            }
        });

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focusTreeSearch");
        getActionMap().put("focusTreeSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mainTabs.getSelectedIndex() == TAB_TREE) {
                    treeWorkspacePanel.focusSearch();
                }
            }
        });
    }

    private void onImportExcel() {
        controller.importExcelAsync(this, newMatcher -> {
            targetExcelTab.setMatcher(newMatcher);
            targetExcelTab.refreshData();
        });
    }

    private void locateCallOnWeb(ApiCall call) {
        if (call != null && call.capture != null) {
            WebLocatorHelper.showLauncherMenu(this, state, call, this::logFromUi);
        }
    }

    private void updateTopBreadcrumb(String branch) {
        List<String> segs = new ArrayList<>();
        segs.add("Root");
        if (branch != null && !branch.trim().isEmpty() && !"/".equals(branch.trim())) {
            for (String p : branch.split("\\s*>\\s*")) {
                if (!p.trim().isEmpty()) {
                    segs.add(p.trim());
                }
            }
        }
        topBreadcrumb.setPath(segs, idx -> {
            if (idx <= 0) {
                treeWorkspacePanel.clearFocusBranch();
            }
        });
    }

    private void refreshModeFromState() {
        syncingControls = true;
        try {
            if (state.getStatus() != CrawlState.Status.RUNNING) {
                segMode.setSelectedIndex(0);
            } else if (config.isAllowAutoRequests()) {
                segMode.setSelectedIndex(2);
            } else {
                segMode.setSelectedIndex(1);
            }
            if (injector != null) {
                swAgentProxy.setSelected(injector.isAgentArmed());
            }
        } finally {
            syncingControls = false;
        }
    }

    private void refreshSafetySummary() {
        List<PolicyRule> rules = policyLedger.snapshotRules();
        int activeGuards = 0;
        boolean hasHold = false;
        boolean hasIntercept = false;
        for (PolicyRule r : rules) {
            if (r.enabled && !r.allow && r.mode != GuardrailMode.NONE) {
                activeGuards++;
                if (r.mode == GuardrailMode.HOLD_AT_PROXY) hasHold = true;
                if (r.mode == GuardrailMode.INTERCEPT_WAIT) hasIntercept = true;
            }
        }
        String label;
        Theme.Token tok;
        if (activeGuards == 0) {
            label = "Nguyên bản NONE";
            tok = Theme.Token.NEUTRAL;
        } else if (hasIntercept) {
            label = "Chặn POST+ (" + activeGuards + ")";
            tok = Theme.Token.PENDING;
        } else if (hasHold) {
            label = "Chỉ đọc GET (" + activeGuards + ")";
            tok = Theme.Token.INFO;
        } else {
            label = "Guardrail (" + activeGuards + ")";
            tok = Theme.Token.ACCENT;
        }
        btnSafetyScope.setToken(tok);
        btnSafetyScope.setLabelAndIcon(label, VectorIcons.shield(Theme.token(tok).text()));
    }

    private void refreshPendingCount() {
        int pending = interceptQueue.pendingCount();
        btnPendingQueue.setToken(pending > 0 ? Theme.Token.PENDING : Theme.Token.NEUTRAL);
        btnPendingQueue.setLabelAndIcon(
            "Chờ duyệt (" + pending + ")",
            VectorIcons.shieldAlert(Theme.token(pending > 0 ? Theme.Token.PENDING : Theme.Token.NEUTRAL).text()));
    }

    private void refreshActiveTab() {
        int idx = mainTabs.getSelectedIndex();
        if (idx == TAB_TREE) {
            treeWorkspacePanel.refreshTableRows();
        } else if (idx == TAB_GUARDRAILS) {
            guardrailLedgerTab.refreshData();
        } else if (idx == TAB_ROLES) {
            rolesTab.refreshData();
        } else if (idx == TAB_EXCEL) {
            targetExcelTab.refreshData();
        } else if (idx == TAB_PUSH) {
            pushLedgerPanel.refreshView();
        } else if (idx == TAB_PLAYBOOKS) {
            playbooksGuideTab.refreshData();
        } else if (idx == TAB_SETTINGS) {
            settingsAuxPanel.refreshData();
        }
    }

    private void persistConfigQuietly() {
        if (onPersistConfig != null) {
            try {
                onPersistConfig.run();
            } catch (Exception ex) {
                logFromUi("Không lưu được Preferences: " + ex.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Floating Modeless Dialog Launchers + Public Blueprint Accessors
    // -------------------------------------------------------------------------

    public JPanel getTopBar() {
        return topBar;
    }

    public JTabbedPane getMainTabs() {
        return mainTabs;
    }

    public TreeWorkspacePanel getTreeWorkspacePanel() {
        return treeWorkspacePanel;
    }

    public GuardrailLedgerTab getGuardrailLedgerTab() {
        return guardrailLedgerTab;
    }

    public RolesTab getRolesTab() {
        return rolesTab;
    }

    public TargetExcelTab getTargetExcelTab() {
        return targetExcelTab;
    }

    public PlaybooksGuideTab getPlaybooksGuideTab() {
        return playbooksGuideTab;
    }

    public InterceptQueueDialog openInterceptQueueDialog() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        if (interceptDialog == null || !interceptDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            interceptDialog = new InterceptQueueDialog(owner, interceptQueue, policyLedger, toast);
        }
        interceptDialog.refreshQueue();
        interceptDialog.setVisible(true);
        interceptDialog.toFront();
        return interceptDialog;
    }

    public JPanel createInterceptQueueViewPanel() {
        return InterceptQueueDialog.createQueuePanel(interceptQueue, policyLedger, toast);
    }

    public DuplicateLineageDialog openDuplicateLineageDialog(String endpointFilter) {
        if (lineageDialog == null || !lineageDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            lineageDialog = new DuplicateLineageDialog(owner, lineageTracker, endpointFilter);
        }
        lineageDialog.refreshData();
        lineageDialog.setVisible(true);
        lineageDialog.toFront();
        return lineageDialog;
    }

    public RequestComposerDialog openRequestComposerDialog(ApiCall initialCall) {
        if (composerDialog == null || !composerDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            composerDialog = new RequestComposerDialog(owner, roleStore, uiHost, toast, initialCall);
        } else if (initialCall != null) {
            composerDialog.loadApiCall(initialCall);
        }
        composerDialog.setVisible(true);
        composerDialog.toFront();
        return composerDialog;
    }

    public DetachedTreeDialog openDetachedTreeDialog() {
        if (detachedTreeDialog == null || !detachedTreeDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            detachedTreeDialog = new DetachedTreeDialog(owner, state, interceptQueue,
                branch -> treeWorkspacePanel.setFocusBranch(branch));
        }
        detachedTreeDialog.rebuild();
        detachedTreeDialog.setVisible(true);
        detachedTreeDialog.toFront();
        return detachedTreeDialog;
    }

    public SpotlightDialog openSpotlightDialog() {
        if (spotlightDialog == null || !spotlightDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            spotlightDialog = new SpotlightDialog(owner, this::collectSpotlightEntries);
        }
        spotlightDialog.openAndFocus();
        return spotlightDialog;
    }

    private List<SpotlightDialog.SearchEntry> collectSpotlightEntries() {
        List<SpotlightDialog.SearchEntry> out = new ArrayList<>();
        out.add(new SpotlightDialog.SearchEntry("TAB", "Cây làm việc", "Tree-First Explorer", Theme.Token.ACCENT,
            () -> mainTabs.setSelectedIndex(TAB_TREE)));
        out.add(new SpotlightDialog.SearchEntry("TAB", "Luật & Chặn", "Guardrail Policy Ledger", Theme.Token.INFO,
            () -> mainTabs.setSelectedIndex(TAB_GUARDRAILS)));
        out.add(new SpotlightDialog.SearchEntry("TAB", "Roles & Auth", "Multi-Role JWT & Auth", Theme.Token.DUP,
            () -> mainTabs.setSelectedIndex(TAB_ROLES)));
        out.add(new SpotlightDialog.SearchEntry("TAB", "Mục tiêu Excel", "Target Excel & {placeholder}", Theme.Token.SUCCESS,
            () -> mainTabs.setSelectedIndex(TAB_EXCEL)));
        out.add(new SpotlightDialog.SearchEntry("QUEUE", "Mở Hàng đợi Duyệt Proxy", interceptQueue.pendingCount() + " chờ duyệt",
            Theme.Token.PENDING, this::openInterceptQueueDialog));
        for (PolicyRule r : policyLedger.snapshotRules()) {
            out.add(new SpotlightDialog.SearchEntry("RULE", r.name, r.scope + " | " + r.mode,
                r.allow ? Theme.Token.SUCCESS : Theme.Token.PENDING,
                () -> mainTabs.setSelectedIndex(TAB_GUARDRAILS)));
        }
        for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
            out.add(new SpotlightDialog.SearchEntry("BRANCH", g.actionPath, g.callCount() + " API",
                Theme.Token.INFO, () -> {
                    mainTabs.setSelectedIndex(TAB_TREE);
                    treeWorkspacePanel.setFocusBranch(g.actionPath);
                }));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Preserved legacy entry points used by CrawlerExtension & CrawlerUnitTests
    // -------------------------------------------------------------------------

    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        if (unlistenLaf != null) {
            unlistenLaf.run();
        }
        closeSub(themeSub);
        closeSub(queueSub);
        closeSub(ledgerSub);
        treeWorkspacePanel.dispose();
        guardrailLedgerTab.dispose();
        rolesTab.dispose();
        targetExcelTab.dispose();
        disposeDialog(interceptDialog);
        disposeDialog(lineageDialog);
        disposeDialog(composerDialog);
        disposeDialog(detachedTreeDialog);
        disposeDialog(spotlightDialog);
    }

    private static void closeSub(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void disposeDialog(Window w) {
        if (w != null && w.isDisplayable()) {
            w.dispose();
        }
    }

    public void scheduleUiRefresh() {
        refreshPending.set(true);
    }

    public boolean isGroupTreeViewMode() {
        return treeViewMode;
    }

    public void setGroupViewMode(boolean treeMode) {
        this.treeViewMode = treeMode;
    }

    public void onPushLedgerUpdated() {
        Runnable task = () -> {
            pushLedgerPanel.refreshView();
            int pushSent = pushManager != null ? pushManager.sentCount() : 0;
            int pushTotal = pushManager != null ? pushManager.totalCount() : 0;
            if (mainTabs.getTabCount() > TAB_PUSH) {
                mainTabs.setTitleAt(TAB_PUSH, pushSent > 0
                    ? String.format("Đẩy Logger++ (%d/%d)", pushSent, pushTotal)
                    : String.format("Đẩy Logger++ (%d)", pushTotal));
            }
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
        String line = "[" + ts + "] " + msg;
        Runnable r = () -> settingsAuxPanel.appendLogLine(line);
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void logFromUi(String msg) {
        if (msg == null) {
            return;
        }
        state.addEventLog(msg);
        appendLog(msg);
    }

    public void applyTheme() {
        setBackground(Theme.canvas());
        topBar.setBackground(Theme.surface());
        topBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
            BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2)));
        Ui.styleTabbedPane(mainTabs);
        if (treeWorkspacePanel != null) {
            treeWorkspacePanel.applyTheme();
        }
        Ui.syncTreeColors(this);
        repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (topBar != null && mainTabs != null) {
            setBackground(Theme.canvas());
            topBar.setBackground(Theme.surface());
            mainTabs.setBackground(Theme.surface());
            mainTabs.setForeground(Theme.text());
        }
        super.paintComponent(g);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (topBar != null && mainTabs != null) {
            applyTheme();
        }
    }
}
