package com.mythofcrawl.crawler.ui;

import com.mythofcrawl.crawler.excel.SimpleXlsxReader;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal column-mapping dialog shown right after loading an Excel (.xlsx) or CSV file
 * when the user wants to review or customize which Sheet / Header Row / STT / Method / Path API
 * columns are imported.
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

    private final SimpleXlsxReader.WorkbookData workbook;
    private final JComboBox<String> cbSheet;
    private final JSpinner spHeaderRow;
    private final JComboBox<String> cbStt;
    private final JComboBox<String> cbMethod;
    private final JComboBox<String> cbPath;
    private final PreviewTableModel previewModel;
    private MappingResult result;

    private ExcelColumnMapperDialog(Window owner, String fileName, SimpleXlsxReader.WorkbookData workbook) {
        super(owner, "Ánh xạ cột Excel - " + fileName, ModalityType.APPLICATION_MODAL);
        this.workbook = workbook;

        setLayout(new BorderLayout(8, 8));
        setSize(820, 460);
        setLocationRelativeTo(owner);

        cbSheet = new JComboBox<>(workbook.getSheetNames().toArray(new String[0]));
        spHeaderRow = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
        cbStt = new JComboBox<>();
        cbMethod = new JComboBox<>();
        cbPath = new JComboBox<>();
        previewModel = new PreviewTableModel();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        form.add(new JLabel("Sheet:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        form.add(cbSheet, gc);
        gc.gridx = 2; gc.weightx = 0;
        form.add(new JLabel("Dòng tiêu đề (Header #):"), gc);
        gc.gridx = 3; gc.weightx = 0.2;
        form.add(spHeaderRow, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        form.add(new JLabel("Cột STT:"), gc);
        gc.gridx = 1; gc.weightx = 0.5;
        form.add(cbStt, gc);
        gc.gridx = 2; gc.weightx = 0;
        form.add(new JLabel("Cột Method:"), gc);
        gc.gridx = 3; gc.weightx = 0.5;
        form.add(cbMethod, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        form.add(new JLabel("Cột Path API:"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1.0;
        form.add(cbPath, gc);

        add(form, BorderLayout.NORTH);

        JTable previewTable = new JTable(previewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JScrollPane sp = new JScrollPane(previewTable);
        sp.setBorder(BorderFactory.createTitledBorder("Xem trước 12 dòng đầu trong file"));
        add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton btnCancel = new JButton("Hủy");
        JButton btnOk = new JButton("Nạp danh sách API");
        bottom.add(btnCancel);
        bottom.add(btnOk);
        add(bottom, BorderLayout.SOUTH);

        cbSheet.addActionListener(e -> refreshForCurrentSheet(true));
        spHeaderRow.addChangeListener(e -> refreshForCurrentSheet(false));

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

        refreshForCurrentSheet(true);
    }

    private void refreshForCurrentSheet(boolean autoGuess) {
        String sheet = (String) cbSheet.getSelectedItem();
        if (sheet == null) {
            return;
        }
        List<List<String>> rows = workbook.getRows(sheet);
        previewModel.setRows(rows);

        SimpleXlsxReader.ColumnGuess guess = SimpleXlsxReader.guessColumns(rows);
        if (autoGuess) {
            int maxRows = Math.max(1, Math.min(50, rows.size()));
            int targetHeader1Based = Math.max(1, Math.min(maxRows, guess.headerRowIndex + 1));
            spHeaderRow.setValue(targetHeader1Based);
        }

        int headerIdx = Math.max(0, Math.min(rows.size() - 1, ((Number) spHeaderRow.getValue()).intValue() - 1));
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
            cbStt.setSelectedIndex(guess.sttCol >= 0 && guess.sttCol < colCount ? (guess.sttCol + 1) : 0);
            cbMethod.setSelectedIndex(guess.methodCol >= 0 && guess.methodCol < colCount ? guess.methodCol : 0);
            cbPath.setSelectedIndex(guess.pathCol >= 0 && guess.pathCol < colCount ? guess.pathCol : Math.min(1, colCount - 1));
        }
    }

    public static MappingResult showDialog(Component parent, String fileName, SimpleXlsxReader.WorkbookData workbook) {
        Window owner = parent instanceof Window ? (Window) parent
            : javax.swing.SwingUtilities.getWindowAncestor(parent);
        if (owner == null) {
            owner = new Frame();
        }
        ExcelColumnMapperDialog dlg = new ExcelColumnMapperDialog(owner, fileName, workbook);
        dlg.setVisible(true);
        return dlg.result;
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
