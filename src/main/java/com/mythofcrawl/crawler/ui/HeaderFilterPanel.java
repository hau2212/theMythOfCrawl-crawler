package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.HeaderFilterProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Burp Suite v2026.10.1 style HTTP Header Filter & Modifier rules panel:
 * - Clean section header (? icon + bold title + 1-line muted description)
 * - Vertical left button stack (Add, Edit, Remove, Up, Down) matching Burp Match & Replace
 * - Clean presets menu (X-Account, Authorization) and double-click row editing
 */
public final class HeaderFilterPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final HeaderFilterProfile profile;
    private final Consumer<String> logger;
    private final Runnable onChanged;

    private final JLabel lblHelpIcon = new JLabel();
    private final JLabel lblSectionTitle = new JLabel("HTTP Header filter & modifier rules");
    private final JLabel lblSectionDesc = new JLabel(
        "Modify or filter HTTP headers automatically when crawling via Proxy (internal /__myth_crawl/* routes are skipped).");

    private final JCheckBox chkEnabled = new JCheckBox("Enable HTTP Header rules");
    private final JCheckBox chkProxy = new JCheckBox("Apply on Proxy Crawl", true);
    private final JCheckBox chkExtender = new JCheckBox("Apply on Extender Push", false);
    private final JButton btnPresets = new JButton("Presets");

    private final JButton btnAdd = new JButton("Add");
    private final JButton btnEdit = new JButton("Edit");
    private final JButton btnRemove = new JButton("Remove");
    private final JButton btnUp = new JButton("Up");
    private final JButton btnDown = new JButton("Down");

    private final RulesTableModel model;
    private final JTable tblRules;
    private final JScrollPane spRules;
    private final JPanel flagsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 2));
    private final JPanel leftButtonsCol = new JPanel();

    public HeaderFilterPanel(
        MontoyaApi api,
        CrawlConfig config,
        Consumer<String> logger,
        Runnable onChanged
    ) {
        this.api = api;
        this.config = config;
        this.profile = config.getHeaderFilterProfile();
        this.logger = logger;
        this.onChanged = onChanged;
        this.model = new RulesTableModel();
        this.tblRules = new JTable(this.model);
        this.spRules = new JScrollPane(this.tblRules);

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        add(buildHeaderSection(), BorderLayout.NORTH);
        add(buildBurpTableSection(), BorderLayout.CENTER);
        syncFromConfig();
        applyTheme();
    }

    private JPanel buildHeaderSection() {
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));

        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblHelpIcon.setToolTipText("Applies only to scoped target hosts; skips internal /__myth_crawl/* control calls.");
        titleLine.add(lblHelpIcon);
        titleLine.add(lblSectionTitle);

        lblSectionDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblSectionDesc.setBorder(BorderFactory.createEmptyBorder(3, 22, 6, 0));

        chkEnabled.setOpaque(false);
        chkProxy.setOpaque(false);
        chkExtender.setOpaque(false);
        chkEnabled.addActionListener(e -> commitFlags());
        chkProxy.addActionListener(e -> commitFlags());
        chkExtender.addActionListener(e -> commitFlags());

        btnPresets.addActionListener(e -> showPresetsMenu());

        flagsRow.setOpaque(false);
        flagsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        flagsRow.setBorder(BorderFactory.createEmptyBorder(2, 18, 0, 0));
        flagsRow.add(chkEnabled);
        flagsRow.add(chkProxy);
        flagsRow.add(chkExtender);
        flagsRow.add(btnPresets);

        wrap.add(titleLine);
        wrap.add(lblSectionDesc);
        wrap.add(flagsRow);
        return wrap;
    }

    private JPanel buildBurpTableSection() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);

        leftButtonsCol.setOpaque(false);
        leftButtonsCol.setLayout(new BoxLayout(leftButtonsCol, BoxLayout.Y_AXIS));
        JButton[] stack = {btnAdd, btnEdit, btnRemove, btnUp, btnDown};
        Dimension btnDim = new Dimension(84, 26);
        for (int i = 0; i < stack.length; i++) {
            JButton b = stack[i];
            b.setMinimumSize(btnDim);
            b.setPreferredSize(btnDim);
            b.setMaximumSize(btnDim);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            leftButtonsCol.add(b);
            if (i < stack.length - 1) {
                leftButtonsCol.add(Box.createVerticalStrut(6));
            }
        }
        leftButtonsCol.add(Box.createVerticalGlue());

        btnAdd.addActionListener(e -> onAddRuleDialog(null));
        btnEdit.addActionListener(e -> onEditSelected());
        btnRemove.addActionListener(e -> onDeleteSelected());
        btnUp.addActionListener(e -> onMoveSelected(-1));
        btnDown.addActionListener(e -> onMoveSelected(1));

        AuditTheme.configureAuditTable(tblRules, api);
        tblRules.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblRules.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        tblRules.setDefaultRenderer(Object.class, new RuleCellRenderer(api));
        tblRules.setDefaultRenderer(Boolean.class, new TableSelectionPreserver.BooleanCellRenderer(api));
        tblRules.getColumnModel().getColumn(0).setPreferredWidth(58);
        tblRules.getColumnModel().getColumn(0).setMaxWidth(68);
        tblRules.getColumnModel().getColumn(1).setPreferredWidth(135);
        tblRules.getColumnModel().getColumn(2).setPreferredWidth(165);
        tblRules.getColumnModel().getColumn(3).setPreferredWidth(250);
        tblRules.getColumnModel().getColumn(4).setPreferredWidth(175);

        tblRules.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblRules.getSelectedRow() >= 0) {
                    onEditSelected();
                }
            }
        });
        tblRules.getSelectionModel().addListSelectionListener(e -> updateButtonsEnabled());
        updateButtonsEnabled();

        p.add(leftButtonsCol, BorderLayout.WEST);
        p.add(spRules, BorderLayout.CENTER);
        return p;
    }

    private void showPresetsMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miAcc = new JMenuItem("Add X-Account: a (*)");
        JMenuItem miAuth = new JMenuItem("Add Authorization: Bearer TOKEN_HERE (*)");
        miAcc.addActionListener(e -> onAddRuleDialog(
            new HeaderFilterProfile.Rule(true, HeaderFilterProfile.Action.SET, "X-Account", "a", "*")));
        miAuth.addActionListener(e -> onAddRuleDialog(
            new HeaderFilterProfile.Rule(true, HeaderFilterProfile.Action.SET, "Authorization", "Bearer TOKEN_HERE", "*")));
        menu.add(miAcc);
        menu.add(miAuth);
        menu.show(btnPresets, 0, btnPresets.getHeight());
    }

    private void updateButtonsEnabled() {
        int vr = tblRules.getSelectedRow();
        int cnt = tblRules.getRowCount();
        btnEdit.setEnabled(vr >= 0);
        btnRemove.setEnabled(vr >= 0);
        btnUp.setEnabled(vr > 0);
        btnDown.setEnabled(vr >= 0 && vr < cnt - 1);
    }

    public void syncFromConfig() {
        chkEnabled.setSelected(profile.isEnabled());
        chkProxy.setSelected(profile.isApplyOnProxyCrawl());
        chkExtender.setSelected(profile.isApplyOnExtenderPush());
        model.refresh();
        updateButtonsEnabled();
    }

    private void commitFlags() {
        profile.setEnabled(chkEnabled.isSelected());
        profile.setApplyOnProxyCrawl(chkProxy.isSelected());
        profile.setApplyOnExtenderPush(chkExtender.isSelected());
        if (logger != null) {
            logger.accept("Bộ lọc Header HTTP: " + (profile.isEnabled() ? "ĐANG BẬT" : "ĐÃ TẮT")
                + " (" + profile.activeRuleCount() + " rule hoạt động).");
        }
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private HeaderFilterProfile.Rule promptRuleDialog(String title, HeaderFilterProfile.Rule seed) {
        JComboBox<String> cbAction = new JComboBox<>(new String[]{
            HeaderFilterProfile.Action.SET.label,
            HeaderFilterProfile.Action.ADD_IF_MISSING.label,
            HeaderFilterProfile.Action.REMOVE.label
        });
        JTextField tfName = new JTextField(seed != null ? seed.headerName : "", 22);
        JTextField tfValue = new JTextField(seed != null ? seed.headerValue : "", 22);
        JTextField tfScope = new JTextField(seed != null ? seed.scopePattern : "*", 22);
        if (seed != null) {
            cbAction.setSelectedIndex(seed.action.ordinal());
        }
        cbAction.addActionListener(e -> {
            boolean rem = cbAction.getSelectedIndex() == 2;
            tfValue.setEnabled(!rem);
            if (rem) {
                tfValue.setText("");
            }
        });
        tfValue.setEnabled(cbAction.getSelectedIndex() != 2);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; form.add(new JLabel("Action:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(cbAction, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; form.add(new JLabel("Header Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(tfName, gbc);
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0; form.add(new JLabel("Header Value:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(tfValue, gbc);
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0; form.add(new JLabel("Scope (Host/Path):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(tfScope, gbc);

        int res = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return null;
        }
        String name = HeaderFilterProfile.sanitizeHeaderName(tfName.getText());
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Tên Header (ví dụ: X-Account).",
                "Thiếu Tên Header", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        HeaderFilterProfile.Action act = HeaderFilterProfile.Action.values()[cbAction.getSelectedIndex()];
        String val = HeaderFilterProfile.sanitizeHeaderValue(tfValue.getText());
        String scope = tfScope.getText().trim();
        boolean en = seed == null || seed.enabled;
        return new HeaderFilterProfile.Rule(en, act, name, val, scope);
    }

    private void onAddRuleDialog(HeaderFilterProfile.Rule seed) {
        HeaderFilterProfile.Rule rule = promptRuleDialog("Add HTTP Header Rule", seed);
        if (rule == null) {
            return;
        }
        profile.addRule(rule);
        if (!profile.isEnabled()) {
            profile.setEnabled(true);
            chkEnabled.setSelected(true);
        }
        model.refresh();
        int last = model.getRowCount() - 1;
        if (last >= 0) {
            tblRules.setRowSelectionInterval(last, last);
        }
        updateButtonsEnabled();
        if (logger != null) {
            logger.accept("Đã thêm Header rule: [" + rule.action.label + "] " + rule.headerName
                + (rule.action == HeaderFilterProfile.Action.REMOVE ? "" : (": " + rule.headerValue)));
        }
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void onEditSelected() {
        int vr = tblRules.getSelectedRow();
        if (vr < 0) {
            return;
        }
        int mr = tblRules.convertRowIndexToModel(vr);
        HeaderFilterProfile.Rule existing = model.getRuleAt(mr);
        HeaderFilterProfile.Rule updated = promptRuleDialog("Edit HTTP Header Rule", existing);
        if (updated == null) {
            return;
        }
        profile.replaceRule(mr, updated);
        model.refresh();
        if (mr >= 0 && mr < tblRules.getRowCount()) {
            tblRules.setRowSelectionInterval(mr, mr);
        }
        updateButtonsEnabled();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void onDeleteSelected() {
        int vr = tblRules.getSelectedRow();
        if (vr < 0) {
            return;
        }
        int mr = tblRules.convertRowIndexToModel(vr);
        profile.removeRule(mr);
        model.refresh();
        int next = Math.min(mr, tblRules.getRowCount() - 1);
        if (next >= 0) {
            tblRules.setRowSelectionInterval(next, next);
        }
        updateButtonsEnabled();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void onMoveSelected(int delta) {
        int vr = tblRules.getSelectedRow();
        if (vr < 0) {
            return;
        }
        int mr = tblRules.convertRowIndexToModel(vr);
        List<HeaderFilterProfile.Rule> snap = profile.getRulesSnapshot();
        int target = mr + delta;
        if (target < 0 || target >= snap.size()) {
            return;
        }
        Collections.swap(snap, mr, target);
        profile.setRules(snap);
        model.refresh();
        tblRules.setRowSelectionInterval(target, target);
        updateButtonsEnabled();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.panelSurface(dark));

        Font base = AuditTheme.displayFont(api, this);
        lblHelpIcon.setIcon(BurpIcons.helpCircle(AuditTheme.mutedForeground(dark)));
        lblSectionTitle.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.0f));
        lblSectionTitle.setForeground(AuditTheme.textPrimary(dark));
        lblSectionDesc.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 0.5f)));
        lblSectionDesc.setForeground(AuditTheme.mutedForeground(dark));

        chkEnabled.setFont(base.deriveFont(Font.BOLD));
        btnPresets.setIcon(BurpIcons.chevronDown(AuditTheme.neutralButtonFg(dark)));
        btnPresets.setHorizontalTextPosition(SwingConstants.LEFT);
        AuditTheme.stylePlainButton(btnPresets, dark);
        AuditTheme.stylePlainButton(btnAdd, dark);
        AuditTheme.stylePlainButton(btnEdit, dark);
        AuditTheme.stylePlainButton(btnRemove, dark);
        AuditTheme.stylePlainButton(btnUp, dark);
        AuditTheme.stylePlainButton(btnDown, dark);

        AuditTheme.configureAuditTable(tblRules, api);
        AuditTheme.styleScrollPane(spRules, dark);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (tblRules != null && btnAdd != null) {
            applyTheme();
        }
    }

    private final class RulesTableModel extends AbstractTableModel {
        private final String[] COLS = {"Enabled", "Action", "Header Name", "Value", "Scope"};
        private List<HeaderFilterProfile.Rule> rows = new ArrayList<>();

        void refresh() {
            this.rows = profile != null ? profile.getRulesSnapshot() : new ArrayList<>();
            fireTableDataChanged();
        }

        HeaderFilterProfile.Rule getRuleAt(int idx) {
            return idx >= 0 && idx < rows.size() ? rows.get(idx) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 0;
        }

        @Override
        public void setValueAt(Object val, int row, int col) {
            if (col == 0 && val instanceof Boolean) {
                profile.toggleRuleEnabled(row, (Boolean) val);
                refresh();
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        }

        @Override
        public Object getValueAt(int row, int col) {
            HeaderFilterProfile.Rule r = rows.get(row);
            switch (col) {
                case 0: return r.enabled;
                case 1: return r.action.label;
                case 2: return r.headerName;
                case 3: return r.action == HeaderFilterProfile.Action.REMOVE ? "(removed)" : r.headerValue;
                case 4: return r.scopePattern.isEmpty() ? "* (All targets)" : r.scopePattern;
                default: return "";
            }
        }
    }

    private final class RuleCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi montoyaApi;

        RuleCellRenderer(MontoyaApi montoyaApi) {
            this.montoyaApi = montoyaApi;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(montoyaApi, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment(column == 1 ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected && table.getModel() instanceof RulesTableModel) {
                HeaderFilterProfile.Rule r = ((RulesTableModel) table.getModel()).getRuleAt(
                    table.convertRowIndexToModel(row));
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = AuditTheme.textPrimary(dark);
                if (r != null) {
                    if (!r.enabled || !profile.isEnabled()) {
                        fg = AuditTheme.mutedForeground(dark);
                    } else if (column == 1) {
                        if (r.action == HeaderFilterProfile.Action.REMOVE) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.ERROR, dark);
                        } else if (r.action == HeaderFilterProfile.Action.ADD_IF_MISSING) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.INFO, dark);
                        } else {
                            fg = AuditTheme.okForeground(dark);
                        }
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 2) {
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }
}
