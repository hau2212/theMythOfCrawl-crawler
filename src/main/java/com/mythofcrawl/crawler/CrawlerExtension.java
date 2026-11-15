package com.mythofcrawl.crawler;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.proxy.ControlPlaneHandler;
import com.mythofcrawl.crawler.proxy.ExtenderPushManager;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.LocalControlServer;
import com.mythofcrawl.crawler.proxy.StaticJsScanner;
import com.mythofcrawl.crawler.ui.CrawlerTabPanel;

import javax.swing.SwingUtilities;

/**
 * Main Burp Suite Montoya API Entry Point for "theMythOfCrawl Crawler".
 * Coordinates the Firefox/Chrome Proxy-Injected Crawler Agent, the local loopback control plane,
 * HTTP Header Filter, Extender -> Logger++ push ledger, static JS endpoint extraction, and audit UI tab.
 */
public final class CrawlerExtension implements BurpExtension, ExtensionUnloadingHandler {

    private static final String PREF_CONFIG_KEY = "theMythOfCrawl.config.v2";

    private MontoyaApi api;
    private CrawlConfig config;
    private LocalControlServer controlServer;
    private StaticJsScanner jsScanner;
    private ExtenderPushManager pushManager;
    private CrawlerTabPanel tabPanel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("theMythOfCrawl Crawler");

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
            jsScanner = new StaticJsScanner(config, state, matcher);
            controlServer = new LocalControlServer(
                config,
                state,
                () -> {
                    if (tabPanel != null) {
                        tabPanel.scheduleUiRefresh();
                    }
                },
                msg -> {
                    api.logging().logToOutput("[theMythOfCrawl] " + msg);
                    if (tabPanel != null) {
                        tabPanel.appendLog(msg);
                    }
                }
            );
            controlServer.start();

            ControlPlaneHandler reqHandler = new ControlPlaneHandler(config, state, controlServer.getPort());
            HtmlAgentInjector respHandler = new HtmlAgentInjector(
                config,
                state,
                reqHandler,
                jsScanner,
                matcher,
                () -> {
                    if (tabPanel != null) {
                        tabPanel.scheduleUiRefresh();
                    }
                },
                msg -> {
                    api.logging().logToOutput("[theMythOfCrawl] " + msg);
                    if (tabPanel != null) {
                        tabPanel.appendLog(msg);
                    }
                }
            );

            jsScanner.setOnUpdateCallback(() -> {
                if (tabPanel != null) {
                    tabPanel.scheduleUiRefresh();
                }
            });

            api.proxy().registerRequestHandler(reqHandler);
            api.proxy().registerResponseHandler(respHandler);

            this.pushManager = new ExtenderPushManager(
                api,
                config,
                () -> {
                    if (tabPanel != null) {
                        tabPanel.onPushLedgerUpdated();
                    }
                },
                msg -> {
                    api.logging().logToOutput("[theMythOfCrawl] " + msg);
                    if (tabPanel != null) {
                        tabPanel.appendLog(msg);
                    }
                }
            );
            api.http().registerHttpHandler(this.pushManager);

            Runnable savePrefs = this::persistPreferences;
            Runnable initUi = () -> {
                tabPanel = new CrawlerTabPanel(api, config, state, respHandler, pushManager, matcher, savePrefs);
                api.userInterface().applyThemeToComponent(tabPanel);
                api.userInterface().registerSuiteTab("theMythOfCrawl Crawler", tabPanel);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                initUi.run();
            } else {
                SwingUtilities.invokeAndWait(initUi);
            }

            api.extension().registerUnloadingHandler(this);
            api.logging().logToOutput(
                "[theMythOfCrawl] Loaded successfully! Loopback Control Server on 127.0.0.1:"
                + controlServer.getPort());
        } catch (Throwable ex) {
            api.logging().logToError("Failed to initialize theMythOfCrawl Crawler", ex);
            if (controlServer != null) {
                controlServer.stop();
            }
            if (jsScanner != null) {
                jsScanner.shutdown();
            }
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new RuntimeException(ex);
        }
    }

    private void persistPreferences() {
        if (api != null && config != null) {
            try {
                api.persistence().preferences().setString(PREF_CONFIG_KEY, config.toPersistJson());
            } catch (Exception ex) {
                api.logging().logToError("[theMythOfCrawl] Could not save preferences: " + ex.getMessage());
            }
        }
    }

    @Override
    public void extensionUnloaded() {
        persistPreferences();
        if (pushManager != null) {
            pushManager.requestCancel();
        }
        if (tabPanel != null) {
            tabPanel.dispose();
        }
        if (controlServer != null) {
            controlServer.stop();
        }
        if (jsScanner != null) {
            jsScanner.shutdown();
        }
    }
}
