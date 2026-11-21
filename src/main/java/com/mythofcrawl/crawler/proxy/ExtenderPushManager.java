package com.mythofcrawl.crawler.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpMode;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Explicitly user-triggered queue for replaying clean captured APIs via Burp's Extender tool channel
 * (`api.http().sendRequest`), so Logger++ (`Log From -> Extender`) captures only clean selected APIs
 * and forwards them to Elasticsearch with zero Proxy noise.
 *
 * STRICT PACKET INTEGRITY:
 * - Outgoing wire packets are NEVER modified (no extra headers or bytes added for correlation).
 * - Group metadata rides solely inside Burp Annotations (Comment) correlated via single-flight
 *   {@link AtomicReference} on the dispatch thread, preserving original {@link HttpMode}.
 * - Ledger items deduplicate by monotonic {@code callUid} so resets never collide.
 */
public final class ExtenderPushManager implements HttpHandler {

    private static final long COMMENT_TTL_MS = 60_000L;

    private static final class PendingAnnotation {
        final String comment;
        final String expectedHost;
        final long createdAtMs;

        PendingAnnotation(String comment, String expectedHost) {
            this.comment = comment;
            this.expectedHost = expectedHost != null ? expectedHost : "";
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
    private final AtomicReference<Thread> activePushThread = new AtomicReference<>();

    private final AtomicReference<PendingAnnotation> activePushAnnotation = new AtomicReference<>();
    private final ConcurrentHashMap<Integer, PendingAnnotation> activeMessageComments = new ConcurrentHashMap<>();

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
        Thread t = activePushThread.get();
        if (t != null) {
            t.interrupt();
        }
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
            if (isAlreadyQueued(call)) {
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

    private boolean isAlreadyQueued(ApiCall call) {
        for (ExtenderPushEntry e : entries) {
            boolean active = (e.getStatus() == ExtenderPushEntry.Status.QUEUED
                || e.getStatus() == ExtenderPushEntry.Status.SENDING);
            if (!active) {
                continue;
            }
            if (call.callUid > 0 && e.callUid > 0) {
                if (e.callUid == call.callUid) {
                    return true;
                }
            } else if (e.groupId == call.groupId && e.callIndex == call.index) {
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
            activePushThread.set(Thread.currentThread());
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
                        HttpRequest req = cap.toMontoyaRequest(svc);
                        // Strict packet integrity: never add correlation headers; only apply user header filter if explicitly toggled ON
                        req = config.getHeaderFilterProfile()
                            .applyToMontoyaRequest(req, cap.host, cap.fullUrl, true);

                        activePushAnnotation.set(new PendingAnnotation(item.buildBurpComment(), svc.host()));
                        item.setPushedAtText(fmt.format(new Date()));
                        HttpMode mode = cap.getHttpMode();
                        HttpRequestResponse res;
                        try {
                            res = api.http().sendRequest(req, mode);
                        } finally {
                            activePushAnnotation.set(null);
                        }
                        int st = res != null && res.hasResponse() ? res.response().statusCode() : 0;
                        item.setPushedHttpStatus(st);
                        item.setStatus(ExtenderPushEntry.Status.SENT);
                        item.setErrorText("");
                        okCount++;
                    } catch (Exception ex) {
                        activePushAnnotation.set(null);
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
                activePushAnnotation.set(null);
                activePushThread.set(null);
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
            PendingAnnotation active = activePushAnnotation.get();
            if (active != null && (System.currentTimeMillis() - active.createdAtMs) < COMMENT_TTL_MS) {
                HttpService svc = req.httpService();
                String reqHost = svc != null && svc.host() != null ? svc.host() : "";
                if (active.expectedHost.isEmpty() || active.expectedHost.equalsIgnoreCase(reqHost)) {
                    if (activePushAnnotation.compareAndSet(active, null)) {
                        if (activeMessageComments.size() > 2048) {
                            pruneOldPending();
                        }
                        activeMessageComments.put(req.messageId(), active);
                        // Keep exact HTTP packet untouched; attach Burp Comment metadata only
                        return RequestToBeSentAction.continueWith(req, req.annotations().withNotes(active.comment));
                    }
                }
            }
        }
        return RequestToBeSentAction.continueWith(req);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived resp) {
        PendingAnnotation meta = activeMessageComments.remove(resp.messageId());
        if (meta != null && meta.comment != null && !meta.comment.isEmpty()) {
            return ResponseReceivedAction.continueWith(resp, resp.annotations().withNotes(meta.comment));
        }
        return ResponseReceivedAction.continueWith(resp);
    }

    private void pruneOldPending() {
        long now = System.currentTimeMillis();
        activeMessageComments.entrySet().removeIf(e -> (now - e.getValue().createdAtMs) > COMMENT_TTL_MS);
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
        String safe = v;
        if (!"-".equals(safe) && !safe.isEmpty()) {
            char first = safe.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                safe = "'" + safe;
            }
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
