package com.mythofcrawl.crawler;

import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.excel.ParamExtractor;
import com.mythofcrawl.crawler.excel.ReportExporter;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.FormFillProfile;
import com.mythofcrawl.crawler.model.MiniJson;
import com.mythofcrawl.crawler.model.PathNamer;
import com.mythofcrawl.crawler.proxy.LocalControlServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure Java self-contained verification suite covering:
 * - Zero-dependency .xlsx + .csv parsing & column guessing
 * - Param extraction (Query, Form, JSON nested keys)
 * - Excel checklist strict matching, 2->8 superset upgrade, disjoint param variant notes
 * - Case-preserving UI PathNamer ("/ > contact > button 'Send'")
 * - CrawlState frontier pagination cap, Danger Guard, messageId->actionPath correlation
 * - LocalControlServer live 127.0.0.1 loopback TCP HTTP round-trips
 * - ReportExporter CSV/JSON/MD output
 */
public final class CrawlerUnitTests {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("mythofcrawl_tests_");
        try {
            testXlsxAndCsvReading(tmpDir);
            testParamExtractor();
            testExcelMatcherRichnessAndDiffNotes(tmpDir);
            testPathNamerPreservesCase();
            testCrawlStateFrontierAndDangerGuard();
            testLocalControlServerLoopback();
            testFormFillProfileAndConfigPersist();
            testGraphqlXmlAndToppagePathNamer();
            testActionGroupStoreNotesAndExport(tmpDir);
            System.out.println("ALL TESTS PASSED (" + passed + " test groups verified).");
        } finally {
            deleteRecursive(tmpDir.toFile());
        }
    }

    private static void testXlsxAndCsvReading(Path tmpDir) throws Exception {
        File xlsxFile = tmpDir.resolve("api_spec.xlsx").toFile();
        createSampleXlsx(xlsxFile);

        SimpleXlsxReader.WorkbookData wb = SimpleXlsxReader.readFile(xlsxFile);
        assertEquals(2, wb.getSheetNames().size(), "Should read 2 sheets from .xlsx");
        List<List<String>> rows = wb.getRows("API Checklist");
        assertTrue(rows.size() >= 5, "Should parse rows including header");

        SimpleXlsxReader.ColumnGuess guess = SimpleXlsxReader.guessColumns(rows);
        assertEquals(1, guess.headerRowIndex, "Should detect header row index 1 (second row)");
        assertEquals(0, guess.sttCol, "Should detect STT col");
        assertEquals(1, guess.methodCol, "Should detect Method col");
        assertEquals(2, guess.pathCol, "Should detect Path API col");

        ExcelSpecMatcher matcher = ExcelSpecMatcher.buildFromRows(
            xlsxFile.getName(), rows, guess.headerRowIndex,
            guess.sttCol, guess.methodCol, guess.pathCol, true);
        assertEquals(3, matcher.getEntries().size(), "Should load 3 API specs");
        assertEquals("1", matcher.getEntries().get(0).stt, "Numeric STT 1.0 should clean to '1'");
        assertEquals("/api/v1/users/search", matcher.getEntries().get(0).normalizedSpecPath, "Path check");

        // Also test CSV
        File csvFile = tmpDir.resolve("api.csv").toFile();
        Files.writeString(csvFile.toPath(),
            "STT,Method,Path API,Note\n1,POST,/api/contact,Gửi liên hệ\n2,GET,/api/users/{id},Xem chi tiết\n",
            StandardCharsets.UTF_8);
        SimpleXlsxReader.WorkbookData csvWb = SimpleXlsxReader.readFile(csvFile);
        List<List<String>> csvRows = csvWb.getRows(csvWb.getSheetNames().get(0));
        assertEquals(3, csvRows.size(), "CSV row count");
        passed++;
    }

    private static void testParamExtractor() {
        String body = "{\"user\":{\"name\":\"Alice\",\"tags\":[\"a\"]},\"roles\":[{\"id\":1,\"code\":\"admin\"}]}";
        Set<String> jsonParams = ParamExtractor.extractParameterKeys(
            "/api/test?a=1&b=2", "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        assertTrue(jsonParams.contains("a") && jsonParams.contains("b"), "Query params");
        assertTrue(jsonParams.contains("json.user.name"), "Nested JSON key");
        assertTrue(jsonParams.contains("json.roles[].id") && jsonParams.contains("json.roles[].code"), "Array JSON key");

        Set<String> formParams = ParamExtractor.extractParameterKeys(
            "/api/contact", "application/x-www-form-urlencoded", "email=a%40b.com&message=hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(formParams.contains("body.email") && formParams.contains("body.message"), "Form params");
        passed++;
    }

    private static void testExcelMatcherRichnessAndDiffNotes(Path tmpDir) throws Exception {
        File xlsxFile = tmpDir.resolve("spec_rich.xlsx").toFile();
        createSampleXlsx(xlsxFile);
        SimpleXlsxReader.WorkbookData wb = SimpleXlsxReader.readFile(xlsxFile);
        List<List<String>> rows = wb.getRows("API Checklist");
        SimpleXlsxReader.ColumnGuess guess = SimpleXlsxReader.guessColumns(rows);
        ExcelSpecMatcher matcher = ExcelSpecMatcher.buildFromRows(
            "spec_rich.xlsx", rows, guess.headerRowIndex,
            guess.sttCol, guess.methodCol, guess.pathCol, true);

        CrawlConfig cfg = new CrawlConfig();
        cfg.setApiBasePrefix("");

        // 1) Non-Excel path must NOT match
        assertNull(recordHelper(matcher, "GET", "/api/v1/not-in-excel?a=1", "/ > home", 200, cfg),
            "Non-Excel API should return null");

        // 2) First capture with 2 params on Page A
        ExcelSpecMatcher.MatchResult r1 = recordHelper(
            matcher, "GET", "/api/v1/users/search?page=1&limit=10",
            "/ > users > button 'Search'", 200, cfg);
        assertTrue(r1 != null && r1.outcome == ApiSpecEntry.RecordOutcome.FIRST_MATCH, "Should record FIRST_MATCH");
        assertEquals(2, r1.entry.getPrimaryCapture().paramCount(), "Initial primary has 2 params");

        // 3) Subset / duplicate (1 param) must be IGNORED
        ExcelSpecMatcher.MatchResult rSub = recordHelper(
            matcher, "GET", "/api/v1/users/search?page=2",
            "/ > home > link 'Users'", 200, cfg);
        assertTrue(rSub != null && rSub.outcome == ApiSpecEntry.RecordOutcome.IGNORED_SAME_OR_SUBSET,
            "Subset should be ignored");
        assertEquals(2, rSub.entry.getPrimaryCapture().paramCount(), "Primary remains 2 params");

        // 4) Superset (8 params on Page B) MUST upgrade primary capture
        ExcelSpecMatcher.MatchResult rFull = recordHelper(
            matcher, "GET", "/api/v1/users/search?page=1&limit=20&q=a&status=1&role=admin&sort=id&from=2025&to=2026",
            "/ > admin > users > button 'Filter Full'", 200, cfg);
        assertTrue(rFull != null && rFull.outcome == ApiSpecEntry.RecordOutcome.UPGRADED_SUPERSET,
            "Superset 2->8 should upgrade primary");
        assertEquals(8, rFull.entry.getPrimaryCapture().paramCount(), "Primary now has 8 params");
        assertTrue(rFull.entry.getNotesSummary().contains("2 -> 8"), "Should contain upgrade note");

        // 5) Disjoint params across two pages -> preserve both & create explicit diff note
        ExcelSpecMatcher.MatchResult rVar1 = recordHelper(
            matcher, "GET", "/api/v1/orders/123?from=2026-01&to=2026-02",
            "/ > orders > button 'View Range'", 200, cfg);
        assertTrue(rVar1 != null && rVar1.outcome == ApiSpecEntry.RecordOutcome.FIRST_MATCH, "Template {id} match");

        ExcelSpecMatcher.MatchResult rVar2 = recordHelper(
            matcher, "GET", "/api/v1/orders/456?status=paid&owner=me&currency=VND",
            "/ > billing > button 'Invoice Detail'", 200, cfg);
        assertTrue(rVar2 != null && rVar2.outcome == ApiSpecEntry.RecordOutcome.ADDED_DIFF_VARIANT,
            "Disjoint params must add diff variant");
        assertTrue(rVar2.entry.hasParamDiffNote(), "Must flag param diff note");
        assertEquals(2, rVar2.entry.getAllStoredCaptures().size(), "Must retain both variants");

        // 6) Verify export CSV / JSON / MD works cleanly
        File csvOut = tmpDir.resolve("out.csv").toFile();
        File jsonOut = tmpDir.resolve("out.json").toFile();
        File mdOut = tmpDir.resolve("out.md").toFile();
        ReportExporter.exportCsv(csvOut, matcher, List.of());
        ReportExporter.exportJson(jsonOut, matcher, List.of());
        ReportExporter.exportMarkdown(mdOut, matcher, List.of());
        assertTrue(csvOut.length() > 50 && jsonOut.length() > 50 && mdOut.length() > 50, "Exports non-empty");
        String mdContent = Files.readString(mdOut.toPath(), StandardCharsets.UTF_8);
        assertTrue(mdContent.contains("/ > admin > users > button 'Filter Full'"), "MD keeps action path");
        passed++;
    }

    private static void testPathNamerPreservesCase() {
        String p1 = PathNamer.buildActionPath("https://target.local/contact", "button", "Send", true);
        assertEquals("/ > contact > button 'Send'", p1, "Must keep uppercase 'Send'");

        String p2 = PathNamer.buildActionPath("https://target.local/contact", "button", "send", true);
        assertEquals("/ > contact > button 'send'", p2, "Must keep lowercase 'send'");

        String pSpa = PathNamer.buildActionPath("https://target.local/#/admin/Users/98765", "link", "Edit User", true);
        assertEquals("/ > admin > Users > {id} > link 'Edit User'", pSpa, "SPA hash + case preserved + numeric id collapsed");

        String pRoot = PathNamer.buildActionPath("https://target.local/", "page load", "", true);
        assertEquals("/ > page load", pRoot, "Root path format");
        passed++;
    }

    private static void testCrawlStateFrontierAndDangerGuard() {
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("app.example.com");
        cfg.setMaxPaginationSamples(3);

        CrawlState state = new CrawlState();
        state.resetAll("https://app.example.com/");

        assertTrue(!state.enqueueUrl("https://evil.com/phish", cfg), "Out-of-scope host blocked");
        assertTrue(!state.enqueueUrl("https://app.example.com/logout", cfg), "Danger Guard blocks /logout URL");
        assertTrue(state.enqueueUrl("https://app.example.com/users?page=1", cfg), "Page 1 accepted");
        assertTrue(state.enqueueUrl("https://app.example.com/users?page=2", cfg), "Page 2 accepted");
        assertTrue(state.enqueueUrl("https://app.example.com/users?page=3", cfg), "Page 3 accepted");
        assertTrue(!state.enqueueUrl("https://app.example.com/users?page=4", cfg), "Pagination >3 capped");

        state.bindMessageActionPath(42, "/ > contact > button 'Send'");
        assertEquals("/ > contact > button 'Send'", state.consumeMessageActionPath(42), "MessageId action correlation");

        CrawlConfig wildCfg = new CrawlConfig();
        wildCfg.setAllowedHostsCsv("*");
        assertTrue(wildCfg.isHostAllowed("anything.example.org"), "Wildcard '*' matches any host");
        passed++;
    }

    private static void testLocalControlServerLoopback() throws Exception {
        CrawlConfig cfg = new CrawlConfig();
        cfg.setAllowedHostsCsv("127.0.0.1");
        CrawlState state = new CrawlState();
        state.resetAll("http://127.0.0.1/home");
        state.setStatus(CrawlState.Status.RUNNING);

        LocalControlServer server = new LocalControlServer(cfg, state, null, null);
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "Ephemeral port allocated");

            String agentJs = httpRequest("GET", "http://127.0.0.1:" + port + "/__myth_crawl/agent.js", null);
            assertTrue(agentJs.contains("__myth_crawl") && agentJs.contains("addEventListener"), "Agent JS served");

            String ctxResp = httpRequest("POST", "http://127.0.0.1:" + port + "/__myth_crawl/context",
                "{\"actionPath\":\"/ > contact > button 'Send'\",\"pageUrl\":\"http://127.0.0.1/contact\"}");
            assertTrue(ctxResp.contains("ok"), "Context accepted");
            assertEquals("/ > contact > button 'Send'", state.getCurrentActionPath(), "Context updated on server");

            String stJson = httpRequest("GET", "http://127.0.0.1:" + port + "/__myth_crawl/state", null);
            Map<String, Object> parsed = MiniJson.parseObject(stJson);
            assertEquals("RUNNING", String.valueOf(parsed.get("status")), "State JSON reflects RUNNING");

            String discResp = httpRequest("POST", "http://127.0.0.1:" + port + "/__myth_crawl/discovered",
                "{\"pageUrl\":\"http://127.0.0.1/home\",\"urls\":[\"http://127.0.0.1/about\",\"http://127.0.0.1/logout\"],\"skipped\":[{\"actionPath\":\"/ > home > link 'Logout'\",\"reason\":\"logout\",\"tag\":\"A\"}]}");
            Map<String, Object> discParsed = MiniJson.parseObject(discResp);
            assertEquals("1", String.valueOf(discParsed.get("enqueued")), "Enqueued /about and skipped /logout");
            assertEquals(1, state.getSkippedDangerActions().size(), "Skipped logout action logged");

            String doneResp = httpRequest("POST", "http://127.0.0.1:" + port + "/__myth_crawl/done_action",
                "{\"pageUrl\":\"http://127.0.0.1/home\",\"signature\":\"button|Send|btn-send\"}");
            assertTrue(doneResp.contains("\"ok\":true"), "done_action returns ok");

            String nextResp = httpRequest("GET", "http://127.0.0.1:" + port + "/__myth_crawl/next_page?url=http://127.0.0.1/home", null);
            Map<String, Object> nextParsed = MiniJson.parseObject(nextResp);
            assertEquals("http://127.0.0.1/about", String.valueOf(nextParsed.get("nextUrl")), "Next queued page is /about after /home visited");
            passed++;
        } finally {
            server.stop();
        }
    }

    private static void testFormFillProfileAndConfigPersist() {
        CrawlConfig cfg = new CrawlConfig();
        FormFillProfile ff = cfg.getFormFillProfile();
        assertEquals("TEXT", ff.get(FormFillProfile.Field.TEXT), "Default free text must be TEXT");
        assertEquals("TEXT", ff.get(FormFillProfile.Field.TEXTAREA), "Default textarea must be TEXT");
        assertEquals("TEXT", ff.get(FormFillProfile.Field.RICHTEXT), "Default richtext/editable table must be TEXT");
        assertEquals("2026-01-01", ff.get(FormFillProfile.Field.DATE), "Default date");
        assertEquals("toppage", cfg.getRouteRootLabel(), "Default route root label");
        assertTrue(cfg.isDeepCrawlMode(), "Deep crawl mode defaults to true");
        assertTrue(cfg.getDangerKeywordsList().contains("logout"), "Logout must be in default danger guard");
        assertTrue(cfg.getDangerKeywordsList().contains("sign out"), "Sign out must be in default danger guard");
        assertTrue(!cfg.getDangerKeywordsList().contains("delete"), "Non-logout business actions must NOT be skipped by default");

        CrawlConfig hostCfg = new CrawlConfig();
        hostCfg.setAllowedHostsCsv("app.example.com");
        CrawlState s = new CrawlState();
        s.resetAll("https://app.example.com/");
        assertTrue(s.enqueueUrl("https://app.example.com/users/delete", hostCfg), "Business delete URL must NOT be blocked by default");

        ff.applyToAllTextFields("AUDIT_STR");
        assertEquals("AUDIT_STR", ff.get(FormFillProfile.Field.TEXT), "applyToAllTextFields updates TEXT");
        assertEquals("AUDIT_STR", ff.get(FormFillProfile.Field.TEXTAREA), "applyToAllTextFields updates TEXTAREA");
        assertEquals("2026-01-01", ff.get(FormFillProfile.Field.DATE), "applyToAllTextFields preserves DATE");
        ff.set(FormFillProfile.Field.DATE, "2027-05-09");
        ff.setRulesText("# comment line\nusername = admin_test\n/^(dob|birth)/ = 1999-12-31\n");

        List<Map<String, Object>> parsedRules = FormFillProfile.parseRules(ff.getRulesText());
        assertEquals(2, parsedRules.size(), "Parsed 2 non-comment rules");
        assertEquals("username", parsedRules.get(0).get("pattern"), "First rule pattern");
        assertEquals(Boolean.FALSE, parsedRules.get(0).get("regex"), "First rule is substring");
        assertEquals(Boolean.TRUE, parsedRules.get(1).get("regex"), "Second rule is regex");
        assertEquals("1999-12-31", parsedRules.get(1).get("value"), "Second rule value");

        cfg.setRouteRootLabel("/");
        cfg.setDeepCrawlMode(false);
        String json = cfg.toPersistJson();

        CrawlConfig restored = new CrawlConfig();
        restored.loadFromPersistJson(json);
        assertEquals("/", restored.getRouteRootLabel(), "Persist round-trip routeRootLabel");
        assertTrue(!restored.isDeepCrawlMode(), "Persist round-trip deepCrawlMode");
        assertEquals("AUDIT_STR", restored.getFormFillProfile().get(FormFillProfile.Field.TEXT), "Persist round-trip TEXT");
        assertEquals("2027-05-09", restored.getFormFillProfile().get(FormFillProfile.Field.DATE), "Persist round-trip DATE");
        assertTrue(restored.getFormFillProfile().getRulesText().contains("username = admin_test"), "Persist round-trip rules");
        passed++;
    }

    private static void testGraphqlXmlAndToppagePathNamer() {
        String gqlOp = ParamExtractor.extractGraphqlOperation(
            "application/json; charset=utf-8",
            "{\"operationName\":\"GetUserOrders\",\"query\":\"query GetUserOrders { orders { id } }\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("GetUserOrders", gqlOp, "Extract GraphQL operationName");

        Set<String> xmlKeys = ParamExtractor.extractParameterKeys(
            "/soap/service",
            "text/xml; charset=utf-8",
            "<Envelope><Body><CreateOrder><customerId>42</customerId></CreateOrder></Body></Envelope>".getBytes(StandardCharsets.UTF_8));
        assertTrue(xmlKeys.contains("xml.Envelope") || xmlKeys.contains("xml.CreateOrder") || xmlKeys.contains("xml.customerId"),
            "Extract XML/SOAP tag keys");

        String contactBtn = PathNamer.buildActionPath("https://target.local/contact", "button", "Send", true, "toppage");
        assertEquals("toppage > contact > button 'Send'", contactBtn, "toppage root hierarchy naming");

        String rootLoad = PathNamer.formatPageLoadPath("https://target.local/", true, "toppage");
        assertEquals("toppage > page load", rootLoad, "toppage root page load naming");
        passed++;
    }

    private static void testActionGroupStoreNotesAndExport(Path tmpDir) throws Exception {
        ActionGroupStore store = new ActionGroupStore();
        ActionGroup g1 = store.openGroup("toppage > contact > button 'Send'", "button", "Send", "https://target.local/contact");

        byte[] dummyReq = "POST /api/contact HTTP/1.1\r\nHost: target.local\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] dummyResp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.UTF_8);

        // Record 5 distinct APIs triggered inside one button click group
        String[][] apis = {
            {"POST", "/api/contact", "POST /api/contact", "", "1", "email,message", "200"},
            {"GET", "/api/csrf", "GET /api/csrf", "", "", "", "200"},
            {"POST", "/api/graphql", "POST /api/graphql#SubmitLead", "SubmitLead", "", "input.name,input.text", "200"},
            {"GET", "/api/config", "GET /api/config", "", "", "mode", "500"},
            {"POST", "/api/audit", "POST /api/audit", "", "", "event", "200"}
        };
        for (String[] a : apis) {
            Set<String> pKeys = a[5].isEmpty() ? Set.of() : Set.of(a[5].split(","));
            CapturedApiEntry cap = new CapturedApiEntry(
                a[4], a[0], "https://target.local" + a[1], a[1], "target.local", 443, true,
                g1.actionPath, dummyReq, dummyResp, Integer.parseInt(a[6]), dummyResp.length, pKeys, System.currentTimeMillis());
            ApiCall call = store.recordCall(g1, cap, a[2], a[3], a[4], a[1], true, false);
            assertEquals("toppage > contact > button 'Send'", call.displayName(), "API display name matches group name");
        }
        assertEquals(5, g1.callCount(), "All 5 APIs grouped under the Send button group");

        // Repeat identical call -> must deduplicate and increment hits to 2
        CapturedApiEntry repeatCap = new CapturedApiEntry(
            "1", "POST", "https://target.local/api/contact", "/api/contact", "target.local", 443, true,
            g1.actionPath, dummyReq, dummyResp, 200, dummyResp.length, Set.of("email", "message"), System.currentTimeMillis());
        ApiCall deduped = store.recordCall(g1, repeatCap, "POST /api/contact", "", "1", "/api/contact", true, false);
        assertEquals(2, deduped.getHits(), "Identical call in same group increments hit counter");
        assertEquals(5, g1.callCount(), "Group still has 5 unique API entries");

        // Second group hits POST /api/contact with DIFFERENT params and HTTP 403 -> PARAM_DIFF, STATUS_DIFF, AUTH notes
        ActionGroup g2 = store.openGroup("toppage > checkout > button 'Confirm'", "button", "Confirm", "https://target.local/checkout");
        CapturedApiEntry diffCap = new CapturedApiEntry(
            "1", "POST", "https://target.local/api/contact", "/api/contact", "target.local", 443, true,
            g2.actionPath, dummyReq, dummyResp, 403, dummyResp.length, Set.of("email", "orderId"), System.currentTimeMillis());
        ApiCall diffCall = store.recordCall(g2, diffCap, "POST /api/contact", "", "1", "/api/contact", true, false);
        String noteStr = diffCall.notesSummary();
        assertTrue(noteStr.contains("Khác tham số"), "PARAM_DIFF note added");
        assertTrue(noteStr.contains("HTTP 403"), "STATUS_DIFF and/or AUTH note added");
        assertEquals(2, store.sharedGroupCount("POST /api/contact"), "Endpoint shared across 2 groups");
        assertTrue(store.noteCount(AuditNote.Severity.ERROR) >= 2, "Errors recorded for 500 and 403");

        File csvOut = tmpDir.resolve("grouped.csv").toFile();
        File jsonOut = tmpDir.resolve("grouped.json").toFile();
        File mdOut = tmpDir.resolve("grouped.md").toFile();
        ReportExporter.exportCsv(csvOut, ExcelSpecMatcher.empty(), List.of(), store);
        ReportExporter.exportJson(jsonOut, ExcelSpecMatcher.empty(), List.of(), store);
        ReportExporter.exportMarkdown(mdOut, ExcelSpecMatcher.empty(), List.of(), store);

        String md = Files.readString(mdOut.toPath(), StandardCharsets.UTF_8);
        assertTrue(md.contains("toppage > contact > button 'Send'"), "Markdown includes group name");
        assertTrue(md.contains("SubmitLead"), "Markdown includes GraphQL op");
        assertTrue(md.contains("Tổng hợp Bảng NOTE Audit"), "Markdown includes Audit NOTE table");
        assertTrue(md.contains("Khác tham số"), "Markdown includes PARAM_DIFF note details");
        assertTrue(md.contains("HTTP 500"), "Markdown includes server error note");
        passed++;
    }

    private static String httpRequest(String method, String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        if (jsonBody != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        assertTrue(code >= 200 && code < 300, method + " " + urlStr + " returned HTTP " + code);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ExcelSpecMatcher.MatchResult recordHelper(
        ExcelSpecMatcher matcher,
        String method,
        String pathWithQuery,
        String actionPath,
        int status,
        CrawlConfig cfg
    ) {
        byte[] req = (method + " " + pathWithQuery + " HTTP/1.1\r\nHost: target.local\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        byte[] resp = ("HTTP/1.1 " + status + " OK\r\nContent-Length: 2\r\n\r\n{}")
            .getBytes(StandardCharsets.UTF_8);
        return matcher.matchAndRecord(
            method,
            "https://target.local" + pathWithQuery,
            pathWithQuery,
            "target.local",
            443,
            true,
            actionPath,
            "",
            req,
            new byte[0],
            resp,
            status,
            resp.length,
            cfg
        );
    }

    private static void createSampleXlsx(File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                + "</Types>");

            writeZipEntry(zos, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");

            writeZipEntry(zos, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets>"
                + "<sheet name=\"API Checklist\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"Summary\" sheetId=\"2\" r:id=\"rId2\"/>"
                + "</sheets></workbook>");

            writeZipEntry(zos, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                + "</Relationships>");

            String[] strings = {
                "Báo cáo danh sách API chuẩn", // 0
                "STT",                         // 1
                "Method",                      // 2
                "Path API",                    // 3
                "Mô tả",                       // 4
                "GET",                         // 5
                "/api/v1/users/search",        // 6
                "Tìm kiếm người dùng",         // 7
                "GET",                         // 8
                "/api/v1/orders/{id}",         // 9
                "Chi tiết đơn",                // 10
                "POST",                        // 11
                "/api/v1/contact"              // 12
            };
            StringBuilder ss = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            for (String s : strings) {
                ss.append("<si><t>").append(escapeXml(s)).append("</t></si>");
            }
            ss.append("</sst>");
            writeZipEntry(zos, "xl/sharedStrings.xml", ss.toString());

            writeZipEntry(zos, "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                + "<row r=\"2\">"
                + "  <c r=\"A2\" t=\"s\"><v>1</v></c>"
                + "  <c r=\"B2\" t=\"s\"><v>2</v></c>"
                + "  <c r=\"C2\" t=\"s\"><v>3</v></c>"
                + "  <c r=\"D2\" t=\"s\"><v>4</v></c>"
                + "</row>"
                + "<row r=\"3\">"
                + "  <c r=\"A3\"><v>1.0</v></c>"
                + "  <c r=\"B3\" t=\"s\"><v>5</v></c>"
                + "  <c r=\"C3\" t=\"s\"><v>6</v></c>"
                + "  <c r=\"D3\" t=\"s\"><v>7</v></c>"
                + "</row>"
                + "<row r=\"4\">"
                + "  <c r=\"A4\"><v>2</v></c>"
                + "  <c r=\"B4\" t=\"s\"><v>8</v></c>"
                + "  <c r=\"C4\" t=\"s\"><v>9</v></c>"
                + "  <c r=\"D4\" t=\"s\"><v>10</v></c>"
                + "</row>"
                + "<row r=\"5\">"
                + "  <c r=\"A5\"><v>3</v></c>"
                + "  <c r=\"B5\" t=\"s\"><v>11</v></c>"
                + "  <c r=\"C5\" t=\"s\"><v>12</v></c>"
                + "</row>"
                + "</sheetData></worksheet>");

            writeZipEntry(zos, "xl/worksheets/sheet2.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Info</t></is></c></row>"
                + "</sheetData></worksheet>");
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String xml) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertNull(Object obj, String msg) {
        if (obj != null) {
            throw new AssertionError("Expected null (" + msg + ") but got: " + obj);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError(msg + " -> expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}
