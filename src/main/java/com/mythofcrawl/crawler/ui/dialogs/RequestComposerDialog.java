package com.mythofcrawl.crawler.ui.dialogs;

import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.core.auth.RoleProfile;
import com.mythofcrawl.crawler.core.auth.RoleStore;
import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.ui.kit.FloatingModelessDialog;
import com.mythofcrawl.crawler.ui.kit.ThemedTable;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating modeless Multi-Role Request Composer & Auth Differential Replay dialog
 * powered by {@link RoleStore} and {@link UiHost}.
 */
public final class RequestComposerDialog extends FloatingModelessDialog {

    private final RoleStore roleStore;
    private final UiHost uiHost;
    private final ToastPane toast;

    private final JTextField tfHost = new JTextField(20);
    private final JSpinner spPort = new JSpinner(new SpinnerNumberModel(443, 1, 65535, 1));
    private final JCheckBox chkHttps = new JCheckBox("HTTPS", true);
    private final JTextArea txtRawRequest = new JTextArea();
    private final JTextArea txtRespPreview = new JTextArea();
    private final JScrollPane spReq = new JScrollPane(txtRawRequest);
    private final JScrollPane spResp = new JScrollPane(txtRespPreview);

    private final RoleMatrixModel matrixModel = new RoleMatrixModel();
    private final ThemedTable tblMatrix = new ThemedTable(matrixModel);
    private final ThemedTable.EmptyWrap matrixWrap;

    private final Ui.UiButton btnRunMultiRole;
    private final Ui.UiButton btnSendRepeater;

    private InstantEventBus.Subscription roleSub;

