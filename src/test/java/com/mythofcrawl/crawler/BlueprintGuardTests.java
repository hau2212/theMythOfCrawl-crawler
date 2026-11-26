package com.mythofcrawl.crawler;

import com.mythofcrawl.crawler.bridge.HeadlessUiHost;
import com.mythofcrawl.crawler.core.auth.RoleProfile;
import com.mythofcrawl.crawler.core.auth.RoleStore;
import com.mythofcrawl.crawler.core.guardrail.ActionGateAuditLog;
import com.mythofcrawl.crawler.core.guardrail.GuardrailMode;
import com.mythofcrawl.crawler.core.guardrail.HttpEncodingGuard;
import com.mythofcrawl.crawler.core.guardrail.LiveIdPool;
import com.mythofcrawl.crawler.core.guardrail.PolicyEvaluator;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.core.guardrail.PolicyScope;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.ChangesBatch;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.core.tree.VisualClusterStore;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.MiniJson;
import com.mythofcrawl.crawler.ui.CrawlerTabPanel;
import com.mythofcrawl.crawler.ui.kit.ThemedTree;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.workspace.TreeWorkspacePanel;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Automated verification suite for the V_1.0_beta Blueprint Redesign:
 * - Enforces all 7 Hard Engineering Invariants (Laws 1..7 + WCAG AA >= 4.5:1) across ALL ui/** & core/**
 * - Tests Stage-1/Stage-2 Action Guardrail logic, ProxyInterceptQueue fail-safes,
 *   PolicyLedger CRUD/Trash/Undo/4-level NONE release/JSON persistence, LiveIdPool,
 *   RoleProfile JWT decoding, FontManager/version resources, custom {placeholder} specs,
 *   0ms Real-Time Push Auto-Expand, and Headless Light/Dark PNG visual snapshots.
 */
public final class BlueprintGuardTests {

    private static final Pattern NEW_COLOR_RX = Pattern.compile("\\bnew\\s+Color\\s*\\(");
    private static final Pattern FLATLAF_RX = Pattern.compile("com\\.formdev\\.flatlaf");
    private static final Pattern HTML_TAG_RX = Pattern.compile("(?i)<\\s*/?\\s*html\\b");
    private static final Pattern MONTOYA_IMPORT_RX = Pattern.compile("^\\s*import\\s+burp\\.api\\.montoya\\b", Pattern.MULTILINE);
    private static final Pattern SWING_IMPORT_RX = Pattern.compile("^\\s*import\\s+javax\\.swing\\b", Pattern.MULTILINE);
    private static final Pattern AWT_IMPORT_RX = Pattern.compile("^\\s*import\\s+java\\.awt\\b", Pattern.MULTILINE);

    private BlueprintGuardTests() {
    }

    public static void main(String[] args) throws Exception {
        Path mainJava = resolveMainJavaRoot();
        testLaw1ColorSovereigntyAndWcagContrast(mainJava);
        testLaw2NoFlatLafAnywhere(mainJava);
        testLaw3NoHtmlInSwingUi(mainJava);
        testLaw4NoDecorativeUnicodeGlyphs(mainJava);
        testLaw5TopBarSingleRowUnder1100px();
        testLaw6HttpEncodingGuard();
        testLaw7CorePureJavaZeroMontoyaOrSwing(mainJava);
        testPolicyEvaluatorAllowPrecedenceAndScopes();
        testPolicyLedgerCrudTrashUndo4LevelReleaseAndJson();
        testProxyInterceptQueueFailSafeDropForwardDraftAnd0msBus();
        testLiveIdPoolAndRoleProfileJwtTtl();
        testExcelSpecMatcherCustomPlaceholders();
        testFontManagerInstantEventBusAndVersionProps();
        testRealTimePushZeroDelayAutoExpand();
        testVisualVerificationPngSnapshots(mainJava);
        System.out.println("ALL BLUEPRINT GUARD TESTS PASSED (15 test groups verified).");
    }

