package com.mythofcrawl.crawler;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import com.mythofcrawl.crawler.bridge.BurpUiHost;
import com.mythofcrawl.crawler.core.auth.RoleStore;
import com.mythofcrawl.crawler.core.guardrail.ActionGateAuditLog;
import com.mythofcrawl.crawler.core.guardrail.LiveIdPool;
import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.ProducerRecreateEngine;
import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.ChangesBatch;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.core.tree.DuplicateLineageTracker;
import com.mythofcrawl.crawler.core.tree.VisualClusterStore;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.EventLogEntry;
import com.mythofcrawl.crawler.proxy.CdpSidecarBridge;
import com.mythofcrawl.crawler.proxy.ControlPlaneHandler;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.LocalControlServer;
import com.mythofcrawl.crawler.proxy.SeedDiscoveryHelper;
import com.mythofcrawl.crawler.proxy.StaticJsScanner;
import com.mythofcrawl.crawler.proxy.WebSocketCaptureHandler;
import com.mythofcrawl.crawler.ui.CrawlerTabPanel;

import javax.swing.SwingUtilities;

/**
 * Main Burp Suite Montoya API Entry Point for "theMythOfCrawl Crawler".
 * Coordinates the Firefox/Chrome Proxy-Injected Crawler Agent, the local loopback control plane,
 * WebSocket capture, optional CDP sidecar, HTTP Header Filter, Extender -> Logger++ push ledger,
 * static JS endpoint extraction, and audit UI tab.
 */
public final class CrawlerExtension implements BurpExtension, ExtensionUnloadingHandler {

    private static final String PREF_CONFIG_KEY = "theMythOfCrawl.config.v2";
    private static final String PREF_POLICIES_KEY = "theMythOfCrawl.policies.v1";
    private static final String PREF_ROLES_KEY = "theMythOfCrawl.roles.v1";

    private MontoyaApi api;
    private BurpUiHost uiHost;
    private CrawlConfig config;
    private InstantEventBus eventBus;
    private ChangesBatch changesBatch;
    private PolicyLedger policyLedger;
    private ProxyInterceptQueue interceptQueue;
    private ActionGateAuditLog gateAuditLog;
    private LiveIdPool liveIdPool;
    private ProducerRecreateEngine producerEngine;
    private RoleStore roleStore;
    private DuplicateLineageTracker lineageTracker;
    private VisualClusterStore visualClusterStore;
    private LocalControlServer controlServer;
    private StaticJsScanner jsScanner;
    private HtmlAgentInjector respHandler;
    private WebSocketCaptureHandler wsHandler;
    private CdpSidecarBridge cdpBridge;
    private ExtenderPushManager pushManager;
    private CrawlerTabPanel tabPanel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("theMythOfCrawl Crawler");

        this.uiHost = new BurpUiHost(api);
        this.eventBus = new InstantEventBus();
        this.changesBatch = new ChangesBatch();
        this.policyLedger = new PolicyLedger();
        this.interceptQueue = new ProxyInterceptQueue();
        this.gateAuditLog = new ActionGateAuditLog();
        this.liveIdPool = new LiveIdPool();
        this.producerEngine = new ProducerRecreateEngine();
        this.roleStore = new RoleStore();
        this.lineageTracker = new DuplicateLineageTracker();
        this.visualClusterStore = new VisualClusterStore();

        String savedPolicies = uiHost.loadPreference(PREF_POLICIES_KEY, "");
        if (savedPolicies != null && !savedPolicies.isEmpty()) {
            this.policyLedger.loadFromJson(savedPolicies);
        }
        String savedRoles = uiHost.loadPreference(PREF_ROLES_KEY, "");
        if (savedRoles != null && !savedRoles.isEmpty()) {
            this.roleStore.loadFromJson(savedRoles);
        }

        this.config = new CrawlConfig();
        try {
            String savedJson = api.persistence().preferences().getString(PREF_CONFIG_KEY);
            if (savedJson != null && !savedJson.isEmpty()) {
                this.config.loadFromPersistJson(savedJson);
                api.logging().logToOutput("[theMythOfCrawl] Loaded saved form/crawl preferences.");
            }
        } catch (Exception ex) {
            api.logging().logToError("[theMythOfCrawl] Could not load preferences: " + ex.getMessage());
        }
        // Safe Permission Gate always starts locked unless user explicitly enables auto-requests
        this.config.setAllowAutoRequests(false);
        CrawlState state = new CrawlState();
        ExcelSpecMatcher matcher = ExcelSpecMatcher.empty();

