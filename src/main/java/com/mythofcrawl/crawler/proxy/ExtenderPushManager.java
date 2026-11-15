package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.ExtenderPushEntry;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Explicitly user-triggered queue for replaying clean captured APIs via Burp's Extender tool channel
 * (`api.http().sendRequest`), so Logger++ (`Log From -> Extender`) captures only clean selected APIs
 * and forwards them to Elasticsearch with zero Proxy noise.
 *
 * Packet bytes are NEVER modified for naming; group names ride solely inside Burp Annotations (Comment)
 * and the 1-to-1 timestamped Push Ledger table.
 */
public final class ExtenderPushManager implements HttpHandler {

    private static final class PendingAnnotation {
        final String comment;
        final long createdAtMs;

        PendingAnnotation(String comment) {
            this.comment = comment;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    private final MontoyaApi api;
    private final CrawlConfig config;
    private final Runnable onUpdated;
    private final Consumer<String> logger;

    private final AtomicInteger seq = new AtomicInteger(0);
    private final List<ExtenderPushEntry> entries = new ArrayList<>();
    private final AtomicBoolean pushing = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    // Dual-key annotation lookup so Burp's URL normalization (:80/:443 removal) still matches cleanly
    private final ConcurrentHashMap<String, Deque<PendingAnnotation>> pendingBySig = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> activeMessageComments = new ConcurrentHashMap<>();

    public ExtenderPushManager(
        MontoyaApi api,
        CrawlConfig config,
        Runnable onUpdated,
        Consumer<String> logger
    ) {
        this.api = api;
        this.config = config;
        this.onUpdated = onUpdated;
        this.logger = logger;
    }

    public boolean isPushing() {
        return pushing.get();
    }

    public void requestCancel() {
        cancelRequested.set(true);
    }

    public synchronized List<ExtenderPushEntry> snapshotEntries() {
        return new ArrayList<>(entries);
    }

    public synchronized int totalCount() {
        return entries.size();
    }

    public synchronized int pendingSelectedCount() {
        int c = 0;
        for (ExtenderPushEntry e : entries) {
            if (e.isSelected() && (e.getStatus() == ExtenderPushEntry.Status.QUEUED
                || e.getStatus() == ExtenderPushEntry.Status.FAILED
                || e.getStatus() == ExtenderPushEntry.Status.CANCELLED)) {
                c++;
            }
        }
        return c;
    }

    public synchronized int sentCount() {
        int c = 0;
        for (ExtenderPushEntry e : entries) {
            if (e.getStatus() == ExtenderPushEntry.Status.SENT) {
                c++;
            }
        }
        return c;
    }

    public synchronized int enqueueGroup(ActionGroup group) {
        if (group == null) {
            return 0;
        }
        return enqueueCalls(group.getCalls());
    }

    public synchronized int enqueueCalls(List<ApiCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (ApiCall call : calls) {
            if (call == null || call.capture == null) {
                continue;
            }
            if (isAlreadyQueued(call.groupId, call.index)) {
                continue;
            }
            entries.add(new ExtenderPushEntry(seq.incrementAndGet(), call));
            added++;
        }
        if (added > 0 && onUpdated != null) {
            onUpdated.run();
        }
        return added;
    }

    private boolean isAlreadyQueued(int groupId, int callIndex) {
        for (ExtenderPushEntry e : entries) {
            if (e.groupId == groupId && e.callIndex == callIndex
                && (e.getStatus() == ExtenderPushEntry.Status.QUEUED
                    || e.getStatus() == ExtenderPushEntry.Status.SENDING)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clearAll() {
        if (pushing.get()) {
            return;
        }
        entries.clear();
        seq.set(0);
        if (onUpdated != null) {
            onUpdated.run();
        }
    }

    public synchronized void clearFinished() {
        entries.removeIf(e -> e.getStatus() == ExtenderPushEntry.Status.SENT
            || e.getStatus() == ExtenderPushEntry.Status.CANCELLED);
        if (onUpdated != null) {
            onUpdated.run();
        }
    }

    public synchronized void selectAll(boolean sel) {
        for (ExtenderPushEntry e : entries) {
            e.setSelected(sel);
        }
        if (onUpdated != null) {
            onUpdated.run();
        }
    }

    public synchronized void requeueSelected() {
        for (ExtenderPushEntry e : entries) {
            if (e.isSelected() && e.getStatus() != ExtenderPushEntry.Status.SENDING) {
                e.setStatus(ExtenderPushEntry.Status.QUEUED);
                e.setErrorText("");
            }
        }
        if (onUpdated != null) {
            onUpdated.run();
        }
    }

    /** Starts pushing queued & checked items sequentially on a background daemon thread. */
    public boolean startPushAsync(int delayMs) {
        if (api == null) {
            if (logger != null) {
                logger.accept("Chỉ đẩy được qua Extender khi đang chạy trong Burp Suite.");
            }
            return false;
        }
        if (!pushing.compareAndSet(false, true)) {
            return false;
        }
        cancelRequested.set(false);
        final int safeDelay = Math.max(0, Math.min(10000, delayMs));

        Thread t = new Thread(() -> {
            int okCount = 0;
            int failCount = 0;
            try {
                List<ExtenderPushEntry> batch = new ArrayList<>();
                synchronized (ExtenderPushManager.this) {
                    for (ExtenderPushEntry e : entries) {
                        if (e.isSelected() && (e.getStatus() == ExtenderPushEntry.Status.QUEUED
                            || e.getStatus() == ExtenderPushEntry.Status.FAILED
                            || e.getStatus() == ExtenderPushEntry.Status.CANCELLED)) {
                            batch.add(e);
                        }
                    }
                }
                if (logger != null) {
                    logger.accept("Bắt đầu đẩy " + batch.size() + " API sạch qua kênh Extender -> Logger++ (delay " + safeDelay + "ms)...");
                }
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
                for (int i = 0; i < batch.size(); i++) {
                    ExtenderPushEntry item = batch.get(i);
                    if (cancelRequested.get()) {
                        item.setStatus(ExtenderPushEntry.Status.CANCELLED);
                        continue;
                    }
                    item.setStatus(ExtenderPushEntry.Status.SENDING);
                    notifyUi();

                    try {
                        CapturedApiEntry cap = item.capture;
                        HttpService svc = HttpService.httpService(cap.host, cap.port, cap.https);
                        HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(cap.requestBytes));
                        // Strict packet integrity: only applies custom header rules if explicitly toggled ON
                        req = config.getHeaderFilterProfile()
                            .applyToMontoyaRequest(req, cap.host, cap.fullUrl, true);

                        String key = buildSigKey(req.method(), svc.host(), req.path());
                        pendingBySig.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>())
                            .addLast(new PendingAnnotation(item.buildBurpComment()));

                        item.setPushedAtText(fmt.format(new Date()));
                        HttpRequestResponse res = api.http().sendRequest(req);
                        int st = res != null && res.hasResponse() ? res.response().statusCode() : 0;
                        item.setPushedHttpStatus(st);
                        item.setStatus(ExtenderPushEntry.Status.SENT);
                        item.setErrorText("");
                        okCount++;
                    } catch (Exception ex) {
                        item.setStatus(ExtenderPushEntry.Status.FAILED);
                        item.setErrorText(ex.getMessage() != null ? ex.getMessage() : "Error");
                        failCount++;
                    }
                    notifyUi();

                    if (i + 1 < batch.size() && safeDelay > 0 && !cancelRequested.get()) {
                        try {
                            Thread.sleep(safeDelay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            cancelRequested.set(true);
                        }
                    }
                }
                if (cancelRequested.get()) {
                    synchronized (ExtenderPushManager.this) {
                        for (ExtenderPushEntry e : entries) {
                            if (e.getStatus() == ExtenderPushEntry.Status.SENDING) {
                                e.setStatus(ExtenderPushEntry.Status.CANCELLED);
                            }
                        }
                    }
                }
                if (logger != null) {
                    logger.accept("Hoàn tất đẩy qua Extender: thành công " + okCount + ", lỗi " + failCount
                        + (cancelRequested.get() ? " (đã hủy giữa chừng)." : "."));
                }
            } finally {
                pushing.set(false);
                pruneOldPending();
                notifyUi();
            }
        }, "MythCrawler-ExtenderPush");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void notifyUi() {
        if (onUpdated != null) {
            onUpdated.run();
        }
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
        if (req.toolSource() != null && req.toolSource().isFromTool(ToolType.EXTENSIONS)) {
            HttpService svc = req.httpService();
            String key = buildSigKey(req.method(), svc != null ? svc.host() : "", req.path());
            Deque<PendingAnnotation> q = pendingBySig.get(key);
            if (q != null) {
                PendingAnnotation meta = q.pollFirst();
                if (meta != null && (System.currentTimeMillis() - meta.createdAtMs) < 30_000L) {
                    if (activeMessageComments.size() < 4096) {
                        activeMessageComments.put(req.messageId(), meta.comment);
                    }
                    // Keep exact HTTP packet untouched; attach Burp Comment metadata only
                    return RequestToBeSentAction.continueWith(req, req.annotations().withNotes(meta.comment));
                }
            }
        }
        return RequestToBeSentAction.continueWith(req);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived resp) {
        String comment = activeMessageComments.remove(resp.messageId());
        if (comment != null && !comment.isEmpty()) {
            return ResponseReceivedAction.continueWith(resp, resp.annotations().withNotes(comment));
        }
        return ResponseReceivedAction.continueWith(resp);
    }

    private static String buildSigKey(String method, String host, String path) {
        return (method != null ? method.toUpperCase(Locale.ROOT) : "GET")
            + "|" + (host != null ? host.toLowerCase(Locale.ROOT) : "")
            + "|" + (path != null ? path : "/");
    }

    private void pruneOldPending() {
        long now = System.currentTimeMillis();
        pendingBySig.values().forEach(q -> q.removeIf(a -> now - a.createdAtMs > 30_000L));
        if (activeMessageComments.size() > 2048) {
            activeMessageComments.clear();
        }
    }

    /** Exports 1-to-1 timestamped Push Ledger CSV so the user can cross-reference names on Elastic UI. */
    public void exportLedgerCsv(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("#,Gio Day,Trang Thai,Ten Nhom (Action Path),Method,Endpoint,Params,Excel STT,HTTP Goc,HTTP Day,NOTE\n");
        for (ExtenderPushEntry e : snapshotEntries()) {
            sb.append(e.seq).append(',')
              .append(csv(e.getPushedAtText())).append(',')
              .append(csv(e.getStatus().label)).append(',')
              .append(csv(e.groupPath)).append(',')
              .append(csv(e.method)).append(',')
              .append(csv(e.endpoint)).append(',')
              .append(csv(e.paramsSummary)).append(',')
              .append(csv(e.excelStt)).append(',')
              .append(e.originalStatus).append(',')
              .append(e.getPushedHttpStatus() > 0 ? String.valueOf(e.getPushedHttpStatus()) : "-").append(',')
              .append(csv(e.notesSummary)).append('\n');
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    private static String csv(String v) {
        if (v == null) {
            return "\"\"";
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
