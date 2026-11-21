package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Excel Checklist Coverage tab with BurpFilterBar, column chooser,
 * stable selection/scroll preservation, and batch Repeater/Organizer actions.
 */
public final class CoveragePanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlState state;
    private final Consumer<ApiSpecEntry> onInspectSpec;
    private final Runnable onRevealInspector;
    private final BiConsumer<CapturedApiEntry, String> onSendRepeater;
    private final Consumer<CapturedApiEntry> onSendOrganizer;

    private final JPanel topToolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
    private final JComboBox<String> cbStatusFilter = new JComboBox<>(
        new String[]{"Trạng thái: Tất cả (ALL)", "Đã lấy (MATCHED)", "Còn thiếu (MISSING)", "Có NOTE (!)"});
    private final JButton btnActions = new JButton("Actions");
    private final BurpFilterBar filterBar;

    private final CoverageTableModel coverageModel = new CoverageTableModel();
    private final JTable tblCoverage = new JTable(coverageModel);
    private final TableSelectionPreserver.ColumnManager colMgr;
    private final JScrollPane spCoverage;
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel emptyState;
    private final boolean[] restoring = {false};

    public CoveragePanel(
        MontoyaApi api,
        CrawlConfig config,
        Runnable onPersistConfig,
        Consumer<ApiSpecEntry> onInspectSpec,
        Runnable onRevealInspector,
        BiConsumer<CapturedApiEntry, String> onSendRepeater,
        Consumer<CapturedApiEntry> onSendOrganizer
    ) {
        this(api, config, null, onPersistConfig, onInspectSpec, onRevealInspector, onSendRepeater, onSendOrganizer);
    }

    public CoveragePanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        Runnable onPersistConfig,
        Consumer<ApiSpecEntry> onInspectSpec,
        Runnable onRevealInspector,
        BiConsumer<CapturedApiEntry, String> onSendRepeater,
        Consumer<CapturedApiEntry> onSendOrganizer
    ) {
        this.api = api;
        this.state = state;
        this.onInspectSpec = onInspectSpec;
        this.onRevealInspector = onRevealInspector;
        this.onSendRepeater = onSendRepeater;
        this.onSendOrganizer = onSendOrganizer;

        this.filterBar = new BurpFilterBar(
            api,
            EnumSet.of(
                BurpFilterBar.QuickChip.ERRORS_4XX_5XX,
                BurpFilterBar.QuickChip.AUTH_401_403,
                BurpFilterBar.QuickChip.MUTATING,
                BurpFilterBar.QuickChip.HAS_PARAMS,
                BurpFilterBar.QuickChip.HAS_NOTES
            ),
            true,
            false
        );

        setLayout(new BorderLayout(0, 0));

        topToolbar.add(cbStatusFilter);
        topToolbar.add(btnActions);
        JPanel northWrap = new JPanel(new BorderLayout(0, 0));
        northWrap.setOpaque(false);
        northWrap.add(topToolbar, BorderLayout.NORTH);
        northWrap.add(filterBar, BorderLayout.SOUTH);
        add(northWrap, BorderLayout.NORTH);

        AuditTheme.configureAuditTable(tblCoverage, api);
        tblCoverage.setAutoCreateRowSorter(true);
        tblCoverage.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblCoverage.setDefaultRenderer(Object.class, new CoverageTableModel.CoverageCellRenderer(api));

        this.colMgr = new TableSelectionPreserver.ColumnManager(
            tblCoverage, "coverage", config, onPersistConfig,
            new int[]{54, 108, 70, 240, 250, 82, 54, 240}, 4);

        this.spCoverage = new JScrollPane(tblCoverage);
        this.emptyState = new BurpComponents.EmptyStatePanel(
            api,
            "No items to show",
            "Import an Excel specification (.xlsx / .csv) from Dashboard Actions to track API coverage."
        );
        centerCards.add(spCoverage, "TABLE");
        centerCards.add(emptyState, "EMPTY");
        add(centerCards, BorderLayout.CENTER);

        wireEvents();
        applyTheme();
    }

    private void wireEvents() {
        cbStatusFilter.addActionListener(e -> applyFilter());
        filterBar.addFilterChangeListener(c -> applyFilter());

        tblCoverage.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoring[0]) {
                ApiSpecEntry entry = getPrimarySelectedEntry();
                if (entry != null && onInspectSpec != null) {
                    onInspectSpec.accept(entry);
                }
            }
        });
        tblCoverage.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblCoverage.getSelectedRow() >= 0 && onRevealInspector != null) {
                    onRevealInspector.run();
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miRepeater = new JMenuItem("Send to Repeater (Ctrl+R)");
        JMenuItem miOrganizer = new JMenuItem("Send to Organizer (Ctrl+O)");
        JMenuItem miCopyAction = new JMenuItem("Copy Action Path");
        JMenuItem miCopyUrl = new JMenuItem("Copy Full URL");

        miRepeater.addActionListener(e -> {
            for (ApiSpecEntry entry : getSelectedEntries()) {
                CapturedApiEntry cap = entry.getPrimaryCapture();
                if (cap != null && onSendRepeater != null) {
                    onSendRepeater.accept(cap, "STT " + entry.stt + " " + cap.method);
                }
            }
        });
        miOrganizer.addActionListener(e -> {
            for (ApiSpecEntry entry : getSelectedEntries()) {
                CapturedApiEntry cap = entry.getPrimaryCapture();
                if (cap != null && onSendOrganizer != null) {
                    onSendOrganizer.accept(cap);
                }
            }
        });
        miCopyAction.addActionListener(e -> {
            ApiSpecEntry entry = getPrimarySelectedEntry();
            CapturedApiEntry cap = entry != null ? entry.getPrimaryCapture() : null;
            if (cap != null) {
                copyText(cap.actionPath);
            }
        });
        miCopyUrl.addActionListener(e -> {
            ApiSpecEntry entry = getPrimarySelectedEntry();
            CapturedApiEntry cap = entry != null ? entry.getPrimaryCapture() : null;
            if (cap != null) {
                copyText(cap.fullUrl);
            }
        });

        popup.add(miRepeater);
        popup.add(miOrganizer);
        if (state != null) {
            popup.add(WebLocatorHelper.createSubMenuForSpec(this, state, this::getPrimarySelectedEntry, null));
        }
        popup.addSeparator();
        popup.add(miCopyAction);
        popup.add(miCopyUrl);

        btnActions.addActionListener(e -> popup.show(btnActions, 0, btnActions.getHeight()));

        tblCoverage.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblCoverage.rowAtPoint(e.getPoint());
                    if (row >= 0 && !tblCoverage.isRowSelected(row)) {
                        tblCoverage.setRowSelectionInterval(row, row);
                    }
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
        });
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        TableSelectionPreserver.preserveAroundUpdate(tblCoverage, coverageModel::getKeyAt, restoring, () -> {
            coverageModel.setMatcher(matcher);
        });
        filterBar.setCounts(coverageModel.getRowCount(), coverageModel.getTotalSourceCount());
    }

    public void refreshData() {
        applyFilter();
    }

    public void focusSearch() {
        filterBar.focusSearch();
    }

    public void clearActiveFilter() {
        filterBar.clearFilters();
    }

    public List<ApiSpecEntry> getSelectedEntries() {
        int[] vrs = tblCoverage.getSelectedRows();
        if (vrs == null || vrs.length == 0) {
            return Collections.emptyList();
        }
        List<ApiSpecEntry> out = new ArrayList<>();
        for (int vr : vrs) {
            if (vr >= 0 && vr < tblCoverage.getRowCount()) {
                ApiSpecEntry en = coverageModel.getEntryAt(tblCoverage.convertRowIndexToModel(vr));
                if (en != null) {
                    out.add(en);
                }
            }
        }
        return out;
    }

    private ApiSpecEntry getPrimarySelectedEntry() {
        int vr = tblCoverage.getSelectedRow();
        if (vr < 0 || vr >= tblCoverage.getRowCount()) {
            return null;
        }
        return coverageModel.getEntryAt(tblCoverage.convertRowIndexToModel(vr));
    }

    private void applyFilter() {
        int idx = cbStatusFilter.getSelectedIndex();
        String mode = "ALL";
        if (idx == 1) {
            mode = "MATCHED";
        } else if (idx == 2) {
            mode = "MISSING";
        } else if (idx == 3) {
            mode = "NOTES";
        }
        final String resolved = mode;
        TableSelectionPreserver.preserveAroundUpdate(tblCoverage, coverageModel::getKeyAt, restoring, () -> {
            coverageModel.setFilter(resolved, "", filterBar.getCriteria());
        });
        filterBar.setCounts(coverageModel.getRowCount(), coverageModel.getTotalSourceCount());
        ((CardLayout) centerCards.getLayout()).show(centerCards, coverageModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private static void copyText(String s) {
        if (s != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.panelSurface(dark));
        AuditTheme.styleSlimToolbar(topToolbar, dark);
        btnActions.setIcon(BurpIcons.chevronDown(AuditTheme.neutralButtonFg(dark)));
        btnActions.setHorizontalTextPosition(SwingConstants.LEFT);
        AuditTheme.stylePlainButton(btnActions, dark);
        AuditTheme.configureAuditTable(tblCoverage, api);
        AuditTheme.styleScrollPane(spCoverage, dark);
        emptyState.applyTheme();
        filterBar.applyTheme(api);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (topToolbar != null && filterBar != null) {
            applyTheme();
        }
    }
}
