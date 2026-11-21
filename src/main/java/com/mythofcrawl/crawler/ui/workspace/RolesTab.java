package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.core.auth.RoleProfile;
import com.mythofcrawl.crawler.core.auth.RoleStore;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.EventLogEntry;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Multi-Role Auth Profiles & Live JWT TTL Decoder Tab (LAW 1-4 & Law 7 compliant).
 * Features: ThemedTable with toggleable enabled column, Tier/TTL Pills, CRUD Role Editor,
 * live Base64URL JWT Decoder & TTL countdown, branch/endpoint scope, and Multi-Role Auth Audit trigger.
 */
public final class RolesTab extends JPanel {

    private final CrawlState state;
    private final RoleStore roleStore;
    private final UiHost host;
    private final ToastPane toast;
    private final Runnable onChanged;

    private InstantEventBus.Subscription storeSub;
    private AutoCloseable themeSub;
    private final Timer countdownTimer;

    private final JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
    private final SearchField searchField = new SearchField("Tìm tên Role, tier, nhánh...", 120);
    private final Pill pillSummary = new Pill("0 roles", Theme.Token.INFO);
    private final Ui.UiButton btnNewRole = Ui.solidPrimary("Thêm Role mới", VectorIcons.plus(Theme.inkWhite()));
    private final Ui.UiButton btnAuditRoles = Ui.tonalBtn(Theme.Token.ACCENT, "Kiểm tra Auth đa Role", VectorIcons.shieldCheck(Theme.token(Theme.Token.ACCENT).text()));

    private final RolesTableModel tableModel = new RolesTableModel();
    private final ThemedTable tblRoles = new ThemedTable(tableModel);
    private final ThemedTable.EmptyWrap rolesWrap;

    // Right Editor + JWT Decoder
    private final JPanel editorCard = new JPanel(new BorderLayout(0, Theme.SPACE_2));
    private final JLabel lblEditorTitle = new JLabel("Biên tập Role / Phiên đăng nhập");
    private final ModernToggleSwitch swEnabled = new ModernToggleSwitch("Kích hoạt", true, Theme.Token.SUCCESS);
    private final JTextField txtName = new JTextField(18);
    private final JComboBox<String> cbTier = new JComboBox<>(new String[]{"ADMIN", "STAFF", "USER", "GUEST", "CUSTOM"});
    private final JTextArea txtHeaders = new JTextArea(4, 24);
    private final JTextArea txtCookies = new JTextArea(3, 24);
    private final JTextField txtBranches = new JTextField(20);
    private final JTextField txtEndpoints = new JTextField(20);
    private final JTextField txtNotes = new JTextField(20);

    private final JPanel jwtCard = new JPanel(new BorderLayout(Theme.SPACE_1, Theme.SPACE_1));
    private final Pill jwtStatusPill = new Pill("Không phát hiện JWT", Theme.Token.NEUTRAL);
    private final Pill jwtTtlPill = new Pill("TTL: --", Theme.Token.NEUTRAL);
    private final WrappedText txtJwtDecoded = new WrappedText("Dán 'Authorization: Bearer <jwt>' vào ô Headers để giải mã payload & đếm ngược TTL tự động.", true, FontManager.monoRegular());

    private final Pill lblSaveBanner = new Pill("Sẵn sàng", Theme.Token.NEUTRAL);
    private final Ui.UiButton btnSaveRole = Ui.solidPrimary("Lưu Role (Ctrl+S)", VectorIcons.check(Theme.inkWhite()));
    private final Ui.UiButton btnDeleteRole = Ui.dangerBtn("Xóa", VectorIcons.trash(Theme.inkWhite()));

    private String editingRoleId = "";
    private final boolean[] restoringSel = {false};

