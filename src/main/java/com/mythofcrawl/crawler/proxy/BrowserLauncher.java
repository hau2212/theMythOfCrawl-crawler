package com.mythofcrawl.crawler.proxy;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches a clean target URL in Firefox (primary), Chrome (secondary), or system default browser
 * for Visual Action Trail Replay without mutating URL query/hash routes.
 */
public final class BrowserLauncher {

    public enum BrowserChoice {
        FIREFOX("Firefox (Ưu tiên)"),
        CHROME("Chrome"),
        SYSTEM_DEFAULT("Mặc định hệ thống");

        public final String label;

        BrowserChoice(String label) {
            this.label = label;
        }
    }

    public static final class LaunchResult {
        public final boolean ok;
        public final String browserUsed;
        public final String message;

        public LaunchResult(boolean ok, String browserUsed, String message) {
            this.ok = ok;
            this.browserUsed = browserUsed != null ? browserUsed : "";
            this.message = message != null ? message : "";
        }
    }

    private BrowserLauncher() {
    }

    public static LaunchResult launch(String cleanUrl, BrowserChoice choice) {
        if (cleanUrl == null || cleanUrl.trim().isEmpty()) {
            return new LaunchResult(false, "", "Không có URL hợp lệ để mở trình duyệt.");
        }
        String url = cleanUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return new LaunchResult(false, "", "URL không phải http/https: " + url);
        }
        BrowserChoice effective = choice != null ? choice : BrowserChoice.FIREFOX;

        if (effective == BrowserChoice.FIREFOX) {
            String exe = findFirefoxExecutable();
            if (exe != null) {
                try {
                    new ProcessBuilder(exe, "-new-tab", url).start();
                    return new LaunchResult(true, "Firefox", "Đã mở tab Firefox: " + url);
                } catch (Exception ex) {
                    return new LaunchResult(false, "Firefox", "Lỗi mở Firefox (" + ex.getMessage() + ").");
                }
            }
            LaunchResult fallback = trySystemBrowse(url);
            if (fallback.ok) {
                return new LaunchResult(true, fallback.browserUsed,
                    "Không tìm thấy firefox.exe — đã mở bằng trình duyệt mặc định: " + url);
            }
            return new LaunchResult(false, "Firefox",
                "Không tìm thấy Firefox trên máy. Hãy copy URL và dán vào Burp Built-in Browser hoặc Firefox.");
        }

        if (effective == BrowserChoice.CHROME) {
            String exe = findChromeExecutable();
            if (exe != null) {
                try {
                    new ProcessBuilder(exe, url).start();
                    return new LaunchResult(true, "Chrome", "Đã mở tab Chrome: " + url);
                } catch (Exception ex) {
                    return new LaunchResult(false, "Chrome", "Lỗi mở Chrome (" + ex.getMessage() + ").");
                }
            }
            LaunchResult fallback = trySystemBrowse(url);
            if (fallback.ok) {
                return new LaunchResult(true, fallback.browserUsed,
                    "Không tìm thấy chrome.exe — đã mở bằng trình duyệt mặc định: " + url);
            }
            return new LaunchResult(false, "Chrome",
                "Không tìm thấy Chrome trên máy. Hãy copy URL và dán vào Burp Built-in Browser hoặc Chrome.");
        }

        return trySystemBrowse(url);
    }

    private static LaunchResult trySystemBrowse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return new LaunchResult(true, "System Default", "Đã mở trình duyệt mặc định: " + url);
            }
        } catch (Exception ex) {
            return new LaunchResult(false, "System Default", "Không mở được trình duyệt mặc định: " + ex.getMessage());
        }
        return new LaunchResult(false, "System Default",
            "Môi trường không hỗ trợ mở trình duyệt mặc định; hãy dùng Copy URL.");
    }

    private static String findFirefoxExecutable() {
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addEnvPath(candidates, "ProgramFiles", "Mozilla Firefox\\firefox.exe");
            addEnvPath(candidates, "ProgramFiles(x86)", "Mozilla Firefox\\firefox.exe");
            addEnvPath(candidates, "LOCALAPPDATA", "Mozilla Firefox\\firefox.exe");
            candidates.add("C:\\Program Files\\Mozilla Firefox\\firefox.exe");
            candidates.add("C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe");
            String fromPath = findInSystemPath("firefox.exe");
            if (fromPath != null) candidates.add(fromPath);
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Firefox.app/Contents/MacOS/firefox");
        } else {
            candidates.add("/usr/bin/firefox");
            candidates.add("/usr/local/bin/firefox");
            String fromPath = findInSystemPath("firefox");
            if (fromPath != null) candidates.add(fromPath);
        }
        return firstExistingFile(candidates);
    }

    private static String findChromeExecutable() {
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addEnvPath(candidates, "ProgramFiles", "Google\\Chrome\\Application\\chrome.exe");
            addEnvPath(candidates, "ProgramFiles(x86)", "Google\\Chrome\\Application\\chrome.exe");
            addEnvPath(candidates, "LOCALAPPDATA", "Google\\Chrome\\Application\\chrome.exe");
            candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
            candidates.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
            String fromPath = findInSystemPath("chrome.exe");
            if (fromPath != null) candidates.add(fromPath);
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        } else {
            candidates.add("/usr/bin/google-chrome");
            candidates.add("/usr/bin/chromium");
            candidates.add("/usr/bin/chromium-browser");
            String fromPath = findInSystemPath("google-chrome");
            if (fromPath != null) candidates.add(fromPath);
        }
        return firstExistingFile(candidates);
    }

    private static void addEnvPath(List<String> out, String envVar, String relPath) {
        String base = System.getenv(envVar);
        if (base != null && !base.trim().isEmpty()) {
            out.add(new File(base.trim(), relPath).getAbsolutePath());
        }
    }

    private static String findInSystemPath(String exeName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) continue;
            File f = new File(dir.trim(), exeName);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String firstExistingFile(List<String> paths) {
        for (String p : paths) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(p);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}
