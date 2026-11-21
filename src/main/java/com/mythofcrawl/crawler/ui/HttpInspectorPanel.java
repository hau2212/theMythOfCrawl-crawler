package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ActionStep;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.ReviewStatus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Modern Burp-style HTTP Inspector supporting:
 * - Native Montoya HttpRequestEditor / HttpResponseEditor (Pretty / Raw / Hex / Render)
 * - Responsive layout switching: Side-by-Side (>= 820px), Vertical Stack, or Tabbed (< 820px split-screen)
 * - Structured Details & Triage side/tab pane (Attributes, Params, Audit NOTEs, Variant Diff, HighlightColor, ReviewStatus, User Comment)
 * - One-click Send to Repeater, Send to Organizer, Queue Logger++, and Shared Groups dialog
 */
public final class HttpInspectorPanel extends JPanel {

    public enum DockMode {
        AUTO,
        HORIZONTAL,
        VERTICAL,
        TABBED
    }

    private final MontoyaApi api;
    private final CrawlState state;
    private final ActionGroupStore store;
    private final Consumer<String> onShowSharedGroups;
    private final Consumer<ApiCall> onQueueCall;
    private final BiConsumer<CapturedApiEntry, String> onSendRepeater;
    private final Consumer<ApiCall> onSendOrganizerCall;
    private final Consumer<CapturedApiEntry> onSendOrganizerCapture;
    private final Runnable onCloseRequested;
    private final Runnable onTriageChanged;
    private final Consumer<String> onSummaryChanged;

    private final JLabel lblSummary = new JLabel(
        "Chọn một API, NOTE hoặc dòng Excel (Enter / double-click để mở HTTP Req/Resp)");
    private final JComboBox<VariantItem> cbVariant = new JComboBox<>();
    private final JButton btnShowShared = new JButton("Nhóm dùng chung");
    private final JButton btnQueuePush = new JButton("+ Logger++");
    private final JButton btnRepeater = new JButton("Repeater");
    private final JButton btnOrganizer = new JButton("Organizer");
    private final JButton btnLocateWeb = new JButton("Tìm trên Web");
    private final JToggleButton btnToggleDetails = new JToggleButton("Chi tiết");
    private final JToggleButton btnDockAuto = new JToggleButton("Tự động");
    private final JToggleButton btnDockHoriz = new JToggleButton("Ngồi cạnh");
    private final JToggleButton btnDockVert = new JToggleButton("Xếp dọc");
    private final JToggleButton btnDockTab = new JToggleButton("Tab");
    private final JButton btnClose = new JButton();

    private final JTextArea txtNotesBanner = new JTextArea(2, 40);
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;

    // Structured Details / Triage side panel (Burp Inspector style)
    private final JPanel detailsPanel = new JPanel(new BorderLayout(4, 4));
    private final JComboBox<HighlightColor> cbHighlight = new JComboBox<>(HighlightColor.values());
    private final JComboBox<ReviewStatus> cbReview = new JComboBox<>(ReviewStatus.values());
    private final JTextField txtComment = new JTextField(18);
    private final JButton btnSaveComment = new JButton("Lưu");
    private final JTextArea txtDetailsBody = new JTextArea();

    private final JPanel bodyCardHost = new JPanel(new CardLayout());
    private final JPanel splitHost = new JPanel(new BorderLayout());
    private final JTabbedPane tabbedEditors = new JTabbedPane();
    private JSplitPane reqRespSplit;
    private JSplitPane outerInspectorSplit;

    private DockMode dockMode = DockMode.AUTO;
    private boolean detailsVisible = true;
    private boolean updatingControls = false;
    private String currentEndpointKey = "";
    private ApiCall currentCall;
    private CapturedApiEntry currentCapture;
    private ApiSpecEntry currentSpecEntry;

