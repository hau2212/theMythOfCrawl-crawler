package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 14-inch Split-Screen Optimized Vertical View ("Gọn 14\" (Sổ dọc)").
 * Reuses the shared {@link ActionGroupStore} without altering the classic "Nhóm & API" architecture:
 * - Vertical Master-Detail / Accordion layout tailored for half-screen Burp + Firefox workflows
 * - 4 selectable view modes: Hierarchical Tree (1, 1.1, 1.1.1), Path-Overlap Grouping, Diff/NOTE Only, Flat + Filters
 * - Built-in "Trùng ở đâu & Khác gì?" live comparison table for any selected API
 */
public final class Compact14ViewPanel extends JPanel {

    public enum ViewMode {
        TREE_STACK,
        DUP_PATH,
        DIFF_NOTE_ONLY,
        FLAT_FILTER
    }

    private final MontoyaApi api;
    private final ActionGroupStore store;
    private final Consumer<ApiCall> onInspectCall;
    private final Consumer<List<ApiCall>> onQueuePush;
    private final Consumer<CapturedApiEntry> onSendRepeater;

    private ViewMode viewMode = ViewMode.TREE_STACK;
    private boolean overlapPanelVisible = true;
    private ApiCall selectedCall;

    private final JButton btnModeTree = new JButton("🌳 Cây Sổ dọc");
    private final JButton btnModeDup = new JButton("🔗 Trùng Path");
    private final JButton btnModeDiff = new JButton("⚡ Khác biệt & NOTE");
    private final JButton btnModeFlat = new JButton("📋 Bảng phẳng");

    private final JComboBox<String> cbMethod = new JComboBox<>(new String[]{"Method: Tất cả", "GET", "POST", "PUT", "PATCH", "DELETE"});
    private final JComboBox<String> cbHttp = new JComboBox<>(new String[]{"HTTP: Tất cả", "2xx", "3xx", "4xx", "5xx"});
    private final JTextField txtSearch = new JTextField(14);
    private final JButton btnExpandAll = new JButton("⊞ Sổ hết");
    private final JButton btnCollapseAll = new JButton("⊟ Thu gọn");
    private final JButton btnToggleOverlap = new JButton("🔍 Ẩn So sánh Trùng");
    private final JLabel lblSummaryChip = new JLabel("0 API");

    // Master Tree (used in TREE_STACK mode above the API table)
    private final GroupTableModel.ActionGroupsModel treeGroupsModel;
    private final JTable tblTreeGroups;

    // Unified API display table (used in all 4 modes)
    private final CompactApiTableModel apiModel;
    private final JTable tblApis;

    // Bottom Overlap Comparison panel ("Trùng với cái nào & Khác gì?")
    private final JLabel lblOverlapTitle = new JLabel("Chọn 1 API bất kỳ để xem nó trùng ở những đường dẫn nào & khác gì nhau");
    private final OverlapTableModel overlapModel = new OverlapTableModel();
    private final JTable tblOverlap = new JTable(overlapModel);

    private final JPanel treeWrap = new JPanel(new BorderLayout());
    private final JPanel overlapWrap = new JPanel(new BorderLayout(2, 2));
    private final JSplitPane verticalTreeApiSplit;
    private final JSplitPane mainVerticalSplit;

