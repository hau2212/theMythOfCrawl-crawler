package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ReportExporter;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.SeedDiscoveryHelper;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Burp Suite Tab UI for theMythOfCrawl Firefox + Excel Auto-Crawler.
 * Designed to stay clean and uncluttered: primary actions sit at the top bar,
 * secondary crawl tunables open via Settings modal, and tables support right-click context actions.
 */
public final class CrawlerTabPanel extends JPanel {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private volatile ExcelSpecMatcher matcher;

    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Timer refreshTimer;
    private File lastDirectory;

    // Top controls
    private final JLabel lblExcelStatus = new JLabel("Chưa nạp file Excel (.xlsx)");
    private final JTextField txtHosts = new JTextField(20);
    private final JTextField txtSeedUrl = new JTextField(22);
    private final JButton btnStart = new JButton("Bắt đầu Crawl");
    private final JButton btnPause = new JButton("Tạm dừng");
    private final JButton btnStop = new JButton("Dừng");
    private final JCheckBox chkArmAgent = new JCheckBox("Bơm Agent vào trình duyệt", true);
    private final JCheckBox chkPassive = new JCheckBox("Bắt thụ động", false);

    // Status strip
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel lblStats = new JLabel("Matched: 0/0 | Missing: 0 | Diff Notes: 0 | Pages: 0 | Queue: 0");
    private final JLabel lblCurrentAction = new JLabel("Sẵn sàng - Nhập Host(s), mở Chrome/Firefox (qua Burp Proxy) tới trang đích rồi bấm Bắt đầu Crawl.");

    // Tab 1: Excel Checklist
    private final CoverageTableModel coverageModel = new CoverageTableModel();
    private final JTable tblCoverage = new JTable(coverageModel);
    private final JComboBox<String> cbStatusFilter = new JComboBox<>(
        new String[]{"Tất cả (ALL)", "Đã lấy (MATCHED)", "Còn thiếu (MISSING)", "Khác tham số (!)"});
    private final JTextField txtSearch = new JTextField(22);

    // Tab 2: Hierarchy Tree
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("/");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree actionTree = new JTree(treeModel);

    // Tab 3: Skipped Danger & JS Hints
    private final CoverageTableModel.SkippedActionTableModel skippedModel =
        new CoverageTableModel.SkippedActionTableModel();
    private final JTable tblSkipped = new JTable(skippedModel);
    private final DefaultListModel<String> jsListModel = new DefaultListModel<>();
    private final JList<String> lstJsEndpoints = new JList<>(jsListModel);

    // Tab 4: Extra Captures
    private final CoverageTableModel.ExtraCaptureTableModel extraModel =
        new CoverageTableModel.ExtraCaptureTableModel();
    private final JTable tblExtras = new JTable(extraModel);

    // Tab 5: Log
    private final JTextArea txtLog = new JTextArea();

    // Bottom Inspector
    private final JLabel lblSelectedSummary = new JLabel("Chọn một dòng API để xem chi tiết Request / Response và Action Path");
    private final JComboBox<VariantItem> cbVariant = new JComboBox<>();
    private final JButton btnSendRepeater = new JButton("Send to Repeater");
    private final JTextArea txtNotesBanner = new JTextArea(2, 40);
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;

    private final JTabbedPane mainTabs = new JTabbedPane();
    private boolean updatingVariants = false;
    private int lastTreeCaptureCount = -1;

