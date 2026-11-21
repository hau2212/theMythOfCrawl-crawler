package com.mythofcrawl.crawler;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.organizer.Organizer;
import burp.api.montoya.proxy.MessageReceivedAction;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.utilities.CompressionType;
import burp.api.montoya.utilities.CompressionUtils;
import burp.api.montoya.utilities.Utilities;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Pure Java dynamic-proxy mock framework over {@code lib/montoya-api.jar} for headless
 * integration testing of Proxy handlers, HTTP handlers, compression, and UI components.
 */
public final class MockMontoyaSupport {

    private static final byte[] MOCK_BROTLI_MAGIC = new byte[] { 'M', 'O', 'C', 'K', '_', 'B', 'R', 0 };
    private static volatile boolean factoryInstalled = false;

    private MockMontoyaSupport() {
    }

    public static final class SentRecord {
        public final HttpRequest request;
        public final HttpMode mode;
        public final byte[] rawBytes;

        SentRecord(HttpRequest request, HttpMode mode) {
            this.request = request;
            this.mode = mode;
            this.rawBytes = request != null && request.toByteArray() != null
                ? request.toByteArray().getBytes()
                : new byte[0];
        }
    }

    public static final class MockEnv {
        public final AtomicInteger brotliDecompressCalls = new AtomicInteger(0);
        public final AtomicInteger copyToTempFileCalls = new AtomicInteger(0);
        public final List<SentRecord> sentRequests = new CopyOnWriteArrayList<>();
        public final List<HttpRequestResponse> organizerItems = new CopyOnWriteArrayList<>();
        public final List<HttpHandler> httpHandlers = new CopyOnWriteArrayList<>();
        public final List<String> capturedHandlerNotes = new CopyOnWriteArrayList<>();
        public volatile int nextSendStatus = 200;
        public volatile String nextSendBody = "{\"ok\":true}";
        public volatile Theme theme = Theme.LIGHT;
    }