    public RequestComposerDialog(
        Window owner,
        RoleStore roleStore,
        UiHost uiHost,
        ToastPane toast,
        ApiCall initialCall
    ) {
        super(owner, "request_composer_dialog", "Request Composer & Replay Đa Role", 900, 620);
        this.roleStore = roleStore;
        this.uiHost = uiHost;
        this.toast = toast;

        this.btnRunMultiRole = Ui.solidPrimary("Chạy Replay các Role", VectorIcons.play(Theme.inkWhite()));
        this.btnSendRepeater = Ui.secondaryBtn("Repeater", VectorIcons.repeater(Theme.text()));
        this.matrixWrap = ThemedTable.wrapWithEmptyState(
            tblMatrix,
            "Chưa chạy kiểm thử đa Role",
            "Thêm Role ở tab Roles & Auth rồi bấm Chạy Replay các Role để so sánh phản hồi."
        );

        JPanel content = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel top = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        top.setOpaque(false);
        JPanel targetFields = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 0));
        targetFields.setOpaque(false);
        targetFields.add(new JLabel("Host:"));
        targetFields.add(tfHost);
        targetFields.add(new JLabel("Port:"));
        targetFields.add(spPort);
        chkHttps.setOpaque(false);
        targetFields.add(chkHttps);

        JPanel topBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        topBtns.setOpaque(false);
        topBtns.add(btnSendRepeater);
        topBtns.add(btnRunMultiRole);

        top.add(targetFields, BorderLayout.CENTER);
        top.add(topBtns, BorderLayout.EAST);

        txtRawRequest.setFont(FontManager.monoRegular());
        txtRawRequest.setBackground(Theme.surface());
        txtRawRequest.setForeground(Theme.text());
        txtRawRequest.setCaretColor(Theme.text());

        txtRespPreview.setEditable(false);
        txtRespPreview.setFont(FontManager.monoRegular());
        txtRespPreview.setBackground(Theme.surface());
        txtRespPreview.setForeground(Theme.text());

        spReq.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spResp.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));

        tblMatrix.setDefaultRenderer(Object.class, new MatrixCellRenderer());
        tblMatrix.setDefaultRenderer(Integer.class, new MatrixCellRenderer());
        tblMatrix.setDefaultRenderer(Long.class, new MatrixCellRenderer());

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, matrixWrap, spResp);
        rightSplit.setResizeWeight(0.5);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spReq, rightSplit);
        mainSplit.setResizeWeight(0.5);
        mainSplit.setPreferredSize(new Dimension(840, 480));

        content.add(top, BorderLayout.NORTH);
        content.add(mainSplit, BorderLayout.CENTER);
        setBodyComponent(content);

        loadApiCall(initialCall);
        wireEvents();
    }

    public void loadApiCall(ApiCall call) {
        if (call != null && call.capture != null) {
            CapturedApiEntry cap = call.capture;
            tfHost.setText(cap.host);
            spPort.setValue(cap.port > 0 ? cap.port : (cap.https ? 443 : 80));
            chkHttps.setSelected(cap.https);
            txtRawRequest.setText(HttpEncodingGuard.fromIso88591Bytes(cap.requestBytes));
            txtRawRequest.setCaretPosition(0);
            txtRespPreview.setText(HttpEncodingGuard.fromIso88591Bytes(cap.responseBytes));
            txtRespPreview.setCaretPosition(0);
        }
    }

    private void wireEvents() {
        btnSendRepeater.addActionListener(e -> {
            if (uiHost == null) {
                return;
            }
            String host = tfHost.getText().trim();
            if (host.isEmpty()) {
                return;
            }
            int port = ((Number) spPort.getValue()).intValue();
            boolean https = chkHttps.isSelected();
            byte[] raw = HttpEncodingGuard.toIso88591Bytes(txtRawRequest.getText());
            uiHost.sendToRepeater(raw, host, port, https, "Composer");
        });

        btnRunMultiRole.addActionListener(e -> executeMultiRoleReplay());

        tblMatrix.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int vr = tblMatrix.getSelectedRow();
                if (vr >= 0) {
                    RoleRunResult r = matrixModel.getAt(tblMatrix.convertRowIndexToModel(vr));
                    if (r != null) {
                        txtRespPreview.setText(r.responseText);
                        txtRespPreview.setCaretPosition(0);
                    }
                }
            }
        });

        if (roleStore != null) {
            roleSub = roleStore.subscribe(() -> SwingUtilities.invokeLater(tblMatrix::repaint));
        }
    }

    private void executeMultiRoleReplay() {
        if (uiHost == null) {
            return;
        }
        String host = tfHost.getText().trim();
        if (host.isEmpty()) {
            if (toast != null) {
                toast.showWarning("Thiếu Host", "Vui lòng nhập Host đích trước khi chạy Replay.");
            }
            return;
        }
        int port = ((Number) spPort.getValue()).intValue();
        boolean https = chkHttps.isSelected();
        String rawTemplate = txtRawRequest.getText();
        List<RoleProfile> roles = roleStore != null ? roleStore.snapshotRoles() : List.of();
        List<RoleProfile> enabledRoles = new ArrayList<>();
        for (RoleProfile r : roles) {
            if (r.enabled) {
                enabledRoles.add(r);
            }
        }
        if (enabledRoles.isEmpty()) {
            RoleProfile rawOnly = new RoleProfile("baseline", "Baseline (Không đổi Auth)", true, "USER");
            enabledRoles.add(rawOnly);
        }

        btnRunMultiRole.setEnabled(false);
        new SwingWorker<List<RoleRunResult>, Void>() {
            @Override
            protected List<RoleRunResult> doInBackground() {
                List<RoleRunResult> results = new ArrayList<>();
                for (RoleProfile role : enabledRoles) {
                    String adapted = applyRoleHeadersToRawHttp(rawTemplate, role);
                    byte[] rawBytes = HttpEncodingGuard.toIso88591Bytes(adapted);
                    UiHost.SendResult res = uiHost.sendRawRequest(rawBytes, host, port, https);
                    String respTxt = res.ok
                        ? HttpEncodingGuard.fromIso88591Bytes(res.rawResponseBytes)
                        : ("ERROR: " + res.error);
                    results.add(new RoleRunResult(
                        role.name,
                        role.tier,
                        res.ok ? res.statusCode : 0,
                        res.rawResponseBytes.length,
                        res.elapsedMs,
                        res.ok ? "OK" : res.error,
                        respTxt
                    ));
                }
                return results;
            }

            @Override
            protected void done() {
                btnRunMultiRole.setEnabled(true);
                try {
                    List<RoleRunResult> res = get();
                    matrixModel.setRows(res);
                    matrixWrap.refreshEmptyState();
                    if (!res.isEmpty()) {
                        tblMatrix.setRowSelectionInterval(0, 0);
                        txtRespPreview.setText(res.get(0).responseText);
                        txtRespPreview.setCaretPosition(0);
                    }
                    if (toast != null) {
                        toast.showSuccess("Hoàn tất Multi-Role Replay", "Đã chạy " + res.size() + " Role");
                    }
                } catch (Exception ex) {
                    if (toast != null) {
                        toast.showError("Lỗi Replay", ex.getMessage());
                    }
                }
            }
        }.execute();
    }

    private static String applyRoleHeadersToRawHttp(String raw, RoleProfile role) {
        if (role == null || (role.headers.isEmpty() && role.cookies.isEmpty())) {
            return raw;
        }
        String nl = raw.contains("\r\n") ? "\r\n" : "\n";
        int sep = raw.indexOf(nl + nl);
        String head = sep >= 0 ? raw.substring(0, sep) : raw;
        String body = sep >= 0 ? raw.substring(sep + nl.length() * 2) : "";

        String[] lines = head.split("\\r?\\n");
        if (lines.length == 0) {
            return raw;
        }
        String requestLine = lines[0];
        Map<String, String> headerMap = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String k = lines[i].substring(0, colon).trim();
                String v = lines[i].substring(colon + 1).trim();
                headerMap.put(k, v);
            }
        }
        for (Map.Entry<String, String> h : role.headers.entrySet()) {
            removeCaseInsensitive(headerMap, h.getKey());
            headerMap.put(h.getKey(), HttpEncodingGuard.asciiSafe(h.getValue()));
        }
        if (!role.cookies.isEmpty()) {
            StringBuilder cookieVal = new StringBuilder();
            for (Map.Entry<String, String> c : role.cookies.entrySet()) {
                if (cookieVal.length() > 0) {
                    cookieVal.append("; ");
                }
                cookieVal.append(c.getKey()).append('=').append(HttpEncodingGuard.asciiSafe(c.getValue()));
            }
            removeCaseInsensitive(headerMap, "Cookie");
            headerMap.put("Cookie", cookieVal.toString());
        }

        StringBuilder out = new StringBuilder();
        out.append(requestLine).append(nl);
        for (Map.Entry<String, String> en : headerMap.entrySet()) {
            out.append(en.getKey()).append(": ").append(en.getValue()).append(nl);
        }
        out.append(nl).append(body);
        return out.toString();
    }

    private static void removeCaseInsensitive(Map<String, String> map, String targetKey) {
        map.entrySet().removeIf(en -> en.getKey().equalsIgnoreCase(targetKey));
    }

    @Override
    protected void onDispose() {
        if (roleSub != null) {
            try {
                roleSub.close();
            } catch (Exception ignored) {
            }
            roleSub = null;
        }
    }

    private static final class RoleRunResult {
        final String roleName;
        final String tier;
        final int statusCode;
        final int bytes;
        final long elapsedMs;
        final String note;
        final String responseText;

        RoleRunResult(String roleName, String tier, int statusCode, int bytes, long elapsedMs, String note, String responseText) {
            this.roleName = roleName;
            this.tier = tier;
            this.statusCode = statusCode;
            this.bytes = bytes;
            this.elapsedMs = elapsedMs;
            this.note = note;
            this.responseText = responseText;
        }
    }

    private static final class RoleMatrixModel extends AbstractTableModel {
        private static final String[] COLS = {"Role", "Tier", "HTTP", "Bytes", "Time (ms)", "Ghi chú"};
        private List<RoleRunResult> rows = new ArrayList<>();

        void setRows(List<RoleRunResult> rows) {
            this.rows = rows != null ? rows : new ArrayList<>();
            fireTableDataChanged();
        }

        RoleRunResult getAt(int mr) {
            return mr >= 0 && mr < rows.size() ? rows.get(mr) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 2 || col == 3) return Integer.class;
            if (col == 4) return Long.class;
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            RoleRunResult r = rows.get(row);
            switch (col) {
                case 0: return r.roleName;
                case 1: return r.tier;
                case 2: return Integer.valueOf(r.statusCode);
                case 3: return Integer.valueOf(r.bytes);
                case 4: return Long.valueOf(r.elapsedMs);
                case 5: return r.note;
                default: return "";
            }
        }
    }

    private final class MatrixCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            javax.swing.JTable tbl, Object val, boolean sel, boolean focus, int row, int col
        ) {
            Component c = super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
            c.setFont(FontManager.uiRegular());
            if (!sel) {
                c.setBackground(row % 2 == 0 ? Theme.surface() : Theme.surfaceAlt());
                c.setForeground(Theme.text());
                RoleRunResult rr = matrixModel.getAt(tbl.convertRowIndexToModel(row));
                if (rr != null && col == 2) {
                    c.setForeground(Theme.forStatus(rr.statusCode).text());
                    c.setFont(FontManager.uiSemiBold());
                }
            } else {
                c.setBackground(Theme.selection());
                c.setForeground(Theme.selectionText());
            }
            return c;
        }
    }
}
