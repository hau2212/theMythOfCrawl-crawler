package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.core.guardrail.ActionGateAuditLog;
import com.mythofcrawl.crawler.core.guardrail.GuardrailMode;
import com.mythofcrawl.crawler.core.guardrail.PolicyEvaluator;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.core.guardrail.PolicyScope;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.ui.kit.MethodChipBar;
import com.mythofcrawl.crawler.ui.kit.ModernToggleSwitch;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.kit.WrappedText;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Unified Action Guardrail Policy Ledger Tab (LAW 1-4 & Law 7 compliant):
 * - 5 PolicyScopes with Progressive Disclosure in the right CRUD editor
 * - Strict #1 ALLOW vs Guardrail rules, pinned Save (Ctrl+S) + confirmation banner
 * - Trash + Undo, Up/Down/Top reordering, JSON Import/Export, 4-level NONE release
 * - Decision Trace table + non-mutating Policy Simulator
 */
public final class GuardrailLedgerTab extends JPanel {

    private final CrawlState state;
    private final PolicyLedger ledger;
    private final PolicyEvaluator evaluator;
    private final ActionGateAuditLog auditLog;
    private final ToastPane toast;
    private final Runnable onChanged;

    private InstantEventBus.Subscription ledgerSub;
    private InstantEventBus.Subscription auditSub;
    private AutoCloseable themeSub;

    // Top controls
    private final JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
    private final SearchField searchField = new SearchField("Tìm tên luật, nhánh, đường dẫn...", 120);
    private final JComboBox<String> cbScopeFilter = new JComboBox<>(new String[]{
        "Tất cả phạm vi", "GLOBAL", "METHOD_OR_ACTION", "BRANCH", "PATH_PATTERN", "EXACT_API"
    });
    private final Pill pillCounts = new Pill("0 luật", Theme.Token.INFO);
    private final Ui.UiButton btnNewRule = Ui.solidPrimary("Thêm luật mới", VectorIcons.plus(Theme.inkWhite()));
    private final Ui.UiButton btnReleaseMenu = Ui.secondaryBtn("Nhả NONE", VectorIcons.unlock(Theme.text()));
    private final Ui.UiButton btnImportJson = Ui.secondaryBtn("Nhập JSON");
    private final Ui.UiButton btnExportJson = Ui.secondaryBtn("Xuất JSON");

    // Left: Rules table + sub-tabs (Decision Trace | Simulator | Trash)
    private final RulesTableModel rulesModel = new RulesTableModel();
    private final ThemedTable tblRules = new ThemedTable(rulesModel);
    private final ThemedTable.EmptyWrap rulesWrap;

    private final TraceTableModel traceModel = new TraceTableModel();
    private final ThemedTable tblTrace = new ThemedTable(traceModel);
    private final ThemedTable.EmptyWrap traceWrap;

    private final TrashTableModel trashModel = new TrashTableModel();
    private final ThemedTable tblTrash = new ThemedTable(trashModel);
    private final ThemedTable.EmptyWrap trashWrap;

    // Simulator inputs
    private final JTextField simBranch = new JTextField(16);
    private final JTextField simHost = new JTextField("api.example.com", 14);
    private final JComboBox<String> simMethod = new JComboBox<>(new String[]{
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    });
    private final JTextField simPath = new JTextField("/api/orders/123", 18);
    private final JComboBox<String> simUiAction = new JComboBox<>(new String[]{
        "", "click", "submit", "delete", "change", "navigate"
    });
    private final Pill simResultPill = new Pill("Chưa chạy thử", Theme.Token.NEUTRAL);
    private final WrappedText simDetailsText = new WrappedText("Nhập thông số request rồi bấm Chạy thử để xem luật thắng cuộc.", true);

    // Right: CRUD Editor with Progressive Disclosure
    private final JPanel editorCard;
    private final JLabel lblEditorHeading = new JLabel("Biên tập Chính sách Guardrail");
    private final JTextField txtRuleName = new JTextField(20);
    private final JSpinner spPriority = new JSpinner(new SpinnerNumberModel(150, 1, 9999, 10));
    private final ModernToggleSwitch swEnabled = new ModernToggleSwitch("Đang bật", true, Theme.Token.SUCCESS);
    private final JComboBox<String> cbRuleKind = new JComboBox<>(new String[]{
        "Chặn / Kiểm soát (Guardrail)", "#1 ALLOW (Ưu tiên tối cao)"
    });
    private final JComboBox<PolicyScope> cbScope = new JComboBox<>(PolicyScope.values());
    private final JComboBox<GuardrailMode> cbMode = new JComboBox<>(GuardrailMode.values());
    private final MethodChipBar methodChips = new MethodChipBar();
    private final JComboBox<String> cbUiAction = new JComboBox<>(new String[]{
        "* (Mọi thao tác UI)", "click", "submit", "delete", "change", "navigate"
    });
    private final JTextField txtBranchPrefix = new JTextField(20);
    private final JTextField txtPathPattern = new JTextField(20);
    private final JTextField txtExactEndpoint = new JTextField(20);
    private final JTextField txtNotes = new JTextField(20);

