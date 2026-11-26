package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.FormFillProfile;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Burp Suite v2026.10.1 Settings Panel:
 * - Left sidebar (~235px): Search box + 3 filter pills (All | Scope | Agent) + borderless category tree
 * - Right workspace: Breadcrumb ("Settings > Category"), section headers (? icon + bold title + 1-line desc),
 *   vertical left-aligned action stacks for rules, and sticky bottom Save / Restore bar
 */
public final class BurpSettingsPanel extends JPanel {

    public static final String CAT_SCOPE = "Target Scope & Host";
    public static final String CAT_AGENT = "Browser Agent & SPA";
    public static final String CAT_FORM = "Form Auto-Fill";
    public static final String CAT_HEADERS = "HTTP Header Rules";
    public static final String CAT_PROTOCOL = "Protocol & CDP";
    public static final String CAT_AUX = "Ngoài Excel, Bị né & JS";

    private static final String[] ALL_CATEGORIES = {
        CAT_SCOPE, CAT_AGENT, CAT_FORM, CAT_HEADERS, CAT_PROTOCOL, CAT_AUX
    };

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final HtmlAgentInjector injector;
    private final Consumer<String> logger;
    private final Runnable onSaved;

    // Left Navigation Sidebar
    private final JPanel leftSidebar = new JPanel(new BorderLayout(0, 6));
    private final JPanel navTopControls = new JPanel();
    private final JTextField tfNavSearch = new JTextField(14);
    private final JPanel navPillsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final BurpComponents.BurpFilterPill pillAll;
    private final BurpComponents.BurpFilterPill pillScope;
    private final BurpComponents.BurpFilterPill pillAgent;
    private String navGroupFilter = "ALL";

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Settings");
    private final JTree navTree = new JTree(new DefaultTreeModel(rootNode));
    private final JScrollPane spNav = new JScrollPane(navTree);

    // Right Breadcrumb + Card Content
    private final JPanel rightWrap = new JPanel(new BorderLayout(0, 0));
    private final JPanel breadcrumbBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel lblBreadcrumb = new JLabel("Settings  >  " + CAT_SCOPE);
    private final JPanel cardHost = new JPanel(new CardLayout());
    private final JSplitPane mainSplit;

    // Scope & Host
    private final JTextField tfHosts = new JTextField(24);
    private final JComboBox<String> cbRootLabel = new JComboBox<>(new String[]{"/", "toppage"});
    private final JTextField tfApiPrefix = new JTextField(22);
    private final JCheckBox cbStrictSeedPath = new JCheckBox("Khóa phạm vi theo nhánh đường dẫn Seed URL (chặn trang gốc '/' và nhánh khác cùng domain)");
    private final JCheckBox cbStrictExcel = new JCheckBox("Strict Excel Mode (chỉ ghi bảng Excel; HTTP History vẫn ghi đủ)");
    private final JCheckBox cbIgnoreTrailingSlash = new JCheckBox("Bỏ qua dấu '/' cuối path khi khớp Excel");
    private final JCheckBox cbNormalizeIds = new JCheckBox("Gộp ID động trong Route URL (/users/123 -> /users/{id})");
    private final JCheckBox cbDangerGuard = new JCheckBox("Bật Danger Guard (chỉ né từ khóa dưới đây)");
    private final JTextField tfDangerKeywords = new JTextField(28);

