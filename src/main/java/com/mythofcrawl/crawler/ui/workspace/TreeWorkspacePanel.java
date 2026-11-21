package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.core.guardrail.PolicyScope;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.ChangesBatch;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.core.tree.NodeTypeCatalog;
import com.mythofcrawl.crawler.core.tree.VisualClusterStore;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.proxy.BrowserLauncher;
import com.mythofcrawl.crawler.ui.kit.Breadcrumb;
import com.mythofcrawl.crawler.ui.kit.MethodChipBar;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.ThemedTree;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Primary Tree-First Workspace (Default Primary Tab in V_1.0_beta).
 * Combines polymorphic Action/Branch/HeldPending/ApiGroup/Cluster/Ghost exploration,
 * Focus Mode, Visual Clusters, 0ms ProxyInterceptQueue push auto-expand without stealing focus,
 * 200ms ChangesBatch coalesced updates, and bottom InlineRequestDock.
 */
public final class TreeWorkspacePanel extends JPanel {

    private final CrawlState state;
    private final CrawlConfig config;
    private final PolicyLedger policyLedger;
    private final ProxyInterceptQueue interceptQueue;
    private final DuplicateLineageTracker lineageTracker;
    private final VisualClusterStore clusterStore;
    private final UiHost uiHost;
    private final ToastPane toast;

    private final Consumer<ApiCall> onLocateWeb;
    private final Consumer<String> onOpenLineageDialog;
    private final Consumer<ApiCall> onOpenComposer;
    private final Consumer<String> onFocusBranchChanged;
    private final Runnable onStartAutoCrawl;
    private final Runnable onTogglePauseCrawl;
    private final Runnable onPersistConfig;

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(
        new ThemedTree.NodeWrapper(
            ThemedTree.NodeKind.ROOT, "root", "Target Root (/)", "",
            NodeTypeCatalog.SCREEN.iconKey, "0", Theme.Token.ACCENT, 0, null));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final ThemedTree tree = new ThemedTree(treeModel);
    private final JScrollPane spTree = new JScrollPane(tree);

    private final ApiRowsTableModel tableModel = new ApiRowsTableModel();
    private final ThemedTable tblApis = new ThemedTable(tableModel);
    private final ThemedTable.EmptyWrap tableWrap;

    private final JPanel northContainer = new JPanel(new BorderLayout(0, 0));
    private final JPanel autoCrawlStrip = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JLabel lblHost = new JLabel("Host:");
    private final JTextField txtTargetHost = new JTextField(10);
    private final JLabel lblSeed = new JLabel("Seed:");
    private final JTextField txtSeedUrl = new JTextField(14);
    private final Ui.UiButton btnStartAuto;
    private final Ui.UiButton btnPauseAuto;
    private final Ui.UiButton btnOpenFirefox;
    private final Pill.StatusDot agentDot = new Pill.StatusDot(Theme.Token.NEUTRAL, 8);
    private final Pill agentStatusPill = new Pill("Chờ trình duyệt", Theme.Token.NEUTRAL, false);
    private final JLabel lblTelemetryCounters = new JLabel("Trang: 0 | Hàng đợi: 0 | Bước: 0/0 | IDLE");
    private boolean syncingAutoStrip = false;

    private final Breadcrumb breadcrumb = new Breadcrumb();
    private final SearchField searchField = new SearchField("Tìm nhánh, API, [CHỜ DUYỆT]...");
    private final MethodChipBar methodBar = new MethodChipBar();
    private final Ui.UiButton btnFocusMode;
    private final Ui.UiButton btnExpandAll;
    private final Ui.UiButton btnCollapseAll;
    private final Ui.UiButton btnCluster;
    private final JPanel toolbar = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
    private final Pill stalePill = new Pill("Đã đồng bộ", Theme.Token.SUCCESS, false);

    private final JSplitPane horizSplit;
    private final JSplitPane vertSplit;
    private final InlineRequestDock inlineDock;

    private final Map<String, DefaultMutableTreeNode> branchNodes = new LinkedHashMap<>();
    private final Map<String, DefaultMutableTreeNode> heldNodesById = new LinkedHashMap<>();

    private String focusBranchPath = "";
    private String selectedBranchFilter = "";
    private volatile boolean stale = false;
    private volatile boolean disposed = false;

    private InstantEventBus.Subscription queueSub;
    private InstantEventBus.Subscription ledgerSub;
    private InstantEventBus.Subscription clusterSub;
    private InstantEventBus.Subscription batchSub;
    private AutoCloseable themeSub;

    public TreeWorkspacePanel(
        CrawlState state,
        PolicyLedger policyLedger,
        ProxyInterceptQueue interceptQueue,
        DuplicateLineageTracker lineageTracker,
        VisualClusterStore clusterStore,
        InstantEventBus eventBus,
        ChangesBatch changesBatch,
        UiHost uiHost,
        ToastPane toast,
        Consumer<ApiCall> onLocateWeb,
        Consumer<String> onOpenLineageDialog,
        Consumer<ApiCall> onOpenComposer,
        Consumer<String> onFocusBranchChanged
    ) {
        this(state, null, policyLedger, interceptQueue, lineageTracker, clusterStore,
            eventBus, changesBatch, uiHost, toast, onLocateWeb, onOpenLineageDialog,
            onOpenComposer, onFocusBranchChanged, null, null, null);
    }

