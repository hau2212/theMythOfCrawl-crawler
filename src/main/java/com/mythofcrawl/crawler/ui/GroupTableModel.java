package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.AuditNote;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table models and calm semantic renderers for:
 * 1) ActionGroupsModel - UI actions / feature groups ("toppage > contact > button 'Send'"), supporting merged hierarchy tree view and flat view
 * 2) GroupApisModel - API calls belonging to selected group(s), supporting collapsible path-grouping when >= 2 APIs share the same path
 * 3) AuditNotesTableModel - Dedicated consolidated NOTE audit log across all groups & APIs
 */
public final class GroupTableModel {

    private GroupTableModel() {
    }

    /**
     * Canonical path key used to merge >= 2 API calls sharing the same endpoint path.
     * Strips scheme/host, query string, fragment, trailing slash, and GraphQL annotation suffix.
     */
    public static String normalizeApiGroupPath(ApiCall c) {
        if (c == null) {
            return "/";
        }
        if (c.inExcel && c.excelPath != null && !c.excelPath.trim().isEmpty()) {
            return normalizeRawPathKey(c.excelPath.trim());
        }
        if (c.endpointKey != null && !c.endpointKey.trim().isEmpty()) {
            String ek = c.endpointKey.trim();
            int sp = ek.indexOf(' ');
            if (sp > 0 && sp < 10) {
                ek = ek.substring(sp + 1).trim();
            }
            return normalizeRawPathKey(ek);
        }
        return normalizeRawPathKey(c.displayPath());
    }

