package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.MiniJson;

import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Shared Swing table utilities for Burp-grade UX:
 * 1) Stable selection + scroll-offset preservation across fireTableDataChanged() & RowSorter updates
 * 2) Zebra/highlight-aware Boolean checkbox cell renderer
 * 3) Right-click header Column Manager (show/hide columns, 14" Compact vs Wide preset, persisted widths)
 */
public final class TableSelectionPreserver {

    private TableSelectionPreserver() {
    }

    public static <K> List<K> captureSelectedKeys(JTable table, IntFunction<K> keyByModelRow) {
        List<K> keys = new ArrayList<>();
        if (table == null || keyByModelRow == null) {
            return keys;
        }
        int[] viewRows = table.getSelectedRows();
        for (int vr : viewRows) {
            if (vr >= 0 && vr < table.getRowCount()) {
                int mr = table.convertRowIndexToModel(vr);
                K k = keyByModelRow.apply(mr);
                if (k != null) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    public static Point captureScrollPosition(JTable table) {
        if (table == null) {
            return null;
        }
        Component p = table.getParent();
        if (p instanceof JViewport) {
            return ((JViewport) p).getViewPosition();
        }
        return null;
    }

    /**
     * Runs {@code tableUpdate}, restores matching selected rows by stable key (preserving multi-selection)
     * without re-triggering selection side effects if {@code restoringFlag != null}, and restores
     * the scroll viewport position so live crawl updates never jump the user's table view.
     */
    public static <K> void preserveAroundUpdate(
        JTable table,
        IntFunction<K> keyByModelRow,
        boolean[] restoringFlag,
        Runnable tableUpdate
    ) {
        if (table == null) {
            if (tableUpdate != null) {
                tableUpdate.run();
            }
            return;
        }
        List<K> prevKeys = captureSelectedKeys(table, keyByModelRow);
        Point prevScroll = captureScrollPosition(table);

        if (restoringFlag != null && restoringFlag.length > 0) {
            restoringFlag[0] = true;
        }
        try {
            if (tableUpdate != null) {
                tableUpdate.run();
            }
            if (!prevKeys.isEmpty() && table.getModel() != null) {
                Set<K> wanted = new LinkedHashSet<>(prevKeys);
                table.clearSelection();
                int rowCount = table.getModel().getRowCount();
                for (int mr = 0; mr < rowCount; mr++) {
                    K k = keyByModelRow.apply(mr);
                    if (k != null && wanted.contains(k)) {
                        int vr = table.convertRowIndexToView(mr);
                        if (vr >= 0 && vr < table.getRowCount()) {
                            table.addRowSelectionInterval(vr, vr);
                        }
                    }
                }
            }
        } finally {
            if (restoringFlag != null && restoringFlag.length > 0) {
                restoringFlag[0] = false;
            }
        }
        if (prevScroll != null) {
            SwingUtilities.invokeLater(() -> {
                Component p = table.getParent();
                if (p instanceof JViewport) {
                    JViewport vp = (JViewport) p;
                    int maxY = Math.max(0, table.getHeight() - vp.getExtentSize().height);
                    int maxX = Math.max(0, table.getWidth() - vp.getExtentSize().width);
                    vp.setViewPosition(new Point(
                        Math.min(prevScroll.x, maxX),
                        Math.min(prevScroll.y, maxY)
                    ));
                }
            });
        }
    }

    public static void scrollRowToVisible(JTable table, int viewRow) {
        if (table != null && viewRow >= 0 && viewRow < table.getRowCount()) {
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
        }
    }

    /**
     * Checkbox cell renderer that inherits zebra / severity / highlight row backgrounds
     * instead of painting flat default Swing white/gray.
     */
    public static final class BooleanCellRenderer extends JCheckBox implements TableCellRenderer {
        private final MontoyaApi api;
        private final RowColorResolver colorResolver;

        @FunctionalInterface
        public interface RowColorResolver {
            Color resolveRowBackground(JTable table, int viewRow, int modelRow, boolean dark);
        }

        public BooleanCellRenderer(MontoyaApi api) {
            this(api, null);
        }

        public BooleanCellRenderer(MontoyaApi api, RowColorResolver colorResolver) {
            this.api = api;
            this.colorResolver = colorResolver;
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            setSelected(Boolean.TRUE.equals(value));
            setEnabled(table.isCellEditable(row, column));
            boolean dark = AuditTheme.isDark(api, table);
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                int mr = table.convertRowIndexToModel(row);
                Color bg = colorResolver != null
                    ? colorResolver.resolveRowBackground(table, row, mr, dark)
                    : AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                setBackground(bg != null ? bg : AuditTheme.zebraRowBackground(table.getBackground(), row, dark));
                setForeground(AuditTheme.textPrimary(dark));
            }
            return this;
        }
    }

    /**
     * Manages column visibility, preferred widths, 14" Compact vs Wide presets,
     * and persistence inside {@link CrawlConfig#getColumnPrefsJson()}.
     */
    public static final class ColumnManager {
        private final JTable table;
        private final String tableId;
        private final CrawlConfig config;
        private final Runnable onPersist;
        private final int[] defaultWidths;
        private final int[] compactHiddenModelCols;
        private final List<TableColumn> allColumns = new ArrayList<>();
        private final Set<Integer> hiddenModelCols = new LinkedHashSet<>();

        public ColumnManager(
            JTable table,
            String tableId,
            CrawlConfig config,
            Runnable onPersist,
            int[] defaultWidths,
            int... compactHiddenModelCols
        ) {
            this.table = table;
            this.tableId = tableId;
            this.config = config;
            this.onPersist = onPersist;
            this.defaultWidths = defaultWidths != null ? defaultWidths.clone() : new int[0];
            this.compactHiddenModelCols = compactHiddenModelCols != null ? compactHiddenModelCols.clone() : new int[0];

            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            snapshotBaseColumns();
            applyDefaultWidths();
            loadFromConfig();
            attachHeaderMenu();
        }

        public void refreshAfterStructureChange() {
            snapshotBaseColumns();
            applyDefaultWidths();
            applyVisibilityAndWidths(null);
        }

        private void snapshotBaseColumns() {
            allColumns.clear();
            TableColumnModel cm = table.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                allColumns.add(cm.getColumn(i));
            }
        }

        private void applyDefaultWidths() {
            for (int i = 0; i < allColumns.size() && i < defaultWidths.length; i++) {
                allColumns.get(i).setPreferredWidth(defaultWidths[i]);
            }
        }

        private void attachHeaderMenu() {
            if (table.getTableHeader() == null) {
                return;
            }
            table.getTableHeader().addMouseListener(new MouseAdapter() {
                private void maybeShow(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        buildPopup().show(e.getComponent(), e.getX(), e.getY());
                    }
                }
                @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
                @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            });
        }

        private JPopupMenu buildPopup() {
            JPopupMenu menu = new JPopupMenu();
            for (TableColumn tc : allColumns) {
                int modelIdx = tc.getModelIndex();
                String title = String.valueOf(tc.getHeaderValue());
                boolean visible = !hiddenModelCols.contains(modelIdx);
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(title, visible);
                item.addActionListener(e -> {
                    if (item.isSelected()) {
                        hiddenModelCols.remove(modelIdx);
                    } else if (visibleCount() > 1) {
                        hiddenModelCols.add(modelIdx);
                    }
                    applyVisibilityAndWidths(null);
                    saveToConfig();
                });
                menu.add(item);
            }
            menu.addSeparator();

            if (compactHiddenModelCols.length > 0) {
                JMenuItem miCompact = new JMenuItem("Chế độ Gọn 14\" (ẩn cột phụ)");
                miCompact.addActionListener(e -> applyCompactPreset(true));
                menu.add(miCompact);
            }
            JMenuItem miWide = new JMenuItem("Hiển thị Đầy đủ tất cả cột");
            miWide.addActionListener(e -> applyCompactPreset(false));
            menu.add(miWide);

            JMenuItem miAutoFit = new JMenuItem("Tự căn độ rộng cột vừa nội dung");
            miAutoFit.addActionListener(e -> autoFitColumns());
            menu.add(miAutoFit);

            return menu;
        }

        public void applyCompactPreset(boolean compact) {
            hiddenModelCols.clear();
            if (compact) {
                for (int mc : compactHiddenModelCols) {
                    hiddenModelCols.add(mc);
                }
            }
            applyVisibilityAndWidths(null);
            saveToConfig();
        }

        public void autoFitColumns() {
            TableColumnModel cm = table.getColumnModel();
            int sampleRows = Math.min(table.getRowCount(), 60);
            for (int vc = 0; vc < cm.getColumnCount(); vc++) {
                TableColumn col = cm.getColumn(vc);
                int maxW = 52;
                TableCellRenderer hr = table.getTableHeader() != null ? table.getTableHeader().getDefaultRenderer() : null;
                if (hr != null) {
                    Component hc = hr.getTableCellRendererComponent(table, col.getHeaderValue(), false, false, -1, vc);
                    maxW = Math.max(maxW, hc.getPreferredSize().width + 18);
                }
                for (int vr = 0; vr < sampleRows; vr++) {
                    TableCellRenderer cr = table.getCellRenderer(vr, vc);
                    Component cc = table.prepareRenderer(cr, vr, vc);
                    maxW = Math.max(maxW, Math.min(440, cc.getPreferredSize().width + 16));
                }
                col.setPreferredWidth(maxW);
            }
            saveToConfig();
        }

        private int visibleCount() {
            return Math.max(0, allColumns.size() - hiddenModelCols.size());
        }

        private void applyVisibilityAndWidths(Map<Integer, Integer> widthMap) {
            TableColumnModel cm = table.getColumnModel();
            while (cm.getColumnCount() > 0) {
                cm.removeColumn(cm.getColumn(0));
            }
            for (TableColumn tc : allColumns) {
                int mc = tc.getModelIndex();
                if (widthMap != null && widthMap.containsKey(mc)) {
                    tc.setPreferredWidth(Math.max(36, Math.min(720, widthMap.get(mc))));
                }
                if (!hiddenModelCols.contains(mc)) {
                    cm.addColumn(tc);
                }
            }
            table.revalidate();
            table.repaint();
        }

        @SuppressWarnings("unchecked")
        private void loadFromConfig() {
            if (config == null || config.getColumnPrefsJson().isEmpty()) {
                return;
            }
            try {
                Map<String, Object> root = MiniJson.parseObject(config.getColumnPrefsJson());
                Object entryObj = root.get(tableId);
                if (!(entryObj instanceof Map<?, ?>)) {
                    return;
                }
                Map<String, Object> entry = (Map<String, Object>) entryObj;
                hiddenModelCols.clear();
                Object hidObj = entry.get("hidden");
                if (hidObj instanceof List<?>) {
                    for (Object o : (List<?>) hidObj) {
                        if (o instanceof Number) {
                            hiddenModelCols.add(((Number) o).intValue());
                        }
                    }
                }
                Map<Integer, Integer> widths = new LinkedHashMap<>();
                Object wObj = entry.get("widths");
                if (wObj instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> en : ((Map<?, ?>) wObj).entrySet()) {
                        if (en.getValue() instanceof Number) {
                            try {
                                widths.put(Integer.parseInt(String.valueOf(en.getKey())), ((Number) en.getValue()).intValue());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                if (visibleCount() == 0) {
                    hiddenModelCols.clear();
                }
                applyVisibilityAndWidths(widths);
            } catch (Exception ignored) {
            }
        }

        public void saveToConfig() {
            if (config == null) {
                return;
            }
            try {
                Map<String, Object> root = config.getColumnPrefsJson().isEmpty()
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(MiniJson.parseObject(config.getColumnPrefsJson()));
                Map<String, Object> entry = new LinkedHashMap<>();
                List<Integer> hid = new ArrayList<>(hiddenModelCols);
                entry.put("hidden", hid);
                Map<String, Integer> widths = new LinkedHashMap<>();
                for (TableColumn tc : allColumns) {
                    widths.put(String.valueOf(tc.getModelIndex()), tc.getPreferredWidth());
                }
                entry.put("widths", widths);
                root.put(tableId, entry);
                config.setColumnPrefsJson(MiniJson.stringify(root));
                if (onPersist != null) {
                    onPersist.run();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
