package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches live HTTP requests (Method + relative Path API) against the loaded Excel API checklist.
 * Supports static paths (/api/a/b/c), template parameters ({id}, :id, <id>, *),
 * trailing slash normalization, and optional API base prefix stripping.
 */
public final class ExcelSpecMatcher {

    public static final class MatchResult {
        public final ApiSpecEntry entry;
        public final ApiSpecEntry.RecordOutcome outcome;

        public MatchResult(ApiSpecEntry entry, ApiSpecEntry.RecordOutcome outcome) {
            this.entry = entry;
            this.outcome = outcome;
        }
    }

    public static final class CoverageStats {
        public final int total;
        public final int matched;
        public final int missing;
        public final int withNotes;

        public CoverageStats(int total, int matched, int missing, int withNotes) {
            this.total = total;
            this.matched = matched;
            this.missing = missing;
            this.withNotes = withNotes;
        }
    }

    private final List<ApiSpecEntry> entries;
    private final Map<String, List<ApiSpecEntry>> exactIndex; // normalized exact path -> entries
    private final List<ApiSpecEntry> wildcardEntries;         // sorted by literalSegmentCount desc
    private final String sourceFileName;

    public ExcelSpecMatcher(String sourceFileName, List<ApiSpecEntry> entries) {
        this.sourceFileName = sourceFileName != null ? sourceFileName : "";
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        Map<String, List<ApiSpecEntry>> exact = new LinkedHashMap<>();
        List<ApiSpecEntry> wild = new ArrayList<>();
        for (ApiSpecEntry e : entries) {
            if (e.hasWildcards) {
                wild.add(e);
            } else {
                exact.computeIfAbsent(e.normalizedSpecPath, k -> new ArrayList<>()).add(e);
            }
        }
        wild.sort(Comparator.comparingInt((ApiSpecEntry e) -> e.literalSegmentCount).reversed());
        this.exactIndex = Collections.unmodifiableMap(exact);
        this.wildcardEntries = Collections.unmodifiableList(wild);
    }

    public static ExcelSpecMatcher empty() {
        return new ExcelSpecMatcher("", Collections.emptyList());
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public boolean isLoaded() {
        return !entries.isEmpty();
    }

    public List<ApiSpecEntry> getEntries() {
        return entries;
    }

    public CoverageStats getCoverageStats() {
        int total = entries.size();
        int matched = 0;
        int notes = 0;
        for (ApiSpecEntry e : entries) {
            if (e.isMatched()) {
                matched++;
            }
            if (!e.getParamNotes().isEmpty()) {
                notes++;
            }
        }
        return new CoverageStats(total, matched, Math.max(0, total - matched), notes);
    }

    public void clearRuntimeCaptures() {
        for (ApiSpecEntry e : entries) {
            e.clearRuntimeCaptures();
        }
    }

    public static ExcelSpecMatcher buildFromRows(
        String sourceFileName,
        List<List<String>> rows,
        int headerRowIdx,
        int sttCol,
        int methodCol,
        int pathCol,
        boolean ignoreTrailingSlash
    ) {
        List<ApiSpecEntry> parsed = new ArrayList<>();
        if (rows == null) {
            return new ExcelSpecMatcher(sourceFileName, parsed);
        }
        int autoStt = 1;
        for (int r = headerRowIdx + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String rawPath = getCell(row, pathCol);
            String rawMethod = getCell(row, methodCol);
            String rawStt = sttCol >= 0 ? getCell(row, sttCol) : "";

            if (rawPath.isEmpty()) {
                continue;
            }
            if (rawStt.isEmpty()) {
                rawStt = String.valueOf(autoStt);
            }
            autoStt++;

            Set<String> methods = parseMethods(rawMethod);
            String displayMethod = rawMethod.isEmpty() ? "GET" : rawMethod.trim().toUpperCase();

            String normPath = normalizeSpecPath(rawPath, ignoreTrailingSlash);
            if (normPath.isEmpty()) {
                continue;
            }
            CompiledPath compiled = compileTemplate(normPath, ignoreTrailingSlash);

            // Capture remaining columns cleanly for user context
            List<String> header = (headerRowIdx >= 0 && headerRowIdx < rows.size())
                ? rows.get(headerRowIdx) : Collections.emptyList();
            List<String> extras = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                if (c == sttCol || c == methodCol || c == pathCol) {
                    continue;
                }
                String v = getCell(row, c);
                if (!v.isEmpty()) {
                    String h = c < header.size() ? getCell(header, c) : ("Col" + (c + 1));
                    extras.add((h.isEmpty() ? ("Col" + (c + 1)) : h) + ": " + v);
                }
            }
            String extraSummary = String.join(" | ", extras);
            parsed.add(new ApiSpecEntry(
                rawStt, displayMethod, methods, rawPath.trim(), normPath,
                extraSummary, compiled.hasWildcards, compiled.literalCount, compiled.pattern));
        }
        return new ExcelSpecMatcher(sourceFileName, parsed);
    }

