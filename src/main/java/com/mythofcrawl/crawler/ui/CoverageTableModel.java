package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.excel.ApiSpecEntry;
import com.mythofcrawl.crawler.excel.ExcelSpecMatcher;
import com.mythofcrawl.crawler.model.AuditNote;
import com.mythofcrawl.crawler.model.CapturedApiEntry;
import com.mythofcrawl.crawler.model.CrawlState;

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
 * Swing table models and visual status renderers for:
 * 1) Excel Checklist Coverage Table (STT, Status, Method, Path API, Action Path, Params, HTTP, Notes)
 * 2) Extra Captures Table (when Strict Excel Mode is unchecked)
 * 3) Danger Guard Skipped Actions Table
 */
public final class CoverageTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
        "STT", "Trạng thái", "Method", "Path API (Excel)", "Action Path (UI)", "Params", "HTTP", "Ghi chú Tham số"
    };

    private List<ApiSpecEntry> allEntries = Collections.emptyList();
    private List<ApiSpecEntry> visibleEntries = new ArrayList<>();
    private String statusFilter = "ALL"; // ALL | MATCHED | MISSING | NOTES
    private String searchQuery = "";

    public void setMatcher(ExcelSpecMatcher matcher) {
        this.allEntries = matcher != null ? matcher.getEntries() : Collections.emptyList();
        applyFilter();
    }

    public void setFilter(String statusFilter, String searchQuery) {
        this.statusFilter = statusFilter != null ? statusFilter : "ALL";
        this.searchQuery = searchQuery != null ? searchQuery.trim().toLowerCase() : "";
        applyFilter();
    }

    public void refreshData() {
        applyFilter();
    }

    private void applyFilter() {
        List<ApiSpecEntry> filtered = new ArrayList<>();
        for (ApiSpecEntry entry : allEntries) {
            boolean matched = entry.isMatched();
            boolean hasNotes = !entry.getParamNotes().isEmpty();
            if ("MATCHED".equals(statusFilter) && !matched) {
                continue;
            }
            if ("MISSING".equals(statusFilter) && matched) {
                continue;
            }
            if ("NOTES".equals(statusFilter) && !hasNotes) {
                continue;
            }
            if (!searchQuery.isEmpty()) {
                CapturedApiEntry cap = entry.getPrimaryCapture();
                String actionPath = cap != null ? cap.actionPath.toLowerCase() : "";
                String url = cap != null ? cap.fullUrl.toLowerCase() : "";
                String notes = entry.getNotesSummary().toLowerCase();
                boolean hit = entry.stt.toLowerCase().contains(searchQuery)
                    || entry.displayMethod.toLowerCase().contains(searchQuery)
                    || entry.rawSpecPath.toLowerCase().contains(searchQuery)
                    || actionPath.contains(searchQuery)
                    || url.contains(searchQuery)
                    || notes.contains(searchQuery);
                if (!hit) {
                    continue;
                }
            }
            filtered.add(entry);
        }
        this.visibleEntries = filtered;
        fireTableDataChanged();
    }

    public ApiSpecEntry getEntryAt(int modelRow) {
        if (modelRow >= 0 && modelRow < visibleEntries.size()) {
            return visibleEntries.get(modelRow);
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return visibleEntries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ApiSpecEntry entry = visibleEntries.get(rowIndex);
        CapturedApiEntry cap = entry.getPrimaryCapture();
        switch (columnIndex) {
            case 0:
                return entry.stt;
            case 1:
                if (cap == null) {
                    return "MISSING";
                }
                return entry.hasParamDiffNote() ? "MATCHED (!)" : "MATCHED";
            case 2:
                return cap != null ? cap.method : entry.displayMethod;
            case 3:
                return entry.rawSpecPath;
            case 4:
                return cap != null ? cap.actionPath : "-";
            case 5:
                if (cap == null) {
                    return "-";
                }
                int variants = entry.getAlternateVariants().size();
                return variants > 0
                    ? cap.paramCount() + " (+ " + variants + " var)"
                    : String.valueOf(cap.paramCount());
            case 6:
                return cap != null ? String.valueOf(cap.statusCode) : "-";
            case 7:
                String summary = entry.getNotesSummary();
                if (!summary.isEmpty()) {
                    return summary;
                }
                if (cap == null && !entry.getJsHints().isEmpty()) {
                    return "[JS Hint] " + String.join(", ", entry.getJsHints());
                }
                return "";
            default:
                return "";
        }
    }

    /**
     * Highlights MATCHED, PARAM DIFF NOTE, and MISSING rows with calm semantic colors on Light & Dark Burp themes.
     */
    public static final class CoverageCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;

        public CoverageCellRenderer() {
            this(null);
        }

        public CoverageCellRenderer(MontoyaApi api) {
            this.api = api;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == 0 || column == 1 || column == 2 || column == 5 || column == 6)
                ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected && table.getModel() instanceof CoverageTableModel) {
                int modelRow = table.convertRowIndexToModel(row);
                ApiSpecEntry entry = ((CoverageTableModel) table.getModel()).getEntryAt(modelRow);
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = table.getForeground();
                if (entry != null) {
                    CapturedApiEntry cap = entry.getPrimaryCapture();
                    if (!entry.isMatched()) {
                        if (column == 1 || column == 3) {
                            fg = AuditTheme.mutedForeground(dark);
                        } else if (column == 7 && !entry.getJsHints().isEmpty()) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.INFO, dark);
                        }
                    } else if (entry.hasParamDiffNote()) {
                        bg = AuditTheme.severityRowTint(AuditNote.Severity.WARN, table.getBackground(), dark);
                        if (column == 1 || column == 7) {
                            fg = AuditTheme.severityForeground(AuditNote.Severity.WARN, dark);
                            c.setFont(table.getFont().deriveFont(Font.BOLD));
                        }
                    } else if (column == 1) {
                        fg = AuditTheme.okForeground(dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    }

                    if (column == 2) {
                        fg = AuditTheme.methodForeground(entry.displayMethod, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } else if (column == 6 && cap != null) {
                        fg = AuditTheme.statusCodeForeground(cap.statusCode, dark);
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

    /**
     * Calm zebra + method/status renderer for auxiliary tables (Extra captures, Skipped actions).
     */
    public static final class SimpleAuditCellRenderer extends DefaultTableCellRenderer {
        private final MontoyaApi api;
        private final int methodCol;
        private final int statusCol;
        private final int centerCol;

        public SimpleAuditCellRenderer(MontoyaApi api, int methodCol, int statusCol, int centerCol) {
            this.api = api;
            this.methodCol = methodCol;
            this.statusCol = statusCol;
            this.centerCol = centerCol;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment((column == methodCol || column == statusCol || column == centerCol)
                ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected) {
                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = table.getForeground();
                String s = value != null ? String.valueOf(value) : "";
                if (column == methodCol) {
                    fg = AuditTheme.methodForeground(s, dark);
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                } else if (column == statusCol) {
                    try {
                        int st = Integer.parseInt(s.trim());
                        fg = AuditTheme.statusCodeForeground(st, dark);
                        c.setFont(table.getFont().deriveFont(Font.BOLD));
                    } catch (NumberFormatException ignored) {
                    }
                }
                c.setBackground(bg);
                c.setForeground(fg);
            }
            if (c instanceof JLabel && value != null) {
                String s = String.valueOf(value);
                ((JLabel) c).setToolTipText(s.length() > 40 ? s : null);
            }
            return c;
        }
    }

    /**
     * Table model for non-Excel traffic captured when "Strict Excel Mode" is toggled off.
     */
    public static final class ExtraCaptureTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Method", "URL / Path", "Action Path (UI)", "Params", "HTTP"};
        private List<CapturedApiEntry> rows = Collections.emptyList();

        public void setRows(List<CapturedApiEntry> rows) {
            this.rows = rows != null ? rows : Collections.emptyList();
            fireTableDataChanged();
        }

        public CapturedApiEntry getRow(int idx) {
            return idx >= 0 && idx < rows.size() ? rows.get(idx) : null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int col) {
            return COLS[col];
        }

        @Override
        public Object getValueAt(int row, int col) {
            CapturedApiEntry e = rows.get(row);
            switch (col) {
                case 0: return e.method;
                case 1: return e.requestPathWithQuery;
                case 2: return e.actionPath;
                case 3: return String.valueOf(e.paramCount());
                case 4: return String.valueOf(e.statusCode);
                default: return "";
            }
        }
    }

    /**
     * Table model for Danger Guard skipped UI actions (Logout, Delete, Payment, etc.).
     */
    public static final class SkippedActionTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Action Path (UI)", "Từ khóa né", "Element", "Page URL"};
        private List<CrawlState.SkippedAction> rows = Collections.emptyList();

        public void setRows(List<CrawlState.SkippedAction> rows) {
            this.rows = rows != null ? rows : Collections.emptyList();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int col) {
            return COLS[col];
        }

        @Override
        public Object getValueAt(int row, int col) {
            CrawlState.SkippedAction s = rows.get(row);
            switch (col) {
                case 0: return s.actionPath;
                case 1: return s.reason;
                case 2: return s.tag;
                case 3: return s.pageUrl;
                default: return "";
            }
        }
    }
}
