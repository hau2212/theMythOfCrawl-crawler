package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.AuditNote;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reusable Burp Proxy HTTP History style sticky filter bar with:
 * - Debounced (180ms) search supporting positive tokens, !negative exclusions (!css,!png), and optional Regex
 * - Quick-filter chips (4xx/5xx, 401/403, Mutating, Params, NOTE, Excel, Interesting)
 * - Method / HTTP Status / MIME dropdowns inside a collapsible 14"-friendly WrapLayout drawer
 * - Live active-filter summary strip & one-click Clear
 */
public final class BurpFilterBar extends JPanel {

    public enum QuickChip {
        ERRORS_4XX_5XX("4xx/5xx", "Chỉ hiện HTTP >= 400"),
        AUTH_401_403("401/403", "Chỉ hiện lỗi xác thực / phân quyền (401, 403)"),
        MUTATING("POST+", "Chỉ hiện Method thay đổi dữ liệu (POST, PUT, PATCH, DELETE)"),
        HAS_PARAMS("Params", "Chỉ hiện API có tham số"),
        HAS_NOTES("NOTE", "Chỉ hiện mục có Audit NOTE"),
        IN_EXCEL("Excel", "Chỉ hiện API khớp danh sách Excel"),
        INTERESTING("Đáng chú ý", "Chỉ hiện mục được đánh dấu màu hoặc trạng thái Đáng chú ý");

        public final String label;
        public final String tooltip;