    public Compact14ViewPanel(
        MontoyaApi api,
        ActionGroupStore store,
        Consumer<ApiCall> onInspectCall,
        Consumer<List<ApiCall>> onQueuePush,
        Consumer<CapturedApiEntry> onSendRepeater
    ) {
        super(new BorderLayout(4, 4));
        this.api = api;
        this.store = store;
        this.onInspectCall = onInspectCall;
        this.onQueuePush = onQueuePush;
        this.onSendRepeater = onSendRepeater;

        boolean dark = AuditTheme.isDark(api, this);
        setOpaque(true);
        setBackground(AuditTheme.canvasSurface(dark));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        this.treeGroupsModel = new GroupTableModel.ActionGroupsModel(store);
        this.treeGroupsModel.setTreeMode(true);
        this.treeGroupsModel.setFilter("WITH_APIS", "");
        this.tblTreeGroups = new JTable(treeGroupsModel);
        AuditTheme.configureAuditTable(tblTreeGroups, api);
        tblTreeGroups.setDefaultRenderer(Object.class, new GroupTableModel.GroupCellRenderer(api));
        tblTreeGroups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setColumnWidths(tblTreeGroups.getColumnModel(), new int[]{64, 340, 56, 56, 56, 48});

        this.apiModel = new CompactApiTableModel(store);
        this.tblApis = new JTable(apiModel);
        AuditTheme.configureAuditTable(tblApis, api);
        this.tblApis.setDefaultRenderer(Object.class, new CompactApiRenderer(api));
        this.tblApis.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setColumnWidths(tblApis.getColumnModel(), new int[]{48, 250, 60, 200, 50, 140, 170, 160});

        AuditTheme.configureAuditTable(tblOverlap, api);
        tblOverlap.setDefaultRenderer(Object.class, new OverlapRenderer(api));
        tblOverlap.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setColumnWidths(tblOverlap.getColumnModel(), new int[]{36, 300, 58, 48, 130, 68, 220});

        add(buildToolbar(dark), BorderLayout.NORTH);

        JScrollPane spTree = new JScrollPane(tblTreeGroups);
        spTree.getViewport().setBackground(AuditTheme.tableSurface(dark));
        treeWrap.add(spTree, BorderLayout.CENTER);

        JScrollPane spApis = new JScrollPane(tblApis);
        spApis.getViewport().setBackground(AuditTheme.tableSurface(dark));

        verticalTreeApiSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeWrap, spApis);
        verticalTreeApiSplit.setResizeWeight(0.42);
        verticalTreeApiSplit.setDividerLocation(180);
        verticalTreeApiSplit.setOneTouchExpandable(true);

        overlapWrap.setBackground(AuditTheme.panelSurface(dark));
        overlapWrap.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AuditTheme.borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        lblOverlapTitle.setFont(lblOverlapTitle.getFont().deriveFont(Font.BOLD));
        lblOverlapTitle.setForeground(AuditTheme.accentForeground(dark));
        overlapWrap.add(lblOverlapTitle, BorderLayout.NORTH);
        JScrollPane spOverlap = new JScrollPane(tblOverlap);
        spOverlap.getViewport().setBackground(AuditTheme.tableSurface(dark));
        overlapWrap.add(spOverlap, BorderLayout.CENTER);

        mainVerticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, verticalTreeApiSplit, overlapWrap);
        mainVerticalSplit.setResizeWeight(0.68);
        mainVerticalSplit.setOneTouchExpandable(true);
        add(mainVerticalSplit, BorderLayout.CENTER);

        wireEvents();
        applyViewMode(ViewMode.TREE_STACK);
    }

    private JPanel buildToolbar(boolean dark) {
        JPanel wrap = new JPanel(new BorderLayout(2, 3));
        AuditTheme.styleActionCard(wrap, dark);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row1.setOpaque(false);
        row1.add(btnModeTree);
        row1.add(btnModeDup);
        row1.add(btnModeDiff);
        row1.add(btnModeFlat);
        row1.add(btnToggleOverlap);
        AuditTheme.styleTogglePill(btnToggleOverlap, overlapPanelVisible, AuditTheme.accentForeground(dark), dark);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 1));
        row2.setOpaque(false);
        row2.add(new JLabel("Tìm:"));
        row2.add(txtSearch);
        row2.add(cbMethod);
        row2.add(cbHttp);
        AuditTheme.styleNeutralActionButton(btnExpandAll, dark);
        AuditTheme.styleNeutralActionButton(btnCollapseAll, dark);
        row2.add(btnExpandAll);
        row2.add(btnCollapseAll);
        AuditTheme.styleStatChip(lblSummaryChip, AuditTheme.accentForeground(dark), AuditTheme.cardSurface(dark));
        row2.add(lblSummaryChip);

        wrap.add(row1, BorderLayout.NORTH);
        wrap.add(row2, BorderLayout.SOUTH);
        return wrap;
    }

    private void wireEvents() {
        btnModeTree.addActionListener(e -> applyViewMode(ViewMode.TREE_STACK));
        btnModeDup.addActionListener(e -> applyViewMode(ViewMode.DUP_PATH));
        btnModeDiff.addActionListener(e -> applyViewMode(ViewMode.DIFF_NOTE_ONLY));
        btnModeFlat.addActionListener(e -> applyViewMode(ViewMode.FLAT_FILTER));

        btnToggleOverlap.addActionListener(e -> {
            overlapPanelVisible = !overlapPanelVisible;
            boolean dark = AuditTheme.isDark(api, this);
            btnToggleOverlap.setText(overlapPanelVisible ? "🔍 Ẩn So sánh Trùng" : "🔍 Hiện So sánh Trùng");
            AuditTheme.styleTogglePill(btnToggleOverlap, overlapPanelVisible, AuditTheme.accentForeground(dark), dark);
            overlapWrap.setVisible(overlapPanelVisible);
            if (overlapPanelVisible) {
                mainVerticalSplit.setDividerLocation(0.68);
            }
            revalidate();
        });

        cbMethod.addActionListener(e -> refreshData());
        cbHttp.addActionListener(e -> refreshData());
        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshData(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshData(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshData(); }
        });

        btnExpandAll.addActionListener(e -> {
            if (viewMode == ViewMode.TREE_STACK) {
                treeGroupsModel.expandAllFolders();
            } else {
                apiModel.expandAll();
            }
        });
        btnCollapseAll.addActionListener(e -> {
            if (viewMode == ViewMode.TREE_STACK) {
                treeGroupsModel.collapseAllFolders();
            } else {
                apiModel.collapseAll();
            }
        });

        tblTreeGroups.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && viewMode == ViewMode.TREE_STACK) {
                refreshApisForCurrentSelection();
            }
        });
        tblTreeGroups.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = tblTreeGroups.rowAtPoint(e.getPoint());
                int c = tblTreeGroups.columnAtPoint(e.getPoint());
                if (r >= 0) {
                    int mr = tblTreeGroups.convertRowIndexToModel(r);
                    if (treeGroupsModel.isFolderRow(mr) && (c == 0 || e.getClickCount() == 2)) {
                        treeGroupsModel.toggleFolderExpanded(mr);
                    }
                }
            }
        });

        tblApis.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblApis.getSelectedRow();
                if (vr >= 0) {
                    int mr = tblApis.convertRowIndexToModel(vr);
                    CompactRow row = apiModel.getRowAt(mr);
                    if (row != null) {
                        if (row.call != null) {
                            updateOverlapInspector(row.call);
                            if (onInspectCall != null) {
                                onInspectCall.accept(row.call);
                            }
                        } else if (row.isHeader && !row.groupedCalls.isEmpty()) {
                            updateOverlapInspector(row.groupedCalls.get(0));
                        }
                    }
                }
            }
        });

        tblApis.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int vr = tblApis.rowAtPoint(e.getPoint());
                int vc = tblApis.columnAtPoint(e.getPoint());
                if (vr >= 0) {
                    int mr = tblApis.convertRowIndexToModel(vr);
                    CompactRow row = apiModel.getRowAt(mr);
                    if (row != null && row.isHeader && (vc == 0 || e.getClickCount() == 2)) {
                        apiModel.toggleExpanded(row.pathKey);
                    }
                }
            }
        });

        JPopupMenu apiPopup = new JPopupMenu();
        JMenuItem miPush = new JMenuItem("+ Đưa vào hàng đẩy Logger++");
        miPush.addActionListener(e -> {
            int vr = tblApis.getSelectedRow();
            if (vr >= 0 && onQueuePush != null) {
                CompactRow row = apiModel.getRowAt(tblApis.convertRowIndexToModel(vr));
                if (row != null && !row.groupedCalls.isEmpty()) {
                    onQueuePush.accept(row.groupedCalls);
                }
            }
        });
        JMenuItem miRepeater = new JMenuItem("Send to Repeater");
        miRepeater.addActionListener(e -> {
            int vr = tblApis.getSelectedRow();
            if (vr >= 0 && onSendRepeater != null) {
                CompactRow row = apiModel.getRowAt(tblApis.convertRowIndexToModel(vr));
                if (row != null && row.call != null) {
                    onSendRepeater.accept(row.call.capture);
                }
            }
        });
        apiPopup.add(miPush);
        apiPopup.add(miRepeater);
        tblApis.setComponentPopupMenu(apiPopup);

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
    }

    public void applyViewMode(ViewMode mode) {
        this.viewMode = mode;
        boolean dark = AuditTheme.isDark(api, this);
        Color accent = AuditTheme.accentForeground(dark);
        AuditTheme.styleTogglePill(btnModeTree, mode == ViewMode.TREE_STACK, accent, dark);
        AuditTheme.styleTogglePill(btnModeDup, mode == ViewMode.DUP_PATH, accent, dark);
        AuditTheme.styleTogglePill(btnModeDiff, mode == ViewMode.DIFF_NOTE_ONLY, accent, dark);
        AuditTheme.styleTogglePill(btnModeFlat, mode == ViewMode.FLAT_FILTER, accent, dark);

        boolean showTree = (mode == ViewMode.TREE_STACK);
        treeWrap.setVisible(showTree);
        if (showTree) {
            verticalTreeApiSplit.setDividerSize(6);
            verticalTreeApiSplit.setDividerLocation(185);
        } else {
            verticalTreeApiSplit.setDividerSize(0);
            verticalTreeApiSplit.setDividerLocation(0);
        }
        refreshData();
        revalidate();
    }

    public void refreshData() {
        String q = txtSearch.getText().trim().toLowerCase();
        treeGroupsModel.setFilter("WITH_APIS", q);
        refreshApisForCurrentSelection();
        if (selectedCall != null) {
            updateOverlapInspector(selectedCall);
        }
    }

    private void refreshApisForCurrentSelection() {
        List<ActionGroup> scopeGroups = null;
        if (viewMode == ViewMode.TREE_STACK) {
            int vr = tblTreeGroups.getSelectedRow();
            if (vr >= 0) {
                scopeGroups = treeGroupsModel.getGroupsAt(tblTreeGroups.convertRowIndexToModel(vr));
            }
        }
        String methodFilter = extractMethodFilter();
        String httpFilter = extractHttpFilter();
        String q = txtSearch.getText().trim().toLowerCase();
        apiModel.rebuild(viewMode, scopeGroups, methodFilter, httpFilter, q);
        lblSummaryChip.setText(apiModel.getTotalMatchingCalls() + " API • " + apiModel.getDuplicatePathCount() + " path trùng");
    }

    private void updateOverlapInspector(ApiCall call) {
        this.selectedCall = call;
        if (call == null) {
            lblOverlapTitle.setText("Chọn 1 API bất kỳ để xem nó trùng ở những đường dẫn nào & khác gì nhau");
            overlapModel.setData(null, Collections.emptyList());
            return;
        }
        GroupTableModel.PathOverlapSummary summary = GroupTableModel.summarizePathOverlap(store, call);
        lblOverlapTitle.setText("🔍 " + summary.pathKey
            + " — Trùng " + summary.totalCalls + " lần (" + summary.distinctLocations + " vị trí) | "
            + summary.diffSummary);
        overlapModel.setData(call, summary.calls);
    }

    private String extractMethodFilter() {
        Object sel = cbMethod.getSelectedItem();
        String s = sel != null ? sel.toString() : "";
        return s.startsWith("Method:") ? "" : s;
    }

    private String extractHttpFilter() {
        Object sel = cbHttp.getSelectedItem();
        String s = sel != null ? sel.toString() : "";
        return s.startsWith("HTTP:") ? "" : s;
    }

    private static void setColumnWidths(TableColumnModel cm, int[] widths) {
        for (int i = 0; i < Math.min(cm.getColumnCount(), widths.length); i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private static final class CompactRow {
        final boolean isHeader;
        final boolean collapsed;
        final boolean diffVariant;
        final String pathKey;
        final String col0;
        final String nameOrAction;
        final String method;
        final String path;
        final String http;
        final String params;
        final String overlapDiff;
        final String note;
        final AuditNote.Severity worst;
        final ApiCall call;
        final List<ApiCall> groupedCalls;

        CompactRow(
            boolean isHeader, boolean collapsed, boolean diffVariant, String pathKey,
            String col0, String nameOrAction, String method, String path, String http,
            String params, String overlapDiff, String note, AuditNote.Severity worst,
            ApiCall call, List<ApiCall> groupedCalls
        ) {
            this.isHeader = isHeader;
            this.collapsed = collapsed;
            this.diffVariant = diffVariant;
            this.pathKey = pathKey;
            this.col0 = col0;
            this.nameOrAction = nameOrAction;
            this.method = method;
            this.path = path;
            this.http = http;
            this.params = params;
            this.overlapDiff = overlapDiff;
            this.note = note;
            this.worst = worst;
            this.call = call;
            this.groupedCalls = groupedCalls;
        }
    }

    private static final class CompactApiTableModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Vị trí / Nhóm tìm thấy", "Method", "Endpoint / Path", "HTTP", "Params", "Trùng & Khác biệt", "NOTE"
        };

        private final ActionGroupStore store;
        private List<CompactRow> rows = new ArrayList<>();
        private final java.util.Set<String> expanded = new java.util.HashSet<>();
        private int totalMatchingCalls = 0;
        private int duplicatePathCount = 0;

        CompactApiTableModel(ActionGroupStore store) {
            this.store = store;
        }

        int getTotalMatchingCalls() { return totalMatchingCalls; }
        int getDuplicatePathCount() { return duplicatePathCount; }

        CompactRow getRowAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        void toggleExpanded(String pathKey) {
            if (!expanded.add(pathKey)) {
                expanded.remove(pathKey);
            }
        }

        void expandAll() {
            for (CompactRow r : rows) {
                if (r.isHeader) {
                    expanded.add(r.pathKey);
                }
            }
        }

        void collapseAll() {
            expanded.clear();
        }

        void rebuild(ViewMode mode, List<ActionGroup> scopeGroups, String methodFilter, String httpFilter, String q) {
            List<ActionGroup> groups = scopeGroups != null
                ? scopeGroups
                : (store != null ? store.snapshotGroups() : Collections.emptyList());

            Map<String, List<ApiCall>> globalByPath = new LinkedHashMap<>();
            if (store != null) {
                for (ActionGroup g : store.snapshotGroups()) {
                    for (ApiCall c : g.getCalls()) {
                        globalByPath.computeIfAbsent(GroupTableModel.normalizeApiGroupPath(c), k -> new ArrayList<>()).add(c);
                    }
                }
            }

            List<ApiCall> matched = new ArrayList<>();
            for (ActionGroup g : groups) {
                for (ApiCall c : g.getCalls()) {
                    if (!methodFilter.isEmpty() && !c.capture.method.equalsIgnoreCase(methodFilter)) {
                        continue;
                    }
                    if (!httpFilter.isEmpty()) {
                        int st = c.capture.statusCode;
                        int want = httpFilter.charAt(0) - '0';
                        if (st / 100 != want) {
                            continue;
                        }
                    }
                    if (!q.isEmpty()) {
                        boolean hit = c.displayName().toLowerCase().contains(q)
                            || c.displayPath().toLowerCase().contains(q)
                            || c.capture.method.toLowerCase().contains(q)
                            || c.notesSummary().toLowerCase().contains(q);
                        if (!hit) {
                            continue;
                        }
                    }
                    matched.add(c);
                }
            }
            this.totalMatchingCalls = matched.size();

            Map<String, List<ApiCall>> byPath = new LinkedHashMap<>();
            for (ApiCall c : matched) {
                byPath.computeIfAbsent(GroupTableModel.normalizeApiGroupPath(c), k -> new ArrayList<>()).add(c);
            }
            int dupPaths = 0;
            for (List<ApiCall> b : byPath.values()) {
                if (b.size() >= 2) {
                    dupPaths++;
                }
            }
            this.duplicatePathCount = dupPaths;

            List<CompactRow> out = new ArrayList<>();
            if (mode == ViewMode.FLAT_FILTER || mode == ViewMode.TREE_STACK) {
                int idx = 0;
                for (ApiCall c : matched) {
                    idx++;
                    String pk = GroupTableModel.normalizeApiGroupPath(c);
                    List<ApiCall> allSame = globalByPath.getOrDefault(pk, Collections.singletonList(c));
                    GroupTableModel.PathOverlapSummary ov = GroupTableModel.summarizeCallsForPath(pk, allSame);
                    String ovTxt = allSame.size() > 1
                        ? ("Trùng " + ov.totalCalls + " nơi | " + (ov.allIdentical ? "Giống 100%" : ov.diffSummary))
                        : "Duy nhất";
                    out.add(leafRow(String.valueOf(idx), c.displayName(), c, ovTxt, !ov.allIdentical && allSame.size() > 1));
                }
            } else {
                for (Map.Entry<String, List<ApiCall>> e : byPath.entrySet()) {
                    String pk = e.getKey();
                    List<ApiCall> bucket = e.getValue();
                    GroupTableModel.PathOverlapSummary ov = GroupTableModel.summarizeCallsForPath(pk, bucket);

                    if (mode == ViewMode.DIFF_NOTE_ONLY && bucket.size() >= 2 && ov.allIdentical) {
                        ApiCall first = bucket.get(0);
                        boolean anyNote = false;
                        for (ApiCall c : bucket) {
                            if (c.hasNotes()) {
                                anyNote = true;
                                break;
                            }
                        }
                        if (!anyNote) {
                            out.add(leafRow("1", first.displayName(), first,
                                "Ẩn lặp: trùng " + bucket.size() + " nơi (Giống 100%)", false));
                            continue;
                        }
                    }

                    if (bucket.size() < 2) {
                        ApiCall single = bucket.get(0);
                        List<ApiCall> allSame = globalByPath.getOrDefault(pk, bucket);
                        GroupTableModel.PathOverlapSummary fullOv = GroupTableModel.summarizeCallsForPath(pk, allSame);
                        String ovTxt = allSame.size() > 1
                            ? ("Trùng " + fullOv.totalCalls + " nơi toàn site | " + (fullOv.allIdentical ? "Giống 100%" : fullOv.diffSummary))
                            : "Duy nhất";
                        out.add(leafRow(String.valueOf(single.index), single.displayName(), single, ovTxt, !fullOv.allIdentical && allSame.size() > 1));
                        continue;
                    }

                    boolean isExp = expanded.contains(pk) || !q.isEmpty();
                    ApiCall base = bucket.get(0);
                    AuditNote.Severity worst = null;
                    StringBuilder notes = new StringBuilder();
                    for (ApiCall c : bucket) {
                        worst = AuditNote.worst(worst, c.worstSeverity());
                        if (notes.length() == 0 && c.hasNotes()) {
                            notes.append(c.notesSummary());
                        }
                    }
                    out.add(new CompactRow(
                        true, !isExp, !ov.allIdentical, pk,
                        isExp ? "▼" : "▶",
                        (isExp ? "📂 " : "📁 ") + pk + " (" + bucket.size() + " lần • " + ov.distinctLocations + " vị trí)",
                        base.capture.method, pk, String.valueOf(base.capture.statusCode),
                        ov.allIdentical ? "Cùng param" : "Nhiều kiểu",
                        ov.diffSummary, notes.toString(), worst, null, bucket
                    ));
                    if (isExp) {
                        int k = 0;
                        for (ApiCall child : bucket) {
                            k++;
                            String diff = GroupTableModel.describeCallDiffAgainst(child, base);
                            out.add(leafRow(String.valueOf(k), "    ↳ " + child.displayName(), child, diff,
                                !diff.startsWith("Giống") && !diff.equals("[Đang xem]")));
                        }
                    }
                }
            }
            this.rows = out;
            fireTableDataChanged();
        }

        private static CompactRow leafRow(String col0, String name, ApiCall c, String ovTxt, boolean diff) {
            String paramTxt = c.capture.paramCount() > 0
                ? (c.capture.paramCount() + " (" + c.capture.paramSignature() + ")")
                : "0";
            return new CompactRow(
                false, false, diff, GroupTableModel.normalizeApiGroupPath(c),
                col0, name, c.capture.method, c.displayPath(),
                String.valueOf(c.capture.statusCode), paramTxt, ovTxt, c.notesSummary(),
                c.worstSeverity(), c, Collections.singletonList(c)
            );
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            CompactRow r = rows.get(row);
            switch (col) {
                case 0: return r.col0;
                case 1: return r.nameOrAction;
                case 2: return r.method;
                case 3: return r.path;
                case 4: return r.http;
                case 5: return r.params;
                case 6: return r.overlapDiff;
                case 7: return r.note;
                default: return "";
            }
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

    private static final class CompactApiRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        CompactApiRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 2 || column == 4) ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (table.getModel() instanceof CompactApiTableModel) {
                CompactRow meta = ((CompactApiTableModel) table.getModel()).getRowAt(table.convertRowIndexToModel(row));
                if (meta != null && !isSelected) {
                    Color bg = meta.isHeader
                        ? AuditTheme.cardSurface(dark)
                        : AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                    Color fg = table.getForeground();
                    if (meta.diffVariant && column == 6) {
                        fg = AuditTheme.severityForeground(AuditNote.Severity.WARN, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (meta.isHeader && (column == 0 || column == 1)) {
                        fg = AuditTheme.accentForeground(dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 2) {
                        fg = AuditTheme.methodForeground(meta.method, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 4 && meta.call != null) {
                        fg = AuditTheme.statusCodeForeground(meta.call.capture.statusCode, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                    c.setBackground(bg);
                    c.setForeground(fg);
                }
            }
            return c;
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
}
