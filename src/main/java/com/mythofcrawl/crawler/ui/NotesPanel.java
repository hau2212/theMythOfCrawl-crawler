package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
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
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Consolidated Audit NOTEs tab with BurpFilterBar, overflow warning banner,
 * column management, and multi-row Repeater / Organizer / Logger++ actions.
 */
public final class NotesPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlState state;
    private final ActionGroupStore store;
    private final Consumer<ApiCall> onInspectCall;
    private final Runnable onRevealInspector;
    private final BiConsumer<Integer, Integer> onJumpToGroupAndCall;
    private final Consumer<List<ApiCall>> onQueuePushCalls;
    private final Consumer<List<ApiCall>> onSendRepeaterCalls;
    private final Consumer<List<ApiCall>> onSendOrganizerCalls;

    private final JPanel topToolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
    private final JComboBox<String> cbSeverity = new JComboBox<>(
        new String[]{"Mức độ: Tất cả (ALL)", "Lỗi (ERROR)", "Cảnh báo (WARN)", "Thông tin (INFO)"});
    private final JButton btnActions = new JButton("Actions");
    private final JLabel lblOverflowWarn = new JLabel();
    private final BurpFilterBar filterBar;

    private final GroupTableModel.AuditNotesTableModel notesModel;
    private final JTable tblNotes;
    private final TableSelectionPreserver.ColumnManager colMgr;
    private final JScrollPane spNotes;
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final BurpComponents.EmptyStatePanel emptyState;
    private final boolean[] restoring = {false};

    public NotesPanel(
        MontoyaApi api,
        CrawlConfig config,
        ActionGroupStore store,
        Runnable onPersistConfig,
        Consumer<ApiCall> onInspectCall,
        Runnable onRevealInspector,
        BiConsumer<Integer, Integer> onJumpToGroupAndCall,
        Consumer<List<ApiCall>> onQueuePushCalls,
        Consumer<List<ApiCall>> onSendRepeaterCalls,
        Consumer<List<ApiCall>> onSendOrganizerCalls
    ) {
        this(api, config, null, store, onPersistConfig, onInspectCall, onRevealInspector,
            onJumpToGroupAndCall, onQueuePushCalls, onSendRepeaterCalls, onSendOrganizerCalls);
    }

    public NotesPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        ActionGroupStore store,
        Runnable onPersistConfig,
        Consumer<ApiCall> onInspectCall,
        Runnable onRevealInspector,
        BiConsumer<Integer, Integer> onJumpToGroupAndCall,
        Consumer<List<ApiCall>> onQueuePushCalls,
        Consumer<List<ApiCall>> onSendRepeaterCalls,
        Consumer<List<ApiCall>> onSendOrganizerCalls
    ) {
        this.api = api;
        this.state = state;
        this.store = store;
        this.onInspectCall = onInspectCall;
        this.onRevealInspector = onRevealInspector;
        this.onJumpToGroupAndCall = onJumpToGroupAndCall;
        this.onQueuePushCalls = onQueuePushCalls;
        this.onSendRepeaterCalls = onSendRepeaterCalls;
        this.onSendOrganizerCalls = onSendOrganizerCalls;

        this.filterBar = new BurpFilterBar(
            api,
            EnumSet.of(BurpFilterBar.QuickChip.MUTATING),
            true,
            false
        );
        this.notesModel = new GroupTableModel.AuditNotesTableModel(store);
        this.tblNotes = new JTable(notesModel);

        setLayout(new BorderLayout(0, 0));

        topToolbar.add(cbSeverity);
        topToolbar.add(btnActions);
        lblOverflowWarn.setVisible(false);
        topToolbar.add(lblOverflowWarn);

        JPanel northWrap = new JPanel(new BorderLayout(0, 0));
        northWrap.setOpaque(false);
        northWrap.add(topToolbar, BorderLayout.NORTH);
        northWrap.add(filterBar, BorderLayout.SOUTH);
        add(northWrap, BorderLayout.NORTH);

        AuditTheme.configureAuditTable(tblNotes, api);
        tblNotes.setAutoCreateRowSorter(true);
        tblNotes.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblNotes.setDefaultRenderer(Object.class, new GroupTableModel.AuditNoteCellRenderer(api));

        this.colMgr = new TableSelectionPreserver.ColumnManager(
            tblNotes, "notes", config, onPersistConfig,
            new int[]{82, 130, 260, 74, 68, 220, 340}, 3);

        this.spNotes = new JScrollPane(tblNotes);
        this.emptyState = new BurpComponents.EmptyStatePanel(
            api,
            "No items to show",
            "Audit anomalies and validation findings discovered during crawling will appear here."
        );
        centerCards.add(spNotes, "TABLE");
        centerCards.add(emptyState, "EMPTY");
        add(centerCards, BorderLayout.CENTER);

        wireEvents();
        applyTheme();
        refreshData();
    }

    private void wireEvents() {
        cbSeverity.addActionListener(e -> applyFilter());
        filterBar.addFilterChangeListener(c -> applyFilter());

        tblNotes.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoring[0]) {
                AuditNote note = getPrimarySelectedNote();
                if (note != null && onInspectCall != null) {
                    ApiCall call = findApiCall(note.groupId, note.callIndex);
                    if (call != null) {
                        onInspectCall.accept(call);
                    }
                }
            }
        });
        tblNotes.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblNotes.getSelectedRow() >= 0 && onRevealInspector != null) {
                    onRevealInspector.run();
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miJump = new JMenuItem("Đi tới Nhóm này (Workspace)");
        JMenuItem miPush = new JMenuItem("+ Đưa API vào hàng đẩy Logger++");
        JMenuItem miRepeater = new JMenuItem("Send to Repeater (Ctrl+R)");
        JMenuItem miOrganizer = new JMenuItem("Send to Organizer (Ctrl+O)");
        JMenuItem miCopy = new JMenuItem("Copy Chi tiết NOTE");

        miJump.addActionListener(e -> {
            AuditNote n = getPrimarySelectedNote();
            if (n != null && onJumpToGroupAndCall != null) {
                onJumpToGroupAndCall.accept(n.groupId, n.callIndex);
            }
        });
        miPush.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onQueuePushCalls != null) {
                onQueuePushCalls.accept(calls);
            }
        });
        miRepeater.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendRepeaterCalls != null) {
                onSendRepeaterCalls.accept(calls);
            }
        });
        miOrganizer.addActionListener(e -> {
            List<ApiCall> calls = getSelectedCallsForAction();
            if (!calls.isEmpty() && onSendOrganizerCalls != null) {
                onSendOrganizerCalls.accept(calls);
            }
        });
        miCopy.addActionListener(e -> {
            AuditNote n = getPrimarySelectedNote();
            if (n != null) {
                String txt = "[" + n.severity.label + " - " + n.type.label + "] "
                    + n.groupPath + " | " + n.method + " " + n.endpoint + " -> " + n.message;
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
            }
        });

        popup.add(miJump);
        popup.add(miPush);
        popup.add(miRepeater);
        popup.add(miOrganizer);
        if (state != null) {
            popup.add(WebLocatorHelper.createSubMenuForCall(this, state, () -> {
                List<ApiCall> calls = getSelectedCallsForAction();
                return calls.isEmpty() ? null : calls.get(0);
            }, null));
        }
        popup.addSeparator();
        popup.add(miCopy);

        btnActions.addActionListener(e -> popup.show(btnActions, 0, btnActions.getHeight()));

        tblNotes.addMouseListener(new MouseAdapter() {
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblNotes.rowAtPoint(e.getPoint());
                    if (row >= 0 && !tblNotes.isRowSelected(row)) {
                        tblNotes.setRowSelectionInterval(row, row);
                    }
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
        });
    }

    public void refreshData() {
        applyFilter();
        int overflow = store != null ? store.getOverflowNoteCount() : 0;
        if (overflow > 0) {
            lblOverflowWarn.setText("Cảnh báo: Đã đạt giới hạn 30,000 NOTE (bỏ qua " + overflow + " NOTE mới hơn)");
            lblOverflowWarn.setVisible(true);
        } else {
            lblOverflowWarn.setVisible(false);
        }
    }

    public void focusSearch() {
        filterBar.focusSearch();
    }

    public void clearActiveFilter() {
        filterBar.clearFilters();
    }

    public List<ApiCall> getSelectedCallsForAction() {
        int[] vrs = tblNotes.getSelectedRows();
        if (vrs == null || vrs.length == 0) {
            return Collections.emptyList();
        }
        Map<Long, ApiCall> uniq = new LinkedHashMap<>();
        for (int vr : vrs) {
            if (vr >= 0 && vr < tblNotes.getRowCount()) {
                AuditNote n = notesModel.getNoteAt(tblNotes.convertRowIndexToModel(vr));
                if (n != null) {
                    ApiCall c = findApiCall(n.groupId, n.callIndex);
                    if (c != null) {
                        uniq.put(c.callUid, c);
                    }
                }
            }
        }
        return new ArrayList<>(uniq.values());
    }

    private void applyFilter() {
        int idx = cbSeverity.getSelectedIndex();
        String sev = "ALL";
        if (idx == 1) {
            sev = "ERROR";
        } else if (idx == 2) {
            sev = "WARN";
        } else if (idx == 3) {
            sev = "INFO";
        }
        final String resolvedSev = sev;
        TableSelectionPreserver.preserveAroundUpdate(tblNotes, notesModel::getKeyAt, restoring, () -> {
            notesModel.setFilter(resolvedSev, "", filterBar.getCriteria());
        });
        filterBar.setCounts(notesModel.getRowCount(), notesModel.getTotalSourceCount());
        ((CardLayout) centerCards.getLayout()).show(centerCards, notesModel.getRowCount() == 0 ? "EMPTY" : "TABLE");
    }

    private AuditNote getPrimarySelectedNote() {
        int vr = tblNotes.getSelectedRow();
        if (vr < 0 || vr >= tblNotes.getRowCount()) {
            return null;
        }
        return notesModel.getNoteAt(tblNotes.convertRowIndexToModel(vr));
    }

    private ApiCall findApiCall(int groupId, int callIndex) {
        if (store == null) {
            return null;
        }
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

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.panelSurface(dark));
        AuditTheme.styleSlimToolbar(topToolbar, dark);
        btnActions.setIcon(BurpIcons.chevronDown(AuditTheme.neutralButtonFg(dark)));
        btnActions.setHorizontalTextPosition(SwingConstants.LEFT);
        AuditTheme.stylePlainButton(btnActions, dark);
        lblOverflowWarn.setForeground(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
        lblOverflowWarn.setFont(AuditTheme.displayFont(api, this).deriveFont(Font.BOLD));
        AuditTheme.configureAuditTable(tblNotes, api);
        AuditTheme.styleScrollPane(spNotes, dark);
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
