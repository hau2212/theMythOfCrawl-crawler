package com.mythofcrawl.crawler.proxy;

import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.model.MiniJson;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure java.base in-JVM HTTP/1.1 loopback control plane bound strictly to 127.0.0.1.
 * Burp's ProxyRequestHandler redirects same-origin requests targeting /__myth_crawl/* here,
 * so zero control traffic ever touches the target website and CORS/Mixed-Content/CSP never block Firefox.
 */
public final class LocalControlServer {

    private final CrawlConfig config;
    private final CrawlState state;
    private final byte[] agentJsBytes;
    private final Runnable onUiEvent;
    private final java.util.function.Consumer<String> logger;

    private ServerSocket serverSocket;
    private ExecutorService workers;
    private volatile boolean running = false;

    public LocalControlServer(
        CrawlConfig config,
        CrawlState state,
        Runnable onUiEvent,
        java.util.function.Consumer<String> logger
    ) throws IOException {
        this.config = config;
        this.state = state;
        this.onUiEvent = onUiEvent;
        this.logger = logger;
        this.agentJsBytes = loadAgentResource();
        this.serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MythCrawler-ControlWorker");
            t.setDaemon(true);
            return t;
        });
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    workers.submit(() -> handleConnection(sock));
                } catch (IOException ignored) {
                    if (!running) {
                        break;
                    }
                }
            }
        }, "MythCrawler-ControlAcceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private byte[] loadAgentResource() {
        try (InputStream in = LocalControlServer.class.getResourceAsStream("/crawler-agent.js")) {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (IOException ignored) {
        }
        return "/* missing crawler-agent.js */\n".getBytes(StandardCharsets.UTF_8);
    }

    private void handleConnection(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            int qIdx = rawPath.indexOf('?');
            String path = qIdx >= 0 ? rawPath.substring(0, qIdx) : rawPath;
            String query = qIdx >= 0 ? rawPath.substring(qIdx + 1) : "";

            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
                }
            }

            byte[] bodyBytes = new byte[0];
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                bodyBytes = readChunkedBody(in);
            } else if (headers.containsKey("content-length")) {
                try {
                    int len = Integer.parseInt(headers.get("content-length").trim());
                    if (len > 0 && len <= 2_000_000) {
                        bodyBytes = readExact(in, len);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

            dispatch(method, path, query, bodyStr, out);
        } catch (Exception ignored) {
        }
    }

    private void dispatch(String method, String path, String query, String bodyStr, OutputStream out)
        throws IOException {
        if ("/__myth_crawl/agent.js".equals(path)) {
            writeResponse(out, 200, "application/javascript; charset=utf-8", agentJsBytes);
            return;
        }

        if ("/__myth_crawl/state".equals(path)) {
            String pageUrl = extractQueryParam(query, "url");
            String routeKey = CrawlState.routeKeyForUrl(pageUrl, config.isNormalizeRouteIds());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", state.getStatus().name());
            resp.put("actionWaitMs", config.getActionWaitMs());
            resp.put("maxActionsPerPage", config.getMaxActionsPerPage());
            resp.put("autoFillForms", config.isAutoFillForms());
            resp.put("dangerGuardEnabled", config.isDangerGuardEnabled());
            resp.put("dangerKeywords", config.getDangerKeywordsList());
            resp.put("normalizeRouteIds", config.isNormalizeRouteIds());
            resp.put("clickedSignatures", state.getClickedSignatures(routeKey));
            resp.put("inFlight", state.getInFlightCount());
            resp.put("queueRemaining", state.getPendingQueueSize());
            resp.put("pagesVisited", state.getVisitedPagesCount());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/context".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String actionPath = MiniJson.asString(req, "actionPath", "/ > page load");
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            state.setCurrentContext(actionPath, pageUrl);
            if (logger != null && !"/ > page load".equals(actionPath)) {
                logger.accept("Context -> " + actionPath);
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("actionPath", actionPath);
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/done_action".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            String sig = MiniJson.asString(req, "signature", "");
            String routeKey = CrawlState.routeKeyForUrl(pageUrl, config.isNormalizeRouteIds());
            state.markActionClicked(routeKey, sig);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("inFlight", state.getInFlightCount());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/discovered".equals(path) && "POST".equals(method)) {
            Map<String, Object> req = MiniJson.parseObject(bodyStr);
            String pageUrl = MiniJson.asString(req, "pageUrl", "");
            if (!pageUrl.isEmpty()) {
                state.markUrlVisited(pageUrl);
            }
            List<String> urls = MiniJson.asStringList(req, "urls");
            int added = 0;
            for (String u : urls) {
                if (state.enqueueUrl(u, config)) {
                    added++;
                }
            }
            Object skippedObj = req.get("skipped");
            if (skippedObj instanceof List<?>) {
                for (Object item : (List<?>) skippedObj) {
                    if (item instanceof Map<?, ?>) {
                        Map<?, ?> m = (Map<?, ?>) item;
                        String ap = m.get("actionPath") != null ? String.valueOf(m.get("actionPath")) : "";
                        String reason = m.get("reason") != null ? String.valueOf(m.get("reason")) : "Danger Guard";
                        String tag = m.get("tag") != null ? String.valueOf(m.get("tag")) : "";
                        state.recordSkippedDanger(ap, reason, tag, pageUrl);
                    }
                }
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("enqueued", added);
            resp.put("queueRemaining", state.getPendingQueueSize());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/next_page".equals(path)) {
            String curUrl = extractQueryParam(query, "url");
            if (!curUrl.isEmpty()) {
                state.markUrlVisited(curUrl);
            }
            String nextUrl = state.getStatus() == CrawlState.Status.RUNNING
                ? state.pollNextUnvisitedUrl() : null;
            if (nextUrl == null && state.getStatus() == CrawlState.Status.RUNNING) {
                state.setStatus(CrawlState.Status.STOPPED);
                if (logger != null) {
                    logger.accept("Hoàn tất crawl hàng đợi! Đã duyệt " + state.getVisitedPagesCount() + " trang.");
                }
            }
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("nextUrl", nextUrl != null ? nextUrl : "");
            resp.put("done", nextUrl == null);
            resp.put("status", state.getStatus().name());
            resp.put("queueRemaining", state.getPendingQueueSize());
            writeJson(out, resp);
            return;
        }

        if ("/__myth_crawl/pause".equals(path) && "POST".equals(method)) {
            state.setStatus(CrawlState.Status.PAUSED);
            if (onUiEvent != null) {
                onUiEvent.run();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", state.getStatus().name());
            writeJson(out, resp);
            return;
        }

        writeJson(out, Map.of("ok", false, "error", "unknown control endpoint"));
    }

    private static void writeJson(OutputStream out, Object obj) throws IOException {
        writeResponse(out, 200, "application/json; charset=utf-8",
            MiniJson.stringify(obj).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, int code, String contentType, byte[] body)
        throws IOException {
        String header = "HTTP/1.1 " + code + " OK\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String extractQueryParam(String query, String targetKey) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (targetKey.equals(k)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    return v;
                }
            }
        }
        return "";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.mark(1);
                int next = in.read();
                if (next != '\n' && next != -1) {
                    in.reset();
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            bos.write(b);
            if (bos.size() > 32768) {
                break;
            }
        }
        if (b == -1 && bos.size() == 0) {
            return null;
        }
        return bos.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        if (off == len) {
            return buf;
        }
        byte[] trim = new byte[off];
        System.arraycopy(buf, 0, trim, 0, off);
        return trim;
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int size = Integer.parseInt(hex, 16);
            if (size <= 0) {
                readLine(in);
                break;
            }
            out.write(readExact(in, size));
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }
}
