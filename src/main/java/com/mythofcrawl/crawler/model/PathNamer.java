package com.mythofcrawl.crawler.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes page routes and UI element provenance into hierarchical paths such as:
 *   "/ > contact > button 'Send'"
 *   "/ > contact > page load"
 *   "/ > page load"
 *
 * Preserves exact original casing of UI labels (per user requirement #4).
 */
public final class PathNamer {

    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d+$");
    private static final Pattern UUID_LIKE = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");

    private PathNamer() {
    }

    public static String buildActionPath(String pageUrlOrRoute, String elementKind, String rawLabel, boolean normalizeRouteIds) {
        return buildActionPath(pageUrlOrRoute, elementKind, rawLabel, normalizeRouteIds, "/");
    }

    public static String buildActionPath(
        String pageUrlOrRoute,
        String elementKind,
        String rawLabel,
        boolean normalizeRouteIds,
        String rootLabel
    ) {
        String root = sanitizeSegment(rootLabel);
        if (root.isEmpty()) {
            root = "/";
        }
        List<String> segments = new ArrayList<>();
        segments.add(root);
        segments.addAll(extractRouteSegments(pageUrlOrRoute, normalizeRouteIds));

        String kind = sanitizeSegment(elementKind);
        if (kind.isEmpty() || "page load".equalsIgnoreCase(kind)) {
            segments.add("page load");
        } else {
            String label = sanitizeUiLabel(rawLabel);
            if (label.isEmpty()) {
                segments.add(kind);
            } else {
                segments.add(kind + " '" + label + "'");
            }
        }
        return String.join(" > ", segments);
    }

    public static String formatPageLoadPath(String pageUrlOrRoute, boolean normalizeRouteIds) {
        return formatPageLoadPath(pageUrlOrRoute, normalizeRouteIds, "/");
    }

    public static String formatPageLoadPath(String pageUrlOrRoute, boolean normalizeRouteIds, String rootLabel) {
        return buildActionPath(pageUrlOrRoute, "page load", "", normalizeRouteIds, rootLabel);
    }

    public static List<String> extractRouteSegments(String pageUrlOrRoute, boolean normalizeRouteIds) {
        if (pageUrlOrRoute == null || pageUrlOrRoute.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String raw = pageUrlOrRoute.trim();
        String path = raw;
        String fragment = "";
        try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                URI uri = URI.create(raw);
                path = uri.getRawPath();
                fragment = uri.getRawFragment() != null ? uri.getRawFragment() : "";
            } else {
                int hashIdx = raw.indexOf('#');
                if (hashIdx >= 0) {
                    fragment = raw.substring(hashIdx + 1);
                    path = raw.substring(0, hashIdx);
                }
                int qIdx = path.indexOf('?');
                if (qIdx >= 0) {
                    path = path.substring(0, qIdx);
                }
            }
        } catch (Exception ignored) {
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) {
                path = path.substring(0, qIdx);
            }
        }

        // SPA hash routing such as /#/contact or /#!/settings/profile
        String chosenRoute = path != null ? path : "";
        if (fragment != null && (fragment.startsWith("/") || fragment.startsWith("!/"))) {
            String hashPath = fragment.startsWith("!/") ? fragment.substring(1) : fragment;
            if (!hashPath.equals("/") || chosenRoute.isEmpty() || chosenRoute.equals("/")) {
                chosenRoute = hashPath;
            }
        }

        List<String> out = new ArrayList<>();
        for (String seg : chosenRoute.split("/")) {
            String clean = sanitizeSegment(seg);
            if (clean.isEmpty()) {
                continue;
            }
            if (normalizeRouteIds && isDynamicIdSegment(clean)) {
                clean = "{id}";
            }
            out.add(clean);
        }
        return out;
    }

    public static boolean isDynamicIdSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        return NUMERIC_ID.matcher(segment).matches()
            || UUID_LIKE.matcher(segment).matches()
            || LONG_HEX.matcher(segment).matches();
    }

    public static String sanitizeUiLabel(String rawLabel) {
        if (rawLabel == null) {
            return "";
        }
        // Preserve original upper/lower case; only collapse whitespace and remove tree delimiters
        String cleaned = rawLabel
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(">", "›")
            .replace("'", "’")
            .replaceAll("\\s+", " ")
            .trim();
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 45).trim() + "...";
        }
        return cleaned;
    }

    public static String sanitizeSegment(String segment) {
        if (segment == null) {
            return "";
        }
        return segment
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(">", "›")
            .replaceAll("\\s+", " ")
            .trim();
    }

    public static List<String> splitHierarchyPath(String fullPath) {
        if (fullPath == null || fullPath.trim().isEmpty()) {
            return Collections.singletonList("/");
        }
        List<String> out = new ArrayList<>();
        for (String part : fullPath.split(">")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? Collections.singletonList("/") : out;
    }

    public static boolean matchesPrefix(String fullPath, List<String> prefixSegments) {
        if (prefixSegments == null || prefixSegments.isEmpty()) {
            return true;
        }
        List<String> parts = splitHierarchyPath(fullPath);
        if (prefixSegments.size() > parts.size()) {
            return false;
        }
        for (int i = 0; i < prefixSegments.size(); i++) {
            if (!parts.get(i).equalsIgnoreCase(prefixSegments.get(i))) {
                return false;
            }
        }
        return true;
    }
}
