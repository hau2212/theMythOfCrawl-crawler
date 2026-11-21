package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Column width and visibility persistence manager with right-click header column chooser
 * (LAW 1–4 compliant; decoupled from backend config via load/save callbacks).
 */
public final class ColumnMemory {

    private static final Map<String, String> SESSION_FALLBACK_STORE = new ConcurrentHashMap<>();

    private final JTable table;
    private final String tableKey;
    private final Supplier<String> loadAllPrefs;
    private final Consumer<String> saveAllPrefs;
    private final int[] defaultWidths;
    private final List<TableColumn> allColumns = new ArrayList<>();
    private final boolean[] hiddenCols;
    private boolean applying = false;

    public ColumnMemory(JTable table, String tableKey, int... defaultWidths) {
        this(table, tableKey, () -> SESSION_FALLBACK_STORE.getOrDefault(tableKey, ""),
            val -> SESSION_FALLBACK_STORE.put(tableKey, val != null ? val : ""), defaultWidths);
    }

    public ColumnMemory(
        JTable table,
        String tableKey,
        Supplier<String> loadAllPrefs,
        Consumer<String> saveAllPrefs,
        int... defaultWidths
    ) {
        this.table = table;
        this.tableKey = tableKey != null ? tableKey : "default";
        this.loadAllPrefs = loadAllPrefs;
        this.saveAllPrefs = saveAllPrefs;
        this.defaultWidths = defaultWidths != null ? defaultWidths.clone() : new int[0];

        int colCount = table.getColumnModel().getColumnCount();
        this.hiddenCols = new boolean[colCount];
        for (int i = 0; i < colCount; i++) {
            TableColumn tc = table.getColumnModel().getColumn(i);
            allColumns.add(tc);
            if (i < this.defaultWidths.length && this.defaultWidths[i] > 0) {
                tc.setPreferredWidth(this.defaultWidths[i]);
            }
        }

        restore();
        installListeners();
    }

    private void installListeners() {
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override public void columnAdded(TableColumnModelEvent e) {}
            @Override public void columnRemoved(TableColumnModelEvent e) {}
            @Override public void columnMoved(TableColumnModelEvent e) {}
            @Override public void columnSelectionChanged(ListSelectionEvent e) {}

            @Override
            public void columnMarginChanged(ChangeEvent e) {
                if (!applying) {
                    persist();
                }
            }
        });

        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.addMouseListener(new MouseAdapter() {
                private void maybeMenu(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showColumnMenu(header, e.getX(), e.getY());
                    }
                }
                @Override public void mousePressed(MouseEvent e) { maybeMenu(e); }
                @Override public void mouseReleased(MouseEvent e) { maybeMenu(e); }
            });
        }
    }

    private void showColumnMenu(JTableHeader header, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(Theme.surface());
        for (int i = 0; i < allColumns.size(); i++) {
            final int modelIdx = i;
            String title = String.valueOf(allColumns.get(i).getHeaderValue());
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(title, !hiddenCols[i]);
            item.setFont(FontManager.uiRegular());
            item.addActionListener(e -> setColumnVisible(modelIdx, item.isSelected()));
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem reset = new JMenuItem("Khôi phục độ rộng mặc định");
        reset.setFont(FontManager.uiRegular());
        reset.addActionListener(e -> resetDefaults());
        menu.add(reset);
        menu.show(header, x, y);
    }

    public void setColumnVisible(int modelIndex, boolean visible) {
        if (modelIndex < 0 || modelIndex >= allColumns.size()) {
            return;
        }
        if (!visible && visibleColumnCount() <= 1) {
            return;
        }
        hiddenCols[modelIndex] = !visible;
        rebuildVisibleColumns();
        persist();
    }

    public void resetDefaults() {
        applying = true;
        try {
            for (int i = 0; i < allColumns.size(); i++) {
                hiddenCols[i] = false;
                if (i < defaultWidths.length && defaultWidths[i] > 0) {
                    allColumns.get(i).setPreferredWidth(defaultWidths[i]);
                    allColumns.get(i).setWidth(defaultWidths[i]);
                }
            }
            rebuildVisibleColumns();
        } finally {
            applying = false;
        }
        persist();
    }

    private int visibleColumnCount() {
        int c = 0;
        for (boolean h : hiddenCols) {
            if (!h) {
                c++;
            }
        }
        return c;
    }

    private void rebuildVisibleColumns() {
        applying = true;
        try {
            TableColumnModel cm = table.getColumnModel();
            while (cm.getColumnCount() > 0) {
                cm.removeColumn(cm.getColumn(0));
            }
            for (int i = 0; i < allColumns.size(); i++) {
                if (!hiddenCols[i]) {
                    cm.addColumn(allColumns.get(i));
                }
            }
        } finally {
            applying = false;
        }
    }

    private void restore() {
        if (loadAllPrefs == null) {
            return;
        }
        String raw = loadAllPrefs.get();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        Map<String, String> byTable = parseTableMap(raw);
        String entry = byTable.get(tableKey);
        if (entry == null || entry.isEmpty()) {
            return;
        }
        applying = true;
        try {
            for (String part : entry.split(",")) {
                String[] kv = part.split(":");
                if (kv.length >= 2) {
                    try {
                        int idx = Integer.parseInt(kv[0].trim());
                        int width = Integer.parseInt(kv[1].trim());
                        boolean vis = kv.length < 3 || !"0".equals(kv[2].trim());
                        if (idx >= 0 && idx < allColumns.size()) {
                            hiddenCols[idx] = !vis;
                            if (width >= 24 && width <= 1600) {
                                allColumns.get(idx).setPreferredWidth(width);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (visibleColumnCount() == 0 && hiddenCols.length > 0) {
                hiddenCols[0] = false;
            }
            rebuildVisibleColumns();
        } finally {
            applying = false;
        }
    }

    public void persist() {
        if (saveAllPrefs == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < allColumns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            int w = Math.max(24, allColumns.get(i).getWidth() > 0
                ? allColumns.get(i).getWidth()
                : allColumns.get(i).getPreferredWidth());
            sb.append(i).append(':').append(w).append(':').append(hiddenCols[i] ? '0' : '1');
        }

        String raw = loadAllPrefs != null ? loadAllPrefs.get() : "";
        Map<String, String> byTable = parseTableMap(raw);
        byTable.put(tableKey, sb.toString());
        saveAllPrefs.accept(serializeTableMap(byTable));
    }

    private static Map<String, String> parseTableMap(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return map;
        }
        for (String line : raw.split(";")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (!k.isEmpty()) {
                    map.put(k, v);
                }
            }
        }
        return map;
    }

    private static String serializeTableMap(Map<String, String> map) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> en : map.entrySet()) {
            if (!first) {
                out.append(';');
            }
            first = false;
            out.append(en.getKey()).append('=').append(en.getValue());
        }
        return out.toString();
    }
}