    public static synchronized void installFactory() {
        if (factoryInstalled && ObjectFactoryLocator.FACTORY != null) {
            return;
        }
        ObjectFactoryLocator.FACTORY = (MontoyaObjectFactory) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { MontoyaObjectFactory.class },
            (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "httpService":
                        return handleHttpServiceFactory(args);
                    case "httpHeader":
                        return handleHttpHeaderFactory(args);
                    case "byteArray":
                        return handleByteArrayFactory(args);
                    case "byteArrayOfLength":
                        return wrapByteArray(new byte[((Integer) args[0])]);
                    case "annotations":
                        return handleAnnotationsFactory(args);
                    case "httpRequest":
                        return handleHttpRequestFactory(args);
                    case "http2Request":
                        return buildHttpRequestFromHeaders(
                            (HttpService) args[0],
                            "GET",
                            "/",
                            "HTTP/2",
                            asHeadersList(args[1]),
                            args[2] instanceof ByteArray ? ((ByteArray) args[2]).getBytes()
                                : String.valueOf(args[2]).getBytes(StandardCharsets.UTF_8));
                    case "httpRequestFromUrl":
                        return buildHttpRequestFromUrl(String.valueOf(args[0]));
                    case "httpResponse":
                        return handleHttpResponseFactory(args);
                    case "httpRequestResponse":
                        return wrapHttpRequestResponse(
                            (HttpRequest) args[0],
                            (HttpResponse) args[1],
                            args.length > 2 ? (Annotations) args[2] : wrapAnnotations("", HighlightColor.NONE),
                            null);
                    case "requestInitialInterceptResultFollowUserRules":
                        return wrapProxyReqAction(
                            MessageReceivedAction.CONTINUE,
                            (HttpRequest) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "requestInitialInterceptResultDoNotIntercept":
                        return wrapProxyReqAction(
                            MessageReceivedAction.DO_NOT_INTERCEPT,
                            (HttpRequest) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "requestInitialInterceptResultIntercept":
                        return wrapProxyReqAction(
                            MessageReceivedAction.INTERCEPT,
                            (HttpRequest) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "requestInitialInterceptResultDrop":
                        return wrapProxyReqAction(MessageReceivedAction.DROP, null, wrapAnnotations("", HighlightColor.NONE));
                    case "proxyRequestReceivedAction":
                        return wrapProxyReqAction((MessageReceivedAction) args[2], (HttpRequest) args[0], (Annotations) args[1]);
                    case "responseInitialInterceptResultFollowUserRules":
                        return wrapProxyRespAction(
                            MessageReceivedAction.CONTINUE,
                            (HttpResponse) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "responseInitialInterceptResultDoNotIntercept":
                        return wrapProxyRespAction(
                            MessageReceivedAction.DO_NOT_INTERCEPT,
                            (HttpResponse) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "responseInitialInterceptResultIntercept":
                        return wrapProxyRespAction(
                            MessageReceivedAction.INTERCEPT,
                            (HttpResponse) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "responseInitialInterceptResultDrop":
                        return wrapProxyRespAction(MessageReceivedAction.DROP, null, wrapAnnotations("", HighlightColor.NONE));
                    case "proxyResponseReceivedAction":
                        return wrapProxyRespAction((MessageReceivedAction) args[2], (HttpResponse) args[0], (Annotations) args[1]);
                    case "requestResult":
                        return wrapRequestToBeSentAction(
                            (HttpRequest) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    case "responseResult":
                        return wrapResponseReceivedAction(
                            (HttpResponse) args[0],
                            args.length > 1 ? (Annotations) args[1] : wrapAnnotations("", HighlightColor.NONE));
                    default:
                        return defaultReturn(method.getReturnType());
                }
            }
        );
        factoryInstalled = true;
    }

    public static byte[] mockBrotliCompress(byte[] plain) {
        byte[] out = new byte[MOCK_BROTLI_MAGIC.length + plain.length];
        System.arraycopy(MOCK_BROTLI_MAGIC, 0, out, 0, MOCK_BROTLI_MAGIC.length);
        System.arraycopy(plain, 0, out, MOCK_BROTLI_MAGIC.length, plain.length);
        return out;
    }

    public static byte[] gzipCompress(byte[] plain) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(plain);
        }
        return bos.toByteArray();
    }

    public static byte[] deflateCompress(byte[] plain) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
            dos.write(plain);
        }
        return bos.toByteArray();
    }

    public static MontoyaApi mockMontoyaApi(MockEnv env) {
        installFactory();
        MockEnv e = env != null ? env : new MockEnv();

        CompressionUtils compUtils = (CompressionUtils) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { CompressionUtils.class },
            (proxy, method, args) -> {
                String m = method.getName();
                if ("decompress".equals(m)) {
                    byte[] raw = ((ByteArray) args[0]).getBytes();
                    CompressionType ct = (CompressionType) args[1];
                    if (ct == CompressionType.BROTLI) {
                        e.brotliDecompressCalls.incrementAndGet();
                        if (raw.length >= MOCK_BROTLI_MAGIC.length) {
                            boolean match = true;
                            for (int i = 0; i < MOCK_BROTLI_MAGIC.length; i++) {
                                if (raw[i] != MOCK_BROTLI_MAGIC[i]) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                return wrapByteArray(Arrays.copyOfRange(raw, MOCK_BROTLI_MAGIC.length, raw.length));
                            }
                        }
                        return null;
                    } else if (ct == CompressionType.GZIP) {
                        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                            return wrapByteArray(gis.readAllBytes());
                        }
                    } else if (ct == CompressionType.DEFLATE) {
                        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(raw))) {
                            return wrapByteArray(iis.readAllBytes());
                        }
                    }
                } else if ("compress".equals(m)) {
                    byte[] raw = ((ByteArray) args[0]).getBytes();
                    CompressionType ct = (CompressionType) args[1];
                    if (ct == CompressionType.BROTLI) {
                        return wrapByteArray(mockBrotliCompress(raw));
                    } else if (ct == CompressionType.GZIP) {
                        return wrapByteArray(gzipCompress(raw));
                    } else if (ct == CompressionType.DEFLATE) {
                        return wrapByteArray(deflateCompress(raw));
                    }
                }
                return defaultReturn(method.getReturnType());
            });

        Utilities utilities = (Utilities) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { Utilities.class },
            (proxy, method, args) -> {
                if ("compressionUtils".equals(method.getName())) {
                    return compUtils;
                }
                return defaultReturn(method.getReturnType());
            });

        Http http = (Http) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { Http.class },
            (proxy, method, args) -> {
                if ("registerHttpHandler".equals(method.getName()) && args != null && args.length == 1) {
                    e.httpHandlers.add((HttpHandler) args[0]);
                    return null;
                }
                if ("sendRequest".equals(method.getName())) {
                    HttpRequest req = (HttpRequest) args[0];
                    HttpMode mode = args.length > 1 && args[1] instanceof HttpMode ? (HttpMode) args[1] : HttpMode.AUTO;
                    Annotations msgAnn = wrapAnnotations("", HighlightColor.NONE);
                    int msgId = 7000 + e.sentRequests.size();
                    for (HttpHandler hh : e.httpHandlers) {
                        RequestToBeSentAction rAct = hh.handleHttpRequestToBeSent(
                            mockToolRequestToBeSent(msgId, req, ToolType.EXTENSIONS));
                        if (rAct != null) {
                            if (rAct.request() != null) req = rAct.request();
                            if (rAct.annotations() != null && rAct.annotations().hasNotes()) {
                                msgAnn = rAct.annotations();
                            }
                        }
                    }
                    e.sentRequests.add(new SentRecord(req, mode));
                    HttpResponse resp = buildHttpResponse(
                        e.nextSendStatus,
                        Collections.singletonList(wrapHeader("Content-Type", "application/json")),
                        e.nextSendBody.getBytes(StandardCharsets.UTF_8));
                    for (HttpHandler hh : e.httpHandlers) {
                        ResponseReceivedAction pAct = hh.handleHttpResponseReceived(
                            mockToolResponseReceived(msgId, req, resp, ToolType.EXTENSIONS));
                        if (pAct != null) {
                            if (pAct.response() != null) resp = pAct.response();
                            if (pAct.annotations() != null && pAct.annotations().hasNotes()) {
                                msgAnn = pAct.annotations();
                            }
                        }
                    }
                    e.capturedHandlerNotes.add(msgAnn.notes());
                    return wrapHttpRequestResponse(req, resp, msgAnn, e);
                }
                return defaultReturn(method.getReturnType());
            });

        Organizer organizer = (Organizer) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { Organizer.class },
            (proxy, method, args) -> {
                if ("sendToOrganizer".equals(method.getName()) && args.length == 1) {
                    if (args[0] instanceof HttpRequestResponse) {
                        e.organizerItems.add((HttpRequestResponse) args[0]);
                    } else if (args[0] instanceof HttpRequest) {
                        e.organizerItems.add(wrapHttpRequestResponse((HttpRequest) args[0], null,
                            wrapAnnotations("", HighlightColor.NONE), e));
                    }
                    return null;
                }
                if ("items".equals(method.getName())) {
                    return new ArrayList<>(e.organizerItems);
                }
                return defaultReturn(method.getReturnType());
            });

        UserInterface ui = (UserInterface) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { UserInterface.class },
            (proxy, method, args) -> {
                if ("currentTheme".equals(method.getName())) {
                    return e.theme;
                }
                return defaultReturn(method.getReturnType());
            });

        burp.api.montoya.proxy.Proxy burpProxy = (burp.api.montoya.proxy.Proxy) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { burp.api.montoya.proxy.Proxy.class },
            (proxy, method, args) -> defaultReturn(method.getReturnType()));

        return (MontoyaApi) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "utilities": return utilities;
                    case "http": return http;
                    case "organizer": return organizer;
                    case "userInterface": return ui;
                    case "proxy": return burpProxy;
                    default: return defaultReturn(method.getReturnType());
                }
            });
    }

    public static InterceptedRequest mockInterceptedRequest(
        int messageId,
        HttpService svc,
        String method,
        String path,
        Map<String, String> headers,
        byte[] body
    ) {
        installFactory();
        List<HttpHeader> hdrList = toHeaderList(headers);
        HttpRequest delegate = buildHttpRequestFromHeaders(svc, method, path, "HTTP/1.1", hdrList, body);
        Annotations ann = wrapAnnotations("", HighlightColor.NONE);
        return (InterceptedRequest) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { InterceptedRequest.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                if ("messageId".equals(mn)) return messageId;
                if ("annotations".equals(mn)) return ann;
                if ("isInScope".equals(mn)) return true;
                if ("sourceIpAddress".equals(mn)) return null;
                if ("destinationIpAddress".equals(mn)) return null;
                return invokeDelegateMethod(delegate, m, args);
            });
    }

    public static InterceptedResponse mockInterceptedResponse(
        int messageId,
        HttpRequest initiatingRequest,
        int statusCode,
        Map<String, String> headers,
        byte[] body,
        MimeType statedMime,
        MimeType inferredMime,
        MockEnv env
    ) {
        installFactory();
        List<HttpHeader> hdrList = toHeaderList(headers);
        HttpResponse delegate = buildHttpResponse(statusCode, hdrList, body);
        Annotations ann = wrapAnnotations("", HighlightColor.NONE);
        return (InterceptedResponse) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { InterceptedResponse.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                if ("messageId".equals(mn)) return messageId;
                if ("initiatingRequest".equals(mn)) return initiatingRequest;
                if ("request".equals(mn)) return initiatingRequest;
                if ("annotations".equals(mn)) return ann;
                if ("statedMimeType".equals(mn)) return statedMime != null ? statedMime : MimeType.NONE;
                if ("inferredMimeType".equals(mn)) return inferredMime != null ? inferredMime : MimeType.NONE;
                if ("mimeType".equals(mn)) return statedMime != null ? statedMime : MimeType.NONE;
                if ("copyToTempFile".equals(mn)) {
                    if (env != null) env.copyToTempFileCalls.incrementAndGet();
                    return delegate;
                }
                return invokeDelegateMethod(delegate, m, args);
            });
    }

    public static HttpRequestToBeSent mockToolRequestToBeSent(int messageId, HttpRequest req, ToolType toolType) {
        installFactory();
        Annotations ann = wrapAnnotations("", HighlightColor.NONE);
        ToolSource ts = wrapToolSource(toolType);
        return (HttpRequestToBeSent) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpRequestToBeSent.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                if ("messageId".equals(mn)) return messageId;
                if ("annotations".equals(mn)) return ann;
                if ("toolSource".equals(mn)) return ts;
                return invokeDelegateMethod(req, m, args);
            });
    }

    public static HttpResponseReceived mockToolResponseReceived(
        int messageId,
        HttpRequest req,
        HttpResponse resp,
        ToolType toolType
    ) {
        installFactory();
        Annotations ann = wrapAnnotations("", HighlightColor.NONE);
        ToolSource ts = wrapToolSource(toolType);
        return (HttpResponseReceived) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpResponseReceived.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                if ("messageId".equals(mn)) return messageId;
                if ("initiatingRequest".equals(mn)) return req;
                if ("annotations".equals(mn)) return ann;
                if ("toolSource".equals(mn)) return ts;
                return invokeDelegateMethod(resp, m, args);
            });
    }

    // ---- Internal factory & proxy builders ----

    private static HttpService handleHttpServiceFactory(Object[] args) {
        if (args.length == 1) {
            String host = String.valueOf(args[0]);
            return wrapHttpService(host, 80, false);
        } else if (args.length == 2) {
            String host = String.valueOf(args[0]);
            boolean sec = (Boolean) args[1];
            return wrapHttpService(host, sec ? 443 : 80, sec);
        } else {
            return wrapHttpService(String.valueOf(args[0]), (Integer) args[1], (Boolean) args[2]);
        }
    }

    private static HttpHeader handleHttpHeaderFactory(Object[] args) {
        if (args.length == 1) {
            String raw = String.valueOf(args[0]);
            int colon = raw.indexOf(':');
            String n = colon >= 0 ? raw.substring(0, colon).trim() : raw.trim();
            String v = colon >= 0 ? raw.substring(colon + 1).trim() : "";
            return wrapHeader(n, v);
        }
        if (args[0] instanceof byte[]) {
            return wrapHeader(
                new String((byte[]) args[0], StandardCharsets.ISO_8859_1),
                new String((byte[]) args[1], StandardCharsets.ISO_8859_1));
        }
        return wrapHeader(String.valueOf(args[0]), String.valueOf(args[1]));
    }

    private static ByteArray handleByteArrayFactory(Object[] args) {
        if (args[0] instanceof byte[]) {
            return wrapByteArray((byte[]) args[0]);
        } else if (args[0] instanceof String) {
            return wrapByteArray(((String) args[0]).getBytes(StandardCharsets.UTF_8));
        } else if (args[0] instanceof int[]) {
            int[] ints = (int[]) args[0];
            byte[] b = new byte[ints.length];
            for (int i = 0; i < ints.length; i++) b[i] = (byte) ints[i];
            return wrapByteArray(b);
        }
        return wrapByteArray(new byte[0]);
    }

    private static Annotations handleAnnotationsFactory(Object[] args) {
        if (args == null || args.length == 0) {
            return wrapAnnotations("", HighlightColor.NONE);
        }
        if (args.length == 1) {
            if (args[0] instanceof HighlightColor) {
                return wrapAnnotations("", (HighlightColor) args[0]);
            }
            return wrapAnnotations(String.valueOf(args[0]), HighlightColor.NONE);
        }
        return wrapAnnotations(String.valueOf(args[0]), (HighlightColor) args[1]);
    }

    private static HttpRequest handleHttpRequestFactory(Object[] args) {
        if (args == null || args.length == 0) {
            return buildHttpRequestFromHeaders(wrapHttpService("localhost", 80, false), "GET", "/", "HTTP/1.1", Collections.emptyList(), new byte[0]);
        }
        if (args.length == 1) {
            byte[] raw = args[0] instanceof ByteArray ? ((ByteArray) args[0]).getBytes()
                : String.valueOf(args[0]).getBytes(StandardCharsets.UTF_8);
            return parseHttpRequest(wrapHttpService("localhost", 80, false), raw);
        }
        HttpService svc = (HttpService) args[0];
        byte[] raw = args[1] instanceof ByteArray ? ((ByteArray) args[1]).getBytes()
            : String.valueOf(args[1]).getBytes(StandardCharsets.UTF_8);
        return parseHttpRequest(svc, raw);
    }

    private static HttpResponse handleHttpResponseFactory(Object[] args) {
        if (args == null || args.length == 0) {
            return buildHttpResponse(200, Collections.emptyList(), new byte[0]);
        }
        byte[] raw = args[0] instanceof ByteArray ? ((ByteArray) args[0]).getBytes()
            : String.valueOf(args[0]).getBytes(StandardCharsets.UTF_8);
        return parseHttpResponse(raw);
    }

    private static HttpRequest buildHttpRequestFromUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            boolean sec = "https".equalsIgnoreCase(u.getScheme());
            int port = u.getPort() > 0 ? u.getPort() : (sec ? 443 : 80);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            return buildHttpRequestFromHeaders(wrapHttpService(u.getHost(), port, sec), "GET", path, "HTTP/1.1",
                Collections.singletonList(wrapHeader("Host", u.getHost())), new byte[0]);
        } catch (Exception ex) {
            return buildHttpRequestFromHeaders(wrapHttpService("localhost", 80, false), "GET", "/", "HTTP/1.1", Collections.emptyList(), new byte[0]);
        }
    }

    private static HttpRequest parseHttpRequest(HttpService svc, byte[] raw) {
        int split = findHeaderBodySplit(raw);
        String head = new String(raw, 0, split >= 0 ? split : raw.length, StandardCharsets.ISO_8859_1);
        byte[] body = split >= 0 ? Arrays.copyOfRange(raw, split + 4, raw.length) : new byte[0];
        String[] lines = head.split("\r\n|\n");
        String method = "GET";
        String path = "/";
        String ver = "HTTP/1.1";
        if (lines.length > 0 && !lines[0].isEmpty()) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 1) method = parts[0];
            if (parts.length >= 2) path = parts[1];
            if (parts.length >= 3) ver = parts[2];
        }
        List<HttpHeader> hdrs = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                hdrs.add(wrapHeader(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim()));
            }
        }
        return buildHttpRequestFromHeaders(svc, method, path, ver, hdrs, body);
    }

    private static HttpResponse parseHttpResponse(byte[] raw) {
        int split = findHeaderBodySplit(raw);
        String head = new String(raw, 0, split >= 0 ? split : raw.length, StandardCharsets.ISO_8859_1);
        byte[] body = split >= 0 ? Arrays.copyOfRange(raw, split + 4, raw.length) : new byte[0];
        String[] lines = head.split("\r\n|\n");
        int code = 200;
        if (lines.length > 0 && !lines[0].isEmpty()) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                try { code = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            }
        }
        List<HttpHeader> hdrs = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                hdrs.add(wrapHeader(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim()));
            }
        }
        return buildHttpResponse(code, hdrs, body);
    }

    private static int findHeaderBodySplit(byte[] raw) {
        for (int i = 0; i <= raw.length - 4; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static List<HttpHeader> toHeaderList(Map<String, String> headers) {
        List<HttpHeader> list = new ArrayList<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                list.add(wrapHeader(e.getKey(), e.getValue()));
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<HttpHeader> asHeadersList(Object obj) {
        return obj instanceof List<?> ? (List<HttpHeader>) obj : Collections.emptyList();
    }

    private static HttpService wrapHttpService(String host, int port, boolean secure) {
        return (HttpService) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpService.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "host": return host;
                    case "port": return port;
                    case "secure": return secure;
                    case "ipAddress": return "127.0.0.1";
                    case "toString": return (secure ? "https://" : "http://") + host + ":" + port;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static HttpHeader wrapHeader(String name, String value) {
        return (HttpHeader) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpHeader.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "name": return name;
                    case "value": return value;
                    case "toString": return name + ": " + value;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static ByteArray wrapByteArray(byte[] data) {
        byte[] copy = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
        return (ByteArray) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { ByteArray.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "length": return copy.length;
                    case "getBytes": return Arrays.copyOf(copy, copy.length);
                    case "getByte": return copy[(Integer) args[0]];
                    case "subArray":
                        if (args.length == 2) {
                            return wrapByteArray(Arrays.copyOfRange(copy, (Integer) args[0], (Integer) args[1]));
                        }
                        return copy;
                    case "toString": return new String(copy, StandardCharsets.UTF_8);
                    case "withAppended":
                        byte[] extra = args[0] instanceof byte[] ? (byte[]) args[0]
                            : args[0] instanceof ByteArray ? ((ByteArray) args[0]).getBytes()
                            : String.valueOf(args[0]).getBytes(StandardCharsets.UTF_8);
                        byte[] joined = Arrays.copyOf(copy, copy.length + extra.length);
                        System.arraycopy(extra, 0, joined, copy.length, extra.length);
                        return wrapByteArray(joined);
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static Annotations wrapAnnotations(String initialNotes, HighlightColor initialColor) {
        final String[] notes = new String[] { initialNotes != null ? initialNotes : "" };
        final HighlightColor[] color = new HighlightColor[] { initialColor != null ? initialColor : HighlightColor.NONE };
        return (Annotations) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { Annotations.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "notes": return notes[0];
                    case "hasNotes": return !notes[0].isEmpty();
                    case "setNotes": notes[0] = args[0] != null ? String.valueOf(args[0]) : ""; return null;
                    case "withNotes": return wrapAnnotations(args[0] != null ? String.valueOf(args[0]) : "", color[0]);
                    case "highlightColor": return color[0];
                    case "hasHighlightColor": return color[0] != HighlightColor.NONE;
                    case "setHighlightColor": color[0] = (HighlightColor) args[0]; return null;
                    case "withHighlightColor": return wrapAnnotations(notes[0], (HighlightColor) args[0]);
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static ToolSource wrapToolSource(ToolType type) {
        return (ToolSource) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { ToolSource.class },
            (proxy, m, args) -> {
                if ("toolType".equals(m.getName())) return type;
                if ("isFromTool".equals(m.getName())) {
                    ToolType[] arr = (ToolType[]) args[0];
                    for (ToolType t : arr) {
                        if (t == type) return true;
                    }
                    return false;
                }
                return defaultReturn(m.getReturnType());
            });
    }

    private static HttpRequest buildHttpRequestFromHeaders(
        HttpService svc,
        String method,
        String path,
        String httpVer,
        List<HttpHeader> headers,
        byte[] body
    ) {
        byte[] safeBody = body != null ? body : new byte[0];
        List<HttpHeader> safeHeaders = new ArrayList<>(headers != null ? headers : Collections.emptyList());
        return (HttpRequest) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpRequest.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                switch (mn) {
                    case "httpService": return svc;
                    case "method": return method;
                    case "path": return path;
                    case "pathWithoutQuery": {
                        int q = path.indexOf('?');
                        return q >= 0 ? path.substring(0, q) : path;
                    }
                    case "url": {
                        String scheme = svc.secure() ? "https" : "http";
                        boolean stdPort = (svc.secure() && svc.port() == 443) || (!svc.secure() && svc.port() == 80);
                        return scheme + "://" + svc.host() + (stdPort ? "" : ":" + svc.port()) + path;
                    }
                    case "httpVersion": return httpVer;
                    case "headers": return Collections.unmodifiableList(safeHeaders);
                    case "headerValue": return findHeaderValue(safeHeaders, String.valueOf(args[0]));
                    case "hasHeader": return findHeaderValue(safeHeaders, String.valueOf(args[0])) != null;
                    case "header": return findHeaderObj(safeHeaders, String.valueOf(args[0]));
                    case "body": return wrapByteArray(safeBody);
                    case "bodyToString": return new String(safeBody, StandardCharsets.UTF_8);
                    case "toByteArray": return wrapByteArray(serializeRequest(method, path, httpVer, safeHeaders, safeBody));
                    case "toString": return new String(serializeRequest(method, path, httpVer, safeHeaders, safeBody), StandardCharsets.UTF_8);
                    case "copyToTempFile": return proxy;
                    case "withService": return buildHttpRequestFromHeaders((HttpService) args[0], method, path, httpVer, safeHeaders, safeBody);
                    case "withPath": return buildHttpRequestFromHeaders(svc, method, String.valueOf(args[0]), httpVer, safeHeaders, safeBody);
                    case "withMethod": return buildHttpRequestFromHeaders(svc, String.valueOf(args[0]), path, httpVer, safeHeaders, safeBody);
                    case "withBody": {
                        byte[] nb = args[0] instanceof ByteArray ? ((ByteArray) args[0]).getBytes()
                            : String.valueOf(args[0]).getBytes(StandardCharsets.UTF_8);
                        return buildHttpRequestFromHeaders(svc, method, path, httpVer, safeHeaders, nb);
                    }
                    case "withUpdatedHeader": {
                        String hName = args.length == 2 ? String.valueOf(args[0]) : ((HttpHeader) args[0]).name();
                        String hVal = args.length == 2 ? String.valueOf(args[1]) : ((HttpHeader) args[0]).value();
                        List<HttpHeader> next = updateHeaderList(safeHeaders, hName, hVal);
                        return buildHttpRequestFromHeaders(svc, method, path, httpVer, next, safeBody);
                    }
                    case "withAddedHeader": {
                        String hName = args.length == 2 ? String.valueOf(args[0]) : ((HttpHeader) args[0]).name();
                        String hVal = args.length == 2 ? String.valueOf(args[1]) : ((HttpHeader) args[0]).value();
                        List<HttpHeader> next = new ArrayList<>(safeHeaders);
                        next.add(wrapHeader(hName, hVal));
                        return buildHttpRequestFromHeaders(svc, method, path, httpVer, next, safeBody);
                    }
                    case "withRemovedHeader": {
                        String hName = args[0] instanceof HttpHeader ? ((HttpHeader) args[0]).name() : String.valueOf(args[0]);
                        List<HttpHeader> next = removeHeaderList(safeHeaders, hName);
                        return buildHttpRequestFromHeaders(svc, method, path, httpVer, next, safeBody);
                    }
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static HttpResponse buildHttpResponse(int statusCode, List<HttpHeader> headers, byte[] body) {
        byte[] safeBody = body != null ? body : new byte[0];
        List<HttpHeader> safeHeaders = new ArrayList<>(headers != null ? headers : Collections.emptyList());
        return (HttpResponse) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpResponse.class },
            (proxy, m, args) -> {
                String mn = m.getName();
                switch (mn) {
                    case "statusCode": return (short) statusCode;
                    case "reasonPhrase": return statusCode == 200 ? "OK" : "HTTP";
                    case "httpVersion": return "HTTP/1.1";
                    case "headers": return Collections.unmodifiableList(safeHeaders);
                    case "headerValue": return findHeaderValue(safeHeaders, String.valueOf(args[0]));
                    case "hasHeader": return findHeaderValue(safeHeaders, String.valueOf(args[0])) != null;
                    case "header": return findHeaderObj(safeHeaders, String.valueOf(args[0]));
                    case "body": return wrapByteArray(safeBody);
                    case "bodyToString": return new String(safeBody, StandardCharsets.UTF_8);
                    case "toByteArray": return wrapByteArray(serializeResponse(statusCode, safeHeaders, safeBody));
                    case "toString": return new String(serializeResponse(statusCode, safeHeaders, safeBody), StandardCharsets.UTF_8);
                    case "copyToTempFile": return proxy;
                    case "withStatusCode": return buildHttpResponse(((Short) args[0]).intValue(), safeHeaders, safeBody);
                    case "withBody": {
                        byte[] nb = args[0] instanceof ByteArray ? ((ByteArray) args[0]).getBytes()
                            : String.valueOf(args[0]).getBytes(StandardCharsets.UTF_8);
                        return buildHttpResponse(statusCode, safeHeaders, nb);
                    }
                    case "withAddedHeader": {
                        String hName = args.length == 2 ? String.valueOf(args[0]) : ((HttpHeader) args[0]).name();
                        String hVal = args.length == 2 ? String.valueOf(args[1]) : ((HttpHeader) args[0]).value();
                        List<HttpHeader> next = new ArrayList<>(safeHeaders);
                        next.add(wrapHeader(hName, hVal));
                        return buildHttpResponse(statusCode, next, safeBody);
                    }
                    case "withUpdatedHeader": {
                        String hName = args.length == 2 ? String.valueOf(args[0]) : ((HttpHeader) args[0]).name();
                        String hVal = args.length == 2 ? String.valueOf(args[1]) : ((HttpHeader) args[0]).value();
                        return buildHttpResponse(statusCode, updateHeaderList(safeHeaders, hName, hVal), safeBody);
                    }
                    case "withRemovedHeader": {
                        String hName = args[0] instanceof HttpHeader ? ((HttpHeader) args[0]).name() : String.valueOf(args[0]);
                        return buildHttpResponse(statusCode, removeHeaderList(safeHeaders, hName), safeBody);
                    }
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static HttpRequestResponse wrapHttpRequestResponse(
        HttpRequest req,
        HttpResponse resp,
        Annotations ann,
        MockEnv env
    ) {
        return (HttpRequestResponse) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { HttpRequestResponse.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "request": return req;
                    case "response": return resp;
                    case "hasResponse": return resp != null;
                    case "httpService": return req != null ? req.httpService() : null;
                    case "url": return req != null ? req.url() : "";
                    case "statusCode": return resp != null ? resp.statusCode() : (short) 0;
                    case "annotations": return ann;
                    case "withAnnotations": return wrapHttpRequestResponse(req, resp, (Annotations) args[0], env);
                    case "copyToTempFile":
                        if (env != null) env.copyToTempFileCalls.incrementAndGet();
                        return proxy;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static ProxyRequestReceivedAction wrapProxyReqAction(MessageReceivedAction act, HttpRequest req, Annotations ann) {
        return (ProxyRequestReceivedAction) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { ProxyRequestReceivedAction.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "action": return act;
                    case "request": return req;
                    case "annotations": return ann;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static ProxyResponseReceivedAction wrapProxyRespAction(MessageReceivedAction act, HttpResponse resp, Annotations ann) {
        return (ProxyResponseReceivedAction) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { ProxyResponseReceivedAction.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "action": return act;
                    case "response": return resp;
                    case "annotations": return ann;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static RequestToBeSentAction wrapRequestToBeSentAction(HttpRequest req, Annotations ann) {
        return (RequestToBeSentAction) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { RequestToBeSentAction.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "request": return req;
                    case "annotations": return ann;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static ResponseReceivedAction wrapResponseReceivedAction(HttpResponse resp, Annotations ann) {
        return (ResponseReceivedAction) Proxy.newProxyInstance(
            MockMontoyaSupport.class.getClassLoader(),
            new Class<?>[] { ResponseReceivedAction.class },
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "response": return resp;
                    case "annotations": return ann;
                    default: return defaultReturn(m.getReturnType());
                }
            });
    }

    private static Object invokeDelegateMethod(Object delegate, java.lang.reflect.Method m, Object[] args) throws Exception {
        java.lang.reflect.Method target = delegate.getClass().getMethod(m.getName(), m.getParameterTypes());
        return target.invoke(delegate, args);
    }

    private static String findHeaderValue(List<HttpHeader> hdrs, String name) {
        for (HttpHeader h : hdrs) {
            if (h.name().equalsIgnoreCase(name)) {
                return h.value();
            }
        }
        return null;
    }

    private static HttpHeader findHeaderObj(List<HttpHeader> hdrs, String name) {
        for (HttpHeader h : hdrs) {
            if (h.name().equalsIgnoreCase(name)) {
                return h;
            }
        }
        return null;
    }

    private static List<HttpHeader> updateHeaderList(List<HttpHeader> hdrs, String name, String val) {
        List<HttpHeader> next = new ArrayList<>();
        boolean replaced = false;
        for (HttpHeader h : hdrs) {
            if (h.name().equalsIgnoreCase(name)) {
                if (!replaced) {
                    next.add(wrapHeader(name, val));
                    replaced = true;
                }
            } else {
                next.add(h);
            }
        }
        if (!replaced) {
            next.add(wrapHeader(name, val));
        }
        return next;
    }

    private static List<HttpHeader> removeHeaderList(List<HttpHeader> hdrs, String name) {
        List<HttpHeader> next = new ArrayList<>();
        for (HttpHeader h : hdrs) {
            if (!h.name().equalsIgnoreCase(name)) {
                next.add(h);
            }
        }
        return next;
    }

    private static byte[] serializeRequest(String method, String path, String ver, List<HttpHeader> hdrs, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(' ').append(ver).append("\r\n");
        for (HttpHeader h : hdrs) {
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] full = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, full, head.length, body.length);
        return full;
    }

    private static byte[] serializeResponse(int statusCode, List<HttpHeader> hdrs, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" OK\r\n");
        for (HttpHeader h : hdrs) {
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] full = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, full, head.length, body.length);
        return full;
    }

    private static Object defaultReturn(Class<?> rt) {
        if (rt == void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == String.class) return "";
        if (rt == List.class) return Collections.emptyList();
        if (rt == Map.class) return Collections.emptyMap();
        return null;
    }
}
