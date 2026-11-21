package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.kit.WrappedText;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Target Excel Checklist, Coverage Matrix, Extra APIs & Interactive Custom {placeholder} Editor
 * (LAW 1-4 compliant).
 */
public final class TargetExcelTab extends JPanel {

    private final CrawlState state;
    private final CrawlConfig config;
    private volatile ExcelSpecMatcher matcher;
    private final UiHost host;
    private final ToastPane toast;
    private final Runnable onImportExcel;
    private final Consumer<ApiCall> onInspectCall;
    private final Consumer<ApiCall> onLocateWeb;

    private AutoCloseable themeSub;

    private final JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
    private final Ui.UiButton btnImport = Ui.solidPrimary("Nạp file Excel", VectorIcons.plus(Theme.inkWhite()));
    private final SearchField searchField = new SearchField("Tìm STT, method, spec path...", 120);
    private final JComboBox<String> cbFilterMode = new JComboBox<>(new String[]{
        "Tất cả chỉ tiêu", "Đã khớp (Matched)", "Chưa thấy (Missing)", "Có JS Hint"
    });
    private final Pill pillFile = new Pill("Chưa nạp Excel", Theme.Token.NEUTRAL);
    private final Pill pillCoverage = new Pill("0 / 0 (0%)", Theme.Token.INFO);

    private final JTabbedPane tabs = new JTabbedPane();

    // Tab 1: Spec entries
    private final SpecTableModel specModel = new SpecTableModel();
    private final ThemedTable tblSpec = new ThemedTable(specModel);
    private final ThemedTable.EmptyWrap specWrap;
    private final Ui.UiButton btnInspectMatched = Ui.secondaryBtn("Soi Request", VectorIcons.eye(Theme.text()));
    private final Ui.UiButton btnLocateMatched = Ui.secondaryBtn("Tìm trên Web", VectorIcons.locate(Theme.text()));
    private final Ui.UiButton btnEditPlaceholder = Ui.tonalBtn(Theme.Token.ACCENT, "Tạo mẫu {placeholder}", VectorIcons.edit(Theme.token(Theme.Token.ACCENT).text()));

