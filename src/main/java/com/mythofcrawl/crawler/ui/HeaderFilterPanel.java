package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.HeaderFilterProfile;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;

/**
 * UI tab for custom HTTP Header Filter & Modifier rules
 * (e.g. X-Account: a, Authorization tokens, Cookie overrides, header removal).
 */
public final class HeaderFilterPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final HeaderFilterProfile profile;
    private final Consumer<String> logger;
    private final Runnable onChanged;

    private final JCheckBox chkEnabled = new JCheckBox("BẬT Bộ lọc & Tùy chỉnh HTTP Header");
    private final JCheckBox chkProxy = new JCheckBox("Áp dụng khi Browser Crawl qua Proxy", true);
    private final JCheckBox chkExtender = new JCheckBox("Áp dụng khi Đẩy qua Extender (mặc định tắt để giữ nguyên gói tin)", false);

    private final JComboBox<String> cbAction = new JComboBox<>(new String[]{
        HeaderFilterProfile.Action.SET.label,
        HeaderFilterProfile.Action.ADD_IF_MISSING.label,
        HeaderFilterProfile.Action.REMOVE.label
    });
    private final JTextField tfName = new JTextField(16);
    private final JTextField tfValue = new JTextField(22);
    private final JTextField tfScope = new JTextField(16);

    private final RulesTableModel model = new RulesTableModel();
    private final JTable tblRules = new JTable(model);

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

        boolean dark = AuditTheme.isDark(api, this);
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(AuditTheme.canvasSurface(dark));

        add(buildTopControls(dark), BorderLayout.NORTH);
        add(buildTableSection(dark), BorderLayout.CENTER);
        syncFromConfig();
    }

    private JPanel buildTopControls(boolean dark) {
        JPanel wrap = new JPanel(new BorderLayout(6, 6));
        wrap.setOpaque(false);

        JPanel masterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        AuditTheme.styleActionCard(masterBar, dark);
        chkEnabled.setFont(chkEnabled.getFont().deriveFont(Font.BOLD));
        chkEnabled.setOpaque(false);
        chkProxy.setOpaque(false);
        chkExtender.setOpaque(false);

        chkEnabled.addActionListener(e -> commitFlags());
        chkProxy.addActionListener(e -> commitFlags());
        chkExtender.addActionListener(e -> commitFlags());

        JButton btnPresetAccount = new JButton("+ Mẫu X-Account: a");
        btnPresetAccount.addActionListener(e -> {
            cbAction.setSelectedIndex(0);
            tfName.setText("X-Account");
            tfValue.setText("a");
            tfScope.setText("*");
            tfValue.requestFocusInWindow();
        });
        JButton btnPresetAuth = new JButton("+ Mẫu Authorization");
        btnPresetAuth.addActionListener(e -> {
            cbAction.setSelectedIndex(0);
            tfName.setText("Authorization");
            tfValue.setText("Bearer TOKEN_HERE");
            tfScope.setText("*");
            tfValue.requestFocusInWindow();
        });

        masterBar.add(chkEnabled);
        masterBar.add(chkProxy);
        masterBar.add(chkExtender);
        masterBar.add(btnPresetAccount);
        masterBar.add(btnPresetAuth);

        JPanel editor = new JPanel(new GridBagLayout());
        AuditTheme.styleActionCard(editor, dark);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        tfName.setToolTipText("Ví dụ: X-Account, Authorization, Cookie, X-Role");
        tfValue.setToolTipText("Ví dụ: a (để trống nếu chọn Xóa Header)");
        tfScope.setToolTipText("Để trống hoặc * = mọi Host đích; hoặc nhập /api/ hay host cụ thể hay /regex/");

        JButton btnAdd = new JButton("+ Thêm Rule");
        btnAdd.setFont(btnAdd.getFont().deriveFont(Font.BOLD));
        btnAdd.addActionListener(e -> onAddRule());

        JButton btnUpdate = new JButton("Cập nhật dòng chọn");
        btnUpdate.addActionListener(e -> onUpdateSelected());

        JButton btnRemove = new JButton("Xóa dòng chọn");
        btnRemove.addActionListener(e -> onDeleteSelected());

        cbAction.addActionListener(e -> {
            boolean isRemove = cbAction.getSelectedIndex() == 2;
            tfValue.setEnabled(!isRemove);
            if (isRemove) {
                tfValue.setText("");
            }
        });

        int x = 0;
        gbc.gridx = x++; gbc.gridy = 0; gbc.weightx = 0; editor.add(new JLabel("Hành động:"), gbc);
        gbc.gridx = x++; gbc.weightx = 0; editor.add(cbAction, gbc);
        gbc.gridx = x++; gbc.weightx = 0; editor.add(new JLabel("Tên Header:"), gbc);
        gbc.gridx = x++; gbc.weightx = 0.25; editor.add(tfName, gbc);
        gbc.gridx = x++; gbc.weightx = 0; editor.add(new JLabel("Giá trị:"), gbc);
        gbc.gridx = x++; gbc.weightx = 0.45; editor.add(tfValue, gbc);
        gbc.gridx = x++; gbc.weightx = 0; editor.add(new JLabel("Lọc Host/Path:"), gbc);
        gbc.gridx = x++; gbc.weightx = 0.20; editor.add(tfScope, gbc);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        btns.setOpaque(false);
        btns.add(btnAdd);
        btns.add(btnUpdate);
        btns.add(btnRemove);
        btns.add(new JLabel("  (Mẹo: Chỉ áp dụng cho Host nằm trong ô Target Host; bỏ qua /__myth_crawl/* nội bộ)"));

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 8; gbc.weightx = 1;
        editor.add(btns, gbc);

        wrap.add(masterBar, BorderLayout.NORTH);
        wrap.add(editor, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel buildTableSection(boolean dark) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setOpaque(false);

        AuditTheme.configureAuditTable(tblRules, api);
        tblRules.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblRules.setDefaultRenderer(Object.class, new RuleCellRenderer(api));
        tblRules.getColumnModel().getColumn(0).setMaxWidth(64);
        tblRules.getColumnModel().getColumn(1).setPreferredWidth(140);
        tblRules.getColumnModel().getColumn(2).setPreferredWidth(180);
        tblRules.getColumnModel().getColumn(3).setPreferredWidth(320);
        tblRules.getColumnModel().getColumn(4).setPreferredWidth(200);

        tblRules.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblRules.getSelectedRow();
                if (vr >= 0) {
                    HeaderFilterProfile.Rule r = model.getRuleAt(tblRules.convertRowIndexToModel(vr));
                    if (r != null) {
                        cbAction.setSelectedIndex(r.action.ordinal());
                        tfName.setText(r.headerName);
                        tfValue.setText(r.headerValue);
                        tfScope.setText(r.scopePattern);
                    }
                }
            }
        });

        p.add(new JScrollPane(tblRules), BorderLayout.CENTER);
        return p;
    }

    public void syncFromConfig() {
        chkEnabled.setSelected(profile.isEnabled());
        chkProxy.setSelected(profile.isApplyOnProxyCrawl());
        chkExtender.setSelected(profile.isApplyOnExtenderPush());
        model.refresh();
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

    private HeaderFilterProfile.Rule readFormRule(boolean enabledFlag) {
        String name = HeaderFilterProfile.sanitizeHeaderName(tfName.getText());
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Tên Header (ví dụ: X-Account).",
                "Thiếu Tên Header", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        HeaderFilterProfile.Action act = HeaderFilterProfile.Action.values()[cbAction.getSelectedIndex()];
        String val = HeaderFilterProfile.sanitizeHeaderValue(tfValue.getText());
        String scope = tfScope.getText().trim();
        return new HeaderFilterProfile.Rule(enabledFlag, act, name, val, scope);
    }

    private void onAddRule() {
        HeaderFilterProfile.Rule rule = readFormRule(true);
        if (rule == null) {
            return;
        }
        profile.addRule(rule);
        if (!profile.isEnabled()) {
            profile.setEnabled(true);
            chkEnabled.setSelected(true);
        }
        model.refresh();
        if (logger != null) {
            logger.accept("Đã thêm Header rule: [" + rule.action.label + "] " + rule.headerName
                + (rule.action == HeaderFilterProfile.Action.REMOVE ? "" : (": " + rule.headerValue)));
        }
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void onUpdateSelected() {
        int vr = tblRules.getSelectedRow();
        if (vr < 0) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một dòng rule trong bảng để cập nhật.",
                "Chưa chọn dòng", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int mr = tblRules.convertRowIndexToModel(vr);
        HeaderFilterProfile.Rule existing = model.getRuleAt(mr);
        HeaderFilterProfile.Rule updated = readFormRule(existing == null || existing.enabled);
        if (updated == null) {
            return;
        }
        profile.replaceRule(mr, updated);
        model.refresh();
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
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private final class RulesTableModel extends AbstractTableModel {
        private final String[] COLS = {"Bật", "Hành động", "Tên Header", "Giá trị", "Phạm vi Host / Path"};
        private List<HeaderFilterProfile.Rule> rows = profile.getRulesSnapshot();

        void refresh() {
            this.rows = profile.getRulesSnapshot();
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
                case 3: return r.action == HeaderFilterProfile.Action.REMOVE ? "(bị xóa)" : r.headerValue;
                case 4: return r.scopePattern.isEmpty() ? "* (Mọi Host đích)" : r.scopePattern;
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