    // Agent & SPA
    private final JCheckBox cbArmAgent = new JCheckBox("Bơm Agent JS vào trình duyệt (ghi Action Path & tự crawl)");
    private final JCheckBox cbPassive = new JCheckBox("Chế độ Bắt thụ động (chỉ nghe traffic, tắt tự bấm)");
    private final JCheckBox cbAutoFill = new JCheckBox("Bật tự điền Form khi Crawl");
    private final JCheckBox cbDeepCrawl = new JCheckBox("Crawl sâu toàn công nghệ (Shadow DOM, Same-origin Iframe, Lazy Scroll, Select Filter, Bảng Editable)");
    private final JSpinner spActionWait = new JSpinner(new SpinnerNumberModel(550, 150, 10000, 50));
    private final JSpinner spMaxActions = new JSpinner(new SpinnerNumberModel(300, 10, 5000, 20));
    private final JSpinner spMaxPages = new JSpinner(new SpinnerNumberModel(500, 10, 10000, 25));
    private final JSpinner spMaxPagination = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));

    // Form Auto-Fill
    private final JTextField tfQuickTextAll = new JTextField(FormFillProfile.DEFAULT_TEXT, 14);
    private final Map<FormFillProfile.Field, JTextField> fieldInputs = new EnumMap<>(FormFillProfile.Field.class);
    private final JTextArea txtCustomRules = new JTextArea(6, 30);
    private final JCheckBox cbOverwriteExisting = new JCheckBox("Ghi đè cả ô form đã có sẵn giá trị");
    private final JCheckBox cbUploadMockFiles = new JCheckBox("Tạo file giả đính kèm cho <input type='file'>");
    private final JButton btnQuickApply = new JButton("Áp dụng");
    private final JButton btnAddFormRule = new JButton("Add");
    private final JButton btnClearFormRules = new JButton("Clear");

    // Protocol & CDP
    private final JCheckBox cbBypassSw = new JCheckBox("Vô hiệu hóa Service Worker khi Crawl để PWA luôn đi qua Burp Proxy");
    private final JCheckBox cbRelaxCsp = new JCheckBox("Tự nới lỏng CSP header khi đang Crawl để Agent chạy mượt");
    private final JCheckBox cbCaptureJs = new JCheckBox("Quét thụ động file .js tìm Hidden Routes & JS Hints");
    private final JCheckBox cbCdpSidecar = new JCheckBox("Bật CDP Sidecar (Chrome --remote-debugging-port) cho SPA chặn Proxy");
    private final JSpinner spCdpPort = new JSpinner(new SpinnerNumberModel(9222, 1024, 65535, 1));

    // Embedded panels
    private final HeaderFilterPanel headerFilterPanel;
    private final AuxPanel auxPanel;

    private final JPanel footer = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 4));
    private final JButton btnRestoreForm = new JButton("Khôi phục Form về TEXT");
    private final BurpComponents.BurpOrangePillButton btnApplySave;

    private final JPanel scopeCard = new JPanel(new GridBagLayout());
    private final JPanel agentCard = new JPanel(new GridBagLayout());
    private final JPanel formCard = new JPanel(new GridBagLayout());
    private final JPanel protocolCard = new JPanel(new GridBagLayout());
    private final JPanel quickFormBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
    private final JScrollPane spScope;
    private final JScrollPane spAgent;
    private final JScrollPane spForm;
    private final JScrollPane spProtocol;
    private final JScrollPane spRules;
    private final List<JLabel> sectionTitles = new ArrayList<>();
    private final List<JLabel> sectionDescs = new ArrayList<>();
    private final List<JLabel> sectionHelpIcons = new ArrayList<>();

    private String currentCategory = CAT_SCOPE;

    public BurpSettingsPanel(
        MontoyaApi api,
        CrawlConfig config,
        HtmlAgentInjector injector,
        HeaderFilterPanel headerFilterPanel,
        AuxPanel auxPanel,
        Consumer<String> logger,
        Runnable onSaved
    ) {
        this.api = api;
        this.config = config;
        this.injector = injector;
        this.headerFilterPanel = headerFilterPanel;
        this.auxPanel = auxPanel;
        this.logger = logger;
        this.onSaved = onSaved;

        this.pillAll = new BurpComponents.BurpFilterPill(api, "All", true);
        this.pillScope = new BurpComponents.BurpFilterPill(api, "Scope", false);
        this.pillAgent = new BurpComponents.BurpFilterPill(api, "Agent", false);
        this.btnApplySave = new BurpComponents.BurpOrangePillButton(
            api, "Lưu & Áp dụng ngay", BurpIcons.check(Color.WHITE));

        setLayout(new BorderLayout(0, 0));

        buildLeftSidebar();

        spRules = new JScrollPane(txtCustomRules);
        spScope = buildScopePane();
        spAgent = buildAgentPane();
        spForm = buildFormPane();
        spProtocol = buildProtocolPane();

        cardHost.add(spScope, CAT_SCOPE);
        cardHost.add(spAgent, CAT_AGENT);
        cardHost.add(spForm, CAT_FORM);
        cardHost.add(headerFilterPanel, CAT_HEADERS);
        cardHost.add(spProtocol, CAT_PROTOCOL);
        cardHost.add(auxPanel, CAT_AUX);

        breadcrumbBar.add(lblBreadcrumb, BorderLayout.WEST);
        rightWrap.add(breadcrumbBar, BorderLayout.NORTH);
        rightWrap.add(cardHost, BorderLayout.CENTER);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSidebar, rightWrap);
        mainSplit.setDividerLocation(235);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setOneTouchExpandable(false);
        add(mainSplit, BorderLayout.CENTER);

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

        rebuildNavTree(CAT_SCOPE);
        syncFromConfig();
        applyTheme();
    }

    private void buildLeftSidebar() {
        leftSidebar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 6));
        navTopControls.setOpaque(false);
        navTopControls.setLayout(new BoxLayout(navTopControls, BoxLayout.Y_AXIS));

        AuditTheme.styleSearchField(tfNavSearch, "Search");
        tfNavSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        tfNavSearch.setAlignmentX(Component.LEFT_ALIGNMENT);
        tfNavSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuildNavTree(currentCategory); }
            @Override public void removeUpdate(DocumentEvent e) { rebuildNavTree(currentCategory); }
            @Override public void changedUpdate(DocumentEvent e) { rebuildNavTree(currentCategory); }
        });

        pillAll.addActionListener(e -> setNavGroupFilter("ALL"));
        pillScope.addActionListener(e -> setNavGroupFilter("SCOPE"));
        pillAgent.addActionListener(e -> setNavGroupFilter("AGENT"));
        navPillsRow.setOpaque(false);
        navPillsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        navPillsRow.add(pillAll);
        navPillsRow.add(pillScope);
        navPillsRow.add(pillAgent);

        navTopControls.add(tfNavSearch);
        navTopControls.add(Box.createVerticalStrut(6));
        navTopControls.add(navPillsRow);

        navTree.setRootVisible(false);
        navTree.setShowsRootHandles(false);
        navTree.setRowHeight(28);
        navTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        navTree.setCellRenderer(new NavTreeRenderer());
        navTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) navTree.getLastSelectedPathComponent();
            if (node != null && node.isLeaf()) {
                showCategory(String.valueOf(node.getUserObject()));
            }
        });

        leftSidebar.add(navTopControls, BorderLayout.NORTH);
        leftSidebar.add(spNav, BorderLayout.CENTER);
    }

    private void setNavGroupFilter(String group) {
        this.navGroupFilter = group;
        pillAll.setSelected("ALL".equals(group));
        pillScope.setSelected("SCOPE".equals(group));
        pillAgent.setSelected("AGENT".equals(group));
        rebuildNavTree(currentCategory);
    }

    private void rebuildNavTree(String preferredCategory) {
        String q = tfNavSearch.getText() != null ? tfNavSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        rootNode.removeAllChildren();
        int preferredRow = -1;
        int idx = 0;
        for (String cat : ALL_CATEGORIES) {
            if (!matchesNavGroup(cat)) {
                continue;
            }
            if (!q.isEmpty() && !cat.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            rootNode.add(new DefaultMutableTreeNode(cat));
            if (cat.equals(preferredCategory)) {
                preferredRow = idx;
            }
            idx++;
        }
        ((DefaultTreeModel) navTree.getModel()).reload();
        if (rootNode.getChildCount() > 0) {
            int row = preferredRow >= 0 ? preferredRow : 0;
            navTree.setSelectionRow(row);
            DefaultMutableTreeNode sel = (DefaultMutableTreeNode) rootNode.getChildAt(row);
            showCategory(String.valueOf(sel.getUserObject()));
        }
    }

    private boolean matchesNavGroup(String cat) {
        if ("SCOPE".equals(navGroupFilter)) {
            return CAT_SCOPE.equals(cat) || CAT_HEADERS.equals(cat) || CAT_AUX.equals(cat);
        }
        if ("AGENT".equals(navGroupFilter)) {
            return CAT_AGENT.equals(cat) || CAT_FORM.equals(cat) || CAT_PROTOCOL.equals(cat);
        }
        return true;
    }

    private void showCategory(String category) {
        this.currentCategory = category;
        lblBreadcrumb.setText("Settings  >  " + category);
        ((CardLayout) cardHost.getLayout()).show(cardHost, category);
    }

    public void selectCategory(String category) {
        if (!matchesNavGroup(category)) {
            setNavGroupFilter("ALL");
        }
        if (!tfNavSearch.getText().isEmpty()) {
            tfNavSearch.setText("");
        }
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (category.equals(String.valueOf(child.getUserObject()))) {
                navTree.setSelectionRow(i);
                showCategory(category);
                return;
            }
        }
    }

    private JPanel createSectionBanner(String title, String desc) {
        JPanel banner = new JPanel();
        banner.setOpaque(false);
        banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel help = new JLabel();
        help.setToolTipText(desc);
        JLabel lblTitle = new JLabel(title);
        sectionHelpIcons.add(help);
        sectionTitles.add(lblTitle);
        top.add(help);
        top.add(lblTitle);

        JLabel lblDesc = new JLabel(desc);
        lblDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblDesc.setBorder(BorderFactory.createEmptyBorder(2, 22, 6, 0));
        sectionDescs.add(lblDesc);

        banner.add(top);
        banner.add(lblDesc);
        return banner;
    }

    private JScrollPane buildScopePane() {
        scopeCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints gc = baseConstraints();
        cbRootLabel.setEditable(true);
        tfHosts.setToolTipText("Hỗ trợ domain hoặc URL kèm đường dẫn nhánh (ví dụ: vnexpress.net/the-gioi hoặc https://vnexpress.net/the-gioi)");
        cbStrictSeedPath.setOpaque(false);
        cbStrictExcel.setOpaque(false);
        cbIgnoreTrailingSlash.setOpaque(false);
        cbNormalizeIds.setOpaque(false);
        cbDangerGuard.setOpaque(false);

        int r = 0;
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; gc.weightx = 1.0;
        scopeCard.add(createSectionBanner(
            "Target hosts & URL normalization",
            "Define allowed target hostnames, root labels, Excel matching rules, and Danger Guard exclusions."), gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; scopeCard.add(new JLabel("Target Host(s):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; scopeCard.add(tfHosts, gc); r++;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; scopeCard.add(new JLabel("Action Path Root:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; scopeCard.add(cbRootLabel, gc); r++;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; scopeCard.add(new JLabel("API Base Prefix:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; scopeCard.add(tfApiPrefix, gc); r++;

        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; scopeCard.add(cbStrictSeedPath, gc);
        gc.gridy = r++; scopeCard.add(cbStrictExcel, gc);
        gc.gridy = r++; scopeCard.add(cbIgnoreTrailingSlash, gc);
        gc.gridy = r++; scopeCard.add(cbNormalizeIds, gc);
        gc.gridy = r++; scopeCard.add(cbDangerGuard, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; scopeCard.add(new JLabel("Danger Guard Keywords:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; scopeCard.add(tfDangerKeywords, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weighty = 1.0; gc.gridwidth = 2;
        scopeCard.add(new JLabel(" "), gc);
        JScrollPane sp = new JScrollPane(scopeCard);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JScrollPane buildAgentPane() {
        agentCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints gc = baseConstraints();
        cbArmAgent.setOpaque(false);
        cbPassive.setOpaque(false);
        cbAutoFill.setOpaque(false);
        cbDeepCrawl.setOpaque(false);

        int r = 0;
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; gc.weightx = 1.0;
        agentCard.add(createSectionBanner(
            "Browser Agent & SPA crawling limits",
            "Control JS agent injection, passive capture mode, deep Shadow DOM/iframe traversal, and crawl caps."), gc);

        gc.gridy = r++; agentCard.add(cbArmAgent, gc);
        gc.gridy = r++; agentCard.add(cbPassive, gc);
        gc.gridy = r++; agentCard.add(cbAutoFill, gc);
        gc.gridy = r++; agentCard.add(cbDeepCrawl, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; agentCard.add(new JLabel("Wait after each click (ms):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; agentCard.add(spActionWait, gc); r++;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; agentCard.add(new JLabel("Max actions per page:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; agentCard.add(spMaxActions, gc); r++;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; agentCard.add(new JLabel("Max pages per session:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; agentCard.add(spMaxPages, gc); r++;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; agentCard.add(new JLabel("Max samples per pagination pattern:"), gc);
        gc.gridx = 1; gc.weightx = 1.0; agentCard.add(spMaxPagination, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weighty = 1.0; gc.gridwidth = 2;
        agentCard.add(new JLabel(" "), gc);
        JScrollPane sp = new JScrollPane(agentCard);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JScrollPane buildFormPane() {
        formCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints gc = baseConstraints();

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
        quickFormBar.add(new JLabel("Quick fill text:"));
        quickFormBar.add(tfQuickTextAll);
        quickFormBar.add(btnQuickApply);

        int r = 0;
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; gc.weightx = 1.0;
        formCard.add(createSectionBanner(
            "Form auto-fill values & custom field rules",
            "Configure default values injected into inputs, date pickers, editable cells, and regex field overrides."), gc);

        gc.gridy = r++;
        formCard.add(quickFormBar, gc);

        gc.gridwidth = 1;
        for (FormFillProfile.Field f : FormFillProfile.Field.values()) {
            JTextField tf = new JTextField(f.defaultValue, 22);
            fieldInputs.put(f, tf);
            gc.gridx = 0; gc.gridy = r; gc.weightx = 0;
            formCard.add(new JLabel(f.label + ":"), gc);
            gc.gridx = 1; gc.weightx = 1.0;
            formCard.add(tf, gc);
            r++;
        }

        cbOverwriteExisting.setOpaque(false);
        cbUploadMockFiles.setOpaque(false);
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2;
        formCard.add(cbOverwriteExisting, gc);
        gc.gridy = r++;
        formCard.add(cbUploadMockFiles, gc);
        gc.gridy = r++;
        formCard.add(new JLabel("Custom field overrides (keyword = value or /regex/ = value):"), gc);

        btnAddFormRule.addActionListener(e -> promptAddFormRule());
        btnClearFormRules.addActionListener(e -> txtCustomRules.setText(""));
        Dimension bDim = new Dimension(76, 26);
        btnAddFormRule.setPreferredSize(bDim);
        btnAddFormRule.setMaximumSize(bDim);
        btnClearFormRules.setPreferredSize(bDim);
        btnClearFormRules.setMaximumSize(bDim);

        JPanel leftRuleBtns = new JPanel();
        leftRuleBtns.setOpaque(false);
        leftRuleBtns.setLayout(new BoxLayout(leftRuleBtns, BoxLayout.Y_AXIS));
        leftRuleBtns.add(btnAddFormRule);
        leftRuleBtns.add(Box.createVerticalStrut(6));
        leftRuleBtns.add(btnClearFormRules);
        leftRuleBtns.add(Box.createVerticalGlue());

        JPanel ruleWrap = new JPanel(new BorderLayout(8, 0));
        ruleWrap.setOpaque(false);
        ruleWrap.add(leftRuleBtns, BorderLayout.WEST);
        ruleWrap.add(spRules, BorderLayout.CENTER);

        gc.gridy = r++; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        spRules.setPreferredSize(new Dimension(300, 120));
        formCard.add(ruleWrap, gc);

        JScrollPane sp = new JScrollPane(formCard);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private void promptAddFormRule() {
        JTextField tfKey = new JTextField(16);
        JTextField tfVal = new JTextField(FormFillProfile.DEFAULT_TEXT, 16);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = baseConstraints();
        g.gridx = 0; g.gridy = 0; g.weightx = 0; p.add(new JLabel("Field / Regex:"), g);
        g.gridx = 1; g.weightx = 1; p.add(tfKey, g);
        g.gridx = 0; g.gridy = 1; g.weightx = 0; p.add(new JLabel("Value:"), g);
        g.gridx = 1; g.weightx = 1; p.add(tfVal, g);
        int res = JOptionPane.showConfirmDialog(this, p, "Add Form Rule", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION && !tfKey.getText().trim().isEmpty()) {
            String cur = txtCustomRules.getText();
            String line = tfKey.getText().trim() + " = " + tfVal.getText();
            txtCustomRules.setText(cur.isEmpty() ? line : (cur.endsWith("\n") ? (cur + line) : (cur + "\n" + line)));
        }
    }

    private JScrollPane buildProtocolPane() {
        protocolCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints gc = baseConstraints();
        cbBypassSw.setOpaque(false);
        cbRelaxCsp.setOpaque(false);
        cbCaptureJs.setOpaque(false);
        cbCdpSidecar.setOpaque(false);

        int r = 0;
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2; gc.weightx = 1.0;
        protocolCard.add(createSectionBanner(
            "Protocol, Service Worker & CDP Sidecar",
            "Control proxy interception guarantees for PWAs, CSP header relaxation, passive JS scanning, and Chrome CDP bridge."), gc);

        gc.gridy = r++; protocolCard.add(cbBypassSw, gc);
        gc.gridy = r++; protocolCard.add(cbRelaxCsp, gc);
        gc.gridy = r++; protocolCard.add(cbCaptureJs, gc);
        gc.gridy = r++; protocolCard.add(cbCdpSidecar, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; protocolCard.add(new JLabel("CDP Port (--remote-debugging-port):"), gc);
        gc.gridx = 1; gc.weightx = 1.0; protocolCard.add(spCdpPort, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.weighty = 1.0; gc.gridwidth = 2;
        protocolCard.add(new JLabel(" "), gc);
        JScrollPane sp = new JScrollPane(protocolCard);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        return gc;
    }

    public void syncFromConfig() {
        tfHosts.setText(config.getAllowedHostsCsv());
        Object rootObj = config.getRouteRootLabel();
        cbRootLabel.setSelectedItem(rootObj);
        tfApiPrefix.setText(config.getApiBasePrefix());
        cbStrictSeedPath.setSelected(config.isStrictSeedPathScope());
        cbStrictExcel.setSelected(config.isStrictExcelOnly());
        cbIgnoreTrailingSlash.setSelected(config.isIgnoreTrailingSlash());
        cbNormalizeIds.setSelected(config.isNormalizeRouteIds());
        cbDangerGuard.setSelected(config.isDangerGuardEnabled());
        tfDangerKeywords.setText(config.getDangerKeywordsCsv());

        cbArmAgent.setSelected(injector == null || injector.isAgentArmed());
        cbPassive.setSelected(injector != null && injector.isPassiveCapture());
        cbAutoFill.setSelected(config.isAutoFillForms());
        cbDeepCrawl.setSelected(config.isDeepCrawlMode());
        spActionWait.setValue(config.getActionWaitMs());
        spMaxActions.setValue(config.getMaxActionsPerPage());
        spMaxPages.setValue(config.getMaxPages());
        spMaxPagination.setValue(config.getMaxPaginationSamples());

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

        cbBypassSw.setSelected(config.isBypassServiceWorker());
        cbRelaxCsp.setSelected(config.isRelaxCspWhileCrawling());
        cbCaptureJs.setSelected(config.isCaptureJsEndpoints());
        cbCdpSidecar.setSelected(config.isCdpSidecarEnabled());
        spCdpPort.setValue(config.getCdpPort());
    }

    public void saveToConfig() {
        config.setAllowedHostsCsv(tfHosts.getText());
        Object rootObj = cbRootLabel.getEditor() != null
            ? cbRootLabel.getEditor().getItem() : cbRootLabel.getSelectedItem();
        config.setRouteRootLabel(rootObj != null ? String.valueOf(rootObj) : "/");
        config.setApiBasePrefix(tfApiPrefix.getText());
        config.setStrictSeedPathScope(cbStrictSeedPath.isSelected());
        config.setStrictExcelOnly(cbStrictExcel.isSelected());
        config.setIgnoreTrailingSlash(cbIgnoreTrailingSlash.isSelected());
        config.setNormalizeRouteIds(cbNormalizeIds.isSelected());
        config.setDangerGuardEnabled(cbDangerGuard.isSelected());
        config.setDangerKeywordsCsv(tfDangerKeywords.getText());

        if (injector != null) {
            injector.setAgentArmed(cbArmAgent.isSelected());
            injector.setPassiveCapture(cbPassive.isSelected());
        }
        config.setAutoFillForms(cbAutoFill.isSelected());
        config.setDeepCrawlMode(cbDeepCrawl.isSelected());
        config.setActionWaitMs(((Number) spActionWait.getValue()).intValue());
        config.setMaxActionsPerPage(((Number) spMaxActions.getValue()).intValue());
        config.setMaxPages(((Number) spMaxPages.getValue()).intValue());
        config.setMaxPaginationSamples(((Number) spMaxPagination.getValue()).intValue());

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

        config.setBypassServiceWorker(cbBypassSw.isSelected());
        config.setRelaxCspWhileCrawling(cbRelaxCsp.isSelected());
        config.setCaptureJsEndpoints(cbCaptureJs.isSelected());
        config.setCdpSidecarEnabled(cbCdpSidecar.isSelected());
        config.setCdpPort(((Number) spCdpPort.getValue()).intValue());

        if (onSaved != null) {
            onSaved.run();
        }
    }

    public void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        setBackground(AuditTheme.canvasSurface(dark));
        mainSplit.setBackground(AuditTheme.canvasSurface(dark));

        leftSidebar.setBackground(AuditTheme.panelSurface(dark));
        navTree.setBackground(AuditTheme.panelSurface(dark));
        navTree.setFont(AuditTheme.displayFont(api, this));
        spNav.setBorder(BorderFactory.createEmptyBorder());
        spNav.getViewport().setBackground(AuditTheme.panelSurface(dark));

        AuditTheme.styleSlimToolbar(breadcrumbBar, dark);
        breadcrumbBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AuditTheme.borderColor(dark)),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        Font base = AuditTheme.displayFont(api, this);
        lblBreadcrumb.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.0f));
        lblBreadcrumb.setForeground(AuditTheme.textPrimary(dark));

        for (JLabel h : sectionHelpIcons) {
            h.setIcon(BurpIcons.helpCircle(AuditTheme.mutedForeground(dark)));
        }
        for (JLabel t : sectionTitles) {
            t.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.0f));
            t.setForeground(AuditTheme.textPrimary(dark));
        }
        for (JLabel d : sectionDescs) {
            d.setFont(base.deriveFont(Math.max(11f, base.getSize2D() - 0.5f)));
            d.setForeground(AuditTheme.mutedForeground(dark));
        }

        scopeCard.setBackground(AuditTheme.panelSurface(dark));
        agentCard.setBackground(AuditTheme.panelSurface(dark));
        formCard.setBackground(AuditTheme.panelSurface(dark));
        protocolCard.setBackground(AuditTheme.panelSurface(dark));
        quickFormBar.setBackground(AuditTheme.cardSurface(dark));
        quickFormBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AuditTheme.borderColor(dark), 1, true),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        txtCustomRules.setFont(AuditTheme.editorFont(api, this));
        txtCustomRules.setBackground(AuditTheme.tableSurface(dark));
        txtCustomRules.setForeground(AuditTheme.textPrimary(dark));

        spScope.setBorder(BorderFactory.createEmptyBorder());
        spAgent.setBorder(BorderFactory.createEmptyBorder());
        spForm.setBorder(BorderFactory.createEmptyBorder());
        spProtocol.setBorder(BorderFactory.createEmptyBorder());
        AuditTheme.styleScrollPane(spRules, dark);

        AuditTheme.styleSlimToolbar(footer, dark);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AuditTheme.borderColor(dark)),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        AuditTheme.stylePlainButton(btnQuickApply, dark);
        AuditTheme.stylePlainButton(btnAddFormRule, dark);
        AuditTheme.stylePlainButton(btnClearFormRules, dark);
        AuditTheme.stylePlainButton(btnRestoreForm, dark);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (navTree != null && scopeCard != null) {
            applyTheme();
        }
    }

    private final class NavTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus
        ) {
            Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            boolean dark = AuditTheme.isDark(api, tree);
            setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            setBackgroundNonSelectionColor(AuditTheme.panelSurface(dark));
            setBackgroundSelectionColor(AuditTheme.burpSelectionBackground(dark));
            setTextSelectionColor(AuditTheme.burpSelectionForeground(dark));
            setTextNonSelectionColor(AuditTheme.textPrimary(dark));
            setFont(AuditTheme.displayFont(api, tree).deriveFont(sel ? Font.BOLD : Font.PLAIN));
            setIcon(null);
            return c;
        }
    }
}
