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
import java.util.Collections;
import java.util.List;

/**
 * Table models and calm semantic renderers for:
 * 1) ActionGroupsModel - UI actions / feature groups ("toppage > contact > button 'Send'")
 * 2) GroupApisModel - API calls belonging to the selected group (every API shows the group name + NOTE column)
 * 3) AuditNotesTableModel - Dedicated consolidated NOTE audit log across all groups & APIs
 */
public final class GroupTableModel {

    private GroupTableModel() {
    }

    public static final class ActionGroupsModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Tên Nhóm Chức Năng (Action Path)", "Số API", "NOTE", "Excel", "Lượt"
        };

        private final ActionGroupStore store;
        private List<ActionGroup> visible = new ArrayList<>();
        private String filterMode = "WITH_APIS"; // ALL | WITH_APIS | WITH_NOTES
        private String searchQuery = "";

        public ActionGroupsModel(ActionGroupStore store) {
            this.store = store;
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
            this.visible = out;
            fireTableDataChanged();
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

        public ActionGroup getGroupAt(int modelRow) {
            return modelRow >= 0 && modelRow < visible.size() ? visible.get(modelRow) : null;
        }

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 0 || col == 2 || col == 3 || col == 5) {
                return Integer.class;
            }
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            ActionGroup g = visible.get(row);
            switch (col) {
                case 0: return g.id;
                case 1: return g.actionPath;
                case 2: return g.callCount();
                case 3: return g.noteCount();
                case 4:
                    int ex = g.excelMatchedCount();
                    return ex > 0 ? String.valueOf(ex) : "-";
                case 5: return g.getTriggerCount();
                default: return "";
            }
        }
    }

    public static final class GroupApisModel extends AbstractTableModel {
        private static final String[] COLS = {
            "#", "Tên API (Tên Nhóm)", "Method", "Endpoint / Path", "HTTP", "Params", "Hits", "Excel", "NOTE"
        };

        private final ActionGroupStore store;
        private List<ApiCall> rows = new ArrayList<>();
        private String searchQuery = "";
        private boolean onlyWithNotes = false;

        public GroupApisModel(ActionGroupStore store) {
            this.store = store;
        }

        public void setGroupAndFilter(ActionGroup group, boolean showAllWhenNull, boolean onlyWithNotes, String searchQuery) {
            this.onlyWithNotes = onlyWithNotes;
            this.searchQuery = searchQuery != null ? searchQuery.trim().toLowerCase() : "";
            List<ApiCall> source = new ArrayList<>();
            if (group != null) {
                source.addAll(group.getCalls());
            } else if (showAllWhenNull && store != null) {
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
                        || c.capture.method.toLowerCase().contains(this.searchQuery)
                        || c.notesSummary().toLowerCase().contains(this.searchQuery)
                        || c.excelStt.toLowerCase().contains(this.searchQuery);
                    if (!hit) {
                        continue;
                    }
                }
                filtered.add(c);
            }
            this.rows = filtered;
            fireTableDataChanged();
        }

        public ApiCall getCallAt(int modelRow) {
            return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 0 || col == 4 || col == 6) {
                return Integer.class;
            }
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            ApiCall c = rows.get(row);
            switch (col) {
                case 0: return c.index;
                case 1: return c.displayName();
                case 2: return c.capture.method;
                case 3: return c.displayPath();
                case 4: return c.capture.statusCode;
                case 5: return c.capture.paramCount() + (c.capture.paramCount() > 0 ? " (" + c.capture.paramSignature() + ")" : "");
                case 6: return c.getHits();
                case 7: return c.inExcel ? ("STT " + c.excelStt) : "-";
                case 8: return c.notesSummary();
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

            if (!isSelected && table.getModel() instanceof ActionGroupsModel) {
                int mr = table.convertRowIndexToModel(row);
                ActionGroup g = ((ActionGroupsModel) table.getModel()).getGroupAt(mr);
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = table.getForeground();
                if (g != null) {
                    AuditNote.Severity w = g.worstSeverity();
                    if (w != null && (w == AuditNote.Severity.ERROR || w == AuditNote.Severity.WARN)) {
                        bg = AuditTheme.severityRowTint(w, table.getBackground(), dark);
                    }
                    if (column == 3 && g.noteCount() > 0 && w != null) {
                        fg = AuditTheme.severityForeground(w, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 2 && g.callCount() > 0) {
                        fg = AuditTheme.accentForeground(dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 1) {
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
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

            if (!isSelected && table.getModel() instanceof GroupApisModel) {
                int mr = table.convertRowIndexToModel(row);
                ApiCall call = ((GroupApisModel) table.getModel()).getCallAt(mr);
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = table.getForeground();
                if (call != null) {
                    AuditNote.Severity w = call.worstSeverity();
                    if (w == AuditNote.Severity.ERROR || w == AuditNote.Severity.WARN) {
                        bg = AuditTheme.severityRowTint(w, table.getBackground(), dark);
                    }
                    if (column == 2) {
                        fg = AuditTheme.methodForeground(call.capture.method, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 4) {
                        fg = AuditTheme.statusCodeForeground(call.capture.statusCode, dark);
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
            if (c instanceof JLabel && value != null) {
                String s = String.valueOf(value);
                ((JLabel) c).setToolTipText(s.length() > 36 ? s : null);
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