    public TreeWorkspacePanel(
        CrawlState state,
        CrawlConfig config,
        PolicyLedger policyLedger,
        ProxyInterceptQueue interceptQueue,
        DuplicateLineageTracker lineageTracker,
        VisualClusterStore clusterStore,
        InstantEventBus eventBus,
        ChangesBatch changesBatch,
        UiHost uiHost,
        ToastPane toast,
        Consumer<ApiCall> onLocateWeb,
        Consumer<String> onOpenLineageDialog,
        Consumer<ApiCall> onOpenComposer,
        Consumer<String> onFocusBranchChanged,
        Runnable onStartAutoCrawl,
        Runnable onTogglePauseCrawl,
        Runnable onPersistConfig
    ) {
        this.state = state;
        this.config = config;
        this.policyLedger = policyLedger;
        this.interceptQueue = interceptQueue;
        this.lineageTracker = lineageTracker;
        this.clusterStore = clusterStore;
        this.uiHost = uiHost;
        this.toast = toast;
        this.onLocateWeb = onLocateWeb;
        this.onOpenLineageDialog = onOpenLineageDialog;
        this.onOpenComposer = onOpenComposer;
        this.onFocusBranchChanged = onFocusBranchChanged;
        this.onStartAutoCrawl = onStartAutoCrawl;
        this.onTogglePauseCrawl = onTogglePauseCrawl;
        this.onPersistConfig = onPersistConfig;

        this.btnStartAuto = Ui.solidPrimary("Bắt đầu Auto", VectorIcons.play(Theme.inkWhite()));
        this.btnPauseAuto = Ui.secondaryBtn("Tạm dừng", VectorIcons.pause(Theme.text()));
        this.btnOpenFirefox = Ui.tonalBtn(Theme.Token.INFO, "Mở Firefox",
            VectorIcons.globe(Theme.token(Theme.Token.INFO).text()));

        this.btnFocusMode = Ui.secondaryBtn("Focus", VectorIcons.eye(Theme.text()));
        this.btnExpandAll = Ui.iconBtn(VectorIcons.expand(Theme.text()), "Mở rộng tất cả nhánh");
        this.btnCollapseAll = Ui.iconBtn(VectorIcons.collapse(Theme.text()), "Thu gọn tất cả nhánh");
        this.btnCluster = Ui.secondaryBtn("Nhóm Cluster", VectorIcons.folder(Theme.text()));

        this.inlineDock = new InlineRequestDock(
            state, interceptQueue, lineageTracker, uiHost, toast, onLocateWeb);

        this.tableWrap = ThemedTable.wrapWithEmptyState(
            tblApis,
            "Chưa có API trong nhánh này",
            "Bắt đầu duyệt Web hoặc chọn một nhánh khác trên cây bên trái."
        );

        setLayout(new BorderLayout(0, 0));
        buildAutoCrawlStrip();
        buildToolbar();
        configureTable();
        configureTree();

        northContainer.setOpaque(false);
        northContainer.add(autoCrawlStrip, BorderLayout.NORTH);
        northContainer.add(toolbar, BorderLayout.SOUTH);

        spTree.setPreferredSize(new Dimension(440, 200));
        spTree.setMinimumSize(new Dimension(360, 120));

        horizSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spTree, tableWrap);
        horizSplit.setDividerLocation(440);
        horizSplit.setResizeWeight(0.36);

        vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, horizSplit, inlineDock);
        vertSplit.setResizeWeight(0.82);

        add(northContainer, BorderLayout.NORTH);
        add(vertSplit, BorderLayout.CENTER);

        syncInputsFromConfig();
        wireSubscriptions(changesBatch);
        rebuildTree();
        refreshTableRows();
        refreshAutoCrawlTelemetry();
        applyTheme();
    }

    private void buildAutoCrawlStrip() {
        autoCrawlStrip.setOpaque(true);
        autoCrawlStrip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
            BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2)));

        txtTargetHost.setToolTipText("Target Host(s) phân tách bằng dấu phẩy (ví dụ: app.example.com hoặc *)");
        txtSeedUrl.setToolTipText("Seed URL đầy đủ (ví dụ: https://app.example.com/dashboard)");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
        left.setOpaque(false);
        left.add(lblHost);
        left.add(txtTargetHost);
        left.add(lblSeed);
        left.add(txtSeedUrl);
        left.add(btnStartAuto);
        left.add(btnPauseAuto);
        left.add(btnOpenFirefox);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        right.setOpaque(false);
        right.add(agentDot);
        right.add(agentStatusPill);
        right.add(lblTelemetryCounters);

        autoCrawlStrip.add(left, BorderLayout.CENTER);
        autoCrawlStrip.add(right, BorderLayout.EAST);

        txtTargetHost.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onHostEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onHostEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onHostEdited(); }
        });
        txtSeedUrl.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onSeedEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onSeedEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onSeedEdited(); }
        });
        FocusAdapter persistOnBlur = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (onPersistConfig != null) {
                    onPersistConfig.run();
                }
            }
        };
        txtTargetHost.addFocusListener(persistOnBlur);
        txtSeedUrl.addFocusListener(persistOnBlur);
        txtTargetHost.addActionListener(e -> triggerStartAuto());
        txtSeedUrl.addActionListener(e -> triggerStartAuto());

        btnStartAuto.addActionListener(e -> triggerStartAuto());
        btnPauseAuto.addActionListener(e -> {
            if (onTogglePauseCrawl != null) {
                onTogglePauseCrawl.run();
            }
            refreshAutoCrawlTelemetry();
        });
        btnOpenFirefox.addActionListener(e -> onLaunchFirefoxClicked());
    }

    private void onHostEdited() {
        if (syncingAutoStrip || config == null) {
            return;
        }
        config.setAllowedHostsCsv(txtTargetHost.getText().trim());
    }

    private void onSeedEdited() {
        if (syncingAutoStrip || config == null) {
            return;
        }
        String seed = txtSeedUrl.getText().trim();
        config.setSeedUrl(seed);
        if (txtTargetHost.getText().trim().isEmpty() && !seed.isEmpty()) {
            String host = extractHostFromUrl(seed);
            if (!host.isEmpty()) {
                syncingAutoStrip = true;
                try {
                    txtTargetHost.setText(host);
                    config.setAllowedHostsCsv(host);
                } finally {
                    syncingAutoStrip = false;
                }
            }
        }
    }

    private void triggerStartAuto() {
        if (config != null) {
            String seed = txtSeedUrl.getText().trim();
            String hosts = txtTargetHost.getText().trim();
            if (hosts.isEmpty() && !seed.isEmpty()) {
                String extracted = extractHostFromUrl(seed);
                if (!extracted.isEmpty()) {
                    hosts = extracted;
                    syncingAutoStrip = true;
                    try {
                        txtTargetHost.setText(hosts);
                    } finally {
                        syncingAutoStrip = false;
                    }
                }
            }
            config.setAllowedHostsCsv(hosts);
            config.setSeedUrl(seed);
        }
        if (onStartAutoCrawl != null) {
            onStartAutoCrawl.run();
        }
        refreshAutoCrawlTelemetry();
    }

    private void onLaunchFirefoxClicked() {
        String url = txtSeedUrl.getText().trim();
        if (url.isEmpty()) {
            String hostCsv = txtTargetHost.getText().trim();
            if (hostCsv.isEmpty() && config != null) {
                hostCsv = config.getAllowedHostsCsv();
            }
            if (hostCsv != null && !hostCsv.isEmpty()) {
                String first = hostCsv.split("[,;\\s]+")[0].trim();
                if (!first.isEmpty() && !"*".equals(first)) {
                    url = first.startsWith("http://") || first.startsWith("https://")
                        ? first : "https://" + first;
                }
            }
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.isEmpty()) {
            focusTargetHostInput();
            if (toast != null) {
                toast.showWarning("Thiếu URL mở trình duyệt",
                    "Hãy nhập Seed URL (https://...) hoặc Target Host trước khi mở Firefox.");
            }
            return;
        }
        BrowserLauncher.LaunchResult res = BrowserLauncher.launch(url, BrowserLauncher.BrowserChoice.FIREFOX);
        if (toast != null) {
            if (res.ok) {
                toast.showSuccess("Mở " + res.browserUsed, res.message);
            } else {
                toast.showWarning("Không mở được trình duyệt", res.message);
            }
        }
    }

    private static String extractHostFromUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("http://")
            && !trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "";
        }
        try {
            URI u = URI.create(trimmed);
            return u.getHost() != null ? u.getHost().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public String getTargetHostInput() {
        return txtTargetHost.getText().trim();
    }

    public String getSeedUrlInput() {
        return txtSeedUrl.getText().trim();
    }

    public void focusTargetHostInput() {
        txtTargetHost.requestFocusInWindow();
        txtTargetHost.selectAll();
    }

    public void syncInputsFromConfig() {
        if (config == null) {
            return;
        }
        syncingAutoStrip = true;
        try {
            if (!txtTargetHost.isFocusOwner()) {
                String h = config.getAllowedHostsCsv();
                if (!txtTargetHost.getText().equals(h)) {
                    txtTargetHost.setText(h);
                }
            }
            if (!txtSeedUrl.isFocusOwner()) {
                String s = config.getSeedUrl();
                if (!txtSeedUrl.getText().equals(s)) {
                    txtSeedUrl.setText(s);
                }
            }
        } finally {
            syncingAutoStrip = false;
        }
    }

    public void refreshAutoCrawlTelemetry() {
        if (state == null) {
            return;
        }
        CrawlState.AgentTelemetry tel = state.getAgentTelemetrySnapshot();
        int visited = state.getVisitedPagesCount();
        int queued = state.getPendingQueueSize();
        String phase = tel != null && tel.phase != null && !tel.phase.isEmpty() ? tel.phase : "IDLE";
        int done = tel != null ? tel.actionsDoneOnPage : 0;
        int total = tel != null ? tel.totalTargetsOnPage : 0;
        boolean connected = tel != null && tel.connected;

        if ("WAITING_APPROVAL".equals(phase)) {
            agentDot.setToken(Theme.Token.PENDING);
            agentStatusPill.setPill("Chờ duyệt Burp", Theme.Token.PENDING, false);
        } else if (connected) {
            agentDot.setToken(Theme.Token.SUCCESS);
            agentStatusPill.setPill("Agent: Đang kết nối", Theme.Token.SUCCESS, false);
        } else {
            agentDot.setToken(Theme.Token.NEUTRAL);
            agentStatusPill.setPill("Chờ trình duyệt", Theme.Token.NEUTRAL, false);
        }

        lblTelemetryCounters.setText(
            "Trang: " + visited
            + " | Hàng đợi: " + queued
            + " | Bước: " + done + "/" + total
            + " | " + phase);

        CrawlState.Status st = state.getStatus();
        boolean paused = st == CrawlState.Status.PAUSED;
        boolean runningOrPaused = st == CrawlState.Status.RUNNING || paused;
        btnPauseAuto.setEnabled(runningOrPaused);
        btnPauseAuto.setLabelAndIcon(
            paused ? "Tiếp tục" : "Tạm dừng",
            paused ? VectorIcons.play(Theme.text()) : VectorIcons.pause(Theme.text()));
    }

    private void buildToolbar() {
        toolbar.setOpaque(true);
        toolbar.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2));

        JPanel topRow = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        topRow.setOpaque(false);
        breadcrumb.setPath(List.of("Root (/)"), i -> clearFocusBranch());
        topRow.add(breadcrumb, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        rightControls.setOpaque(false);
        rightControls.add(stalePill);
        rightControls.add(btnFocusMode);
        rightControls.add(btnCluster);
        rightControls.add(btnExpandAll);
        rightControls.add(btnCollapseAll);
        rightControls.add(searchField);
        topRow.add(rightControls, BorderLayout.EAST);

        toolbar.add(topRow, BorderLayout.NORTH);
        toolbar.add(methodBar, BorderLayout.SOUTH);

        searchField.onSearchChange(q -> {
            rebuildTree();
            refreshTableRows();
        });
        methodBar.onChange(set -> refreshTableRows());
        btnExpandAll.addActionListener(e -> expandAll());
        btnCollapseAll.addActionListener(e -> collapseAll());
        btnFocusMode.addActionListener(e -> toggleFocusModeOnSelected());
        btnCluster.addActionListener(e -> promptCreateCluster());
    }

    private void configureTable() {
        tblApis.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblApis.setDefaultRenderer(Object.class, new ApiRowCellRenderer());
        tblApis.setDefaultRenderer(Integer.class, new ApiRowCellRenderer());
        if (tblApis.getColumnModel().getColumnCount() >= 6) {
            tblApis.getColumnModel().getColumn(0).setPreferredWidth(70);
            tblApis.getColumnModel().getColumn(1).setPreferredWidth(250);
            tblApis.getColumnModel().getColumn(2).setPreferredWidth(68);
            tblApis.getColumnModel().getColumn(3).setPreferredWidth(180);
            tblApis.getColumnModel().getColumn(4).setPreferredWidth(120);
            tblApis.getColumnModel().getColumn(5).setPreferredWidth(85);
        }

        tblApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblApis.getSelectedRow();
                if (vr >= 0) {
                    DisplayRow row = tableModel.getRowAt(tblApis.convertRowIndexToModel(vr));
                    if (row != null) {
                        if (row.pendingItem != null) {
                            inlineDock.showPendingItem(row.pendingItem, false);
                        } else if (row.call != null) {
                            inlineDock.showApiCall(row.call);
                        }
                    }
                }
            }
        });
        tblApis.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblApis.getSelectedRow() >= 0) {
                    inlineDock.setExpanded(true);
                }
            }
        });
    }

    private void configureTree() {
        tree.setRootVisible(true);
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) {
                return;
            }
            ThemedTree.NodeWrapper w = ThemedTree.NodeWrapper.unwrap(node);
            if (w == null) {
                return;
            }
            if (w.kind == ThemedTree.NodeKind.HELD_PENDING
                && w.payload instanceof ProxyInterceptQueue.PendingInterceptItem) {
                ProxyInterceptQueue.PendingInterceptItem item =
                    (ProxyInterceptQueue.PendingInterceptItem) w.payload;
                selectedBranchFilter = item.branchPath;
                updateBreadcrumb(item.branchPath);
                inlineDock.showPendingItem(item, false);
            } else if (w.kind == ThemedTree.NodeKind.API_GROUP && w.payload instanceof ActionGroup) {
                ActionGroup g = (ActionGroup) w.payload;
                selectedBranchFilter = g.actionPath;
                updateBreadcrumb(g.actionPath);
                List<ApiCall> calls = g.getCalls();
                if (!calls.isEmpty()) {
                    inlineDock.showApiCall(calls.get(0));
                }
            } else if (w.kind == ThemedTree.NodeKind.BRANCH) {
                selectedBranchFilter = w.id;
                updateBreadcrumb(w.id);
            } else if (w.kind == ThemedTree.NodeKind.ROOT) {
                selectedBranchFilter = "";
                updateBreadcrumb("");
            }
            refreshTableRows();
        });

        JPopupMenu popup = buildTreePopupMenu();
        tree.addMouseListener(new MouseAdapter() {
            private void maybePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tree.getRowForLocation(e.getX(), e.getY());
                    if (row >= 0) {
                        tree.setSelectionRow(row);
                    }
                    popup.show(tree, e.getX(), e.getY());
                }
            }

            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                    ThemedTree.NodeWrapper w = ThemedTree.NodeWrapper.unwrap(node);
                    if (w != null && w.kind == ThemedTree.NodeKind.HELD_PENDING) {
                        inlineDock.setExpanded(true);
                    }
                }
            }
        });
    }

    private JPopupMenu buildTreePopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miFocus = new JMenuItem("Focus nhánh này");
        JMenuItem miClearFocus = new JMenuItem("Bỏ Focus (Hiện toàn bộ cây)");
        JMenuItem miBlockBranch = new JMenuItem("Chặn duyệt POST+ trên nhánh này (Guardrail)");
        JMenuItem miReleaseBranch = new JMenuItem("Nhả nhánh này về Nguyên bản (NONE)");
        JMenuItem miLineage = new JMenuItem("Xem Dòng dõi trùng lặp");
        JMenuItem miComposer = new JMenuItem("Mở Request Composer đa Role");
        JMenuItem miLocate = new JMenuItem("Tìm vị trí trên Web");

        miFocus.addActionListener(e -> {
            if (!selectedBranchFilter.isEmpty()) {
                setFocusBranch(selectedBranchFilter);
            }
        });
        miClearFocus.addActionListener(e -> clearFocusBranch());
        miBlockBranch.addActionListener(e -> {
            if (policyLedger != null && !selectedBranchFilter.isEmpty()) {
                PolicyRule r = new PolicyRule();
                r.name = "Intercept " + selectedBranchFilter;
                r.scope = PolicyScope.BRANCH;
                r.branchPrefix = selectedBranchFilter;
                r.methods.addAll(Set.of("POST", "PUT", "PATCH", "DELETE"));
                policyLedger.addRule(r);
                if (toast != null) {
                    toast.showInfo("Đã thêm Guardrail", "Chặn duyệt tại nhánh: " + selectedBranchFilter);
                }
            }
        });
        miReleaseBranch.addActionListener(e -> {
            if (policyLedger != null && !selectedBranchFilter.isEmpty()) {
                policyLedger.releaseBranchToNone(selectedBranchFilter);
                if (toast != null) {
                    toast.showSuccess("Đã nhả NONE", "Nhánh: " + selectedBranchFilter);
                }
            }
        });
        miLineage.addActionListener(e -> {
            String ep = resolveSelectedEndpointKey();
            if (onOpenLineageDialog != null) {
                onOpenLineageDialog.accept(ep);
            }
        });
        miComposer.addActionListener(e -> {
            ApiCall call = resolveSelectedApiCall();
            if (onOpenComposer != null) {
                onOpenComposer.accept(call);
            }
        });
        miLocate.addActionListener(e -> {
            ApiCall call = resolveSelectedApiCall();
            if (call != null && onLocateWeb != null) {
                onLocateWeb.accept(call);
            }
        });

        menu.add(miFocus);
        menu.add(miClearFocus);
        menu.addSeparator();
        menu.add(miBlockBranch);
        menu.add(miReleaseBranch);
        menu.addSeparator();
        menu.add(miLineage);
        menu.add(miComposer);
        menu.add(miLocate);
        return menu;
    }

    private void wireSubscriptions(ChangesBatch changesBatch) {
        if (interceptQueue != null) {
            queueSub = interceptQueue.subscribe(ev -> {
                if (disposed || ev == null) {
                    return;
                }
                Runnable onEdt = () -> {
                    if (ev.kind == ProxyInterceptQueue.QueueEventKind.ENQUEUED && ev.item != null) {
                        insertHeldItemImmediatelyAndExpand(ev.item);
                        inlineDock.showPendingIfNotEditing(ev.item);
                    } else {
                        rebuildTree();
                    }
                    refreshTableRows();
                };
                if (SwingUtilities.isEventDispatchThread()) {
                    onEdt.run();
                } else {
                    SwingUtilities.invokeLater(onEdt);
                }
            });
        }
        if (policyLedger != null) {
            ledgerSub = policyLedger.subscribe(() -> runOnEdt(this::rebuildTree));
        }
        if (clusterStore != null) {
            clusterSub = clusterStore.subscribe(() -> runOnEdt(this::rebuildTree));
        }
        if (changesBatch != null) {
            batchSub = changesBatch.onFlush(snap -> {
                if (disposed || snap == null || !snap.hasAnyChange()) {
                    return;
                }
                if (snap.dirty || snap.treeStale || snap.tableStale || snap.addedCount > 0 || snap.structureChanged) {
                    markStale();
                    runOnEdt(this::refreshFromBatch);
                }
            });
        }
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public void markStale() {
        this.stale = true;
        runOnEdt(() -> stalePill.setPill("Đang cập nhật", Theme.Token.PENDING, false));
    }

    public boolean isStale() {
        return stale;
    }

    public void refreshFromBatch() {
        rebuildTree();
        refreshTableRows();
        this.stale = false;
        stalePill.setPill("Đã đồng bộ", Theme.Token.SUCCESS, false);
    }

    /**
     * 0ms zero-delay push handler: inserts [CHỜ DUYỆT] directly under its branch node,
     * expands collapsed parent branches immediately, and scrolls the held node into view.
     */
    public void insertHeldItemImmediatelyAndExpand(ProxyInterceptQueue.PendingInterceptItem item) {
        if (item == null) {
            return;
        }
        String branch = item.branchPath.isEmpty() ? "/" : item.branchPath;
        DefaultMutableTreeNode parent = ensureBranchChain(branch);
        if (heldNodesById.containsKey(item.id)) {
            DefaultMutableTreeNode existing = heldNodesById.get(item.id);
            tree.expandPath(new TreePath(parent.getPath()));
            tree.scrollPathToVisible(new TreePath(existing.getPath()));
            return;
        }

        DefaultMutableTreeNode heldNode = new DefaultMutableTreeNode(
            new ThemedTree.NodeWrapper(
                ThemedTree.NodeKind.HELD_PENDING,
                item.id,
                "[CHỜ DUYỆT] " + item.method + " " + item.path,
                item.host,
                "HELD_PENDING",
                "CHỜ DUYỆT",
                Theme.Token.PENDING,
                countDepth(branch) + 1,
                item
            ));
        parent.insert(heldNode, 0);
        heldNodesById.put(item.id, heldNode);
        treeModel.nodesWereInserted(parent, new int[]{0});

        TreePath parentPath = new TreePath(parent.getPath());
        tree.expandPath(parentPath);
        TreePath heldPath = new TreePath(heldNode.getPath());
        tree.scrollPathToVisible(heldPath);
    }

    public void rebuildTree() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        rootNode.removeAllChildren();
        branchNodes.clear();
        heldNodesById.clear();

        int totalItems = 0;

        // 1. Live pending [CHỜ DUYỆT] items
        if (interceptQueue != null) {
            for (ProxyInterceptQueue.PendingInterceptItem item : interceptQueue.snapshotPending()) {
                if (!matchesBranchFocusAndQuery(item.branchPath, item.method + " " + item.path + " chờ duyệt", q)) {
                    continue;
                }
                DefaultMutableTreeNode parent = ensureBranchChain(item.branchPath.isEmpty() ? "/" : item.branchPath);
                DefaultMutableTreeNode held = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.HELD_PENDING,
                        item.id,
                        "[CHỜ DUYỆT] " + item.method + " " + item.path,
                        item.host,
                        "HELD_PENDING",
                        "CHỜ DUYỆT",
                        Theme.Token.PENDING,
                        countDepth(item.branchPath) + 1,
                        item
                    ));
                parent.add(held);
                heldNodesById.put(item.id, held);
                totalItems++;
            }
        }

        // 2. Action groups & captured APIs
        if (state != null) {
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                if (!matchesBranchFocusAndQuery(g.actionPath, g.actionPath + " " + g.label, q)) {
                    continue;
                }
                DefaultMutableTreeNode parent = ensureBranchChain(g.actionPath);
                int callCount = g.callCount();
                String badge = callCount > 0 ? String.valueOf(callCount) : "";
                Theme.Token bTok = g.noteCount() > 0 ? Theme.Token.WARNING : Theme.Token.INFO;
                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.API_GROUP,
                        "grp-" + g.id,
                        g.label.isEmpty() ? g.actionPath : g.label,
                        callCount + " API",
                        mapKindToIconKey(g.kind),
                        badge,
                        bTok,
                        countDepth(g.actionPath) + 1,
                        g
                    ));
                parent.add(groupNode);
                totalItems += Math.max(1, callCount);
            }

            // 3. Static JS Ghost endpoints
            List<String> ghosts = state.getDiscoveredJsEndpoints();
            if (!ghosts.isEmpty() && focusBranchPath.isEmpty()) {
                DefaultMutableTreeNode ghostFolder = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.GHOSTS,
                        "ghosts-root",
                        "Ghost APIs từ JS",
                        ghosts.size() + " endpoints",
                        NodeTypeCatalog.GHOST.iconKey,
                        String.valueOf(ghosts.size()),
                        Theme.Token.GHOST,
                        1,
                        null
                    ));
                for (String ep : ghosts) {
                    if (!q.isEmpty() && !ep.toLowerCase(Locale.ROOT).contains(q)) {
                        continue;
                    }
                    ghostFolder.add(new DefaultMutableTreeNode(
                        new ThemedTree.NodeWrapper(
                            ThemedTree.NodeKind.GHOSTS,
                            "ghost-" + ep,
                            ep,
                            "Static JS",
                            NodeTypeCatalog.GHOST.iconKey,
                            "GHOST",
                            Theme.Token.GHOST,
                            2,
                            ep
                        )));
                }
                if (ghostFolder.getChildCount() > 0) {
                    rootNode.add(ghostFolder);
                }
            }
        }

        // 4. Visual Clusters
        if (clusterStore != null) {
            for (VisualClusterStore.VisualCluster cl : clusterStore.snapshotAll()) {
                if (!matchesBranchFocusAndQuery(cl.branchPath, cl.name, q)) {
                    continue;
                }
                DefaultMutableTreeNode parent = ensureBranchChain(cl.branchPath);
                parent.add(new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.CLUSTER,
                        cl.id,
                        cl.name,
                        cl.endpointKeys.size() + " API",
                        "CLUSTER",
                        "CLUSTER",
                        Theme.Token.DUP,
                        countDepth(cl.branchPath) + 1,
                        cl
                    )));
            }
        }

        rootNode.setUserObject(new ThemedTree.NodeWrapper(
            ThemedTree.NodeKind.ROOT,
            "root",
            focusBranchPath.isEmpty() ? "Target Root (/)" : ("Focus: " + focusBranchPath),
            totalItems + " mục",
            NodeTypeCatalog.SCREEN.iconKey,
            String.valueOf(totalItems),
            Theme.Token.ACCENT,
            0,
            null
        ));

        treeModel.reload();
        // Auto-expand top level + any branch holding [CHỜ DUYỆT]
        tree.expandPath(new TreePath(rootNode.getPath()));
        for (DefaultMutableTreeNode held : heldNodesById.values()) {
            if (held.getParent() instanceof DefaultMutableTreeNode) {
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) held.getParent()).getPath()));
            }
        }
    }

    private DefaultMutableTreeNode ensureBranchChain(String rawBranchPath) {
        String clean = (rawBranchPath == null || rawBranchPath.trim().isEmpty()) ? "/" : rawBranchPath.trim();
        if ("/".equals(clean)) {
            return branchNodes.computeIfAbsent("/", k -> {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.BRANCH, "/", "Trang chủ (/)", "",
                        NodeTypeCatalog.SCREEN.iconKey, "", Theme.Token.DEPTH_0, 0, null));
                rootNode.add(n);
                return n;
            });
        }
        String[] segs = clean.split("\\s*>\\s*");
        DefaultMutableTreeNode curParent = rootNode;
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i].trim();
            if (seg.isEmpty()) {
                continue;
            }
            if (acc.length() > 0) {
                acc.append(" > ");
            }
            acc.append(seg);
            final String pathKey = acc.toString();
            final int depth = i;
            final DefaultMutableTreeNode parentRef = curParent;
            curParent = branchNodes.computeIfAbsent(pathKey, k -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.BRANCH,
                        pathKey,
                        seg,
                        "",
                        inferBranchIconKey(seg, depth),
                        "",
                        Theme.forDepthToken(depth),
                        depth,
                        null
                    ));
                parentRef.add(node);
                return node;
            });
        }
        return curParent;
    }

    public DefaultMutableTreeNode findBranchNode(String branchPath) {
        if (branchPath == null) {
            return null;
        }
        return branchNodes.get(branchPath.trim());
    }

    private boolean matchesBranchFocusAndQuery(String branchPath, String searchable, String q) {
        String bp = branchPath != null ? branchPath : "";
        if (!focusBranchPath.isEmpty() && !bp.startsWith(focusBranchPath)) {
            return false;
        }
        if (q == null || q.isEmpty()) {
            return true;
        }
        return (searchable != null && searchable.toLowerCase(Locale.ROOT).contains(q))
            || bp.toLowerCase(Locale.ROOT).contains(q);
    }

    private static int countDepth(String branchPath) {
        if (branchPath == null || branchPath.trim().isEmpty()) {
            return 0;
        }
        return branchPath.split("\\s*>\\s*").length;
    }

    private static String inferBranchIconKey(String seg, int depth) {
        String low = seg.toLowerCase(Locale.ROOT);
        if (low.contains("modal") || low.contains("dialog")) {
            return NodeTypeCatalog.MODAL.iconKey;
        }
        if (low.contains("menu") || low.contains("nav")) {
            return NodeTypeCatalog.MENU.iconKey;
        }
        if (low.contains("form")) {
            return NodeTypeCatalog.FORM.iconKey;
        }
        if (low.contains("button") || low.contains("btn")) {
            return NodeTypeCatalog.BUTTON.iconKey;
        }
        return depth == 0 ? NodeTypeCatalog.SCREEN.iconKey : NodeTypeCatalog.MENU.iconKey;
    }

    private static String mapKindToIconKey(String elementKind) {
        if (elementKind == null) {
            return NodeTypeCatalog.API.iconKey;
        }
        String low = elementKind.toLowerCase(Locale.ROOT);
        if (low.contains("form") || low.contains("submit")) {
            return NodeTypeCatalog.FORM.iconKey;
        }
        if (low.contains("button") || low.contains("click")) {
            return NodeTypeCatalog.BUTTON.iconKey;
        }
        if (low.contains("link") || low.contains("nav")) {
            return NodeTypeCatalog.MENU.iconKey;
        }
        return NodeTypeCatalog.API.iconKey;
    }

    public void refreshTableRows() {
        List<DisplayRow> out = new ArrayList<>();
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);

        if (interceptQueue != null) {
            for (ProxyInterceptQueue.PendingInterceptItem p : interceptQueue.snapshotPending()) {
                if (!matchesSelectedBranchAndFilter(p.branchPath, p.method, p.path + " " + p.reason, q)) {
                    continue;
                }
                out.add(new DisplayRow(
                    p.method,
                    p.path,
                    0,
                    p.branchPath,
                    "CHỜ DUYỆT",
                    Theme.Token.PENDING,
                    p,
                    null
                ));
            }
        }

        if (state != null) {
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                for (ApiCall call : g.getCalls()) {
                    if (call.capture == null) {
                        continue;
                    }
                    String m = call.capture.method;
                    String path = call.displayPath();
                    if (!matchesSelectedBranchAndFilter(g.actionPath, m, path + " " + g.actionPath, q)) {
                        continue;
                    }
                    int st = call.capture.statusCode;
                    String statusTxt = st > 0 ? ("HTTP " + st) : "CAPTURED";
                    out.add(new DisplayRow(
                        m,
                        path,
                        st,
                        g.actionPath,
                        statusTxt,
                        Theme.forStatusToken(st),
                        null,
                        call
                    ));
                }
            }
        }

        tableModel.setRows(out);
        tableWrap.refreshEmptyState();
    }

    private boolean matchesSelectedBranchAndFilter(String branch, String method, String haystack, String q) {
        String b = branch != null ? branch : "";
        if (!focusBranchPath.isEmpty() && !b.startsWith(focusBranchPath)) {
            return false;
        }
        if (!selectedBranchFilter.isEmpty() && !b.startsWith(selectedBranchFilter)) {
            return false;
        }
        if (!methodBar.matches(method)) {
            return false;
        }
        if (!q.isEmpty() && !haystack.toLowerCase(Locale.ROOT).contains(q)) {
            return false;
        }
        return true;
    }

    private void updateBreadcrumb(String branch) {
        List<String> parts = new ArrayList<>();
        parts.add("Root (/)");
        if (branch != null && !branch.trim().isEmpty() && !"/".equals(branch.trim())) {
            for (String s : branch.split("\\s*>\\s*")) {
                if (!s.trim().isEmpty()) {
                    parts.add(s.trim());
                }
            }
        }
        breadcrumb.setPath(parts, idx -> {
            if (idx <= 0) {
                selectedBranchFilter = "";
                clearFocusBranch();
            } else {
                StringBuilder sub = new StringBuilder();
                for (int i = 1; i <= idx && i < parts.size(); i++) {
                    if (sub.length() > 0) {
                        sub.append(" > ");
                    }
                    sub.append(parts.get(i));
                }
                selectedBranchFilter = sub.toString();
                refreshTableRows();
            }
        });
    }

    private void toggleFocusModeOnSelected() {
        if (!focusBranchPath.isEmpty()) {
            clearFocusBranch();
            return;
        }
        if (!selectedBranchFilter.isEmpty()) {
            setFocusBranch(selectedBranchFilter);
        } else if (toast != null) {
            toast.showInfo("Chọn nhánh", "Hãy chọn một nhánh trên cây trước khi bật Focus Mode.");
        }
    }

    public void setFocusBranch(String branchPath) {
        this.focusBranchPath = branchPath != null ? branchPath.trim() : "";
        btnFocusMode.setSelectedVisual(!this.focusBranchPath.isEmpty());
        btnFocusMode.setLabelAndIcon(
            this.focusBranchPath.isEmpty() ? "Focus" : "Bỏ Focus",
            VectorIcons.eye(this.focusBranchPath.isEmpty() ? Theme.text() : Theme.token(Theme.Token.ACCENT).text())
        );
        rebuildTree();
        refreshTableRows();
        if (onFocusBranchChanged != null) {
            onFocusBranchChanged.accept(this.focusBranchPath);
        }
    }

    public void clearFocusBranch() {
        setFocusBranch("");
    }

    private void promptCreateCluster() {
        if (clusterStore == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Tên Visual Cluster mới:", "Tạo Cluster", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            String branch = selectedBranchFilter.isEmpty() ? "/" : selectedBranchFilter;
            VisualClusterStore.VisualCluster cl = clusterStore.createCluster(branch, name.trim());
            String ep = resolveSelectedEndpointKey();
            if (!ep.isEmpty()) {
                clusterStore.assignEndpoint(cl.id, ep);
            }
            if (toast != null) {
                toast.showSuccess("Đã tạo Cluster", cl.name + " tại " + branch);
            }
        }
    }

    private String resolveSelectedEndpointKey() {
        int vr = tblApis.getSelectedRow();
        if (vr >= 0) {
            DisplayRow r = tableModel.getRowAt(tblApis.convertRowIndexToModel(vr));
            if (r != null) {
                return r.method + " " + r.path;
            }
        }
        return "";
    }

    private ApiCall resolveSelectedApiCall() {
        int vr = tblApis.getSelectedRow();
        if (vr >= 0) {
            DisplayRow r = tableModel.getRowAt(tblApis.convertRowIndexToModel(vr));
            if (r != null && r.call != null) {
                return r.call;
            }
        }
        return null;
    }

    public void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    public void collapseAll() {
        for (int i = tree.getRowCount() - 1; i >= 1; i--) {
            tree.collapseRow(i);
        }
    }

    public void focusSearch() {
        searchField.focusInput();
    }

    public ThemedTree getTree() {
        return tree;
    }

    public InlineRequestDock getInlineDock() {
        return inlineDock;
    }

    public void applyTheme() {
        setBackground(Theme.canvas());
        autoCrawlStrip.setBackground(Theme.surfaceAlt());
        autoCrawlStrip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
            BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2)));
        lblHost.setFont(FontManager.uiSmall());
        lblHost.setForeground(Theme.textSecondary());
        lblSeed.setFont(FontManager.uiSmall());
        lblSeed.setForeground(Theme.textSecondary());
        lblTelemetryCounters.setFont(FontManager.monoSmall());
        lblTelemetryCounters.setForeground(Theme.textSecondary());
        styleCompactField(txtTargetHost);
        styleCompactField(txtSeedUrl);
        toolbar.setBackground(Theme.surface());
        spTree.getViewport().setBackground(Theme.surface());
        spTree.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        tree.applyTheme();
        tableWrap.applyTheme();
        inlineDock.applyTheme();
        horizSplit.setBackground(Theme.canvas());
        vertSplit.setBackground(Theme.canvas());
        refreshAutoCrawlTelemetry();
    }

    private static void styleCompactField(JTextField field) {
        field.setFont(FontManager.monoSmall());
        field.setBackground(Theme.surface());
        field.setForeground(Theme.text());
        field.setCaretColor(Theme.text());
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.border(), 1, true),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (toolbar != null && spTree != null && inlineDock != null) {
            applyTheme();
        }
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (toolbar != null && spTree != null) {
            setBackground(Theme.canvas());
            toolbar.setBackground(Theme.surface());
            spTree.getViewport().setBackground(Theme.surface());
        }
        super.paintComponent(g);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::applyTheme);
        }
    }

    @Override
    public void removeNotify() {
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
        super.removeNotify();
    }

    public void dispose() {
        disposed = true;
        closeSub(queueSub);
        closeSub(ledgerSub);
        closeSub(clusterSub);
        closeSub(batchSub);
        closeSub(themeSub);
        queueSub = null;
        ledgerSub = null;
        clusterSub = null;
        batchSub = null;
        themeSub = null;
    }

    private static void closeSub(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class DisplayRow {
        final String method;
        final String path;
        final int statusCode;
        final String branchPath;
        final String badgeText;
        final Theme.Token badgeToken;
        final ProxyInterceptQueue.PendingInterceptItem pendingItem;
        final ApiCall call;

        DisplayRow(
            String method,
            String path,
            int statusCode,
            String branchPath,
            String badgeText,
            Theme.Token badgeToken,
            ProxyInterceptQueue.PendingInterceptItem pendingItem,
            ApiCall call
        ) {
            this.method = method != null ? method : "";
            this.path = path != null ? path : "";
            this.statusCode = statusCode;
            this.branchPath = branchPath != null ? branchPath : "";
            this.badgeText = badgeText != null ? badgeText : "";
            this.badgeToken = badgeToken != null ? badgeToken : Theme.Token.NEUTRAL;
            this.pendingItem = pendingItem;
            this.call = call;
        }
    }

    private static final class ApiRowsTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Method", "Endpoint / Path", "Status", "Nhánh hành động", "Trạng thái", "Params"};
        private List<DisplayRow> rows = new ArrayList<>();

        void setRows(List<DisplayRow> rows) {
            this.rows = rows != null ? rows : new ArrayList<>();
            fireTableDataChanged();
        }

        DisplayRow getRowAt(int mr) {
            return mr >= 0 && mr < rows.size() ? rows.get(mr) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            DisplayRow r = rows.get(row);
            switch (col) {
                case 0: return r.method;
                case 1: return r.path;
                case 2: return r.statusCode > 0 ? Integer.valueOf(r.statusCode) : "-";
                case 3: return r.branchPath;
                case 4: return r.badgeText;
                case 5: return r.call != null && r.call.capture != null ? Integer.valueOf(r.call.capture.paramCount()) : Integer.valueOf(0);
                default: return "";
            }
        }
    }

    private final class ApiRowCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            c.setFont(column == 0 || column == 1 ? FontManager.monoRegular() : FontManager.uiRegular());
            if (!isSelected) {
                DisplayRow dr = tableModel.getRowAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                c.setForeground(Theme.text());
                if (dr != null) {
                    if (dr.pendingItem != null) {
                        c.setBackground(Theme.token(Theme.Token.PENDING).fill());
                        if (column == 4) {
                            c.setForeground(Theme.token(Theme.Token.PENDING).text());
                            c.setFont(FontManager.uiSemiBold());
                        }
                    } else if (column == 0) {
                        c.setForeground(Theme.forMethod(dr.method).text());
                        c.setFont(FontManager.monoBold());
                    } else if (column == 2 || column == 4) {
                        c.setForeground(Theme.token(dr.badgeToken).text());
                    }
                }
            } else {
                c.setBackground(Theme.selection());
                c.setForeground(Theme.selectionText());
            }
            return c;
        }
    }
}