    private static String getCell(List<String> row, int col) {
        if (row == null || col < 0 || col >= row.size()) {
            return "";
        }
        String v = row.get(col);
        return v != null ? v.trim() : "";
    }

    public static Set<String> parseMethods(String rawMethod) {
        if (rawMethod == null || rawMethod.trim().isEmpty()) {
            return Collections.singleton("GET");
        }
        String upper = rawMethod.trim().toUpperCase();
        if (upper.equals("*") || upper.equals("ANY") || upper.equals("ALL")) {
            return Collections.singleton("*");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String tok : upper.split("[,/;|\\s]+")) {
            String t = tok.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? Collections.singleton("GET") : out;
    }

    public static String normalizeSpecPath(String rawPath, boolean ignoreTrailingSlash) {
        if (rawPath == null) {
            return "";
        }
        String p = rawPath.trim();
        if (p.isEmpty()) {
            return "";
        }
        // Strip scheme/host if a row accidentally contains full URL
        if (p.startsWith("http://") || p.startsWith("https://")) {
            try {
                URI uri = URI.create(p);
                p = uri.getRawPath();
            } catch (Exception ignored) {
                int idx = p.indexOf('/', 8);
                p = idx >= 0 ? p.substring(idx) : "/";
            }
        }
        if (p == null) {
            p = "/";
        }
        int hash = p.indexOf('#');
        if (hash >= 0) {
            p = p.substring(0, hash);
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        p = p.trim();
        if (p.isEmpty()) {
            p = "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        p = p.replaceAll("/{2,}", "/");
        if (ignoreTrailingSlash && p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static final class CompiledPath {
        final boolean hasWildcards;
        final int literalCount;
        final Pattern pattern;

        CompiledPath(boolean hasWildcards, int literalCount, Pattern pattern) {
            this.hasWildcards = hasWildcards;
            this.literalCount = literalCount;
            this.pattern = pattern;
        }
    }

    private static CompiledPath compileTemplate(String normPath, boolean ignoreTrailingSlash) {
        if ("/".equals(normPath)) {
            return new CompiledPath(false, 0, Pattern.compile("^/$"));
        }
        String[] segments = normPath.substring(1).split("/");
        boolean wild = false;
        int literals = 0;
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals("**") || seg.equals("{**}") || seg.startsWith("{*")) {
                wild = true;
                regex.append("(?:/.*)?");
            } else if ((seg.startsWith("{") && seg.endsWith("}"))
                || seg.startsWith(":")
                || (seg.startsWith("<") && seg.endsWith(">"))
                || seg.equals("*")) {
                wild = true;
                regex.append("/[^/]+");
            } else if (seg.contains("*")) {
                wild = true;
                regex.append("/");
                String[] parts = seg.split("\\*", -1);
                for (int j = 0; j < parts.length; j++) {
                    if (j > 0) {
                        regex.append("[^/]*");
                    }
                    if (!parts[j].isEmpty()) {
                        regex.append(Pattern.quote(parts[j]));
                    }
                }
            } else {
                literals++;
                regex.append("/").append(Pattern.quote(seg));
            }
        }
        if (ignoreTrailingSlash) {
            regex.append("/?");
        }
        regex.append("$");
        return new CompiledPath(wild, literals, Pattern.compile(regex.toString()));
    }

    public ApiSpecEntry findMatch(String method, String requestPathAndQuery, CrawlConfig config) {
        if (entries.isEmpty()) {
            return null;
        }
        String mUpper = method != null ? method.trim().toUpperCase() : "GET";
        boolean stripSlash = config == null || config.isIgnoreTrailingSlash();
        String normReqPath = normalizeSpecPath(requestPathAndQuery, stripSlash);

        List<String> candidates = new ArrayList<>();
        candidates.add(normReqPath);
        if (config != null && config.getApiBasePrefix() != null && !config.getApiBasePrefix().isEmpty()) {
            String pref = normalizeSpecPath(config.getApiBasePrefix(), true);
            if (!"/".equals(pref) && normReqPath.startsWith(pref)) {
                String stripped = normReqPath.substring(pref.length());
                if (stripped.isEmpty()) {
                    stripped = "/";
                } else if (!stripped.startsWith("/")) {
                    stripped = "/" + stripped;
                }
                candidates.add(stripped);
            }
        }

        // 1. Exact path match first
        for (String cand : candidates) {
            List<ApiSpecEntry> exactList = exactIndex.get(cand);
            if (exactList != null) {
                for (ApiSpecEntry entry : exactList) {
                    if (methodMatches(entry, mUpper)) {
                        return entry;
                    }
                }
            }
        }

        // 2. Parameterized template match ({id}, :id, *) sorted by specificity
        for (String cand : candidates) {
            for (ApiSpecEntry entry : wildcardEntries) {
                if (methodMatches(entry, mUpper) && entry.pathRegex.matcher(cand).matches()) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static boolean methodMatches(ApiSpecEntry entry, String reqMethodUpper) {
        return entry.allowedMethods.contains("*") || entry.allowedMethods.contains(reqMethodUpper);
    }

    public MatchResult matchAndRecord(
        String method,
        String fullUrl,
        String requestPathWithQuery,
        String host,
        int port,
        boolean https,
        String actionPath,
        String contentType,
        byte[] requestBytes,
        byte[] requestBodyBytes,
        byte[] responseBytes,
        int statusCode,
        int responseLength,
        CrawlConfig config
    ) {
        ApiSpecEntry entry = findMatch(method, requestPathWithQuery, config);
        if (entry == null) {
            return null;
        }
        Set<String> params = ParamExtractor.extractParameterKeys(requestPathWithQuery, contentType, requestBodyBytes);
        CapturedApiEntry capture = new CapturedApiEntry(
            entry.stt,
            method != null ? method.trim().toUpperCase() : "GET",
            fullUrl,
            requestPathWithQuery,
            host,
            port,
            https,
            actionPath,
            requestBytes,
            responseBytes,
            statusCode,
            responseLength,
            params,
            System.currentTimeMillis()
        );
        ApiSpecEntry.RecordOutcome outcome = entry.recordCapture(capture);
        return new MatchResult(entry, outcome);
    }

    public void attachJsHintIfMentioned(String jsContent, String jsUrl) {
        if (jsContent == null || jsContent.isEmpty() || entries.isEmpty()) {
            return;
        }
        for (ApiSpecEntry entry : entries) {
            String needle = entry.normalizedSpecPath;
            if (needle.equals("/") || needle.length() < 3) {
                continue;
            }
            // For parameterized paths like /api/users/{id}, check the longest literal prefix "/api/users"
            int brace = needle.indexOf('{');
            int colon = needle.indexOf(':');
            int star = needle.indexOf('*');
            int cut = needle.length();
            if (brace > 0) cut = Math.min(cut, brace);
            if (colon > 0) cut = Math.min(cut, colon);
            if (star > 0) cut = Math.min(cut, star);
            String prefix = needle.substring(0, cut);
            if (prefix.endsWith("/") && prefix.length() > 1) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (prefix.length() >= 4 && jsContent.contains(prefix)) {
                entry.addJsHint(jsUrl);
            }
        }
    }
}
