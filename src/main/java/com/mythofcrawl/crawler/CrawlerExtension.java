package com.mythofcrawl.crawler;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.proxy.ControlPlaneHandler;
import com.mythofcrawl.crawler.proxy.HtmlAgentInjector;
import com.mythofcrawl.crawler.proxy.LocalControlServer;
import com.mythofcrawl.crawler.proxy.StaticJsScanner;
import com.mythofcrawl.crawler.ui.CrawlerTabPanel;

import javax.swing.SwingUtilities;

/**
 * Main Burp Suite Montoya API Entry Point for "theMythOfCrawl Crawler".
 * Coordinates the Firefox Proxy-Injected Crawler Agent, the local loopback control plane,
 * static JS endpoint extraction, strict Excel API checklist verification, and UI tab.
 */
public final class CrawlerExtension implements BurpExtension, ExtensionUnloadingHandler {

    private LocalControlServer controlServer;
    private StaticJsScanner jsScanner;
    private CrawlerTabPanel tabPanel;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("theMythOfCrawl Crawler");

        CrawlConfig config = new CrawlConfig();
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

            Runnable initUi = () -> {
                tabPanel = new CrawlerTabPanel(api, config, state, respHandler, matcher);
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

    @Override
    public void extensionUnloaded() {
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