        QuickChip(String label, String tooltip) {
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    public static final class FilterCriteria {
        public final String rawQuery;
        public final List<String> positiveTerms;
        public final List<String> negativeTerms;
        public final boolean regexMode;
        public final Pattern compiledRegex;
        public final String regexError;
        public final String methodFilter;
        public final String statusFilter;
        public final String mimeFilter;
        public final Set<QuickChip> chips;

        FilterCriteria(
            String rawQuery,
            boolean regexMode,
            String methodFilter,
            String statusFilter,
            String mimeFilter,
            Set<QuickChip> chips
        ) {
            this.rawQuery = rawQuery != null ? rawQuery.trim() : "";
            this.regexMode = regexMode;
            this.methodFilter = methodFilter != null ? methodFilter : "";
            this.statusFilter = statusFilter != null ? statusFilter : "";
            this.mimeFilter = mimeFilter != null ? mimeFilter : "";
            this.chips = chips != null && !chips.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.copyOf(chips))
                : Collections.emptySet();

            List<String> pos = new ArrayList<>();
            List<String> neg = new ArrayList<>();
            Pattern pat = null;
            String err = "";

            if (!this.rawQuery.isEmpty()) {
                if (regexMode) {
                    try {
                        pat = Pattern.compile(this.rawQuery, Pattern.CASE_INSENSITIVE);
                    } catch (PatternSyntaxException ex) {
                        err = ex.getDescription();
                    }
                } else {
                    for (String tok : this.rawQuery.split("[\\s,]+")) {
                        String t = tok.trim().toLowerCase(Locale.ROOT);
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (t.startsWith("!") && t.length() > 1) {
                            neg.add(t.substring(1));
                        } else if (!t.equals("!")) {
                            pos.add(t);
                        }
                    }
                }
            }
            this.positiveTerms = Collections.unmodifiableList(pos);
            this.negativeTerms = Collections.unmodifiableList(neg);
            this.compiledRegex = pat;
            this.regexError = err;
        }

        public boolean isAnyFilterActive() {
            return !rawQuery.isEmpty()
                || !methodFilter.isEmpty()
                || !statusFilter.isEmpty()
                || !mimeFilter.isEmpty()
                || !chips.isEmpty();
        }

        public String describeActiveFilters() {
            List<String> parts = new ArrayList<>();
            if (!methodFilter.isEmpty()) {
                parts.add(methodFilter);
            }
            if (!statusFilter.isEmpty()) {
                parts.add("HTTP " + statusFilter);
            }
            if (!mimeFilter.isEmpty()) {
                parts.add(mimeFilter);
            }
            for (QuickChip c : chips) {
                parts.add(c.label);
            }
            if (!rawQuery.isEmpty()) {
                parts.add(regexMode ? ("/" + rawQuery + "/") : ("\"" + rawQuery + "\""));
            }
            return String.join(" • ", parts);
        }

        public boolean matchesText(String... haystacks) {
            if (rawQuery.isEmpty()) {
                return true;
            }
            if (regexMode) {
                if (compiledRegex == null) {
                    return true;
                }
                if (haystacks != null) {
                    for (String h : haystacks) {
                        if (h != null && compiledRegex.matcher(h).find()) {
                            return true;
                        }
                    }
                }
                return false;
            }
            String joined = joinLower(haystacks);
            for (String neg : negativeTerms) {
                if (joined.contains(neg)) {
                    return false;
                }
            }
            for (String pos : positiveTerms) {
                if (!joined.contains(pos)) {
                    return false;
                }
            }
            return true;
        }

        public boolean matches(
            String method,
            int statusCode,
            String mimeOrContentType,
            int paramCount,
            boolean hasNotes,
            boolean inExcel,
            boolean interesting,
            String... searchableHaystacks
        ) {
            String m = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
            if (!methodFilter.isEmpty()) {
                if ("WS".equals(methodFilter)) {
                    if (!m.startsWith("WS")) {
                        return false;
                    }
                } else if (!m.equalsIgnoreCase(methodFilter)) {
                    return false;
                }
            }
            if (!statusFilter.isEmpty() && !matchesStatusBucket(statusFilter, statusCode)) {
                return false;
            }
            if (!mimeFilter.isEmpty() && !matchesMimeBucket(mimeFilter, mimeOrContentType)) {
                return false;
            }
            if (chips.contains(QuickChip.ERRORS_4XX_5XX) && statusCode < 400) {
                return false;
            }
            if (chips.contains(QuickChip.AUTH_401_403) && statusCode != 401 && statusCode != 403) {
                return false;
            }
            if (chips.contains(QuickChip.MUTATING)
                && !("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m))) {
                return false;
            }
            if (chips.contains(QuickChip.HAS_PARAMS) && paramCount <= 0) {
                return false;
            }
            if (chips.contains(QuickChip.HAS_NOTES) && !hasNotes) {
                return false;
            }
            if (chips.contains(QuickChip.IN_EXCEL) && !inExcel) {
                return false;
            }
            if (chips.contains(QuickChip.INTERESTING) && !interesting) {
                return false;
            }
            return matchesText(searchableHaystacks);
        }

        private static boolean matchesStatusBucket(String bucket, int statusCode) {
            switch (bucket) {
                case "2xx":
                    return statusCode >= 200 && statusCode < 300;
                case "3xx":
                    return statusCode >= 300 && statusCode < 400;
                case "4xx":
                    return statusCode >= 400 && statusCode < 500;
                case "5xx":
                    return statusCode >= 500 && statusCode < 600;
                case "4xx/5xx":
                    return statusCode >= 400;
                case "401/403":
                    return statusCode == 401 || statusCode == 403;
                default:
                    return true;
            }
        }

        private static boolean matchesMimeBucket(String bucket, String mime) {
            String low = mime != null ? mime.toLowerCase(Locale.ROOT) : "";
            switch (bucket) {
                case "JSON":
                    return low.contains("json") || low.contains("graphql");
                case "HTML":
                    return low.contains("html");
                case "Form":
                    return low.contains("form") || low.contains("multipart");
                case "XML":
                    return low.contains("xml");
                case "Khác":
                    return !low.contains("json") && !low.contains("html")
                        && !low.contains("form") && !low.contains("xml");
                default:
                    return true;
            }
        }

        private static String joinLower(String[] parts) {
            if (parts == null || parts.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p != null && !p.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(p.toLowerCase(Locale.ROOT));
                }
            }
            return sb.toString();
        }
    }

    private final MontoyaApi api;
    private final Set<QuickChip> enabledChips;
    private final boolean showDropdowns;
    private final boolean showMimeDropdown;

    private final JTextField txtSearch = new JTextField(16);
    private final JToggleButton btnRegex = new JToggleButton(".*");
    private final JToggleButton btnExpandDrawer = new JToggleButton("Lọc nhanh");
    private final JButton btnClear = new JButton("Xóa lọc");
    private final JLabel lblSummary = new JLabel("Hiển thị 0 / 0");

