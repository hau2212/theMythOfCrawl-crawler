package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.ReviewStatus;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ultra-Minimalist Burp v2026.10.1 HTTP History / Site Map Workspace:
 * - ONE slim (~34px) top bar: [Trái–Phải | Sổ dọc 14" | Cây Path] [View v] [Actions v]   [All APIs / Nhóm: ... x]
 * - Directly followed by sticky BurpFilterBar.
 * - Centered EmptyStatePanels when tables are empty, clean Burp selection (#DCEBFC / #2F65CA), zero emojis.
 */
public final class GroupsWorkspacePanel extends JPanel {

    public enum WorkspaceLayout {
        SIDE_BY_SIDE,
        COMPACT_STACK,
        PATH_TREE
    }

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final ActionGroupStore store;
    private final Runnable onPersistConfig;
    private final Consumer<ApiCall> onInspectCall;
    private final Runnable onRevealInspector;
    private final Consumer<List<ApiCall>> onSendRepeaterCalls;
    private final Consumer<List<ApiCall>> onSendOrganizerCalls;
    private final Consumer<List<ApiCall>> onQueuePushCalls;
    private final Consumer<String> onShowSharedGroups;

    private WorkspaceLayout layoutMode = WorkspaceLayout.SIDE_BY_SIDE;
    private boolean treeViewMode = true;
    private boolean groupsPaneVisible = true;
    private boolean overlapVisible = false;
    private boolean treeTabByApiPath = false;
    private boolean showAllApisWhenUnselected = true;
    private String groupFilterMode = "WITH_APIS"; // WITH_APIS | ALL | WITH_NOTES
    private final boolean[] restoringGroupSel = {false};
    private final boolean[] restoringApiSel = {false};
    private long lastTreeVersion = -1L;
    private boolean lastBuiltTreeByPath = false;

    // Slim single top toolbar
    private final JPanel topToolbar = new JPanel(new BorderLayout(8, 0));
    private final JToggleButton btnLayoutSide = new JToggleButton("Trái–Phải", true);
    private final JToggleButton btnLayoutStack = new JToggleButton("Sổ dọc 14\"", false);
    private final JToggleButton btnLayoutTree = new JToggleButton("Cây Path", false);
    private final JButton btnViewMenu = new JButton("View");
    private final JButton btnActionsMenu = new JButton("Actions");
    private final JLabel lblScopeHeader = new JLabel("All APIs");
    private final JButton btnClearGroup = new JButton();

    private final BurpFilterBar filterBar;

    // Tables & Empty States
    private final GroupTableModel.ActionGroupsModel groupsModel;
    private final JTable tblGroups;
    private final TableSelectionPreserver.ColumnManager groupsColMgr;
    private final JPanel groupsCardHost = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel groupsEmptyState;

    private final GroupTableModel.GroupApisModel groupApisModel;
    private final JTable tblGroupApis;
    private final TableSelectionPreserver.ColumnManager apisColMgr;
    private final JPanel apisCardHost = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel apisEmptyState;

    private final OverlapTableModel overlapModel = new OverlapTableModel();
    private final JTable tblOverlap = new JTable(overlapModel);
    private final JLabel lblOverlapTitle = new JLabel("Chọn 1 API để xem nơi trùng lặp & khác biệt");

    // Path Tree view
    private final DefaultMutableTreeNode treeRoot;
    private final DefaultTreeModel treeModel;
    private final JTree actionTree;
    private final JPanel treeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
    private final JToggleButton btnTreeByAction = new JToggleButton("Theo Nhóm UI", true);
    private final JToggleButton btnTreeByPath = new JToggleButton("Theo URL Path (/...)", false);
    private final JButton btnTreeExp = new JButton("Mở hết");
    private final JButton btnTreeCol = new JButton("Thu gọn");
    private final JButton btnTreeRefresh = new JButton("Làm mới");

    // Split panes & card container
    private final JPanel cardHost = new JPanel(new CardLayout());
    private final JPanel leftGroupsPanel = new JPanel(new BorderLayout(0, 0));
    private final JPanel rightApisPanel = new JPanel(new BorderLayout(0, 0));
    private final JPanel overlapWrap = new JPanel(new BorderLayout(2, 2));
    private final JSplitPane groupsSplitPane;
    private final JSplitPane tablesOuterSplit;
    private final JPanel treePanel = new JPanel(new BorderLayout(0, 0));
    private JScrollPane spGroups;
    private JScrollPane spApis;
    private JScrollPane spOverlap;
    private JScrollPane spTree;

    public GroupsWorkspacePanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        Runnable onPersistConfig,
        Consumer<ApiCall> onInspectCall,
        Runnable onRevealInspector,
        Consumer<List<ApiCall>> onSendRepeaterCalls,
        Consumer<List<ApiCall>> onSendOrganizerCalls,
        Consumer<List<ApiCall>> onQueuePushCalls,
        Consumer<String> onShowSharedGroups
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.store = state.getActionGroups();
        this.onPersistConfig = onPersistConfig;
        this.onInspectCall = onInspectCall;
        this.onRevealInspector = onRevealInspector;
        this.onSendRepeaterCalls = onSendRepeaterCalls;
        this.onSendOrganizerCalls = onSendOrganizerCalls;
        this.onQueuePushCalls = onQueuePushCalls;
        this.onShowSharedGroups = onShowSharedGroups;

        this.filterBar = new BurpFilterBar(api);
        this.groupsModel = new GroupTableModel.ActionGroupsModel(store);
        this.tblGroups = new JTable(groupsModel);
        this.groupsEmptyState = new BurpComponents.EmptyStatePanel(
            api, "No groups to show", "Chưa có Action Group nào được ghi nhận.");

        this.groupApisModel = new GroupTableModel.GroupApisModel(store);
        this.tblGroupApis = new JTable(groupApisModel);
        this.apisEmptyState = new BurpComponents.EmptyStatePanel(
            api, "No items to show", "Chọn nhóm bên trái hoặc duyệt web qua Proxy để xem API.");

        this.treeRoot = new DefaultMutableTreeNode(config.getRouteRootLabel());
        this.treeModel = new DefaultTreeModel(treeRoot);
        this.actionTree = new JTree(treeModel);

        setLayout(new BorderLayout(0, 0));

        buildTopToolbar();
        buildGroupsPane();
        buildApisPane();
        buildOverlapPane();
        buildTreePane();

        groupsSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftGroupsPanel, rightApisPanel);
        groupsSplitPane.setResizeWeight(0.32);
        groupsSplitPane.setOneTouchExpandable(false);
        groupsSplitPane.setBorder(BorderFactory.createEmptyBorder());

        tablesOuterSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, groupsSplitPane, overlapWrap);
        tablesOuterSplit.setResizeWeight(0.72);
        tablesOuterSplit.setOneTouchExpandable(false);
        tablesOuterSplit.setBorder(BorderFactory.createEmptyBorder());

        cardHost.add(tablesOuterSplit, "TABLES");
        cardHost.add(treePanel, "TREE");

        JPanel northWrap = new JPanel(new BorderLayout(0, 0));
        northWrap.setOpaque(false);
        northWrap.add(topToolbar, BorderLayout.NORTH);
        northWrap.add(filterBar, BorderLayout.SOUTH);

        add(northWrap, BorderLayout.NORTH);
        add(cardHost, BorderLayout.CENTER);

        this.groupsColMgr = new TableSelectionPreserver.ColumnManager(
            tblGroups, "groups", config, onPersistConfig,
            new int[]{56, 260, 54, 52, 52, 46}, 4, 5);
        this.apisColMgr = new TableSelectionPreserver.ColumnManager(
            tblGroupApis, "groupApis", config, onPersistConfig,
            new int[]{42, 260, 62, 230, 52, 180, 44, 62, 190, 120}, 6, 7);

        wireEvents();
        applyGroupViewModeToTables();
        setOverlapVisible(false);
        applyTheme();
        refreshData();
    }

    private void buildTopToolbar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        left.setOpaque(false);
        left.add(btnLayoutSide);
        left.add(btnLayoutStack);
        left.add(btnLayoutTree);
        left.add(btnViewMenu);
        left.add(btnActionsMenu);

        btnViewMenu.setHorizontalTextPosition(SwingConstants.LEFT);
        btnViewMenu.setIconTextGap(4);
        btnViewMenu.addActionListener(e -> showViewMenu());

        btnActionsMenu.setHorizontalTextPosition(SwingConstants.LEFT);
        btnActionsMenu.setIconTextGap(4);
        btnActionsMenu.addActionListener(e -> showActionsMenu());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        right.setOpaque(false);
        btnClearGroup.setToolTipText("Bỏ chọn nhóm (hiện All APIs)");
        btnClearGroup.setVisible(false);
        right.add(lblScopeHeader);
        right.add(btnClearGroup);

        topToolbar.add(left, BorderLayout.WEST);
        topToolbar.add(right, BorderLayout.EAST);
    }

    private void showViewMenu() {
        JPopupMenu m = new JPopupMenu();

        JCheckBoxMenuItem miGroups = new JCheckBoxMenuItem("Hiện cột Nhóm bên trái", groupsPaneVisible);
        miGroups.addActionListener(e -> setGroupsPaneVisible(miGroups.isSelected()));

        JCheckBoxMenuItem miOverlap = new JCheckBoxMenuItem("So sánh Trùng lặp (Overlap)", overlapVisible);
        miOverlap.addActionListener(e -> setOverlapVisible(miOverlap.isSelected()));

        ButtonGroup bgView = new ButtonGroup();
        JRadioButtonMenuItem miTree = new JRadioButtonMenuItem("Gộp trùng theo Path", treeViewMode);
        JRadioButtonMenuItem miFlat = new JRadioButtonMenuItem("Bảng phẳng (Flat)", !treeViewMode);
        bgView.add(miTree);
        bgView.add(miFlat);
        miTree.addActionListener(e -> setGroupViewMode(true));
        miFlat.addActionListener(e -> setGroupViewMode(false));

        JMenuItem miExpAll = new JMenuItem("Mở hết nhánh");
        miExpAll.setEnabled(treeViewMode);
        miExpAll.addActionListener(e -> {
            groupsModel.expandAllFolders();
            groupApisModel.expandAllPathGroups();
        });

        JMenuItem miColAll = new JMenuItem("Thu gọn nhánh");
        miColAll.setEnabled(treeViewMode);
        miColAll.addActionListener(e -> {
            groupsModel.collapseAllFolders();
            groupApisModel.collapseAllPathGroups();
        });

        JMenu mGroupScope = new JMenu("Lọc danh sách Nhóm");
        ButtonGroup bgScope = new ButtonGroup();
        JRadioButtonMenuItem miWithApis = new JRadioButtonMenuItem("Nhóm có API (>0)", "WITH_APIS".equals(groupFilterMode));
        JRadioButtonMenuItem miAllGrp = new JRadioButtonMenuItem("Tất cả Nhóm", "ALL".equals(groupFilterMode));
        JRadioButtonMenuItem miWithNotes = new JRadioButtonMenuItem("Chỉ Nhóm có NOTE (!)", "WITH_NOTES".equals(groupFilterMode));
        bgScope.add(miWithApis);
        bgScope.add(miAllGrp);
        bgScope.add(miWithNotes);
        miWithApis.addActionListener(e -> { groupFilterMode = "WITH_APIS"; applyGroupFilter(); });
        miAllGrp.addActionListener(e -> { groupFilterMode = "ALL"; applyGroupFilter(); });
        miWithNotes.addActionListener(e -> { groupFilterMode = "WITH_NOTES"; applyGroupFilter(); });
        mGroupScope.add(miWithApis);
        mGroupScope.add(miAllGrp);
        mGroupScope.add(miWithNotes);

        JCheckBoxMenuItem miShowAllUnselected = new JCheckBoxMenuItem(
            "Hiện tất cả API khi chưa chọn nhóm", showAllApisWhenUnselected);
        miShowAllUnselected.addActionListener(e -> {
            showAllApisWhenUnselected = miShowAllUnselected.isSelected();
            applyGroupApisFilter();
        });

        m.add(miGroups);
        m.add(miOverlap);
        m.addSeparator();
        m.add(miTree);
        m.add(miFlat);
        m.add(miExpAll);
        m.add(miColAll);
        m.addSeparator();
        m.add(mGroupScope);
        m.add(miShowAllUnselected);
        m.show(btnViewMenu, 0, btnViewMenu.getHeight());
    }

    private void showActionsMenu() {
        JPopupMenu m = new JPopupMenu();
        List<ApiCall> selected = getSelectedCallsForAction();
        boolean hasSel = !selected.isEmpty();

        JMenu locateSub = WebLocatorHelper.createSubMenuForCall(this, state, () -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            return calls.isEmpty() ? null : calls.get(0);
        }, null);
        locateSub.setEnabled(hasSel);

        JMenuItem miRep = new JMenuItem("Send to Repeater (Ctrl+R)");
        miRep.setEnabled(hasSel);
        miRep.addActionListener(e -> {
            if (hasSel && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(selected);
            }
        });

        JMenuItem miOrg = new JMenuItem("Send to Organizer (Ctrl+O)");
        miOrg.setEnabled(hasSel);
        miOrg.addActionListener(e -> {
            if (hasSel && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(selected);
            }
        });

        JMenuItem miPush = new JMenuItem("+ Đưa vào hàng đẩy Logger++ (Extender)");
        miPush.setEnabled(hasSel);
        miPush.addActionListener(e -> {
            if (hasSel && onQueuePushCalls != null) {
                onQueuePushCalls.accept(selected);
            }
        });

        m.add(locateSub);
        m.addSeparator();
        m.add(miRep);
        m.add(miOrg);
        m.add(miPush);
        m.show(btnActionsMenu, 0, btnActionsMenu.getHeight());
    }

    private void buildGroupsPane() {
        AuditTheme.configureAuditTable(tblGroups, api);
        tblGroups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblGroups.setDefaultRenderer(Object.class, new GroupTableModel.GroupCellRenderer(api));
        tblGroups.setDefaultRenderer(Integer.class, new GroupTableModel.GroupCellRenderer(api));
        spGroups = new JScrollPane(tblGroups);
        groupsCardHost.add(groupsEmptyState, "EMPTY");
        groupsCardHost.add(spGroups, "TABLE");
        leftGroupsPanel.add(groupsCardHost, BorderLayout.CENTER);
    }

    private void buildApisPane() {
        AuditTheme.configureAuditTable(tblGroupApis, api);
        tblGroupApis.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblGroupApis.setDefaultRenderer(Object.class, new GroupTableModel.GroupApiCellRenderer(api));
        tblGroupApis.setDefaultRenderer(Integer.class, new GroupTableModel.GroupApiCellRenderer(api));
        spApis = new JScrollPane(tblGroupApis);
        apisCardHost.add(apisEmptyState, "EMPTY");
        apisCardHost.add(spApis, "TABLE");
        rightApisPanel.add(apisCardHost, BorderLayout.CENTER);
    }

    private void buildOverlapPane() {
        AuditTheme.configureAuditTable(tblOverlap, api);
        tblOverlap.setAutoCreateRowSorter(true);
        tblOverlap.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tblOverlap.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblOverlap.setDefaultRenderer(Object.class, new OverlapRenderer(api));
        tblOverlap.setDefaultRenderer(Integer.class, new OverlapRenderer(api));
        int[] widths = {38, 280, 58, 50, 140, 68, 240};
        for (int i = 0; i < widths.length && i < tblOverlap.getColumnCount(); i++) {
            tblOverlap.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        spOverlap = new JScrollPane(tblOverlap);
        lblOverlapTitle.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        overlapWrap.add(lblOverlapTitle, BorderLayout.NORTH);
        overlapWrap.add(spOverlap, BorderLayout.CENTER);
    }

    private void buildTreePane() {
        btnTreeExp.addActionListener(e -> expandAllTreeNodes(true));
        btnTreeCol.addActionListener(e -> expandAllTreeNodes(false));
        btnTreeRefresh.addActionListener(e -> {
            lastTreeVersion = -1L;
            rebuildHierarchyTreeIfChanged(store.version());
        });
        btnTreeByAction.addActionListener(e -> {
            treeTabByApiPath = false;
            updateModeButtonStyles();
            lastTreeVersion = -1L;
            rebuildHierarchyTreeIfChanged(store.version());
        });
        btnTreeByPath.addActionListener(e -> {
            treeTabByApiPath = true;
            updateModeButtonStyles();
            lastTreeVersion = -1L;
            rebuildHierarchyTreeIfChanged(store.version());
        });
        treeBar.add(btnTreeByAction);
        treeBar.add(btnTreeByPath);
        treeBar.add(btnTreeExp);
        treeBar.add(btnTreeCol);
        treeBar.add(btnTreeRefresh);

        actionTree.setCellRenderer(new AuditTreeRenderer());
        spTree = new JScrollPane(actionTree);
        treePanel.add(treeBar, BorderLayout.NORTH);
        treePanel.add(spTree, BorderLayout.CENTER);
    }

    private void wireEvents() {
        btnLayoutSide.addActionListener(e -> setWorkspaceLayout(WorkspaceLayout.SIDE_BY_SIDE));
        btnLayoutStack.addActionListener(e -> setWorkspaceLayout(WorkspaceLayout.COMPACT_STACK));
        btnLayoutTree.addActionListener(e -> setWorkspaceLayout(WorkspaceLayout.PATH_TREE));

        btnClearGroup.addActionListener(e -> {
            tblGroups.clearSelection();
            applyGroupApisFilter();
        });

        filterBar.addFilterChangeListener(criteria -> {
            applyGroupFilter();
            if (layoutMode == WorkspaceLayout.PATH_TREE) {
                lastTreeVersion = -1L;
                rebuildHierarchyTreeIfChanged(store.version());
            }
        });

        tblGroups.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringGroupSel[0]) {
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
                    String key = groupsModel.getKeyAt(mr);
                    TableSelectionPreserver.preserveAroundUpdate(
                        tblGroups, groupsModel::getKeyAt, restoringGroupSel, () -> groupsModel.toggleFolderExpanded(mr));
                    selectGroupByKey(key);
                    applyGroupApisFilter();
                }
            }
        });

        tblGroupApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringApiSel[0]) {
                GroupTableModel.GroupApisModel.ApiDisplayRow row = getSelectedApiRow();
                if (row == null) {
                    return;
                }
                ApiCall call = row.call != null ? row.call : pickRepresentativeCall(row.groupedCalls);
                if (call != null) {
                    updateOverlapInspector(call);
                    if (onInspectCall != null) {
                        onInspectCall.accept(call);
                    }
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
                if (row.isGroupHeader && ((vc == 0 && e.getClickCount() == 1) || e.getClickCount() == 2)) {
                    String key = groupApisModel.getKeyAt(mr);
                    TableSelectionPreserver.preserveAroundUpdate(
                        tblGroupApis, groupApisModel::getKeyAt, restoringApiSel, () -> groupApisModel.togglePathGroupExpanded(mr));
                    selectApiByKey(key);
                } else if (!row.isGroupHeader && e.getClickCount() == 2 && onRevealInspector != null) {
                    onRevealInspector.run();
                }
            }
        });

        tblGroupApis.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleExpand");
        tblGroupApis.getActionMap().put("toggleExpand", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int vr = tblGroupApis.getSelectedRow();
                if (vr >= 0) {
                    int mr = tblGroupApis.convertRowIndexToModel(vr);
                    if (groupApisModel.isGroupHeaderRow(mr)) {
                        String key = groupApisModel.getKeyAt(mr);
                        TableSelectionPreserver.preserveAroundUpdate(
                            tblGroupApis, groupApisModel::getKeyAt, restoringApiSel, () -> groupApisModel.togglePathGroupExpanded(mr));
                        selectApiByKey(key);
                    } else if (onRevealInspector != null) {
                        onRevealInspector.run();
                    }
                }
            }
        });

        tblOverlap.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblOverlap.getSelectedRow();
                if (vr >= 0) {
                    ApiCall c = overlapModel.getCallAt(tblOverlap.convertRowIndexToModel(vr));
                    if (c != null && onInspectCall != null) {
                        onInspectCall.accept(c);
                    }
                }
            }
        });
        tblOverlap.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblOverlap.getSelectedRow() >= 0 && onRevealInspector != null) {
                    onRevealInspector.run();
                }
            }
        });

        actionTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof TreeCallNode) {
                TreeCallNode tcn = (TreeCallNode) node.getUserObject();
                if (onInspectCall != null) {
                    onInspectCall.accept(tcn.call);
                }
            }
        });
        actionTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
                    if (node != null && node.getUserObject() instanceof TreeCallNode && onRevealInspector != null) {
                        onRevealInspector.run();
                    }
                }
            }
        });

        installGroupPopupMenu();
        installApiPopupMenu();
        installTreePopupMenu();
    }

    public void setWorkspaceLayout(WorkspaceLayout mode) {
        this.layoutMode = mode != null ? mode : WorkspaceLayout.SIDE_BY_SIDE;
        CardLayout cl = (CardLayout) cardHost.getLayout();
        if (this.layoutMode == WorkspaceLayout.PATH_TREE) {
            cl.show(cardHost, "TREE");
            lastTreeVersion = -1L;
            rebuildHierarchyTreeIfChanged(store.version());
        } else {
            cl.show(cardHost, "TABLES");
            if (this.layoutMode == WorkspaceLayout.COMPACT_STACK) {
                groupsSplitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
                groupsSplitPane.setResizeWeight(0.38);
                setOverlapVisible(true);
            } else {
                groupsSplitPane.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
                groupsSplitPane.setResizeWeight(0.32);
            }
            SwingUtilities.invokeLater(() -> {
                if (groupsPaneVisible && groupsSplitPane.getWidth() > 100 && groupsSplitPane.getHeight() > 100) {
                    groupsSplitPane.setDividerLocation(this.layoutMode == WorkspaceLayout.COMPACT_STACK ? 0.36 : 0.32);
                }
            });
        }
        updateModeButtonStyles();
        revalidate();
        repaint();
    }

    public void setGroupsPaneVisible(boolean visible) {
        this.groupsPaneVisible = visible;
        leftGroupsPanel.setVisible(visible);
        if (visible) {
            groupsSplitPane.setDividerSize(5);
            SwingUtilities.invokeLater(() -> {
                if (groupsSplitPane.getWidth() > 120) {
                    groupsSplitPane.setDividerLocation(layoutMode == WorkspaceLayout.COMPACT_STACK ? 0.36 : 0.32);
                }
            });
        } else {
            groupsSplitPane.setDividerSize(0);
            groupsSplitPane.setDividerLocation(0);
        }
        updateModeButtonStyles();
        groupsSplitPane.revalidate();
        groupsSplitPane.repaint();
    }

    public void setOverlapVisible(boolean visible) {
        this.overlapVisible = visible;
        overlapWrap.setVisible(visible);
        if (visible) {
            tablesOuterSplit.setDividerSize(5);
            SwingUtilities.invokeLater(() -> {
                if (tablesOuterSplit.getHeight() > 160) {
                    tablesOuterSplit.setDividerLocation(0.70);
                }
            });
        } else {
            tablesOuterSplit.setDividerSize(0);
            tablesOuterSplit.setDividerLocation(1.0);
        }
        updateModeButtonStyles();
        tablesOuterSplit.revalidate();
        tablesOuterSplit.repaint();
    }

    public boolean isGroupTreeViewMode() {
        return treeViewMode;
    }

    public void setGroupViewMode(boolean treeMode) {
        this.treeViewMode = treeMode;
        TableSelectionPreserver.preserveAroundUpdate(tblGroups, groupsModel::getKeyAt, restoringGroupSel, () -> {
            TableSelectionPreserver.preserveAroundUpdate(tblGroupApis, groupApisModel::getKeyAt, restoringApiSel, () -> {
                applyGroupViewModeToTables();
                applyGroupApisFilter();
            });
        });
    }

    private void applyGroupViewModeToTables() {
        groupsModel.setTreeMode(treeViewMode);
        groupApisModel.setTreeMode(treeViewMode);

        tblGroups.setAutoCreateRowSorter(!treeViewMode);
        tblGroupApis.setAutoCreateRowSorter(!treeViewMode);
        if (treeViewMode) {
            tblGroups.setRowSorter(null);
            tblGroupApis.setRowSorter(null);
        }
        groupsColMgr.refreshAfterStructureChange();
        apisColMgr.refreshAfterStructureChange();
        updateModeButtonStyles();
    }

    public void refreshData() {
        applyGroupFilter();
        if (layoutMode == WorkspaceLayout.PATH_TREE) {
            rebuildHierarchyTreeIfChanged(store.version());
        }
    }

    public void focusSearch() {
        filterBar.focusSearch();
    }

    public void clearActiveFilter() {
        filterBar.clearFilters();
    }

    public ActionGroup getSelectedDirectGroup() {
        int vr = tblGroups.getSelectedRow();
        if (vr < 0) {
            return null;
        }
        return groupsModel.getGroupAt(tblGroups.convertRowIndexToModel(vr));
    }

    public List<ApiCall> getSelectedCallsForAction() {
        if (layoutMode == WorkspaceLayout.PATH_TREE) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) actionTree.getLastSelectedPathComponent();
            if (node == null) {
                return Collections.emptyList();
            }
            if (node.getUserObject() instanceof TreeCallNode) {
                return Collections.singletonList(((TreeCallNode) node.getUserObject()).call);
            }
            return collectTreeCalls(node);
        }
        int[] vrs = tblGroupApis.getSelectedRows();
        if (vrs == null || vrs.length == 0) {
            return Collections.emptyList();
        }
        Map<Long, ApiCall> uniq = new LinkedHashMap<>();
        for (int vr : vrs) {
            if (vr >= 0 && vr < tblGroupApis.getRowCount()) {
                GroupTableModel.GroupApisModel.ApiDisplayRow row =
                    groupApisModel.getRowAt(tblGroupApis.convertRowIndexToModel(vr));
                if (row != null) {
                    for (ApiCall c : row.groupedCalls) {
                        uniq.put(c.callUid, c);
                    }
                }
            }
        }
        return new ArrayList<>(uniq.values());
    }

    public void jumpToGroupAndCall(int groupId, int callIndex) {
        if (layoutMode == WorkspaceLayout.PATH_TREE) {
            setWorkspaceLayout(WorkspaceLayout.SIDE_BY_SIDE);
        }
        groupFilterMode = "WITH_APIS";
        filterBar.clearFilters();
        ActionGroup targetGroup = store.getGroup(groupId);
        if (targetGroup != null) {
            groupsModel.ensureGroupVisible(targetGroup);
        }
        groupsModel.refresh();
        setGroupsPaneVisible(true);
        selectGroupByKey("G:" + groupId);
        applyGroupApisFilter();

        ApiCall target = findApiCall(groupId, callIndex);
        if (target != null) {
            groupApisModel.ensureCallVisible(target);
            selectApiByKey("C:" + target.callUid);
            if (onInspectCall != null) {
                onInspectCall.accept(target);
            }
        }
    }

    private ApiCall findApiCall(int groupId, int callIndex) {
        ActionGroup g = store.getGroup(groupId);
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

    private void applyGroupFilter() {
        final String resolvedMode = groupFilterMode;
        String rawQuery = filterBar.getCriteria().rawQuery;
        TableSelectionPreserver.preserveAroundUpdate(tblGroups, groupsModel::getKeyAt, restoringGroupSel, () -> {
            groupsModel.setFilter(resolvedMode, rawQuery);
        });
        ((CardLayout) groupsCardHost.getLayout()).show(
            groupsCardHost, groupsModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
        applyGroupApisFilter();
    }

    private void applyGroupApisFilter() {
        GroupTableModel.ActionGroupsModel.GroupRow rowMeta = getSelectedGroupRow();
        boolean showAllWhenNull = showAllApisWhenUnselected;
        List<ActionGroup> selectedGroups = Collections.emptyList();
        boolean filteredBySelection = rowMeta != null;

        if (rowMeta != null) {
            selectedGroups = rowMeta.subtreeGroups;
            if (rowMeta.isFolder) {
                String shortKey = shorten(rowMeta.folderKey, 34);
                lblScopeHeader.setText("Nhánh: " + shortKey + " (" + rowMeta.totalApis + ")");
                lblScopeHeader.setToolTipText("Nhánh: " + rowMeta.folderKey
                    + " (" + rowMeta.subtreeGroups.size() + " nhóm, "
                    + rowMeta.totalApis + " API, " + rowMeta.totalNotes + " NOTE)");
            } else if (rowMeta.directGroup != null) {
                ActionGroup g = rowMeta.directGroup;
                lblScopeHeader.setText("Nhóm: " + shorten(g.actionPath, 34) + " (" + g.callCount() + ")");
                lblScopeHeader.setToolTipText("Nhóm: " + g.actionPath
                    + " (" + g.callCount() + " API, " + g.noteCount() + " NOTE, " + g.getTriggerCount() + " lượt)");
            }
        } else if (showAllWhenNull) {
            lblScopeHeader.setText("All APIs (" + store.totalCalls() + ")");
            lblScopeHeader.setToolTipText("Toàn bộ API đã nhóm (" + store.totalCalls() + " API)");
        } else {
            lblScopeHeader.setText("Chưa chọn nhóm");
            lblScopeHeader.setToolTipText("Chọn một nhóm bên trái để xem các API bên trong");
        }
        btnClearGroup.setVisible(filteredBySelection);

        final List<ActionGroup> finalGroups = selectedGroups;
        BurpFilterBar.FilterCriteria criteria = filterBar.getCriteria();
        TableSelectionPreserver.preserveAroundUpdate(tblGroupApis, groupApisModel::getKeyAt, restoringApiSel, () -> {
            groupApisModel.setGroupsAndFilter(finalGroups, showAllWhenNull, false, "", criteria);
        });
        filterBar.setCounts(groupApisModel.getFilteredCallCount(), groupApisModel.getTotalSourceCount());
        ((CardLayout) apisCardHost.getLayout()).show(
            apisCardHost, groupApisModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s != null ? s : "";
        }
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private void selectGroupByKey(String key) {
        if (key == null) {
            return;
        }
        restoringGroupSel[0] = true;
        try {
            for (int r = 0; r < groupsModel.getRowCount(); r++) {
                if (key.equals(groupsModel.getKeyAt(r))) {
                    int vr = tblGroups.convertRowIndexToView(r);
                    if (vr >= 0) {
                        tblGroups.setRowSelectionInterval(vr, vr);
                        TableSelectionPreserver.scrollRowToVisible(tblGroups, vr);
                    }
                    break;
                }
            }
        } finally {
            restoringGroupSel[0] = false;
        }
    }

    private void selectApiByKey(String key) {
        if (key == null) {
            return;
        }
        restoringApiSel[0] = true;
        try {
            for (int r = 0; r < groupApisModel.getRowCount(); r++) {
                if (key.equals(groupApisModel.getKeyAt(r))) {
                    int vr = tblGroupApis.convertRowIndexToView(r);
                    if (vr >= 0) {
                        tblGroupApis.setRowSelectionInterval(vr, vr);
                        TableSelectionPreserver.scrollRowToVisible(tblGroupApis, vr);
                    }
                    break;
                }
            }
        } finally {
            restoringApiSel[0] = false;
        }
    }

    private GroupTableModel.ActionGroupsModel.GroupRow getSelectedGroupRow() {
        int vr = tblGroups.getSelectedRow();
        if (vr < 0 || vr >= tblGroups.getRowCount()) {
            return null;
        }
        return groupsModel.getRowAt(tblGroups.convertRowIndexToModel(vr));
    }

    private GroupTableModel.GroupApisModel.ApiDisplayRow getSelectedApiRow() {
        int vr = tblGroupApis.getSelectedRow();
        if (vr < 0 || vr >= tblGroupApis.getRowCount()) {
            return null;
        }
        return groupApisModel.getRowAt(tblGroupApis.convertRowIndexToModel(vr));
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

    private void updateOverlapInspector(ApiCall call) {
        if (call == null) {
            lblOverlapTitle.setText("Chọn 1 API để xem nơi trùng lặp & khác biệt");
            overlapModel.setData(null, Collections.emptyList());
            return;
        }
        GroupTableModel.PathOverlapSummary summary = GroupTableModel.summarizePathOverlap(store, call);
        lblOverlapTitle.setText(summary.pathKey
            + " — Trùng " + summary.totalCalls + " lần (" + summary.distinctLocations + " vị trí) | "
            + summary.diffSummary);
        overlapModel.setData(call, summary.calls);
    }

    private void installGroupPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miPush = new JMenuItem("Đưa nhóm/nhánh vào hàng đẩy Logger++ (Extender)");
        JMenuItem miRep = new JMenuItem("Send tất cả API trong nhóm/nhánh sang Repeater");
        JMenuItem miOrg = new JMenuItem("Send tất cả API trong nhóm/nhánh sang Organizer");
        JMenuItem miCopyPath = new JMenuItem("Copy Tên Nhóm (Action Path)");
        JMenuItem miCopyUrl = new JMenuItem("Copy Page URL");

        miPush.addActionListener(e -> {
            List<ApiCall> calls = selectedGroupCalls();
            if (!calls.isEmpty() && onQueuePushCalls != null) {
                onQueuePushCalls.accept(calls);
            }
        });
        miRep.addActionListener(e -> {
            List<ApiCall> calls = selectedGroupCalls();
            if (!calls.isEmpty() && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(calls);
            }
        });
        miOrg.addActionListener(e -> {
            List<ApiCall> calls = selectedGroupCalls();
            if (!calls.isEmpty() && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(calls);
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

        popup.add(miPush);
        popup.add(miRep);
        popup.add(miOrg);
        popup.addSeparator();
        popup.add(miCopyPath);
        popup.add(miCopyUrl);
        attachPopup(tblGroups, popup);
    }

    private List<ApiCall> selectedGroupCalls() {
        GroupTableModel.ActionGroupsModel.GroupRow row = getSelectedGroupRow();
        if (row == null) {
            return Collections.emptyList();
        }
        List<ApiCall> out = new ArrayList<>();
        for (ActionGroup g : row.subtreeGroups) {
            if (g != null) {
                out.addAll(g.getCalls());
            }
        }
        return out;
    }

    private void installApiPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem miRep = new JMenuItem("Send to Repeater (Ctrl+R)");
        JMenuItem miOrg = new JMenuItem("Send to Organizer (Ctrl+O)");
        JMenuItem miPush = new JMenuItem("+ Đưa vào hàng đẩy Logger++ (Extender)");
        JMenuItem miPushGroup = new JMenuItem("+ Đưa cả nhóm chứa API này vào hàng đẩy Logger++");
        JMenuItem miShared = new JMenuItem("Xem các nhóm cùng gọi API này");

        miRep.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(calls);
            }
        });
        miOrg.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(calls);
            }
        });
        miPush.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onQueuePushCalls != null) {
                onQueuePushCalls.accept(calls);
            }
        });
        miPushGroup.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onQueuePushCalls != null) {
                ActionGroup g = store.getGroup(calls.get(0).groupId);
                if (g != null) {
                    onQueuePushCalls.accept(g.getCalls());
                }
            }
        });
        miShared.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onShowSharedGroups != null) {
                onShowSharedGroups.accept(calls.get(0).endpointKey);
            }
        });

        boolean dark = AuditTheme.isDark(api, this);
        JMenu mHighlight = new JMenu("Highlight màu");
        for (HighlightColor hc : HighlightColor.values()) {
            JMenuItem item = new JMenuItem(hc == HighlightColor.NONE ? "Bỏ màu (None)" : hc.displayName());
            Color swatch = AuditTheme.highlightBadgeColor(api, hc, dark);
            if (swatch != null) {
                item.setIcon(BurpIcons.dot(swatch));
            }
            item.addActionListener(e -> {
                for (ApiCall c : getSelectedCallsForAction()) {
                    c.setHighlightColor(hc);
                }
                tblGroupApis.repaint();
            });
            mHighlight.add(item);
        }

        JMenu mReview = new JMenu("Trạng thái Triage");
        for (ReviewStatus rs : ReviewStatus.values()) {
            JMenuItem item = new JMenuItem(rs.label);
            item.addActionListener(e -> {
                for (ApiCall c : getSelectedCallsForAction()) {
                    c.setReviewStatus(rs);
                }
                tblGroupApis.repaint();
            });
            mReview.add(item);
        }

        JMenuItem miComment = new JMenuItem("Ghi chú (Comment)...");
        miComment.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (calls.isEmpty()) {
                return;
            }
            String cur = calls.get(0).getUserComment();
            String input = JOptionPane.showInputDialog(this, "Nhập ghi chú cho " + calls.size() + " API đã chọn:", cur);
            if (input != null) {
                for (ApiCall c : calls) {
                    c.setUserComment(input);
                }
                tblGroupApis.repaint();
            }
        });

        JMenuItem miCopyName = new JMenuItem("Copy Action Path");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");
        JMenuItem miCopyCurl = new JMenuItem("Copy as cURL");
        miCopyName.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty()) {
                copyToClipboard(calls.get(0).displayName());
            }
        });
        miCopyUrl.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty()) {
                copyToClipboard(calls.get(0).capture.fullUrl);
            }
        });
        miCopyCurl.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty()) {
                copyToClipboard(buildSimpleCurl(calls.get(0).capture));
            }
        });

        popup.add(miRep);
        popup.add(miOrg);
        popup.add(WebLocatorHelper.createSubMenuForCall(this, state, () -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            return calls.isEmpty() ? null : calls.get(0);
        }, null));
        popup.add(miPush);
        popup.add(miPushGroup);
        popup.addSeparator();
        popup.add(mHighlight);
        popup.add(mReview);
        popup.add(miComment);
        popup.addSeparator();
        popup.add(miShared);
        popup.add(miCopyName);
        popup.add(miCopyUrl);
        popup.add(miCopyCurl);
        attachPopup(tblGroupApis, popup);
    }

    private void installTreePopupMenu() {
        JPopupMenu treePopup = new JPopupMenu();
        JMenuItem miPush = new JMenuItem("+ Đưa API (hoặc nhánh) vào hàng đẩy Logger++");
        JMenuItem miRep = new JMenuItem("Send to Repeater");
        JMenuItem miOrg = new JMenuItem("Send to Organizer");
        miPush.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onQueuePushCalls != null) {
                onQueuePushCalls.accept(calls);
            }
        });
        miRep.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(calls);
            }
        });
        miOrg.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(calls);
            }
        });
        treePopup.add(miPush);
        treePopup.add(miRep);
        treePopup.add(miOrg);
        treePopup.add(WebLocatorHelper.createSubMenuForCall(this, state, () -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            return calls.isEmpty() ? null : calls.get(0);
        }, null));
        actionTree.setComponentPopupMenu(treePopup);
    }

    private static void attachPopup(JTable table, JPopupMenu popup) {
        table.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && !table.isRowSelected(row)) {
                        table.setRowSelectionInterval(row, row);
                    }
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
        });
    }

    private void rebuildHierarchyTreeIfChanged(long curVer) {
        String rootLabel = config.getRouteRootLabel();
        if (curVer == lastTreeVersion
            && treeTabByApiPath == lastBuiltTreeByPath
            && (treeTabByApiPath || rootLabel.equals(treeRoot.getUserObject()))) {
            return;
        }
        lastTreeVersion = curVer;
        lastBuiltTreeByPath = treeTabByApiPath;

        Set<String> expandedKeys = getExpandedDescendantKeys();
        treeRoot.removeAllChildren();

        BurpFilterBar.FilterCriteria criteria = filterBar.getCriteria();
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();
        int shownCalls = 0;
        int totalCalls = 0;

        if (treeTabByApiPath) {
            for (ActionGroup g : store.snapshotGroups()) {
                for (ApiCall call : g.getCalls()) {
                    totalCalls++;
                    if (!matchesCriteria(call, criteria)) {
                        continue;
                    }
                    shownCalls++;
                    String normPath = GroupTableModel.normalizeApiGroupPath(call);
                    DefaultMutableTreeNode endpointFolder = resolveApiPathTreeFolder(folderNodes, normPath);
                    endpointFolder.add(new DefaultMutableTreeNode(new TreeCallNode(call, true)));
                }
            }
            treeRoot.setUserObject("API Path Tree (" + shownCalls + " API)");
        } else {
            treeRoot.setUserObject(rootLabel);
            Map<DefaultMutableTreeNode, List<ApiCall>> nodeCalls = new IdentityHashMap<>();
            List<DefaultMutableTreeNode> orderedFolders = new ArrayList<>();
            for (ActionGroup g : store.snapshotGroups()) {
                if (g.callCount() == 0) {
                    continue;
                }
                List<ApiCall> matchedCalls = new ArrayList<>();
                for (ApiCall c : g.getCalls()) {
                    totalCalls++;
                    if (matchesCriteria(c, criteria)) {
                        matchedCalls.add(c);
                        shownCalls++;
                    }
                }
                if (matchedCalls.isEmpty()) {
                    continue;
                }
                DefaultMutableTreeNode groupParent = resolveTreeFolder(folderNodes, g.actionPath, rootLabel);
                List<ApiCall> list = nodeCalls.computeIfAbsent(groupParent, k -> {
                    orderedFolders.add(k);
                    return new ArrayList<>();
                });
                list.addAll(matchedCalls);
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
                            entry.getKey() + " (" + bucket.size() + " API)");
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
        filterBar.setCounts(shownCalls, totalCalls);
    }

    private static boolean matchesCriteria(ApiCall c, BurpFilterBar.FilterCriteria criteria) {
        if (criteria == null || !criteria.isAnyFilterActive()) {
            return true;
        }
        boolean interesting = c.getHighlightColor() != HighlightColor.NONE
            || c.getReviewStatus() == ReviewStatus.INTERESTING;
        return criteria.matches(
            c.capture.method, c.capture.statusCode, c.mimeType,
            c.capture.paramCount(), c.hasNotes(), c.inExcel, interesting,
            c.displayName(), c.displayPath(), GroupTableModel.normalizeApiGroupPath(c),
            c.capture.paramSignature(), c.notesSummary(), c.excelStt, c.getUserComment());
    }

    private DefaultMutableTreeNode resolveApiPathTreeFolder(
        Map<String, DefaultMutableTreeNode> folderNodes, String normPath
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

    private DefaultMutableTreeNode resolveTreeFolder(
        Map<String, DefaultMutableTreeNode> folderNodes, String actionPath, String rootLabel
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

    private Set<String> getExpandedDescendantKeys() {
        Set<String> keys = new HashSet<>();
        Enumeration<TreePath> en = actionTree.getExpandedDescendants(new TreePath(treeRoot));
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

    private static String buildSimpleCurl(CapturedApiEntry cap) {
        if (cap == null) {
            return "";
        }
        return "curl -i -s -k -X " + cap.method + " \"" + cap.fullUrl.replace("\"", "\\\"") + "\"";
    }

    private static void copyToClipboard(String text) {
        if (text != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        Color panel = AuditTheme.panelSurface(dark);
        Color muted = AuditTheme.mutedForeground(dark);
        Font base = AuditTheme.displayFont(api, this);

        setBackground(AuditTheme.canvasSurface(dark));
        AuditTheme.styleSlimToolbar(topToolbar, dark);
        AuditTheme.styleSlimToolbar(treeBar, dark);
        leftGroupsPanel.setBackground(panel);
        rightApisPanel.setBackground(panel);
        overlapWrap.setBackground(panel);
        treePanel.setBackground(panel);

        lblScopeHeader.setFont(base.deriveFont(Font.BOLD));
        lblScopeHeader.setForeground(AuditTheme.textPrimary(dark));
        lblOverlapTitle.setForeground(AuditTheme.accentForeground(dark));
        lblOverlapTitle.setFont(base.deriveFont(Font.BOLD));

        AuditTheme.configureAuditTable(tblGroups, api);
        AuditTheme.configureAuditTable(tblGroupApis, api);
        AuditTheme.configureAuditTable(tblOverlap, api);
        AuditTheme.styleScrollPane(spGroups, dark);
        AuditTheme.styleScrollPane(spApis, dark);
        AuditTheme.styleScrollPane(spOverlap, dark);
        AuditTheme.styleScrollPane(spTree, dark);
        spGroups.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, AuditTheme.borderColor(dark)));
        spApis.setBorder(BorderFactory.createEmptyBorder());
        groupsEmptyState.applyTheme();
        apisEmptyState.applyTheme();
        actionTree.setBackground(AuditTheme.tableSurface(dark));

        btnViewMenu.setIcon(BurpIcons.chevronDown(muted));
        btnActionsMenu.setIcon(BurpIcons.chevronDown(muted));
        btnClearGroup.setIcon(BurpIcons.clear(muted));
        AuditTheme.stylePlainButton(btnViewMenu, dark);
        AuditTheme.stylePlainButton(btnActionsMenu, dark);
        AuditTheme.stylePlainButton(btnClearGroup, dark);

        updateModeButtonStyles();
        filterBar.applyTheme(api);
    }

    private void updateModeButtonStyles() {
        boolean dark = AuditTheme.isDark(api, this);
        Color activeFg = AuditTheme.pastelActiveFg(dark);
        Color muted = AuditTheme.mutedForeground(dark);

        btnLayoutSide.setIcon(BurpIcons.sidebar(layoutMode == WorkspaceLayout.SIDE_BY_SIDE ? activeFg : muted));
        btnLayoutStack.setIcon(BurpIcons.inspector(layoutMode == WorkspaceLayout.COMPACT_STACK ? activeFg : muted));
        btnLayoutTree.setIcon(BurpIcons.tree(layoutMode == WorkspaceLayout.PATH_TREE ? activeFg : muted));
        AuditTheme.styleTogglePill(btnLayoutSide, layoutMode == WorkspaceLayout.SIDE_BY_SIDE, null, dark);
        AuditTheme.styleTogglePill(btnLayoutStack, layoutMode == WorkspaceLayout.COMPACT_STACK, null, dark);
        AuditTheme.styleTogglePill(btnLayoutTree, layoutMode == WorkspaceLayout.PATH_TREE, null, dark);

        btnTreeByAction.setSelected(!treeTabByApiPath);
        btnTreeByPath.setSelected(treeTabByApiPath);
        AuditTheme.styleTogglePill(btnTreeByAction, !treeTabByApiPath, null, dark);
        AuditTheme.styleTogglePill(btnTreeByPath, treeTabByApiPath, null, dark);
        btnTreeExp.setIcon(BurpIcons.expandAll(AuditTheme.textPrimary(dark)));
        btnTreeCol.setIcon(BurpIcons.collapseAll(AuditTheme.textPrimary(dark)));
        AuditTheme.stylePlainButton(btnTreeExp, dark);
        AuditTheme.stylePlainButton(btnTreeCol, dark);
        AuditTheme.stylePlainButton(btnTreeRefresh, dark);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (topToolbar != null && filterBar != null) {
            applyTheme();
        }
    }

    private static final class OverlapTableModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Vị trí tìm thấy (Action Path)", "Method", "HTTP", "Params", "Size", "Khác biệt so với bản đang xem"
        };
        private ApiCall baseline;
        private List<ApiCall> calls = Collections.emptyList();

        void setData(ApiCall baseline, List<ApiCall> calls) {
            this.baseline = baseline;
            this.calls = calls != null ? calls : Collections.emptyList();
            fireTableDataChanged();
        }

        ApiCall getCallAt(int r) {
            return r >= 0 && r < calls.size() ? calls.get(r) : null;
        }

        @Override public int getRowCount() { return calls.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return (col == 0 || col == 3) ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            ApiCall c = calls.get(row);
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return c.displayName();
                case 2: return c.capture.method;
                case 3: return Integer.valueOf(c.capture.statusCode);
                case 4: return c.capture.paramCount() > 0 ? (c.capture.paramCount() + " (" + c.capture.paramSignature() + ")") : "0";
                case 5: return c.capture.responseLength + " B";
                case 6: return GroupTableModel.describeCallDiffAgainst(c, baseline);
                default: return "";
            }
        }
    }

    private static final class OverlapRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        OverlapRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 2 || column == 3 || column == 5) ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());
            if (!isSelected) {
                String val = value != null ? value.toString() : "";
                c.setBackground(AuditTheme.zebraRowBackground(table.getBackground(), row, dark));
                if (column == 6) {
                    if (val.startsWith("[Đang xem]")) {
                        c.setForeground(AuditTheme.accentForeground(dark));
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (val.startsWith("Giống")) {
                        c.setForeground(AuditTheme.okForeground(dark));
                    } else {
                        c.setForeground(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                } else {
                    c.setForeground(table.getForeground());
                }
            }
            return c;
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
            setFont(tree.getFont());
            if (!sel && value instanceof DefaultMutableTreeNode) {
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                if (uo instanceof TreeCallNode) {
                    TreeCallNode tcn = (TreeCallNode) uo;
                    AuditNote.Severity sev = tcn.call.worstSeverity();
                    if (sev != null) {
                        setForeground(AuditTheme.severityForeground(sev, dark));
                    } else if (tcn.call.inExcel) {
                        setForeground(AuditTheme.okForeground(dark));
                    } else {
                        setForeground(AuditTheme.textPrimary(dark));
                    }
                } else {
                    setForeground(AuditTheme.textPrimary(dark));
                    setFont(tree.getFont().deriveFont(Font.BOLD));
                    setIcon(BurpIcons.folder(expanded, AuditTheme.accentForeground(dark)));
                }
            }
            return c;
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
            String groupTag = includeGroupName ? ("  —  " + call.displayName()) : "";
            return tag + " " + call.capture.method + " " + call.displayPath()
                + "  (" + call.capture.paramCount() + " params, HTTP " + call.capture.statusCode
                + ", x" + call.getHits() + ")" + groupTag + noteTag;
        }
    }
}
