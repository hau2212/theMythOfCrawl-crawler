package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.EventLogEntry;
import com.mythofcrawl.crawler.model.ReplayPlan;
import com.mythofcrawl.crawler.proxy.BrowserLauncher;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared UI helper for "Tìm vị trí trên Web" (Visual Action Trail Replay & UI Spotlight Locator).
 * Supports both Auto-Step (tự chạy từng bước) and Manual Step (khoanh vùng từng bước) modes,
 * prioritizing Firefox -> Chrome -> System Default -> Copy URL for Burp Built-in Browser.
 */
public final class WebLocatorHelper {

    private WebLocatorHelper() {
    }

    public static void showLauncherMenu(
        Component anchor,
        CrawlState state,
        ApiCall call,
        Consumer<String> logger
    ) {
        if (call == null) {
            JOptionPane.showMessageDialog(anchor, "Hãy chọn một API có dữ liệu để tìm vị trí trên Web.",
                "Chưa chọn API", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JPopupMenu menu = buildPopupForCall(anchor, state, call, logger);
        menu.show(anchor, 0, anchor.getHeight());
    }

    public static void showLauncherMenuForCapture(
        Component anchor,
        CrawlState state,
        CapturedApiEntry cap,
        String title,
        Consumer<String> logger
    ) {
        if (cap == null) {
            JOptionPane.showMessageDialog(anchor, "API này chưa được kích hoạt hoặc không có URL.",
                "Chưa có dữ liệu", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        populateReplayItems(menu, anchor, state, null, cap, title, logger);
        menu.show(anchor, 0, anchor.getHeight());
    }

    public static JMenu createSubMenuForCall(
        Component parent,
        CrawlState state,
        Supplier<ApiCall> callSupplier,
        Consumer<String> logger
    ) {
        JMenu sub = new JMenu("Tìm vị trí trên Web");
        sub.setIcon(BurpIcons.locateWeb(AuditTheme.burpOrange(AuditTheme.isDark(null, parent))));

        JMenuItem miAutoFf = new JMenuItem("Tự chạy từng bước — Firefox (Ưu tiên)");
        JMenuItem miAutoCr = new JMenuItem("Tự chạy từng bước — Chrome");
        JMenuItem miManFf = new JMenuItem("Khoanh vùng thủ công — Firefox (Ưu tiên)");
        JMenuItem miManCr = new JMenuItem("Khoanh vùng thủ công — Chrome");
        JMenuItem miSys = new JMenuItem("Mở bằng Trình duyệt mặc định (Tự chạy)");
        JMenuItem miCopyBurp = new JMenuItem("Copy URL cho Burp Built-in Browser (Tự chạy)");
        JMenuItem miCopyBurpMan = new JMenuItem("Copy URL cho Burp Built-in Browser (Thủ công)");
        JMenuItem miStop = new JMenuItem("Dừng Spotlight đang chạy");

        miAutoFf.addActionListener(e -> launchForSupplier(parent, state, callSupplier, true, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miAutoCr.addActionListener(e -> launchForSupplier(parent, state, callSupplier, true, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miManFf.addActionListener(e -> launchForSupplier(parent, state, callSupplier, false, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miManCr.addActionListener(e -> launchForSupplier(parent, state, callSupplier, false, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miSys.addActionListener(e -> launchForSupplier(parent, state, callSupplier, true, BrowserLauncher.BrowserChoice.SYSTEM_DEFAULT, false, logger));
        miCopyBurp.addActionListener(e -> launchForSupplier(parent, state, callSupplier, true, null, true, logger));
        miCopyBurpMan.addActionListener(e -> launchForSupplier(parent, state, callSupplier, false, null, true, logger));
        miStop.addActionListener(e -> stopActiveReplay(state, logger));

        sub.add(miAutoFf);
        sub.add(miAutoCr);
        sub.add(miManFf);
        sub.add(miManCr);
        sub.addSeparator();
        sub.add(miSys);
        sub.add(miCopyBurp);
        sub.add(miCopyBurpMan);
        sub.addSeparator();
        sub.add(miStop);
        return sub;
    }

    public static JMenu createSubMenuForSpec(
        Component parent,
        CrawlState state,
        Supplier<ApiSpecEntry> specSupplier,
        Consumer<String> logger
    ) {
        JMenu sub = new JMenu("Tìm vị trí trên Web");
        sub.setIcon(BurpIcons.locateWeb(AuditTheme.burpOrange(AuditTheme.isDark(null, parent))));

        JMenuItem miAutoFf = new JMenuItem("Tự chạy từng bước — Firefox (Ưu tiên)");
        JMenuItem miAutoCr = new JMenuItem("Tự chạy từng bước — Chrome");
        JMenuItem miManFf = new JMenuItem("Khoanh vùng thủ công — Firefox (Ưu tiên)");
        JMenuItem miManCr = new JMenuItem("Khoanh vùng thủ công — Chrome");
        JMenuItem miCopyBurp = new JMenuItem("Copy URL cho Burp Browser (Tự chạy)");
        JMenuItem miStop = new JMenuItem("Dừng Spotlight đang chạy");

        miAutoFf.addActionListener(e -> launchForSpec(parent, state, specSupplier.get(), true, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miAutoCr.addActionListener(e -> launchForSpec(parent, state, specSupplier.get(), true, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miManFf.addActionListener(e -> launchForSpec(parent, state, specSupplier.get(), false, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miManCr.addActionListener(e -> launchForSpec(parent, state, specSupplier.get(), false, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miCopyBurp.addActionListener(e -> launchForSpec(parent, state, specSupplier.get(), true, null, true, logger));
        miStop.addActionListener(e -> stopActiveReplay(state, logger));

        sub.add(miAutoFf);
        sub.add(miAutoCr);
        sub.add(miManFf);
        sub.add(miManCr);
        sub.addSeparator();
        sub.add(miCopyBurp);
        sub.add(miStop);
        return sub;
    }

    private static JPopupMenu buildPopupForCall(
        Component parent,
        CrawlState state,
        ApiCall call,
        Consumer<String> logger
    ) {
        JPopupMenu menu = new JPopupMenu();
        populateReplayItems(menu, parent, state, call, call.capture, call.displayName(), logger);
        return menu;
    }

    private static void populateReplayItems(
        JPopupMenu menu,
        Component parent,
        CrawlState state,
        ApiCall call,
        CapturedApiEntry cap,
        String label,
        Consumer<String> logger
    ) {
        JMenuItem miAutoFf = new JMenuItem("Tự chạy từng bước — Firefox (Ưu tiên)");
        JMenuItem miAutoCr = new JMenuItem("Tự chạy từng bước — Chrome");
        JMenuItem miManFf = new JMenuItem("Khoanh vùng thủ công — Firefox (Ưu tiên)");
        JMenuItem miManCr = new JMenuItem("Khoanh vùng thủ công — Chrome");
        JMenuItem miSys = new JMenuItem("Mở bằng Trình duyệt mặc định (Tự chạy)");
        JMenuItem miCopyBurp = new JMenuItem("Copy URL cho Burp Built-in Browser (Tự chạy)");
        JMenuItem miCopyBurpMan = new JMenuItem("Copy URL cho Burp Built-in Browser (Thủ công)");

        miAutoFf.addActionListener(e -> executeLaunch(parent, state, call, cap, label, true, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miAutoCr.addActionListener(e -> executeLaunch(parent, state, call, cap, label, true, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miManFf.addActionListener(e -> executeLaunch(parent, state, call, cap, label, false, BrowserLauncher.BrowserChoice.FIREFOX, false, logger));
        miManCr.addActionListener(e -> executeLaunch(parent, state, call, cap, label, false, BrowserLauncher.BrowserChoice.CHROME, false, logger));
        miSys.addActionListener(e -> executeLaunch(parent, state, call, cap, label, true, BrowserLauncher.BrowserChoice.SYSTEM_DEFAULT, false, logger));
        miCopyBurp.addActionListener(e -> executeLaunch(parent, state, call, cap, label, true, null, true, logger));
        miCopyBurpMan.addActionListener(e -> executeLaunch(parent, state, call, cap, label, false, null, true, logger));

        menu.add(miAutoFf);
        menu.add(miAutoCr);
        menu.addSeparator();
        menu.add(miManFf);
        menu.add(miManCr);
        menu.addSeparator();
        menu.add(miSys);
        menu.add(miCopyBurp);
        menu.add(miCopyBurpMan);

        if (state != null && state.hasActiveReplayPlan()) {
            menu.addSeparator();
            JMenuItem miStop = new JMenuItem("Dừng Spotlight đang chạy");
            miStop.addActionListener(e -> stopActiveReplay(state, logger));
            menu.add(miStop);
        }
    }

    private static void launchForSupplier(
        Component parent,
        CrawlState state,
        Supplier<ApiCall> callSupplier,
        boolean autoPlay,
        BrowserLauncher.BrowserChoice choice,
        boolean copyOnly,
        Consumer<String> logger
    ) {
        ApiCall call = callSupplier != null ? callSupplier.get() : null;
        if (call == null) {
            JOptionPane.showMessageDialog(parent, "Hãy chọn một API để tìm vị trí trên Web.",
                "Chưa chọn API", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        executeLaunch(parent, state, call, call.capture, call.displayName(), autoPlay, choice, copyOnly, logger);
    }

    private static void launchForSpec(
        Component parent,
        CrawlState state,
        ApiSpecEntry entry,
        boolean autoPlay,
        BrowserLauncher.BrowserChoice choice,
        boolean copyOnly,
        Consumer<String> logger
    ) {
        if (entry == null) {
            return;
        }
        CapturedApiEntry cap = entry.getPrimaryCapture();
        if (cap == null) {
            JOptionPane.showMessageDialog(parent,
                "API STT " + entry.stt + " (" + entry.rawSpecPath + ") chưa được kích hoạt nên chưa có dấu vết UI.",
                "Chưa có dấu vết UI", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        executeLaunch(parent, state, null, cap, cap.actionPath, autoPlay, choice, copyOnly, logger);
    }

    public static void executeLaunch(
        Component parent,
        CrawlState state,
        ApiCall call,
        CapturedApiEntry cap,
        String label,
        boolean autoPlay,
        BrowserLauncher.BrowserChoice choice,
        boolean copyOnly,
        Consumer<String> logger
    ) {
        if (state == null) {
            return;
        }
        ReplayPlan plan;
        if (call != null) {
            plan = state.startReplayPlan(call, autoPlay);
        } else if (cap != null) {
            ActionGroup group = state.getActionGroups().findByPath(cap.actionPath);
            String endpoint = cap.method + " " + cap.requestPathWithQuery;
            plan = state.startReplayPlan(group, label != null ? label : cap.actionPath, endpoint, cap.fullUrl, autoPlay);
        } else {
            return;
        }

        if (plan == null || plan.startUrl == null || plan.startUrl.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "Không tìm thấy URL trang gốc để mở Replay Spotlight.",
                "Thiếu URL", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String modeText = autoPlay ? "Tự chạy từng bước" : "Khoanh vùng thủ công";
        if (copyOnly || choice == null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(plan.startUrl), null);
            String msg = "Đã bật Replay Spotlight (" + modeText + ", " + plan.steps.size()
                + " bước) & copy URL cho Burp Browser: " + plan.startUrl;
            state.addEventLog(EventLogEntry.Level.INFO, "Replay", msg);
            if (logger != null) {
                logger.accept(msg);
            }
            JOptionPane.showMessageDialog(parent,
                "Đã kích hoạt Replay Spotlight (" + modeText + " | " + plan.steps.size() + " bước) và copy URL:\n"
                    + plan.startUrl + "\n\nHãy dán vào Burp Built-in Browser (đã đi qua Proxy) để xem Spotlight!",
                "Đã sẵn sàng Replay trên Burp Browser", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String startUrl = plan.startUrl;
        new SwingWorker<BrowserLauncher.LaunchResult, Void>() {
            @Override
            protected BrowserLauncher.LaunchResult doInBackground() {
                return BrowserLauncher.launch(startUrl, choice);
            }

            @Override
            protected void done() {
                try {
                    BrowserLauncher.LaunchResult res = get();
                    String msg = "[" + modeText + " | " + plan.steps.size() + " bước | "
                        + res.browserUsed + "] " + res.message;
                    state.addEventLog(res.ok ? EventLogEntry.Level.INFO : EventLogEntry.Level.ERROR, "Replay", msg);
                    if (logger != null) {
                        logger.accept(msg);
                    }
                    if (!res.ok) {
                        JOptionPane.showMessageDialog(parent,
                            res.message + "\n\nBạn có thể dùng 'Copy URL cho Burp Browser' để mở thủ công.",
                            "Không mở được trình duyệt", JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    if (logger != null) {
                        logger.accept("Lỗi mở trình duyệt Replay: " + ex.getMessage());
                    }
                }
            }
        }.execute();
    }

    public static void stopActiveReplay(CrawlState state, Consumer<String> logger) {
        if (state == null) {
            return;
        }
        ReplayPlan active = state.getActiveReplayPlan();
        if (active != null) {
            state.stopReplayPlan(active.planId);
            String msg = "Đã dừng Visual Spotlight Replay (" + active.targetEndpoint + ").";
            state.addEventLog(EventLogEntry.Level.INFO, "Replay", msg);
            if (logger != null) {
                logger.accept(msg);
            }
        }
    }
}
