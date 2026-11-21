package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.bridge.UiHost;
import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.model.ActionStep;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.MiniJson;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.SegmentedToggle;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Slim bottom Inline Raw HTTP Dock defaulting to a ~24-30px collapsed summary bar.
 * Expands cleanly into a JetBrains Mono Raw HTTP / Response / Lineage & Trail editor
 * using {@link HttpEncodingGuard}, never stealing focus when another held item arrives.
 */
public final class InlineRequestDock extends JPanel {

    private final CrawlState state;
    private final ProxyInterceptQueue interceptQueue;
    private final DuplicateLineageTracker lineageTracker;
    private final UiHost uiHost;
    private final ToastPane toast;
    private final Consumer<ApiCall> onLocateWeb;

    private final JPanel summaryBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final Pill pillMethod = new Pill("GET", Theme.Token.METHOD_GET, false);
    private final JLabel lblPath = new JLabel("Chưa chọn yêu cầu");
    private final Pill pillStatus = new Pill("IDLE", Theme.Token.NEUTRAL, false);
    private final Pill pillDupCount = new Pill("0 trùng", Theme.Token.DUP, false);
    private final Pill pillWaitingNotice = new Pill("", Theme.Token.PENDING, true);

    private final Ui.UiButton btnExpand;
    private final Ui.UiButton btnForward;
    private final Ui.UiButton btnSaveDraft;
    private final Ui.UiButton btnDrop;
    private final Ui.UiButton btnLocate;

    private final JPanel bodyWrap = new JPanel(new BorderLayout(0, Theme.SPACE_1));
    private final SegmentedToggle bodyModeToggle;
    private final Ui.UiButton btnFormatJson;
    private final Ui.UiButton btnRestoreOriginal;
    private final Ui.UiButton btnRepeater;
    private final Ui.UiButton btnOrganizer;

    private final JPanel bodyCards = new JPanel(new CardLayout());
    private final JTextArea txtRawRequest = new JTextArea();
    private final JTextArea txtRawResponse = new JTextArea();
    private final JTextArea txtLineage = new JTextArea();
    private final JScrollPane spReq = new JScrollPane(txtRawRequest);
    private final JScrollPane spResp = new JScrollPane(txtRawResponse);
    private final JScrollPane spLineage = new JScrollPane(txtLineage);

    private ProxyInterceptQueue.PendingInterceptItem currentPending;
    private ApiCall currentCall;
    private String originalRawReq = "";
    private boolean expanded = false;
    private boolean editedDirty = false;
    private boolean loadingDoc = false;
    private AutoCloseable themeSub;

    public InlineRequestDock(
        CrawlState state,
        ProxyInterceptQueue interceptQueue,
        DuplicateLineageTracker lineageTracker,
        UiHost uiHost,
        ToastPane toast,
        Consumer<ApiCall> onLocateWeb
    ) {
        this.state = state;
        this.interceptQueue = interceptQueue;
        this.lineageTracker = lineageTracker;
        this.uiHost = uiHost;
        this.toast = toast;
        this.onLocateWeb = onLocateWeb;

        this.btnExpand = Ui.iconBtn(VectorIcons.chevronRight(Theme.text()), "Mở rộng / Thu gọn Raw HTTP Dock");
        this.btnForward = Ui.solidPrimary("Gửi Server", VectorIcons.send(Theme.inkWhite()));
        this.btnSaveDraft = Ui.secondaryBtn("Lưu nháp", VectorIcons.edit(Theme.text()));
        this.btnDrop = Ui.dangerBtn("Hủy Drop", VectorIcons.cross(Theme.token(Theme.Token.DANGER).text()));
        this.btnLocate = Ui.tonalBtn(Theme.Token.ACCENT, "Tìm trên Web", VectorIcons.locate(Theme.token(Theme.Token.ACCENT).text()));

        this.bodyModeToggle = new SegmentedToggle(
            new SegmentedToggle.Option("Request Raw", Theme.Token.INFO),
            new SegmentedToggle.Option("Response", Theme.Token.SUCCESS),
            new SegmentedToggle.Option("Trùng lặp & Dấu vết", Theme.Token.DUP)
        );
        this.btnFormatJson = Ui.secondaryBtn("Format JSON");
        this.btnRestoreOriginal = Ui.secondaryBtn("Khôi phục gốc", VectorIcons.undo(Theme.text()));
        this.btnRepeater = Ui.secondaryBtn("Repeater", VectorIcons.repeater(Theme.text()));
        this.btnOrganizer = Ui.secondaryBtn("Organizer", VectorIcons.organizer(Theme.text()));

        setLayout(new BorderLayout(0, 0));
        setOpaque(true);

        buildSummaryBar();
        buildBodyArea();

        add(Ui.hairline(true), BorderLayout.NORTH);
        JPanel stack = new JPanel(new BorderLayout(0, 0));
        stack.setOpaque(false);
        stack.add(summaryBar, BorderLayout.NORTH);
        stack.add(bodyWrap, BorderLayout.CENTER);
        add(stack, BorderLayout.CENTER);

        setExpanded(false);
        updateButtonStates();
        applyTheme();
    }