    private static Path resolveMainJavaRoot() {
        Path p1 = Paths.get("src", "main", "java");
        if (Files.isDirectory(p1)) {
            return p1.toAbsolutePath().normalize();
        }
        Path p2 = Paths.get("theMythOfCrawl-crawler", "src", "main", "java");
        if (Files.isDirectory(p2)) {
            return p2.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Cannot locate src/main/java from working directory " + Paths.get("").toAbsolutePath());
    }

    private static List<Path> listJavaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    // -------------------------------------------------------------------------
    // Law 1: Color Sovereignty + WCAG AA (>= 4.5:1) Contrast Matrix
    // -------------------------------------------------------------------------
    private static void testLaw1ColorSovereigntyAndWcagContrast(Path mainJava) throws IOException {
        Path uiDir = mainJava.resolve("com/mythofcrawl/crawler/ui");
        Path bridgeDir = mainJava.resolve("com/mythofcrawl/crawler/bridge");
        List<Path> scanFiles = new ArrayList<>();
        scanFiles.addAll(listJavaFiles(uiDir));
        scanFiles.addAll(listJavaFiles(bridgeDir));

        List<String> violations = new ArrayList<>();
        for (Path f : scanFiles) {
            String normalized = f.toString().replace('\\', '/');
            if (normalized.endsWith("/ui/theme/Theme.java")) {
                continue;
            }
            String content = Files.readString(f, StandardCharsets.UTF_8);
            if (NEW_COLOR_RX.matcher(content).find()) {
                violations.add(mainJava.relativize(f).toString());
            }
        }
        assertTrue("Law 1: zero 'new Color(' outside Theme.java across all ui/**: " + violations, violations.isEmpty());

        boolean[] modes = { false, true };
        for (boolean dark : modes) {
            String modeLabel = dark ? "Dark" : "Light";
            assertContrastAtLeast("Theme.text vs canvas (" + modeLabel + ")",
                Theme.text(dark), Theme.canvas(dark), 4.5);
            assertContrastAtLeast("Theme.text vs surface (" + modeLabel + ")",
                Theme.text(dark), Theme.surface(dark), 4.5);
            assertContrastAtLeast("Theme.text vs surfaceAlt (" + modeLabel + ")",
                Theme.text(dark), Theme.surfaceAlt(dark), 4.5);
            assertContrastAtLeast("Theme.textSecondary vs canvas (" + modeLabel + ")",
                Theme.textSecondary(dark), Theme.canvas(dark), 4.5);
            assertContrastAtLeast("Theme.textSecondary vs surface (" + modeLabel + ")",
                Theme.textSecondary(dark), Theme.surface(dark), 4.5);

            for (Theme.Token tok : Theme.Token.values()) {
                Theme.PaletteEntry p = Theme.token(tok, dark);
                assertNotNull("PaletteEntry exists for " + tok + " (" + modeLabel + ")", p);
                assertContrastAtLeast(tok + ".text vs canvas (" + modeLabel + ")",
                    p.text(), Theme.canvas(dark), 4.5);
                assertContrastAtLeast(tok + ".text vs surface (" + modeLabel + ")",
                    p.text(), Theme.surface(dark), 4.5);
                assertContrastAtLeast(tok + ".text vs surfaceAlt (" + modeLabel + ")",
                    p.text(), Theme.surfaceAlt(dark), 4.5);
                assertContrastAtLeast(tok + ".text vs fill (" + modeLabel + ")",
                    p.text(), p.fill(), 4.5);
                assertContrastAtLeast(tok + ".text vs fillStrong (" + modeLabel + ")",
                    p.text(), p.fillStrong(), 4.5);
                assertContrastAtLeast(tok + ".onSolid vs solidFill (" + modeLabel + ")",
                    p.onSolid(), p.solidFill(), 4.5);
            }
        }
    }

    private static void assertContrastAtLeast(String label, Color fg, Color bg, double minRatio) {
        double ratio = Theme.contrastRatio(fg, bg);
        if (ratio < minRatio) {
            throw new AssertionError(String.format(
                "WCAG AA contrast failed for %s: %.2f < %.2f", label, ratio, minRatio));
        }
    }

    // -------------------------------------------------------------------------
    // Law 2: Zero FlatLaf Anywhere in src/main/java
    // -------------------------------------------------------------------------
    private static void testLaw2NoFlatLafAnywhere(Path mainJava) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : listJavaFiles(mainJava)) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            if (FLATLAF_RX.matcher(content).find()) {
                hits.add(mainJava.relativize(f).toString());
            }
        }
        assertTrue("Law 2: zero com.formdev.flatlaf imports/refs: " + hits, hits.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Law 3: Zero <html> Strings in UI Swing Components
    // -------------------------------------------------------------------------
    private static void testLaw3NoHtmlInSwingUi(Path mainJava) throws IOException {
        Path uiDir = mainJava.resolve("com/mythofcrawl/crawler/ui");
        List<Path> scanFiles = listJavaFiles(uiDir);
        List<String> hits = new ArrayList<>();
        for (Path f : scanFiles) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            if (HTML_TAG_RX.matcher(content).find()) {
                hits.add(mainJava.relativize(f).toString());
            }
        }
        assertTrue("Law 3: zero <html> in Swing UI across all ui/**: " + hits, hits.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Law 4: Zero Decorative Unicode Glyphs (>= 0x2600 or 0x2190..0x25FF)
    // -------------------------------------------------------------------------
    private static void testLaw4NoDecorativeUnicodeGlyphs(Path mainJava) throws IOException {
        Path uiDir = mainJava.resolve("com/mythofcrawl/crawler/ui");
        List<Path> scanFiles = listJavaFiles(uiDir);
        List<String> hits = new ArrayList<>();
        for (Path f : scanFiles) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            int[] bad = content.codePoints()
                .filter(cp -> cp >= 0x2600 || (cp >= 0x2190 && cp <= 0x25FF))
                .limit(3)
                .toArray();
            if (bad.length > 0) {
                hits.add(mainJava.relativize(f) + " (U+" + Integer.toHexString(bad[0]).toUpperCase() + ")");
            }
        }
        assertTrue("Law 4: zero decorative Unicode glyphs in UI across all ui/**: " + hits, hits.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Law 5: Single-Row Top Bar Preferred Width <= 1100px in Light & Dark Mode
    // -------------------------------------------------------------------------
    private static void testLaw5TopBarSingleRowUnder1100px() {
        boolean prevDark = Theme.isDark();
        CrawlConfig config = new CrawlConfig();
        CrawlState state = new CrawlState();
        ChangesBatch batch = new ChangesBatch(500L);
        CrawlerTabPanel panel = new CrawlerTabPanel(
            new HeadlessUiHost(), config, state, null, null,
            ExcelSpecMatcher.empty(), new PolicyLedger(), new ProxyInterceptQueue(),
            new ActionGateAuditLog(), new RoleStore(), new DuplicateLineageTracker(),
            new VisualClusterStore(), new InstantEventBus(), batch, null
        );
        try {
            Theme.setDark(false);
            int lightW = panel.getTopBar().getPreferredSize().width;
            assertTrue("Law 5: Top Bar Light Mode preferredWidth <= 1100 (actual=" + lightW + ")", lightW <= 1100);

            Theme.setDark(true);
            int darkW = panel.getTopBar().getPreferredSize().width;
            assertTrue("Law 5: Top Bar Dark Mode preferredWidth <= 1100 (actual=" + darkW + ")", darkW <= 1100);
        } finally {
            Theme.setDark(prevDark);
            panel.dispose();
            batch.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Law 6: HttpEncodingGuard ASCII Safe + ISO-8859-1 Round-Trip + Virtual 200 OK
    // -------------------------------------------------------------------------
    private static void testLaw6HttpEncodingGuard() {
        String vn = "Xóa đơn hàng #42 - Tiếng Việt Đđ ệ ơ";
        String safe = HttpEncodingGuard.asciiSafe(vn);
        assertEquals("asciiSafe strips Vietnamese diacritics cleanly",
            "Xoa don hang #42 - Tieng Viet Dd e o", safe);
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            assertTrue("Strictly 7-bit printable ASCII", c == '\r' || c == '\n' || c == '\t' || (c >= 0x20 && c <= 0x7E));
        }

        // ISO-8859-1 high byte round-trip (must not expand 0xE9 into UTF-8 0xC3 0xA9)
        byte[] original = new byte[] { 0x47, 0x45, 0x54, 0x20, 0x2F, (byte) 0xE9, (byte) 0xFF, (byte) 0x80 };
        String isoStr = HttpEncodingGuard.fromIso88591Bytes(original);
        byte[] roundTripped = HttpEncodingGuard.toIso88591Bytes(isoStr);
        assertEquals("ISO-8859-1 byte length preserved", original.length, roundTripped.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals("Byte " + i + " preserved verbatim", original[i], roundTripped[i]);
        }

        byte[] virtualHttp = HttpEncodingGuard.buildFullVirtual200OkHttpMessage("Guardrail Hold", "/api/orders/1");
        String wire = new String(virtualHttp, StandardCharsets.US_ASCII);
        assertTrue("Virtual response starts with HTTP/1.1 200 OK", wire.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue("Virtual response includes X-Myth-Proxy-Hold: 1", wire.contains("X-Myth-Proxy-Hold: 1"));
        int sep = wire.indexOf("\r\n\r\n");
        assertTrue("Virtual response has header/body separator", sep > 0);
        Map<String, Object> json = MiniJson.parseObject(wire.substring(sep + 4));
        assertEquals("heldAtProxy flag", Boolean.TRUE, json.get("heldAtProxy"));
        assertEquals("virtualResponse flag", Boolean.TRUE, json.get("virtualResponse"));
    }

    // -------------------------------------------------------------------------
    // Law 7: Pure-Java Core (Zero Montoya API, Swing, AWT, or FlatLaf)
    // -------------------------------------------------------------------------
    private static void testLaw7CorePureJavaZeroMontoyaOrSwing(Path mainJava) throws IOException {
        Path coreDir = mainJava.resolve("com/mythofcrawl/crawler/core");
        List<Path> coreFiles = listJavaFiles(coreDir);
        assertTrue("core/ files exist", !coreFiles.isEmpty());
        List<String> violations = new ArrayList<>();
        for (Path f : coreFiles) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            if (MONTOYA_IMPORT_RX.matcher(content).find()
                || SWING_IMPORT_RX.matcher(content).find()
                || AWT_IMPORT_RX.matcher(content).find()
                || FLATLAF_RX.matcher(content).find()) {
                violations.add(mainJava.relativize(f).toString());
            }
        }
        assertTrue("Law 7: zero Montoya/Swing/AWT/FlatLaf in core/: " + violations, violations.isEmpty());
    }

    // -------------------------------------------------------------------------
    // PolicyEvaluator: #1 ALLOW Precedence + 5 Scopes + uiAction + simulate()
    // -------------------------------------------------------------------------
    private static void testPolicyEvaluatorAllowPrecedenceAndScopes() {
        PolicyLedger ledger = new PolicyLedger();
        PolicyRule globalHold = new PolicyRule("r-global", "Global Hold", 10, true, false,
            PolicyScope.GLOBAL, GuardrailMode.HOLD_AT_PROXY);
        PolicyRule deleteIntercept = new PolicyRule("r-del", "Delete Gate", 50, true, false,
            PolicyScope.METHOD_OR_ACTION, GuardrailMode.INTERCEPT_WAIT)
            .withMethods("DELETE").withUiActions("delete");
        PolicyRule patternHold = new PolicyRule("r-pat", "Orders Pattern", 80, true, false,
            PolicyScope.PATH_PATTERN, GuardrailMode.LOCK_2XX)
            .withPathPattern("/api/orders/*");
        PolicyRule exactHold = new PolicyRule("r-exact", "Exact Order Delete", 120, true, false,
            PolicyScope.EXACT_API, GuardrailMode.HOLD_AT_PROXY)
            .withExactEndpoint("DELETE /api/orders/1");

        ledger.addRule(globalHold);
        ledger.addRule(deleteIntercept);
        ledger.addRule(patternHold);
        ledger.addRule(exactHold);

        // Specificity check: EXACT_API wins over PATH_PATTERN / METHOD_OR_ACTION / GLOBAL
        PolicyEvaluator.Decision d1 = PolicyEvaluator.simulate(ledger, "Orders", "example.com",
            "DELETE", "/api/orders/1", "delete");
        assertTrue("Guard blocks when no ALLOW rule", !d1.allowed);
        assertEquals("Exact rule wins by specificity", "r-exact", d1.winningRule.id);
        assertEquals("Simulate must not increment hits", 0L, exactHold.getHits());

        // Evaluate increments hit count
        PolicyEvaluator.Decision d1Live = PolicyEvaluator.evaluate(ledger, "Orders", "example.com",
            "DELETE", "/api/orders/1", "delete");
        assertEquals("Evaluate increments hit counter", 1L, exactHold.getHits());
        assertEquals("Winning mode is HOLD_AT_PROXY", GuardrailMode.HOLD_AT_PROXY, d1Live.effectiveMode);

        // Method + uiAction check
        PolicyEvaluator.Decision d2 = PolicyEvaluator.simulate(ledger, "Users", "example.com",
            "DELETE", "/api/users/9", "delete");
        assertEquals("METHOD_OR_ACTION beats GLOBAL", "r-del", d2.winningRule.id);
        assertEquals("Mode INTERCEPT_WAIT", GuardrailMode.INTERCEPT_WAIT, d2.effectiveMode);

        // uiAction mismatch falls back to GLOBAL
        PolicyEvaluator.Decision d3 = PolicyEvaluator.simulate(ledger, "Users", "example.com",
            "DELETE", "/api/users/9", "click");
        assertEquals("Mismatched uiAction falls back to GLOBAL", "r-global", d3.winningRule.id);

        // Add low-priority ALLOW rule -> #1 ALLOW precedence MUST override even high-priority EXACT_API block!
        PolicyRule allowRule = new PolicyRule("r-allow", "Explicit Allow", 1, true, true,
            PolicyScope.EXACT_API, GuardrailMode.NONE)
            .withExactEndpoint("DELETE /api/orders/1");
        ledger.addRule(allowRule);

        PolicyEvaluator.Decision dAllow = PolicyEvaluator.evaluate(ledger, "Orders", "example.com",
            "DELETE", "/api/orders/1", "delete");
        assertTrue("#1 ALLOW precedence overrides blocking rules", dAllow.allowed);
        assertEquals("Effective mode is NONE on ALLOW", GuardrailMode.NONE, dAllow.effectiveMode);
        assertEquals("ALLOW rule is winningRule", "r-allow", dAllow.winningRule.id);
    }

    // -------------------------------------------------------------------------
    // PolicyLedger: CRUD, Trash/Undo, 4-Level NONE Release, Playbooks, JSON
    // -------------------------------------------------------------------------
    private static void testPolicyLedgerCrudTrashUndo4LevelReleaseAndJson() {
        PolicyLedger ledger = new PolicyLedger();
        AtomicInteger notifications = new AtomicInteger(0);
        try (InstantEventBus.Subscription sub = ledger.subscribe(notifications::incrementAndGet)) {
            PolicyRule r1 = new PolicyRule("r1", "Rule 1", 100, true, false,
                PolicyScope.GLOBAL, GuardrailMode.HOLD_AT_PROXY);
            PolicyRule r2 = new PolicyRule("r2", "Rule 2", 200, true, false,
                PolicyScope.PATH_PATTERN, GuardrailMode.INTERCEPT_WAIT).withPathPattern("/api/admin/*");
            ledger.addRule(r1);
            ledger.addRule(r2);
            assertEquals("Two rules added", 2, ledger.snapshotRules().size());

            // Duplicate + Reorder
            PolicyRule dup = ledger.duplicateRule("r1");
            assertNotNull("Duplicate creates copy", dup);
            assertEquals("Three rules after duplicate", 3, ledger.snapshotRules().size());
            assertTrue("moveToTop moves dup to index 0", ledger.moveToTop(dup.id));
            assertEquals("dup is now first", dup.id, ledger.snapshotRules().get(0).id);

            // Soft-delete -> Trash -> Undo
            assertTrue("deleteRule moves to trash", ledger.deleteRule(dup.id));
            assertEquals("One item in trash", 1, ledger.snapshotTrash().size());
            assertEquals("Two rules remain", 2, ledger.snapshotRules().size());

            PolicyRule undone = ledger.undoLastDelete();
            assertNotNull("undoLastDelete restores rule", undone);
            assertEquals("Trash is empty after undo", 0, ledger.snapshotTrash().size());
            assertEquals("Restored at original index 0", dup.id, ledger.snapshotRules().get(0).id);

            // Delete and restoreFromTrash by id
            assertTrue("delete r2", ledger.deleteRule("r2"));
            assertNotNull("restoreFromTrash restores r2", ledger.restoreFromTrash("r2"));
            ledger.deleteRule(dup.id);
            ledger.emptyTrash();
            assertEquals("emptyTrash clears trash", 0, ledger.snapshotTrash().size());

            // Level 3 & Level 2 release
            ledger.releaseEndpointToNone("DELETE /api/orders/55");
            PolicyEvaluator.Decision dEp = PolicyEvaluator.simulate(ledger, "Sales", "h",
                "DELETE", "/api/orders/55", "delete");
            assertTrue("Level 3 releaseEndpointToNone grants ALLOW", dEp.allowed);

            ledger.releaseBranchToNone("OrdersBranch");
            PolicyEvaluator.Decision dBranch = PolicyEvaluator.simulate(ledger, "OrdersBranch > Edit", "h",
                "POST", "/api/orders/create", "submit");
            assertTrue("Level 2 releaseBranchToNone unlocks entire branch", dBranch.allowed);

            // Level 1 release all
            ledger.releaseAllToNone();
            PolicyEvaluator.Decision dOther = PolicyEvaluator.simulate(ledger, "Other", "h",
                "DELETE", "/api/admin/nuke", "delete");
            assertTrue("Level 1 releaseAllToNone releases everything", dOther.allowed);

            // JSON round-trip
            PolicyLedger presetLedger = new PolicyLedger();
            presetLedger.installSafeReadOnlyPlaybook();
            String json = presetLedger.toJson();
            PolicyLedger reloaded = new PolicyLedger();
            reloaded.loadFromJson(json);
            int loadedCount = reloaded.snapshotRules().size();
            assertEquals("JSON round-trip restores exact rule count",
                presetLedger.snapshotRules().size(), loadedCount);
            assertTrue("0ms synchronous notifications fired", notifications.get() >= 5);

            ActionGateAuditLog auditLog = new ActionGateAuditLog();
            auditLog.record(ActionGateAuditLog.Outcome.HOLD_AT_PROXY, "Orders", "example.com",
                "DELETE", "/api/orders/55", "delete", r1, "Held");
            assertEquals("AuditLog records entry", 1, auditLog.snapshot().size());
        }
    }

    // -------------------------------------------------------------------------
    // ProxyInterceptQueue: Fail-Safe Timeout DROP, Forward w/ Edit, Draft, 0ms Event
    // -------------------------------------------------------------------------
    private static void testProxyInterceptQueueFailSafeDropForwardDraftAnd0msBus() throws Exception {
        ProxyInterceptQueue queue = new ProxyInterceptQueue();
        assertTrue("autoOpenWindow defaults to false", !queue.isAutoOpenWindow());

        AtomicInteger enqueuedEvents = new AtomicInteger(0);
        AtomicReference<ProxyInterceptQueue.QueueEventKind> lastKind = new AtomicReference<>();
        CountDownLatch enqueueLatch = new CountDownLatch(1);
        try (InstantEventBus.Subscription sub = queue.subscribe(ev -> {
            lastKind.set(ev.kind);
            if (ev.kind == ProxyInterceptQueue.QueueEventKind.ENQUEUED) {
                enqueuedEvents.incrementAndGet();
                enqueueLatch.countDown();
            }
        })) {
            // 1. Timeout fail-safe MUST resolve to DROP
            byte[] reqBytes = HttpEncodingGuard.toIso88591Bytes("DELETE /api/orders/1 HTTP/1.1\r\nHost: ex\r\n\r\n");
            ProxyInterceptQueue.InterceptResolution timedOut = queue.enqueueAndWait(
                "Orders", "ex", "DELETE", "/api/orders/1", reqBytes, "Guard hold", "SafeDelete", 60L);
            assertEquals("Timeout fail-safe resolves to DROP",
                ProxyInterceptQueue.ResolutionAction.DROP, timedOut.action);
            assertEquals("Timeout emits TIMEOUT_DROP",
                ProxyInterceptQueue.QueueEventKind.TIMEOUT_DROP, lastKind.get());
            assertEquals("Pending queue cleared after timeout", 0, queue.pendingCount());

            // 2. Concurrent resolveForward with edited Raw HTTP
            AtomicReference<ProxyInterceptQueue.InterceptResolution> fwdRes = new AtomicReference<>();
            CountDownLatch fwdDone = new CountDownLatch(1);
            CountDownLatch secondEnqueued = new CountDownLatch(1);
            try (InstantEventBus.Subscription sub2 = queue.subscribe(ev -> {
                if (ev.kind == ProxyInterceptQueue.QueueEventKind.ENQUEUED && queue.pendingCount() > 0) {
                    secondEnqueued.countDown();
                }
            })) {
                Thread worker = new Thread(() -> {
                    fwdRes.set(queue.enqueueAndWait(
                        "Orders", "ex", "POST", "/api/orders", reqBytes, "Wait edit", "RuleX", 4000L));
                    fwdDone.countDown();
                });
                worker.setDaemon(true);
                worker.start();

                assertTrue("0ms enqueue notification arrived promptly", secondEnqueued.await(1, TimeUnit.SECONDS));
                List<ProxyInterceptQueue.PendingInterceptItem> items = queue.snapshotPending();
                assertEquals("One item waiting in queue", 1, items.size());
                String itemId = items.get(0).id;
                String editedRaw = "POST /api/orders HTTP/1.1\r\nHost: ex\r\nX-Edited: \u00E9\r\n\r\n";
                assertTrue("resolveForward succeeds", queue.resolveForward(itemId, editedRaw));
                assertTrue("Worker resumed cleanly", fwdDone.await(1, TimeUnit.SECONDS));
                assertEquals("Resolution is FORWARD",
                    ProxyInterceptQueue.ResolutionAction.FORWARD, fwdRes.get().action);
                assertEquals("Edited ISO-8859-1 bytes preserved",
                    editedRaw, HttpEncodingGuard.fromIso88591Bytes(fwdRes.get().editedRawBytes));
            }

            // 3. Save Draft
            var fut = queue.enqueue("Users", "ex", "PUT", "/api/users/2", reqBytes, "Draft test", "RuleY", 4000L);
            String draftId = queue.snapshotPending().get(0).id;
            assertTrue("resolveSaveDraft succeeds", queue.resolveSaveDraft(draftId, "PUT /api/users/2 HTTP/1.1\r\n\r\n"));
            assertEquals("Draft resolution action is SAVE_DRAFT",
                ProxyInterceptQueue.ResolutionAction.SAVE_DRAFT, fut.get(1, TimeUnit.SECONDS).action);
            assertEquals("Saved draft recorded", 1, queue.snapshotDrafts().size());
        }
    }

    // -------------------------------------------------------------------------
    // LiveIdPool + RoleProfile JWT Decoding & TTL
    // -------------------------------------------------------------------------
    private static void testLiveIdPoolAndRoleProfileJwtTtl() {
        LiveIdPool pool = new LiveIdPool();
        int harvested = pool.harvestFromJson("/api/orders", "{\"id\":9001,\"status\":\"OPEN\"}");
        assertTrue("Harvested live ID from JSON", harvested >= 1);
        assertEquals("Latest ID for orders is 9001", "9001", pool.getLatestId("orders"));
        assertEquals("Stale numeric ID substituted cleanly",
            "/api/orders/9001", pool.substituteInPath("/api/orders/123"));

        String headerB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long iat = 1_700_000_000L;
        long exp = 1_700_003_600L;
        String payloadJson = "{\"sub\":\"tester-1\",\"role\":\"ADMIN\",\"iss\":\"myth-auth\",\"iat\":"
            + iat + ",\"exp\":" + exp + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String jwt = headerB64 + "." + payloadB64 + ".fakesig";

        RoleProfile.JwtInfo info = RoleProfile.decodeJwt(jwt);
        assertTrue("JWT validFormat", info.validFormat);
        assertEquals("JWT subject", "tester-1", info.subject);
        assertEquals("JWT role claim", "ADMIN", info.roleClaim);
        assertEquals("JWT issuer", "myth-auth", info.issuer);
        assertEquals("Remaining seconds at iat+600", 3000L, info.remainingSeconds(iat + 600L));
        assertTrue("Not expired at iat+600", !info.isExpired(iat + 600L));
        assertTrue("Expired at exp+1", info.isExpired(exp + 1L));

        RoleStore store = new RoleStore();
        RoleProfile admin = new RoleProfile("role-admin", "Admin", true, "ADMIN")
            .withHeader("Authorization", "Bearer " + jwt);
        store.addRole(admin);
        assertEquals("RoleStore stores role", 1, store.snapshotRoles().size());
    }

    // -------------------------------------------------------------------------
    // ExcelSpecMatcher: Custom {placeholder} Segments + Backward Compatibility
    // -------------------------------------------------------------------------
    private static void testExcelSpecMatcherCustomPlaceholders() {
        String replaced = ExcelSpecMatcher.replaceSegmentWithPlaceholder("/api/a/sess_99/c", 2, "session");
        assertEquals("replaceSegmentWithPlaceholder replaces segment 2", "/api/a/{session}/c", replaced);

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("STT", "Method", "Path API"));
        rows.add(List.of("1", "GET", "/api/a/static/c"));
        ExcelSpecMatcher matcher = ExcelSpecMatcher.buildFromRows("test.xlsx", rows, 0, 0, 1, 2, true);

        ApiSpecEntry added1 = matcher.addCustomPlaceholderSpec("CUSTOM-1", "GET", "/api/a/{session}/c", "Session");
        ApiSpecEntry added2 = matcher.addCustomPlaceholderSpec("CUSTOM-2", "POST", "/v1/{tenant}/orders/{orderId}", "TenantOrder");
        assertNotNull("Custom placeholder entry 1 created", added1);
        assertNotNull("Custom placeholder entry 2 created", added2);

        assertEquals("normalizeWithCustomPlaceholders preserves query",
            "/api/a/{session}/c?tab=1",
            matcher.normalizeWithCustomPlaceholders("/api/a/tok_abc123/c?tab=1"));
        assertEquals("normalizeWithCustomPlaceholders handles multi-placeholder templates",
            "/v1/{tenant}/orders/{orderId}",
            matcher.normalizeWithCustomPlaceholders("/v1/acme/orders/777"));

        // Exact spec must still beat wildcard custom spec
        ApiSpecEntry exactHit = matcher.findMatch("GET", "/api/a/static/c", null);
        assertNotNull("Exact match found", exactHit);
        assertEquals("Exact spec wins over custom placeholder wildcard", "1", exactHit.stt);

        ApiSpecEntry customHit1 = matcher.findMatch("GET", "/api/a/tok_999/c", null);
        assertNotNull("Custom session placeholder matched", customHit1);
        assertEquals("Matched CUSTOM-1", "CUSTOM-1", customHit1.stt);

        ApiSpecEntry customHit2 = matcher.findMatch("POST", "/v1/globex/orders/ord-55", null);
        assertNotNull("Custom multi-placeholder matched", customHit2);
        assertEquals("Matched CUSTOM-2", "CUSTOM-2", customHit2.stt);
    }

    // -------------------------------------------------------------------------
    // FontManager + InstantEventBus + ChangesBatch + version.properties
    // -------------------------------------------------------------------------
    private static void testFontManagerInstantEventBusAndVersionProps() throws Exception {
        FontManager.ensureInitialized();
        assertNotNull("activeUiFamily non-null", FontManager.activeUiFamily());
        assertNotNull("activeMonoFamily non-null", FontManager.activeMonoFamily());
        assertNotNull("FontManager uiRegular non-null", FontManager.uiRegular());
        assertNotNull("FontManager monoRegular non-null", FontManager.monoRegular());

        try (InputStream in = BlueprintGuardTests.class.getResourceAsStream("/version.properties")) {
            assertNotNull("version.properties exists on classpath", in);
            Properties props = new Properties();
            props.load(in);
            assertEquals("Version is V_1.1_beta", "V_1.1_beta", props.getProperty("version"));
        }

        InstantEventBus bus = new InstantEventBus();
        AtomicReference<String> received = new AtomicReference<>();
        try (InstantEventBus.Subscription sub = bus.subscribe(String.class, received::set)) {
            bus.publish("hello-guard");
            assertEquals("InstantEventBus delivers synchronously", "hello-guard", received.get());
        }
        bus.publish("after-close");
        assertEquals("Unsubscribed listener receives nothing", "hello-guard", received.get());

        ChangesBatch batch = new ChangesBatch(500L);
        AtomicReference<ChangesBatch.BatchSnapshot> snapRef = new AtomicReference<>();
        try (InstantEventBus.Subscription sub = batch.onFlush(snapRef::set)) {
            batch.markAdded(3);
            batch.flushNow();
            assertNotNull("ChangesBatch flushed", snapRef.get());
            assertTrue("treeStale marked on add", snapRef.get().treeStale);
            assertTrue("tableStale marked on add", snapRef.get().tableStale);
            assertEquals("addedCount=3", 3, snapRef.get().addedCount);
        } finally {
            batch.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // 0ms Real-Time Push: Enqueue Pending Item Auto-Expands Collapsed Branch
    // -------------------------------------------------------------------------
    private static void testRealTimePushZeroDelayAutoExpand() throws Exception {
        CrawlConfig config = new CrawlConfig();
        CrawlState state = new CrawlState();
        state.getActionGroups().openGroup(
            "Trang chủ > Đơn hàng > Xóa đơn", "button", "Xóa đơn", "https://example.com/orders");

        PolicyLedger ledger = new PolicyLedger();
        ProxyInterceptQueue queue = new ProxyInterceptQueue();
        ChangesBatch batch = new ChangesBatch(60_000L); // Long interval so only 0ms push can update tree
        CrawlerTabPanel tabPanel = new CrawlerTabPanel(
            new HeadlessUiHost(), config, state, null, null,
            ExcelSpecMatcher.empty(), ledger, queue, new ActionGateAuditLog(),
            new RoleStore(), new DuplicateLineageTracker(), new VisualClusterStore(),
            new InstantEventBus(), batch, null
        );
        try {
            TreeWorkspacePanel treeWs = tabPanel.getTreeWorkspacePanel();
            flushEdt();
            treeWs.rebuildTree();
            treeWs.collapseAll();
            flushEdt();

            DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeWs.getTree().getModel().getRoot();
            DefaultMutableTreeNode targetBranch = findTreeNodeByTitle(root, "Xóa đơn");
            assertNotNull("Target branch node exists before enqueue", targetBranch);
            TreePath branchTreePath = new TreePath(targetBranch.getPath());
            assertTrue("Target branch is initially collapsed", !treeWs.getTree().isExpanded(branchTreePath));

            // Enqueue pending intercept item -> 0ms listener MUST insert [CHỜ DUYỆT] and expand branch immediately
            byte[] raw = HttpEncodingGuard.toIso88591Bytes("DELETE /api/orders/77 HTTP/1.1\r\nHost: example.com\r\n\r\n");
            queue.enqueue("Trang chủ > Đơn hàng > Xóa đơn", "example.com", "DELETE", "/api/orders/77",
                raw, "Intercepted by DeleteGate", "DeleteGate", 5000L);
            flushEdt();

            assertEquals("Tab stays on Tree-First workspace (index 0)",
                CrawlerTabPanel.TAB_TREE, tabPanel.getMainTabs().getSelectedIndex());
            assertTrue("0ms push auto-expanded collapsed target branch without batch flush",
                treeWs.getTree().isExpanded(branchTreePath));
            DefaultMutableTreeNode heldNode = findTreeNodeByTitle(targetBranch, "[CHỜ DUYỆT] DELETE /api/orders/77");
            assertNotNull("[CHỜ DUYỆT] child node inserted immediately under branch", heldNode);
            assertEquals("Queue pending count is 1", 1, queue.pendingCount());
        } finally {
            queue.dropAll();
            tabPanel.dispose();
            batch.shutdown();
        }
    }

    private static DefaultMutableTreeNode findTreeNodeByTitle(DefaultMutableTreeNode root, String substring) {
        if (root == null) {
            return null;
        }
        Enumeration<?> en = root.breadthFirstEnumeration();
        while (en.hasMoreElements()) {
            Object next = en.nextElement();
            if (next instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) next;
                Object uo = node.getUserObject();
                if (uo instanceof ThemedTree.NodeWrapper) {
                    ThemedTree.NodeWrapper w = (ThemedTree.NodeWrapper) uo;
                    if (w.title != null && w.title.contains(substring)) {
                        return node;
                    }
                } else if (uo != null && uo.toString().contains(substring)) {
                    return node;
                }
            }
        }
        return null;
    }

    private static void flushEdt() throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait(() -> {});
        }
    }

    // -------------------------------------------------------------------------
    // Headless Light & Dark Mode Visual PNG Snapshots (5 Key Screens x 2 Modes)
    // -------------------------------------------------------------------------
    private static void testVisualVerificationPngSnapshots(Path mainJava) throws Exception {
        boolean prevDark = Theme.isDark();
        Path projectRoot = mainJava.getParent().getParent().getParent();
        Path outDir = projectRoot.resolve("build").resolve("visual-snapshots");
        Files.createDirectories(outDir);

        CrawlConfig config = new CrawlConfig();
        CrawlState state = new CrawlState();
        ActionGroup g = state.getActionGroups().openGroup(
            "Trang chủ > Đơn hàng > Thanh toán", "button", "Thanh toán", "https://example.com/checkout");
        assertNotNull("Sample ActionGroup created", g);

        PolicyLedger ledger = new PolicyLedger();
        ledger.installSafeReadOnlyPlaybook();

        ProxyInterceptQueue queue = new ProxyInterceptQueue();
        byte[] sampleReq = HttpEncodingGuard.toIso88591Bytes(
            "DELETE /api/v1/orders/9001 HTTP/1.1\r\nHost: target.example.com\r\nAuthorization: Bearer demo\r\n\r\n");
        queue.enqueue("Trang chủ > Đơn hàng > Thanh toán", "target.example.com", "DELETE", "/api/v1/orders/9001",
            sampleReq, "Chặn duyệt thao tác XÓA (INTERCEPT_WAIT)", "Chặn Xóa Đơn Hàng", 30_000L);

        RoleStore roleStore = new RoleStore();
        roleStore.addRole(new RoleProfile("admin-1", "Quản trị viên (ADMIN)", true, "ADMIN")
            .withHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGUiOiJBRE1JTiIsImV4cCI6MTkwMDAwMDAwMH0.sig"));
        roleStore.addRole(new RoleProfile("user-1", "Người dùng thường (USER)", true, "USER")
            .withCookie("SESSIONID", "usr_998123"));

        ActionGateAuditLog auditLog = new ActionGateAuditLog();
        auditLog.record(ActionGateAuditLog.Outcome.INTERCEPT_WAIT,
            "Trang chủ > Đơn hàng > Thanh toán", "target.example.com", "DELETE", "/api/v1/orders/9001",
            "delete", ledger.snapshotRules().isEmpty() ? null : ledger.snapshotRules().get(0),
            "Giữ chờ duyệt trên Intercept Queue");

        ChangesBatch batch = new ChangesBatch(60_000L);
        CrawlerTabPanel tabPanel = new CrawlerTabPanel(
            new HeadlessUiHost(), config, state, null, null,
            ExcelSpecMatcher.empty(), ledger, queue, auditLog,
            roleStore, new DuplicateLineageTracker(), new VisualClusterStore(),
            new InstantEventBus(), batch, null
        );

        try {
            flushEdt();
            boolean[] modes = { false, true };
            for (boolean dark : modes) {
                String suffix = dark ? "dark" : "light";
                Theme.setDark(dark);
                flushEdt();

                // 01: Tree-First Workspace + Single-Row Top Bar
                tabPanel.getMainTabs().setSelectedIndex(CrawlerTabPanel.TAB_TREE);
                tabPanel.getTreeWorkspacePanel().expandAll();
                renderAndVerifyPng(tabPanel, outDir.resolve("01_tree_topbar_" + suffix + ".png"));

                // 02: Roles & Auth Tab
                tabPanel.getMainTabs().setSelectedIndex(CrawlerTabPanel.TAB_ROLES);
                tabPanel.getRolesTab().refreshData();
                renderAndVerifyPng(tabPanel, outDir.resolve("02_roles_" + suffix + ".png"));

                // 03: Intercept Queue View
                JPanel queuePanel = tabPanel.createInterceptQueueViewPanel();
                renderAndVerifyPng(queuePanel, outDir.resolve("03_intercept_queue_" + suffix + ".png"));

                // 04: Guardrail Policy Ledger Tab
                tabPanel.getMainTabs().setSelectedIndex(CrawlerTabPanel.TAB_GUARDRAILS);
                tabPanel.getGuardrailLedgerTab().refreshData();
                renderAndVerifyPng(tabPanel, outDir.resolve("04_guardrail_ledger_" + suffix + ".png"));

                // 05: Playbooks & Guide Tab
                tabPanel.getMainTabs().setSelectedIndex(CrawlerTabPanel.TAB_PLAYBOOKS);
                tabPanel.getPlaybooksGuideTab().refreshData();
                renderAndVerifyPng(tabPanel, outDir.resolve("05_playbooks_guide_" + suffix + ".png"));
            }
        } finally {
            Theme.setDark(prevDark);
            queue.dropAll();
            tabPanel.dispose();
            batch.shutdown();
        }
    }

    private static void renderAndVerifyPng(JComponent comp, Path pngFile) throws Exception {
        final int w = 1280;
        final int h = 760;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Runnable renderTask = () -> {
            comp.setSize(w, h);
            layoutRecursively(comp);
            Graphics2D g2 = img.createGraphics();
            try {
                g2.setColor(Theme.canvas());
                g2.fillRect(0, 0, w, h);
                comp.printAll(g2);
            } finally {
                g2.dispose();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            renderTask.run();
        } else {
            SwingUtilities.invokeAndWait(renderTask);
        }

        ImageIO.write(img, "png", pngFile.toFile());
        assertTrue("Snapshot PNG file exists: " + pngFile, Files.isRegularFile(pngFile));
        byte[] bytes = Files.readAllBytes(pngFile);
        assertTrue("Snapshot PNG non-trivial size (> 4 KB): " + pngFile + " (" + bytes.length + " bytes)",
            bytes.length > 4096);
        assertTrue("Valid PNG magic header: " + pngFile,
            (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47);

        Set<Integer> distinctRgb = new HashSet<>();
        for (int y = 0; y < h; y += 16) {
            for (int x = 0; x < w; x += 16) {
                distinctRgb.add(img.getRGB(x, y));
            }
        }
        assertTrue("Rendered PNG is non-blank (distinct colors=" + distinctRgb.size() + "): " + pngFile,
            distinctRgb.size() >= 4);
    }

    private static void layoutRecursively(Component comp) {
        if (comp == null) {
            return;
        }
        comp.doLayout();
        if (comp instanceof Container) {
            Container c = (Container) comp;
            int n = c.getComponentCount();
            for (int i = 0; i < n; i++) {
                layoutRecursively(c.getComponent(i));
            }
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertNotNull(String message, Object value) {
        if (value == null) {
            throw new AssertionError("Expected non-null: " + message);
        }
    }

    private static void assertEquals(String message, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