    private final JPanel rowBranch = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JPanel rowPathPattern = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JPanel rowExactEndpoint = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JPanel rowMode = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JLabel lblScopeHint = new JLabel();

    private final Pill lblSaveBanner = new Pill("Sẵn sàng", Theme.Token.NEUTRAL);
    private final Ui.UiButton btnSaveRule = Ui.solidPrimary("Lưu chính sách (Ctrl+S)", VectorIcons.check(Theme.inkWhite()));
    private final Ui.UiButton btnResetForm = Ui.secondaryBtn("Tạo mới");

    private String editingRuleId = "";
    private final boolean[] restoringRules = {false};

    public GuardrailLedgerTab(
        CrawlState state,
        PolicyLedger ledger,
        PolicyEvaluator evaluator,
        ActionGateAuditLog auditLog,
        ToastPane toast,
        Runnable onChanged
    ) {
        this.state = state;
        this.ledger = ledger != null ? ledger : new PolicyLedger();
        this.evaluator = evaluator;
        this.auditLog = auditLog != null ? auditLog : new ActionGateAuditLog();
        this.toast = toast;
        this.onChanged = onChanged;

        this.rulesWrap = ThemedTable.wrapWithEmptyState(
            tblRules, "Chưa có chính sách Guardrail", "Bấm 'Thêm luật mới' hoặc áp dụng 1 Playbook trong tab Hướng dẫn.");
        this.traceWrap = ThemedTable.wrapWithEmptyState(
            tblTrace, "Chưa có lịch sử quyết định", "Khi Proxy hoặc Simulator chạy, các quyết định ALLOW / HOLD / INTERCEPT sẽ ghi tại đây.");
        this.trashWrap = ThemedTable.wrapWithEmptyState(
            tblTrash, "Thùng rác trống", "Các luật vừa xóa mềm sẽ giữ tại đây để Hoàn tác (Undo).");

        this.editorCard = new JPanel(new BorderLayout(0, Theme.SPACE_2));

        setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        buildTopBar();
        JComponent leftPane = buildLeftPane();
        buildRightEditor();

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, editorCard);
        mainSplit.setResizeWeight(0.58);
        mainSplit.setOneTouchExpandable(false);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());

        add(topBar, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);

        installCtrlSShortcut();
        this.ledgerSub = this.ledger.subscribe(() -> SwingUtilities.invokeLater(this::refreshData));
        this.auditSub = this.auditLog.subscribe(entry -> SwingUtilities.invokeLater(this::refreshTraceOnly));
        this.themeSub = Theme.onThemeChange(this::applyTheme);

        applyTheme();
        startNewRule();
        refreshData();
    }

    private void buildTopBar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        left.setOpaque(false);
        searchField.onSearchChange(q -> refreshRulesOnly());
        cbScopeFilter.addActionListener(e -> refreshRulesOnly());
        left.add(searchField);
        left.add(cbScopeFilter);
        left.add(pillCounts);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 2));
        right.setOpaque(false);
        btnNewRule.addActionListener(e -> startNewRule());
        btnReleaseMenu.addActionListener(e -> showReleaseMenu());
        btnImportJson.addActionListener(e -> importJsonFile());
        btnExportJson.addActionListener(e -> exportJsonFile());

        right.add(btnNewRule);
        right.add(btnReleaseMenu);
        right.add(btnImportJson);
        right.add(btnExportJson);

        topBar.setOpaque(false);
        topBar.add(left, BorderLayout.CENTER);
        topBar.add(right, BorderLayout.EAST);
    }

    private JComponent buildLeftPane() {
        tblRules.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblRules.setDefaultRenderer(Object.class, new RuleTableRenderer());
        tblRules.getColumnModel().getColumn(0).setPreferredWidth(44);
        tblRules.getColumnModel().getColumn(1).setPreferredWidth(76);
        tblRules.getColumnModel().getColumn(2).setPreferredWidth(105);
        tblRules.getColumnModel().getColumn(3).setPreferredWidth(200);
        tblRules.getColumnModel().getColumn(4).setPreferredWidth(105);
        tblRules.getColumnModel().getColumn(5).setPreferredWidth(110);
        tblRules.getColumnModel().getColumn(6).setPreferredWidth(140);
        tblRules.getColumnModel().getColumn(7).setPreferredWidth(54);

        tblRules.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringRules[0]) {
                PolicyRule r = getSelectedRule();
                if (r != null) {
                    loadRuleIntoEditor(r);
                }
            }
        });

        Ui.UiButton btnToggle = Ui.secondaryBtn("Bật/Tắt");
        Ui.UiButton btnDup = Ui.secondaryBtn("Nhân bản", VectorIcons.copy(Theme.text()));
        Ui.UiButton btnUp = Ui.secondaryBtn("Lên");
        Ui.UiButton btnDown = Ui.secondaryBtn("Xuống");
        Ui.UiButton btnTop = Ui.secondaryBtn("Lên đầu");
        Ui.UiButton btnDel = Ui.dangerBtn("Xóa", VectorIcons.trash(Theme.inkWhite()));
        Ui.UiButton btnUndo = Ui.secondaryBtn("Hoàn tác", VectorIcons.undo(Theme.text()));

        btnToggle.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null) {
                ledger.setEnabled(r.id, !r.enabled);
                notifyMutated();
            }
        });
        btnDup.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null) {
                PolicyRule copy = ledger.duplicateRule(r.id);
                if (copy != null) {
                    loadRuleIntoEditor(copy);
                    showToast(Theme.Token.INFO, "Đã nhân bản", copy.name);
                    notifyMutated();
                }
            }
        });
        btnUp.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null && ledger.moveUp(r.id)) notifyMutated();
        });
        btnDown.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null && ledger.moveDown(r.id)) notifyMutated();
        });
        btnTop.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null && ledger.moveToTop(r.id)) notifyMutated();
        });
        btnDel.addActionListener(e -> {
            PolicyRule r = getSelectedRule();
            if (r != null && ledger.deleteRule(r.id)) {
                showToast(Theme.Token.WARNING, "Đã đưa vào Thùng rác", r.name);
                startNewRule();
                notifyMutated();
            }
        });
        btnUndo.addActionListener(e -> {
            PolicyRule restored = ledger.undoLastDelete();
            if (restored != null) {
                loadRuleIntoEditor(restored);
                showToast(Theme.Token.SUCCESS, "Đã khôi phục", restored.name);
                notifyMutated();
            }
        });

        JPanel ruleBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 2));
        ruleBtns.setOpaque(false);
        ruleBtns.add(btnToggle);
        ruleBtns.add(btnDup);
        ruleBtns.add(btnUp);
        ruleBtns.add(btnDown);
        ruleBtns.add(btnTop);
        ruleBtns.add(btnDel);
        ruleBtns.add(btnUndo);

        JPanel rulesSection = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        rulesSection.setOpaque(false);
        rulesSection.add(rulesWrap, BorderLayout.CENTER);
        rulesSection.add(ruleBtns, BorderLayout.SOUTH);

        // Bottom tabs: Decision Trace | Policy Simulator | Trash
        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.addTab("Decision Trace", buildTracePanel());
        bottomTabs.addTab("Policy Simulator", buildSimulatorPanel());
        bottomTabs.addTab("Thùng rác (Trash)", buildTrashPanel());
        ThemeSyncHelper.styleTabbedPane(bottomTabs);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rulesSection, bottomTabs);
        leftSplit.setResizeWeight(0.58);
        leftSplit.setOneTouchExpandable(false);
        leftSplit.setBorder(BorderFactory.createEmptyBorder());
        return leftSplit;
    }

    private JPanel buildTracePanel() {
        tblTrace.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblTrace.setDefaultRenderer(Object.class, new TraceRenderer());
        Ui.UiButton btnClearTrace = Ui.secondaryBtn("Xóa Trace", VectorIcons.trash(Theme.text()));
        btnClearTrace.addActionListener(e -> {
            auditLog.clear();
            refreshTraceOnly();
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 2));
        top.setOpaque(false);
        top.add(btnClearTrace);

        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.add(top, BorderLayout.NORTH);
        p.add(traceWrap, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildSimulatorPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        simBranch.setText(state != null ? state.getCurrentActionPath() : "/ > page load");

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0; form.add(new JLabel("Nhánh UI:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; form.add(simBranch, gc);
        gc.gridx = 2; gc.weightx = 0; form.add(new JLabel("Host:"), gc);
        gc.gridx = 3; gc.weightx = 0.6; form.add(simHost, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0; form.add(new JLabel("Method:"), gc);
        gc.gridx = 1; gc.weightx = 0.4; form.add(simMethod, gc);
        gc.gridx = 2; gc.weightx = 0; form.add(new JLabel("UI Action:"), gc);
        gc.gridx = 3; gc.weightx = 0.6; form.add(simUiAction, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0; form.add(new JLabel("Path:"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1.0; form.add(simPath, gc);

        Ui.UiButton btnRunSim = Ui.solidPrimary("Chạy thử (Simulate)", VectorIcons.play(Theme.inkWhite()));
        btnRunSim.addActionListener(e -> runSimulation());
        gc.gridx = 3; gc.gridwidth = 1; gc.weightx = 0; form.add(btnRunSim, gc);

        JPanel resRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        resRow.setOpaque(false);
        resRow.add(simResultPill);

        JPanel out = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        out.setOpaque(false);
        out.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        out.add(form, BorderLayout.NORTH);
        out.add(resRow, BorderLayout.CENTER);
        out.add(simDetailsText, BorderLayout.SOUTH);
        return out;
    }

    private JPanel buildTrashPanel() {
        Ui.UiButton btnRestore = Ui.tonalBtn(Theme.Token.SUCCESS, "Khôi phục đã chọn", VectorIcons.undo(Theme.token(Theme.Token.SUCCESS).text()));
        Ui.UiButton btnEmpty = Ui.dangerBtn("Dọn sạch");
        btnRestore.addActionListener(e -> {
            int vr = tblTrash.getSelectedRow();
            if (vr >= 0) {
                PolicyLedger.TrashEntry te = trashModel.getAt(tblTrash.convertRowIndexToModel(vr));
                if (te != null) {
                    ledger.restoreFromTrash(te.rule.id);
                    notifyMutated();
                }
            }
        });
        btnEmpty.addActionListener(e -> {
            ledger.emptyTrash();
            refreshData();
        });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 2));
        btns.setOpaque(false);
        btns.add(btnRestore);
        btns.add(btnEmpty);

        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.add(trashWrap, BorderLayout.CENTER);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private void buildRightEditor() {
        editorCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel head = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        head.setOpaque(false);
        lblEditorHeading.setFont(FontManager.uiTitle());
        head.add(lblEditorHeading, BorderLayout.WEST);
        head.add(swEnabled, BorderLayout.EAST);

        java.awt.Dimension compactFieldDim = new java.awt.Dimension(110, 28);
        txtRuleName.setPreferredSize(compactFieldDim);
        cbRuleKind.setPreferredSize(compactFieldDim);
        spPriority.setPreferredSize(new java.awt.Dimension(86, 28));
        cbScope.setPreferredSize(compactFieldDim);
        cbMode.setPreferredSize(compactFieldDim);
        cbUiAction.setPreferredSize(compactFieldDim);
        txtBranchPrefix.setPreferredSize(compactFieldDim);
        txtPathPattern.setPreferredSize(compactFieldDim);
        txtExactEndpoint.setPreferredSize(compactFieldDim);
        txtNotes.setPreferredSize(compactFieldDim);
        methodChips.setLayout(new com.mythofcrawl.crawler.ui.WrapLayout(FlowLayout.LEFT, Theme.SPACE_1, 3));

        ThemeSyncHelper.ViewportWidthFormPanel form = new ThemeSyncHelper.ViewportWidthFormPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 0, Theme.SPACE_2, 0);
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.weightx = 1.0;
        form.add(labeledField("Tên luật (trống = tự sinh)", txtRuleName), gc);

        gc.gridy = 1; gc.gridwidth = 1; gc.weightx = 0.62;
        gc.insets = new Insets(0, 0, Theme.SPACE_2, Theme.SPACE_2);
        form.add(labeledField("Loại quyết định", cbRuleKind), gc);
        gc.gridx = 1; gc.weightx = 0.38;
        gc.insets = new Insets(0, 0, Theme.SPACE_2, 0);
        form.add(labeledField("Độ ưu tiên", spPriority), gc);

        rowMode.setOpaque(false);
        rowMode.add(labeledField("Chế độ thực thi (Mode)", cbMode), BorderLayout.CENTER);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 1; gc.weightx = 0.48;
        gc.insets = new Insets(0, 0, Theme.SPACE_1, Theme.SPACE_2);
        form.add(labeledField("Phạm vi (Scope)", cbScope), gc);
        gc.gridx = 1; gc.weightx = 0.52;
        gc.insets = new Insets(0, 0, Theme.SPACE_1, 0);
        form.add(rowMode, gc);

        lblScopeHint.setFont(FontManager.uiSmall());
        gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 2; gc.weightx = 1.0;
        gc.insets = new Insets(0, 0, Theme.SPACE_2, 0);
        form.add(lblScopeHint, gc);

        gc.gridy = 4;
        form.add(labeledField("HTTP Methods", methodChips), gc);

        gc.gridy = 5;
        form.add(labeledField("Thao tác UI (uiAction)", cbUiAction), gc);

        rowBranch.setOpaque(false);
        rowBranch.add(labeledField("Tiền tố Nhánh UI (Branch Prefix)", txtBranchPrefix), BorderLayout.CENTER);
        gc.gridy = 6;
        form.add(rowBranch, gc);

        rowPathPattern.setOpaque(false);
        rowPathPattern.add(labeledField("Mẫu Đường dẫn (*, {id}, re:...)", txtPathPattern), BorderLayout.CENTER);
        gc.gridy = 7;
        form.add(rowPathPattern, gc);

        rowExactEndpoint.setOpaque(false);
        rowExactEndpoint.add(labeledField("API cụ thể (vd: DELETE /api/orders/1)", txtExactEndpoint), BorderLayout.CENTER);
        gc.gridy = 8;
        form.add(rowExactEndpoint, gc);

        gc.gridy = 9;
        form.add(labeledField("Ghi chú", txtNotes), gc);

        gc.gridy = 10; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        JPanel glue = new JPanel();
        glue.setOpaque(false);
        form.add(glue, gc);

        cbRuleKind.addActionListener(e -> updateProgressiveFields());
        cbScope.addActionListener(e -> updateProgressiveFields());

        JScrollPane editorScroll = new JScrollPane(form);
        editorScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorScroll.setOpaque(false);
        editorScroll.getViewport().setOpaque(false);

        JPanel footer = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        footer.setOpaque(false);
        JPanel footerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
        footerLeft.setOpaque(false);
        footerLeft.add(lblSaveBanner);

        JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_2, 0));
        footerRight.setOpaque(false);
        btnResetForm.addActionListener(e -> startNewRule());
        btnSaveRule.addActionListener(e -> saveCurrentRule());
        footerRight.add(btnResetForm);
        footerRight.add(btnSaveRule);

        footer.add(footerLeft, BorderLayout.CENTER);
        footer.add(footerRight, BorderLayout.EAST);

        editorCard.add(head, BorderLayout.NORTH);
        editorCard.add(editorScroll, BorderLayout.CENTER);
        editorCard.add(footer, BorderLayout.SOUTH);
    }

    private static JPanel labeledField(String caption, Component field) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(caption);
        l.setFont(FontManager.uiSmall().deriveFont(java.awt.Font.BOLD));
        l.setForeground(Theme.textSecondary());
        p.add(l, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private void updateProgressiveFields() {
        boolean isAllow = cbRuleKind.getSelectedIndex() == 1;
        PolicyScope sc = (PolicyScope) cbScope.getSelectedItem();
        if (sc == null) sc = PolicyScope.GLOBAL;

        rowMode.setVisible(!isAllow);
        rowBranch.setVisible(sc == PolicyScope.BRANCH || sc == PolicyScope.PATH_PATTERN);
        rowPathPattern.setVisible(sc == PolicyScope.PATH_PATTERN);
        rowExactEndpoint.setVisible(sc == PolicyScope.EXACT_API);

        if (isAllow) {
            lblScopeHint.setText("Ưu tiên #1 ALLOW: cho phép đi qua, cắt ngang mọi luật chặn/giữ.");
        } else {
            switch (sc) {
                case GLOBAL:
                    lblScopeHint.setText("Toàn cục: ẩn ô nhánh/đường dẫn; áp dụng trên mọi nhánh cho các method đã chọn.");
                    break;
                case METHOD_OR_ACTION:
                    lblScopeHint.setText("Method / UI Action: khớp theo HTTP Method và/hoặc nút UI (click/submit/delete).");
                    break;
                case BRANCH:
                    lblScopeHint.setText("Nhánh UI: chỉ áp dụng khi request phát sinh từ nhánh có tiền tố này.");
                    break;
                case PATH_PATTERN:
                    lblScopeHint.setText("Mẫu đường dẫn: hỗ trợ wildcard * / {param} hoặc regex tiền tố re:.");
                    break;
                case EXACT_API:
                    lblScopeHint.setText("API cụ thể: khớp chính xác endpoint (vd: DELETE /api/users/42).");
                    break;
            }
        }
        editorCard.revalidate();
        editorCard.repaint();
    }

    private void startNewRule() {
        editingRuleId = "";
        lblEditorHeading.setText("Tạo Chính sách Guardrail Mới");
        txtRuleName.setText("");
        spPriority.setValue(Integer.valueOf(150));
        swEnabled.setSelected(true);
        cbRuleKind.setSelectedIndex(0);
        cbScope.setSelectedItem(PolicyScope.GLOBAL);
        cbMode.setSelectedItem(GuardrailMode.HOLD_AT_PROXY);
        methodChips.selectOnly(Set.of("POST", "PUT", "PATCH", "DELETE"));
        cbUiAction.setSelectedIndex(0);
        txtBranchPrefix.setText(state != null ? state.getCurrentActionPath() : "");
        txtPathPattern.setText("/api/*");
        txtExactEndpoint.setText("");
        txtNotes.setText("");
        lblSaveBanner.setPill("Đang soạn luật mới", Theme.Token.NEUTRAL, false);
        updateProgressiveFields();
    }

    private void loadRuleIntoEditor(PolicyRule r) {
        editingRuleId = r.id;
        lblEditorHeading.setText("Sửa Luật [" + r.id + "]");
        txtRuleName.setText(r.name);
        spPriority.setValue(Integer.valueOf(r.priority));
        swEnabled.setSelected(r.enabled);
        cbRuleKind.setSelectedIndex(r.allow ? 1 : 0);
        cbScope.setSelectedItem(r.scope);
        cbMode.setSelectedItem(r.mode);
        if (r.methods.isEmpty()) {
            methodChips.selectAll();
        } else {
            methodChips.selectOnly(r.methods);
        }
        if (r.uiActions.isEmpty()) {
            cbUiAction.setSelectedIndex(0);
        } else {
            cbUiAction.setSelectedItem(r.uiActions.iterator().next());
        }
        txtBranchPrefix.setText(r.branchPrefix);
        txtPathPattern.setText(r.pathPattern);
        txtExactEndpoint.setText(r.exactEndpoint);
        txtNotes.setText(r.notes);
        lblSaveBanner.setPill("Đang sửa #" + r.id, Theme.Token.INFO, false);
        updateProgressiveFields();
    }

    private void saveCurrentRule() {
        boolean isAllow = cbRuleKind.getSelectedIndex() == 1;
        PolicyScope sc = (PolicyScope) cbScope.getSelectedItem();
        if (sc == null) sc = PolicyScope.GLOBAL;
        GuardrailMode gm = (GuardrailMode) cbMode.getSelectedItem();
        if (gm == null) gm = GuardrailMode.HOLD_AT_PROXY;
        if (isAllow) {
            gm = GuardrailMode.NONE;
        }

        Set<String> chosenMethods = methodChips.selectedMethods();
        String uiAct = cbUiAction.getSelectedIndex() > 0
            ? String.valueOf(cbUiAction.getSelectedItem()).trim().toLowerCase(Locale.ROOT)
            : "";

        String name = txtRuleName.getText().trim();
        if (name.isEmpty()) {
            String mLabel = chosenMethods.size() == MethodChipBar.METHODS.length
                ? "ALL" : String.join("/", chosenMethods);
            if (mLabel.isEmpty()) mLabel = "ANY";
            name = (isAllow ? "ALLOW " : (gm.shortLabel + " ")) + sc.name() + " (" + mLabel + ")";
            txtRuleName.setText(name);
        }

        String id = editingRuleId.isEmpty() ? UUID.randomUUID().toString().substring(0, 8) : editingRuleId;
        int pri = ((Number) spPriority.getValue()).intValue();
        PolicyRule rule = new PolicyRule(id, name, pri, swEnabled.isSelected(), isAllow, sc, gm);
        if (chosenMethods.size() < MethodChipBar.METHODS.length) {
            for (String m : chosenMethods) {
                rule.withMethods(m);
            }
        }
        if (!uiAct.isEmpty()) {
            rule.withUiActions(uiAct);
        }
        if (sc == PolicyScope.BRANCH || sc == PolicyScope.PATH_PATTERN) {
            rule.withBranchPrefix(txtBranchPrefix.getText());
        }
        if (sc == PolicyScope.PATH_PATTERN) {
            rule.withPathPattern(txtPathPattern.getText());
        }
        if (sc == PolicyScope.EXACT_API) {
            rule.withExactEndpoint(txtExactEndpoint.getText());
        }
        rule.withNotes(txtNotes.getText());

        ledger.upsertRule(rule);
        editingRuleId = rule.id;
        lblSaveBanner.setPill("Đã lưu: " + rule.name, Theme.Token.SUCCESS, true);
        showToast(Theme.Token.SUCCESS, "Đã lưu chính sách", rule.name);
        notifyMutated();
    }

    private void showReleaseMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem miAll = new JMenuItem("1. Nhả TẤT CẢ về Nguyên bản (NONE)");
        JMenuItem miBranch = new JMenuItem("2. Nhả Nhánh đang mở về NONE");
        JMenuItem miApi = new JMenuItem("3. Nhả API ở ô Exact Endpoint về NONE");

        miAll.addActionListener(e -> {
            ledger.releaseAllToNone();
            showToast(Theme.Token.SUCCESS, "Đã nhả toàn bộ", "Mọi luật chặn/giữ đã chuyển về NONE.");
            notifyMutated();
        });
        miBranch.addActionListener(e -> {
            String branch = txtBranchPrefix.getText().trim();
            if (branch.isEmpty() && state != null) {
                branch = state.getCurrentActionPath();
            }
            if (!branch.isEmpty()) {
                ledger.releaseBranchToNone(branch);
                showToast(Theme.Token.SUCCESS, "Đã mở khóa nhánh (#1 ALLOW)", branch);
                notifyMutated();
            }
        });
        miApi.addActionListener(e -> {
            String ep = txtExactEndpoint.getText().trim();
            if (ep.isEmpty()) ep = simMethod.getSelectedItem() + " " + simPath.getText().trim();
            if (!ep.isEmpty()) {
                ledger.releaseEndpointToNone(ep);
                showToast(Theme.Token.SUCCESS, "Đã mở khóa API (#1 ALLOW)", ep);
                notifyMutated();
            }
        });

        m.add(miAll);
        m.add(miBranch);
        m.add(miApi);
        m.show(btnReleaseMenu, 0, btnReleaseMenu.getHeight());
    }

    private void runSimulation() {
        String branch = simBranch.getText().trim();
        String host = simHost.getText().trim();
        String method = String.valueOf(simMethod.getSelectedItem());
        String path = simPath.getText().trim();
        String uiAct = String.valueOf(simUiAction.getSelectedItem()).trim();

        PolicyEvaluator.Decision d = PolicyEvaluator.simulate(ledger, branch, host, method, path, uiAct);
        Theme.Token tok = d.allowed ? Theme.Token.SUCCESS
            : (d.effectiveMode == GuardrailMode.INTERCEPT_WAIT ? Theme.Token.WARNING : Theme.Token.PENDING);
        simResultPill.setPill(
            (d.allowed ? "ALLOW" : d.effectiveMode.shortLabel) + " — " + d.reason,
            tok, true);

        StringBuilder sb = new StringBuilder();
        sb.append("Luật thắng: ").append(d.winningRule != null ? (d.winningRule.name + " [" + d.winningRule.scope + "]") : "(Mặc định NONE)")
            .append(" | Số ứng viên khớp: ").append(d.matchedCandidates.size());
        if (!d.matchedCandidates.isEmpty()) {
            sb.append("\nDanh sách khớp: ");
            for (int i = 0; i < d.matchedCandidates.size(); i++) {
                if (i > 0) sb.append(", ");
                PolicyRule mr = d.matchedCandidates.get(i);
                sb.append(mr.name).append(" (").append(mr.allow ? "ALLOW" : mr.mode.shortLabel).append(")");
            }
        }
        simDetailsText.setText(sb.toString());
    }

    private void importJsonFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Nhập chính sách Guardrail (JSON)");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try {
                String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                ledger.loadFromJson(json);
                showToast(Theme.Token.SUCCESS, "Đã nhập JSON", f.getName());
                notifyMutated();
            } catch (Exception ex) {
                showToast(Theme.Token.DANGER, "Lỗi đọc JSON", ex.getMessage());
            }
        }
    }

    private void exportJsonFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Xuất chính sách Guardrail (JSON)");
        fc.setSelectedFile(new File("guardrail-policies.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try {
                Files.writeString(f.toPath(), ledger.toJson(), StandardCharsets.UTF_8);
                showToast(Theme.Token.SUCCESS, "Đã xuất JSON", f.getName());
            } catch (Exception ex) {
                showToast(Theme.Token.DANGER, "Lỗi ghi JSON", ex.getMessage());
            }
        }
    }

    private void installCtrlSShortcut() {
        int mask = java.awt.event.InputEvent.CTRL_DOWN_MASK;
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            } catch (java.awt.HeadlessException ignored) {
            }
        }
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "savePolicy");
        getActionMap().put("savePolicy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveCurrentRule();
            }
        });
    }

    private PolicyRule getSelectedRule() {
        int vr = tblRules.getSelectedRow();
        if (vr < 0 || vr >= tblRules.getRowCount()) {
            return null;
        }
        return rulesModel.getAt(tblRules.convertRowIndexToModel(vr));
    }

    private void refreshRulesOnly() {
        restoringRules[0] = true;
        String selId = null;
        PolicyRule cur = getSelectedRule();
        if (cur != null) selId = cur.id;
        try {
            String scopeSel = cbScopeFilter.getSelectedIndex() > 0
                ? String.valueOf(cbScopeFilter.getSelectedItem()) : "";
            List<PolicyRule> filtered = new ArrayList<>();
            String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
            for (PolicyRule r : ledger.snapshotRules()) {
                if (!scopeSel.isEmpty() && !r.scope.name().equals(scopeSel)) {
                    continue;
                }
                if (!q.isEmpty()) {
                    String hay = (r.name + " " + r.branchPrefix + " " + r.pathPattern + " " + r.exactEndpoint + " " + r.notes)
                        .toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                filtered.add(r);
            }
            rulesModel.setRows(filtered);
            pillCounts.setPill(filtered.size() + " luật", Theme.Token.INFO, false);
            rulesWrap.refreshEmptyState();

            if (selId != null) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (selId.equals(filtered.get(i).id)) {
                        int vr = tblRules.convertRowIndexToView(i);
                        if (vr >= 0) tblRules.setRowSelectionInterval(vr, vr);
                        break;
                    }
                }
            }
        } finally {
            restoringRules[0] = false;
        }
    }

    private void refreshTraceOnly() {
        traceModel.setEntries(auditLog.snapshot());
        traceWrap.refreshEmptyState();
    }

    public void refreshData() {
        refreshRulesOnly();
        refreshTraceOnly();
        trashModel.setEntries(ledger.snapshotTrash());
        trashWrap.refreshEmptyState();
    }

    public void dispose() {
        if (ledgerSub != null) {
            ledgerSub.close();
            ledgerSub = null;
        }
        if (auditSub != null) {
            auditSub.close();
            auditSub = null;
        }
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
    }

    private void applyTheme() {
        setBackground(Theme.canvas());
        ThemeSyncHelper.applyTree(this);
        editorCard.setBackground(Theme.surface());
        editorCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.border(), 1, true),
            BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3)));
        lblEditorHeading.setForeground(Theme.text());
        lblScopeHint.setForeground(Theme.textSecondary());
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (editorCard != null && lblEditorHeading != null) {
            applyTheme();
        }
    }

    private void showToast(Theme.Token tok, String title, String msg) {
        if (toast != null) {
            toast.show(tok, title, msg);
        }
    }

    private void notifyMutated() {
        refreshData();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private static final class RulesTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Bật", "Loại", "Phạm vi", "Tên luật", "Chế độ", "Method / UI", "Mẫu / Nhánh", "Hits"};
        private List<PolicyRule> rows = Collections.emptyList();

        void setRows(List<PolicyRule> rows) {
            this.rows = rows != null ? rows : Collections.emptyList();
            fireTableDataChanged();
        }

        PolicyRule getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            PolicyRule r = rows.get(row);
            switch (col) {
                case 0: return r.enabled ? "ON" : "OFF";
                case 1: return r.allow ? "ALLOW" : "GUARD";
                case 2: return r.scope.name();
                case 3: return r.name;
                case 4: return r.allow ? "NONE" : r.mode.shortLabel;
                case 5: {
                    String m = r.methods.isEmpty() ? "*" : String.join(",", r.methods);
                    String u = r.uiActions.isEmpty() ? "" : (" [" + String.join(",", r.uiActions) + "]");
                    return m + u;
                }
                case 6: {
                    if (!r.exactEndpoint.isEmpty()) return r.exactEndpoint;
                    if (!r.pathPattern.isEmpty()) return r.pathPattern;
                    if (!r.branchPrefix.isEmpty()) return r.branchPrefix;
                    return "(Toàn cục)";
                }
                case 7: return Long.valueOf(r.getHits());
                default: return "";
            }
        }
    }

    private static final class RuleTableRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            if (!isSelected && table.getModel() instanceof RulesTableModel) {
                PolicyRule r = ((RulesTableModel) table.getModel()).getAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                if (r != null && column == 1) {
                    c.setForeground(r.allow ? Theme.token(Theme.Token.SUCCESS).text() : Theme.token(Theme.Token.PENDING).text());
                    c.setFont(FontManager.uiSemiBold());
                } else if (r != null && column == 0) {
                    c.setForeground(r.enabled ? Theme.token(Theme.Token.SUCCESS).text() : Theme.muted());
                    c.setFont(FontManager.uiSemiBold());
                } else {
                    c.setForeground(Theme.text());
                }
            }
            return c;
        }
    }

    private static final class TraceTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Thời gian", "Kết quả", "Method", "Endpoint", "Nhánh UI", "Luật thắng", "Lý do"};
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        private List<ActionGateAuditLog.AuditEntry> rows = Collections.emptyList();

        void setEntries(List<ActionGateAuditLog.AuditEntry> all) {
            List<ActionGateAuditLog.AuditEntry> rev = new ArrayList<>();
            if (all != null) {
                for (int i = all.size() - 1; i >= 0; i--) {
                    rev.add(all.get(i));
                }
            }
            this.rows = rev;
            fireTableDataChanged();
        }

        ActionGateAuditLog.AuditEntry getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            ActionGateAuditLog.AuditEntry e = rows.get(row);
            switch (col) {
                case 0: synchronized (fmt) { return fmt.format(new Date(e.timestampMs)); }
                case 1: return e.outcome.name();
                case 2: return e.method;
                case 3: return e.path;
                case 4: return e.branchPath;
                case 5: return e.winningRuleName;
                case 6: return e.reason;
                default: return "";
            }
        }
    }

    private static final class TraceRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            if (!isSelected && table.getModel() instanceof TraceTableModel) {
                ActionGateAuditLog.AuditEntry en = ((TraceTableModel) table.getModel()).getAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                if (en != null && column == 1) {
                    Theme.Token tok = Theme.Token.INFO;
                    if (en.outcome == ActionGateAuditLog.Outcome.ALLOW || en.outcome == ActionGateAuditLog.Outcome.FORWARD) {
                        tok = Theme.Token.SUCCESS;
                    } else if (en.outcome == ActionGateAuditLog.Outcome.DROP) {
                        tok = Theme.Token.DANGER;
                    } else if (en.outcome == ActionGateAuditLog.Outcome.INTERCEPT_WAIT) {
                        tok = Theme.Token.WARNING;
                    }
                    c.setForeground(Theme.token(tok).text());
                    c.setFont(FontManager.uiSemiBold());
                } else {
                    c.setForeground(Theme.text());
                }
            }
            return c;
        }
    }

    private static final class TrashTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Tên luật đã xóa", "Phạm vi", "Chế độ"};
        private List<PolicyLedger.TrashEntry> rows = Collections.emptyList();

        void setEntries(List<PolicyLedger.TrashEntry> list) {
            this.rows = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        PolicyLedger.TrashEntry getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            PolicyLedger.TrashEntry te = rows.get(row);
            switch (col) {
                case 0: return te.rule.name;
                case 1: return te.rule.scope.name();
                case 2: return te.rule.allow ? "ALLOW" : te.rule.mode.shortLabel;
                default: return "";
            }
        }
    }
}
