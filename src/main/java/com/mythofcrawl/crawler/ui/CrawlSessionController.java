package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ReportExporter;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.SeedDiscoveryHelper;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Controller coordinating crawl lifecycle, background SwingWorker Excel import/export,
 * and batch Montoya Repeater / Organizer / Logger++ dispatch outside Swing UI layout code.
 */
public final class CrawlSessionController {

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final CrawlState state;
    private final HtmlAgentInjector injector;
    private final ExtenderPushManager pushManager;
    private final Consumer<String> logger;
    private volatile ExcelSpecMatcher matcher;
    private File lastDirectory;

    public CrawlSessionController(
        MontoyaApi api,
        CrawlConfig config,
        CrawlState state,
        HtmlAgentInjector injector,
        ExtenderPushManager pushManager,
        ExcelSpecMatcher initialMatcher,
        Consumer<String> logger
    ) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.injector = injector;
        this.pushManager = pushManager;
        this.matcher = initialMatcher != null ? initialMatcher : ExcelSpecMatcher.empty();
        this.logger = logger;
    }

    public ExcelSpecMatcher getMatcher() {
        return matcher;
    }

    public boolean startOrResumeCrawl(
        Component parent,
        String hostsInput,
        String seedInput,
        Runnable onStrictDisabled,
        Runnable onStarted
    ) {
        String hosts = hostsInput != null ? CrawlConfig.unwrapMarkdownLinks(hostsInput).trim() : "";
        String seed = seedInput != null ? CrawlConfig.unwrapMarkdownLinks(seedInput).trim() : "";

        if (hosts.isEmpty() && !seed.isEmpty()) {
            try {
                java.net.URI u = java.net.URI.create(seed);
                if (u.getHost() != null) {
                    hosts = u.getHost();
                }
            } catch (Exception ignored) {
            }
        }

        config.setAllowedHostsCsv(hosts);
        if (!seed.isEmpty()) {
            config.setSeedUrl(seed);
        } else {
            seed = config.getPrimaryAllowedEntryUrl();
        }
        if (config.getAllowedHostsSet().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "Vui lòng nhập Target Host / URL nhánh (ví dụ: vnexpress.net/the-gioi hoặc *) hoặc Seed URL đầy đủ.",
                "Thiếu Target Host", JOptionPane.WARNING_MESSAGE);
            return false;
        }

        if (config.isStrictExcelOnly() && !matcher.isLoaded()) {
            int choice = JOptionPane.showConfirmDialog(parent,
                "Bạn chưa Import file Excel API. Tiếp tục ở chế độ bắt & nhóm toàn bộ API?",
                "Chưa có file Excel", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return false;
            }
            config.setStrictExcelOnly(false);
            if (onStrictDisabled != null) {
                onStrictDisabled.run();
            }
        }

        if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            log("Tiếp tục Crawl (" + (config.isAllowAutoRequests() ? "Auto ON" : "Khóa tự gửi - Thao tác tay") + ")...");
            if (onStarted != null) {
                onStarted.run();
            }
            return true;
        }

        if (!seed.isEmpty() && !seed.startsWith("http://") && !seed.startsWith("https://")) {
            seed = "https://" + seed;
        }

        state.resetAll(seed);
        if (injector != null) {
            injector.setAgentArmed(true);
        }
        state.setStatus(CrawlState.Status.RUNNING);

        if (!seed.isEmpty() && api != null) {
            if (config.isAllowAutoRequests()) {
                SeedDiscoveryHelper.fetchSeedsAsync(api, seed, config, state, matcher, logger);
            } else {
                log("Tạm giữ Seed URL (chế độ Khóa tự gửi): Extension KHÔNG tự gửi /robots.txt hay /sitemap.xml cho tới khi bạn BẬT 'Tự gửi Request'.");
            }
        }

        log("Crawl RUNNING trên Scope: " + config.getAllowedHostsCsv()
            + (config.hasPathScopeRestriction()
                ? (" [Khóa nhánh URL -> " + config.getPrimaryAllowedEntryUrl() + "]")
                : "")
            + (config.isAllowAutoRequests()
                ? " | Tự gửi Request ĐANG BẬT: Agent tự click & duyệt."
                : " | Khóa tự gửi ĐANG BẬT: Bạn tự bấm trên trình duyệt để gom nhóm API an toàn."));
        if (onStarted != null) {
            onStarted.run();
        }
        return true;
    }

    public void togglePause() {
        if (state.getStatus() == CrawlState.Status.RUNNING) {
            state.setStatus(CrawlState.Status.PAUSED);
            log("Đã tạm dừng Crawl (có thể tương tác tay trên trình duyệt rồi bấm Tiếp tục).");
        } else if (state.getStatus() == CrawlState.Status.PAUSED) {
            state.setStatus(CrawlState.Status.RUNNING);
            log("Đã tiếp tục Crawl.");
        }
    }

    public void stopCrawl() {
        state.setStatus(CrawlState.Status.STOPPED);
        log("Đã DỪNG Crawl.");
    }

    public void resetCaptures() {
        state.resetAll("");
        state.setStatus(CrawlState.Status.IDLE);
        matcher.clearRuntimeCaptures();
        log("Đã Reset kết quả crawl (giữ nguyên danh sách API Excel, Header Filter & Push Ledger).");
    }

    public void importExcelAsync(Component parent, Consumer<ExcelSpecMatcher> onLoaded) {
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setDialogTitle("Chọn file Excel (.xlsx) hoặc CSV danh sách API");
        fc.setFileFilter(new FileNameExtensionFilter(
            "Excel / CSV (*.xlsx, *.xlsm, *.csv, *.tsv)", "xlsx", "xlsm", "csv", "tsv"));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = fc.getSelectedFile();
        lastDirectory = file.getParentFile();
        log("Đang đọc file Excel/CSV: " + file.getName() + "...");

        new SwingWorker<SimpleXlsxReader.WorkbookData, Void>() {
            @Override
            protected SimpleXlsxReader.WorkbookData doInBackground() throws Exception {
                return SimpleXlsxReader.readFile(file);
            }

            @Override
            protected void done() {
                try {
                    SimpleXlsxReader.WorkbookData wb = get();
                    if (wb.getSheetNames().isEmpty()) {
                        JOptionPane.showMessageDialog(parent, "Không tìm thấy Sheet dữ liệu nào trong file.",
                            "File rỗng", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    ExcelColumnMapperDialog.MappingResult mapping =
                        ExcelColumnMapperDialog.showDialog(api, parent, file.getName(), wb);
                    if (mapping == null) {
                        return;
                    }
                    buildMatcherAsync(parent, file, wb, mapping, onLoaded);
                } catch (Exception ex) {
                    log("Lỗi đọc Excel: " + ex.getMessage());
                    JOptionPane.showMessageDialog(parent, "Lỗi đọc file: " + ex.getMessage(),
                        "Lỗi Import", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void buildMatcherAsync(
        Component parent,
        File file,
        SimpleXlsxReader.WorkbookData wb,
        ExcelColumnMapperDialog.MappingResult mapping,
        Consumer<ExcelSpecMatcher> onLoaded
    ) {
        final boolean ignoreTrailing = config.isIgnoreTrailingSlash();
        new SwingWorker<ExcelSpecMatcher, Void>() {
            @Override
            protected ExcelSpecMatcher doInBackground() {
                List<List<String>> rows = wb.getRows(mapping.sheetName);
                return ExcelSpecMatcher.buildFromRows(
                    file.getName(),
                    rows,
                    mapping.headerRowIndex,
                    mapping.sttCol,
                    mapping.methodCol,
                    mapping.pathCol,
                    ignoreTrailing
                );
            }

            @Override
            protected void done() {
                try {
                    ExcelSpecMatcher newMatcher = get();
                    if (!newMatcher.isLoaded()) {
                        JOptionPane.showMessageDialog(parent,
                            "Không đọc được dòng API hợp lệ nào từ cột đã chọn. Kiểm tra lại dòng tiêu đề & cột Path API.",
                            "Không có API", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    CrawlSessionController.this.matcher = newMatcher;
                    if (injector != null) {
                        injector.setMatcher(newMatcher);
                    }
                    log("Đã nạp " + newMatcher.getEntries().size() + " API từ '" + file.getName()
                        + "' [Sheet: " + mapping.sheetName + "].");
                    if (onLoaded != null) {
                        onLoaded.accept(newMatcher);
                    }
                } catch (Exception ex) {
                    log("Lỗi xử lý Excel: " + ex.getMessage());
                    JOptionPane.showMessageDialog(parent, "Lỗi xử lý file: " + ex.getMessage(),
                        "Lỗi Import", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void exportReportAsync(Component parent, String fmt) {
        String ext = "tmoc".equals(fmt) ? "json" : fmt;
        String defaultName = "tmoc".equals(fmt) ? "theMythOfCrawl_targets.json" : ("mythofcrawl_report." + ext);
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setSelectedFile(new File(defaultName));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        lastDirectory = out.getParentFile();

        final ExcelSpecMatcher snapMatcher = this.matcher;
        final List<CapturedApiEntry> extras = state.getExtraCaptures();
        final ActionGroupStore store = state.getActionGroups();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if ("csv".equals(fmt)) {
                    ReportExporter.exportCsv(out, snapMatcher, extras, store, state);
                } else if ("json".equals(fmt)) {
                    ReportExporter.exportJson(out, snapMatcher, extras, store, state);
                } else if ("tmoc".equals(fmt)) {
                    ReportExporter.exportForTheMythOfCrawlJson(out, snapMatcher, store);
                } else {
                    ReportExporter.exportMarkdown(out, snapMatcher, extras, store, state);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    log("Đã xuất báo cáo: " + out.getAbsolutePath());
                    int open = JOptionPane.showConfirmDialog(parent,
                        "Đã lưu báo cáo tại:\n" + out.getAbsolutePath() + "\n\nBạn có muốn mở file ngay?",
                        "Xuất báo cáo thành công", JOptionPane.YES_NO_OPTION);
                    if (open == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                        new SwingWorker<Void, Void>() {
                            @Override
                            protected Void doInBackground() throws Exception {
                                Desktop.getDesktop().open(out);
                                return null;
                            }
                        }.execute();
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Lỗi xuất báo cáo: " + ex.getMessage(),
                        "Lỗi Export", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void exportPushLedgerCsvAsync(Component parent) {
        if (pushManager.totalCount() == 0) {
            JOptionPane.showMessageDialog(parent, "Bảng Đối Chiếu đẩy Elastic chưa có dữ liệu.",
                "Trống", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(lastDirectory);
        fc.setSelectedFile(new File("mythofcrawl_elastic_ledger.csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        lastDirectory = out.getParentFile();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                pushManager.exportLedgerCsv(out);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    log("Đã xuất Bảng Đối Chiếu đẩy Elastic: " + out.getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Lỗi xuất Bảng Đối Chiếu: " + ex.getMessage(),
                        "Lỗi Export", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void sendToRepeater(CapturedApiEntry cap, String caption) {
        if (api == null || cap == null) {
            return;
        }
        try {
            HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
            HttpRequest req = cap.toMontoyaRequest(svc);
            api.repeater().sendToRepeater(req, caption);
        } catch (Exception ex) {
            log("Không gửi được Repeater: " + ex.getMessage());
        }
    }

    public void sendCallsToRepeater(Component parent, List<ApiCall> calls) {
        if (api == null || calls == null || calls.isEmpty()) {
            return;
        }
        if (calls.size() > 25) {
            int ok = JOptionPane.showConfirmDialog(parent,
                "Đang chọn " + calls.size() + " API. Gửi toàn bộ sang Repeater?",
                "Xác nhận gửi sang Repeater", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                int sent = 0;
                for (ApiCall c : calls) {
                    if (c == null || c.capture == null) {
                        continue;
                    }
                    String tag = c.inExcel ? ("STT " + c.excelStt) : ("G" + c.groupId + "#" + c.index);
                    sendToRepeater(c.capture, tag + " " + c.capture.method);
                    sent++;
                }
                return sent;
            }

            @Override
            protected void done() {
                try {
                    log("Đã gửi " + get() + " API sang Repeater.");
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    public void sendCallToOrganizer(ApiCall call) {
        if (api == null || call == null || call.capture == null) {
            return;
        }
        try {
            CapturedApiEntry cap = call.capture;
            HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
            HttpRequest req = cap.toMontoyaRequest(svc);
            HttpResponse resp = cap.toMontoyaResponse();
            StringBuilder notes = new StringBuilder();
            notes.append("[theMythOfCrawl] ").append(call.displayName());
            if (call.inExcel) {
                notes.append(" | STT:").append(call.excelStt);
            }
            if (call.hasNotes()) {
                notes.append(" | NOTE: ").append(call.notesSummary());
            }
            if (!call.getUserComment().isEmpty()) {
                notes.append(" | Comment: ").append(call.getUserComment());
            }
            HighlightColor hc = call.getHighlightColor() != null ? call.getHighlightColor() : HighlightColor.NONE;
            Annotations ann = Annotations.annotations(notes.toString(), hc);
            api.organizer().sendToOrganizer(HttpRequestResponse.httpRequestResponse(req, resp, ann));
        } catch (Exception ex) {
            log("Không gửi được Organizer: " + ex.getMessage());
        }
    }

    public void sendCaptureToOrganizer(CapturedApiEntry cap) {
        if (api == null || cap == null) {
            return;
        }
        try {
            HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
            HttpRequest req = cap.toMontoyaRequest(svc);
            HttpResponse resp = cap.toMontoyaResponse();
            Annotations ann = Annotations.annotations("[theMythOfCrawl] " + cap.actionPath);
            api.organizer().sendToOrganizer(HttpRequestResponse.httpRequestResponse(req, resp, ann));
        } catch (Exception ex) {
            log("Không gửi được Organizer: " + ex.getMessage());
        }
    }

    public void sendCallsToOrganizer(Component parent, List<ApiCall> calls) {
        if (api == null || calls == null || calls.isEmpty()) {
            return;
        }
        if (calls.size() > 50) {
            int ok = JOptionPane.showConfirmDialog(parent,
                "Đang chọn " + calls.size() + " API. Gửi toàn bộ sang Burp Organizer?",
                "Xác nhận gửi sang Organizer", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                int sent = 0;
                for (ApiCall c : calls) {
                    sendCallToOrganizer(c);
                    sent++;
                }
                return sent;
            }

            @Override
            protected void done() {
                try {
                    log("Đã gửi " + get() + " API sang Burp Organizer.");
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    public void showSharedGroupsDialog(Component parent, String endpointKey) {
        ActionGroupStore store = state.getActionGroups();
        String normPath = GroupTableModel.normalizeRawPathKey(endpointKey);
        int sp = endpointKey != null ? endpointKey.indexOf(' ') : -1;
        if (sp > 0 && sp < 10) {
            normPath = GroupTableModel.normalizeRawPathKey(endpointKey.substring(sp + 1).trim());
        }
        List<ApiCall> calls = GroupTableModel.collectCallsForNormalizedPath(store, normPath);
        if (calls.isEmpty()) {
            List<String> paths = store.sharedGroupPaths(endpointKey);
            JOptionPane.showMessageDialog(parent,
                paths.isEmpty()
                    ? ("Không có thông tin nhóm dùng chung cho endpoint: " + endpointKey)
                    : ("Endpoint: " + endpointKey + "\n" + String.join("\n", paths)),
                "Nhóm dùng chung", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        GroupTableModel.PathOverlapSummary summary = GroupTableModel.summarizeCallsForPath(normPath, calls);
        ApiCall baseline = calls.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint chuẩn hóa: ").append(normPath).append('\n');
        sb.append("Trùng ").append(summary.totalCalls).append(" lần tại ")
            .append(summary.distinctLocations).append(" vị trí khác nhau\n");
        sb.append("Tổng hợp khác biệt: ").append(summary.diffSummary).append("\n\n");
        for (int i = 0; i < calls.size(); i++) {
            ApiCall c = calls.get(i);
            String diff = GroupTableModel.describeCallDiffAgainst(c, baseline);
            sb.append(i + 1).append(". [")
                .append(c.capture.method).append(" | HTTP ").append(c.capture.statusCode)
                .append(" | ").append(c.capture.paramCount()).append(" params")
                .append(" | ").append(c.capture.responseLength).append(" B]  ")
                .append(c.displayName()).append('\n')
                .append("    -> ").append(diff).append('\n');
        }
        javax.swing.JTextArea ta = new javax.swing.JTextArea(sb.toString(), Math.min(22, calls.size() * 2 + 6), 76);
        ta.setEditable(false);
        ta.setFont(AuditTheme.editorFont(api, parent));
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(parent, new javax.swing.JScrollPane(ta),
            "Chi tiết API trùng lặp & Khác biệt", JOptionPane.INFORMATION_MESSAGE);
    }

    private void log(String msg) {
        if (logger != null && msg != null) {
            logger.accept(msg);
        }
    }
}
