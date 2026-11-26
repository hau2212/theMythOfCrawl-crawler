package com.mythofcrawl.crawler.excel;

import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.PathNamer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches live HTTP requests (Method + Host + Path + Query + GraphQL op) against the loaded
 * Excel API checklist.
 *
 * Parity & performance features:
 * - Strips `;matrix` parameters (e.g. `;jsessionid=...`) and decodes `%XX` preserving `%2F`.
 * - Exact index + case-insensitive fallback + first-segment bucketed wildcard lookup.
 * - Optional host, query parameter, GraphQL operationName, and `{id}`-in-spec matching.
 * - Built-in Aho-Corasick multi-pattern matcher for O(N) JS bundle hint scanning.
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

    private static final class SpecMeta {
        final String host;             // empty if unspecified
        final String graphqlOp;        // empty if unspecified
        final List<String[]> queryPairs; // [name, valOrNull]
        final Pattern caseInsensitiveRegex;
        final Pattern idRelaxedRegex;
        final int order;

        SpecMeta(
            String host,
            String graphqlOp,
            List<String[]> queryPairs,
            Pattern caseInsensitiveRegex,
            Pattern idRelaxedRegex,
            int order
        ) {
            this.host = host;
            this.graphqlOp = graphqlOp;
            this.queryPairs = queryPairs;
            this.caseInsensitiveRegex = caseInsensitiveRegex;
            this.idRelaxedRegex = idRelaxedRegex;
            this.order = order;
        }
    }

    private static final class CustomPlaceholderRule {
        final String templatePath;
        final int literalCount;
        final Pattern matchPattern;

        CustomPlaceholderRule(String templatePath, int literalCount, Pattern matchPattern) {
            this.templatePath = templatePath;
            this.literalCount = literalCount;
            this.matchPattern = matchPattern;
        }
    }

    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile("\\{[^}]+\\}|^<[^>]+>$|^:");
    private static final Pattern OP_BRACKET = Pattern.compile("(?i)\\[op\\s*:\\s*([^\\]]+)\\]");

    private volatile List<ApiSpecEntry> entries;
    private volatile Map<String, List<ApiSpecEntry>> exactIndex;       // exact normalized path -> entries
    private volatile Map<String, List<ApiSpecEntry>> exactLowerIndex;  // lowercase normalized path -> entries
    private volatile Map<String, List<ApiSpecEntry>> wildcardBuckets;  // first static segment (lowercase) or "*" -> entries
    private volatile List<ApiSpecEntry> wildcardEntries;               // all wildcards sorted by literalSegmentCount desc
    private volatile Map<ApiSpecEntry, SpecMeta> metaByEntry;
    private volatile AhoCorasickMatcher jsPrefixMatcher;
    private final List<CustomPlaceholderRule> customPlaceholderRules = new ArrayList<>();
    private final Object indexLock = new Object();
    private final String sourceFileName;

    public ExcelSpecMatcher(String sourceFileName, List<ApiSpecEntry> entries) {
        this.sourceFileName = sourceFileName != null ? sourceFileName : "";
        rebuildIndexes(entries != null ? entries : Collections.emptyList());
    }

    private void rebuildIndexes(List<ApiSpecEntry> sourceEntries) {
        synchronized (indexLock) {
            List<ApiSpecEntry> copy = Collections.unmodifiableList(new ArrayList<>(sourceEntries));
            Map<String, List<ApiSpecEntry>> exact = new LinkedHashMap<>();
            Map<String, List<ApiSpecEntry>> exactLower = new LinkedHashMap<>();
            Map<String, List<ApiSpecEntry>> buckets = new HashMap<>();
            List<ApiSpecEntry> wild = new ArrayList<>();
            IdentityHashMap<ApiSpecEntry, SpecMeta> metaMap = new IdentityHashMap<>();

            int order = 0;
            for (ApiSpecEntry e : copy) {
                SpecMeta meta = buildSpecMeta(e, order++);
                metaMap.put(e, meta);
                if (e.hasWildcards) {
                    wild.add(e);
                } else {
                    exact.computeIfAbsent(e.normalizedSpecPath, k -> new ArrayList<>()).add(e);
                    exactLower.computeIfAbsent(e.normalizedSpecPath.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
                }
            }
            Comparator<ApiSpecEntry> wildOrder = Comparator
                .comparingInt((ApiSpecEntry e) -> e.literalSegmentCount).reversed()
                .thenComparingInt(e -> metaMap.get(e).order);
            wild.sort(wildOrder);

            for (ApiSpecEntry e : wild) {
                String bucketKey = firstStaticSegmentLower(e.normalizedSpecPath);
                buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(e);
            }

            this.entries = copy;
            this.exactIndex = Collections.unmodifiableMap(exact);
            this.exactLowerIndex = Collections.unmodifiableMap(exactLower);
            this.wildcardBuckets = Collections.unmodifiableMap(buckets);
            this.wildcardEntries = Collections.unmodifiableList(wild);
            this.metaByEntry = Collections.unmodifiableMap(metaMap);
            this.jsPrefixMatcher = buildJsPrefixMatcher(copy);
        }
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

    /**
     * Computes lowercase route segment hints (e.g. {@code "/orders"}, {@code "/invoices"})
     * from currently unmatched Excel API entries so the priority frontier visits screens
     * most likely to trigger missing checklist endpoints first.
     */
    public Set<String> computeMissingRouteHints() {
        Set<String> hints = new LinkedHashSet<>();
        for (ApiSpecEntry e : entries) {
            if (e.isMatched()) {
                continue;
            }
            String p = e.normalizedSpecPath;
            if (p == null || p.length() <= 1) {
                continue;
            }
            for (String seg : p.substring(1).split("/")) {
                String clean = seg.trim().toLowerCase(Locale.ROOT);
                if (clean.length() < 3 || clean.startsWith("{") || clean.startsWith(":")
                    || clean.contains("*") || "api".equals(clean) || "rest".equals(clean)
                    || "graphql".equals(clean) || clean.matches("^v\\d+$")
                    || PathNamer.isDynamicIdSegment(clean)) {
                    continue;
                }
                hints.add("/" + clean);
                if (hints.size() >= 128) {
                    return hints;
                }
                break; // first meaningful domain resource segment per unmatched API
            }
        }
        return hints;
    }

    /**
     * Replaces the 0-based path segment at {@code segmentIndex} with {@code {placeholderName}}
     * (for example: {@code replaceSegmentWithPlaceholder("/api/a/sess_91/c", 2, "session")} -> {@code "/api/a/{session}/c"}).
     */
    public static String replaceSegmentWithPlaceholder(String path, int segmentIndex, String placeholderName) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int qIdx = path.indexOf('?');
        String rawPathPart = qIdx >= 0 ? path.substring(0, qIdx) : path;
        String queryPart = qIdx >= 0 ? path.substring(qIdx) : "";
        String norm = normalizeSpecPath(rawPathPart, true);
        if ("/".equals(norm) || segmentIndex < 0) {
            return norm + queryPart;
        }
        String[] segs = norm.substring(1).split("/");
        if (segmentIndex >= segs.length) {
            return norm + queryPart;
        }
        String cleanName = placeholderName == null ? "" : placeholderName.trim();
        if (cleanName.startsWith("{") && cleanName.endsWith("}") && cleanName.length() > 2) {
            cleanName = cleanName.substring(1, cleanName.length() - 1).trim();
        }
        cleanName = cleanName.replaceAll("[/\\s{}]+", "_");
        if (cleanName.isEmpty()) {
            cleanName = "param";
        }
        segs[segmentIndex] = "{" + cleanName + "}";
        return "/" + String.join("/", segs) + queryPart;
    }

    /**
     * Registers a custom placeholder template (e.g. {@code /api/a/{session}/c}) for path normalization
     * without requiring a full Excel row when only normalization rules are needed.
     */
    public void registerCustomPlaceholderTemplate(String templatePath) {
        if (templatePath == null || templatePath.isBlank()) {
            return;
        }
        String norm = normalizeSpecPath(templatePath, true);
        if (norm.isEmpty()) {
            return;
        }
        CompiledPath compiled = compileTemplate(norm, true, false, false);
        synchronized (indexLock) {
            for (CustomPlaceholderRule existing : customPlaceholderRules) {
                if (existing.templatePath.equals(norm)) {
                    return;
                }
            }
            customPlaceholderRules.add(new CustomPlaceholderRule(norm, compiled.literalCount, compiled.pattern));
            customPlaceholderRules.sort((a, b) -> Integer.compare(b.literalCount, a.literalCount));
        }
    }

    /**
     * Dynamically adds or updates a custom {@code {placeholder}} API spec entry (e.g. {@code /v1/{tenant}/orders/{orderId}})
     * and rebuilds matcher indexes cleanly in a thread-safe manner.
     */
    public ApiSpecEntry addCustomPlaceholderSpec(String stt, String method, String templatePath, String note) {
        if (templatePath == null || templatePath.isBlank()) {
            throw new IllegalArgumentException("templatePath must not be blank");
        }
        String normTemplate = normalizeSpecPath(templatePath, true);
        Set<String> methods = parseMethods(method);
        String displayMethod = (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        CompiledPath compiled = compileTemplate(normTemplate, true, false, false);

        registerCustomPlaceholderTemplate(normTemplate);

        synchronized (indexLock) {
            for (ApiSpecEntry existing : entries) {
                if (existing.normalizedSpecPath.equals(normTemplate)
                    && existing.displayMethod.equalsIgnoreCase(displayMethod)) {
                    return existing;
                }
            }
            String effectiveStt = (stt == null || stt.isBlank())
                ? "CUSTOM-" + (entries.size() + 1)
                : stt.trim();
            ApiSpecEntry created = new ApiSpecEntry(
                effectiveStt,
                displayMethod,
                methods,
                templatePath.trim(),
                normTemplate,
                note != null ? note.trim() : "Custom {placeholder} rule",
                compiled.hasWildcards,
                compiled.literalCount,
                compiled.pattern
            );
            List<ApiSpecEntry> next = new ArrayList<>(entries);
            next.add(created);
            rebuildIndexes(next);
            return created;
        }
    }

    public ApiSpecEntry addCustomPlaceholderSpec(String method, String templatePath) {
        return addCustomPlaceholderSpec("", method, templatePath, "Custom {placeholder} rule");
    }

    /**
     * Normalizes an incoming concrete path using user-managed custom {@code {placeholder}} templates first
     * (and wildcard {@code {placeholder}} spec entries in this matcher), preserving query string if present.
     */
    public String normalizeWithCustomPlaceholders(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int qIdx = path.indexOf('?');
        String rawPathPart = qIdx >= 0 ? path.substring(0, qIdx) : path;
        String queryPart = qIdx >= 0 ? path.substring(qIdx) : "";
        String norm = normalizeSpecPath(rawPathPart, true);

        synchronized (indexLock) {
            for (CustomPlaceholderRule rule : customPlaceholderRules) {
                if (rule.matchPattern.matcher(norm).matches()) {
                    return rule.templatePath + queryPart;
                }
            }
        }
        for (ApiSpecEntry e : wildcardEntries) {
            if (e.normalizedSpecPath.contains("{") && e.normalizedSpecPath.contains("}")
                && e.pathRegex.matcher(norm).matches()) {
                return e.normalizedSpecPath + queryPart;
            }
        }
        return norm + queryPart;
    }

    public List<String> getCustomPlaceholderTemplates() {
        synchronized (indexLock) {
            List<String> out = new ArrayList<>(customPlaceholderRules.size());
            for (CustomPlaceholderRule r : customPlaceholderRules) {
                out.add(r.templatePath);
            }
            return Collections.unmodifiableList(out);
        }
    }

    public void clearCustomPlaceholderTemplates() {
        synchronized (indexLock) {
            customPlaceholderRules.clear();
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
            String displayMethod = rawMethod.isEmpty() ? "GET" : rawMethod.trim().toUpperCase(Locale.ROOT);

            String normPath = normalizeSpecPath(rawPath, ignoreTrailingSlash);
            if (normPath.isEmpty()) {
                continue;
            }
            CompiledPath compiled = compileTemplate(normPath, ignoreTrailingSlash, false, false);

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
        String upper = rawMethod.trim().toUpperCase(Locale.ROOT);
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
        // Strip [op:...] annotation if present in spec cell
        p = OP_BRACKET.matcher(p).replaceAll("").trim();

        // Strip scheme/host if a row contains a full URL
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
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
        p = decodePercentPreservingSlash(p.trim());
        if (p.isEmpty()) {
            p = "/";
        }
        // Strip ;matrix parameters per path segment (e.g. ;jsessionid=xyz)
        StringBuilder clean = new StringBuilder();
        for (String seg : p.split("/")) {
            int semi = seg.indexOf(';');
            String s = semi >= 0 ? seg.substring(0, semi) : seg;
            if (!s.isEmpty()) {
                clean.append('/').append(s);
            }
        }
        String out = clean.length() == 0 ? "/" : clean.toString();
        if (!ignoreTrailingSlash && p.endsWith("/") && out.length() > 1) {
            out = out + "/";
        }
        return out;
    }

    /** Decodes %XX sequences except %2F / %2f so encoded path separators are preserved. */
    static String decodePercentPreservingSlash(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s != null ? s : "";
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n) {
                char h1 = s.charAt(i + 1);
                char h2 = s.charAt(i + 2);
                int d1 = Character.digit(h1, 16);
                int d2 = Character.digit(h2, 16);
                if (d1 >= 0 && d2 >= 0) {
                    int val = (d1 << 4) | d2;
                    if (val == '/') {
                        out.append(s, i, i + 3);
                        i += 3;
                        continue;
                    }
                    // Decode UTF-8 multi-byte percent sequence cleanly
                    int j = i;
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    while (j + 2 < n && s.charAt(j) == '%') {
                        int a = Character.digit(s.charAt(j + 1), 16);
                        int b = Character.digit(s.charAt(j + 2), 16);
                        if (a < 0 || b < 0) break;
                        int bv = (a << 4) | b;
                        if (bv == '/') break;
                        bytes.write(bv);
                        j += 3;
                    }
                    if (bytes.size() > 0) {
                        out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                        i = j;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
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

    private static CompiledPath compileTemplate(
        String normPath,
        boolean ignoreTrailingSlash,
        boolean caseInsensitive,
        boolean idsAsParams
    ) {
        int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
        if ("/".equals(normPath)) {
            return new CompiledPath(false, 0, Pattern.compile("^/$", flags));
        }
        String[] segments = normPath.substring(1).split("/");
        boolean wild = false;
        int literals = 0;
        StringBuilder regex = new StringBuilder("^");
        for (String seg : segments) {
            if (seg.equals("**") || seg.equals("{**}") || seg.startsWith("{*")) {
                wild = true;
                regex.append("(?:/.*)?");
            } else if ((seg.startsWith("{") && seg.endsWith("}"))
                || seg.startsWith(":")
                || (seg.startsWith("<") && seg.endsWith(">"))
                || seg.equals("*")) {
                wild = true;
                regex.append("/[^/]+");
            } else if (idsAsParams && PathNamer.isDynamicIdSegment(seg)) {
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
        return new CompiledPath(wild, literals, Pattern.compile(regex.toString(), flags));
    }

    private static SpecMeta buildSpecMeta(ApiSpecEntry entry, int order) {
        String raw = entry.rawSpecPath != null ? entry.rawSpecPath.trim() : "";
        String specHost = "";
        String graphqlOp = "";
        List<String[]> queryPairs = new ArrayList<>();

        Matcher opMatcher = OP_BRACKET.matcher(raw);
        if (opMatcher.find()) {
            graphqlOp = opMatcher.group(1).trim();
        }
        String rawClean = OP_BRACKET.matcher(raw).replaceAll("").trim();

        String lower = rawClean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URI uri = URI.create(rawClean);
                if (uri.getHost() != null) {
                    specHost = uri.getHost().toLowerCase(Locale.ROOT);
                }
            } catch (Exception ignored) {
            }
        }

        int hash = rawClean.indexOf('#');
        if (hash >= 0) {
            String frag = rawClean.substring(hash + 1).trim();
            if (graphqlOp.isEmpty() && !frag.isEmpty() && !frag.startsWith("/")) {
                graphqlOp = frag;
            }
            rawClean = rawClean.substring(0, hash);
        }

        int q = rawClean.indexOf('?');
        if (q >= 0 && q + 1 < rawClean.length()) {
            String qs = rawClean.substring(q + 1);
            for (String pair : qs.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = decodeUrlSafe(eq >= 0 ? pair.substring(0, eq) : pair).trim();
                String v = eq >= 0 ? decodeUrlSafe(pair.substring(eq + 1)).trim() : null;
                if (!k.isEmpty()) {
                    queryPairs.add(new String[] { k, v });
                }
            }
        }

        CompiledPath ci = compileTemplate(entry.normalizedSpecPath, true, true, false);
        CompiledPath idRelaxed = compileTemplate(entry.normalizedSpecPath, true, true, true);
        return new SpecMeta(specHost, graphqlOp, queryPairs, ci.pattern, idRelaxed.pattern, order);
    }

    private static String firstStaticSegmentLower(String normPath) {
        if (normPath == null || normPath.length() <= 1) {
            return "*";
        }
        int slash = normPath.indexOf('/', 1);
        String first = slash > 1 ? normPath.substring(1, slash) : normPath.substring(1);
        if (first.isEmpty() || first.contains("{") || first.contains("*") || first.startsWith(":")
            || (first.startsWith("<") && first.endsWith(">"))) {
            return "*";
        }
        return first.toLowerCase(Locale.ROOT);
    }

    public ApiSpecEntry findMatch(String method, String requestPathAndQuery, CrawlConfig config) {
        return findMatch(method, requestPathAndQuery, null, "", config);
    }

    public ApiSpecEntry findMatch(
        String method,
        String requestPathAndQuery,
        String host,
        String graphqlOp,
        CrawlConfig config
    ) {
        if (entries.isEmpty()) {
            return null;
        }
        String mUpper = method != null ? method.trim().toUpperCase(Locale.ROOT) : "GET";
        boolean stripSlash = config == null || config.isIgnoreTrailingSlash();
        String normReqPath = normalizeSpecPath(requestPathAndQuery, stripSlash);
        String reqHost = host != null ? host.trim().toLowerCase(Locale.ROOT) : "";
        Map<String, List<String>> reqQuery = parseQueryMap(requestPathAndQuery);
        String reqOp = graphqlOp != null ? graphqlOp.trim() : "";

        List<String> candidates = buildPathCandidates(normReqPath, config);

        ApiSpecEntry best = null;
        long bestScore = Long.MIN_VALUE;

        // 1. Exact path match (case-sensitive, then case-insensitive fallback)
        for (String cand : candidates) {
            List<ApiSpecEntry> exactList = exactIndex.get(cand);
            if (exactList != null) {
                for (ApiSpecEntry entry : exactList) {
                    long score = scoreCandidate(entry, mUpper, reqHost, reqQuery, reqOp, 4);
                    if (score > bestScore) {
                        bestScore = score;
                        best = entry;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }

        for (String cand : candidates) {
            List<ApiSpecEntry> ciList = exactLowerIndex.get(cand.toLowerCase(Locale.ROOT));
            if (ciList != null) {
                for (ApiSpecEntry entry : ciList) {
                    long score = scoreCandidate(entry, mUpper, reqHost, reqQuery, reqOp, 3);
                    if (score > bestScore) {
                        bestScore = score;
                        best = entry;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }

        // 2. Parameterized template match ({id}, :id, *) bucketed by first static segment + "*"
        for (String cand : candidates) {
            String firstSeg = firstStaticSegmentLower(cand);
            List<ApiSpecEntry> primaryBucket = wildcardBuckets.get(firstSeg);
            if (primaryBucket != null) {
                for (ApiSpecEntry entry : primaryBucket) {
                    if (entry.pathRegex.matcher(cand).matches()
                        || metaByEntry.get(entry).caseInsensitiveRegex.matcher(cand).matches()) {
                        long score = scoreCandidate(entry, mUpper, reqHost, reqQuery, reqOp, 2);
                        if (score > bestScore) {
                            bestScore = score;
                            best = entry;
                        }
                    }
                }
            }
            if (!"*".equals(firstSeg)) {
                List<ApiSpecEntry> starBucket = wildcardBuckets.get("*");
                if (starBucket != null) {
                    for (ApiSpecEntry entry : starBucket) {
                        if (entry.pathRegex.matcher(cand).matches()
                            || metaByEntry.get(entry).caseInsensitiveRegex.matcher(cand).matches()) {
                            long score = scoreCandidate(entry, mUpper, reqHost, reqQuery, reqOp, 2);
                            if (score > bestScore) {
                                bestScore = score;
                                best = entry;
                            }
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }

        // 3. Fallback: treat literal numeric/UUID/hex segments in Excel specs as {id} when normalizeRouteIds=true
        if (config == null || config.isNormalizeRouteIds()) {
            for (String cand : candidates) {
                for (ApiSpecEntry entry : entries) {
                    SpecMeta meta = metaByEntry.get(entry);
                    if (meta.idRelaxedRegex.matcher(cand).matches()) {
                        long score = scoreCandidate(entry, mUpper, reqHost, reqQuery, reqOp, 1);
                        if (score > bestScore) {
                            bestScore = score;
                            best = entry;
                        }
                    }
                }
            }
        }
        return best;
    }

    private long scoreCandidate(
        ApiSpecEntry entry,
        String reqMethodUpper,
        String reqHost,
        Map<String, List<String>> reqQuery,
        String reqOp,
        int matchTier
    ) {
        if (!methodMatches(entry, reqMethodUpper)) {
            return Long.MIN_VALUE;
        }
        SpecMeta meta = metaByEntry.get(entry);
        if (!meta.host.isEmpty() && !reqHost.isEmpty() && !meta.host.equals(reqHost)) {
            return Long.MIN_VALUE;
        }
        if (!meta.graphqlOp.isEmpty() && !reqOp.isEmpty() && !meta.graphqlOp.equalsIgnoreCase(reqOp)) {
            return Long.MIN_VALUE;
        }
        if (!meta.queryPairs.isEmpty() && !queryConstraintsMatch(meta.queryPairs, reqQuery)) {
            return Long.MIN_VALUE;
        }

        long score = (long) matchTier * 1_000_000L;
        if (!meta.graphqlOp.isEmpty() && meta.graphqlOp.equalsIgnoreCase(reqOp)) {
            score += 200_000L;
        }
        if (!meta.host.isEmpty() && meta.host.equals(reqHost)) {
            score += 100_000L;
        }
        score += (long) meta.queryPairs.size() * 10_000L;
        score += (long) entry.literalSegmentCount * 100L;
        score -= meta.order;
        return score;
    }

    private static boolean queryConstraintsMatch(List<String[]> required, Map<String, List<String>> actual) {
        for (String[] pair : required) {
            List<String> vals = actual.get(pair[0]);
            if (vals == null) {
                return false;
            }
            String reqVal = pair[1];
            if (reqVal != null && !reqVal.isEmpty() && !PLACEHOLDER_VALUE.matcher(reqVal).find()) {
                if (!vals.contains(reqVal)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Map<String, List<String>> parseQueryMap(String rawPathAndQuery) {
        if (rawPathAndQuery == null) {
            return Collections.emptyMap();
        }
        int q = rawPathAndQuery.indexOf('?');
        if (q < 0 || q + 1 >= rawPathAndQuery.length()) {
            return Collections.emptyMap();
        }
        String qs = rawPathAndQuery.substring(q + 1);
        int hash = qs.indexOf('#');
        if (hash >= 0) {
            qs = qs.substring(0, hash);
        }
        Map<String, List<String>> out = new HashMap<>();
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = decodeUrlSafe(eq >= 0 ? pair.substring(0, eq) : pair).trim();
            String v = eq >= 0 ? decodeUrlSafe(pair.substring(eq + 1)) : "";
            if (!k.isEmpty()) {
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        return out;
    }

    private static String decodeUrlSafe(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return s;
        }
    }

    private static List<String> buildPathCandidates(String normReqPath, CrawlConfig config) {
        List<String> candidates = new ArrayList<>(2);
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
        return candidates;
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
        String graphqlOp = ParamExtractor.extractGraphqlOperation(requestPathWithQuery, contentType, requestBodyBytes);
        ApiSpecEntry entry = findMatch(method, requestPathWithQuery, host, graphqlOp, config);
        if (entry == null) {
            return null;
        }
        Set<String> params = ParamExtractor.extractParameterKeys(requestPathWithQuery, contentType, requestBodyBytes);
        CapturedApiEntry capture = new CapturedApiEntry(
            entry.stt,
            method != null ? method.trim().toUpperCase(Locale.ROOT) : "GET",
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

    /**
     * Scans JS bundle content in a single O(N) pass using an Aho-Corasick multi-pattern
     * automaton built from all Excel entry literal prefixes.
     */
    public void attachJsHintIfMentioned(String jsContent, String jsUrl) {
        if (jsContent == null || jsContent.isEmpty() || entries.isEmpty() || jsPrefixMatcher == null) {
            return;
        }
        jsPrefixMatcher.scan(jsContent, jsUrl);
    }

    private static AhoCorasickMatcher buildJsPrefixMatcher(List<ApiSpecEntry> entries) {
        Map<String, List<ApiSpecEntry>> prefixMap = new LinkedHashMap<>();
        for (ApiSpecEntry entry : entries) {
            String needle = entry.normalizedSpecPath;
            if (needle == null || needle.equals("/") || needle.length() < 3) {
                continue;
            }
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
            if (prefix.length() >= 4) {
                prefixMap.computeIfAbsent(prefix, k -> new ArrayList<>()).add(entry);
            }
        }
        return prefixMap.isEmpty() ? null : new AhoCorasickMatcher(prefixMap);
    }

    /** Lightweight zero-dependency Aho-Corasick multi-pattern string matcher. */
    private static final class AhoCorasickMatcher {
        private static final class Node {
            final Map<Character, Node> next = new HashMap<>(4);
            Node fail;
            List<ApiSpecEntry> outputs;
        }

        private final Node root = new Node();

        AhoCorasickMatcher(Map<String, List<ApiSpecEntry>> patternToEntries) {
            for (Map.Entry<String, List<ApiSpecEntry>> e : patternToEntries.entrySet()) {
                Node cur = root;
                String pat = e.getKey();
                for (int i = 0; i < pat.length(); i++) {
                    cur = cur.next.computeIfAbsent(pat.charAt(i), c -> new Node());
                }
                if (cur.outputs == null) {
                    cur.outputs = new ArrayList<>(e.getValue());
                } else {
                    cur.outputs.addAll(e.getValue());
                }
            }
            Queue<Node> q = new ArrayDeque<>();
            for (Node child : root.next.values()) {
                child.fail = root;
                q.add(child);
            }
            while (!q.isEmpty()) {
                Node cur = q.poll();
                for (Map.Entry<Character, Node> edge : cur.next.entrySet()) {
                    char ch = edge.getKey();
                    Node nxt = edge.getValue();
                    Node f = cur.fail;
                    while (f != null && !f.next.containsKey(ch)) {
                        f = f.fail;
                    }
                    nxt.fail = (f != null) ? f.next.get(ch) : root;
                    if (nxt.fail.outputs != null) {
                        if (nxt.outputs == null) {
                            nxt.outputs = new ArrayList<>(nxt.fail.outputs);
                        } else {
                            nxt.outputs.addAll(nxt.fail.outputs);
                        }
                    }
                    q.add(nxt);
                }
            }
        }

        void scan(String text, String jsUrl) {
            Node cur = root;
            int n = text.length();
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                while (cur != root && !cur.next.containsKey(ch)) {
                    cur = cur.fail;
                }
                Node nxt = cur.next.get(ch);
                cur = (nxt != null) ? nxt : root;
                if (cur.outputs != null) {
                    for (ApiSpecEntry entry : cur.outputs) {
                        entry.addJsHint(jsUrl);
                    }
                }
            }
        }
    }
}