    public static String normalizeRawPathKey(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "/";
        }
        String s = raw.trim();
        int spaceIdx = s.indexOf(" (");
        if (spaceIdx > 0) {
            s = s.substring(0, spaceIdx).trim();
        }
        int bracketIdx = s.indexOf(" [");
        if (bracketIdx > 0) {
            s = s.substring(0, bracketIdx).trim();
        }
        int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx);
        }
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) {
            s = s.substring(0, qIdx);
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            int schemeEnd = s.indexOf("://");
            int slashAfterHost = s.indexOf('/', schemeEnd + 3);
            s = slashAfterHost >= 0 ? s.substring(slashAfterHost) : "/";
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "/";
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static List<String> splitActionPath(String actionPath) {
        List<String> out = new ArrayList<>();
        if (actionPath != null && !actionPath.trim().isEmpty()) {
            for (String part : actionPath.split("\\s*>\\s*")) {
                String clean = part.trim();
                if (!clean.isEmpty()) {
                    out.add(clean);
                }
            }
        }
        if (out.isEmpty()) {
            out.add("root");
        }
        return out;
    }

    public static List<String> treeSegmentsForActionPath(String actionPath) {
        List<String> raw = splitActionPath(actionPath);
        if (raw.size() >= 2 && "/".equals(raw.get(0))) {
            List<String> out = new ArrayList<>(raw.size());
            String second = raw.get(1);
            if (isUiOrControlSegment(second)) {
                out.add("Trang chủ (/)");
                out.addAll(raw.subList(1, raw.size()));
            } else {
                out.add("/" + second);
                out.addAll(raw.subList(2, raw.size()));
            }
            return out;
        }
        return raw;
    }

    private static boolean isUiOrControlSegment(String seg) {
        if (seg == null) {
            return false;
        }
        String l = seg.trim().toLowerCase();
        return l.equals("page load")
            || l.equals("manual")
            || l.startsWith("section ")
            || l.startsWith("popup ")
            || l.startsWith("menu ")
            || l.startsWith("mục ")
            || l.startsWith("form ")
            || l.startsWith("iframe ")
            || l.startsWith("button ")
            || l.startsWith("link ")
            || l.startsWith("tab ")
            || l.startsWith("menu-item ")
            || l.startsWith("select ")
            || l.startsWith("toggle ")
            || l.startsWith("search ")
            || l.startsWith("input ")
            || l.startsWith("nút ")
            || l.equals("button")
            || l.equals("link")
            || l.equals("form")
            || l.equals("input");
    }

    private static String indentPrefix(int depth) {
        if (depth <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(depth * 4 + 2);
        for (int i = 0; i < depth; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }

    private static String treeBranchPrefix(int depth, boolean isLast) {
        if (depth <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(depth * 4 + 4);
        for (int i = 0; i < depth - 1; i++) {
            sb.append("│   ");
        }
        sb.append(isLast ? "└─ " : "├─ ");
        return sb.toString();
    }

    public static final class PathOverlapSummary {
        public final String pathKey;
        public final int totalCalls;
        public final int distinctLocations;
        public final boolean allIdentical;
        public final String diffSummary;
        public final List<ApiCall> calls;

        PathOverlapSummary(
            String pathKey,
            int totalCalls,
            int distinctLocations,
            boolean allIdentical,
            String diffSummary,
            List<ApiCall> calls
        ) {
            this.pathKey = pathKey;
            this.totalCalls = totalCalls;
            this.distinctLocations = distinctLocations;
            this.allIdentical = allIdentical;
            this.diffSummary = diffSummary;
            this.calls = calls;
        }
    }

    public static PathOverlapSummary summarizePathOverlap(ActionGroupStore store, ApiCall target) {
        if (store == null || target == null) {
            return new PathOverlapSummary("/", 0, 0, true, "", Collections.emptyList());
        }
        String key = normalizeApiGroupPath(target);
        return summarizeCallsForPath(key, collectCallsForNormalizedPath(store, key));
    }

    public static List<ApiCall> collectCallsForNormalizedPath(ActionGroupStore store, String pathKey) {
        if (store == null || pathKey == null) {
            return Collections.emptyList();
        }
        List<ApiCall> matches = new ArrayList<>();
        for (ActionGroup g : store.snapshotGroups()) {
            for (ApiCall c : g.getCalls()) {
                if (pathKey.equals(normalizeApiGroupPath(c))) {
                    matches.add(c);
                }
            }
        }
        return matches;
    }

    public static PathOverlapSummary summarizeCallsForPath(String pathKey, List<ApiCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return new PathOverlapSummary(pathKey, 0, 0, true, "", Collections.emptyList());
        }
        Set<String> locations = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        Set<Integer> statuses = new LinkedHashSet<>();
        Set<String> paramSigs = new LinkedHashSet<>();
        int minSize = Integer.MAX_VALUE;
        int maxSize = Integer.MIN_VALUE;

        for (ApiCall c : calls) {
            locations.add(c.displayName());
            if (!c.capture.method.isEmpty()) {
                methods.add(c.capture.method);
            }
            statuses.add(c.capture.statusCode);
            List<String> keys = new ArrayList<>(c.capture.paramKeys);
            Collections.sort(keys);
            paramSigs.add(String.join(",", keys));
            int sz = c.capture.responseLength;
            if (sz < minSize) {
                minSize = sz;
            }
            if (sz > maxSize) {
                maxSize = sz;
            }
        }

        List<String> diffs = new ArrayList<>();
        if (methods.size() > 1) {
            diffs.add("Method (" + String.join("/", methods) + ")");
        }
        if (statuses.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Integer st : statuses) {
                if (sb.length() > 0) {
                    sb.append("/");
                }
                sb.append(st);
            }
            diffs.add("HTTP (" + sb + ")");
        }
        if (paramSigs.size() > 1) {
            diffs.add("Params (" + paramSigs.size() + " kiểu)");
        }
        if (calls.size() > 1 && maxSize > minSize) {
            diffs.add("Size (" + minSize + "B.." + maxSize + "B)");
        }

        boolean identical = diffs.isEmpty();
        String summary = identical
            ? "Giống hệt 100% (chỉ khác vị trí bấm)"
            : ("Khác: " + String.join(" | ", diffs));
        return new PathOverlapSummary(
            pathKey,
            calls.size(),
            locations.size(),
            identical,
            summary,
            Collections.unmodifiableList(new ArrayList<>(calls))
        );
    }

    public static String describeCallDiffAgainst(ApiCall candidate, ApiCall baseline) {
        if (candidate == null || baseline == null) {
            return "";
        }
        if (candidate.groupId == baseline.groupId && candidate.index == baseline.index) {
            return "[Đang xem]";
        }
        List<String> diffs = new ArrayList<>();
        if (!candidate.capture.method.equalsIgnoreCase(baseline.capture.method)) {
            diffs.add("Method " + candidate.capture.method + " ≠ " + baseline.capture.method);
        }
        if (candidate.capture.statusCode != baseline.capture.statusCode) {
            diffs.add("HTTP " + candidate.capture.statusCode + " ≠ " + baseline.capture.statusCode);
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String k : candidate.capture.paramKeys) {
            if (!baseline.capture.paramKeys.contains(k)) {
                added.add("+" + k);
            }
        }
        for (String k : baseline.capture.paramKeys) {
            if (!candidate.capture.paramKeys.contains(k)) {
                removed.add("-" + k);
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            List<String> pDiff = new ArrayList<>(added);
            pDiff.addAll(removed);
            diffs.add("Params (" + String.join(", ", pDiff) + ")");
        }
        if (candidate.capture.responseLength != baseline.capture.responseLength) {
            diffs.add("Size " + candidate.capture.responseLength + "B ≠ " + baseline.capture.responseLength + "B");
        }
        return diffs.isEmpty() ? "Giống hệt 100% (khác vị trí)" : String.join(" | ", diffs);
    }

    public static final class ActionGroupsModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Tên Nhóm Chức Năng (Action Path)", "Số API", "NOTE", "Excel", "Lượt"
        };

        public static final class GroupRow {
            public final boolean isFolder;
            public final int depth;
            public final String treeNumber;
            public final String folderKey;
            public final String displayLabel;
            public final ActionGroup directGroup;
            public final List<ActionGroup> subtreeGroups;
            public final int totalApis;
            public final int totalNotes;
            public final int totalExcel;
            public final int totalTriggers;
            public final AuditNote.Severity worstSeverity;
            public final boolean collapsed;

            GroupRow(
                boolean isFolder,
                int depth,
                String treeNumber,
                String folderKey,
                String displayLabel,
                ActionGroup directGroup,
                List<ActionGroup> subtreeGroups,
                int totalApis,
                int totalNotes,
                int totalExcel,
                int totalTriggers,
                AuditNote.Severity worstSeverity,
                boolean collapsed
            ) {
                this.isFolder = isFolder;
                this.depth = depth;
                this.treeNumber = treeNumber != null ? treeNumber : "";
                this.folderKey = folderKey;
                this.displayLabel = displayLabel;
                this.directGroup = directGroup;
                this.subtreeGroups = subtreeGroups;
                this.totalApis = totalApis;
                this.totalNotes = totalNotes;
                this.totalExcel = totalExcel;
                this.totalTriggers = totalTriggers;
                this.worstSeverity = worstSeverity;
                this.collapsed = collapsed;
            }
        }

        private static final class PrefixNode {
            final String segment;
            final String fullPath;
            final Map<String, PrefixNode> children = new LinkedHashMap<>();
            ActionGroup directGroup;
            final List<ActionGroup> subtreeGroups = new ArrayList<>();
            int totalApis;
            int totalNotes;
            int totalExcel;
            int totalTriggers;
            AuditNote.Severity worstSeverity;

            PrefixNode(String segment, String fullPath) {
                this.segment = segment;
                this.fullPath = fullPath;
            }
        }

        private final ActionGroupStore store;
        private List<ActionGroup> filtered = new ArrayList<>();
        private List<GroupRow> visible = new ArrayList<>();
        private final Set<String> collapsedFolders = new HashSet<>();
        private boolean treeMode = true;
        private String filterMode = "WITH_APIS"; // ALL | WITH_APIS | WITH_NOTES
        private String searchQuery = "";

        public ActionGroupsModel(ActionGroupStore store) {
            this.store = store;
        }

        public boolean isTreeMode() {
            return treeMode;
        }

        public void setTreeMode(boolean treeMode) {
            if (this.treeMode != treeMode) {
                this.treeMode = treeMode;
                rebuildVisibleRows();
                fireTableStructureChanged();
            }
        }

        public void setFilter(String filterMode, String searchQuery) {
            this.filterMode = filterMode != null ? filterMode : "WITH_APIS";
            this.searchQuery = searchQuery != null ? searchQuery.trim().toLowerCase() : "";
            refresh();
        }

        public void refresh() {
            List<ActionGroup> all = store != null ? store.snapshotGroups() : Collections.emptyList();
            List<ActionGroup> out = new ArrayList<>(all.size());
            for (ActionGroup g : all) {
                if ("WITH_APIS".equals(filterMode) && g.callCount() == 0 && g.noteCount() == 0) {
                    continue;
                }
                if ("WITH_NOTES".equals(filterMode) && g.noteCount() == 0) {
                    continue;
                }
                if (!searchQuery.isEmpty() && !matchesGroup(g, searchQuery)) {
                    continue;
                }
                out.add(g);
            }
            this.filtered = out;
            rebuildVisibleRows();
            fireTableDataChanged();
        }

        private void rebuildVisibleRows() {
            List<GroupRow> rows = new ArrayList<>();
            if (!treeMode) {
                int seq = 0;
                for (ActionGroup g : filtered) {
                    seq++;
                    rows.add(new GroupRow(
                        false,
                        0,
                        String.valueOf(seq),
                        g.actionPath,
                        g.actionPath,
                        g,
                        Collections.singletonList(g),
                        g.callCount(),
                        g.noteCount(),
                        g.excelMatchedCount(),
                        g.getTriggerCount(),
                        g.worstSeverity(),
                        false
                    ));
                }
                this.visible = rows;
                return;
            }

            PrefixNode root = new PrefixNode("", "");
            for (ActionGroup g : filtered) {
                List<String> parts = treeSegmentsForActionPath(g.actionPath);
                PrefixNode cur = root;
                StringBuilder pathAcc = new StringBuilder();
                for (String part : parts) {
                    if (pathAcc.length() > 0) {
                        pathAcc.append(" > ");
                    }
                    pathAcc.append(part);
                    final String fp = pathAcc.toString();
                    cur = cur.children.computeIfAbsent(part, k -> new PrefixNode(k, fp));
                }
                cur.directGroup = g;
            }
            aggregatePrefixTree(root);
            int topCount = root.children.size();
            int topIdx = 0;
            for (PrefixNode top : root.children.values()) {
                topIdx++;
                emitPrefixNode(top, top.segment, 0, String.valueOf(topIdx), topIdx == topCount, rows);
            }
            this.visible = rows;
        }

        private static void aggregatePrefixTree(PrefixNode node) {
            node.subtreeGroups.clear();
            node.totalApis = 0;
            node.totalNotes = 0;
            node.totalExcel = 0;
            node.totalTriggers = 0;
            node.worstSeverity = null;

            if (node.directGroup != null) {
                ActionGroup g = node.directGroup;
                node.subtreeGroups.add(g);
                node.totalApis += g.callCount();
                node.totalNotes += g.noteCount();
                node.totalExcel += g.excelMatchedCount();
                node.totalTriggers += g.getTriggerCount();
                node.worstSeverity = AuditNote.worst(node.worstSeverity, g.worstSeverity());
            }
            for (PrefixNode child : node.children.values()) {
                aggregatePrefixTree(child);
                node.subtreeGroups.addAll(child.subtreeGroups);
                node.totalApis += child.totalApis;
                node.totalNotes += child.totalNotes;
                node.totalExcel += child.totalExcel;
                node.totalTriggers += child.totalTriggers;
                node.worstSeverity = AuditNote.worst(node.worstSeverity, child.worstSeverity);
            }
        }

        private void emitPrefixNode(
            PrefixNode node,
            String label,
            int depth,
            String numLabel,
            boolean isLast,
            List<GroupRow> out
        ) {
            if (node.children.isEmpty()) {
                ActionGroup g = node.directGroup;
                if (g == null) {
                    return;
                }
                String rendered = depth == 0
                    ? label
                    : treeBranchPrefix(depth, isLast) + label;
                out.add(new GroupRow(
                    false,
                    depth,
                    numLabel,
                    node.fullPath,
                    rendered,
                    g,
                    Collections.singletonList(g),
                    g.callCount(),
                    g.noteCount(),
                    g.excelMatchedCount(),
                    g.getTriggerCount(),
                    g.worstSeverity(),
                    false
                ));
                return;
            }

            // Compress single-child intermediate paths when there is no direct group on the intermediate prefix.
            if (node.directGroup == null && node.children.size() == 1) {
                PrefixNode onlyChild = node.children.values().iterator().next();
                emitPrefixNode(onlyChild, label + " > " + onlyChild.segment, depth, numLabel, isLast, out);
                return;
            }

            boolean collapsed = collapsedFolders.contains(node.fullPath);
            String folderTitle = treeBranchPrefix(depth, isLast)
                + (collapsed ? "📁 " : "📂 ")
                + label
                + " (" + node.subtreeGroups.size() + " nhóm)";
            out.add(new GroupRow(
                true,
                depth,
                numLabel,
                node.fullPath,
                folderTitle,
                null,
                Collections.unmodifiableList(new ArrayList<>(node.subtreeGroups)),
                node.totalApis,
                node.totalNotes,
                node.totalExcel,
                node.totalTriggers,
                node.worstSeverity,
                collapsed
            ));
            if (!collapsed) {
                int childIdx = 0;
                int totalChildren = node.children.size() + (node.directGroup != null ? 1 : 0);
                if (node.directGroup != null) {
                    childIdx++;
                    ActionGroup g = node.directGroup;
                    boolean childLast = (childIdx == totalChildren);
                    out.add(new GroupRow(
                        false,
                        depth + 1,
                        numLabel + "." + childIdx,
                        node.fullPath + "::self",
                        treeBranchPrefix(depth + 1, childLast) + g.actionPath,
                        g,
                        Collections.singletonList(g),
                        g.callCount(),
                        g.noteCount(),
                        g.excelMatchedCount(),
                        g.getTriggerCount(),
                        g.worstSeverity(),
                        false
                    ));
                }
                for (PrefixNode child : node.children.values()) {
                    childIdx++;
                    boolean childLast = (childIdx == totalChildren);
                    emitPrefixNode(child, child.segment, depth + 1, numLabel + "." + childIdx, childLast, out);
                }
            }
        }

        private static boolean matchesGroup(ActionGroup g, String q) {
            if (g.actionPath.toLowerCase().contains(q) || g.pageUrl.toLowerCase().contains(q)) {
                return true;
            }
            for (AuditNote gn : g.getGroupNotes()) {
                if (gn.endpoint.toLowerCase().contains(q) || gn.message.toLowerCase().contains(q)) {
                    return true;
                }
            }
            for (ApiCall c : g.getCalls()) {
                if (c.displayPath().toLowerCase().contains(q)
                    || c.capture.method.toLowerCase().contains(q)
                    || c.notesSummary().toLowerCase().contains(q)
                    || c.excelStt.toLowerCase().contains(q)) {
                    return true;
                }
            }
            return false;
        }

        public GroupRow getRowAt(int modelRow) {
            return modelRow >= 0 && modelRow < visible.size() ? visible.get(modelRow) : null;
        }

        public ActionGroup getGroupAt(int modelRow) {
            GroupRow r = getRowAt(modelRow);
            return r != null ? r.directGroup : null;
        }

        public List<ActionGroup> getGroupsAt(int modelRow) {
            GroupRow r = getRowAt(modelRow);
            return r != null ? r.subtreeGroups : Collections.emptyList();
        }

        public boolean isFolderRow(int modelRow) {
            GroupRow r = getRowAt(modelRow);
            return r != null && r.isFolder;
        }

        public void toggleFolderExpanded(int modelRow) {
            GroupRow r = getRowAt(modelRow);
            if (r == null || !r.isFolder) {
                return;
            }
            if (!collapsedFolders.remove(r.folderKey)) {
                collapsedFolders.add(r.folderKey);
            }
            rebuildVisibleRows();
            fireTableDataChanged();
        }

        public void expandAllFolders() {
            if (!collapsedFolders.isEmpty()) {
                collapsedFolders.clear();
                rebuildVisibleRows();
                fireTableDataChanged();
            }
        }

        public void collapseAllFolders() {
            for (GroupRow r : visible) {
                if (r.isFolder) {
                    collapsedFolders.add(r.folderKey);
                }
            }
            rebuildVisibleRows();
            fireTableDataChanged();
        }

        public int findRowForGroupId(int groupId) {
            for (int i = 0; i < visible.size(); i++) {
                GroupRow r = visible.get(i);
                if (r.directGroup != null && r.directGroup.id == groupId) {
                    return i;
                }
            }
            return -1;
        }

        public void ensureGroupVisible(ActionGroup group) {
            if (group == null || !treeMode) {
                return;
            }
            if (findRowForGroupId(group.id) >= 0) {
                return;
            }
            List<String> parts = treeSegmentsForActionPath(group.actionPath);
            StringBuilder acc = new StringBuilder();
            boolean changed = false;
            for (int i = 0; i < parts.size(); i++) {
                if (acc.length() > 0) {
                    acc.append(" > ");
                }
                acc.append(parts.get(i));
                if (collapsedFolders.remove(acc.toString())) {
                    changed = true;
                }
            }
            if (changed) {
                rebuildVisibleRows();
                fireTableDataChanged();
            }
        }

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (!treeMode && (col == 0 || col == 2 || col == 3 || col == 5)) {
                return Integer.class;
            }
            return Object.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            GroupRow r = visible.get(row);
            switch (col) {
                case 0:
                    if (treeMode) {
                        if (r.isFolder) {
                            return (r.collapsed ? "▶ " : "▼ ") + r.treeNumber;
                        }
                        return r.treeNumber;
                    }
                    return r.directGroup != null ? Integer.valueOf(r.directGroup.id) : "";
                case 1: return r.displayLabel;
                case 2: return Integer.valueOf(r.totalApis);
                case 3: return Integer.valueOf(r.totalNotes);
                case 4: return r.totalExcel > 0 ? String.valueOf(r.totalExcel) : "-";
                case 5: return Integer.valueOf(r.totalTriggers);
                default: return "";
            }
        }
    }

    public static final class GroupApisModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Tên / Vị trí tìm thấy (Action Path)", "Method", "Endpoint / Path", "HTTP", "Params", "Hits", "Excel", "NOTE"
        };

        public static final class ApiDisplayRow {
            public final boolean isGroupHeader;
            public final boolean indentedChild;
            public final boolean collapsed;
            public final boolean diffParamVariant;
            public final String pathKey;
            public final String titleDisplay;
            public final String methodDisplay;
            public final String pathDisplay;
            public final String httpDisplay;
            public final String paramsDisplay;
            public final int hits;
            public final String excelDisplay;
            public final String noteDisplay;
            public final AuditNote.Severity worstSeverity;
            public final boolean anyExcel;
            public final ApiCall call;
            public final List<ApiCall> groupedCalls;

            ApiDisplayRow(
                boolean isGroupHeader,
                boolean indentedChild,
                boolean collapsed,
                boolean diffParamVariant,
                String pathKey,
                String titleDisplay,
                String methodDisplay,
                String pathDisplay,
                String httpDisplay,
                String paramsDisplay,
                int hits,
                String excelDisplay,
                String noteDisplay,
                AuditNote.Severity worstSeverity,
                boolean anyExcel,
                ApiCall call,
                List<ApiCall> groupedCalls
            ) {
                this.isGroupHeader = isGroupHeader;
                this.indentedChild = indentedChild;
                this.collapsed = collapsed;
                this.diffParamVariant = diffParamVariant;
                this.pathKey = pathKey;
                this.titleDisplay = titleDisplay;
                this.methodDisplay = methodDisplay;
                this.pathDisplay = pathDisplay;
                this.httpDisplay = httpDisplay;
                this.paramsDisplay = paramsDisplay;
                this.hits = hits;
                this.excelDisplay = excelDisplay;
                this.noteDisplay = noteDisplay;
                this.worstSeverity = worstSeverity;
                this.anyExcel = anyExcel;
                this.call = call;
                this.groupedCalls = groupedCalls;
            }
        }

        private final ActionGroupStore store;
        private List<ApiCall> filteredCalls = new ArrayList<>();
        private List<ApiDisplayRow> rows = new ArrayList<>();
        // Default collapsed so >= 2 same-path / diff-param calls stay rolled up until user expands
        private final Set<String> expandedPaths = new HashSet<>();
        private boolean treeMode = true;
        private String searchQuery = "";
        private boolean onlyWithNotes = false;

        public GroupApisModel(ActionGroupStore store) {
            this.store = store;
        }

        public boolean isTreeMode() {
            return treeMode;
        }

        public void setTreeMode(boolean treeMode) {
            if (this.treeMode != treeMode) {
                this.treeMode = treeMode;
                rebuildDisplayRows();
                fireTableStructureChanged();
            }
        }

        public void setGroupAndFilter(ActionGroup group, boolean showAllWhenNull, boolean onlyWithNotes, String searchQuery) {
            List<ActionGroup> list = group != null ? Collections.singletonList(group) : Collections.emptyList();
            setGroupsAndFilter(list, showAllWhenNull, onlyWithNotes, searchQuery);
        }

        public void setGroupsAndFilter(
            Collection<ActionGroup> groups,
            boolean showAllWhenEmpty,
            boolean onlyWithNotes,
            String searchQuery
        ) {
            this.onlyWithNotes = onlyWithNotes;
            this.searchQuery = searchQuery != null ? searchQuery.trim().toLowerCase() : "";
            List<ApiCall> source = new ArrayList<>();
            if (groups != null && !groups.isEmpty()) {
                for (ActionGroup g : groups) {
                    if (g != null) {
                        source.addAll(g.getCalls());
                    }
                }
            } else if (showAllWhenEmpty && store != null) {
                for (ActionGroup g : store.snapshotGroups()) {
                    source.addAll(g.getCalls());
                }
            }
            List<ApiCall> filtered = new ArrayList<>();
            for (ApiCall c : source) {
                if (this.onlyWithNotes && !c.hasNotes()) {
                    continue;
                }
                if (!this.searchQuery.isEmpty()) {
                    boolean hit = c.displayName().toLowerCase().contains(this.searchQuery)
                        || c.displayPath().toLowerCase().contains(this.searchQuery)
                        || normalizeApiGroupPath(c).toLowerCase().contains(this.searchQuery)
                        || c.capture.method.toLowerCase().contains(this.searchQuery)
                        || c.capture.paramSignature().toLowerCase().contains(this.searchQuery)
                        || c.notesSummary().toLowerCase().contains(this.searchQuery)
                        || c.excelStt.toLowerCase().contains(this.searchQuery);
                    if (!hit) {
                        continue;
                    }
                }
                filtered.add(c);
            }
            this.filteredCalls = filtered;
            rebuildDisplayRows();
            fireTableDataChanged();
        }

        private void rebuildDisplayRows() {
            List<ApiDisplayRow> out = new ArrayList<>();
            if (!treeMode) {
                for (ApiCall c : filteredCalls) {
                    out.add(singletonLeafRow(c));
                }
                this.rows = out;
                return;
            }

            Map<String, List<ApiCall>> byPath = new LinkedHashMap<>();
            for (ApiCall c : filteredCalls) {
                byPath.computeIfAbsent(normalizeApiGroupPath(c), k -> new ArrayList<>()).add(c);
            }

            for (Map.Entry<String, List<ApiCall>> entry : byPath.entrySet()) {
                String pathKey = entry.getKey();
                List<ApiCall> bucket = entry.getValue();
                if (bucket.size() < 2) {
                    out.add(singletonLeafRow(bucket.get(0)));
                    continue;
                }

                // Auto-expand while user is searching so matches inside collapsed groups appear directly.
                boolean expanded = expandedPaths.contains(pathKey) || !searchQuery.isEmpty();
                boolean collapsed = !expanded;

                Set<String> methods = new LinkedHashSet<>();
                Set<Integer> statuses = new LinkedHashSet<>();
                Set<String> groupNames = new LinkedHashSet<>();
                Set<String> excelStts = new LinkedHashSet<>();
                Map<String, List<ApiCall>> byParamSig = new LinkedHashMap<>();
                int totalHits = 0;
                int excelCount = 0;
                int noteCallCount = 0;
                AuditNote.Severity worst = null;
                String sampleNote = "";

                for (ApiCall c : bucket) {
                    if (!c.capture.method.isEmpty()) {
                        methods.add(c.capture.method);
                    }
                    if (c.capture.statusCode > 0) {
                        statuses.add(c.capture.statusCode);
                    }
                    groupNames.add(c.displayName());
                    byParamSig.computeIfAbsent(canonicalParamKey(c.capture.paramKeys), k -> new ArrayList<>()).add(c);
                    totalHits += c.getHits();
                    if (c.inExcel) {
                        excelCount++;
                        if (!c.excelStt.isEmpty()) {
                            excelStts.add(c.excelStt);
                        }
                    }
                    if (c.hasNotes()) {
                        noteCallCount++;
                        worst = AuditNote.worst(worst, c.worstSeverity());
                        if (sampleNote.isEmpty()) {
                            sampleNote = c.notesSummary();
                        }
                    }
                }

                int variantCount = byParamSig.size();
                boolean hasDiffParams = variantCount > 1;
                String title = (collapsed ? "📁 " : "📂 ")
                    + pathKey
                    + " (" + bucket.size() + " API"
                    + (hasDiffParams ? (" • " + variantCount + " kiểu param") : " • cùng param")
                    + (groupNames.size() > 1 ? (" • " + groupNames.size() + " vị trí") : "")
                    + ")";
                String methodText = String.join(", ", methods);
                String statusText = joinInts(statuses);
                String paramsHeader = formatHeaderParamsSummary(byParamSig, bucket.get(0));
                String excelText = excelCount == 0
                    ? "-"
                    : (excelStts.size() == 1 && excelCount == bucket.size()
                        ? "STT " + excelStts.iterator().next()
                        : excelCount + "/" + bucket.size());
                String noteText = noteCallCount == 0
                    ? ""
                    : "[" + noteCallCount + "/" + bucket.size() + " NOTE] " + sampleNote;

                out.add(new ApiDisplayRow(
                    true,
                    false,
                    collapsed,
                    hasDiffParams,
                    pathKey,
                    title,
                    methodText,
                    pathKey,
                    statusText,
                    paramsHeader,
                    totalHits,
                    excelText,
                    noteText,
                    worst,
                    excelCount > 0,
                    null,
                    Collections.unmodifiableList(new ArrayList<>(bucket))
                ));

                if (!collapsed) {
                    Set<String> baselineKeys = bucket.get(0).capture.paramKeys;
                    int variantIdx = 0;
                    for (List<ApiCall> variantCalls : byParamSig.values()) {
                        variantIdx++;
                        for (ApiCall child : variantCalls) {
                            out.add(groupedChildRow(child, variantIdx, variantCount, baselineKeys));
                        }
                    }
                }
            }
            this.rows = out;
        }

        private static ApiDisplayRow singletonLeafRow(ApiCall c) {
            return new ApiDisplayRow(
                false,
                false,
                false,
                false,
                normalizeApiGroupPath(c),
                c.displayName(),
                c.capture.method,
                c.displayPath(),
                String.valueOf(c.capture.statusCode),
                c.capture.paramCount() + (c.capture.paramCount() > 0 ? " (" + c.capture.paramSignature() + ")" : ""),
                c.getHits(),
                c.inExcel ? ("STT " + c.excelStt) : "-",
                c.notesSummary(),
                c.worstSeverity(),
                c.inExcel,
                c,
                Collections.singletonList(c)
            );
        }

        private static ApiDisplayRow groupedChildRow(
            ApiCall c,
            int variantIdx,
            int variantCount,
            Set<String> baselineKeys
        ) {
            String baseParamLabel = c.capture.paramCount() > 0
                ? (c.capture.paramCount() + " (" + c.capture.paramSignature() + ")")
                : "0 (không param)";
            boolean diffFromBaseline = variantCount > 1 && variantIdx > 1;
            String paramText;
            if (variantCount <= 1) {
                paramText = baseParamLabel + " [cùng param]";
            } else if (variantIdx == 1) {
                paramText = "[Kiểu 1/" + variantCount + " (gốc)] " + baseParamLabel;
            } else {
                String diff = describeKeyDiffCompact(c.capture.paramKeys, baselineKeys);
                paramText = "[Kiểu " + variantIdx + "/" + variantCount
                    + (diff.isEmpty() ? "" : " | " + diff) + "] " + baseParamLabel;
            }
            return new ApiDisplayRow(
                false,
                true,
                false,
                diffFromBaseline,
                normalizeApiGroupPath(c),
                "    ↳ " + c.displayName(),
                c.capture.method,
                "    ↳ " + c.displayPath(),
                String.valueOf(c.capture.statusCode),
                paramText,
                c.getHits(),
                c.inExcel ? ("STT " + c.excelStt) : "-",
                c.notesSummary(),
                c.worstSeverity(),
                c.inExcel,
                c,
                Collections.singletonList(c)
            );
        }

        private static String canonicalParamKey(Set<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return "";
            }
            List<String> sorted = new ArrayList<>(keys);
            Collections.sort(sorted);
            return String.join(", ", sorted);
        }

        private static String formatHeaderParamsSummary(Map<String, List<ApiCall>> byParamSig, ApiCall first) {
            if (byParamSig.size() <= 1) {
                int cnt = first.capture.paramCount();
                if (cnt == 0) {
                    return "Cùng param: 0 param";
                }
                return "Cùng param: " + cnt + " (" + first.capture.paramSignature() + ")";
            }
            StringBuilder variants = new StringBuilder();
            int idx = 0;
            for (String sig : byParamSig.keySet()) {
                if (idx > 0) {
                    variants.append(" | ");
                }
                variants.append(sig.isEmpty() ? "(rỗng)" : sig);
                idx++;
                if (idx >= 4 && byParamSig.size() > 4) {
                    variants.append(" | ...");
                    break;
                }
            }
            return byParamSig.size() + " kiểu param: [" + variants + "]";
        }

        private static String describeKeyDiffCompact(Set<String> now, Set<String> base) {
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            for (String k : now) {
                if (!base.contains(k)) {
                    added.add("+" + k);
                }
            }
            for (String k : base) {
                if (!now.contains(k)) {
                    removed.add("-" + k);
                }
            }
            List<String> all = new ArrayList<>(added.size() + removed.size());
            all.addAll(added);
            all.addAll(removed);
            return String.join(", ", all);
        }

        private static String joinInts(Set<Integer> nums) {
            if (nums.isEmpty()) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            for (Integer n : nums) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(n);
            }
            return sb.toString();
        }

        public ApiDisplayRow getRowAt(int modelRow) {
            return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
        }

        public ApiCall getCallAt(int modelRow) {
            ApiDisplayRow r = getRowAt(modelRow);
            return r != null ? r.call : null;
        }

        public boolean isGroupHeaderRow(int modelRow) {
            ApiDisplayRow r = getRowAt(modelRow);
            return r != null && r.isGroupHeader;
        }

        public List<ApiCall> getCallsAt(int modelRow) {
            ApiDisplayRow r = getRowAt(modelRow);
            return r != null ? r.groupedCalls : Collections.emptyList();
        }

        public void togglePathGroupExpanded(int modelRow) {
            ApiDisplayRow r = getRowAt(modelRow);
            if (r == null || !r.isGroupHeader) {
                return;
            }
            if (!expandedPaths.add(r.pathKey)) {
                expandedPaths.remove(r.pathKey);
            }
            rebuildDisplayRows();
            fireTableDataChanged();
        }

        public void expandAllPathGroups() {
            boolean changed = false;
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ApiCall c : filteredCalls) {
                String k = normalizeApiGroupPath(c);
                counts.put(k, counts.getOrDefault(k, 0) + 1);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() >= 2 && expandedPaths.add(e.getKey())) {
                    changed = true;
                }
            }
            if (changed) {
                rebuildDisplayRows();
                fireTableDataChanged();
            }
        }

        public void collapseAllPathGroups() {
            if (!expandedPaths.isEmpty()) {
                expandedPaths.clear();
                rebuildDisplayRows();
                fireTableDataChanged();
            }
        }

        public void ensureCallVisible(ApiCall target) {
            if (target == null || !treeMode) {
                return;
            }
            if (findRowForCall(target) >= 0) {
                return;
            }
            String key = normalizeApiGroupPath(target);
            if (expandedPaths.add(key)) {
                rebuildDisplayRows();
                fireTableDataChanged();
            }
        }

        public int findRowForCall(ApiCall target) {
            if (target == null) {
                return -1;
            }
            for (int i = 0; i < rows.size(); i++) {
                ApiDisplayRow r = rows.get(i);
                if (r.call != null && r.call.groupId == target.groupId && r.call.index == target.index) {
                    return i;
                }
            }
            return -1;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (!treeMode && (col == 0 || col == 4 || col == 6)) {
                return Integer.class;
            }
            return Object.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            ApiDisplayRow r = rows.get(row);
            switch (col) {
                case 0:
                    if (r.isGroupHeader) {
                        return r.collapsed ? "▶" : "▼";
                    }
                    return r.call != null ? Integer.valueOf(r.call.index) : "";
                case 1: return r.titleDisplay;
                case 2: return r.methodDisplay;
                case 3: return r.pathDisplay;
                case 4:
                    if (!treeMode && !r.isGroupHeader && r.call != null) {
                        return Integer.valueOf(r.call.capture.statusCode);
                    }
                    return r.httpDisplay;
                case 5: return r.paramsDisplay;
                case 6: return Integer.valueOf(r.hits);
                case 7: return r.excelDisplay;
                case 8: return r.noteDisplay;
                default: return "";
            }
        }
    }

    public static final class AuditNotesTableModel extends AbstractTableModel {
        private static final String[] COLS = {
            "Mức độ", "Loại NOTE", "Tên Nhóm (Action Path)", "API #", "Method", "Endpoint / Path", "Chi tiết NOTE"
        };

        private final ActionGroupStore store;
        private List<AuditNote> visible = new ArrayList<>();
        private String severityFilter = "ALL"; // ALL | ERROR | WARN | INFO
        private String searchQuery = "";

        public AuditNotesTableModel(ActionGroupStore store) {
            this.store = store;
        }

        public void setFilter(String severityFilter, String searchQuery) {
            this.severityFilter = severityFilter != null ? severityFilter : "ALL";
            this.searchQuery = searchQuery != null ? searchQuery.trim().toLowerCase() : "";
            refresh();
        }

        public void refresh() {
            List<AuditNote> all = store != null ? store.snapshotNotes() : Collections.emptyList();
            List<AuditNote> out = new ArrayList<>(all.size());
            for (AuditNote n : all) {
                if (!"ALL".equals(severityFilter) && !n.severity.name().equals(severityFilter)) {
                    continue;
                }
                if (!searchQuery.isEmpty()) {
                    boolean hit = n.groupPath.toLowerCase().contains(searchQuery)
                        || n.endpoint.toLowerCase().contains(searchQuery)
                        || n.message.toLowerCase().contains(searchQuery)
                        || n.type.label.toLowerCase().contains(searchQuery)
                        || n.method.toLowerCase().contains(searchQuery);
                    if (!hit) {
                        continue;
                    }
                }
                out.add(n);
            }
            this.visible = out;
            fireTableDataChanged();
        }

        public AuditNote getNoteAt(int modelRow) {
            return modelRow >= 0 && modelRow < visible.size() ? visible.get(modelRow) : null;
        }

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            AuditNote n = visible.get(row);
            switch (col) {
                case 0: return n.severity.label;
                case 1: return n.type.label;
                case 2: return n.groupPath;
                case 3: return "G" + n.groupId + " #" + n.callIndex;
                case 4: return n.method;
                case 5: return n.endpoint;
                case 6: return n.message;
                default: return "";
            }
        }
    }

    public static final class GroupCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        public GroupCellRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 2 || column == 3 || column == 4 || column == 5)
                ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            String tooltip = null;
            if (table.getModel() instanceof ActionGroupsModel) {
                int mr = table.convertRowIndexToModel(row);
                ActionGroupsModel.GroupRow meta = ((ActionGroupsModel) table.getModel()).getRowAt(mr);
                if (meta != null) {
                    if (meta.isFolder) {
                        tooltip = meta.folderKey + " (" + meta.subtreeGroups.size() + " nhóm, " + meta.totalApis + " API) — Double-click hoặc bấm "
                            + (meta.collapsed ? "▶ để sổ" : "▼ để thu gọn");
                    } else if (meta.directGroup != null && meta.directGroup.actionPath.length() > 30) {
                        tooltip = meta.directGroup.actionPath;
                    }
                    if (!isSelected) {
                        Color bg = meta.isFolder
                            ? AuditTheme.cardSurface(dark)
                            : AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                        Color fg = table.getForeground();
                        if (!meta.isFolder && meta.depth > 0) {
                            bg = AuditTheme.blend(bg, AuditTheme.panelSurface(dark), 0.18f);
                        }
                        AuditNote.Severity w = meta.worstSeverity;
                        if (w != null && (w == AuditNote.Severity.ERROR || w == AuditNote.Severity.WARN)) {
                            bg = meta.isFolder
                                ? AuditTheme.blend(AuditTheme.cardSurface(dark), AuditTheme.severityRowTint(w, table.getBackground(), dark), 0.5f)
                                : AuditTheme.severityRowTint(w, table.getBackground(), dark);
                        }
                        if (column == 0) {
                            if (meta.isFolder || meta.depth == 0) {
                                fg = AuditTheme.accentForeground(dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else {
                                fg = AuditTheme.mutedForeground(dark);
                            }
                        } else if (column == 3 && meta.totalNotes > 0 && w != null) {
                            fg = AuditTheme.severityForeground(w, dark);
                            c.setFont(table.getFont().deriveFont(Font.BOLD));
                        } else if (column == 2 && meta.totalApis > 0) {
                            fg = AuditTheme.accentForeground(dark);
                            c.setFont(table.getFont().deriveFont(Font.BOLD));
                        } else if (column == 1) {
                            if (meta.isFolder) {
                                fg = AuditTheme.accentForeground(dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (meta.depth == 0) {
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            }
                        }
                        c.setBackground(bg);
                        c.setForeground(fg);
                    }
                }
            }
            if (c instanceof JLabel) {
                ((JLabel) c).setToolTipText(tooltip);
            }
            return c;
        }
    }

    public static final class GroupApiCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        public GroupApiCellRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 2 || column == 4 || column == 6 || column == 7)
                ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            String tooltip = null;
            if (table.getModel() instanceof GroupApisModel) {
                int mr = table.convertRowIndexToModel(row);
                GroupApisModel.ApiDisplayRow meta = ((GroupApisModel) table.getModel()).getRowAt(mr);
                if (meta != null) {
                    if (meta.isGroupHeader) {
                        tooltip = meta.pathKey + " (" + meta.groupedCalls.size() + " API — " + meta.paramsDisplay + ") — bấm "
                            + (meta.collapsed ? "▶ để sổ chi tiết" : "▼ để thu gọn");
                    } else if (meta.call != null && value != null) {
                        String s = String.valueOf(value);
                        if (s.length() > 30 || column == 5) {
                            tooltip = column == 1 ? meta.call.displayName()
                                : (column == 3 ? meta.call.displayPath() : s);
                        }
                    }
                    if (!isSelected) {
                        Color bg = meta.isGroupHeader
                            ? AuditTheme.cardSurface(dark)
                            : AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                        Color fg = table.getForeground();
                        if (!meta.isGroupHeader && meta.indentedChild) {
                            bg = AuditTheme.blend(bg, AuditTheme.panelSurface(dark), 0.22f);
                        }
                        AuditNote.Severity w = meta.worstSeverity;
                        if (w == AuditNote.Severity.ERROR || w == AuditNote.Severity.WARN) {
                            bg = meta.isGroupHeader
                                ? AuditTheme.blend(AuditTheme.cardSurface(dark), AuditTheme.severityRowTint(w, table.getBackground(), dark), 0.45f)
                                : AuditTheme.severityRowTint(w, table.getBackground(), dark);
                        }

                        if (meta.isGroupHeader) {
                            if (column == 0 || column == 1 || column == 6) {
                                fg = AuditTheme.accentForeground(dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 2) {
                                if (meta.methodDisplay.indexOf(',') < 0 && !meta.methodDisplay.isEmpty()) {
                                    fg = AuditTheme.methodForeground(meta.methodDisplay, dark);
                                }
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 3) {
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 4 && !meta.groupedCalls.isEmpty() && meta.httpDisplay.indexOf(',') < 0) {
                                fg = AuditTheme.statusCodeForeground(meta.groupedCalls.get(0).capture.statusCode, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 5) {
                                fg = meta.diffParamVariant
                                    ? AuditTheme.severityForeground(AuditNote.Severity.WARN, dark)
                                    : AuditTheme.mutedForeground(dark);
                                if (meta.diffParamVariant) {
                                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                                }
                            } else if (column == 7 && meta.anyExcel) {
                                fg = AuditTheme.okForeground(dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 8 && w != null) {
                                fg = AuditTheme.severityForeground(w, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            }
                        } else if (meta.call != null) {
                            ApiCall call = meta.call;
                            if (column == 2) {
                                fg = AuditTheme.methodForeground(call.capture.method, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 4) {
                                fg = AuditTheme.statusCodeForeground(call.capture.statusCode, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 5 && meta.diffParamVariant) {
                                fg = AuditTheme.severityForeground(AuditNote.Severity.WARN, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 7 && call.inExcel) {
                                fg = AuditTheme.okForeground(dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            } else if (column == 8 && w != null) {
                                fg = AuditTheme.severityForeground(w, dark);
                                c.setFont(table.getFont().deriveFont(Font.BOLD));
                            }
                        }
                        c.setBackground(bg);
                        c.setForeground(fg);
                    }
                }
            }
            if (c instanceof JLabel) {
                ((JLabel) c).setToolTipText(tooltip);
            }
            return c;
        }
    }

    public static final class AuditNoteCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        public AuditNoteCellRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 3 || column == 4)
                ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected && table.getModel() instanceof AuditNotesTableModel) {
                int mr = table.convertRowIndexToModel(row);
                AuditNote note = ((AuditNotesTableModel) table.getModel()).getNoteAt(mr);
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = table.getForeground();
                if (note != null) {
                    bg = AuditTheme.severityRowTint(note.severity, table.getBackground(), dark);
                    if (column == 0 || column == 1 || column == 6) {
                        fg = AuditTheme.severityForeground(note.severity, dark);
                        if (column == 0 || column == 1) {
                            c.setFont(table.getFont().deriveFont(Font.BOLD));
                        }
                    } else if (column == 4) {
                        fg = AuditTheme.methodForeground(note.method, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }
}