    public CrawlerTabPanel(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExcelSpecMatcher initialMatcher
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.injector = injector;
        this.matcher = initialMatcher != null ? initialMatcher : ExcelSpecMatcher.empty();

        this.requestEditor = api != null
            ? api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY) : null;
        this.responseEditor = api != null
            ? api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY) : null;

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(buildMainSplitPane(), BorderLayout.CENTER);

        // Debounced UI refresh + live Firefox status ticker
        this.refreshTimer = new Timer(400, e -> {
            if (refreshPending.getAndSet(false) || state.getStatus() == CrawlState.Status.RUNNING) {
                refreshAllViewsNow();
            }
        });
        this.refreshTimer.start();

        refreshButtonsState();
        appendLog("theMythOfCrawl Firefox + Excel Crawler sẵn sàng. Hãy nạp file Excel (.xlsx) trước khi Crawl.");
    }

    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    public void scheduleUiRefresh() {
        refreshPending.set(true);
    }

    public void appendLog(String msg) {
        if (msg == null) {
            return;
        }
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        Runnable r = () -> {
            if (txtLog.getLineCount() > 1500) {
                try {
                    int end = txtLog.getLineEndOffset(400);
                    txtLog.replaceRange("", 0, end);
                } catch (Exception ignored) {
                }
            }
            txtLog.append("[" + ts + "] " + msg + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private JPanel buildHeaderPanel() {
        JPanel container = new JPanel(new BorderLayout(4, 4));

        // Row 1: Primary Workflow Controls
        JPanel row1 = new JPanel(new GridBagLayout());
        row1.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        JButton btnImport = new JButton("Import Excel (.xlsx/.csv)");
        btnImport.addActionListener(e -> onImportExcel());

        JButton btnSettings = new JButton("Cài đặt...");
        btnSettings.addActionListener(e -> onOpenSettings());

        JButton btnExport = new JButton("Xuất báo cáo ▼");
        btnExport.addActionListener(e -> showExportMenu(btnExport));

        JButton btnReset = new JButton("Reset kết quả");
        btnReset.addActionListener(e -> onResetCaptures());

        txtHosts.setToolTipText("Ví dụ: app.example.com, *.example.com hoặc * để bắt mọi host qua Burp Proxy (Chrome/Firefox)");
        txtHosts.getDocument().addDocumentListener(new DocumentListener() {
            private void sync() { config.setAllowedHostsCsv(txtHosts.getText()); }
            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
        });
        txtSeedUrl.setToolTipText("Tùy chọn: URL trang bắt đầu (ví dụ https://app.example.com/dashboard). Nếu bỏ trống, trình duyệt (Chrome/Firefox) sẽ tự crawl ngay trang bạn đang mở.");

        btnStart.addActionListener(e -> onStartCrawl());
        btnPause.addActionListener(e -> onTogglePause());
        btnStop.addActionListener(e -> onStopCrawl());

        chkArmAgent.setToolTipText("Bơm /__myth_crawl/agent.js vào trang HTML của Target Host qua Burp Proxy (hỗ trợ Chrome, Burp Browser, Edge, Firefox).");
        chkArmAgent.addActionListener(e -> {
            if (injector != null) {
                injector.setAgentArmed(chkArmAgent.isSelected());
                appendLog(chkArmAgent.isSelected()
                    ? "Đã bật bơm Browser Crawler Agent (Reload tab Chrome/Firefox để hiện badge góc phải dưới)."
                    : "Đã tắt bơm Browser Agent.");
            }
        });

        chkPassive.setToolTipText("Ngay cả khi chưa bấm Start Crawl, vẫn tự động match traffic lướt tay trên trình duyệt theo file Excel.");
        chkPassive.addActionListener(e -> {
            if (injector != null) {
                injector.setPassiveCapture(chkPassive.isSelected());
                if (chkPassive.isSelected() && config.getAllowedHostsSet().isEmpty()) {
                    appendLog("Lưu ý: Ô Host(s) đang trống - hãy nhập domain đích (hoặc * để test) thì Bắt thụ động mới chạy!");
                }
            }
        });

        // Sub-row A: File + Target + Seed
        int col = 0;
        gc.gridx = col++; gc.gridy = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        row1.add(btnImport, gc);

        gc.gridx = col++; gc.weightx = 0.25; gc.fill = GridBagConstraints.HORIZONTAL;
        lblExcelStatus.setFont(lblExcelStatus.getFont().deriveFont(Font.BOLD));
        row1.add(lblExcelStatus, gc);

        gc.gridx = col++; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        row1.add(new JLabel("Host(s):"), gc);

        gc.gridx = col++; gc.weightx = 0.35; gc.fill = GridBagConstraints.HORIZONTAL;
        row1.add(txtHosts, gc);

        gc.gridx = col++; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        row1.add(new JLabel("Seed URL:"), gc);

        gc.gridx = col++; gc.weightx = 0.4; gc.fill = GridBagConstraints.HORIZONTAL;
        row1.add(txtSeedUrl, gc);

        // Sub-row B: Crawl Controls & Tools
        JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionsBar.add(btnStart);
        actionsBar.add(btnPause);
        actionsBar.add(btnStop);
        actionsBar.add(btnReset);
        actionsBar.add(Box.createHorizontalStrut(8));
        actionsBar.add(chkArmAgent);
        actionsBar.add(chkPassive);
        actionsBar.add(Box.createHorizontalStrut(8));
        actionsBar.add(btnSettings);
        actionsBar.add(btnExport);

        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = col; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        row1.add(actionsBar, gc);

        container.add(row1, BorderLayout.NORTH);

        // Row 2: Live Progress & Current Action Strip
        JPanel row2 = new JPanel(new BorderLayout(8, 4));
        row2.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(200, 18));

        JPanel leftStats = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftStats.add(progressBar);
        lblStats.setFont(lblStats.getFont().deriveFont(Font.BOLD));
        leftStats.add(lblStats);

        lblCurrentAction.setForeground(new Color(40, 110, 175));
        row2.add(leftStats, BorderLayout.WEST);
        row2.add(lblCurrentAction, BorderLayout.CENTER);

        container.add(row2, BorderLayout.SOUTH);
        return container;
    }

    private JSplitPane buildMainSplitPane() {
        mainTabs.addTab("Tiến độ Excel (STT)", buildExcelCoverageTab());
        mainTabs.addTab("Cây Phân cấp (/ > Page > Button)", buildHierarchyTreeTab());
        mainTabs.addTab("Nút bị né & Gợi ý JS", buildSkippedAndJsTab());
        mainTabs.addTab("Ngoài Excel", buildExtraCapturesTab());
        mainTabs.addTab("Log", buildLogTab());

        JPanel bottomInspector = buildInspectorPanel();

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainTabs, bottomInspector);
        mainSplit.setResizeWeight(0.56);
        mainSplit.setOneTouchExpandable(true);
        return mainSplit;
    }

    private JPanel buildExcelCoverageTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        filterBar.add(new JLabel("Lọc:"));
        filterBar.add(cbStatusFilter);
        filterBar.add(new JLabel("Tìm kiếm:"));
        filterBar.add(txtSearch);

        cbStatusFilter.addActionListener(e -> applyCoverageFilter());
        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyCoverageFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyCoverageFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyCoverageFilter(); }
        });

        tblCoverage.setAutoCreateRowSorter(true);
        tblCoverage.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblCoverage.setRowHeight(22);
        tblCoverage.setDefaultRenderer(Object.class, new CoverageTableModel.CoverageCellRenderer());

        // Reasonable column widths
        int[] widths = {55, 95, 70, 260, 280, 75, 55, 280};
        for (int i = 0; i < widths.length; i++) {
            tblCoverage.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        tblCoverage.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onCoverageSelectionChanged();
            }
        });

        tblCoverage.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = tblCoverage.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    tblCoverage.setRowSelectionInterval(row, row);
                }
                ApiSpecEntry entry = getSelectedSpecEntry();
                if (entry != null) {
                    buildCoveragePopup(entry).show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        panel.add(filterBar, BorderLayout.NORTH);
        panel.add(new JScrollPane(tblCoverage), BorderLayout.CENTER);
        return panel;
    }

    private JPopupMenu buildCoveragePopup(ApiSpecEntry entry) {
        JPopupMenu menu = new JPopupMenu();
        CapturedApiEntry cap = getSelectedOrPrimaryCapture(entry);

        JMenuItem miRepeater = new JMenuItem("Send selected to Repeater");
        miRepeater.setEnabled(cap != null);
        miRepeater.addActionListener(e -> sendToRepeater(cap, "STT " + entry.stt + " " + entry.displayMethod));
        menu.add(miRepeater);

        if (!entry.getAlternateVariants().isEmpty()) {
            JMenuItem miAllVars = new JMenuItem("Send ALL variants (" + entry.getAllStoredCaptures().size() + ") to Repeater");
            miAllVars.addActionListener(e -> {
                int idx = 1;
                for (CapturedApiEntry v : entry.getAllStoredCaptures()) {
                    sendToRepeater(v, "STT " + entry.stt + " #" + idx++);
                }
            });
            menu.add(miAllVars);
        }

        JMenuItem miCopyAction = new JMenuItem("Copy Action Path");
        miCopyAction.setEnabled(cap != null);
        miCopyAction.addActionListener(e -> copyToClipboard(cap != null ? cap.actionPath : ""));
        menu.add(miCopyAction);

        JMenuItem miCopyUrl = new JMenuItem("Copy Matched URL");
        miCopyUrl.setEnabled(cap != null);
        miCopyUrl.addActionListener(e -> copyToClipboard(cap != null ? cap.fullUrl : ""));
        menu.add(miCopyUrl);

        JMenuItem miCopyPath = new JMenuItem("Copy Excel Path");
        miCopyPath.addActionListener(e -> copyToClipboard(entry.rawSpecPath));
        menu.add(miCopyPath);

        return menu;
    }

    private JPanel buildHierarchyTreeTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton btnExpand = new JButton("Mở rộng hết");
        JButton btnCollapse = new JButton("Thu gọn");
        JButton btnRefresh = new JButton("Làm mới cây");
        btnExpand.addActionListener(e -> expandAllTreeNodes(true));
        btnCollapse.addActionListener(e -> expandAllTreeNodes(false));
        btnRefresh.addActionListener(e -> {
            lastTreeCaptureCount = -1;
            rebuildHierarchyTree();
        });
        top.add(btnExpand);
        top.add(btnCollapse);
        top.add(btnRefresh);
        top.add(new JLabel("(Đặt tên chuẩn UI gốc, ví dụ: / > contact > button 'Send')"));

        actionTree.addTreeSelectionListener(e -> {
            Object comp = actionTree.getLastSelectedPathComponent();
            if (comp instanceof DefaultMutableTreeNode) {
                Object userObj = ((DefaultMutableTreeNode) comp).getUserObject();
                if (userObj instanceof TreeCaptureNode) {
                    showCaptureInInspector(((TreeCaptureNode) userObj).specEntry, ((TreeCaptureNode) userObj).capture);
                }
            }
        });

        actionTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                TreePath path = actionTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    actionTree.setSelectionPath(path);
                    Object obj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (obj instanceof TreeCaptureNode) {
                        TreeCaptureNode leaf = (TreeCaptureNode) obj;
                        JPopupMenu m = new JPopupMenu();
                        JMenuItem miRep = new JMenuItem("Send to Repeater");
                        miRep.addActionListener(ae -> sendToRepeater(leaf.capture, leaf.capture.stt.isEmpty()
                            ? leaf.capture.method : ("STT " + leaf.capture.stt)));
                        JMenuItem miCopy = new JMenuItem("Copy Full Action Path");
                        miCopy.addActionListener(ae -> copyToClipboard(leaf.capture.actionPath));
                        m.add(miRep);
                        m.add(miCopy);
                        m.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });

        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(actionTree), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSkippedAndJsTab() {
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setBorder(BorderFactory.createTitledBorder("Nút bị Danger Guard né (bảo vệ session & dữ liệu)"));
        tblSkipped.setRowHeight(21);
        left.add(new JScrollPane(tblSkipped), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.setBorder(BorderFactory.createTitledBorder("Routes & API gợi ý từ file JS (.js static scan)"));
        right.add(new JScrollPane(lstJsEndpoints), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(split, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildExtraCapturesTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        JLabel hint = new JLabel(" Chỉ lưu khi tắt 'Strict Excel' trong Cài đặt (hoặc chưa nạp file Excel). Right-click -> Send to Repeater.");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tblExtras.setRowHeight(21);
        tblExtras.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int r = tblExtras.getSelectedRow();
                if (r >= 0) {
                    CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(r));
                    if (cap != null) {
                        showCaptureInInspector(null, cap);
                    }
                }
            }
        });
        tblExtras.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = tblExtras.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    tblExtras.setRowSelectionInterval(row, row);
                    CapturedApiEntry cap = extraModel.getRow(tblExtras.convertRowIndexToModel(row));
                    if (cap != null) {
                        JPopupMenu m = new JPopupMenu();
                        JMenuItem mi = new JMenuItem("Send to Repeater");
                        mi.addActionListener(ae -> sendToRepeater(cap, cap.method));
                        m.add(mi);
                        m.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
        p.add(hint, BorderLayout.NORTH);
        p.add(new JScrollPane(tblExtras), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildLogTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        txtLog.setEditable(false);
        txtLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnClear = new JButton("Xóa Log");
        btnClear.addActionListener(e -> txtLog.setText(""));
        top.add(btnClear);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildInspectorPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Chi tiết API & Biến thể Tham số"));

        JPanel top = new JPanel(new BorderLayout(6, 4));
        lblSelectedSummary.setFont(lblSelectedSummary.getFont().deriveFont(Font.BOLD));
        top.add(lblSelectedSummary, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(new JLabel("Phiên bản:"));
        cbVariant.setPreferredSize(new Dimension(280, 24));
        cbVariant.addActionListener(e -> {
            if (!updatingVariants) {
                VariantItem item = (VariantItem) cbVariant.getSelectedItem();
                if (item != null && item.capture != null) {
                    renderHttpMessages(item.specEntry, item.capture);
                }
            }
        });
        btnSendRepeater.setEnabled(false);
        btnSendRepeater.addActionListener(e -> {
            VariantItem item = (VariantItem) cbVariant.getSelectedItem();
            if (item != null && item.capture != null) {
                String title = item.specEntry != null
                    ? ("STT " + item.specEntry.stt + " " + item.capture.method)
                    : item.capture.method;
                sendToRepeater(item.capture, title);
            }
        });
        right.add(cbVariant);
        right.add(btnSendRepeater);
        top.add(right, BorderLayout.EAST);

        txtNotesBanner.setEditable(false);
        txtNotesBanner.setLineWrap(true);
        txtNotesBanner.setWrapStyleWord(true);
        txtNotesBanner.setBackground(p.getBackground());
        txtNotesBanner.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        JPanel northWrap = new JPanel(new BorderLayout(2, 2));
        northWrap.add(top, BorderLayout.NORTH);
        northWrap.add(txtNotesBanner, BorderLayout.SOUTH);
        p.add(northWrap, BorderLayout.NORTH);

        if (requestEditor != null && responseEditor != null) {
            JSplitPane msgSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, requestEditor.uiComponent(), responseEditor.uiComponent());
            msgSplit.setResizeWeight(0.5);
            p.add(msgSplit, BorderLayout.CENTER);
        } else {
            p.add(new JLabel("Montoya HTTP Editor chỉ hiển thị khi chạy trong Burp Suite."), BorderLayout.CENTER);
        }
        return p;
    }

    private void onImportExcel() {
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setDialogTitle("Chọn file Excel (.xlsx) hoặc CSV danh sách API");
        fc.setFileFilter(new FileNameExtensionFilter("Excel / CSV (*.xlsx, *.xlsm, *.csv, *.tsv)", "xlsx", "xlsm", "csv", "tsv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = fc.getSelectedFile();
        lastDirectory = file.getParentFile();

        try {
            SimpleXlsxReader.WorkbookData wb = SimpleXlsxReader.readFile(file);
            if (wb.getSheetNames().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Không tìm thấy Sheet dữ liệu nào trong file.", "File rỗng", JOptionPane.WARNING_MESSAGE);
                return;
            }
            ExcelColumnMapperDialog.MappingResult mapping =
                ExcelColumnMapperDialog.showDialog(this, file.getName(), wb);
            if (mapping == null) {
                return;
            }

            List<List<String>> rows = wb.getRows(mapping.sheetName);
            ExcelSpecMatcher newMatcher = ExcelSpecMatcher.buildFromRows(
                file.getName(),
                rows,
                mapping.headerRowIndex,
                mapping.sttCol,
                mapping.methodCol,
                mapping.pathCol,
                config.isIgnoreTrailingSlash());

            if (!newMatcher.isLoaded()) {
                JOptionPane.showMessageDialog(this,
                    "Không đọc được dòng API hợp lệ nào từ cột đã chọn. Kiểm tra lại dòng tiêu đề & cột Path API.",
                    "Không có API", JOptionPane.WARNING_MESSAGE);
                return;
            }

            this.matcher = newMatcher;
            if (injector != null) {
                injector.setMatcher(newMatcher);
            }
            coverageModel.setMatcher(newMatcher);
            lblExcelStatus.setText(file.getName() + " (" + newMatcher.getEntries().size() + " APIs)");
            appendLog("Đã nạp " + newMatcher.getEntries().size() + " API từ '" + file.getName()
                + "' [Sheet: " + mapping.sheetName + "].");
            mainTabs.setSelectedIndex(0);
            refreshAllViewsNow();
        } catch (Exception ex) {
            appendLog("Lỗi đọc Excel: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Lỗi đọc file: " + ex.getMessage(), "Lỗi Import", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onStartCrawl() {
        String hostsInput = txtHosts.getText().trim();
        String seedInput = txtSeedUrl.getText().trim();

        // Auto-infer host from Seed URL if user left Host(s) blank
        if (hostsInput.isEmpty() && !seedInput.isEmpty()) {
            try {
                java.net.URI u = java.net.URI.create(seedInput);
                if (u.getHost() != null) {
                    hostsInput = u.getHost();
                    txtHosts.setText(hostsInput);
                }
            } catch (Exception ignored) {
            }
        }

        config.setAllowedHostsCsv(hostsInput);
        if (config.getAllowedHostsSet().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Vui lòng nhập Target Host (ví dụ: target.example.com) hoặc Seed URL đầy đủ.",
                "Thiếu Target Host", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (config.isStrictExcelOnly() && !matcher.isLoaded()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "Bạn chưa Import file Excel API. Tiếp tục ở chế độ chỉ bắt ngoài Excel?",
                "Chưa có file Excel", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
            config.setStrictExcelOnly(false);
        }

        if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            appendLog("Tiếp tục Crawl...");
            refreshButtonsState();
            return;
        }

        String seed = seedInput;
        if (!seed.isEmpty() && !seed.startsWith("http://") && !seed.startsWith("https://")) {
            seed = "https://" + seed;
            txtSeedUrl.setText(seed);
        }

        state.resetAll(seed);
        chkArmAgent.setSelected(true);
        if (injector != null) {
            injector.setAgentArmed(true);
        }
        state.setStatus(CrawlState.Status.RUNNING);
        refreshButtonsState();

        if (!seed.isEmpty() && api != null) {
            SeedDiscoveryHelper.fetchSeedsAsync(api, seed, config, state, this::appendLog);
        }

        if (!matcher.isLoaded()) {
            mainTabs.setSelectedIndex(3); // Switch to "Ngoài Excel" tab when testing without Excel
        }

        appendLog("Crawl RUNNING trên Host(s): " + config.getAllowedHostsCsv()
            + " | Mở Chrome/Firefox qua Burp Proxy (hoặc bấm Refresh trang đang mở).");
        refreshAllViewsNow();
    }

    private void onTogglePause() {
        if (state.getStatus() == CrawlState.Status.RUNNING) {
            state.setStatus(CrawlState.Status.PAUSED);
            appendLog("Đã tạm dừng Crawl (có thể tương tác tay trên Firefox rồi bấm Tiếp tục).");
        } else if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            appendLog("Đã tiếp tục Crawl.");
        }
        refreshButtonsState();
    }

    private void onStopCrawl() {
        state.setStatus(CrawlState.Status.STOPPED);
        appendLog("Đã DỪNG Crawl.");
        refreshButtonsState();
        refreshAllViewsNow();
    }

    private void onResetCaptures() {
        state.resetAll("");
        state.setStatus(CrawlState.Status.IDLE);
        matcher.clearRuntimeCaptures();
        lastTreeCaptureCount = -1;
        extraModel.setRows(new ArrayList<>());
        skippedModel.setRows(new ArrayList<>());
        jsListModel.clear();
        clearInspector();
        appendLog("Đã Reset kết quả crawl (giữ nguyên danh sách API Excel đã nạp).");
        refreshButtonsState();
        refreshAllViewsNow();
    }

    private void onOpenSettings() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(owner != null ? owner : new Frame(), "Cài đặt Crawl & Bảo vệ", JDialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField tfPrefix = new JTextField(config.getApiBasePrefix(), 24);
        JCheckBox cbStrict = new JCheckBox("Strict Excel Mode (chỉ lưu API khớp Excel)", config.isStrictExcelOnly());
        JCheckBox cbTrailing = new JCheckBox("Bỏ qua dấu '/' cuối path khi so khớp Excel", config.isIgnoreTrailingSlash());
        JCheckBox cbNormRoute = new JCheckBox("Gộp ID động trong Route URL (/users/123 -> /users/{id})", config.isNormalizeRouteIds());
        JCheckBox cbAutoFill = new JCheckBox("Tự điền dữ liệu mẫu an toàn vào Form (Auto-fill)", config.isAutoFillForms());
        JCheckBox cbDanger = new JCheckBox("Bật Danger Guard (né nút Logout, Delete, Payment...)", config.isDangerGuardEnabled());
        JTextField tfDangerKw = new JTextField(config.getDangerKeywordsCsv(), 32);
        JCheckBox cbRelaxCsp = new JCheckBox("Tự nới lỏng CSP header khi đang crawl để Firefox chạy Agent", config.isRelaxCspWhileCrawling());
        JCheckBox cbJsScan = new JCheckBox("Quét thụ động file .js tìm Hidden API & JS Hints", config.isCaptureJsEndpoints());

        JSpinner spWait = new JSpinner(new SpinnerNumberModel(config.getActionWaitMs(), 200, 5000, 50));
        JSpinner spMaxActions = new JSpinner(new SpinnerNumberModel(config.getMaxActionsPerPage(), 10, 1000, 10));
        JSpinner spMaxPages = new JSpinner(new SpinnerNumberModel(config.getMaxPages(), 10, 2000, 20));
        JSpinner spMaxPag = new JSpinner(new SpinnerNumberModel(config.getMaxPaginationSamples(), 1, 20, 1));

        int r = 0;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; form.add(new JLabel("API Base Prefix (nếu Excel thiếu):"), gc);
        gc.gridx = 1; gc.weightx = 1; form.add(tfPrefix, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.gridwidth = 2; form.add(cbStrict, gc); r++;
        gc.gridx = 0; gc.gridy = r; form.add(cbTrailing, gc); r++;
        gc.gridx = 0; gc.gridy = r; form.add(cbNormRoute, gc); r++;
        gc.gridx = 0; gc.gridy = r; form.add(cbAutoFill, gc); r++;
        gc.gridx = 0; gc.gridy = r; form.add(cbDanger, gc); r++;

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; gc.weightx = 0; form.add(new JLabel("Từ khóa Danger Guard:"), gc);
        gc.gridx = 1; gc.weightx = 1; form.add(tfDangerKw, gc); r++;

        gc.gridx = 0; gc.gridy = r; gc.gridwidth = 2; form.add(cbRelaxCsp, gc); r++;
        gc.gridx = 0; gc.gridy = r; form.add(cbJsScan, gc); r++;

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = r; form.add(new JLabel("Thời gian chờ sau mỗi click (ms):"), gc);
        gc.gridx = 1; form.add(spWait, gc); r++;

        gc.gridx = 0; gc.gridy = r; form.add(new JLabel("Số nút tối đa mỗi trang:"), gc);
        gc.gridx = 1; form.add(spMaxActions, gc); r++;

        gc.gridx = 0; gc.gridy = r; form.add(new JLabel("Số trang tối đa toàn phiên:"), gc);
        gc.gridx = 1; form.add(spMaxPages, gc); r++;

        gc.gridx = 0; gc.gridy = r; form.add(new JLabel("Số mẫu tối đa mỗi pattern ?page= / ID:"), gc);
        gc.gridx = 1; form.add(spMaxPag, gc); r++;

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Hủy");
        JButton btnSave = new JButton("Lưu cài đặt");
        btnCancel.addActionListener(e -> dlg.dispose());
        btnSave.addActionListener(e -> {
            config.setApiBasePrefix(tfPrefix.getText());
            config.setStrictExcelOnly(cbStrict.isSelected());
            config.setIgnoreTrailingSlash(cbTrailing.isSelected());
            config.setNormalizeRouteIds(cbNormRoute.isSelected());
            config.setAutoFillForms(cbAutoFill.isSelected());
            config.setDangerGuardEnabled(cbDanger.isSelected());
            config.setDangerKeywordsCsv(tfDangerKw.getText());
            config.setRelaxCspWhileCrawling(cbRelaxCsp.isSelected());
            config.setCaptureJsEndpoints(cbJsScan.isSelected());
            config.setActionWaitMs(((Number) spWait.getValue()).intValue());
            config.setMaxActionsPerPage(((Number) spMaxActions.getValue()).intValue());
            config.setMaxPages(((Number) spMaxPages.getValue()).intValue());
            config.setMaxPaginationSamples(((Number) spMaxPag.getValue()).intValue());
            appendLog("Đã cập nhật cài đặt.");
            dlg.dispose();
        });
        btns.add(btnCancel);
        btns.add(btnSave);

        dlg.add(new JScrollPane(form), BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void showExportMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miCsv = new JMenuItem("Xuất CSV (Excel UTF-8)...");
        JMenuItem miJson = new JMenuItem("Xuất JSON...");
        JMenuItem miMd = new JMenuItem("Xuất Markdown (Cây UI + Ghi chú)...");

        miCsv.addActionListener(e -> exportReport("csv"));
        miJson.addActionListener(e -> exportReport("json"));
        miMd.addActionListener(e -> exportReport("md"));

        menu.add(miCsv);
        menu.add(miJson);
        menu.add(miMd);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void exportReport(String fmt) {
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setSelectedFile(new File("mythofcrawl_report." + fmt));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        lastDirectory = out.getParentFile();
        try {
            List<CapturedApiEntry> extras = state.getExtraCaptures();
            if ("csv".equals(fmt)) {
                ReportExporter.exportCsv(out, matcher, extras);
            } else if ("json".equals(fmt)) {
                ReportExporter.exportJson(out, matcher, extras);
            } else {
                ReportExporter.exportMarkdown(out, matcher, extras);
            }
            appendLog("Đã xuất báo cáo: " + out.getAbsolutePath());
            int open = JOptionPane.showConfirmDialog(this,
                "Đã lưu báo cáo tại:\n" + out.getAbsolutePath() + "\n\nBạn có muốn mở file ngay?",
                "Xuất báo cáo thành công", JOptionPane.YES_NO_OPTION);
            if (open == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(out);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi xuất báo cáo: " + ex.getMessage(), "Lỗi Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshButtonsState() {
        CrawlState.Status st = state.getStatus();
        btnStart.setEnabled(st == CrawlState.Status.IDLE || st == CrawlState.Status.STOPPED || st == CrawlState.Status.PAUSED);
        btnStart.setText(st == CrawlState.Status.PAUSED ? "Tiếp tục" : "Bắt đầu Crawl");
        btnPause.setEnabled(st == CrawlState.Status.RUNNING || st == CrawlState.Status.PAUSED);
        btnPause.setText(st == CrawlState.Status.PAUSED ? "Đang Tạm dừng" : "Tạm dừng");
        btnStop.setEnabled(st == CrawlState.Status.RUNNING || st == CrawlState.Status.PAUSED);
    }

    private void applyCoverageFilter() {
        int idx = cbStatusFilter.getSelectedIndex();
        String mode = "ALL";
        if (idx == 1) mode = "MATCHED";
        else if (idx == 2) mode = "MISSING";
        else if (idx == 3) mode = "NOTES";
        coverageModel.setFilter(mode, txtSearch.getText());
    }

    private void refreshAllViewsNow() {
        refreshButtonsState();

        ExcelSpecMatcher.CoverageStats stats = matcher.getCoverageStats();
        int pct = stats.total > 0 ? (int) Math.round(stats.matched * 100.0 / stats.total) : 0;
        progressBar.setValue(pct);
        progressBar.setString(pct + "%");

        List<CapturedApiEntry> extras = state.getExtraCaptures();
        List<CrawlState.SkippedAction> skipped = state.getSkippedDangerActions();
        List<String> jsItems = state.getDiscoveredJsEndpoints();

        lblStats.setText(String.format(
            "Matched: %d/%d | Missing: %d | Diff: %d | Ngoài Excel: %d | Pages: %d | Queue: %d",
            stats.matched, stats.total, stats.missing, stats.withNotes,
            extras.size(), state.getVisitedPagesCount(), state.getPendingQueueSize()));

        mainTabs.setTitleAt(0, String.format("Tiến độ Excel (%d/%d)", stats.matched, stats.total));
        mainTabs.setTitleAt(2, String.format("Nút bị né & JS (%d/%d)", skipped.size(), jsItems.size()));
        mainTabs.setTitleAt(3, String.format("Ngoài Excel (%d)", extras.size()));

        CrawlState.Status st = state.getStatus();
        lblCurrentAction.setText("[" + st.name() + "] " + state.getCurrentActionPath()
            + (state.getCurrentPageUrl().isEmpty() ? "" : ("  (" + state.getCurrentPageUrl() + ")")));

        // Preserve selected STT row while refreshing coverage model
        ApiSpecEntry prevSelected = getSelectedSpecEntry();
        coverageModel.refreshData();
        if (prevSelected != null) {
            reselectSpecEntry(prevSelected);
        }

        skippedModel.setRows(skipped);
        extraModel.setRows(extras);

        if (jsListModel.size() != jsItems.size()) {
            jsListModel.clear();
            for (String s : jsItems) {
                jsListModel.addElement(s);
            }
        }

        rebuildHierarchyTreeIfChanged(extras);
    }

    private void rebuildHierarchyTree() {
        lastTreeCaptureCount = -1;
        rebuildHierarchyTreeIfChanged(state.getExtraCaptures());
    }

    private void rebuildHierarchyTreeIfChanged(List<CapturedApiEntry> extras) {
        int totalCaptures = extras.size();
        for (ApiSpecEntry entry : matcher.getEntries()) {
            totalCaptures += entry.getAllStoredCaptures().size();
        }
        mainTabs.setTitleAt(1, String.format("Cây Phân cấp (%d)", totalCaptures));
        if (totalCaptures == lastTreeCaptureCount) {
            return;
        }
        lastTreeCaptureCount = totalCaptures;

        treeRoot.removeAllChildren();
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();

        for (ApiSpecEntry entry : matcher.getEntries()) {
            for (CapturedApiEntry cap : entry.getAllStoredCaptures()) {
                addCaptureToTree(folderNodes, entry, cap);
            }
        }
        for (CapturedApiEntry ex : extras) {
            addCaptureToTree(folderNodes, null, ex);
        }
        treeModel.reload();
    }

    private void addCaptureToTree(
        Map<String, DefaultMutableTreeNode> folderNodes,
        ApiSpecEntry specEntry,
        CapturedApiEntry cap
    ) {
        String[] parts = cap.actionPath.split("\\s*>\\s*");
        DefaultMutableTreeNode parent = treeRoot;
        StringBuilder keyBuilder = new StringBuilder("/");
        int start = (parts.length > 0 && parts[0].trim().equals("/")) ? 1 : 0;
        for (int i = start; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) {
                continue;
            }
            keyBuilder.append(" > ").append(seg);
            String fullKey = keyBuilder.toString();
            DefaultMutableTreeNode node = folderNodes.get(fullKey);
            if (node == null) {
                node = new DefaultMutableTreeNode(seg);
                folderNodes.put(fullKey, node);
                parent.add(node);
            }
            parent = node;
        }
        parent.add(new DefaultMutableTreeNode(new TreeCaptureNode(specEntry, cap)));
    }

    private void expandAllTreeNodes(boolean expand) {
        for (int i = 0; i < actionTree.getRowCount(); i++) {
            if (expand) {
                actionTree.expandRow(i);
            } else if (i > 0) {
                actionTree.collapseRow(i);
            }
        }
    }

    private ApiSpecEntry getSelectedSpecEntry() {
        int viewRow = tblCoverage.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = tblCoverage.convertRowIndexToModel(viewRow);
        return coverageModel.getEntryAt(modelRow);
    }

    private void reselectSpecEntry(ApiSpecEntry target) {
        for (int r = 0; r < coverageModel.getRowCount(); r++) {
            if (coverageModel.getEntryAt(r) == target) {
                int viewRow = tblCoverage.convertRowIndexToView(r);
                if (viewRow >= 0) {
                    tblCoverage.setRowSelectionInterval(viewRow, viewRow);
                }
                break;
            }
        }
    }

    private CapturedApiEntry getSelectedOrPrimaryCapture(ApiSpecEntry entry) {
        if (entry == null) {
            return null;
        }
        VariantItem item = (VariantItem) cbVariant.getSelectedItem();
        if (item != null && item.specEntry == entry && item.capture != null) {
            return item.capture;
        }
        return entry.getPrimaryCapture();
    }

    private void onCoverageSelectionChanged() {
        ApiSpecEntry entry = getSelectedSpecEntry();
        if (entry == null) {
            return;
        }
        showSpecEntryInInspector(entry);
    }

    private void showSpecEntryInInspector(ApiSpecEntry entry) {
        updatingVariants = true;
        cbVariant.removeAllItems();
        CapturedApiEntry primary = entry.getPrimaryCapture();
        if (primary != null) {
            cbVariant.addItem(new VariantItem(entry, primary,
                "Bản chính (" + primary.paramCount() + " params) - " + primary.actionPath));
            int i = 1;
            for (CapturedApiEntry alt : entry.getAlternateVariants()) {
                cbVariant.addItem(new VariantItem(entry, alt,
                    "Biến thể #" + (i++) + " (" + alt.paramCount() + " params) - " + alt.actionPath));
            }
            cbVariant.setSelectedIndex(0);
            updatingVariants = false;
            renderHttpMessages(entry, primary);
        } else {
            updatingVariants = false;
            clearInspectorForMissing(entry);
        }
    }

    private void showCaptureInInspector(ApiSpecEntry entry, CapturedApiEntry cap) {
        updatingVariants = true;
        cbVariant.removeAllItems();
        cbVariant.addItem(new VariantItem(entry, cap, cap.actionPath));
        cbVariant.setSelectedIndex(0);
        updatingVariants = false;
        renderHttpMessages(entry, cap);
    }

    private void renderHttpMessages(ApiSpecEntry entry, CapturedApiEntry cap) {
        if (cap == null) {
            clearInspector();
            return;
        }
        String prefix = entry != null ? ("[STT " + entry.stt + "] ") : "[Ngoài Excel] ";
        lblSelectedSummary.setText(prefix + cap.actionPath + "  ->  " + cap.method + " " + cap.fullUrl
            + "  (HTTP " + cap.statusCode + ", " + cap.paramCount() + " params: " + cap.paramSignature() + ")");

        if (entry != null && !entry.getNotesSummary().isEmpty()) {
            txtNotesBanner.setText("Ghi chú tham số: " + entry.getNotesSummary());
            txtNotesBanner.setForeground(new Color(180, 110, 15));
        } else {
            txtNotesBanner.setText("Params: " + cap.paramSignature());
            txtNotesBanner.setForeground(lblSelectedSummary.getForeground());
        }

        btnSendRepeater.setEnabled(api != null);
        if (requestEditor != null && responseEditor != null) {
            try {
                HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
                HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(cap.requestBytes));
                requestEditor.setRequest(req);
            } catch (Exception ignored) {
            }
            try {
                HttpResponse resp = HttpResponse.httpResponse(ByteArray.byteArray(cap.responseBytes));
                responseEditor.setResponse(resp);
            } catch (Exception ignored) {
            }
        }
    }

    private void clearInspectorForMissing(ApiSpecEntry entry) {
        lblSelectedSummary.setText("[STT " + entry.stt + " - MISSING] " + entry.displayMethod + " " + entry.rawSpecPath);
        List<String> hints = entry.getJsHints();
        if (!hints.isEmpty()) {
            txtNotesBanner.setText("Chưa kích hoạt qua UI. Gợi ý xuất hiện trong file JS: " + String.join(", ", hints));
            txtNotesBanner.setForeground(new Color(40, 110, 175));
        } else {
            txtNotesBanner.setText("Chưa có request nào khớp API này."
                + (entry.extraInfo.isEmpty() ? "" : (" Thông tin thêm từ Excel: " + entry.extraInfo)));
            txtNotesBanner.setForeground(lblSelectedSummary.getForeground());
        }
        btnSendRepeater.setEnabled(false);
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    private void clearInspector() {
        lblSelectedSummary.setText("Chọn một dòng API để xem chi tiết Request / Response và Action Path");
        txtNotesBanner.setText("");
        updatingVariants = true;
        cbVariant.removeAllItems();
        updatingVariants = false;
        btnSendRepeater.setEnabled(false);
        if (requestEditor != null && responseEditor != null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    private void sendToRepeater(CapturedApiEntry cap, String caption) {
        if (api == null || cap == null) {
            return;
        }
        try {
            HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
            HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(cap.requestBytes));
            api.repeater().sendToRepeater(req, caption);
        } catch (Exception ex) {
            appendLog("Không gửi được Repeater: " + ex.getMessage());
        }
    }

    private static void copyToClipboard(String text) {
        if (text == null) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static final class VariantItem {
        final ApiSpecEntry specEntry;
        final CapturedApiEntry capture;
        final String label;

        VariantItem(ApiSpecEntry specEntry, CapturedApiEntry capture, String label) {
            this.specEntry = specEntry;
            this.capture = capture;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class TreeCaptureNode {
        final ApiSpecEntry specEntry;
        final CapturedApiEntry capture;

        TreeCaptureNode(ApiSpecEntry specEntry, CapturedApiEntry capture) {
            this.specEntry = specEntry;
            this.capture = capture;
        }

        @Override
        public String toString() {
            String tag = capture.stt.isEmpty() ? "[EXTRA]" : ("[STT " + capture.stt + "]");
            return tag + " " + capture.method + " " + capture.requestPathWithQuery
                + "  (" + capture.paramCount() + " params, HTTP " + capture.statusCode + ")";
        }
    }
}