    private void buildSummaryBar() {
        summaryBar.setOpaque(true);
        summaryBar.setBorder(BorderFactory.createEmptyBorder(3, Theme.SPACE_2, 3, Theme.SPACE_2));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 0));
        left.setOpaque(false);
        pillWaitingNotice.setVisible(false);
        left.add(btnExpand);
        left.add(pillMethod);
        left.add(lblPath);
        left.add(pillStatus);
        left.add(pillDupCount);
        left.add(pillWaitingNotice);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        right.setOpaque(false);
        right.add(btnForward);
        right.add(btnSaveDraft);
        right.add(btnDrop);
        right.add(btnLocate);

        btnExpand.addActionListener(e -> setExpanded(!expanded));
        btnForward.addActionListener(e -> onForwardClicked());
        btnSaveDraft.addActionListener(e -> onSaveDraftClicked());
        btnDrop.addActionListener(e -> onDropClicked());
        btnLocate.addActionListener(e -> {
            if (currentCall != null && onLocateWeb != null) {
                onLocateWeb.accept(currentCall);
            }
        });

        summaryBar.add(left, BorderLayout.CENTER);
        summaryBar.add(right, BorderLayout.EAST);
    }

    private void buildBodyArea() {
        bodyWrap.setOpaque(true);
        bodyWrap.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JPanel subToolbar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        subToolbar.setOpaque(false);
        subToolbar.add(bodyModeToggle, BorderLayout.WEST);

        JPanel subRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        subRight.setOpaque(false);
        subRight.add(btnFormatJson);
        subRight.add(btnRestoreOriginal);
        subRight.add(btnRepeater);
        subRight.add(btnOrganizer);
        subToolbar.add(subRight, BorderLayout.EAST);

        txtRawRequest.setLineWrap(false);
        txtRawResponse.setEditable(false);
        txtRawResponse.setLineWrap(false);
        txtLineage.setEditable(false);
        txtLineage.setLineWrap(true);
        txtLineage.setWrapStyleWord(true);

        txtRawRequest.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onReqEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onReqEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onReqEdited(); }
        });

        bodyModeToggle.onChange(idx -> {
            CardLayout cl = (CardLayout) bodyCards.getLayout();
            if (idx == 0) {
                cl.show(bodyCards, "REQ");
            } else if (idx == 1) {
                cl.show(bodyCards, "RESP");
            } else {
                cl.show(bodyCards, "LINEAGE");
            }
        });

        btnFormatJson.addActionListener(e -> formatJsonInEditor());
        btnRestoreOriginal.addActionListener(e -> {
            loadingDoc = true;
            try {
                txtRawRequest.setText(originalRawReq);
                txtRawRequest.setCaretPosition(0);
                editedDirty = false;
            } finally {
                loadingDoc = false;
            }
        });
        btnRepeater.addActionListener(e -> sendToBurpTool(true));
        btnOrganizer.addActionListener(e -> sendToBurpTool(false));

        bodyCards.add(spReq, "REQ");
        bodyCards.add(spResp, "RESP");
        bodyCards.add(spLineage, "LINEAGE");
        bodyCards.setPreferredSize(new Dimension(200, 220));

        bodyWrap.add(subToolbar, BorderLayout.NORTH);
        bodyWrap.add(bodyCards, BorderLayout.CENTER);
    }

    private void onReqEdited() {
        if (!loadingDoc && currentPending != null) {
            editedDirty = true;
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        bodyWrap.setVisible(expanded);
        btnExpand.setLabelAndIcon("", expanded
            ? VectorIcons.chevronDown(Theme.text())
            : VectorIcons.chevronRight(Theme.text()));
        revalidate();
        repaint();
    }

    public boolean isUserEditing() {
        return currentPending != null && (editedDirty || txtRawRequest.isFocusOwner());
    }

    /**
     * Called when a new pending item arrives over the 0ms queue push channel.
     * Never steals focus or overwrites an active edit in progress.
     */
    public void showPendingIfNotEditing(ProxyInterceptQueue.PendingInterceptItem item) {
        if (item == null) {
            return;
        }
        if (isUserEditing()) {
            pillWaitingNotice.setVisible(true);
            int cnt = interceptQueue != null ? interceptQueue.pendingCount() : 1;
            pillWaitingNotice.setPill("+" + Math.max(1, cnt - 1) + " chờ mới", Theme.Token.PENDING, true);
            return;
        }
        showPendingItem(item, false);
    }

    public void showPendingItem(ProxyInterceptQueue.PendingInterceptItem item, boolean expandDock) {
        this.currentPending = item;
        this.currentCall = null;
        this.editedDirty = false;
        this.pillWaitingNotice.setVisible(false);
        if (item == null) {
            clear();
            return;
        }
        pillMethod.setPill(item.method, Theme.forMethodToken(item.method), false);
        lblPath.setText(item.host + item.path);
        pillStatus.setPill("CHỜ DUYỆT", Theme.Token.PENDING, true);
        updateDuplicateBadge(item.method + " " + item.path);

        this.originalRawReq = item.rawRequestIso88591;
        loadReqText(this.originalRawReq);
        txtRawResponse.setText("Yêu cầu đang bị giữ tại Proxy.\nLý do: " + item.reason
            + (item.winningRuleName.isEmpty() ? "" : ("\nRule: " + item.winningRuleName)));
        txtRawResponse.setCaretPosition(0);
        populateLineageText(item.method + " " + item.path, null);

        if (expandDock) {
            setExpanded(true);
        }
        updateButtonStates();
    }

    public void showApiCall(ApiCall call) {
        this.currentPending = null;
        this.currentCall = call;
        this.editedDirty = false;
        this.pillWaitingNotice.setVisible(false);
        if (call == null || call.capture == null) {
            clear();
            return;
        }
        CapturedApiEntry cap = call.capture;
        String displayPath = call.displayPath();
        pillMethod.setPill(cap.method, Theme.forMethodToken(cap.method), false);
        lblPath.setText(cap.host + displayPath);
        int st = cap.statusCode;
        pillStatus.setPill(st > 0 ? ("HTTP " + st) : "CAPTURED", Theme.forStatusToken(st), false);
        updateDuplicateBadge(cap.method + " " + displayPath);

        this.originalRawReq = HttpEncodingGuard.fromIso88591Bytes(cap.requestBytes);
        loadReqText(this.originalRawReq);
        txtRawResponse.setText(HttpEncodingGuard.fromIso88591Bytes(cap.responseBytes));
        txtRawResponse.setCaretPosition(0);
        populateLineageText(cap.method + " " + displayPath, call);
        updateButtonStates();
    }

    public void clear() {
        this.currentPending = null;
        this.currentCall = null;
        this.editedDirty = false;
        this.originalRawReq = "";
        pillMethod.setPill("-", Theme.Token.NEUTRAL, false);
        lblPath.setText("Chọn một nhánh, API hoặc mục [CHỜ DUYỆT] trên cây để xem Raw HTTP");
        pillStatus.setPill("IDLE", Theme.Token.NEUTRAL, false);
        pillDupCount.setVisible(false);
        pillWaitingNotice.setVisible(false);
        loadReqText("");
        txtRawResponse.setText("");
        txtLineage.setText("");
        updateButtonStates();
    }

    private void loadReqText(String raw) {
        loadingDoc = true;
        try {
            txtRawRequest.setText(raw != null ? raw : "");
            txtRawRequest.setCaretPosition(0);
        } finally {
            loadingDoc = false;
        }
    }

    private void updateDuplicateBadge(String endpointKey) {
        if (lineageTracker == null || endpointKey == null || endpointKey.isEmpty()) {
            pillDupCount.setVisible(false);
            return;
        }
        DuplicateLineageTracker.EndpointLineage lin = lineageTracker.getLineage(endpointKey);
        if (lin == null || lin.hitCount <= 1) {
            pillDupCount.setVisible(false);
        } else {
            pillDupCount.setVisible(true);
            String txt = lin.seenBranches.size() > 1
                ? (lin.seenBranches.size() + " nhánh · " + lin.hitCount + " hit")
                : (lin.hitCount + " lần");
            pillDupCount.setPill(txt, Theme.Token.DUP, false);
        }
    }

    private void populateLineageText(String endpointKey, ApiCall call) {
        StringBuilder sb = new StringBuilder();
        if (lineageTracker != null && endpointKey != null) {
            DuplicateLineageTracker.EndpointLineage lin = lineageTracker.getLineage(endpointKey);
            if (lin != null) {
                sb.append("Endpoint Key: ").append(lin.endpointKey).append('\n');
                sb.append("Nhánh phát hiện gốc (originBranch): ").append(lin.originBranch).append('\n');
                sb.append("Tổng hits: ").append(lin.hitCount)
                    .append(" | Refresh: ").append(lin.refreshCount).append('\n');
                sb.append("Đã thấy tại các nhánh (").append(lin.seenBranches.size()).append("):\n");
                for (String b : lin.seenBranches) {
                    sb.append("  - ").append(b).append('\n');
                }
                if (!lin.moveHistory.isEmpty()) {
                    SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
                    sb.append("\nLịch sử di chuyển (Move History):\n");
                    for (DuplicateLineageTracker.MoveEvent mv : lin.moveHistory) {
                        sb.append("  [").append(fmt.format(new Date(mv.movedAtMs))).append("] ")
                            .append(mv.fromBranch).append(" -> ").append(mv.toBranch)
                            .append(" (").append(mv.reason).append(")\n");
                    }
                }
            }
        }
        if (call != null) {
            List<ActionStep> steps = call.getActionTrail();
            if (!steps.isEmpty()) {
                sb.append("\n--- Dấu vết UI Action Trail (").append(steps.size()).append(" bước) ---\n");
                for (int i = 0; i < steps.size(); i++) {
                    ActionStep st = steps.get(i);
                    sb.append(i + 1).append(". [").append(st.kind).append("] ")
                        .append(st.label.isEmpty() ? st.actionPath : st.label).append('\n');
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("Chưa có dữ liệu trùng lặp hoặc Action Trail bổ sung.");
        }
        txtLineage.setText(sb.toString());
        txtLineage.setCaretPosition(0);
    }

    private void onForwardClicked() {
        if (currentPending != null && interceptQueue != null) {
            String raw = txtRawRequest.getText();
            boolean ok = interceptQueue.resolveForward(currentPending.id, raw);
            if (ok && toast != null) {
                toast.showSuccess("Đã gửi sang Server", currentPending.method + " " + currentPending.path);
            }
            editedDirty = false;
            showNextPendingOrClear();
        } else if (currentCall != null && currentCall.capture != null && uiHost != null) {
            byte[] rawBytes = HttpEncodingGuard.toIso88591Bytes(txtRawRequest.getText());
            CapturedApiEntry cap = currentCall.capture;
            UiHost.SendResult res = uiHost.sendRawRequest(rawBytes, cap.host, cap.port, cap.https);
            if (toast != null) {
                if (res.ok) {
                    toast.showSuccess("Đã gửi lại Request", "HTTP " + res.statusCode + " (" + res.elapsedMs + " ms)");
                } else {
                    toast.showError("Lỗi gửi Request", res.error);
                }
            }
        }
    }

    private void onSaveDraftClicked() {
        if (currentPending != null && interceptQueue != null) {
            String raw = txtRawRequest.getText();
            boolean ok = interceptQueue.resolveSaveDraft(currentPending.id, raw);
            if (ok && toast != null) {
                toast.showInfo("Đã lưu nháp", currentPending.method + " " + currentPending.path);
            }
            editedDirty = false;
            showNextPendingOrClear();
        }
    }

    private void onDropClicked() {
        if (currentPending != null && interceptQueue != null) {
            boolean ok = interceptQueue.resolveDrop(currentPending.id);
            if (ok && toast != null) {
                toast.showWarning("Đã Drop yêu cầu", currentPending.method + " " + currentPending.path);
            }
            editedDirty = false;
            showNextPendingOrClear();
        }
    }

    private void showNextPendingOrClear() {
        if (interceptQueue != null) {
            List<ProxyInterceptQueue.PendingInterceptItem> rem = interceptQueue.snapshotPending();
            if (!rem.isEmpty()) {
                showPendingItem(rem.get(0), expanded);
                return;
            }
        }
        clear();
    }

    private void formatJsonInEditor() {
        String raw = txtRawRequest.getText();
        int sep = raw.indexOf("\r\n\r\n");
        String nl = "\r\n";
        if (sep < 0) {
            sep = raw.indexOf("\n\n");
            nl = "\n";
        }
        if (sep < 0) {
            return;
        }
        String head = raw.substring(0, sep);
        String body = raw.substring(sep + (nl.length() * 2)).trim();
        if (!body.startsWith("{")) {
            return;
        }
        try {
            Object parsed = MiniJson.parseObject(body);
            if (parsed != null) {
                String pretty = MiniJson.stringify(parsed);
                txtRawRequest.setText(head + nl + nl + pretty);
                txtRawRequest.setCaretPosition(0);
            }
        } catch (Exception ignored) {
        }
    }

    private void sendToBurpTool(boolean repeater) {
        if (uiHost == null) {
            return;
        }
        byte[] rawReq = HttpEncodingGuard.toIso88591Bytes(txtRawRequest.getText());
        String host = "";
        int port = 443;
        boolean https = true;
        String caption = "theMythOfCrawl";
        if (currentPending != null) {
            host = currentPending.host;
            caption = currentPending.method + " " + currentPending.path;
        } else if (currentCall != null && currentCall.capture != null) {
            host = currentCall.capture.host;
            port = currentCall.capture.port;
            https = currentCall.capture.https;
            caption = currentCall.capture.method + " " + currentCall.displayPath();
        }
        if (host.isEmpty()) {
            return;
        }
        if (repeater) {
            uiHost.sendToRepeater(rawReq, host, port, https, caption);
        } else {
            byte[] rawResp = HttpEncodingGuard.toIso88591Bytes(txtRawResponse.getText());
            uiHost.sendToOrganizer(rawReq, rawResp, host, port, https, caption);
        }
    }

    private void updateButtonStates() {
        boolean hasPending = currentPending != null;
        boolean hasCall = currentCall != null && currentCall.capture != null;
        btnForward.setEnabled(hasPending || (hasCall && uiHost != null));
        btnForward.setLabelAndIcon(hasPending ? "Gửi Server" : "Gửi lại", VectorIcons.send(Theme.inkWhite()));
        btnSaveDraft.setVisible(hasPending);
        btnSaveDraft.setEnabled(hasPending);
        btnDrop.setVisible(hasPending);
        btnDrop.setEnabled(hasPending);
        btnLocate.setEnabled(hasCall && onLocateWeb != null);
        btnFormatJson.setEnabled(hasPending || hasCall);
        btnRestoreOriginal.setEnabled(hasPending || hasCall);
        btnRepeater.setEnabled((hasPending || hasCall) && uiHost != null);
        btnOrganizer.setEnabled((hasPending || hasCall) && uiHost != null);
        txtRawRequest.setEditable(hasPending || hasCall);
    }

    public void applyTheme() {
        setBackground(Theme.surface());
        summaryBar.setBackground(Theme.surfaceAlt());
        bodyWrap.setBackground(Theme.surface());
        lblPath.setFont(FontManager.monoMedium());
        lblPath.setForeground(Theme.text());

        txtRawRequest.setFont(FontManager.monoRegular());
        txtRawRequest.setBackground(Theme.surface());
        txtRawRequest.setForeground(Theme.text());
        txtRawRequest.setCaretColor(Theme.text());

        txtRawResponse.setFont(FontManager.monoRegular());
        txtRawResponse.setBackground(Theme.surface());
        txtRawResponse.setForeground(Theme.text());

        txtLineage.setFont(FontManager.uiRegular());
        txtLineage.setBackground(Theme.surface());
        txtLineage.setForeground(Theme.text());

        spReq.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spResp.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spLineage.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spReq.getViewport().setBackground(Theme.surface());
        spResp.getViewport().setBackground(Theme.surface());
        spLineage.getViewport().setBackground(Theme.surface());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (summaryBar != null && bodyWrap != null && txtRawRequest != null) {
            applyTheme();
        }
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (summaryBar != null && bodyWrap != null) {
            setBackground(Theme.surface());
            summaryBar.setBackground(Theme.surfaceAlt());
            bodyWrap.setBackground(Theme.surface());
            lblPath.setForeground(Theme.text());
            txtRawRequest.setBackground(Theme.surface());
            txtRawRequest.setForeground(Theme.text());
            txtRawResponse.setBackground(Theme.surface());
            txtRawResponse.setForeground(Theme.text());
            txtLineage.setBackground(Theme.surface());
            txtLineage.setForeground(Theme.text());
        }
        super.paintComponent(g);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::applyTheme);
        }
    }

    @Override
    public void removeNotify() {
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
        super.removeNotify();
    }
}