        try {
            java.util.function.Consumer<String> unifiedLogger = msg -> {
                state.addEventLog(msg);
                api.logging().logToOutput("[theMythOfCrawl] " + msg);
                if (tabPanel != null) {
                    tabPanel.appendLog(msg);
                }
            };

            Runnable notifyUi = () -> {
                if (changesBatch != null) {
                    changesBatch.markDirty();
                }
                if (tabPanel != null) {
                    tabPanel.scheduleUiRefresh();
                }
            };

            jsScanner = new StaticJsScanner(config, state, matcher);
            jsScanner.setLiveIdPool(liveIdPool);
            controlServer = new LocalControlServer(
                config,
                state,
                notifyUi,
                unifiedLogger,
                true
            );
            controlServer.setInterceptQueue(interceptQueue);
            controlServer.setLiveIdPool(liveIdPool);
            controlServer.start();

            ControlPlaneHandler reqHandler = new ControlPlaneHandler(config, state, controlServer.getPort());
            reqHandler.setGuardrailEngine(
                policyLedger,
                interceptQueue,
                gateAuditLog,
                controlServer.getSessionToken(),
                controlServer::getLatestUiAction
            );
            reqHandler.setChangesBatch(changesBatch);
            respHandler = new HtmlAgentInjector(
                api,
                config,
                state,
                reqHandler,
                jsScanner,
                matcher,
                notifyUi,
                unifiedLogger
            );
            respHandler.setLiveIdPool(liveIdPool);

            wsHandler = new WebSocketCaptureHandler(
                config,
                state,
                () -> respHandler != null && respHandler.isPassiveCapture(),
                matcher,
                notifyUi,
                unifiedLogger
            );

            respHandler.setOnMatcherUpdated(m -> {
                if (wsHandler != null) {
                    wsHandler.setMatcher(m);
                }
            });

            cdpBridge = new CdpSidecarBridge(
                config,
                controlServer,
                unifiedLogger
            );
            cdpBridge.start();

            jsScanner.setOnUpdateCallback(notifyUi);

            api.proxy().registerRequestHandler(reqHandler);
            api.proxy().registerResponseHandler(respHandler);
            api.proxy().registerWebSocketCreationHandler(wsHandler);

            this.pushManager = new ExtenderPushManager(
                api,
                config,
                () -> {
                    if (tabPanel != null) {
                        tabPanel.onPushLedgerUpdated();
                    }
                },
                unifiedLogger
            );
            api.http().registerHttpHandler(this.pushManager);

            Runnable savePrefs = this::persistPreferences;
            Runnable initUi = () -> {
                tabPanel = new CrawlerTabPanel(
                    uiHost,
                    config,
                    state,
                    respHandler,
                    pushManager,
                    matcher,
                    policyLedger,
                    interceptQueue,
                    gateAuditLog,
                    roleStore,
                    lineageTracker,
                    visualClusterStore,
                    eventBus,
                    changesBatch,
                    savePrefs
                );
                api.userInterface().applyThemeToComponent(tabPanel);
                api.userInterface().registerSuiteTab("theMythOfCrawl Crawler", tabPanel);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                initUi.run();
            } else {
                SwingUtilities.invokeAndWait(initUi);
            }

            api.extension().registerUnloadingHandler(this);
            String readyMsg = "Loaded successfully! Loopback Control Server on 127.0.0.1:" + controlServer.getPort();
            state.addEventLog(EventLogEntry.Level.INFO, "Suite", readyMsg);
            api.logging().logToOutput("[theMythOfCrawl] " + readyMsg);
        } catch (Throwable ex) {
            api.logging().logToError("Failed to initialize theMythOfCrawl Crawler", ex);
            if (cdpBridge != null) {
                cdpBridge.shutdown();
            }
            if (wsHandler != null) {
                wsHandler.shutdown();
            }
            if (respHandler != null) {
                respHandler.shutdown();
            }
            if (controlServer != null) {
                controlServer.stop();
            }
            if (jsScanner != null) {
                jsScanner.shutdown();
            }
            SeedDiscoveryHelper.shutdown();
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new RuntimeException(ex);
        }
    }

    private void persistPreferences() {
        if (uiHost != null) {
            if (config != null) {
                uiHost.savePreference(PREF_CONFIG_KEY, config.toPersistJson());
            }
            if (policyLedger != null) {
                uiHost.savePreference(PREF_POLICIES_KEY, policyLedger.toJson());
            }
            if (roleStore != null) {
                uiHost.savePreference(PREF_ROLES_KEY, roleStore.toJson());
            }
        }
    }

    public BurpUiHost getUiHost() { return uiHost; }
    public InstantEventBus getEventBus() { return eventBus; }
    public ChangesBatch getChangesBatch() { return changesBatch; }
    public PolicyLedger getPolicyLedger() { return policyLedger; }
    public ProxyInterceptQueue getInterceptQueue() { return interceptQueue; }
    public ActionGateAuditLog getGateAuditLog() { return gateAuditLog; }
    public LiveIdPool getLiveIdPool() { return liveIdPool; }
    public ProducerRecreateEngine getProducerEngine() { return producerEngine; }
    public RoleStore getRoleStore() { return roleStore; }
    public DuplicateLineageTracker getLineageTracker() { return lineageTracker; }
    public VisualClusterStore getVisualClusterStore() { return visualClusterStore; }

    @Override
    public void extensionUnloaded() {
        persistPreferences();
        if (interceptQueue != null) {
            interceptQueue.dropAll();
        }
        if (changesBatch != null) {
            changesBatch.shutdown();
        }
        if (eventBus != null) {
            eventBus.clear();
        }
        if (pushManager != null) {
            pushManager.requestCancel();
        }
        if (tabPanel != null) {
            tabPanel.dispose();
        }
        if (cdpBridge != null) {
            cdpBridge.shutdown();
        }
        if (wsHandler != null) {
            wsHandler.shutdown();
        }
        if (respHandler != null) {
            respHandler.shutdown();
        }
        if (controlServer != null) {
            controlServer.stop();
        }
        if (jsScanner != null) {
            jsScanner.shutdown();
        }
        SeedDiscoveryHelper.shutdown();
    }
}