    public RolesTab(
        CrawlState state,
        RoleStore roleStore,
        UiHost host,
        ToastPane toast,
        Runnable onChanged
    ) {
        this.state = state;
        this.roleStore = roleStore != null ? roleStore : new RoleStore();
        this.host = host;
        this.toast = toast;
        this.onChanged = onChanged;

        this.rolesWrap = ThemedTable.wrapWithEmptyState(
            tblRoles, "Chưa cấu hình Role nào", "Bấm 'Thêm Role mới' để khai báo Header/Cookie/JWT cho ADMIN, USER, GUEST...");

        setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        buildTopBar();
        buildTablePane();
        buildEditorPane();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rolesWrap, editorCard);
        split.setResizeWeight(0.52);
        split.setOneTouchExpandable(false);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(topBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        installCtrlSShortcut();
        this.storeSub = this.roleStore.subscribe(() -> SwingUtilities.invokeLater(this::refreshData));
        this.themeSub = Theme.onThemeChange(this::applyTheme);
        this.countdownTimer = new Timer(1000, e -> {
            if (isShowing()) {
                updateJwtPreview();
                tblRoles.repaint();
            }
        });
        this.countdownTimer.start();

        applyTheme();
        startNewRole();
        refreshData();
    }

    private void buildTopBar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        left.setOpaque(false);
        searchField.onSearchChange(q -> refreshData());
        left.add(searchField);
        left.add(pillSummary);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_2, 2));
        right.setOpaque(false);
        btnNewRole.addActionListener(e -> startNewRole());
        btnAuditRoles.addActionListener(e -> runMultiRoleAudit());
        right.add(btnNewRole);
        right.add(btnAuditRoles);

        topBar.setOpaque(false);
        topBar.add(left, BorderLayout.CENTER);
        topBar.add(right, BorderLayout.EAST);
    }

    private void buildTablePane() {
        tblRoles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblRoles.setDefaultRenderer(Object.class, new RolesRenderer());
        tblRoles.getColumnModel().getColumn(0).setPreferredWidth(46);
        tblRoles.getColumnModel().getColumn(1).setPreferredWidth(76);
        tblRoles.getColumnModel().getColumn(2).setPreferredWidth(145);
        tblRoles.getColumnModel().getColumn(3).setPreferredWidth(120);
        tblRoles.getColumnModel().getColumn(4).setPreferredWidth(115);
        tblRoles.getColumnModel().getColumn(5).setPreferredWidth(140);

        tblRoles.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !restoringSel[0]) {
                RoleProfile rp = getSelectedRole();
                if (rp != null) {
                    loadRoleIntoEditor(rp);
                }
            }
        });
        tblRoles.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = tblRoles.rowAtPoint(e.getPoint());
                int c = tblRoles.columnAtPoint(e.getPoint());
                if (r >= 0 && c == 0) {
                    RoleProfile rp = tableModel.getAt(tblRoles.convertRowIndexToModel(r));
                    if (rp != null) {
                        roleStore.setEnabled(rp.id, !rp.enabled);
                        notifyMutated();
                    }
                }
            }
        });
    }

    private void buildEditorPane() {
        editorCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel head = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        head.setOpaque(false);
        lblEditorTitle.setFont(FontManager.uiTitle());
        head.add(lblEditorTitle, BorderLayout.WEST);
        head.add(swEnabled, BorderLayout.EAST);

        txtHeaders.setFont(FontManager.monoRegular());
        txtHeaders.setLineWrap(true);
        txtHeaders.setWrapStyleWord(true);
        txtCookies.setFont(FontManager.monoRegular());
        txtCookies.setLineWrap(true);
        txtCookies.setWrapStyleWord(true);

        java.awt.Dimension compactDim = new java.awt.Dimension(110, 28);
        txtName.setPreferredSize(compactDim);
        cbTier.setPreferredSize(new java.awt.Dimension(96, 28));
        txtBranches.setPreferredSize(compactDim);
        txtEndpoints.setPreferredSize(compactDim);
        txtNotes.setPreferredSize(compactDim);

        DocumentListener jwtWatcher = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateJwtPreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updateJwtPreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updateJwtPreview(); }
        };
        txtHeaders.getDocument().addDocumentListener(jwtWatcher);
        txtCookies.getDocument().addDocumentListener(jwtWatcher);

        ThemeSyncHelper.ViewportWidthFormPanel form = new ThemeSyncHelper.ViewportWidthFormPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.anchor = java.awt.GridBagConstraints.NORTHWEST;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 1; gc.weightx = 0.65;
        gc.insets = new java.awt.Insets(0, 0, Theme.SPACE_2, Theme.SPACE_2);
        form.add(labeled("Tên Role", txtName), gc);
        gc.gridx = 1; gc.weightx = 0.35;
        gc.insets = new java.awt.Insets(0, 0, Theme.SPACE_2, 0);
        form.add(labeled("Cấp (Tier)", cbTier), gc);

        JScrollPane spHdr = new JScrollPane(txtHeaders);
        spHdr.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        spHdr.setPreferredSize(new java.awt.Dimension(160, 76));
        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 2; gc.weightx = 1.0;
        form.add(labeled("HTTP Headers (mỗi dòng: Header-Name: value)", spHdr), gc);

        JScrollPane spCk = new JScrollPane(txtCookies);
        spCk.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        spCk.setPreferredSize(new java.awt.Dimension(160, 62));
        gc.gridy = 2;
        form.add(labeled("Cookies (mỗi dòng: cookieName=cookieValue)", spCk), gc);

        gc.gridy = 3;
        form.add(labeled("Giới hạn Nhánh UI (phân cách bằng dấu phẩy, trống = tất cả)", txtBranches), gc);

        gc.gridy = 4;
        form.add(labeled("Giới hạn Endpoint Pattern (phân cách bằng dấu phẩy)", txtEndpoints), gc);

        gc.gridy = 5;
        form.add(labeled("Ghi chú", txtNotes), gc);

        // Live JWT Card
        JPanel jwtPills = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 2));
        jwtPills.setOpaque(false);
        jwtPills.add(jwtStatusPill);
        jwtPills.add(jwtTtlPill);
        jwtCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        jwtCard.add(jwtPills, BorderLayout.NORTH);
        jwtCard.add(txtJwtDecoded, BorderLayout.CENTER);
        gc.gridy = 6;
        form.add(jwtCard, gc);

        gc.gridy = 7; gc.weighty = 1.0; gc.fill = java.awt.GridBagConstraints.BOTH;
        JPanel glue = new JPanel();
        glue.setOpaque(false);
        form.add(glue, gc);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        JPanel footer = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        footer.setOpaque(false);
        JPanel footerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footerLeft.setOpaque(false);
        footerLeft.add(lblSaveBanner);

        JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_2, 0));
        footerRight.setOpaque(false);
        btnDeleteRole.addActionListener(e -> deleteCurrentRole());
        btnSaveRole.addActionListener(e -> saveCurrentRole());
        footerRight.add(btnDeleteRole);
        footerRight.add(btnSaveRole);

        footer.add(footerLeft, BorderLayout.CENTER);
        footer.add(footerRight, BorderLayout.EAST);

        editorCard.add(head, BorderLayout.NORTH);
        editorCard.add(scroll, BorderLayout.CENTER);
        editorCard.add(footer, BorderLayout.SOUTH);
    }

    private static JPanel labeled(String title, Component comp) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lbl = new JLabel(title);
        lbl.setFont(FontManager.uiSmall().deriveFont(java.awt.Font.BOLD));
        lbl.setForeground(Theme.textSecondary());
        p.add(lbl, BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    private void startNewRole() {
        editingRoleId = "";
        lblEditorTitle.setText("Thêm Role / Profile mới");
        swEnabled.setSelected(true);
        txtName.setText("");
        cbTier.setSelectedItem("USER");
        txtHeaders.setText("");
        txtCookies.setText("");
        txtBranches.setText("");
        txtEndpoints.setText("");
        txtNotes.setText("");
        btnDeleteRole.setEnabled(false);
        lblSaveBanner.setPill("Đang soạn Role mới", Theme.Token.NEUTRAL, false);
        updateJwtPreview();
    }

    private void loadRoleIntoEditor(RoleProfile rp) {
        editingRoleId = rp.id;
        lblEditorTitle.setText("Sửa Role [" + rp.id + "]");
        swEnabled.setSelected(rp.enabled);
        txtName.setText(rp.name);
        cbTier.setSelectedItem(rp.tier);

        StringBuilder hb = new StringBuilder();
        rp.headers.forEach((k, v) -> hb.append(k).append(": ").append(v).append('\n'));
        txtHeaders.setText(hb.toString().trim());

        StringBuilder cb = new StringBuilder();
        rp.cookies.forEach((k, v) -> cb.append(k).append('=').append(v).append('\n'));
        txtCookies.setText(cb.toString().trim());

        txtBranches.setText(String.join(", ", rp.branchPrefixes));
        txtEndpoints.setText(String.join(", ", rp.endpointPatterns));
        txtNotes.setText(rp.notes);
        btnDeleteRole.setEnabled(true);
        lblSaveBanner.setPill("Đang sửa " + rp.name, Theme.Token.INFO, false);
        updateJwtPreview();
    }

    private RoleProfile buildProfileFromEditor() {
        String tier = String.valueOf(cbTier.getSelectedItem());
        String name = txtName.getText().trim();
        if (name.isEmpty()) {
            name = tier + "-Profile";
            txtName.setText(name);
        }
        String id = editingRoleId.isEmpty() ? UUID.randomUUID().toString().substring(0, 8) : editingRoleId;
        RoleProfile rp = new RoleProfile(id, name, swEnabled.isSelected(), tier);

        for (String line : txtHeaders.getText().split("\\R")) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (colon > 0) {
                rp.withHeader(t.substring(0, colon).trim(), t.substring(colon + 1).trim());
            }
        }
        for (String line : txtCookies.getText().split("\\R")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq > 0) {
                rp.withCookie(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
        for (String bp : txtBranches.getText().split(",")) {
            if (!bp.trim().isEmpty()) {
                rp.withBranchPrefix(bp.trim());
            }
        }
        for (String ep : txtEndpoints.getText().split(",")) {
            if (!ep.trim().isEmpty()) {
                rp.withEndpointPattern(ep.trim());
            }
        }
        rp.notes = txtNotes.getText().trim();
        return rp;
    }

    private void saveCurrentRole() {
        RoleProfile rp = buildProfileFromEditor();
        roleStore.upsertRole(rp);
        editingRoleId = rp.id;
        btnDeleteRole.setEnabled(true);
        lblSaveBanner.setPill("Đã lưu: " + rp.name, Theme.Token.SUCCESS, true);
        if (toast != null) {
            toast.show(Theme.Token.SUCCESS, "Đã lưu Role", rp.name + " (" + rp.tier + ")");
        }
        notifyMutated();
    }

    private void deleteCurrentRole() {
        if (!editingRoleId.isEmpty() && roleStore.deleteRole(editingRoleId)) {
            if (toast != null) {
                toast.show(Theme.Token.WARNING, "Đã xóa Role", txtName.getText());
            }
            startNewRole();
            notifyMutated();
        }
    }

    private void updateJwtPreview() {
        RoleProfile probe = buildProfileFromEditorWithoutMutatingName();
        RoleProfile.JwtInfo info = probe.getJwtInfo();
        if (!info.validFormat) {
            jwtStatusPill.setPill("Không có JWT", Theme.Token.NEUTRAL, false);
            jwtTtlPill.setPill("TTL: --", Theme.Token.NEUTRAL, false);
            txtJwtDecoded.setText("Dán 'Authorization: Bearer <jwt>' vào ô Headers để giải mã payload & đếm ngược TTL.");
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        long rem = info.remainingSeconds(nowSec);
        if (info.expiresAtEpochSec <= 0L) {
            jwtStatusPill.setPill("JWT hợp lệ (không có exp)", Theme.Token.INFO, false);
            jwtTtlPill.setPill("No exp", Theme.Token.NEUTRAL, false);
        } else if (rem <= 0L) {
            jwtStatusPill.setPill("JWT ĐÃ HẾT HẠN", Theme.Token.CRITICAL, true);
            jwtTtlPill.setPill("Hết hạn " + formatDuration(-rem) + " trước", Theme.Token.CRITICAL, false);
        } else if (rem < 300L) {
            jwtStatusPill.setPill("JWT sắp hết hạn", Theme.Token.WARNING, true);
            jwtTtlPill.setPill("Còn " + formatDuration(rem), Theme.Token.WARNING, false);
        } else {
            jwtStatusPill.setPill("JWT hợp lệ", Theme.Token.SUCCESS, false);
            jwtTtlPill.setPill("Còn " + formatDuration(rem), Theme.Token.SUCCESS, false);
        }
        txtJwtDecoded.setText(
            "sub=" + (info.subject.isEmpty() ? "(none)" : info.subject)
                + " | role=" + (info.roleClaim.isEmpty() ? "(none)" : info.roleClaim)
                + (info.issuer.isEmpty() ? "" : (" | iss=" + info.issuer))
                + "\nPayload: " + info.payloadJson);
    }

    private RoleProfile buildProfileFromEditorWithoutMutatingName() {
        RoleProfile rp = new RoleProfile("tmp", "tmp", true, "USER");
        for (String line : txtHeaders.getText().split("\\R")) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (colon > 0) {
                rp.withHeader(t.substring(0, colon).trim(), t.substring(colon + 1).trim());
            }
        }
        for (String line : txtCookies.getText().split("\\R")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq > 0) {
                rp.withCookie(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
        return rp;
    }

    private void runMultiRoleAudit() {
        List<RoleProfile> active = new ArrayList<>();
        int expired = 0;
        long nowSec = System.currentTimeMillis() / 1000L;
        for (RoleProfile r : roleStore.snapshotRoles()) {
            if (r.enabled) {
                active.add(r);
                if (r.getJwtInfo().isExpired(nowSec)) {
                    expired++;
                }
            }
        }
        int groups = state != null ? state.getActionGroups().groupCount() : 0;
        String branchSample = state != null ? state.getCurrentActionPath() : "/";
        int matchedOnBranch = roleStore.matchRolesFor(branchSample, "/").size();

        String msg = "Role đang bật: " + active.size()
            + " | Khớp nhánh hiện tại: " + matchedOnBranch
            + " | JWT hết hạn: " + expired
            + " | Action Groups sẵn sàng đối chiếu: " + groups;
        if (state != null) {
            state.addEventLog(
                expired > 0 ? EventLogEntry.Level.ERROR : EventLogEntry.Level.INFO,
                "Roles", msg);
        }
        if (toast != null) {
            toast.show(
                active.isEmpty() || expired > 0 ? Theme.Token.WARNING : Theme.Token.SUCCESS,
                "Kiểm tra Auth đa Role", msg);
        }
        if (host != null) {
            host.logInfo("[Multi-Role Auth Audit] " + msg);
        }
    }

    private static String formatDuration(long sec) {
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        long remSec = sec % 60;
        if (min < 60) return min + "m " + remSec + "s";
        long hrs = min / 60;
        long remMin = min % 60;
        if (hrs < 24) return hrs + "h " + remMin + "m";
        long days = hrs / 24;
        return days + "d " + (hrs % 24) + "h";
    }

    private RoleProfile getSelectedRole() {
        int vr = tblRoles.getSelectedRow();
        if (vr < 0 || vr >= tblRoles.getRowCount()) {
            return null;
        }
        return tableModel.getAt(tblRoles.convertRowIndexToModel(vr));
    }

    public void refreshData() {
        restoringSel[0] = true;
        RoleProfile sel = getSelectedRole();
        String selId = sel != null ? sel.id : null;
        try {
            String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
            List<RoleProfile> out = new ArrayList<>();
            int enabledCount = 0;
            for (RoleProfile r : roleStore.snapshotRoles()) {
                if (r.enabled) enabledCount++;
                if (!q.isEmpty()) {
                    String hay = (r.name + " " + r.tier + " " + String.join(" ", r.branchPrefixes)).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                out.add(r);
            }
            tableModel.setRows(out);
            pillSummary.setPill(enabledCount + "/" + out.size() + " đang bật", Theme.Token.INFO, false);
            rolesWrap.refreshEmptyState();

            if (selId != null) {
                for (int i = 0; i < out.size(); i++) {
                    if (selId.equals(out.get(i).id)) {
                        int vr = tblRoles.convertRowIndexToView(i);
                        if (vr >= 0) tblRoles.setRowSelectionInterval(vr, vr);
                        break;
                    }
                }
            }
        } finally {
            restoringSel[0] = false;
        }
    }

    public void dispose() {
        countdownTimer.stop();
        if (storeSub != null) {
            storeSub.close();
            storeSub = null;
        }
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
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
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "saveRole");
        getActionMap().put("saveRole", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveCurrentRole();
            }
        });
    }

    private void applyTheme() {
        setBackground(Theme.canvas());
        ThemeSyncHelper.applyTree(this);
        editorCard.setBackground(Theme.surface());
        editorCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.border(), 1, true),
            BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3)));
        jwtCard.setBackground(Theme.surfaceAlt());
        jwtCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.borderSubtle(), 1, true),
            BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2)));
        lblEditorTitle.setForeground(Theme.text());
        txtHeaders.setBackground(Theme.surfaceAlt());
        txtHeaders.setForeground(Theme.text());
        txtCookies.setBackground(Theme.surfaceAlt());
        txtCookies.setForeground(Theme.text());
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (editorCard != null && jwtCard != null) {
            applyTheme();
        }
    }

    private void notifyMutated() {
        refreshData();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private static final class RolesTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Bật", "Tier", "Tên Role", "Headers / Cookies", "JWT TTL", "Phạm vi"};
        private List<RoleProfile> rows = Collections.emptyList();

        void setRows(List<RoleProfile> list) {
            this.rows = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        RoleProfile getAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            RoleProfile r = rows.get(row);
            switch (col) {
                case 0: return r.enabled ? "ON" : "OFF";
                case 1: return r.tier;
                case 2: return r.name;
                case 3: return r.headers.size() + " hdr, " + r.cookies.size() + " ck";
                case 4: {
                    RoleProfile.JwtInfo ji = r.getJwtInfo();
                    if (!ji.validFormat) return "-";
                    if (ji.expiresAtEpochSec <= 0L) return "No exp";
                    long rem = ji.remainingSeconds(System.currentTimeMillis() / 1000L);
                    return rem <= 0L ? "HẾT HẠN" : formatDuration(rem);
                }
                case 5: {
                    if (r.branchPrefixes.isEmpty() && r.endpointPatterns.isEmpty()) return "Toàn bộ";
                    return String.join(", ", r.branchPrefixes);
                }
                default: return "";
            }
        }
    }

    private static final class RolesRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            if (!isSelected && table.getModel() instanceof RolesTableModel) {
                RoleProfile r = ((RolesTableModel) table.getModel()).getAt(table.convertRowIndexToModel(row));
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                if (r != null && column == 0) {
                    c.setForeground(r.enabled ? Theme.token(Theme.Token.SUCCESS).text() : Theme.muted());
                    c.setFont(FontManager.uiSemiBold());
                } else if (r != null && column == 4 && "HẾT HẠN".equals(value)) {
                    c.setForeground(Theme.token(Theme.Token.CRITICAL).text());
                    c.setFont(FontManager.uiSemiBold());
                } else {
                    c.setForeground(Theme.text());
                }
            }
            return c;
        }
    }
}
