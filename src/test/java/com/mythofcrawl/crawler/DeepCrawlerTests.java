package com.mythofcrawl.crawler;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.MessageReceivedAction;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.websocket.InterceptedBinaryMessage;
import burp.api.montoya.proxy.websocket.InterceptedTextMessage;
import burp.api.montoya.proxy.websocket.ProxyMessageHandler;
import burp.api.montoya.proxy.websocket.ProxyWebSocket;
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreation;
import burp.api.montoya.websocket.Direction;
import com.mythofcrawl.crawler.excel.ParamExtractor;
import com.mythofcrawl.crawler.excel.ReportExporter;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ActionStep;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.EventLogEntry;
import com.mythofcrawl.crawler.model.ExtenderPushEntry;
import com.mythofcrawl.crawler.model.HeaderFilterProfile;
import com.mythofcrawl.crawler.model.MiniJson;
import com.mythofcrawl.crawler.model.ReplayPlan;
import com.mythofcrawl.crawler.model.ReviewStatus;
import com.mythofcrawl.crawler.proxy.ControlPlaneHandler;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.LocalControlServer;
import com.mythofcrawl.crawler.proxy.StaticJsScanner;
import com.mythofcrawl.crawler.proxy.WebSocketCaptureHandler;
import com.mythofcrawl.crawler.ui.AuditTheme;
import com.mythofcrawl.crawler.ui.BurpFilterBar;
import com.mythofcrawl.crawler.ui.GroupTableModel;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deep integration and concurrency verification suite using {@link MockMontoyaSupport}
 * (zero Burp process required). Covers Wave 1 & core guarantees across:
 * 1) ControlPlaneHandler (HeaderFilterProfile, Accept-Encoding, /__myth_crawl/ gating & token, TTL cleanup)
 * 2) HtmlAgentInjector (BROTLI/GZIP JS decode, comment-safe <head>, >1024B sniff, static skip, 256KB preview cap)
 * 3) ActionGroupStore (ReentrantReadWriteLock concurrency, MAX_NOTES accounting, triage metadata & version bump)
 * 4) ExtenderPushManager (zero wire packet alteration, post-reset callUid non-collision, CSV formula safety)
 * 5) GroupTableModel & BurpFilterBar (headless Class<?>, !exclude/Regex/Chips, 1/1.1 tree numbering & overlap)
 */