    private final JComboBox<String> cbMethod = new JComboBox<>(
        new String[]{"Method: Tất cả", "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "WS"});
    private final JComboBox<String> cbStatus = new JComboBox<>(
        new String[]{"HTTP: Tất cả", "2xx", "3xx", "4xx", "5xx", "4xx/5xx", "401/403"});
    private final JComboBox<String> cbMime = new JComboBox<>(
        new String[]{"MIME: Tất cả", "JSON", "HTML", "Form", "XML", "Khác"});

    private final Map<QuickChip, JToggleButton> chipButtons = new EnumMap<>(QuickChip.class);
    private final JPanel pnlDrawer = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel pnlRightControls = new JPanel(new WrapLayout(FlowLayout.RIGHT, 4, 0));
    private final JPanel pnlTop = new JPanel(new BorderLayout(8, 0));

    private final Timer debounceTimer;
    private final List<Consumer<FilterCriteria>> listeners = new CopyOnWriteArrayList<>();

    private boolean drawerExpanded = false;
    private boolean suppressingEvents = false;
    private int lastVisibleCount = 0;
    private int lastTotalCount = 0;
    private FilterCriteria currentCriteria = new FilterCriteria("", false, "", "", "", EnumSet.noneOf(QuickChip.class));

    public BurpFilterBar(MontoyaApi api) {
        this(api, EnumSet.allOf(QuickChip.class), true, true);
    }

    public BurpFilterBar(
        MontoyaApi api,
        Set<QuickChip> enabledChips,
        boolean showDropdowns,
        boolean showMimeDropdown
    ) {
        this.api = api;
        this.enabledChips = enabledChips != null && !enabledChips.isEmpty()
            ? EnumSet.copyOf(enabledChips)
            : EnumSet.noneOf(QuickChip.class);
        this.showDropdowns = showDropdowns;
        this.showMimeDropdown = showMimeDropdown;

        this.debounceTimer = new Timer(180, e -> commitFilterChange());
        this.debounceTimer.setRepeats(false);

        setLayout(new BorderLayout(2, 2));
        buildUi();
        applyTheme(api);
    }

    private void buildUi() {
        AuditTheme.styleSearchField(txtSearch, "Tìm URL, path, param... (!css,!png)");
        txtSearch.setPreferredSize(new Dimension(200, 24));
        txtSearch.setMinimumSize(new Dimension(110, 22));
        txtSearch.setToolTipText("Gõ nhiều từ khóa (AND) hoặc !từ_khóa để loại trừ (vd: /api/ !health !metrics)");

        lblSummary.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        lblSummary.setToolTipText("Bấm để mở/thu gọn bộ lọc nhanh (Method, HTTP, MIME, Quick Chips)");
        lblSummary.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!enabledChips.isEmpty() || showDropdowns) {
                    setDrawerExpanded(!drawerExpanded);
                }
            }
        });

        btnRegex.setToolTipText("Bật/tắt tìm kiếm theo Biểu thức chính quy (Regex)");
        btnExpandDrawer.setToolTipText("Mở/thu gọn bộ lọc nhanh (Method, HTTP Status, MIME, Quick Chips)");
        btnClear.setToolTipText("Xóa toàn bộ bộ lọc đang áp dụng (Esc)");
        btnClear.setVisible(false);

        pnlRightControls.setOpaque(false);
        pnlRightControls.add(txtSearch);
        pnlRightControls.add(btnRegex);
        if (!enabledChips.isEmpty() || showDropdowns) {
            pnlRightControls.add(btnExpandDrawer);
        }
        pnlRightControls.add(btnClear);

        pnlTop.setOpaque(false);
        pnlTop.add(lblSummary, BorderLayout.CENTER);
        pnlTop.add(pnlRightControls, BorderLayout.EAST);

