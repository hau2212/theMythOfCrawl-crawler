package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.excel.SimpleXlsxReader;
import com.mythofcrawl.crawler.model.AuditNote;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal column-mapping dialog shown right after loading an Excel (.xlsx) or CSV file
 * when the user wants to review or customize which Sheet / Header Row / STT / Method / Path API
 * columns are imported. Supports Burp Light/Dark themes, suiteFrame anchoring, and live preview.
 */
public final class ExcelColumnMapperDialog extends JDialog {

    public static final class MappingResult {
        public final String sheetName;
        public final int headerRowIndex;
        public final int sttCol;
        public final int methodCol;
        public final int pathCol;

        public MappingResult(String sheetName, int headerRowIndex, int sttCol, int methodCol, int pathCol) {
            this.sheetName = sheetName;
            this.headerRowIndex = headerRowIndex;
            this.sttCol = sttCol;
            this.methodCol = methodCol;
            this.pathCol = pathCol;
        }
    }

    private final MontoyaApi api;
    private final SimpleXlsxReader.WorkbookData workbook;
    private final JComboBox<String> cbSheet;
    private final JSpinner spHeaderRow;
    private final JComboBox<String> cbStt;
    private final JComboBox<String> cbMethod;
    private final JComboBox<String> cbPath;
    private final PreviewTableModel previewModel;
    private final JTable previewTable;
    private final JScrollPane previewScroll;
    private final JPanel formPanel;
    private final JPanel bottomPanel;
    private final JButton btnCancel;
    private final JButton btnOk;
    private boolean suppressingEvents = false;
    private MappingResult result;