public final class DeepCrawlerTests {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        MockMontoyaSupport.installFactory();
        Path tmpDir = Files.createTempDirectory("mythofcrawl_deep_tests_");
        try {
            testControlPlaneHandler();
            testLocalControlServerTokenEnforcement();
            testHtmlAgentInjector();
            testActionGroupStoreConcurrencyAndTriage();
            testExtenderPushZeroWireModificationAndPostResetUid(tmpDir);
            testGroupTableModelAndBurpFilterBar();
            testPhase4ParamExtractorProtocols();
            testPhase4SimpleXlsxReaderStaxAndZipBomb(tmpDir);
            testPhase4WebSocketCaptureHandler();
            testPhase4ReportExporterTheMythOfCrawlJson(tmpDir);
            testEventLogRingBufferAndInference();
            testActionStepAndReplayPlanLifecycle();
            testLocalControlServerReplayEndpoints();
            testHtmlAgentInjectorAndControlPlaneWhenReplayActive();
            testAuditThemeContrastForeground();
            testWaveCAutoCrawlTelemetrySeedAndJsRoutes();
            testCoverageV11Upgrades(tmpDir);
            testSubPathScopeLockVnExpressScenario();
            System.out.println("ALL DEEP INTEGRATION TESTS PASSED (" + passed + " test groups verified).");
        } finally {
            deleteRecursive(tmpDir.toFile());
        }
    }

    private static void testControlPlaneHandler() {
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("app.example.com");
        HeaderFilterProfile hf = cfg.getHeaderFilterProfile();
        hf.setEnabled(true);
        hf.setApplyOnProxyCrawl(true);
        hf.setRules(Collections.singletonList(
            new HeaderFilterProfile.Rule(true, HeaderFilterProfile.Action.SET, "X-Account", "tenantA", "app.example.com")));

        CrawlState state = new CrawlState();
        state.setStatus(CrawlState.Status.RUNNING);
        state.setCurrentContext("/ > home > button \"Load\"", "button", "Load", "https://app.example.com/home");

        ControlPlaneHandler handler = new ControlPlaneHandler(cfg, state, 19999);
        HttpService appSvc = HttpService.httpService("app.example.com", 443, true);
        HttpService evilSvc = HttpService.httpService("evil.attacker.com", 443, true);

        // 1. Document navigation gets Accept-Encoding: identity, conditional headers stripped, X-Account injected
        Map<String, String> docHdrs = new LinkedHashMap<>();
        docHdrs.put("Host", "app.example.com");
        docHdrs.put("Accept", "text/html,application/xhtml+xml");
        docHdrs.put("Accept-Encoding", "gzip, deflate, br, zstd");
        docHdrs.put("If-None-Match", "\"etag-1\"");
        docHdrs.put("If-Modified-Since", "Mon, 01 Jan 2024 00:00:00 GMT");
        docHdrs.put("Sec-Fetch-Dest", "document");
        InterceptedRequest docReq = MockMontoyaSupport.mockInterceptedRequest(101, appSvc, "GET", "/home", docHdrs, new byte[0]);
        ProxyRequestReceivedAction docAct = handler.handleRequestReceived(docReq);
        assertEquals(MessageReceivedAction.CONTINUE, docAct.action(), "Document request should continue");
        assertEquals("identity", docAct.request().headerValue("Accept-Encoding"), "Document Accept-Encoding forced to identity");
        assertEquals("tenantA", docAct.request().headerValue("X-Account"), "HeaderFilterProfile X-Account applied");
        assertTrue(!docAct.request().hasHeader("If-None-Match"), "If-None-Match stripped on doc");
        assertTrue(!docAct.request().hasHeader("If-Modified-Since"), "If-Modified-Since stripped on doc");
        assertEquals(1, state.getInFlightCount(), "Tracked in-flight incremented");
        assertTrue(handler.consumeTrackedInFlight(101), "consumeTrackedInFlight returns true once");
        assertTrue(!handler.consumeTrackedInFlight(101), "consumeTrackedInFlight idempotent false on second call");

        // 2. JS / API request gets Accept-Encoding: gzip, deflate, br, identity (zstd stripped)
        Map<String, String> jsHdrs = new LinkedHashMap<>();
        jsHdrs.put("Host", "app.example.com");
        jsHdrs.put("Accept", "*/*");
        jsHdrs.put("Accept-Encoding", "gzip, deflate, br, zstd");
        jsHdrs.put("Sec-Fetch-Dest", "script");
        InterceptedRequest jsReq = MockMontoyaSupport.mockInterceptedRequest(102, appSvc, "GET", "/static/main.js", jsHdrs, new byte[0]);
        ProxyRequestReceivedAction jsAct = handler.handleRequestReceived(jsReq);
        assertEquals("gzip, deflate, br, identity", jsAct.request().headerValue("Accept-Encoding"), "JS Accept-Encoding strips zstd while preserving br/gzip/deflate");
        assertEquals("tenantA", jsAct.request().headerValue("X-Account"), "HeaderFilterProfile applied to JS/API");

        // 3. /__myth_crawl/* scope gating: allowed host routed to 127.0.0.1:19999 doNotIntercept; disallowed host NOT hijacked
        InterceptedRequest ctrlAllowed = MockMontoyaSupport.mockInterceptedRequest(103, appSvc, "GET", "/__myth_crawl/state", docHdrs, new byte[0]);
        ProxyRequestReceivedAction ctrlAllowedAct = handler.handleRequestReceived(ctrlAllowed);
        assertEquals(MessageReceivedAction.DO_NOT_INTERCEPT, ctrlAllowedAct.action(), "Allowed /__myth_crawl/ routed doNotIntercept");
        assertEquals("127.0.0.1", ctrlAllowedAct.request().httpService().host(), "Control routed to 127.0.0.1");
        assertEquals(19999, ctrlAllowedAct.request().httpService().port(), "Control routed to localControlPort");

        Map<String, String> evilHdrs = new LinkedHashMap<>();
        evilHdrs.put("Host", "evil.attacker.com");
        InterceptedRequest ctrlDisallowed = MockMontoyaSupport.mockInterceptedRequest(104, evilSvc, "GET", "/__myth_crawl/state", evilHdrs, new byte[0]);
        ProxyRequestReceivedAction ctrlDisallowedAct = handler.handleRequestReceived(ctrlDisallowed);
        assertEquals(MessageReceivedAction.CONTINUE, ctrlDisallowedAct.action(), "Disallowed third-party /__myth_crawl/ not routed to local control");
        assertEquals("evil.attacker.com", ctrlDisallowedAct.request().httpService().host(), "Disallowed host untouched");

        // Also blocked when STOPPED & disarmed even on allowed host
        state.setStatus(CrawlState.Status.STOPPED);
        handler.setAgentArmed(false);
        ProxyRequestReceivedAction stoppedCtrlAct = handler.handleRequestReceived(ctrlAllowed);
        assertEquals(MessageReceivedAction.CONTINUE, stoppedCtrlAct.action(), "Stopped + disarmed should not hijack /__myth_crawl/");

        // 4. Burst eviction (>8192) sweeps pendingMessages without unbounded leak
        state.setStatus(CrawlState.Status.RUNNING);
        for (int i = 1000; i < 1000 + 8300; i++) {
            state.bindMessageContext(i, "/ > burst", null, true);
        }
        assertTrue(state.getInFlightCount() < 8300, "Overflow burst sweep must evict stale tracked in-flight entries: " + state.getInFlightCount());

        passed++;
    }

    private static void testLocalControlServerTokenEnforcement() throws Exception {
        CrawlConfig cfg = new CrawlConfig();
        CrawlState state = new CrawlState();
        LocalControlServer srv = new LocalControlServer(cfg, state, null, null, true);
        srv.start();
        try {
            int port = srv.getPort();
            // Without X-Myth-Token -> 403
            HttpURLConnection noTok = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/state").openConnection();
            noTok.setRequestMethod("GET");
            assertEquals(403, noTok.getResponseCode(), "Missing X-Myth-Token must return 403");

            // With valid X-Myth-Token -> 200 and JSON state
            HttpURLConnection withTok = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/state").openConnection();
            withTok.setRequestMethod("GET");
            withTok.setRequestProperty("X-Myth-Token", srv.getSessionToken());
            assertEquals(200, withTok.getResponseCode(), "Valid X-Myth-Token must return 200");

            // Served agent.js embeds window.__MYTH_TOKEN__
            HttpURLConnection jsConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/agent.js").openConnection();
            assertEquals(200, jsConn.getResponseCode(), "agent.js returns 200");
            String jsText = new String(jsConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(jsText.contains(srv.getSessionToken()), "Served agent.js embeds session token");

            // /pause with token transitions state to PAUSED
            state.setStatus(CrawlState.Status.RUNNING);
            HttpURLConnection pauseConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/pause").openConnection();
            pauseConn.setRequestMethod("POST");
            pauseConn.setRequestProperty("X-Myth-Token", srv.getSessionToken());
            pauseConn.setDoOutput(true);
            try (OutputStream os = pauseConn.getOutputStream()) {
                os.write("{\"reason\":\"captcha\"}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, pauseConn.getResponseCode(), "POST /pause returns 200");
            assertEquals(CrawlState.Status.PAUSED, state.getStatus(), "State transitions to PAUSED");
            passed++;
        } finally {
            srv.stop();
        }
    }

    private static void testHtmlAgentInjector() throws Exception {
        MockMontoyaSupport.MockEnv env = new MockMontoyaSupport.MockEnv();
        MontoyaApi api = MockMontoyaSupport.mockMontoyaApi(env);

        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("app.example.com");
        cfg.setStrictExcelOnly(false);
        cfg.setCaptureJsEndpoints(true);
        cfg.setRelaxCspWhileCrawling(true);

        CrawlState state = new CrawlState();
        state.setStatus(CrawlState.Status.RUNNING);
        state.setCurrentContext("/ > dashboard > button \"Load\"", "button", "Load", "https://app.example.com/dashboard");

        ControlPlaneHandler cph = new ControlPlaneHandler(cfg, state, 19999);
        StaticJsScanner jsScanner = new StaticJsScanner(cfg, state, null);
        HtmlAgentInjector injector = new HtmlAgentInjector(api, cfg, state, cph, jsScanner, null, null, null);

        try {
            HttpService svc = HttpService.httpService("app.example.com", 443, true);

            // 1. BROTLI-compressed JS bundle -> decoded via CompressionUtils.BROTLI -> StaticJsScanner discovers /api/v1/secret
            String jsSrc = "const ep='/api/v1/secret'; const rt='/admin/users';";
            byte[] brBytes = MockMontoyaSupport.mockBrotliCompress(jsSrc.getBytes(StandardCharsets.UTF_8));
            HttpRequest jsReq = MockMontoyaSupport.mockInterceptedRequest(201, svc, "GET", "/assets/app.js",
                Collections.singletonMap("Host", "app.example.com"), new byte[0]);
            Map<String, String> brHdrs = new LinkedHashMap<>();
            brHdrs.put("Content-Type", "application/javascript");
            brHdrs.put("Content-Encoding", "br");
            InterceptedResponse brResp = MockMontoyaSupport.mockInterceptedResponse(
                201, jsReq, 200, brHdrs, brBytes, MimeType.SCRIPT, MimeType.SCRIPT, env);
            injector.handleResponseReceived(brResp);

            // Also GZIP JS bundle
            byte[] gzBytes = MockMontoyaSupport.gzipCompress("fetch('/api/v2/audit-log');".getBytes(StandardCharsets.UTF_8));
            HttpRequest gzReq = MockMontoyaSupport.mockInterceptedRequest(202, svc, "GET", "/assets/vendor.js",
                Collections.singletonMap("Host", "app.example.com"), new byte[0]);
            Map<String, String> gzHdrs = new LinkedHashMap<>();
            gzHdrs.put("Content-Type", "application/javascript");
            gzHdrs.put("Content-Encoding", "gzip");
            InterceptedResponse gzResp = MockMontoyaSupport.mockInterceptedResponse(
                202, gzReq, 200, gzHdrs, gzBytes, MimeType.SCRIPT, MimeType.SCRIPT, env);
            injector.handleResponseReceived(gzResp);

            // Wait for StaticJsScanner async threads
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                List<String> eps = state.getDiscoveredJsEndpoints();
                boolean hasSecret = eps.stream().anyMatch(s -> s.contains("/api/v1/secret"));
                boolean hasAudit = eps.stream().anyMatch(s -> s.contains("/api/v2/audit-log"));
                if (hasSecret && hasAudit) {
                    break;
                }
                Thread.sleep(20);
            }
            List<String> discovered = state.getDiscoveredJsEndpoints();
            assertTrue(env.brotliDecompressCalls.get() >= 1, "CompressionUtils.BROTLI invoked for br .js bundle");
            assertTrue(discovered.stream().anyMatch(s -> s.contains("/api/v1/secret")), "Brotli JS endpoint /api/v1/secret discovered");
            assertTrue(discovered.stream().anyMatch(s -> s.contains("/api/v2/audit-log")), "Gzip JS endpoint /api/v2/audit-log discovered");

            // 2. Comment-safe <head> injection + >1024B leading HTML comment preamble
            String padding = "<!-- " + "A".repeat(1600) + " <head>fake-comment-head</head> -->\n";
            String html = padding + "<!doctype html><html><head><title>App</title></head><body><h1>Hi</h1></body></html>";
            HttpRequest htmlReq = MockMontoyaSupport.mockInterceptedRequest(203, svc, "GET", "/dashboard",
                Collections.singletonMap("Host", "app.example.com"), new byte[0]);
            Map<String, String> htmlHdrs = new LinkedHashMap<>();
            htmlHdrs.put("Content-Type", "text/html; charset=utf-8");
            htmlHdrs.put("Content-Security-Policy", "default-src 'self'");
            InterceptedResponse htmlResp = MockMontoyaSupport.mockInterceptedResponse(
                203, htmlReq, 200, htmlHdrs, html.getBytes(StandardCharsets.UTF_8), MimeType.HTML, MimeType.HTML, env);
            ProxyResponseReceivedAction htmlAct = injector.handleResponseReceived(htmlResp);
            String outHtml = htmlAct.response().bodyToString();
            int commentIdx = outHtml.indexOf("<head>fake-comment-head</head>");
            int realHeadIdx = outHtml.indexOf("</title>") - 11; // after real <head>
            int agentIdx = outHtml.indexOf("data-myth-agent=\"1\"");
            assertTrue(agentIdx > commentIdx + "<head>fake-comment-head</head>".length(), "Agent script injected AFTER comment, not inside <!-- <head> -->");
            assertTrue(agentIdx < realHeadIdx + 20, "Agent script injected inside real <head> even past >1024B preamble");
            assertTrue(!htmlAct.response().hasHeader("Content-Security-Policy"), "CSP header relaxed while crawling");

            // 3. Static asset early skip (no ActionGroup record, no copyToTempFile)
            int tempBeforeStatic = env.copyToTempFileCalls.get();
            HttpRequest pngReq = MockMontoyaSupport.mockInterceptedRequest(204, svc, "GET", "/img/logo.png",
                Collections.singletonMap("Host", "app.example.com"), new byte[0]);
            InterceptedResponse pngResp = MockMontoyaSupport.mockInterceptedResponse(
                204, pngReq, 200, Collections.singletonMap("Content-Type", "image/png"), new byte[] { 1, 2, 3, 4 },
                MimeType.IMAGE_PNG, MimeType.IMAGE_PNG, env);
            injector.handleResponseReceived(pngResp);
            assertEquals(tempBeforeStatic, env.copyToTempFileCalls.get(), "Static asset must NOT trigger copyToTempFile");

            // 4. Bounded preview memory cap (> 256 KB response body capped to 256 KB on heap)
            byte[] largeJson = ("{\"items\":\"" + "x".repeat(400 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
            HttpRequest bigReq = MockMontoyaSupport.mockInterceptedRequest(205, svc, "GET", "/api/v1/export-dump",
                Collections.singletonMap("Host", "app.example.com"), new byte[0]);
            InterceptedResponse bigResp = MockMontoyaSupport.mockInterceptedResponse(
                205, bigReq, 200, Collections.singletonMap("Content-Type", "application/json"), largeJson,
                MimeType.JSON, MimeType.JSON, env);
            injector.handleResponseReceived(bigResp);

            long capDeadline = System.currentTimeMillis() + 3000L;
            CapturedApiEntry bigCap = null;
            while (System.currentTimeMillis() < capDeadline && bigCap == null) {
                for (CapturedApiEntry c : state.getExtraCaptures()) {
                    if ("/api/v1/export-dump".equals(c.requestPathWithQuery)) {
                        bigCap = c;
                        break;
                    }
                }
                if (bigCap == null) {
                    Thread.sleep(20);
                }
            }
            assertTrue(bigCap != null, "Large API capture /api/v1/export-dump recorded off-Proxy thread");
            assertEquals(CapturedApiEntry.MAX_INLINE_RESPONSE_BYTES, bigCap.responseBytes.length, "Inline responseBytes capped at 256 KB");
            assertEquals(largeJson.length, bigCap.responseLength, "Original responseLength preserved");
            assertTrue(bigCap.hasTempFileBacking(), "Temp file backing attached when MontoyaApi available");

            // Also verify CapturedApiEntry constructor itself caps oversized byte[] arrays
            CapturedApiEntry directCap = new CapturedApiEntry(
                "-", "GET", "https://app.example.com/big", "/big",
                "app.example.com", 443, true, "/ > big",
                new byte[16], largeJson, 200, largeJson.length,
                Collections.emptySet(), System.currentTimeMillis());
            assertTrue(directCap.responseTruncated, "Direct oversized CapturedApiEntry sets responseTruncated=true");
            assertEquals(CapturedApiEntry.MAX_INLINE_RESPONSE_BYTES, directCap.responseBytes.length, "Direct oversized responseBytes capped at 256 KB");
            passed++;
        } finally {
            injector.shutdown();
            jsScanner.shutdown();
        }
    }

    private static void testActionGroupStoreConcurrencyAndTriage() throws Exception {
        ActionGroupStore store = new ActionGroupStore();
        int writerThreads = 6;
        int readerThreads = 4;
        int callsPerWriter = 40;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writerThreads + readerThreads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int w = 0; w < writerThreads; w++) {
            final int wid = w;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < callsPerWriter; i++) {
                        ActionGroup g = store.openGroup("/ > mod" + wid + " > btn \"" + i + "\"", "button", String.valueOf(i), "https://app/mod" + wid);
                        CapturedApiEntry cap = new CapturedApiEntry(
                            "-", "POST", "https://app/api/item/" + wid + "/" + i, "/api/item/" + wid + "/" + i,
                            "app", 443, true, g.actionPath, new byte[0], new byte[0], 200, 10,
                            Collections.singleton("id"), System.currentTimeMillis());
                        store.recordCall(g, cap, "POST /api/item/" + wid + "/" + i, "", "", "", false, false, "JSON");
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        for (int r = 0; r < readerThreads; r++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < 80; i++) {
                        store.snapshotGroups();
                        store.snapshotNotes();
                        store.totalCalls();
                        store.version();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startGate.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent RW lock test finished within timeout");
        assertTrue(failure.get() == null, "Zero exceptions during concurrent reads/writes: " + failure.get());
        assertEquals(writerThreads * callsPerWriter, store.totalCalls(), "Exact totalCalls under concurrency");

        // Test triage metadata & version bump
        ActionGroup firstGroup = store.snapshotGroups().get(0);
        ApiCall firstCall = firstGroup.getCalls().get(0);
        long vBefore = store.version();
        store.updateCallTriage(firstCall, HighlightColor.ORANGE, ReviewStatus.INTERESTING, "BOLA candidate");
        assertTrue(store.version() > vBefore, "updateCallTriage bumps store version");
        assertEquals(HighlightColor.ORANGE, firstCall.getHighlightColor(), "HighlightColor updated");
        assertEquals(ReviewStatus.INTERESTING, firstCall.getReviewStatus(), "ReviewStatus updated");
        assertEquals("BOLA candidate", firstCall.getUserComment(), "User comment updated");

        // Test MAX_NOTES (30,000) strict accounting: severityCounts never diverges from stored notes
        ActionGroupStore noteStore = new ActionGroupStore();
        ActionGroup ng = noteStore.openGroup("/ > overflow", "button", "Go", "https://app/overflow");
        int totalAttempted = 30_250;
        for (int i = 0; i < totalAttempted; i++) {
            noteStore.recordStandaloneNote(ng, AuditNote.Severity.WARN, AuditNote.Type.NOT_IN_EXCEL,
                "GET", "/api/ep/" + i, "note-" + i);
        }
        assertEquals(30_000, noteStore.snapshotNotes().size(), "Stored notes capped at MAX_NOTES=30000");
        assertEquals(30_000, noteStore.noteCount(AuditNote.Severity.WARN), "severityCounts matches retained notes exactly");
        assertEquals(250, noteStore.getOverflowNoteCount(), "overflowNoteCount tracks exact dropped note count");
        passed++;
    }

    private static void testExtenderPushZeroWireModificationAndPostResetUid(Path tmpDir) throws Exception {
        MockMontoyaSupport.MockEnv env = new MockMontoyaSupport.MockEnv();
        MontoyaApi api = MockMontoyaSupport.mockMontoyaApi(env);

        CrawlConfig cfg = new CrawlConfig();
        ActionGroupStore store = new ActionGroupStore();
        ActionGroup grp = store.openGroup("/ > checkout > button \"Pay\"", "button", "Pay", "https://shop.test/checkout");

        String rawReqStr = "POST /api/orders HTTP/1.1\r\n"
            + "Host: shop.test\r\n"
            + "Content-Type: application/json\r\n"
            + "X-Custom-Client: mobile-v2\r\n"
            + "\r\n"
            + "{\"sku\":\"A1\",\"qty\":2}";
        byte[] rawReqBytes = rawReqStr.getBytes(StandardCharsets.UTF_8);

        CapturedApiEntry cap = new CapturedApiEntry(
            "7", "POST", "https://shop.test/api/orders", "/api/orders",
            "shop.test", 443, true, grp.actionPath,
            rawReqBytes, "HTTP/1.1 200 OK\r\n\r\n{}".getBytes(StandardCharsets.UTF_8),
            200, 2, new LinkedHashSet<>(Arrays.asList("json.sku", "json.qty")),
            System.currentTimeMillis(), null, HttpMode.HTTP_2, "");
        ApiCall call1 = store.recordCall(grp, cap, "POST /api/orders", "", "7", "/api/orders", true, false, "JSON");

        ExtenderPushManager pushMgr = new ExtenderPushManager(api, cfg, null, null);
        api.http().registerHttpHandler(pushMgr);
        assertEquals(1, pushMgr.enqueueCalls(Collections.singletonList(call1)), "First call enqueued");
        assertEquals(0, pushMgr.enqueueCalls(Collections.singletonList(call1)), "Duplicate callUid rejected");

        // Trigger async push & verify strict packet integrity + HttpMode + Annotations
        assertTrue(pushMgr.startPushAsync(0), "startPushAsync started");
        long deadline = System.currentTimeMillis() + 3000L;
        while (pushMgr.isPushing() && System.currentTimeMillis() < deadline) {
            Thread.sleep(15);
        }
        assertEquals(1, env.sentRequests.size(), "One request dispatched via api.http().sendRequest");
        MockMontoyaSupport.SentRecord sent = env.sentRequests.get(0);
        assertEquals(HttpMode.HTTP_2, sent.mode, "Original HttpMode.HTTP_2 preserved");
        assertTrue(Arrays.equals(rawReqBytes, sent.rawBytes), "Outgoing HTTP wire bytes MUST be byte-for-byte identical");
        assertEquals(1, env.capturedHandlerNotes.size(), "HttpHandler captured Burp annotation");
        assertTrue(env.capturedHandlerNotes.get(0).contains("[theMythOfCrawl] / > checkout > button \"Pay\""),
            "Burp annotation carries group path without modifying HTTP packet");

        // Verify handleHttpRequestToBeSent & handleHttpResponseReceived attach Annotations without altering wire bytes
        HttpRequest reqObj = cap.toMontoyaRequest(HttpService.httpService("shop.test", 443, true));
        // Simulate active push window via a fresh call + handler invocation
        RequestToBeSentAction reqAct = pushMgr.handleHttpRequestToBeSent(
            MockMontoyaSupport.mockToolRequestToBeSent(901, reqObj, ToolType.EXTENSIONS));
        assertTrue(Arrays.equals(rawReqBytes, reqAct.request().toByteArray().getBytes()), "HttpHandler never modifies request bytes");
        ResponseReceivedAction respAct = pushMgr.handleHttpResponseReceived(
            MockMontoyaSupport.mockToolResponseReceived(901, reqObj,
                HttpResponse.httpResponse("HTTP/1.1 200 OK\r\n\r\n{}"), ToolType.EXTENSIONS));
        assertTrue(respAct.response() != null, "HttpHandler returns response cleanly");

        // Verify post-reset callUid non-collision: reset store -> new groupId=1, index=1 -> enqueue must SUCCEED
        store.clear();
        ActionGroup postResetGrp = store.openGroup("/ > reset > button \"Save\"", "button", "Save", "https://shop.test/reset");
        assertEquals(1, postResetGrp.id, "After store.clear(), groupId restarts at 1");
        ApiCall postResetCall = store.recordCall(postResetGrp, cap, "POST /api/orders", "", "7", "/api/orders", true, false, "JSON");
        assertEquals(1, postResetCall.index, "After store.clear(), callIndex restarts at 1");
        assertTrue(postResetCall.callUid != call1.callUid, "Monotonic callUid must NOT collide after reset");
        assertEquals(1, pushMgr.enqueueCalls(Collections.singletonList(postResetCall)), "Post-reset (groupId=1,index=1) with distinct callUid enqueues cleanly");

        // Verify CSV formula injection neutralization in exportLedgerCsv
        ActionGroup formulaGrp = store.openGroup("=HYPERLINK(\"http://evil\")", "button", "=cmd", "https://shop.test/f");
        CapturedApiEntry formulaCap = new CapturedApiEntry(
            "+1", "GET", "https://shop.test/@calc", "/@calc", "shop.test", 443, true,
            formulaGrp.actionPath, rawReqBytes, new byte[0], 200, 0,
            Collections.singleton("-formula"), System.currentTimeMillis());
        ApiCall formulaCall = store.recordCall(formulaGrp, formulaCap, "GET /@calc", "", "+1", "/@calc", true, false, "JSON");
        pushMgr.enqueueCalls(Collections.singletonList(formulaCall));

        File csvFile = tmpDir.resolve("push_ledger.csv").toFile();
        pushMgr.exportLedgerCsv(csvFile);
        String csvText = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(csvText.contains("\"'=HYPERLINK"), "Leading '=' neutralized with apostrophe");
        assertTrue(csvText.contains("\"'+1\""), "Leading '+' neutralized with apostrophe");
        assertTrue(csvText.contains("\"/@calc\""), "Safe path preserved");
        passed++;
    }

    private static void testGroupTableModelAndBurpFilterBar() throws Exception {
        ActionGroupStore store = new ActionGroupStore();
        ActionGroup gLoad = store.openGroup("/ > users > page load", "page load", "", "https://app/users");
        ActionGroup gSave = store.openGroup("/ > users > button \"Save\"", "button", "Save", "https://app/users");

        CapturedApiEntry cap1 = new CapturedApiEntry(
            "1", "GET", "https://app/api/users?page=1", "/api/users?page=1",
            "app", 443, true, gLoad.actionPath, new byte[0], new byte[0], 200, 128,
            Collections.singleton("page"), System.currentTimeMillis());
        CapturedApiEntry cap2 = new CapturedApiEntry(
            "1", "POST", "https://app/api/users", "/api/users",
            "app", 443, true, gSave.actionPath, new byte[0], new byte[0], 403, 64,
            new LinkedHashSet<>(Arrays.asList("name", "role")), System.currentTimeMillis());

        ApiCall c1 = store.recordCall(gLoad, cap1, "GET /api/users", "", "1", "/api/users", true, false, "JSON");
        ApiCall c2 = store.recordCall(gSave, cap2, "POST /api/users", "", "1", "/api/users", true, false, "JSON");
        store.updateCallTriage(c2, HighlightColor.RED, ReviewStatus.INTERESTING, "IDOR check");

        // 1. Hierarchical 1 / 1.1 numbering & Column Class<?> integrity
        GroupTableModel.ActionGroupsModel groupsModel = new GroupTableModel.ActionGroupsModel(store);
        groupsModel.setTreeMode(true);
        groupsModel.refresh();
        assertTrue(groupsModel.getRowCount() >= 3, "Tree mode emits folder + 2 leaf rows");
        assertEquals("v 1", String.valueOf(groupsModel.getValueAt(0, 0)), "Top folder numbered '1'");
        assertEquals("1.1", String.valueOf(groupsModel.getValueAt(1, 0)), "First child numbered '1.1'");
        assertEquals("1.2", String.valueOf(groupsModel.getValueAt(2, 0)), "Second child numbered '1.2'");

        groupsModel.setTreeMode(false);
        assertEquals(Integer.class, groupsModel.getColumnClass(0), "Flat ActionGroupsModel col 0 is Integer.class");
        assertEquals(Integer.class, groupsModel.getColumnClass(2), "Flat ActionGroupsModel col 2 (APIs) is Integer.class");

        GroupTableModel.GroupApisModel apisModel = new GroupTableModel.GroupApisModel(store);
        apisModel.setTreeMode(false);
        apisModel.setGroupsAndFilter(Arrays.asList(gLoad, gSave), false, false, "");
        assertEquals(Integer.class, apisModel.getColumnClass(0), "Flat GroupApisModel col 0 (#) is Integer.class");
        assertEquals(Integer.class, apisModel.getColumnClass(4), "Flat GroupApisModel col 4 (HTTP) is Integer.class");
        assertEquals(Integer.class, apisModel.getColumnClass(6), "Flat GroupApisModel col 6 (Hits) is Integer.class");

        // Overlap summary
        GroupTableModel.PathOverlapSummary overlap = GroupTableModel.summarizeCallsForPath("/api/users", Arrays.asList(c1, c2));
        assertEquals(2, overlap.totalCalls, "Overlap totalCalls=2");
        assertEquals(2, overlap.distinctLocations, "Overlap distinctLocations=2");
        assertTrue(!overlap.allIdentical, "Overlap detects differing methods/params/statuses");

        // 2. Headless BurpFilterBar + negative !exclude + QuickChips + Regex
        SwingUtilities.invokeAndWait(() -> {
            BurpFilterBar bar = new BurpFilterBar(null);
            bar.setCounts(1, 2);

            // Access FilterCriteria matching via constructor-accessible FilterCriteria or public API
            assertTrue(bar.getCriteria().matches("GET", 200, "JSON", 1, false, true, false, "/api/users"), "Empty filter matches");

            // Negative !exclude tokens: "api !health !css"
            // Simulate user entering text and committing via ActionListener on txtSearch? Use reflection-free UI or check FilterCriteria
            bar.setDrawerExpanded(true);
            bar.clearFilters();
        });

        // Direct FilterCriteria verification (positive + !negative + Regex + QuickChips)
        java.lang.reflect.Constructor<BurpFilterBar.FilterCriteria> ctor = BurpFilterBar.FilterCriteria.class
            .getDeclaredConstructor(String.class, boolean.class, String.class, String.class, String.class, Set.class);
        ctor.setAccessible(true);

        BurpFilterBar.FilterCriteria negCrit = ctor.newInstance(
            "/api/ !health !png", false, "", "", "", EnumSet.noneOf(BurpFilterBar.QuickChip.class));
        assertTrue(negCrit.matches("GET", 200, "JSON", 1, false, true, false, "/api/users"), "Matches /api/users");
        assertTrue(!negCrit.matches("GET", 200, "JSON", 0, false, false, false, "/api/health"), "!health excludes /api/health");
        assertTrue(!negCrit.matches("GET", 200, "IMAGE", 0, false, false, false, "/api/logo.png"), "!png excludes .png");

        BurpFilterBar.FilterCriteria chipCrit = ctor.newInstance(
            "", false, "POST", "401/403", "JSON",
            EnumSet.of(BurpFilterBar.QuickChip.AUTH_401_403, BurpFilterBar.QuickChip.INTERESTING, BurpFilterBar.QuickChip.MUTATING));
        assertTrue(chipCrit.matches("POST", 403, "application/json", 2, true, true, true, "/api/users"), "Chip+dropdown combo matches 403 POST interesting");
        assertTrue(!chipCrit.matches("GET", 200, "application/json", 1, false, true, false, "/api/users"), "Chip+dropdown excludes 200 GET");

        BurpFilterBar.FilterCriteria badRegex = ctor.newInstance(
            "[unclosed", true, "", "", "", EnumSet.noneOf(BurpFilterBar.QuickChip.class));
        assertTrue(!badRegex.regexError.isEmpty(), "Invalid regex captures error message instead of throwing");

        passed++;
    }

    private static void testPhase4ParamExtractorProtocols() throws Exception {
        // 1. Batched GraphQL JSON array
        String batchedJson = "[{\"operationName\":\"GetUser\",\"query\":\"query GetUser { user { id } }\"},"
            + "{\"operationName\":\"UpdateRole\",\"query\":\"mutation UpdateRole { ok }\"}]";
        assertEquals("GetUser + UpdateRole",
            ParamExtractor.extractGraphqlOperation("application/json", batchedJson.getBytes(StandardCharsets.UTF_8)),
            "Batched GraphQL array operations joined");

        // 2. GET GraphQL + stringified JSON variables
        String getGqlPath = "/graphql?operationName=ListItems&variables=%7B%22limit%22%3A20%2C%22filter%22%3A%7B%22active%22%3Atrue%7D%7D";
        assertEquals("ListItems",
            ParamExtractor.extractGraphqlOperation(getGqlPath, "", new byte[0]),
            "GET GraphQL operationName parsed");
        Set<String> getGqlParams = ParamExtractor.extractParameterKeys(getGqlPath, "", new byte[0]);
        assertTrue(getGqlParams.contains("json.variables.limit") && getGqlParams.contains("json.variables.filter.active"),
            "GET GraphQL variables JSON unpacked into parameter keys");

        // 3. GraphQL HTTP 200 error payload helper
        List<String> gqlErrs = ParamExtractor.extractGraphqlResponseErrors("application/json",
            "{\"data\":null,\"errors\":[{\"message\":\"Unauthorized\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, gqlErrs.size(), "GraphQL error count");
        assertEquals("Unauthorized", gqlErrs.get(0), "GraphQL error message extracted");

        // 4. Binary-safe Multipart: mixed-case boundary preserved + embedded fake Content-Disposition inside binary bytes must NOT false-match
        String bnd = "----WebKitFormBoundaryAbC123";
        ByteArrayOutputStream mp = new ByteArrayOutputStream();
        mp.write(("--" + bnd + "\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"pic.bin\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        mp.write(new byte[] { 0x00, 0x01, (byte) 0xFF, (byte) 0xFE });
        mp.write("Content-Disposition: form-data; name=\"poison\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        mp.write(("\r\n--" + bnd + "\r\nContent-Disposition: form-data; name=\"meta\"\r\n"
            + "Content-Type: application/json\r\n\r\n{\"role\":\"admin\"}\r\n--" + bnd + "--\r\n")
            .getBytes(StandardCharsets.UTF_8));
        Set<String> mpKeys = ParamExtractor.extractParameterKeys(
            "/api/upload", "multipart/form-data; boundary=" + bnd, mp.toByteArray());
        assertTrue(mpKeys.contains("form.upload"), "Binary multipart file field extracted");
        assertTrue(mpKeys.contains("form.meta") && mpKeys.contains("form.meta.role"), "JSON metadata part unpacked");
        assertTrue(!mpKeys.contains("form.poison"), "Fake Content-Disposition inside binary bytes MUST NOT false-match");

        // 5. gRPC-Web (+proto binary & -text Base64): 5-byte framing + protobuf wire fields + trailer frame
        byte[] pbMsg = new byte[] {
            0x08, 0x2A,                                     // field 1 varint = 42
            0x12, 0x05, 'h', 'e', 'l', 'l', 'o'             // field 2 length-delimited = "hello"
        };
        byte[] trailer = "grpc-status: 0\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream grpcBuf = new ByteArrayOutputStream();
        grpcBuf.write(0x00); // data frame
        grpcBuf.write(new byte[] { 0, 0, 0, (byte) pbMsg.length });
        grpcBuf.write(pbMsg);
        grpcBuf.write(0x80); // trailer frame
        grpcBuf.write(new byte[] { 0, 0, 0, (byte) trailer.length });
        grpcBuf.write(trailer);
        byte[] grpcRaw = grpcBuf.toByteArray();

        Set<String> protoKeys = ParamExtractor.extractParameterKeys("/rpc.Svc/Call", "application/grpc-web+proto", grpcRaw);
        assertTrue(protoKeys.contains("pb.f1") && protoKeys.contains("pb.f2") && protoKeys.contains("grpc.trailer.grpc-status"),
            "gRPC-Web+proto decoded protobuf wire fields + trailer: " + protoKeys);

        byte[] grpcText = Base64.getEncoder().encode(grpcRaw);
        Set<String> textKeys = ParamExtractor.extractParameterKeys("/rpc.Svc/Call", "application/grpc-web-text", grpcText);
        assertEquals(protoKeys, textKeys, "gRPC-Web-text Base64 matches +proto keys");

        passed++;
    }

    private static void testPhase4SimpleXlsxReaderStaxAndZipBomb(Path tmpDir) throws Exception {
        // 1. StAX + <rPh> phonetic run skip + relative "../xl/worksheets/sheet1.xml" target resolution
        File xlsx = tmpDir.resolve("stax_rph.xlsx").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(xlsx.toPath()))) {
            zos.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zos.write(("<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"APIs\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            zos.write(("<Relationships>"
                + "<Relationship Id=\"rId1\" Target=\"../xl/worksheets/sheet1.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zos.write(("<sst><si><t>GET</t><rPh sb=\"0\" eb=\"1\"><t>PHONETIC_NOISE</t></rPh></si>"
                + "<si><t>/api/orders</t></si></sst>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zos.write(("<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                + "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        SimpleXlsxReader.WorkbookData wb = SimpleXlsxReader.readFile(xlsx);
        List<List<String>> rows = wb.getRows("APIs");
        assertEquals(1, rows.size(), "Resolved ../xl/worksheets/sheet1.xml cleanly");
        assertEquals("GET", rows.get(0).get(0), "<rPh> phonetic text skipped in StAX sharedStrings");
        assertEquals("/api/orders", rows.get(0).get(1), "Second shared string intact");

        // 2. Zip-bomb guard (> 40 MB uncompressed entry rejected)
        File bombXlsx = tmpDir.resolve("bomb.xlsx").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(bombXlsx.toPath()))) {
            zos.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 42; i++) {
                zos.write(zeros);
            }
            zos.closeEntry();
        }
        boolean bombRejected = false;
        try {
            SimpleXlsxReader.readFile(bombXlsx);
        } catch (IOException ex) {
            bombRejected = ex.getMessage() != null && ex.getMessage().contains("40 MB");
        }
        assertTrue(bombRejected, "SimpleXlsxReader rejects >40 MB zip-bomb entry");
        passed++;
    }

    private static void testPhase4WebSocketCaptureHandler() throws Exception {
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("ws.example.com");
        CrawlState state = new CrawlState();
        state.setStatus(CrawlState.Status.RUNNING);
        state.setCurrentContext("/ > live > button \"Connect\"", "button", "Connect", "https://ws.example.com/live");

        WebSocketCaptureHandler wsHandler = new WebSocketCaptureHandler(cfg, state, () -> false, null, null, null);
        try {
            HttpService wsSvc = HttpService.httpService("ws.example.com", 443, true);
            HttpRequest upgradeReq = MockMontoyaSupport.mockInterceptedRequest(
                801, wsSvc, "GET", "/socket", Collections.singletonMap("Host", "ws.example.com"), new byte[0]);

            AtomicReference<ProxyMessageHandler> registered = new AtomicReference<>();
            ProxyWebSocket mockWs = (ProxyWebSocket) Proxy.newProxyInstance(
                DeepCrawlerTests.class.getClassLoader(),
                new Class<?>[] { ProxyWebSocket.class },
                (p, m, args) -> {
                    if ("registerProxyMessageHandler".equals(m.getName()) && args != null && args.length == 1) {
                        registered.set((ProxyMessageHandler) args[0]);
                    }
                    return null;
                });
            ProxyWebSocketCreation creation = (ProxyWebSocketCreation) Proxy.newProxyInstance(
                DeepCrawlerTests.class.getClassLoader(),
                new Class<?>[] { ProxyWebSocketCreation.class },
                (p, m, args) -> {
                    if ("upgradeRequest".equals(m.getName())) return upgradeReq;
                    if ("proxyWebSocket".equals(m.getName())) return mockWs;
                    return null;
                });

            wsHandler.handleWebSocketCreation(creation);
            ProxyMessageHandler msgHandler = registered.get();
            assertTrue(msgHandler != null, "WebSocketCaptureHandler registered ProxyMessageHandler for allowed host");

            InterceptedTextMessage txtMsg = (InterceptedTextMessage) Proxy.newProxyInstance(
                DeepCrawlerTests.class.getClassLoader(),
                new Class<?>[] { InterceptedTextMessage.class },
                (p, m, args) -> {
                    if ("payload".equals(m.getName())) {
                        return "{\"operationName\":\"SubAlerts\",\"query\":\"subscription SubAlerts { alert }\",\"variables\":{\"room\":\"sec\"}}";
                    }
                    if ("direction".equals(m.getName())) return Direction.CLIENT_TO_SERVER;
                    return null;
                });
            InterceptedBinaryMessage binMsg = (InterceptedBinaryMessage) Proxy.newProxyInstance(
                DeepCrawlerTests.class.getClassLoader(),
                new Class<?>[] { InterceptedBinaryMessage.class },
                (p, m, args) -> {
                    if ("payload".equals(m.getName())) return ByteArray.byteArray(new byte[] { 1, 2, 3, 4 });
                    if ("direction".equals(m.getName())) return Direction.SERVER_TO_CLIENT;
                    return null;
                });

            msgHandler.handleTextMessageReceived(txtMsg);
            msgHandler.handleBinaryMessageReceived(binMsg);

            ActionGroupStore store = state.getActionGroups();
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && store.totalCalls() < 2) {
                Thread.sleep(20);
            }
            assertEquals(2, store.totalCalls(), "Both WS text & binary frames recorded asynchronously");
            List<ApiCall> calls = store.snapshotGroups().get(0).getCalls();
            ApiCall sendCall = calls.get(0);
            ApiCall recvCall = calls.get(1);
            assertEquals("WS_SEND", sendCall.capture.method, "Text frame method tagged WS_SEND");
            assertEquals("SubAlerts", sendCall.graphqlOperation, "GraphQL-WS operationName extracted");
            assertTrue(sendCall.capture.paramKeys.contains("ws.variables.room"), "WS JSON nested param extracted");
            assertEquals("WEBSOCKET_TEXT", sendCall.mimeType, "MIME tagged WEBSOCKET_TEXT");
            assertEquals("WS_RECV", recvCall.capture.method, "Binary frame method tagged WS_RECV");
            assertEquals("WEBSOCKET_BIN", recvCall.mimeType, "MIME tagged WEBSOCKET_BIN");
            passed++;
        } finally {
            wsHandler.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testPhase4ReportExporterTheMythOfCrawlJson(Path tmpDir) throws Exception {
        ActionGroupStore store = new ActionGroupStore();
        ActionGroup grp = store.openGroup("/ > transfer > button \"Submit\"", "button", "Submit", "https://bank.test/transfer");
        byte[] rawReq = ("POST /api/transfer HTTP/1.1\r\nHost: bank.test\r\n\r\n{\"amount\":100}").getBytes(StandardCharsets.UTF_8);

        CapturedApiEntry httpCap = new CapturedApiEntry(
            "3", "POST", "https://bank.test/api/transfer", "/api/transfer",
            "bank.test", 443, true, grp.actionPath, rawReq, new byte[0], 200, 12,
            Collections.singleton("json.amount"), System.currentTimeMillis());
        store.recordCall(grp, httpCap, "POST /api/transfer", "", "3", "/api/transfer", true, false, "JSON");

        CapturedApiEntry wsCap = new CapturedApiEntry(
            "-", "WS_SEND", "wss://bank.test/ws", "/ws",
            "bank.test", 443, true, grp.actionPath, new byte[0], new byte[0], 101, 4,
            Collections.emptySet(), System.currentTimeMillis());
        store.recordCall(grp, wsCap, "WS_SEND /ws", "", "", "", false, false, "WEBSOCKET_TEXT");

        File out = tmpDir.resolve("tmoc_handoff.json").toFile();
        ReportExporter.exportForTheMythOfCrawlJson(out, null, store);
        Map<String, Object> parsed = MiniJson.parseObject(Files.readString(out.toPath(), StandardCharsets.UTF_8));
        assertEquals("theMythOfCrawl", MiniJson.asString(parsed, "extension", ""), "Extension marker matches");
        List<String> headers = MiniJson.asStringList(parsed, "headers");
        assertEquals(Arrays.asList("STT", "PROTOCOL", "PATH", "POSITION"), headers, "tmoc_core headers present");

        List<?> entries = (List<?>) parsed.get("entries");
        List<?> capturedReqs = (List<?>) parsed.get("capturedRequests");
        assertEquals(1, entries.size(), "WS_SEND excluded, 1 HTTP entry exported");
        assertEquals(1, capturedReqs.size(), "1 raw capturedRequest exported");
        Map<String, Object> cr0 = (Map<String, Object>) capturedReqs.get(0);
        String b64 = String.valueOf(cr0.get("rawRequestBase64"));
        assertTrue(Arrays.equals(rawReq, Base64.getDecoder().decode(b64)), "rawRequestBase64 decodes to exact original request");
        passed++;
    }

    private static void testEventLogRingBufferAndInference() {
        CrawlState state = new CrawlState();
        long v0 = state.getEventLogVersion();

        EventLogEntry agentEntry = state.addEventLog("[Agent] Đã bơm script vào trang: https://app.test");
        EventLogEntry errEntry = state.addEventLog("CẢNH BÁO: 15 request liên tiếp trả 401 -> Tạm dừng");
        EventLogEntry critEntry = state.addEventLog("Critical OutOfMemory condition detected");
        EventLogEntry pushEntry = state.addEventLog("Hoàn tất đẩy qua Extender: thành công 5");

        assertEquals("Agent", agentEntry.source, "Inferred source Agent");
        assertEquals(EventLogEntry.Level.INFO, agentEntry.level, "Inferred level INFO");
        assertEquals(EventLogEntry.Level.ERROR, errEntry.level, "Inferred level ERROR");
        assertEquals(EventLogEntry.Level.CRITICAL, critEntry.level, "Inferred level CRITICAL");
        assertEquals("Push", pushEntry.source, "Inferred source Push");
        assertTrue(state.getEventLogVersion() >= v0 + 4, "getEventLogVersion increments per event");

        // Verify 1500-entry ring buffer cap
        for (int i = 0; i < 1600; i++) {
            state.addEventLog(EventLogEntry.Level.DEBUG, "Proxy", "msg-" + i);
        }
        List<EventLogEntry> snap = state.snapshotEventLogs();
        assertEquals(1500, snap.size(), "Event log ring buffer capped at 1500 entries");
        assertEquals("msg-1599", snap.get(snap.size() - 1).message, "Newest event retained");

        long vBeforeClear = state.getEventLogVersion();
        state.clearEventLogs();
        assertEquals(0, state.snapshotEventLogs().size(), "clearEventLogs empties buffer");
        assertTrue(state.getEventLogVersion() > vBeforeClear, "clearEventLogs bumps version");
        passed++;
    }

    private static void testActionStepAndReplayPlanLifecycle() {
        CrawlState state = new CrawlState();
        ActionGroupStore store = state.getActionGroups();

        String cleanSpaUrl = "https://spa.example.com/app?tab=billing#/workspace/orders";
        state.setCurrentContext("/ > workspace > orders > page load", "page load", "", cleanSpaUrl, null);
        ActionStep locator = new ActionStep(
            1, cleanSpaUrl, "", Arrays.asList("#app-shell"), "button[data-testid=\"submit-order\"]",
            "button", "button", "button", "Đặt hàng", "", "/ > workspace > orders > button \"Đặt hàng\"",
            Collections.singletonList(Map.of("selector", "input[name=\"note\"]", "label", "Ghi chú", "value", "TEXT")));
        ActionGroup grp = state.setCurrentContext(
            "/ > workspace > orders > button \"Đặt hàng\"", "button", "Đặt hàng", cleanSpaUrl, locator);

        CapturedApiEntry cap = new CapturedApiEntry(
            "1", "POST", "https://spa.example.com/api/orders", "/api/orders",
            "spa.example.com", 443, true, grp.actionPath, new byte[0], new byte[0], 200, 10,
            Collections.singleton("note"), System.currentTimeMillis());
        ApiCall call = store.recordCall(grp, cap, "POST /api/orders", "", "1", "/api/orders", true, false, "JSON");

        List<ActionStep> trail = call.getActionTrail();
        assertTrue(trail.size() >= 2, "Recorded ActionTrail has page load + trigger step");
        assertEquals("button[data-testid=\"submit-order\"]", trail.get(trail.size() - 1).cssSelector, "Locator preserved on ApiCall");

        // Start ReplayPlan -> startUrl MUST remain clean without mutating ?query or #/hash
        ReplayPlan plan = state.startReplayPlan(call, true);
        assertTrue(plan != null && plan.isActive(), "ReplayPlan active");
        assertEquals(cleanSpaUrl, plan.getStartUrl(), "startUrl preserves ?query and #/hash cleanly");
        assertTrue(state.hasActiveReplayPlan(), "hasActiveReplayPlan is true");
        assertTrue(state.isReplayHostAllowed("spa.example.com"), "isReplayHostAllowed matches startUrl host");

        // Update step & mode
        ReplayPlan updated = state.updateReplayStep(plan.getPlanId(), 1, false);
        assertEquals(1, updated.getCurrentIndex(), "currentIndex updated to 1");
        assertTrue(!updated.isAutoPlay(), "autoPlay toggled to false");

        // Fallback trail synthesis when no DOM locator recorded
        ActionGroup fallbackGrp = store.openGroup("/ > login > popup \"Đăng nhập\" > button \"Gửi\"", "button", "Gửi", "https://auth.example.com/login");
        ReplayPlan fbPlan = state.startReplayPlan(fallbackGrp, "Gửi", "POST /api/login", "https://auth.example.com/login", false);
        assertTrue(fbPlan.getSteps().size() >= 2, "Fallback trail synthesized from actionPath");
        assertEquals("https://auth.example.com/login", fbPlan.getStartUrl(), "Fallback startUrl clean");

        // Stop plan
        state.stopReplayPlan(fbPlan.getPlanId());
        assertTrue(!state.hasActiveReplayPlan(), "stopReplayPlan deactivates active plan");
        passed++;
    }

    @SuppressWarnings("unchecked")
    private static void testLocalControlServerReplayEndpoints() throws Exception {
        CrawlConfig cfg = new CrawlConfig();
        CrawlState state = new CrawlState();
        LocalControlServer srv = new LocalControlServer(cfg, state, null, null, true);
        srv.start();
        try {
            int port = srv.getPort();
            String tok = srv.getSessionToken();

            // 1. POST /__myth_crawl/context with stepLocator
            String ctxBody = "{"
                + "\"actionPath\":\"/ > profile > button \\\"Lưu\\\"\","
                + "\"kind\":\"button\",\"label\":\"Lưu\","
                + "\"pageUrl\":\"https://target.local/profile?v=1#/edit\","
                + "\"stepLocator\":{"
                + "  \"cssSelector\":\"button[data-testid=\\\"save-btn\\\"]\","
                + "  \"shadowSelectors\":[\"profile-card\"],"
                + "  \"framePath\":\"\","
                + "  \"tag\":\"button\",\"role\":\"button\","
                + "  \"filledFields\":[{\"selector\":\"input[name=\\\"bio\\\"]\",\"label\":\"Bio\",\"value\":\"TEXT\"}]"
                + "}}";
            HttpURLConnection ctxConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/context").openConnection();
            ctxConn.setRequestMethod("POST");
            ctxConn.setRequestProperty("X-Myth-Token", tok);
            ctxConn.setRequestProperty("Content-Type", "application/json");
            ctxConn.setDoOutput(true);
            try (OutputStream os = ctxConn.getOutputStream()) {
                os.write(ctxBody.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, ctxConn.getResponseCode(), "POST /context returns 200");

            ActionGroup grp = state.getCurrentGroup();
            assertTrue(grp != null && !grp.getActionTrail().isEmpty(), "ActionGroup recorded stepLocator trail");
            ActionStep last = grp.getActionTrail().get(grp.getActionTrail().size() - 1);
            assertEquals("button[data-testid=\"save-btn\"]", last.cssSelector, "Parsed stepLocator.cssSelector");
            assertEquals(Collections.singletonList("profile-card"), last.shadowSelectors, "Parsed shadowSelectors");
            assertEquals(1, last.filledFields.size(), "Parsed filledFields");

            // Start ReplayPlan with 2 steps
            ActionStep s0 = new ActionStep(0, "https://target.local/profile?v=1#/edit", "", Collections.emptyList(), "", "", "", "page load", "Trang", "", "", Collections.emptyList());
            grp.setActionTrail(Arrays.asList(s0, last));
            ReplayPlan rp = state.startReplayPlan(grp, "Lưu", "POST /api/profile", "https://target.local/profile?v=1#/edit", true);

            // 2. GET /__myth_crawl/state exposes replayPlan
            HttpURLConnection stConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/state").openConnection();
            stConn.setRequestProperty("X-Myth-Token", tok);
            Map<String, Object> stJson = MiniJson.parseObject(new String(stConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(stJson.get("replayPlan") instanceof Map<?, ?>, "/state includes active replayPlan");

            // 3. GET & POST /__myth_crawl/replay_plan (step, mode, stop)
            HttpURLConnection rpGet = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/replay_plan").openConnection();
            rpGet.setRequestProperty("X-Myth-Token", tok);
            assertEquals(200, rpGet.getResponseCode(), "GET /replay_plan returns 200");

            HttpURLConnection rpStep = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/replay_plan").openConnection();
            rpStep.setRequestMethod("POST");
            rpStep.setRequestProperty("X-Myth-Token", tok);
            rpStep.setDoOutput(true);
            try (OutputStream os = rpStep.getOutputStream()) {
                os.write(("{\"action\":\"step\",\"planId\":\"" + rp.getPlanId() + "\",\"currentIndex\":1,\"autoPlay\":false}")
                    .getBytes(StandardCharsets.UTF_8));
            }
            Map<String, Object> stepResp = MiniJson.parseObject(new String(rpStep.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> rpMap = (Map<String, Object>) stepResp.get("replayPlan");
            assertEquals(1, ((Number) rpMap.get("currentIndex")).intValue(), "POST /replay_plan updated currentIndex=1");
            assertEquals(Boolean.FALSE, rpMap.get("autoPlay"), "POST /replay_plan updated autoPlay=false");

            HttpURLConnection rpStop = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/replay_plan").openConnection();
            rpStop.setRequestMethod("POST");
            rpStop.setRequestProperty("X-Myth-Token", tok);
            rpStop.setDoOutput(true);
            try (OutputStream os = rpStop.getOutputStream()) {
                os.write(("{\"action\":\"stop\",\"planId\":\"" + rp.getPlanId() + "\"}").getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, rpStop.getResponseCode(), "POST /replay_plan stop returns 200");
            assertTrue(!state.hasActiveReplayPlan(), "ReplayPlan stopped cleanly via /replay_plan");
            passed++;
        } finally {
            srv.stop();
        }
    }

    private static void testHtmlAgentInjectorAndControlPlaneWhenReplayActive() {
        MockMontoyaSupport.MockEnv env = new MockMontoyaSupport.MockEnv();
        MontoyaApi api = MockMontoyaSupport.mockMontoyaApi(env);

        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv(""); // Host NOT in allowedHosts, only in active ReplayPlan!
        CrawlState state = new CrawlState();
        state.setStatus(CrawlState.Status.STOPPED);

        ActionGroup grp = new ActionGroup(1, "/ > shop > button \"Buy\"", "button", "Buy", "https://replay-only.test/shop", System.currentTimeMillis());
        state.startReplayPlan(grp, "Buy", "POST /api/buy", "https://replay-only.test/shop", true);

        ControlPlaneHandler cph = new ControlPlaneHandler(cfg, state, 19999);
        cph.setAgentArmed(false); // Disarmed + STOPPED: only active ReplayPlan authorizes injection
        StaticJsScanner jsScanner = new StaticJsScanner(cfg, state, null);
        HtmlAgentInjector injector = new HtmlAgentInjector(api, cfg, state, cph, jsScanner, null, null, null);
        injector.setAgentArmed(false);
        try {
            HttpService svc = HttpService.httpService("replay-only.test", 443, true);
            Map<String, String> hdrs = new LinkedHashMap<>();
            hdrs.put("Host", "replay-only.test");
            hdrs.put("Accept", "text/html");
            hdrs.put("Accept-Encoding", "gzip, br, zstd");
            hdrs.put("Sec-Fetch-Dest", "document");

            InterceptedRequest docReq = MockMontoyaSupport.mockInterceptedRequest(301, svc, "GET", "/shop", hdrs, new byte[0]);
            ProxyRequestReceivedAction reqAct = cph.handleRequestReceived(docReq);
            assertEquals("identity", reqAct.request().headerValue("Accept-Encoding"),
                "ControlPlaneHandler forces Accept-Encoding: identity on replay host even when STOPPED & disarmed");

            InterceptedRequest ctrlReq = MockMontoyaSupport.mockInterceptedRequest(302, svc, "GET", "/__myth_crawl/state", hdrs, new byte[0]);
            ProxyRequestReceivedAction ctrlAct = cph.handleRequestReceived(ctrlReq);
            assertEquals(MessageReceivedAction.DO_NOT_INTERCEPT, ctrlAct.action(),
                "ControlPlaneHandler routes /__myth_crawl/* on replay host even when STOPPED & disarmed");

            String html = "<!doctype html><html><head><title>Shop</title></head><body><button>Buy</button></body></html>";
            InterceptedResponse resp = MockMontoyaSupport.mockInterceptedResponse(
                301, docReq, 200, Collections.singletonMap("Content-Type", "text/html; charset=utf-8"),
                html.getBytes(StandardCharsets.UTF_8), MimeType.HTML, MimeType.HTML, env);
            ProxyResponseReceivedAction respAct = injector.handleResponseReceived(resp);
            assertTrue(respAct.response().bodyToString().contains("data-myth-agent=\"1\""),
                "HtmlAgentInjector injects agent.js on replay host even when Status == STOPPED & disarmed");
            passed++;
        } finally {
            injector.shutdown();
            jsScanner.shutdown();
        }
    }

    private static void testAuditThemeContrastForeground() {
        Color darkInk = new Color(0x11, 0x18, 0x27);
        assertEquals(darkInk, AuditTheme.contrastForeground(new Color(0xF9, 0xA8, 0xD4)), "Bright Pink #F9A8D4 gets dark ink");
        assertEquals(darkInk, AuditTheme.contrastForeground(new Color(0xF2, 0x8C, 0x38)), "Orange #F28C38 gets dark ink");
        assertEquals(darkInk, AuditTheme.contrastForeground(new Color(0xFD, 0xE0, 0x47)), "Yellow #FDE047 gets dark ink");
        assertEquals(darkInk, AuditTheme.contrastForeground(new Color(0xF5, 0x42, 0x91)), "Magenta #F54291 (WCAG contrast 4.93 vs dark > 3.67 vs white) gets dark ink");
        assertEquals(Color.WHITE, AuditTheme.contrastForeground(new Color(0x9D, 0x17, 0x4D)), "Deep Ruby #9D174D gets white ink");
        assertEquals(Color.WHITE, AuditTheme.contrastForeground(new Color(0x2B, 0x2D, 0x30)), "Dark #2B2D30 gets white ink");
        assertEquals(Color.WHITE, AuditTheme.contrastForeground(new Color(0x2F, 0x65, 0xCA)), "Selection #2F65CA gets white ink");
        passed++;
    }

    private static void testWaveCAutoCrawlTelemetrySeedAndJsRoutes() throws Exception {
        // 1. CrawlConfig.seedUrl & maxPageDurationSec persistence + clamping
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("spa.example.com");
        cfg.setSeedUrl("https://spa.example.com/app");
        cfg.setMaxPageDurationSec(120);
        cfg.setDeepCrawlMode(true);
        String persisted = cfg.toPersistJson();
        CrawlConfig restored = new CrawlConfig();
        restored.loadFromPersistJson(persisted);
        assertEquals("https://spa.example.com/app", restored.getSeedUrl(), "seedUrl persisted in JSON");
        assertEquals(120, restored.getMaxPageDurationSec(), "maxPageDurationSec persisted in JSON");
        restored.setMaxPageDurationSec(5);
        assertEquals(15, restored.getMaxPageDurationSec(), "maxPageDurationSec clamped to min 15s");
        restored.setMaxPageDurationSec(9999);
        assertEquals(600, restored.getMaxPageDurationSec(), "maxPageDurationSec clamped to max 600s");

        // 2. LocalControlServer /__myth_crawl/telemetry + /__myth_crawl/state pendingInterceptCount
        CrawlState state = new CrawlState();
        com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue queue =
            new com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue();
        LocalControlServer srv = new LocalControlServer(cfg, state, null, null, true);
        srv.setInterceptQueue(queue);
        srv.start();
        try {
            int port = srv.getPort();
            queue.enqueue("Orders > Delete", "spa.example.com", "DELETE", "/api/orders/10",
                "DELETE /api/orders/10 HTTP/1.1\r\nHost: spa.example.com\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                "Guard hold", "SafeDelete", 10_000L);

            HttpURLConnection telConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/telemetry").openConnection();
            telConn.setRequestMethod("POST");
            telConn.setRequestProperty("X-Myth-Token", srv.getSessionToken());
            telConn.setRequestProperty("Content-Type", "application/json");
            telConn.setDoOutput(true);
            String telBody = "{\"phase\":\"CLICKING\",\"pageUrl\":\"https://spa.example.com/orders\",\"actionsDone\":4,\"totalTargets\":12}";
            try (OutputStream os = telConn.getOutputStream()) {
                os.write(telBody.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, telConn.getResponseCode(), "/__myth_crawl/telemetry returns 200");
            String telResp = new String(telConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(telResp.contains("\"phase\":\"CLICKING\""), "Telemetry response echoes phase");

            CrawlState.AgentTelemetry snap = state.getAgentTelemetrySnapshot();
            assertTrue(snap.connected, "AgentTelemetry marks connected after telemetry post");
            assertEquals("CLICKING", snap.phase, "AgentTelemetry phase updated");
            assertEquals("https://spa.example.com/orders", snap.currentUrl, "AgentTelemetry URL updated");
            assertEquals(4, snap.actionsDoneOnPage, "AgentTelemetry actionsDoneOnPage=4");
            assertEquals(12, snap.totalTargetsOnPage, "AgentTelemetry totalTargetsOnPage=12");

            HttpURLConnection stConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/state?url=https://spa.example.com/orders").openConnection();
            stConn.setRequestMethod("GET");
            stConn.setRequestProperty("X-Myth-Token", srv.getSessionToken());
            assertEquals(200, stConn.getResponseCode(), "/__myth_crawl/state returns 200");
            String stBody = new String(stConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(stBody.contains("\"maxPageDurationMs\":120000"), "/state includes maxPageDurationMs");
            assertTrue(stBody.contains("\"maxPaginationSamples\":5"), "/state includes maxPaginationSamples");
            assertTrue(stBody.contains("\"pendingInterceptCount\":1"), "/state includes live pendingInterceptCount=1");
        } finally {
            queue.dropAll();
            srv.stop();
        }

        // 3. StaticJsScanner / CrawlState.enqueueJsRouteIfEligible SPA route frontier feeding
        CrawlState jsState = new CrawlState();
        assertTrue(jsState.enqueueJsRouteIfEligible("/dashboard/settings", "https://spa.example.com/assets/app.js", cfg),
            "Clean SPA route enqueued when deepCrawlMode=true");
        assertTrue(!jsState.enqueueJsRouteIfEligible("/api/v1/users", "https://spa.example.com/assets/app.js", cfg),
            "API endpoint not enqueued as browser page route");
        assertTrue(!jsState.enqueueJsRouteIfEligible("/assets/logo.png", "https://spa.example.com/assets/app.js", cfg),
            "Static asset not enqueued as browser page route");

        StaticJsScanner scanner = new StaticJsScanner(cfg, jsState, null);
        try {
            String jsBundle = "const routes = ['/reports/monthly', '/api/v1/reports', '/static/main.css'];";
            scanner.submitJsScan("https://spa.example.com/assets/bundle.js", jsBundle);
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && jsState.getPendingQueueSize() < 2) {
                Thread.sleep(25L);
            }
            assertTrue(jsState.getPendingQueueSize() >= 2, "StaticJsScanner enqueued discovered SPA route into crawl frontier");
        } finally {
            scanner.shutdown();
        }

        passed++;
    }

    private static void testCoverageV11Upgrades(Path tmpDir) throws Exception {
        // 1. Priority Frontier: P0_HIGH (admin + missing-Excel hint) beats P1_NORMAL beats P2_LOW (pagination)
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("spa.example.com");
        cfg.setMaxPaginationSamples(4);
        cfg.setDeepCrawlMode(true);
        cfg.setStrictExcelOnly(false);

        CrawlState prioState = new CrawlState();
        prioState.updateMissingExcelRouteHints(Collections.singletonList("/invoices"));
        assertTrue(prioState.enqueueUrl("https://spa.example.com/catalog/items/list/view?page=2", cfg), "Enqueue P2 pagination");
        assertTrue(prioState.enqueueUrl("https://spa.example.com/catalog/items/list/detail", cfg), "Enqueue P1 depth-4 normal");
        assertTrue(prioState.enqueueUrl("https://spa.example.com/portal/dept/sub/invoices/pending", cfg), "Enqueue P0 missing-Excel hint");
        assertTrue(prioState.enqueueUrl("https://spa.example.com/admin/roles", cfg), "Enqueue P0 admin module");

        String p1 = prioState.pollNextUnvisitedUrl();
        String p2 = prioState.pollNextUnvisitedUrl();
        String p3 = prioState.pollNextUnvisitedUrl();
        String p4 = prioState.pollNextUnvisitedUrl();
        assertEquals("https://spa.example.com/portal/dept/sub/invoices/pending", p1, "Missing-Excel hinted route polled first (P0)");
        assertEquals("https://spa.example.com/admin/roles", p2, "Admin module route polled second (P0)");
        assertEquals("https://spa.example.com/catalog/items/list/detail", p3, "Normal route polled before pagination (P1)");
        assertEquals("https://spa.example.com/catalog/items/list/view?page=2", p4, "Pagination polled last (P2)");

        // 2. Incomplete-page revisit: re-queues incomplete page up to 3 times without double-counting uniqueVisitedPages
        prioState.markUrlVisited("https://spa.example.com/admin/roles");
        int visitedBefore = prioState.getVisitedPagesCount();
        assertTrue(prioState.requeueIncompletePage("https://spa.example.com/admin/roles", cfg), "Incomplete page re-queued #1");
        assertEquals("https://spa.example.com/admin/roles", prioState.pollNextUnvisitedUrl(), "Incomplete page returned at head of P0");
        prioState.markUrlVisited("https://spa.example.com/admin/roles");
        assertEquals(visitedBefore, prioState.getVisitedPagesCount(), "Revisiting incomplete page does not inflate unique visited count");
        prioState.markUrlCompleted("https://spa.example.com/admin/roles");
        assertTrue(!prioState.requeueIncompletePage("https://spa.example.com/admin/roles", cfg), "Completed page is never re-queued as incomplete");

        // 3. X-Myth-Ctx deterministic request correlation + zero-wire-leak header stripping
        CrawlState ctxState = new CrawlState();
        ctxState.setStatus(CrawlState.Status.RUNNING);
        CrawlState.ActionContextSnapshot snapApprove = ctxState.setCurrentContextSnapshot(
            "/ > orders > button \"Approve\"", "button", "Approve", "https://spa.example.com/orders", null);
        CrawlState.ActionContextSnapshot snapExport = ctxState.setCurrentContextSnapshot(
            "/ > orders > button \"Export\"", "button", "Export", "https://spa.example.com/orders", null);
        assertTrue(snapExport.contextSeq > snapApprove.contextSeq, "Monotonic contextSeq generated");

        ControlPlaneHandler cph = new ControlPlaneHandler(cfg, ctxState, 19999);
        HttpService svc = HttpService.httpService("spa.example.com", 443, true);
        Map<String, String> delayedHdrs = new LinkedHashMap<>();
        delayedHdrs.put("Host", "spa.example.com");
        delayedHdrs.put("X-Myth-Ctx", String.valueOf(snapApprove.contextSeq));
        InterceptedRequest delayedReq = MockMontoyaSupport.mockInterceptedRequest(
            701, svc, "POST", "/api/v1/orders/approve", delayedHdrs, "{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        ProxyRequestReceivedAction delayedAct = cph.handleRequestReceived(delayedReq);
        assertTrue(!delayedAct.request().hasHeader("X-Myth-Ctx"), "X-Myth-Ctx MUST be stripped before leaving Proxy");
        assertEquals("/ > orders > button \"Approve\"", ctxState.consumeMessageActionPath(701),
            "Delayed async request correlated to exact originating Approve context despite newer Export context");

        // 4. ES6 Template-Literal JS Scanner + Router Props + LiveIdPool Dynamic Concretization + OpenAPI
        com.mythofcrawl.crawler.core.guardrail.LiveIdPool idPool = new com.mythofcrawl.crawler.core.guardrail.LiveIdPool();
        CrawlState dynState = new CrawlState();
        StaticJsScanner dynScanner = new StaticJsScanner(cfg, dynState, null);
        dynScanner.setLiveIdPool(idPool);
        try {
            String modernJs = "const cancelUrl = `${API_ROOT}/api/v1/orders/${order.id}/cancel`;\n"
                + "const router = [{ path: 'orders/:orderId/audit' }, { path: '/billing/summary' }];";
            dynScanner.submitJsScan("https://spa.example.com/assets/modern.js", modernJs);
            long waitDeadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < waitDeadline && dynState.getDiscoveredJsEndpoints().size() < 3) {
                Thread.sleep(20L);
            }
            List<String> jsEps = dynState.getDiscoveredJsEndpoints();
            assertTrue(jsEps.stream().anyMatch(s -> s.contains("/api/v1/orders/{orderId}/cancel")),
                "ES6 template literal endpoint normalized and extracted: " + jsEps);
            assertEquals("https://spa.example.com/billing/summary", dynState.pollNextUnvisitedUrl(),
                "Static router path enqueued immediately");
            assertEquals(0, dynState.getPendingQueueSize(), "Parameterized /orders/:orderId/audit waits for LiveIdPool ID");

            // Simulate harvesting {"id": 7788} from GET /api/v1/orders -> triggers concretization!
            int harvested = idPool.harvestFromJson("/api/v1/orders", "{\"data\":[{\"id\":7788}]}");
            assertTrue(harvested >= 1, "LiveIdPool harvested order id 7788");
            dynScanner.tryConcretizePendingRoutes();
            assertEquals("https://spa.example.com/orders/7788/audit", dynState.pollNextUnvisitedUrl(),
                "Parameterized JS route concretized with real harvested ID 7788 and enqueued");

            // OpenAPI 3.0 discovery
            String openApiJson = "{\"openapi\":\"3.0.1\",\"paths\":{\"/api/v1/invoices/{id}\":{\"get\":{},\"delete\":{}}}}";
            int oaFound = com.mythofcrawl.crawler.proxy.SeedDiscoveryHelper.parseOpenApiSpecJson(
                openApiJson, "https://spa.example.com", "/openapi.json", cfg, dynState, null);
            assertEquals(2, oaFound, "Parsed 2 OpenAPI operations");
            assertTrue(dynState.getDiscoveredJsEndpoints().stream().anyMatch(s -> s.contains("[OpenAPI] DELETE /api/v1/invoices/{id}")),
                "OpenAPI DELETE endpoint recorded in Ghost discovery");
        } finally {
            dynScanner.shutdown();
        }

        // 5. GET GraphQL Op Extraction + HTTP 200 GraphQL Error & Disguised JSON 401 + Ghost Coverage Report
        MockMontoyaSupport.MockEnv env = new MockMontoyaSupport.MockEnv();
        MontoyaApi api = MockMontoyaSupport.mockMontoyaApi(env);
        CrawlState qualState = new CrawlState();
        qualState.setStatus(CrawlState.Status.RUNNING);
        qualState.setCurrentContext("/ > audit > button \"Check\"", "button", "Check", "https://spa.example.com/audit");
        qualState.addDiscoveredJsEndpoint("[OpenAPI] DELETE /api/v1/hidden-admin  (from /openapi.json)");
        qualState.addDiscoveredJsEndpoint("/api/v1/profile  (from app.js)");

        ControlPlaneHandler qCph = new ControlPlaneHandler(cfg, qualState, 19999);
        StaticJsScanner qScanner = new StaticJsScanner(cfg, qualState, null);
        HtmlAgentInjector qInjector = new HtmlAgentInjector(api, cfg, qualState, qCph, qScanner, null, null, null);
        qInjector.setLiveIdPool(idPool);
        try {
            // GET GraphQL with HTTP 200 errors[]
            HttpRequest gqlGetReq = MockMontoyaSupport.mockInterceptedRequest(
                801, svc, "GET", "/graphql?operationName=ListSecrets&query=query+ListSecrets+%7B+secrets+%7D",
                Collections.singletonMap("Host", "spa.example.com"), new byte[0]);
            byte[] gqlErrBody = "{\"data\":null,\"errors\":[{\"message\":\"Access denied to secrets\"}]}".getBytes(StandardCharsets.UTF_8);
            InterceptedResponse gqlGetResp = MockMontoyaSupport.mockInterceptedResponse(
                801, gqlGetReq, 200, Collections.singletonMap("Content-Type", "application/json"),
                gqlErrBody, MimeType.JSON, MimeType.JSON, env);
            qInjector.handleResponseReceived(gqlGetResp);

            // Disguised 401 inside HTTP 200 JSON envelope
            HttpRequest softAuthReq = MockMontoyaSupport.mockInterceptedRequest(
                802, svc, "POST", "/api/v1/profile",
                Collections.singletonMap("Host", "spa.example.com"), new byte[0]);
            byte[] softAuthBody = "{\"code\":401,\"message\":\"Token expired\"}".getBytes(StandardCharsets.UTF_8);
            InterceptedResponse softAuthResp = MockMontoyaSupport.mockInterceptedResponse(
                802, softAuthReq, 200, Collections.singletonMap("Content-Type", "application/json"),
                softAuthBody, MimeType.JSON, MimeType.JSON, env);
            qInjector.handleResponseReceived(softAuthResp);

            long qWait = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < qWait && qualState.getActionGroups().totalCalls() < 2) {
                Thread.sleep(20L);
            }
            // Allow post-record JSON note attachment inside captureWorker to finish
            Thread.sleep(60L);

            List<AuditNote> qNotes = qualState.getActionGroups().snapshotNotes();
            assertTrue(qNotes.stream().anyMatch(n -> n.type == AuditNote.Type.GRAPHQL_ERROR && n.endpoint.contains("ListSecrets")),
                "GET GraphQL op extracted and HTTP 200 GraphQL error flagged: " + qNotes);
            assertTrue(qNotes.stream().anyMatch(n -> n.type == AuditNote.Type.SOFT_AUTH_OR_ERROR && n.message.contains("401")),
                "Disguised JSON 401 inside HTTP 200 flagged: " + qNotes);

            File mdReport = tmpDir.resolve("v11_coverage.md").toFile();
            ReportExporter.exportMarkdown(mdReport, null, qualState.getExtraCaptures(), qualState.getActionGroups(), qualState);
            String mdText = Files.readString(mdReport.toPath(), StandardCharsets.UTF_8);
            assertTrue(mdText.contains("UNTRIGGERED_GHOST") && mdText.contains("/api/v1/hidden-admin"),
                "Markdown report highlights untriggered OpenAPI ghost endpoint");
            assertTrue(mdText.contains("`CALLED`") && mdText.contains("/api/v1/profile"),
                "Markdown report marks triggered endpoint as CALLED");
        } finally {
            qInjector.shutdown();
            qScanner.shutdown();
        }

        passed++;
    }

    private static void testSubPathScopeLockVnExpressScenario() throws Exception {
        String mdInput = "[Tin Thế giới, thời sự quốc tế, phân tích an ninh, quân sự - VnExpress](https://vnexpress.net/the-gioi)";
        assertEquals("https://vnexpress.net/the-gioi", CrawlConfig.unwrapMarkdownLinks(mdInput),
            "Markdown link unwraps cleanly to https://vnexpress.net/the-gioi");

        // Mode A: User pastes Markdown link / full sub-path URL directly into Host
        CrawlConfig cfgHostPath = new CrawlConfig();
        cfgHostPath.setAllowedHostsCsv(mdInput);
        assertTrue(cfgHostPath.isHostAllowed("vnexpress.net"), "Host-level check allows vnexpress.net for control & API routing");
        assertTrue(cfgHostPath.hasPathScopeRestriction(), "hasPathScopeRestriction true when Host includes /the-gioi");
        assertEquals("https://vnexpress.net/the-gioi", cfgHostPath.getPrimaryAllowedEntryUrl(),
            "Primary allowed entry URL derived from Host sub-path");
        assertTrue(cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi"), "/the-gioi allowed");
        assertTrue(cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi/"), "/the-gioi/ allowed");
        assertTrue(cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi/quan-su-4800123.html"), "Child article allowed");
        assertTrue(cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi?page=2"), "Query pagination allowed");
        assertTrue(cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi-p2"), "CMS -p2 pagination allowed");
        assertTrue(!cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/"), "Root https://vnexpress.net/ rejected");
        assertTrue(!cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net"), "Root https://vnexpress.net rejected");
        assertTrue(!cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-thao"), "Sibling /the-thao rejected");
        assertTrue(!cfgHostPath.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi-moi"), "Prefix collision /the-gioi-moi rejected");

        // Mode B: Host = vnexpress.net + Seed = https://vnexpress.net/the-gioi + strictSeedPathScope = true
        CrawlConfig cfgSeedLock = new CrawlConfig();
        cfgSeedLock.setAllowedHostsCsv("vnexpress.net");
        cfgSeedLock.setSeedUrl(mdInput);
        cfgSeedLock.setStrictSeedPathScope(true);
        cfgSeedLock.setAllowAutoRequests(true);
        assertTrue(cfgSeedLock.hasPathScopeRestriction(), "strictSeedPathScope enables path scope restriction");
        assertTrue(cfgSeedLock.isUrlAllowedForCrawl("https://vnexpress.net/the-gioi/phan-tich"), "Mode B allows /the-gioi/phan-tich");
        assertTrue(!cfgSeedLock.isUrlAllowedForCrawl("https://vnexpress.net/"), "Mode B blocks https://vnexpress.net/");

        // Persistence round-trip
        CrawlConfig reloaded = new CrawlConfig();
        reloaded.loadFromPersistJson(cfgSeedLock.toPersistJson());
        assertTrue(reloaded.isStrictSeedPathScope(), "strictSeedPathScope persisted in JSON");
        assertEquals("https://vnexpress.net/the-gioi", reloaded.getSeedUrl(), "Clean unwrapped seedUrl persisted");

        // Frontier filtering in CrawlState
        CrawlState state = new CrawlState();
        state.resetAll("https://vnexpress.net/the-gioi");
        state.setStatus(CrawlState.Status.RUNNING);
        assertTrue(!state.enqueueUrl("https://vnexpress.net/", cfgSeedLock), "Frontier rejects root https://vnexpress.net/");
        assertTrue(!state.enqueueUrl("https://vnexpress.net/the-thao", cfgSeedLock), "Frontier rejects /the-thao");
        assertTrue(state.enqueueUrl("https://vnexpress.net/the-gioi/tu-lieu", cfgSeedLock), "Frontier accepts /the-gioi/tu-lieu");

        // LocalControlServer /state scopeRules + /bounce_back fallback to https://vnexpress.net/the-gioi
        LocalControlServer srv = new LocalControlServer(cfgSeedLock, state, null, null, true);
        srv.start();
        MockMontoyaSupport.MockEnv env = new MockMontoyaSupport.MockEnv();
        MontoyaApi api = MockMontoyaSupport.mockMontoyaApi(env);
        ControlPlaneHandler cph = new ControlPlaneHandler(cfgSeedLock, state, srv.getPort());
        StaticJsScanner scanner = new StaticJsScanner(cfgSeedLock, state, null);
        HtmlAgentInjector injector = new HtmlAgentInjector(api, cfgSeedLock, state, cph, scanner, null, null, null);
        try {
            int port = srv.getPort();
            String tok = srv.getSessionToken();

            HttpURLConnection stConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__myth_crawl/state").openConnection();
            stConn.setRequestProperty("X-Myth-Token", tok);
            Map<String, Object> stJson = MiniJson.parseObject(new String(stConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE, stJson.get("hasPathScope"), "/state reports hasPathScope=true");
            assertEquals("https://vnexpress.net/the-gioi", stJson.get("seedUrl"), "/state exposes allowed seedUrl");
            assertTrue(stJson.get("scopeRules") instanceof List<?> && !((List<?>) stJson.get("scopeRules")).isEmpty(),
                "/state exposes scopeRules for crawler-agent.js");

            HttpService vneSvc = HttpService.httpService("vnexpress.net", 443, true);

            // 1. Top-level browser navigation to disallowed root https://vnexpress.net/ -> intercepted & bounced!
            Map<String, String> rootDocHdrs = new LinkedHashMap<>();
            rootDocHdrs.put("Host", "vnexpress.net");
            rootDocHdrs.put("Accept", "text/html,application/xhtml+xml");
            rootDocHdrs.put("Sec-Fetch-Dest", "document");
            rootDocHdrs.put("Sec-Fetch-Mode", "navigate");
            InterceptedRequest rootReq = MockMontoyaSupport.mockInterceptedRequest(901, vneSvc, "GET", "/", rootDocHdrs, new byte[0]);
            ProxyRequestReceivedAction rootAct = cph.handleRequestReceived(rootReq);
            assertEquals(MessageReceivedAction.DO_NOT_INTERCEPT, rootAct.action(),
                "Top-level GET / on vnexpress.net intercepted before hitting server");
            assertTrue(rootAct.request().path().startsWith("/__myth_crawl/bounce_back?"),
                "Disallowed root GET / redirected to local /__myth_crawl/bounce_back");

            // Verify LocalControlServer /__myth_crawl/bounce_back bounces browser to https://vnexpress.net/the-gioi
            HttpURLConnection bbConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + rootAct.request().path()).openConnection();
            String bbHtml = new String(bbConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(bbHtml.contains("https://vnexpress.net/the-gioi"),
                "bounce_back sends browser straight to https://vnexpress.net/the-gioi: " + bbHtml);

            // 2. Top-level browser navigation to allowed https://vnexpress.net/the-gioi -> allowed!
            InterceptedRequest okDocReq = MockMontoyaSupport.mockInterceptedRequest(902, vneSvc, "GET", "/the-gioi", rootDocHdrs, new byte[0]);
            ProxyRequestReceivedAction okDocAct = cph.handleRequestReceived(okDocReq);
            assertEquals(MessageReceivedAction.CONTINUE, okDocAct.action(),
                "Allowed GET /the-gioi continues normally through proxy");

            // 3. Backend XHR/fetch API call (/api/comment/list) triggered FROM /the-gioi -> allowed & captured!
            state.setCurrentContextSnapshot("/ > the-gioi > button \"Xem thêm\"", "button", "Xem thêm",
                "https://vnexpress.net/the-gioi", null);
            Map<String, String> apiHdrs = new LinkedHashMap<>();
            apiHdrs.put("Host", "vnexpress.net");
            apiHdrs.put("Accept", "application/json");
            apiHdrs.put("Sec-Fetch-Dest", "empty");
            apiHdrs.put("Sec-Fetch-Mode", "cors");
            apiHdrs.put("Referer", "https://vnexpress.net/the-gioi");
            InterceptedRequest apiReq = MockMontoyaSupport.mockInterceptedRequest(903, vneSvc, "GET", "/api/comment/list?id=1", apiHdrs, new byte[0]);
            ProxyRequestReceivedAction apiAct = cph.handleRequestReceived(apiReq);
            assertEquals(MessageReceivedAction.CONTINUE, apiAct.action(),
                "Sub-resource API GET /api/comment/list from /the-gioi is NOT blocked by document path gate");
            InterceptedResponse apiResp = MockMontoyaSupport.mockInterceptedResponse(
                903, apiReq, 200, Collections.singletonMap("Content-Type", "application/json"),
                "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8), MimeType.JSON, MimeType.JSON, env);
            injector.handleResponseReceived(apiResp);

            // 4. Unbound API call from disallowed background tab (Referer: https://vnexpress.net/) -> ignored!
            Map<String, String> outTabHdrs = new LinkedHashMap<>();
            outTabHdrs.put("Host", "vnexpress.net");
            outTabHdrs.put("Accept", "application/json");
            outTabHdrs.put("Sec-Fetch-Dest", "empty");
            outTabHdrs.put("Referer", "https://vnexpress.net/");
            HttpRequest outTabReq = MockMontoyaSupport.mockInterceptedRequest(999, vneSvc, "GET", "/api/home/box-top", outTabHdrs, new byte[0]);
            InterceptedResponse outTabResp = MockMontoyaSupport.mockInterceptedResponse(
                999, outTabReq, 200, Collections.singletonMap("Content-Type", "application/json"),
                "{\"home\":true}".getBytes(StandardCharsets.UTF_8), MimeType.JSON, MimeType.JSON, env);
            injector.handleResponseReceived(outTabResp);

            long waitUntil = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < waitUntil && state.getActionGroups().totalCalls() < 1) {
                Thread.sleep(20L);
            }
            Thread.sleep(50L);
            assertEquals(1, state.getActionGroups().totalCalls(),
                "Only in-scope API from /the-gioi captured; out-of-scope Referer / ignored");

            // 5. HTTP 302 redirect from /the-gioi/old-news -> Location: https://vnexpress.net/ intercepted & bounced!
            InterceptedRequest oldNewsReq = MockMontoyaSupport.mockInterceptedRequest(904, vneSvc, "GET", "/the-gioi/old-news", rootDocHdrs, new byte[0]);
            Map<String, String> redirHdrs = new LinkedHashMap<>();
            redirHdrs.put("Location", "https://vnexpress.net/");
            InterceptedResponse redirResp = MockMontoyaSupport.mockInterceptedResponse(
                904, oldNewsReq, 302, redirHdrs, new byte[0], MimeType.NONE, MimeType.NONE, env);
            ProxyResponseReceivedAction redirAct = injector.handleResponseReceived(redirResp);
            assertEquals(MessageReceivedAction.DO_NOT_INTERCEPT, redirAct.action(),
                "HTTP 302 redirecting to disallowed root https://vnexpress.net/ intercepted by HtmlAgentInjector");
            assertTrue(redirAct.response().bodyToString().contains("https://vnexpress.net/the-gioi"),
                "HTTP 302 bounce HTML returns browser to allowed /the-gioi scope | status=" + state.getStatus()
                + " | q=" + state.getPendingQueueSize()
                + " | defSeed=" + state.isUrlDeferred("https://vnexpress.net/the-gioi")
                + " | compSeed=" + state.isUrlCompleted("https://vnexpress.net/the-gioi")
                + " | body=" + redirAct.response().bodyToString());
        } finally {
            injector.shutdown();
            scanner.shutdown();
            srv.stop();
        }

        passed++;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " | expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " | expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