        pnlDrawer.setOpaque(false);
        for (QuickChip qc : QuickChip.values()) {
            if (!enabledChips.contains(qc)) {
                continue;
            }
            JToggleButton btn = new JToggleButton(qc.label);
            btn.setToolTipText(qc.tooltip);
            btn.addActionListener(e -> {
                updateToggleStyles();
                commitFilterChange();
            });
            chipButtons.put(qc, btn);
            pnlDrawer.add(btn);
        }
        if (showDropdowns) {
            pnlDrawer.add(cbMethod);
            pnlDrawer.add(cbStatus);
            if (showMimeDropdown) {
                pnlDrawer.add(cbMime);
            }
        }
        pnlDrawer.setVisible(drawerExpanded);

        add(pnlTop, BorderLayout.NORTH);
        add(pnlDrawer, BorderLayout.SOUTH);

        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onTextEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onTextEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onTextEdited(); }
        });
        txtSearch.addActionListener(e -> {
            debounceTimer.stop();
            commitFilterChange();
        });
        btnRegex.addActionListener(e -> {
            updateToggleStyles();
            commitFilterChange();
        });
        btnExpandDrawer.addActionListener(e -> setDrawerExpanded(btnExpandDrawer.isSelected()));
        btnClear.addActionListener(e -> clearFilters());
        cbMethod.addActionListener(e -> {
            if (!suppressingEvents) {
                commitFilterChange();
            }
        });
        cbStatus.addActionListener(e -> {
            if (!suppressingEvents) {
                commitFilterChange();
            }
        });
        cbMime.addActionListener(e -> {
            if (!suppressingEvents) {
                commitFilterChange();
            }
        });
    }

    private void onTextEdited() {
        if (!suppressingEvents) {
            debounceTimer.restart();
        }
    }

    public void setDrawerExpanded(boolean expanded) {
        this.drawerExpanded = expanded;
        btnExpandDrawer.setSelected(expanded);
        pnlDrawer.setVisible(expanded);
        updateToggleStyles();
        revalidate();
        repaint();
    }

    public void addFilterChangeListener(Consumer<FilterCriteria> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public FilterCriteria getCriteria() {
        return currentCriteria;
    }

    public void focusSearch() {
        txtSearch.requestFocusInWindow();
        txtSearch.selectAll();
    }

    public void clearFilters() {
        suppressingEvents = true;
        debounceTimer.stop();
        try {
            txtSearch.setText("");
            btnRegex.setSelected(false);
            cbMethod.setSelectedIndex(0);
            cbStatus.setSelectedIndex(0);
            cbMime.setSelectedIndex(0);
            for (JToggleButton btn : chipButtons.values()) {
                btn.setSelected(false);
            }
        } finally {
            suppressingEvents = false;
        }
        updateToggleStyles();
        commitFilterChange();
    }

    public void setCounts(int visibleCount, int totalCount) {
        this.lastVisibleCount = visibleCount;
        this.lastTotalCount = totalCount;
        refreshSummaryChip();
    }

    private void commitFilterChange() {
        String method = "";
        if (cbMethod.getSelectedIndex() > 0 && cbMethod.getSelectedItem() != null) {
            method = cbMethod.getSelectedItem().toString();
        }
        String status = "";
        if (cbStatus.getSelectedIndex() > 0 && cbStatus.getSelectedItem() != null) {
            status = cbStatus.getSelectedItem().toString();
        }
        String mime = "";
        if (cbMime.getSelectedIndex() > 0 && cbMime.getSelectedItem() != null) {
            mime = cbMime.getSelectedItem().toString();
        }
        EnumSet<QuickChip> active = EnumSet.noneOf(QuickChip.class);
        for (Map.Entry<QuickChip, JToggleButton> en : chipButtons.entrySet()) {
            if (en.getValue().isSelected()) {
                active.add(en.getKey());
            }
        }
        this.currentCriteria = new FilterCriteria(
            txtSearch.getText(),
            btnRegex.isSelected(),
            method,
            status,
            mime,
            active
        );
        updateRegexFeedback();
        refreshSummaryChip();
        btnClear.setVisible(currentCriteria.isAnyFilterActive());
        btnClear.setEnabled(currentCriteria.isAnyFilterActive());
        for (Consumer<FilterCriteria> l : listeners) {
            l.accept(currentCriteria);
        }
    }

    private void updateRegexFeedback() {
        boolean dark = AuditTheme.isDark(api, this);
        if (currentCriteria.regexMode && !currentCriteria.regexError.isEmpty()) {
            Color err = AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark);
            txtSearch.setToolTipText("Regex không hợp lệ: " + currentCriteria.regexError);
            if (AuditTheme.isFlatLaf()) {
                txtSearch.putClientProperty("JComponent.outline", "error");
            } else {
                txtSearch.setBorder(BorderFactory.createLineBorder(err, 1));
            }
        } else {
            txtSearch.setToolTipText("Gõ nhiều từ khóa (AND) hoặc !từ_khóa để loại trừ (vd: /api/ !health !metrics)");
            if (AuditTheme.isFlatLaf()) {
                txtSearch.putClientProperty("JComponent.outline", null);
            } else {
                txtSearch.setBorder(null);
            }
        }
    }

    private void refreshSummaryChip() {
        boolean dark = AuditTheme.isDark(api, this);
        boolean active = currentCriteria.isAnyFilterActive();
        lblSummary.setOpaque(true);
        lblSummary.setBackground(active ? AuditTheme.pastelActiveBg(dark) : AuditTheme.tableSurface(dark));
        lblSummary.setIcon(BurpIcons.filter(active ? AuditTheme.pastelActiveFg(dark) : AuditTheme.mutedForeground(dark)));
        Color boxBorder = active ? AuditTheme.pastelActiveBorder(dark) : AuditTheme.borderColor(dark);
        lblSummary.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(boxBorder, 1, true),
            BorderFactory.createEmptyBorder(3, 8, 3, 10)));
        if (active) {
            String desc = currentCriteria.describeActiveFilters();
            if (desc.length() > 54) {
                desc = desc.substring(0, 51) + "...";
            }
            lblSummary.setText("Filter: " + desc + " (" + lastVisibleCount + "/" + lastTotalCount + ")");
            lblSummary.setForeground(AuditTheme.pastelActiveFg(dark));
        } else {
            lblSummary.setText("Filter: Showing all items (" + lastVisibleCount + "/" + lastTotalCount + ") | Click to filter...");
            lblSummary.setForeground(AuditTheme.mutedForeground(dark));
        }
    }

    private void updateToggleStyles() {
        boolean dark = AuditTheme.isDark(api, this);
        Color accent = AuditTheme.accentForeground(dark);

        btnRegex.setIcon(BurpIcons.regex(btnRegex.isSelected() ? accent : AuditTheme.mutedForeground(dark)));
        AuditTheme.styleTogglePill(btnRegex, btnRegex.isSelected(), accent, dark);

        btnExpandDrawer.setIcon(BurpIcons.filter(drawerExpanded ? accent : AuditTheme.textPrimary(dark)));
        AuditTheme.styleTogglePill(btnExpandDrawer, drawerExpanded, accent, dark);

        btnClear.setIcon(BurpIcons.clear(btnClear.isEnabled()
            ? AuditTheme.severityForeground(AuditNote.Severity.WARN, dark)
            : AuditTheme.mutedForeground(dark)));
        AuditTheme.styleNeutralActionButton(btnClear, dark);

        for (Map.Entry<QuickChip, JToggleButton> en : chipButtons.entrySet()) {
            QuickChip qc = en.getKey();
            JToggleButton btn = en.getValue();
            Color chipAccent = qc == QuickChip.ERRORS_4XX_5XX || qc == QuickChip.AUTH_401_403
                ? AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark)
                : (qc == QuickChip.HAS_NOTES
                    ? AuditTheme.severityForeground(AuditNote.Severity.WARN, dark)
                    : (qc == QuickChip.IN_EXCEL ? AuditTheme.okForeground(dark) : accent));
            AuditTheme.styleTogglePill(btn, btn.isSelected(), chipAccent, dark);
        }
    }

    public void applyTheme(MontoyaApi montoyaApi) {
        MontoyaApi effective = montoyaApi != null ? montoyaApi : this.api;
        boolean dark = AuditTheme.isDark(effective, this);
        AuditTheme.styleSlimToolbar(this, dark);
        Font disp = AuditTheme.displayFont(effective, this);
        lblSummary.setFont(disp);
        updateToggleStyles();
        refreshSummaryChip();
        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (txtSearch != null && lblSummary != null) {
            applyTheme(api);
        }
    }
}
