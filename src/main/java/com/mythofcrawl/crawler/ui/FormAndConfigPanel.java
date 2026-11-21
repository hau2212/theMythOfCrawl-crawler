package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.FormFillProfile;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dedicated, clutter-free Configuration Tab hosting:
 * - Left: Form / Date / Editable Table / Rich-Text Auto-Fill Profile (defaults to "TEXT", fully editable)
 * - Right: Crawl Scope, Root Path Label, Deep Technology Coverage, Service Worker Bypass & CDP Sidecar
 */
public final class FormAndConfigPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final Consumer<String> logger;
    private final Runnable onSaved;

    // FormFill fields
    private final JTextField tfQuickTextAll = new JTextField(FormFillProfile.DEFAULT_TEXT, 14);
    private final Map<FormFillProfile.Field, JTextField> fieldInputs = new EnumMap<>(FormFillProfile.Field.class);
    private final JTextArea txtCustomRules = new JTextArea(6, 30);
    private final JCheckBox cbOverwriteExisting = new JCheckBox("Ghi đè cả ô form đã có sẵn giá trị");
    private final JCheckBox cbUploadMockFiles = new JCheckBox("Tạo file giả đính kèm cho <input type='file'>");

    // Crawl & Tech settings
    private final JComboBox<String> cbRootLabel = new JComboBox<>(new String[]{"/", "toppage"});
    private final JTextField tfApiPrefix = new JTextField(20);
    private final JCheckBox cbAutoFill = new JCheckBox("Bật tự điền Form khi Crawl");
    private final JCheckBox cbDeepCrawl = new JCheckBox("Crawl sâu toàn công nghệ (Shadow DOM, Same-origin Iframe, Lazy Scroll, Select Filter, Bảng Editable)");
    private final JCheckBox cbStrictExcel = new JCheckBox("Strict Excel Mode (chỉ ghi bảng Excel; tab Nhóm & API vẫn ghi đầy đủ)");
    private final JCheckBox cbIgnoreTrailingSlash = new JCheckBox("Bỏ qua dấu '/' cuối path khi khớp Excel");
    private final JCheckBox cbNormalizeIds = new JCheckBox("Gộp ID động trong Route URL (/users/123 -> /users/{id})");
    private final JCheckBox cbRelaxCsp = new JCheckBox("Tự nới lỏng CSP header khi đang Crawl để Agent chạy mượt");
    private final JCheckBox cbCaptureJs = new JCheckBox("Quét thụ động file .js tìm Hidden Routes & JS Hints");
    private final JCheckBox cbBypassSw = new JCheckBox("Vô hiệu hóa Service Worker khi Crawl để PWA luôn đi qua Burp Proxy");
    private final JCheckBox cbCdpSidecar = new JCheckBox("Bật CDP Sidecar (Chrome --remote-debugging-port) cho SPA chặn Proxy");
    private final JSpinner spCdpPort = new JSpinner(new SpinnerNumberModel(9222, 1024, 65535, 1));
    private final JCheckBox cbDangerGuard = new JCheckBox("Bật Danger Guard (chỉ né từ khóa dưới đây)");
    private final JTextField tfDangerKeywords = new JTextField(26);

    private final JSpinner spActionWait = new JSpinner(new SpinnerNumberModel(550, 150, 10000, 50));
    private final JSpinner spMaxActions = new JSpinner(new SpinnerNumberModel(300, 10, 5000, 20));
    private final JSpinner spMaxPages = new JSpinner(new SpinnerNumberModel(500, 10, 10000, 25));
    private final JSpinner spMaxPagination = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));

    private final JPanel formPanel = new JPanel(new GridBagLayout());
    private final JPanel crawlPanel = new JPanel(new GridBagLayout());
    private final JPanel quickBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
    private final JPanel footer = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 4));
    private final JButton btnQuickApply = new JButton("Áp dụng cho tất cả ô chữ");
    private final JButton btnRestoreForm = new JButton("Khôi phục Form về TEXT");
    private final JButton btnApplySave = new JButton("Lưu & Áp dụng cấu hình ngay");
    private final JScrollPane spForm;
    private final JScrollPane spCrawl;
    private final JScrollPane spRuleScroll;
    private final JSplitPane split;

    public FormAndConfigPanel(CrawlConfig config, Consumer<String> logger, Runnable onSaved) {
        this(null, config, logger, onSaved);
    }

    public FormAndConfigPanel(MontoyaApi api, CrawlConfig config, Consumer<String> logger, Runnable onSaved) {
        this.api = api;
        this.config = config;
        this.logger = logger;
        this.onSaved = onSaved;

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        spRuleScroll = new JScrollPane(txtCustomRules);
        spForm = buildFormFillPane();
        spCrawl = buildCrawlConfigPane();

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spForm, spCrawl);
        split.setResizeWeight(0.52);
        split.setOneTouchExpandable(false);
        add(split, BorderLayout.CENTER);

        btnRestoreForm.addActionListener(e -> {
            config.getFormFillProfile().resetDefaults();
            syncFromConfig();
            saveToConfig();
            if (logger != null) {
                logger.accept("Đã khôi phục giá trị tự điền form về mặc định (TEXT).");
            }
        });
        btnApplySave.addActionListener(e -> {
            saveToConfig();
            if (logger != null) {
                logger.accept("Đã lưu cấu hình Form Fill (chữ mặc định: '"
                    + config.getFormFillProfile().get(FormFillProfile.Field.TEXT)
                    + "') & Cài đặt Crawl (Root: '" + config.getRouteRootLabel() + "').");
            }
        });
        footer.add(btnRestoreForm);
        footer.add(btnApplySave);
        add(footer, BorderLayout.SOUTH);

        syncFromConfig();
        applyTheme();
    }

    private JScrollPane buildFormFillPane() {
        formPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        btnQuickApply.addActionListener(e -> {
            String v = tfQuickTextAll.getText();
            for (FormFillProfile.Field f : FormFillProfile.Field.values()) {
                if (f.isTextLike()) {
                    JTextField tf = fieldInputs.get(f);
                    if (tf != null) {
                        tf.setText(v);
                    }
                }
            }
            saveToConfig();
            if (logger != null) {
                logger.accept("Đã đổi toàn bộ trường chữ tự điền thành: '" + v + "'");
            }
        });
        quickBar.add(new JLabel("Chữ mẫu:"));
        quickBar.add(tfQuickTextAll);
        quickBar.add(btnQuickApply);

        int r = 0;
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; gc.weightx = 1.0;
        formPanel.add(quickBar, gc);

        gc.gridwidth = 1;
        for (FormFillProfile.Field f : FormFillProfile.Field.values()) {
            JTextField tf = new JTextField(f.defaultValue, 22);
            fieldInputs.put(f, tf);
            gc.gridx = 0; gc.gridy = r; gc.weightx = 0;
            formPanel.add(new JLabel(f.label + ":"), gc);
            gc.gridx = 1; gc.weightx = 1.0;
            formPanel.add(tf, gc);
            r++;
        }

        cbOverwriteExisting.setOpaque(false);
        cbUploadMockFiles.setOpaque(false);
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2;
        formPanel.add(cbOverwriteExisting, gc);
        gc.gridy = r++;
        formPanel.add(cbUploadMockFiles, gc);

        gc.gridy = r++;
        JLabel lblRules = new JLabel("Quy tắc riêng theo tên trường (từ_khóa = giá_trị hoặc /regex/ = giá_trị):");
        formPanel.add(lblRules, gc);

        gc.gridy = r++; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        spRuleScroll.setPreferredSize(new java.awt.Dimension(300, 120));
        formPanel.add(spRuleScroll, gc);

        JScrollPane sp = new JScrollPane(formPanel);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JScrollPane buildCrawlConfigPane() {
        crawlPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        cbRootLabel.setEditable(true);
        cbAutoFill.setOpaque(false);
        cbDeepCrawl.setOpaque(false);
        cbStrictExcel.setOpaque(false);
        cbIgnoreTrailingSlash.setOpaque(false);
        cbNormalizeIds.setOpaque(false);
        cbRelaxCsp.setOpaque(false);
        cbCaptureJs.setOpaque(false);
        cbBypassSw.setOpaque(false);
        cbCdpSidecar.setOpaque(false);
        cbDangerGuard.setOpaque(false);

        int r = 0;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; crawlPanel.add(new JLabel("Tiền tố gốc (Action Path Root):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; crawlPanel.add(cbRootLabel, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; crawlPanel.add(new JLabel("API Base Prefix (khi Excel bỏ prefix):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; crawlPanel.add(tfApiPrefix, gc); r++;

        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; crawlPanel.add(cbAutoFill, gc);
        gc.gridy = r++; crawlPanel.add(cbDeepCrawl, gc);
        gc.gridy = r++; crawlPanel.add(cbStrictExcel, gc);
        gc.gridy = r++; crawlPanel.add(cbIgnoreTrailingSlash, gc);
        gc.gridy = r++; crawlPanel.add(cbNormalizeIds, gc);
        gc.gridy = r++; crawlPanel.add(cbRelaxCsp, gc);
        gc.gridy = r++; crawlPanel.add(cbCaptureJs, gc);
        gc.gridy = r++; crawlPanel.add(cbBypassSw, gc);
        gc.gridy = r++; crawlPanel.add(cbCdpSidecar, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; crawlPanel.add(new JLabel("CDP Port (--remote-debugging-port):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; crawlPanel.add(spCdpPort, gc); r++;

        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; crawlPanel.add(cbDangerGuard, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; crawlPanel.add(new JLabel("Từ khóa Danger Guard:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; crawlPanel.add(tfDangerKeywords, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; crawlPanel.add(new JLabel("Thời gian chờ sau mỗi click (ms):"), gc);
        gc.gridx = 1; crawlPanel.add(spActionWait, gc); r++;

        gc.gridx = 0; gc.gridy = r; crawlPanel.add(new JLabel("Số thao tác tối đa mỗi trang:"), gc);
        gc.gridx = 1; crawlPanel.add(spMaxActions, gc); r++;

        gc.gridx = 0; gc.gridy = r; crawlPanel.add(new JLabel("Số trang tối đa toàn phiên:"), gc);
        gc.gridx = 1; crawlPanel.add(spMaxPages, gc); r++;

        gc.gridx = 0; gc.gridy = r; crawlPanel.add(new JLabel("Số mẫu tối đa mỗi pattern ?page= / {id}:"), gc);
        gc.gridx = 1; crawlPanel.add(spMaxPagination, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weighty = 1.0; gc.gridwidth = 2;
        crawlPanel.add(new JLabel(" "), gc);

        JScrollPane sp = new JScrollPane(crawlPanel);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    public void syncFromConfig() {
        FormFillProfile profile = config.getFormFillProfile();
        tfQuickTextAll.setText(profile.get(FormFillProfile.Field.TEXT));
        for (FormFillProfile.Field f : FormFillProfile.Field.values()) {
            JTextField tf = fieldInputs.get(f);
            if (tf != null) {
                tf.setText(profile.get(f));
            }
        }
        txtCustomRules.setText(profile.getRulesText());
        cbOverwriteExisting.setSelected(profile.isOverwriteExisting());
        cbUploadMockFiles.setSelected(profile.isUploadFiles());

        cbRootLabel.setSelectedItem(config.getRouteRootLabel());
        tfApiPrefix.setText(config.getApiBasePrefix());
        cbAutoFill.setSelected(config.isAutoFillForms());
        cbDeepCrawl.setSelected(config.isDeepCrawlMode());
        cbStrictExcel.setSelected(config.isStrictExcelOnly());
        cbIgnoreTrailingSlash.setSelected(config.isIgnoreTrailingSlash());
        cbNormalizeIds.setSelected(config.isNormalizeRouteIds());
        cbRelaxCsp.setSelected(config.isRelaxCspWhileCrawling());
        cbCaptureJs.setSelected(config.isCaptureJsEndpoints());
        cbBypassSw.setSelected(config.isBypassServiceWorker());
        cbCdpSidecar.setSelected(config.isCdpSidecarEnabled());
        spCdpPort.setValue(config.getCdpPort());
        cbDangerGuard.setSelected(config.isDangerGuardEnabled());
        tfDangerKeywords.setText(config.getDangerKeywordsCsv());
        spActionWait.setValue(config.getActionWaitMs());
        spMaxActions.setValue(config.getMaxActionsPerPage());
        spMaxPages.setValue(config.getMaxPages());
        spMaxPagination.setValue(config.getMaxPaginationSamples());
    }

    public void saveToConfig() {
        FormFillProfile profile = config.getFormFillProfile();
        for (FormFillProfile.Field f : FormFillProfile.Field.values()) {
            JTextField tf = fieldInputs.get(f);
            if (tf != null) {
                profile.set(f, tf.getText());
            }
        }
        profile.setRulesText(txtCustomRules.getText());
        profile.setOverwriteExisting(cbOverwriteExisting.isSelected());
        profile.setUploadFiles(cbUploadMockFiles.isSelected());

        Object rootObj = cbRootLabel.getEditor() != null
            ? cbRootLabel.getEditor().getItem() : cbRootLabel.getSelectedItem();
        config.setRouteRootLabel(rootObj != null ? String.valueOf(rootObj) : "/");
        config.setApiBasePrefix(tfApiPrefix.getText());
        config.setAutoFillForms(cbAutoFill.isSelected());
        config.setDeepCrawlMode(cbDeepCrawl.isSelected());
        config.setStrictExcelOnly(cbStrictExcel.isSelected());
        config.setIgnoreTrailingSlash(cbIgnoreTrailingSlash.isSelected());
        config.setNormalizeRouteIds(cbNormalizeIds.isSelected());
        config.setRelaxCspWhileCrawling(cbRelaxCsp.isSelected());
        config.setCaptureJsEndpoints(cbCaptureJs.isSelected());
        config.setBypassServiceWorker(cbBypassSw.isSelected());
        config.setCdpSidecarEnabled(cbCdpSidecar.isSelected());
        config.setCdpPort(((Number) spCdpPort.getValue()).intValue());
        config.setDangerGuardEnabled(cbDangerGuard.isSelected());
        config.setDangerKeywordsCsv(tfDangerKeywords.getText());
        config.setActionWaitMs(((Number) spActionWait.getValue()).intValue());
        config.setMaxActionsPerPage(((Number) spMaxActions.getValue()).intValue());
        config.setMaxPages(((Number) spMaxPages.getValue()).intValue());
        config.setMaxPaginationSamples(((Number) spMaxPagination.getValue()).intValue());

        if (onSaved != null) {
            onSaved.run();
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.canvasSurface(dark));
        split.setBackground(AuditTheme.canvasSurface(dark));
        formPanel.setBackground(AuditTheme.panelSurface(dark));
        crawlPanel.setBackground(AuditTheme.panelSurface(dark));
        quickBar.setBackground(AuditTheme.cardSurface(dark));
        AuditTheme.styleTitledBorder(quickBar, "Đổi nhanh chữ cho mọi ô TEXT / Textarea / Search / Bảng", dark);

        txtCustomRules.setFont(AuditTheme.editorFont(api, this));
        txtCustomRules.setBackground(AuditTheme.tableSurface(dark));
        txtCustomRules.setForeground(AuditTheme.textPrimary(dark));

        AuditTheme.styleScrollPane(spForm, dark);
        AuditTheme.styleScrollPane(spCrawl, dark);
        AuditTheme.styleTitledBorder(spForm, "Cấu hình Tự Điền Form, Ngày Tháng & Bảng (mặc định: TEXT)", dark);
        AuditTheme.styleTitledBorder(spCrawl, "Cài đặt Crawl, Service Worker & CDP Sidecar", dark);

        AuditTheme.styleSlimToolbar(footer, dark);
        AuditTheme.stylePlainButton(btnQuickApply, dark);
        AuditTheme.stylePlainButton(btnRestoreForm, dark);
        AuditTheme.stylePrimaryActionButton(btnApplySave, AuditTheme.accentForeground(dark), dark);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (formPanel != null && crawlPanel != null) {
            applyTheme();
        }
    }
}