    private ExcelColumnMapperDialog(
        MontoyaApi api,
        Window owner,
        String fileName,
        SimpleXlsxReader.WorkbookData workbook
    ) {
        super(owner, "Ánh xạ cột Excel - " + fileName, ModalityType.APPLICATION_MODAL);
        this.api = api;
        this.workbook = workbook;

        setLayout(new BorderLayout(8, 8));
        setSize(860, 480);
        setMinimumSize(new java.awt.Dimension(600, 360));
        setLocationRelativeTo(owner);

        cbSheet = new JComboBox<>(workbook.getSheetNames().toArray(new String[0]));
        spHeaderRow = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
        cbStt = new JComboBox<>();
        cbMethod = new JComboBox<>();
        cbPath = new JComboBox<>();
        previewModel = new PreviewTableModel();
        previewTable = new JTable(previewModel);

        formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        formPanel.add(new JLabel("Sheet:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        formPanel.add(cbSheet, gc);
        gc.gridx = 2; gc.weightx = 0;
        formPanel.add(new JLabel("Dòng tiêu đề (Header #):"), gc);
        gc.gridx = 3; gc.weightx = 0.2;
        formPanel.add(spHeaderRow, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        formPanel.add(new JLabel("Cột STT:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        formPanel.add(cbStt, gc);
        gc.gridx = 2; gc.weightx = 0;
        formPanel.add(new JLabel("Cột Method:"), gc);
        gc.gridx = 3; gc.weightx = 0.5;
        formPanel.add(cbMethod, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        formPanel.add(new JLabel("Cột Path API:"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1.0;
        formPanel.add(cbPath, gc);

        add(formPanel, BorderLayout.NORTH);

        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        previewTable.setDefaultRenderer(Object.class, new PreviewCellRenderer());
        previewScroll = new JScrollPane(previewTable);
        add(previewScroll, BorderLayout.CENTER);

        bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnCancel = new JButton("Hủy");
        btnOk = new JButton("Nạp danh sách API");
        bottomPanel.add(btnCancel);
        bottomPanel.add(btnOk);
        add(bottomPanel, BorderLayout.SOUTH);

        cbSheet.addActionListener(e -> {
            if (!suppressingEvents) {
                refreshForCurrentSheet(true);
            }
        });
        spHeaderRow.addChangeListener(e -> {
            if (!suppressingEvents) {
                refreshForCurrentSheet(false);
            }
        });
        cbStt.addActionListener(e -> {
            if (!suppressingEvents) {
                previewTable.repaint();
            }
        });
        cbMethod.addActionListener(e -> {
            if (!suppressingEvents) {
                previewTable.repaint();
            }
        });
        cbPath.addActionListener(e -> {
            if (!suppressingEvents) {
                previewTable.repaint();
            }
        });

        btnCancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        btnOk.addActionListener(e -> {
            String sheet = (String) cbSheet.getSelectedItem();
            int headerRowIdx = Math.max(0, ((Number) spHeaderRow.getValue()).intValue() - 1);
            int sttCol = cbStt.getSelectedIndex() - 1; // index 0 = "(Tự đánh STT 1..N)"
            int methodCol = cbMethod.getSelectedIndex();
            int pathCol = cbPath.getSelectedIndex();
            if (pathCol < 0) {
                JOptionPane.showMessageDialog(this, "Vui lòng chọn cột chứa Path API (/api/...).", "Thiếu cột", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (methodCol < 0) {
                JOptionPane.showMessageDialog(this, "Vui lòng chọn cột chứa HTTP Method.", "Thiếu cột", JOptionPane.WARNING_MESSAGE);
                return;
            }
            result = new MappingResult(sheet, headerRowIdx, sttCol, methodCol, pathCol);
            dispose();
        });

        JRootPane rp = getRootPane();
        rp.setDefaultButton(btnOk);
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        rp.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                result = null;
                dispose();
            }
        });

        if (api != null) {
            try {
                api.userInterface().applyThemeToComponent(this);
            } catch (Exception ignored) {
            }
        }
        applyTheme();
        refreshForCurrentSheet(true);
    }

    private void applyTheme() {
        boolean dark = AuditTheme.isDark(api, this);
        getContentPane().setBackground(AuditTheme.canvasSurface(dark));
        formPanel.setBackground(AuditTheme.panelSurface(dark));
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        bottomPanel.setBackground(AuditTheme.panelSurface(dark));
        bottomPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AuditTheme.borderColor(dark)));

        AuditTheme.configureAuditTable(previewTable, api);
        AuditTheme.styleScrollPane(previewScroll, dark);
        AuditTheme.styleTitledBorder(previewScroll, "Xem trước 12 dòng đầu trong file (tô màu dòng tiêu đề & cột đã chọn)", dark);
        AuditTheme.styleNeutralActionButton(btnCancel, dark);
        AuditTheme.stylePrimaryActionButton(btnOk, AuditTheme.accentForeground(dark), dark);
    }

    private void refreshForCurrentSheet(boolean autoGuess) {
        String sheet = (String) cbSheet.getSelectedItem();
        if (sheet == null) {
            return;
        }
        int prevSttIdx = cbStt.getSelectedIndex();
        int prevMethodIdx = cbMethod.getSelectedIndex();
        int prevPathIdx = cbPath.getSelectedIndex();

        suppressingEvents = true;
        try {
            List<List<String>> rows = workbook.getRows(sheet);
            previewModel.setRows(rows);

            SimpleXlsxReader.ColumnGuess guess = autoGuess ? SimpleXlsxReader.guessColumns(rows) : null;
            if (autoGuess && guess != null) {
                int maxRows = Math.max(1, Math.min(50, rows.size()));
                int targetHeader1Based = Math.max(1, Math.min(maxRows, guess.headerRowIndex + 1));
                spHeaderRow.setValue(targetHeader1Based);
            }

            int headerIdx = rows.isEmpty()
                ? 0
                : Math.max(0, Math.min(rows.size() - 1, ((Number) spHeaderRow.getValue()).intValue() - 1));
            List<String> headerRow = (!rows.isEmpty() && headerIdx >= 0 && headerIdx < rows.size())
                ? rows.get(headerIdx) : new ArrayList<>();

            int colCount = headerRow.size();
            for (int r = 0; r < Math.min(rows.size(), 12); r++) {
                colCount = Math.max(colCount, rows.get(r).size());
            }

            cbStt.removeAllItems();
            cbMethod.removeAllItems();
            cbPath.removeAllItems();

            cbStt.addItem("(Tự đánh STT 1..N)");
            for (int c = 0; c < colCount; c++) {
                String title = c < headerRow.size() && !headerRow.get(c).trim().isEmpty()
                    ? headerRow.get(c).trim() : "(Trống)";
                String label = excelColLetters(c) + " : " + title;
                cbStt.addItem(label);
                cbMethod.addItem(label);
                cbPath.addItem(label);
            }

            if (colCount > 0) {
                if (autoGuess && guess != null) {
                    cbStt.setSelectedIndex(guess.sttCol >= 0 && guess.sttCol < colCount ? (guess.sttCol + 1) : 0);
                    cbMethod.setSelectedIndex(guess.methodCol >= 0 && guess.methodCol < colCount ? guess.methodCol : 0);
                    cbPath.setSelectedIndex(guess.pathCol >= 0 && guess.pathCol < colCount ? guess.pathCol : Math.min(1, colCount - 1));
                } else {
                    cbStt.setSelectedIndex(clampIndex(prevSttIdx, cbStt.getItemCount(), 0));
                    cbMethod.setSelectedIndex(clampIndex(prevMethodIdx, cbMethod.getItemCount(), 0));
                    cbPath.setSelectedIndex(clampIndex(prevPathIdx, cbPath.getItemCount(), Math.min(1, colCount - 1)));
                }
            }
        } finally {
            suppressingEvents = false;
        }
        previewTable.repaint();
    }

    private static int clampIndex(int idx, int count, int fallback) {
        if (count <= 0) {
            return -1;
        }
        if (idx >= 0 && idx < count) {
            return idx;
        }
        return Math.max(0, Math.min(count - 1, fallback));
    }

    public static MappingResult showDialog(Component parent, String fileName, SimpleXlsxReader.WorkbookData workbook) {
        return showDialog(null, parent, fileName, workbook);
    }

    public static MappingResult showDialog(
        MontoyaApi api,
        Component parent,
        String fileName,
        SimpleXlsxReader.WorkbookData workbook
    ) {
        Window owner = resolveOwner(api, parent);
        ExcelColumnMapperDialog dlg = new ExcelColumnMapperDialog(api, owner, fileName, workbook);
        Runnable unlisten = AuditTheme.installLiveThemeListener(dlg, () -> {
            SwingUtilities.updateComponentTreeUI(dlg);
            dlg.applyTheme();
        });
        try {
            dlg.setVisible(true);
            return dlg.result;
        } finally {
            unlisten.run();
        }
    }

    private static Window resolveOwner(MontoyaApi api, Component parent) {
        if (api != null) {
            try {
                if (parent != null) {
                    Window w = api.userInterface().swingUtils().windowForComponent(parent);
                    if (w != null) {
                        return w;
                    }
                }
                Window frame = api.userInterface().swingUtils().suiteFrame();
                if (frame != null) {
                    return frame;
                }
            } catch (Exception ignored) {
            }
        }
        if (parent instanceof Window) {
            return (Window) parent;
        }
        return parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
    }

    private static String excelColLetters(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

    private final class PreviewCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean dark = AuditTheme.isDark(api, table);
            setBorder(AuditTheme.cellPaddingBorder());
            setHorizontalAlignment(column == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
            c.setFont(table.getFont());

            if (!isSelected) {
                int headerRowIdx = Math.max(0, ((Number) spHeaderRow.getValue()).intValue() - 1);
                int sttModelCol = cbStt.getSelectedIndex() - 1;
                int methodModelCol = cbMethod.getSelectedIndex();
                int pathModelCol = cbPath.getSelectedIndex();
                int dataCol = column - 1;

                Color bg = AuditTheme.zebraRowBackground(table.getBackground(), row, dark);
                Color fg = AuditTheme.textPrimary(dark);

                if (column == 0) {
                    bg = AuditTheme.cardSurface(dark);
                    fg = AuditTheme.mutedForeground(dark);
                } else if (row == headerRowIdx) {
                    bg = AuditTheme.blend(AuditTheme.tableSurface(dark), AuditTheme.accentForeground(dark), dark ? 0.24f : 0.16f);
                    c.setFont(table.getFont().deriveFont(Font.BOLD));
                } else if (dataCol == pathModelCol) {
                    bg = AuditTheme.blend(bg, AuditTheme.okForeground(dark), dark ? 0.14f : 0.10f);
                } else if (dataCol == methodModelCol) {
                    bg = AuditTheme.blend(bg, AuditTheme.severityForeground(AuditNote.Severity.INFO, dark), dark ? 0.14f : 0.10f);
                } else if (sttModelCol >= 0 && dataCol == sttModelCol) {
                    bg = AuditTheme.blend(bg, AuditTheme.severityForeground(AuditNote.Severity.WARN, dark), dark ? 0.12f : 0.08f);
                }

                c.setBackground(bg);
                c.setForeground(fg);
            }
            return c;
        }
    }

    private static final class PreviewTableModel extends AbstractTableModel {
        private List<List<String>> previewRows = new ArrayList<>();
        private int colCount = 0;

        public void setRows(List<List<String>> rows) {
            previewRows = new ArrayList<>();
            int max = 0;
            if (rows != null) {
                int limit = Math.min(rows.size(), 12);
                for (int i = 0; i < limit; i++) {
                    List<String> r = rows.get(i);
                    previewRows.add(r);
                    max = Math.max(max, r.size());
                }
            }
            colCount = max;
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return previewRows.size();
        }

        @Override
        public int getColumnCount() {
            return colCount + 1;
        }

        @Override
        public String getColumnName(int column) {
            if (column == 0) {
                return "Dòng";
            }
            return excelColLetters(column - 1);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                return "#" + (rowIndex + 1);
            }
            List<String> row = previewRows.get(rowIndex);
            int c = columnIndex - 1;
            return c < row.size() ? row.get(c) : "";
        }
    }
}