    // Tab 2: Interactive Custom {placeholder} Editor
    private final JComboBox<String> cbTemplateMethod = new JComboBox<>(new String[]{
        "GET", "POST", "PUT", "PATCH", "DELETE", "*"
    });
    private final JTextField txtSourcePath = new JTextField("/api/v1/sess_91ab/orders/12345", 28);
    private final JTextField txtPlaceholderName = new JTextField("session", 14);
    private final JPanel segmentChipsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 4));
    private final JLabel lblTemplatePreview = new JLabel("/api/v1/{session}/orders/12345");
    private final TemplatesTableModel templatesModel = new TemplatesTableModel();
    private final ThemedTable tblTemplates = new ThemedTable(templatesModel);
    private final ThemedTable.EmptyWrap templatesWrap;
    private int selectedSegmentIndex = 2;

    // Tab 3: Extra captures not in Excel
    private final ExtraCallsModel extraModel = new ExtraCallsModel();
    private final ThemedTable tblExtra = new ThemedTable(extraModel);
    private final ThemedTable.EmptyWrap extraWrap;

    public TargetExcelTab(
        CrawlState state,
        CrawlConfig config,
        ExcelSpecMatcher initialMatcher,
        UiHost host,
        ToastPane toast,
        Runnable onImportExcel,
        Consumer<ApiCall> onInspectCall,
        Consumer<ApiCall> onLocateWeb
    ) {
        this.state = state;
        this.config = config;
        this.matcher = initialMatcher != null ? initialMatcher : ExcelSpecMatcher.empty();
        this.host = host;
        this.toast = toast;
        this.onImportExcel = onImportExcel;
        this.onInspectCall = onInspectCall;
        this.onLocateWeb = onLocateWeb;

        this.specWrap = ThemedTable.wrapWithEmptyState(
            tblSpec, "Chưa có dữ liệu Excel", "Bấm 'Nạp file Excel' hoặc chuyển sang tab 'Tạo mẫu {placeholder}' để định nghĩa chỉ tiêu.");
        this.templatesWrap = ThemedTable.wrapWithEmptyState(
            tblTemplates, "Chưa có mẫu {placeholder} tùy chỉnh", "Chọn 1 segment đường dẫn phía trên để gắn biến {session} / {tenant} / {id}.");
        this.extraWrap = ThemedTable.wrapWithEmptyState(
            tblExtra, "Chưa có API ngoài Excel", "Các API bắt được qua Proxy chưa khớp file Excel sẽ hiện tại đây.");

        setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        buildTopBar();
        tabs.addTab("Chỉ tiêu Excel", buildSpecTab());
        tabs.addTab("Tạo mẫu {placeholder}", buildPlaceholderEditorTab());
        tabs.addTab("API Ngoài Excel", buildExtraTab());

        add(topBar, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        this.themeSub = Theme.onThemeChange(this::applyTheme);
        applyTheme();
        rebuildSegmentChips();
        refreshData();
    }

    private void buildTopBar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        left.setOpaque(false);
        btnImport.addActionListener(e -> {
            if (onImportExcel != null) {
                onImportExcel.run();
            }
        });
        searchField.onSearchChange(q -> refreshSpecTable());
        cbFilterMode.addActionListener(e -> refreshSpecTable());

        left.add(btnImport);
        left.add(searchField);
        left.add(cbFilterMode);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 2));
        right.setOpaque(false);
        right.add(pillFile);
        right.add(pillCoverage);

        topBar.setOpaque(false);
        topBar.add(left, BorderLayout.CENTER);
        topBar.add(right, BorderLayout.EAST);
    }

    private JPanel buildSpecTab() {
        tblSpec.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblSpec.setDefaultRenderer(Object.class, new SpecRenderer());
        tblSpec.getColumnModel().getColumn(0).setPreferredWidth(70);
        tblSpec.getColumnModel().getColumn(1).setPreferredWidth(95);
        tblSpec.getColumnModel().getColumn(2).setPreferredWidth(74);
        tblSpec.getColumnModel().getColumn(3).setPreferredWidth(290);
        tblSpec.getColumnModel().getColumn(4).setPreferredWidth(54);
        tblSpec.getColumnModel().getColumn(5).setPreferredWidth(66);
        tblSpec.getColumnModel().getColumn(6).setPreferredWidth(250);

        tblSpec.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    inspectSelectedSpec();
                }
            }
        });

        btnInspectMatched.addActionListener(e -> inspectSelectedSpec());
        btnLocateMatched.addActionListener(e -> locateSelectedSpec());
        btnEditPlaceholder.addActionListener(e -> {
            ApiSpecEntry en = getSelectedSpec();
            if (en != null) {
                cbTemplateMethod.setSelectedItem(en.displayMethod);
                txtSourcePath.setText(en.rawSpecPath);
                selectedSegmentIndex = 0;
                rebuildSegmentChips();
                tabs.setSelectedIndex(1);
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        bar.setOpaque(false);
        bar.add(btnInspectMatched);
        bar.add(btnLocateMatched);
        bar.add(btnEditPlaceholder);

        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.add(specWrap, BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildPlaceholderEditorTab() {
        JPanel editorBox = new JPanel();
        editorBox.setOpaque(false);
        editorBox.setLayout(new BoxLayout(editorBox, BoxLayout.Y_AXIS));
        editorBox.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        WrappedText intro = new WrappedText(
            "Chọn hoặc nhập một URL Path chứa ID/session động, bấm vào từng segment để đánh dấu vị trí cần thay bằng {placeholder} (ví dụ /api/a/{session}/c).",
            true);
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        editorBox.add(intro);
        editorBox.add(Box.createVerticalStrut(Theme.SPACE_2));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        row1.setOpaque(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        Ui.UiButton btnSplitSegs = Ui.secondaryBtn("Tách Segment");
        btnSplitSegs.addActionListener(e -> {
            selectedSegmentIndex = 0;
            rebuildSegmentChips();
        });
        row1.add(new JLabel("Method:"));
        row1.add(cbTemplateMethod);
        row1.add(new JLabel("Đường dẫn gốc:"));
        row1.add(txtSourcePath);
        row1.add(btnSplitSegs);
        editorBox.add(row1);

        segmentChipsRow.setOpaque(false);
        segmentChipsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        editorBox.add(segmentChipsRow);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        Ui.UiButton btnPreview = Ui.secondaryBtn("Xem trước");
        btnPreview.addActionListener(e -> updateTemplatePreview());
        Ui.UiButton btnAddTemplate = Ui.solidPrimary("Thêm mẫu {placeholder} vào Matcher", VectorIcons.plus(Theme.inkWhite()));
        btnAddTemplate.addActionListener(e -> applyPlaceholderTemplate());

        row2.add(new JLabel("Tên biến ({...}):"));
        row2.add(txtPlaceholderName);
        row2.add(btnPreview);
        row2.add(new JLabel("Kết quả:"));
        lblTemplatePreview.setFont(FontManager.monoBold());
        row2.add(lblTemplatePreview);
        row2.add(btnAddTemplate);
        editorBox.add(row2);

        JPanel out = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        out.setOpaque(false);
        out.add(editorBox, BorderLayout.NORTH);
        out.add(templatesWrap, BorderLayout.CENTER);
        return out;
    }

    private JPanel buildExtraTab() {
        tblExtra.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblExtra.setDefaultRenderer(Object.class, new ExtraRenderer());
        Ui.UiButton btnInspectEx = Ui.secondaryBtn("Soi Request", VectorIcons.eye(Theme.text()));
        Ui.UiButton btnLocateEx = Ui.secondaryBtn("Tìm trên Web", VectorIcons.locate(Theme.text()));
        Ui.UiButton btnToPlaceholder = Ui.tonalBtn(Theme.Token.ACCENT, "Tạo {placeholder} từ API này", VectorIcons.plus(Theme.token(Theme.Token.ACCENT).text()));

        btnInspectEx.addActionListener(e -> {
            ApiCall c = getSelectedExtraCall();
            if (c != null && onInspectCall != null) onInspectCall.accept(c);
        });
        btnLocateEx.addActionListener(e -> {
            ApiCall c = getSelectedExtraCall();
            if (c != null && onLocateWeb != null) onLocateWeb.accept(c);
        });
        btnToPlaceholder.addActionListener(e -> {
            ApiCall c = getSelectedExtraCall();
            if (c != null) {
                cbTemplateMethod.setSelectedItem(c.capture.method);
                txtSourcePath.setText(c.displayPath());
                selectedSegmentIndex = 0;
                rebuildSegmentChips();
                tabs.setSelectedIndex(1);
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        bar.setOpaque(false);
        bar.add(btnInspectEx);
        bar.add(btnLocateEx);
        bar.add(btnToPlaceholder);

        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.add(extraWrap, BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    private void rebuildSegmentChips() {
        segmentChipsRow.removeAll();
        String raw = txtSourcePath.getText().trim();
        int q = raw.indexOf('?');
        String clean = q >= 0 ? raw.substring(0, q) : raw;
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        String[] segs = clean.isEmpty() ? new String[0] : clean.split("/");
        if (selectedSegmentIndex < 0 || selectedSegmentIndex >= segs.length) {
            selectedSegmentIndex = Math.max(0, segs.length - 1);
        }
        JLabel cap = new JLabel("Chọn segment để thay:");
        cap.setFont(FontManager.uiSmall().deriveFont(java.awt.Font.BOLD));
        cap.setForeground(Theme.textSecondary());
        segmentChipsRow.add(cap);

        for (int i = 0; i < segs.length; i++) {
            final int idx = i;
            String seg = segs[i];
            boolean sel = (idx == selectedSegmentIndex);
            Ui.UiButton chip = sel
                ? Ui.tonalBtn(Theme.Token.ACCENT, "[" + idx + "] /" + seg, null)
                : Ui.secondaryBtn("[" + idx + "] /" + seg);
            chip.addActionListener(e -> {
                selectedSegmentIndex = idx;
                rebuildSegmentChips();
            });
            segmentChipsRow.add(chip);
        }
        updateTemplatePreview();
        segmentChipsRow.revalidate();
        segmentChipsRow.repaint();
    }

    private void updateTemplatePreview() {
        String preview = ExcelSpecMatcher.replaceSegmentWithPlaceholder(
            txtSourcePath.getText().trim(), selectedSegmentIndex, txtPlaceholderName.getText().trim());
        lblTemplatePreview.setText(preview);
    }

    private void applyPlaceholderTemplate() {
        updateTemplatePreview();
        String tpl = lblTemplatePreview.getText().trim();
        String method = String.valueOf(cbTemplateMethod.getSelectedItem());
        if (tpl.isEmpty() || "/".equals(tpl)) {
            return;
        }
        matcher.addCustomPlaceholderSpec(method, tpl);
        matcher.registerCustomPlaceholderTemplate(tpl);
        if (toast != null) {
            toast.show(Theme.Token.SUCCESS, "Đã thêm mẫu {placeholder}", method + " " + tpl);
        }
        refreshData();
    }

    private ApiSpecEntry getSelectedSpec() {
        int vr = tblSpec.getSelectedRow();
        if (vr < 0 || vr >= tblSpec.getRowCount()) {
            return null;
        }
        return specModel.getAt(tblSpec.convertRowIndexToModel(vr));
    }

    private ApiCall getSelectedExtraCall() {
        int vr = tblExtra.getSelectedRow();
        if (vr < 0 || vr >= tblExtra.getRowCount()) {
            return null;
        }
        return extraModel.getAt(tblExtra.convertRowIndexToModel(vr));
    }

    private void inspectSelectedSpec() {
        ApiSpecEntry entry = getSelectedSpec();
        if (entry == null || !entry.isMatched()) {
            return;
        }
        ApiCall match = findApiCallForCapture(entry.getPrimaryCapture());
        if (match != null && onInspectCall != null) {
            onInspectCall.accept(match);
        }
    }

    private void locateSelectedSpec() {
        ApiSpecEntry entry = getSelectedSpec();
        if (entry == null || !entry.isMatched()) {
            return;
        }
        ApiCall match = findApiCallForCapture(entry.getPrimaryCapture());
        if (match != null && onLocateWeb != null) {
            onLocateWeb.accept(match);
        }
    }

    private ApiCall findApiCallForCapture(CapturedApiEntry cap) {
        if (cap == null || state == null) {
            return null;
        }
        for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
            for (ApiCall c : g.getCalls()) {
                if (c.capture == cap) {
                    return c;
                }
            }
        }
        return null;
    }

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.matcher = matcher != null ? matcher : ExcelSpecMatcher.empty();
        refreshData();
    }

    public void refreshData() {
        refreshSpecTable();
        refreshTemplatesTable();
        refreshExtraTable();
    }

    private void refreshSpecTable() {
        ExcelSpecMatcher m = this.matcher;
        ExcelSpecMatcher.CoverageStats stats = m.getCoverageStats();
        String fn = m.getSourceFileName().isEmpty() ? "Chưa nạp Excel" : m.getSourceFileName();
        pillFile.setPill(fn, m.isLoaded() ? Theme.Token.ACCENT : Theme.Token.NEUTRAL, false);
        int pct = stats.total > 0 ? (int) Math.round(stats.matched * 100.0 / stats.total) : 0;
        pillCoverage.setPill(stats.matched + " / " + stats.total + " (" + pct + "%)",
            stats.matched > 0 ? Theme.Token.SUCCESS : Theme.Token.INFO, false);

        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        int filterIdx = cbFilterMode.getSelectedIndex();
        List<ApiSpecEntry> out = new ArrayList<>();
        for (ApiSpecEntry e : m.getEntries()) {
            if (filterIdx == 1 && !e.isMatched()) continue;
            if (filterIdx == 2 && e.isMatched()) continue;
            if (filterIdx == 3 && e.getJsHints().isEmpty()) continue;
            if (!q.isEmpty()) {
                String hay = (e.stt + " " + e.displayMethod + " " + e.rawSpecPath + " " + e.getNotesSummary())
                    .toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(e);
        }
        specModel.setRows(out);
        specWrap.refreshEmptyState();
    }

    private void refreshTemplatesTable() {
        templatesModel.setRows(matcher.getCustomPlaceholderTemplates());
        templatesWrap.refreshEmptyState();
    }

    private void refreshExtraTable() {
        List<ApiCall> extras = new ArrayList<>();
        if (state != null) {
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                for (ApiCall c : g.getCalls()) {
                    if (!c.inExcel) {
                        extras.add(c);
                    }
                }
            }
        }
        extraModel.setRows(extras);
        extraWrap.refreshEmptyState();
    }

    public void dispose() {
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
        lblTemplatePreview.setForeground(Theme.token(Theme.Token.ACCENT).text());
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (lblTemplatePreview != null && tabs != null) {
            applyTheme();
        }
    }

    private static final class SpecTableModel extends AbstractTableModel {
        private static final String[] COLS = {"STT", "Trạng thái", "Method", "Spec Path", "Hits", "Params", "Ghi chú / JS Hints"};
        private List<ApiSpecEntry> rows = Collections.emptyList();

        void setRows(List<ApiSpecEntry> list) {
            this.rows = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        ApiSpecEntry getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            ApiSpecEntry e = rows.get(row);
            switch (col) {
                case 0: return e.stt;
                case 1: return e.isMatched() ? "MATCHED" : (!e.getJsHints().isEmpty() ? "JS_HINT" : "MISSING");
                case 2: return e.displayMethod;
                case 3: return e.rawSpecPath;
                case 4: return Integer.valueOf(e.getTotalHits());
                case 5: return e.getPrimaryCapture() != null ? Integer.valueOf(e.getPrimaryCapture().paramCount()) : Integer.valueOf(0);
                case 6: return !e.getNotesSummary().isEmpty() ? e.getNotesSummary()
                    : (!e.getJsHints().isEmpty() ? String.join(", ", e.getJsHints()) : e.extraInfo);
                default: return "";
            }
        }
    }

    private static final class SpecRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            if (!isSelected && table.getModel() instanceof SpecTableModel) {
                ApiSpecEntry en = ((SpecTableModel) table.getModel()).getAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                if (en != null && column == 1) {
                    Theme.Token tok = en.isMatched() ? Theme.Token.SUCCESS
                        : (!en.getJsHints().isEmpty() ? Theme.Token.INFO : Theme.Token.GHOST);
                    c.setForeground(Theme.token(tok).text());
                    c.setFont(FontManager.uiSemiBold());
                } else if (en != null && column == 2) {
                    c.setForeground(Theme.forMethod(en.displayMethod).text());
                    c.setFont(FontManager.monoBold());
                } else {
                    c.setForeground(Theme.text());
                }
            }
            return c;
        }
    }

    private static final class TemplatesTableModel extends AbstractTableModel {
        private static final String[] COLS = {"#", "Mẫu đường dẫn {placeholder}"};
        private List<String> rows = Collections.emptyList();

        void setRows(List<String> list) {
            this.rows = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Object getValueAt(int row, int col) {
            return col == 0 ? Integer.valueOf(row + 1) : rows.get(row);
        }
    }

    private static final class ExtraCallsModel extends AbstractTableModel {
        private static final String[] COLS = {"Nhóm", "Method", "Endpoint Path", "HTTP", "Params"};
        private List<ApiCall> rows = Collections.emptyList();

        void setRows(List<ApiCall> list) {
            this.rows = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        ApiCall getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
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
                case 4: return Integer.valueOf(c.capture.paramCount());
                default: return "";
            }
        }
    }

    private static final class ExtraRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            if (!isSelected && table.getModel() instanceof ExtraCallsModel) {
                ApiCall call = ((ExtraCallsModel) table.getModel()).getAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                if (call != null && column == 1) {
                    c.setForeground(Theme.forMethod(call.capture.method).text());
                    c.setFont(FontManager.monoBold());
                } else {
                    c.setForeground(Theme.text());
                }
            }
            return c;
        }
    }
}