    public HttpInspectorPanel(
        MontoyaApi api,
        CrawlState state,
        Consumer<String> onShowSharedGroups,
        Consumer<ApiCall> onQueueCall,
        BiConsumer<CapturedApiEntry, String> onSendRepeater,
        Consumer<ApiCall> onSendOrganizerCall,
        Consumer<CapturedApiEntry> onSendOrganizerCapture,
        Runnable onCloseRequested,
        Runnable onTriageChanged,
        Consumer<String> onSummaryChanged
    ) {
        this.api = api;
        this.state = state;
        this.store = state != null ? state.getActionGroups() : null;
        this.onShowSharedGroups = onShowSharedGroups;
        this.onQueueCall = onQueueCall;
        this.onSendRepeater = onSendRepeater;
        this.onSendOrganizerCall = onSendOrganizerCall;
        this.onSendOrganizerCapture = onSendOrganizerCapture;
        this.onCloseRequested = onCloseRequested;
        this.onTriageChanged = onTriageChanged;
        this.onSummaryChanged = onSummaryChanged;

        this.requestEditor = api != null
            ? api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY) : null;
        this.responseEditor = api != null
            ? api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY) : null;

        setLayout(new BorderLayout(0, 2));
        buildUi();
        applyTheme();
        clear();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (dockMode == DockMode.AUTO) {
                    applyEffectiveLayout();
                }
            }
        });
    }

    private final JPanel topBar = new JPanel(new BorderLayout(8, 2));
    private final JLabel lblDetailsHeader = new JLabel("Chi tiết & Triage");

    private void buildUi() {
        JPanel rightActions = new JPanel(new WrapLayout(FlowLayout.RIGHT, 4, 1));
        rightActions.setOpaque(false);

        cbVariant.setPreferredSize(new Dimension(210, 24));
        cbVariant.addActionListener(e -> {
            if (!updatingControls) {
                VariantItem item = (VariantItem) cbVariant.getSelectedItem();
                if (item != null && item.capture != null) {
                    renderHttpMessages(item.specEntry, item.call, item.capture);
                }
            }
        });

        btnShowShared.setEnabled(false);
        btnShowShared.addActionListener(e -> {
            if (!currentEndpointKey.isEmpty() && onShowSharedGroups != null) {
                onShowSharedGroups.accept(currentEndpointKey);
            }
        });

        btnQueuePush.setEnabled(false);
        btnQueuePush.setToolTipText("Đưa API đang xem vào hàng đẩy Logger++ (Extender)");
        btnQueuePush.addActionListener(e -> {
            VariantItem item = (VariantItem) cbVariant.getSelectedItem();
            if (item != null && item.call != null && onQueueCall != null) {
                onQueueCall.accept(item.call);
            }
        });

        btnRepeater.setEnabled(false);
        btnRepeater.setToolTipText("Gửi sang Burp Repeater (Ctrl+R)");
        btnRepeater.addActionListener(e -> sendCurrentToRepeater());

        btnOrganizer.setEnabled(false);
        btnOrganizer.setToolTipText("Gửi sang Burp Organizer kèm Highlight & Ghi chú (Ctrl+O)");
        btnOrganizer.addActionListener(e -> sendCurrentToOrganizer());

        btnLocateWeb.setEnabled(false);
        btnLocateWeb.setToolTipText("Mở trình duyệt & khoanh vùng Spotlight từng bước dẫn tới API này");
        btnLocateWeb.addActionListener(e -> {
            if (currentCall != null) {
                WebLocatorHelper.showLauncherMenu(btnLocateWeb, state, currentCall, null);
            } else if (currentCapture != null) {
                WebLocatorHelper.showLauncherMenuForCapture(btnLocateWeb, state, currentCapture, currentCapture.actionPath, null);
            }
        });

        btnToggleDetails.setSelected(detailsVisible);
        btnToggleDetails.setToolTipText("Hiện/Ẩn bảng Chi tiết & Triage bên phải");
        btnToggleDetails.addActionListener(e -> {
            detailsVisible = btnToggleDetails.isSelected();
            applyEffectiveLayout();
            updateButtonStyles();
        });

        btnDockAuto.setSelected(true);
        btnDockAuto.setToolTipText("Tự động xếp ngang (>=820px) hoặc Tab (<820px)");
        btnDockHoriz.setToolTipText("Luôn xếp Request | Response cạnh nhau");
        btnDockVert.setToolTipText("Luôn xếp Request / Response dọc");
        btnDockTab.setToolTipText("Luôn dùng Tab [Request | Response | Chi tiết]");

        btnDockAuto.addActionListener(e -> setDockMode(DockMode.AUTO));
        btnDockHoriz.addActionListener(e -> setDockMode(DockMode.HORIZONTAL));
        btnDockVert.addActionListener(e -> setDockMode(DockMode.VERTICAL));
        btnDockTab.addActionListener(e -> setDockMode(DockMode.TABBED));

        btnClose.setToolTipText("Ẩn HTTP Inspector (Esc)");
        btnClose.addActionListener(e -> {
            if (onCloseRequested != null) {
                onCloseRequested.run();
            }
        });

        rightActions.add(new JLabel("Bản:"));
        rightActions.add(cbVariant);
        rightActions.add(btnShowShared);
        rightActions.add(btnQueuePush);
        rightActions.add(btnRepeater);
        rightActions.add(btnOrganizer);
        rightActions.add(btnLocateWeb);
        rightActions.add(btnToggleDetails);
        rightActions.add(btnDockAuto);
        rightActions.add(btnDockHoriz);
        rightActions.add(btnDockVert);
        rightActions.add(btnDockTab);
        rightActions.add(btnClose);

        topBar.add(lblSummary, BorderLayout.CENTER);
        topBar.add(rightActions, BorderLayout.EAST);

        txtNotesBanner.setEditable(false);
        txtNotesBanner.setLineWrap(true);
        txtNotesBanner.setWrapStyleWord(true);
        txtNotesBanner.setOpaque(true);
        txtNotesBanner.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        JPanel northWrap = new JPanel(new BorderLayout(2, 2));
        northWrap.setOpaque(false);
        northWrap.add(topBar, BorderLayout.NORTH);
        northWrap.add(txtNotesBanner, BorderLayout.SOUTH);
        add(northWrap, BorderLayout.NORTH);

        buildDetailsPanel();

        if (requestEditor != null && responseEditor != null) {
            reqRespSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, requestEditor.uiComponent(), responseEditor.uiComponent());
            reqRespSplit.setResizeWeight(0.5);
            reqRespSplit.setOneTouchExpandable(false);

            outerInspectorSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, reqRespSplit, detailsPanel);
            outerInspectorSplit.setResizeWeight(0.74);
            outerInspectorSplit.setOneTouchExpandable(false);
            splitHost.add(outerInspectorSplit, BorderLayout.CENTER);

            bodyCardHost.add(splitHost, "SPLIT");
            bodyCardHost.add(tabbedEditors, "TABBED");
            add(bodyCardHost, BorderLayout.CENTER);
            applyEffectiveLayout();
        } else {
            JPanel fallback = new JPanel(new BorderLayout(6, 6));
            fallback.add(new JLabel("Montoya HTTP Editor chỉ hiển thị khi chạy trong Burp Suite."), BorderLayout.CENTER);
            fallback.add(detailsPanel, BorderLayout.EAST);
            add(fallback, BorderLayout.CENTER);
        }
    }

    private void buildDetailsPanel() {
        JPanel triageForm = new JPanel(new GridBagLayout());
        triageForm.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 3, 2, 3);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        cbHighlight.addActionListener(e -> {
            if (!updatingControls && currentCall != null) {
                currentCall.setHighlightColor((HighlightColor) cbHighlight.getSelectedItem());
                if (onTriageChanged != null) {
                    onTriageChanged.run();
                }
            }
        });
        cbReview.addActionListener(e -> {
            if (!updatingControls && currentCall != null) {
                currentCall.setReviewStatus((ReviewStatus) cbReview.getSelectedItem());
                if (onTriageChanged != null) {
                    onTriageChanged.run();
                }
            }
        });
        Runnable saveCmt = () -> {
            if (!updatingControls && currentCall != null) {
                currentCall.setUserComment(txtComment.getText());
                refreshDetailsBody();
                if (onTriageChanged != null) {
                    onTriageChanged.run();
                }
            }
        };
        btnSaveComment.addActionListener(e -> saveCmt.run());
        txtComment.addActionListener(e -> saveCmt.run());

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        triageForm.add(new JLabel("Màu:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        triageForm.add(cbHighlight, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        triageForm.add(new JLabel("Trạng thái:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        triageForm.add(cbReview, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        triageForm.add(new JLabel("Ghi chú:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        JPanel cmtRow = new JPanel(new BorderLayout(4, 0));
        cmtRow.setOpaque(false);
        cmtRow.add(txtComment, BorderLayout.CENTER);
        cmtRow.add(btnSaveComment, BorderLayout.EAST);
        triageForm.add(cmtRow, gc);

        txtDetailsBody.setEditable(false);
        txtDetailsBody.setLineWrap(true);
        txtDetailsBody.setWrapStyleWord(true);
        JScrollPane spDetails = new JScrollPane(txtDetailsBody);
        spDetails.setBorder(BorderFactory.createEmptyBorder());

        JPanel detailsTop = new JPanel(new BorderLayout(0, 6));
        detailsTop.setOpaque(false);
        detailsTop.add(lblDetailsHeader, BorderLayout.NORTH);
        detailsTop.add(triageForm, BorderLayout.CENTER);

        detailsPanel.setPreferredSize(new Dimension(280, 200));
        detailsPanel.add(detailsTop, BorderLayout.NORTH);
        detailsPanel.add(spDetails, BorderLayout.CENTER);
    }

    public void setDockMode(DockMode mode) {
        this.dockMode = mode != null ? mode : DockMode.AUTO;
        applyEffectiveLayout();
        updateButtonStyles();
    }

    private void applyEffectiveLayout() {
        if (requestEditor == null || responseEditor == null || reqRespSplit == null || outerInspectorSplit == null) {
            return;
        }
        int w = getWidth();
        DockMode effective = dockMode;
        if (effective == DockMode.AUTO) {
            effective = (w > 0 && w < 820) ? DockMode.TABBED : DockMode.HORIZONTAL;
        }

        CardLayout cl = (CardLayout) bodyCardHost.getLayout();
        if (effective == DockMode.TABBED) {
            tabbedEditors.removeAll();
            tabbedEditors.addTab("Request", requestEditor.uiComponent());
            tabbedEditors.addTab("Response", responseEditor.uiComponent());
            tabbedEditors.addTab("Chi tiết & Triage", detailsPanel);
            cl.show(bodyCardHost, "TABBED");
        } else {
            tabbedEditors.removeAll();
            reqRespSplit.setOrientation(
                effective == DockMode.VERTICAL ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
            reqRespSplit.setLeftComponent(requestEditor.uiComponent());
            reqRespSplit.setRightComponent(responseEditor.uiComponent());
            reqRespSplit.setResizeWeight(0.5);

            outerInspectorSplit.setLeftComponent(reqRespSplit);
            if (detailsVisible) {
                outerInspectorSplit.setRightComponent(detailsPanel);
                detailsPanel.setVisible(true);
                outerInspectorSplit.setDividerSize(6);
                outerInspectorSplit.setResizeWeight(0.75);
            } else {
                detailsPanel.setVisible(false);
                outerInspectorSplit.setDividerSize(0);
                outerInspectorSplit.setDividerLocation(1.0);
            }
            cl.show(bodyCardHost, "SPLIT");
        }
        bodyCardHost.revalidate();
        bodyCardHost.repaint();
    }

    public void sendCurrentToRepeater() {
        VariantItem item = (VariantItem) cbVariant.getSelectedItem();
        if (item == null || item.capture == null || onSendRepeater == null) {
            return;
        }
        String title = item.call != null
            ? (item.call.inExcel ? ("STT " + item.call.excelStt + " " + item.capture.method)
                                 : ("G" + item.call.groupId + "#" + item.call.index + " " + item.capture.method))
            : (item.specEntry != null ? ("STT " + item.specEntry.stt + " " + item.capture.method) : item.capture.method);
        onSendRepeater.accept(item.capture, title);
    }

    public void sendCurrentToOrganizer() {
        VariantItem item = (VariantItem) cbVariant.getSelectedItem();
        if (item == null) {
            return;
        }
        if (item.call != null && onSendOrganizerCall != null) {
            onSendOrganizerCall.accept(item.call);
        } else if (item.capture != null && onSendOrganizerCapture != null) {
            onSendOrganizerCapture.accept(item.capture);
        }
    }

    public CapturedApiEntry getSelectedOrPrimaryCapture(ApiSpecEntry entry) {
        if (entry == null) {
            return null;
        }
        VariantItem item = (VariantItem) cbVariant.getSelectedItem();
        if (item != null && item.specEntry == entry && item.capture != null) {
            return item.capture;
        }
        return entry.getPrimaryCapture();
    }

    public void showApiCall(ApiCall call) {
        if (call == null) {
            clear();
            return;
        }
        updatingControls = true;
        try {
            cbVariant.removeAllItems();
            cbVariant.addItem(new VariantItem(null, call, call.capture,
                "[Nhóm #" + call.groupId + " API #" + call.index + "] " + call.displayName()));
            cbVariant.setSelectedIndex(0);
        } finally {
            updatingControls = false;
        }
        renderHttpMessages(null, call, call.capture);
    }

    public void showStandaloneCapture(CapturedApiEntry cap) {
        if (cap == null) {
            clear();
            return;
        }
        updatingControls = true;
        try {
            cbVariant.removeAllItems();
            cbVariant.addItem(new VariantItem(null, null, cap, cap.actionPath));
            cbVariant.setSelectedIndex(0);
        } finally {
            updatingControls = false;
        }
        renderHttpMessages(null, null, cap);
    }

    public void showSpecEntry(ApiSpecEntry entry) {
        if (entry == null) {
            clear();
            return;
        }
        CapturedApiEntry primary = entry.getPrimaryCapture();
        updatingControls = true;
        try {
            cbVariant.removeAllItems();
            if (primary != null) {
                cbVariant.addItem(new VariantItem(entry, null, primary,
                    "Bản chính (" + primary.paramCount() + " params) - " + primary.actionPath));
                int i = 1;
                for (CapturedApiEntry alt : entry.getAlternateVariants()) {
                    cbVariant.addItem(new VariantItem(entry, null, alt,
                        "Biến thể #" + (i++) + " (" + alt.paramCount() + " params) - " + alt.actionPath));
                }
                cbVariant.setSelectedIndex(0);
            }
        } finally {
            updatingControls = false;
        }
        if (primary != null) {
            renderHttpMessages(entry, null, primary);
        } else {
            clearForMissing(entry);
        }
    }

    private void renderHttpMessages(ApiSpecEntry specEntry, ApiCall call, CapturedApiEntry cap) {
        if (cap == null) {
            clear();
            return;
        }
        this.currentSpecEntry = specEntry;
        this.currentCall = call;
        this.currentCapture = cap;

        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = AuditTheme.panelSurface(dark);

        String tag;
        if (call != null) {
            tag = call.inExcel
                ? ("[STT " + call.excelStt + " | G" + call.groupId + "#" + call.index + "] ")
                : ("[G" + call.groupId + "#" + call.index + "] ");
            currentEndpointKey = call.endpointKey;
            int shared = store != null ? store.sharedGroupCount(call.endpointKey) : 0;
            btnShowShared.setEnabled(shared > 1);
            btnShowShared.setText("Trùng (" + shared + ")");
        } else if (specEntry != null) {
            tag = "[STT " + specEntry.stt + "] ";
            currentEndpointKey = "";
            btnShowShared.setEnabled(false);
            btnShowShared.setText("Trùng");
        } else {
            tag = "[Ngoài Excel] ";
            currentEndpointKey = "";
            btnShowShared.setEnabled(false);
            btnShowShared.setText("Trùng");
        }

        lblSummary.setText(tag + cap.actionPath + "  ->  " + cap.method + " " + cap.fullUrl
            + "  (HTTP " + cap.statusCode + ", " + cap.paramCount() + " params)");
        if (onSummaryChanged != null) {
            String pDisp = cap.requestPathWithQuery != null ? cap.requestPathWithQuery : "";
            if (pDisp.length() > 42) {
                pDisp = pDisp.substring(0, 39) + "...";
            }
            onSummaryChanged.accept(cap.method + " " + pDisp);
        }

        if (call != null && call.hasNotes()) {
            AuditNote.Severity worst = call.worstSeverity();
            txtNotesBanner.setText("NOTE (" + call.getNotes().size() + "): " + call.notesSummary());
            txtNotesBanner.setForeground(AuditTheme.severityForeground(worst, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(worst, baseBg, dark));
        } else if (specEntry != null && !specEntry.getNotesSummary().isEmpty()) {
            txtNotesBanner.setText("NOTE tham số: " + specEntry.getNotesSummary());
            txtNotesBanner.setForeground(AuditTheme.severityForeground(AuditNote.Severity.WARN, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(AuditNote.Severity.WARN, baseBg, dark));
        } else {
            txtNotesBanner.setText("Params: " + (cap.paramCount() == 0 ? "(không có tham số)" : cap.paramSignature()));
            txtNotesBanner.setForeground(AuditTheme.mutedForeground(dark));
            txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        }

        updatingControls = true;
        try {
            boolean hasCall = call != null;
            cbHighlight.setEnabled(hasCall);
            cbReview.setEnabled(hasCall);
            txtComment.setEnabled(hasCall);
            btnSaveComment.setEnabled(hasCall);
            if (hasCall) {
                cbHighlight.setSelectedItem(call.getHighlightColor());
                cbReview.setSelectedItem(call.getReviewStatus());
                txtComment.setText(call.getUserComment());
            } else {
                cbHighlight.setSelectedItem(HighlightColor.NONE);
                cbReview.setSelectedItem(ReviewStatus.UNREVIEWED);
                txtComment.setText("");
            }
        } finally {
            updatingControls = false;
        }
        refreshDetailsBody();

        btnQueuePush.setEnabled(call != null);
        btnRepeater.setEnabled(api != null);
        btnOrganizer.setEnabled(api != null);
        btnLocateWeb.setEnabled(state != null && (call != null || cap != null));

        if (requestEditor != null && responseEditor != null) {
            try {
                HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
                HttpRequest req = cap.toMontoyaRequest(svc);
                requestEditor.setRequest(req);
            } catch (Exception ignored) {
            }
            try {
                HttpResponse resp = cap.toMontoyaResponse();
                responseEditor.setResponse(resp);
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDetailsBody() {
        StringBuilder sb = new StringBuilder();
        if (currentCapture != null) {
            sb.append("Method: ").append(currentCapture.method).append('\n');
            sb.append("HTTP: ").append(currentCapture.statusCode)
                .append("  |  Size: ").append(currentCapture.responseLength).append(" B")
                .append("  |  Mode: ").append(currentCapture.getHttpMode()).append('\n');
            if (currentCapture.responseTruncated && !currentCapture.hasTempFileBacking()) {
                sb.append("(Lưu ý: body preview bị cắt ở ").append(CapturedApiEntry.MAX_INLINE_RESPONSE_BYTES / 1024).append(" KB)\n");
            }
            if (currentCapture.hasTempFileBacking()) {
                sb.append("Storage: Burp TempFile (lossless)\n");
            }
            sb.append("\n--- Tham số (").append(currentCapture.paramCount()).append(") ---\n");
            sb.append(currentCapture.paramKeys.isEmpty() ? "(Không có)" : String.join("\n• ", prependEmpty(currentCapture.paramKeys))).append('\n');
        }
        if (currentCall != null) {
            if (!currentCall.graphqlOperation.isEmpty()) {
                sb.append("\nGraphQL Op: ").append(currentCall.graphqlOperation).append('\n');
            }
            if (!currentCall.mimeType.isEmpty()) {
                sb.append("MIME: ").append(currentCall.mimeType).append('\n');
            }
            if (!currentCall.getUserComment().isEmpty()) {
                sb.append("\n--- Ghi chú cá nhân ---\n").append(currentCall.getUserComment()).append('\n');
            }
            List<ActionStep> trail = currentCall.getActionTrail();
            if (!trail.isEmpty()) {
                sb.append("\n--- Dấu vết UI / Action Trail (").append(trail.size()).append(" bước) ---\n");
                for (int i = 0; i < trail.size(); i++) {
                    ActionStep st = trail.get(i);
                    sb.append(i + 1).append(". [").append(st.kind).append("] ")
                        .append(st.label.isEmpty() ? st.actionPath : st.label);
                    if (!st.cssSelector.isEmpty()) {
                        sb.append("  (").append(st.cssSelector).append(')');
                    }
                    sb.append('\n');
                }
            }
            List<AuditNote> notes = currentCall.getNotes();
            if (!notes.isEmpty()) {
                sb.append("\n--- Audit NOTEs (").append(notes.size()).append(") ---\n");
                for (AuditNote n : notes) {
                    sb.append("• [").append(n.severity.label).append("] ").append(n.message).append('\n');
                }
            }
            if (store != null) {
                GroupTableModel.PathOverlapSummary ov = GroupTableModel.summarizePathOverlap(store, currentCall);
                if (ov.totalCalls > 1) {
                    sb.append("\n--- Trùng trên toàn site (").append(ov.totalCalls).append(" lần / ")
                        .append(ov.distinctLocations).append(" vị trí) ---\n");
                    sb.append(ov.diffSummary).append('\n');
                    for (int i = 0; i < Math.min(10, ov.calls.size()); i++) {
                        ApiCall other = ov.calls.get(i);
                        sb.append(i + 1).append(". ").append(other.displayName())
                            .append(" -> ").append(GroupTableModel.describeCallDiffAgainst(other, currentCall)).append('\n');
                    }
                }
            }
        } else if (currentSpecEntry != null) {
            if (!currentSpecEntry.getJsHints().isEmpty()) {
                sb.append("\n--- JS Hints ---\n").append(String.join("\n", currentSpecEntry.getJsHints())).append('\n');
            }
        }
        txtDetailsBody.setText(sb.toString().trim());
        txtDetailsBody.setCaretPosition(0);
    }

    private static String prependEmpty(java.util.Set<String> keys) {
        return "• " + String.join("\n• ", keys);
    }

    private void clearForMissing(ApiSpecEntry entry) {
        boolean dark = AuditTheme.isDark(api, this);
        Color baseBg = AuditTheme.panelSurface(dark);
        this.currentSpecEntry = entry;
        this.currentCall = null;
        this.currentCapture = null;
        this.currentEndpointKey = "";

        btnShowShared.setEnabled(false);
        btnShowShared.setText("Trùng");
        btnQueuePush.setEnabled(false);
        btnRepeater.setEnabled(false);
        btnOrganizer.setEnabled(false);
        btnLocateWeb.setEnabled(false);
        cbHighlight.setEnabled(false);
        cbReview.setEnabled(false);
        txtComment.setEnabled(false);
        btnSaveComment.setEnabled(false);

        lblSummary.setText("[STT " + entry.stt + " - MISSING] " + entry.displayMethod + " " + entry.rawSpecPath);
        List<String> hints = entry.getJsHints();
        if (!hints.isEmpty()) {
            txtNotesBanner.setText("Chưa kích hoạt qua UI. Gợi ý xuất hiện trong file JS: " + String.join(", ", hints));
            txtNotesBanner.setForeground(AuditTheme.severityForeground(AuditNote.Severity.INFO, dark));
            txtNotesBanner.setBackground(AuditTheme.severityRowTint(AuditNote.Severity.INFO, baseBg, dark));
        } else {
            txtNotesBanner.setText("Chưa có request nào khớp API này."
                + (entry.extraInfo.isEmpty() ? "" : (" Thông tin thêm từ Excel: " + entry.extraInfo)));
            txtNotesBanner.setForeground(AuditTheme.mutedForeground(dark));
            txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        }
        refreshDetailsBody();
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    public void clear() {
        boolean dark = AuditTheme.isDark(api, this);
        this.currentSpecEntry = null;
        this.currentCall = null;
        this.currentCapture = null;
        this.currentEndpointKey = "";

        lblSummary.setText("Chọn một API, NOTE hoặc dòng Excel (Enter / double-click để soi gói tin)");
        if (onSummaryChanged != null) {
            onSummaryChanged.accept("");
        }
        txtNotesBanner.setText("");
        txtNotesBanner.setBackground(AuditTheme.cardSurface(dark));
        txtDetailsBody.setText("");

        updatingControls = true;
        try {
            cbVariant.removeAllItems();
            cbHighlight.setSelectedItem(HighlightColor.NONE);
            cbReview.setSelectedItem(ReviewStatus.UNREVIEWED);
            txtComment.setText("");
        } finally {
            updatingControls = false;
        }
        cbHighlight.setEnabled(false);
        cbReview.setEnabled(false);
        txtComment.setEnabled(false);
        btnSaveComment.setEnabled(false);
        btnShowShared.setEnabled(false);
        btnShowShared.setText("Trùng");
        btnQueuePush.setEnabled(false);
        btnRepeater.setEnabled(false);
        btnOrganizer.setEnabled(false);
        btnLocateWeb.setEnabled(false);

        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        Font disp = AuditTheme.displayFont(api, this);
        setBackground(AuditTheme.panelSurface(dark));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AuditTheme.borderColor(dark)));

        AuditTheme.styleSlimToolbar(topBar, dark);
        lblSummary.setFont(disp.deriveFont(Font.BOLD));
        lblSummary.setForeground(AuditTheme.textPrimary(dark));
        txtNotesBanner.setFont(disp);
        txtDetailsBody.setFont(disp);
        txtDetailsBody.setBackground(AuditTheme.tableSurface(dark));
        txtDetailsBody.setForeground(AuditTheme.textPrimary(dark));

        detailsPanel.setBackground(AuditTheme.panelSurface(dark));
        detailsPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, AuditTheme.borderColor(dark)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        lblDetailsHeader.setFont(disp.deriveFont(Font.BOLD));
        lblDetailsHeader.setForeground(AuditTheme.textPrimary(dark));
        AuditTheme.styleBurpTabbedPane(tabbedEditors, dark);
        updateButtonStyles();
    }

    private void updateButtonStyles() {
        boolean dark = AuditTheme.isDark(api, this);
        AuditTheme.stylePlainButton(btnShowShared, dark);
        AuditTheme.stylePlainButton(btnQueuePush, dark);
        AuditTheme.stylePlainButton(btnRepeater, dark);
        AuditTheme.stylePlainButton(btnOrganizer, dark);
        btnLocateWeb.setIcon(BurpIcons.locateWeb(AuditTheme.burpOrange(dark)));
        AuditTheme.stylePlainButton(btnLocateWeb, dark);
        AuditTheme.stylePlainButton(btnSaveComment, dark);
        AuditTheme.styleTogglePill(btnToggleDetails, detailsVisible, null, dark);
        AuditTheme.styleTogglePill(btnDockAuto, dockMode == DockMode.AUTO, null, dark);
        AuditTheme.styleTogglePill(btnDockHoriz, dockMode == DockMode.HORIZONTAL, null, dark);
        AuditTheme.styleTogglePill(btnDockVert, dockMode == DockMode.VERTICAL, null, dark);
        AuditTheme.styleTogglePill(btnDockTab, dockMode == DockMode.TABBED, null, dark);
        btnClose.setIcon(BurpIcons.clear(AuditTheme.mutedForeground(dark)));
        AuditTheme.stylePlainButton(btnClose, dark);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (lblSummary != null && txtDetailsBody != null) {
            applyTheme();
        }
    }

    private static final class VariantItem {
        final ApiSpecEntry specEntry;
        final ApiCall call;
        final CapturedApiEntry capture;
        final String label;

        VariantItem(ApiSpecEntry specEntry, ApiCall call, CapturedApiEntry capture, String label) {
            this.specEntry = specEntry;
            this.call = call;
            this.capture = capture;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
