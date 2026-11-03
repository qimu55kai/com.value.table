package com.value.table.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finished, thread safe snapshot of what the table currently knows: every cell
 * is already a plain {@link String}, so the UI thread only copies text into
 * widgets and never touches the debug target.
 * <p>
 * A snapshot covers the rows and columns read so far, not necessarily all of
 * them: {@link #getTotalRows()} says how many there are, and the window asks
 * for more when the user scrolls. {@link #getNumericColumns()} and
 * {@link #getEditableColumns()} are decided while the debug model is at hand,
 * because both need type information the strings no longer carry.
 * </p>
 */
public class TableData {

    private final String[] colName;
    private final String[] rowName;
    private final String[][] cells;
    private final List<String> warnings;
    /** What the table shows, e.g. "Members of User"; null keeps the default title. */
    private final String caption;

    private final int totalRows;
    private final int totalColumns;
    private final boolean[] numericColumns;
    private final boolean[] editableColumns;
    /** One entry per loaded row; false for a row with no object behind it. */
    private final boolean[] editableRows;

    public TableData(String[] colName, String[] rowName, String[][] cells, List<String> warnings) {
        this(colName, rowName, cells, warnings, null, -1, -1, null, null);
    }

    public TableData(String[] colName, String[] rowName, String[][] cells, List<String> warnings,
            String caption) {
        this(colName, rowName, cells, warnings, caption, -1, -1, null, null);
    }

    public TableData(String[] colName, String[] rowName, String[][] cells, List<String> warnings,
            String caption, int totalRows, boolean[] numericColumns, boolean[] editableColumns) {
        this(colName, rowName, cells, warnings, caption, totalRows, -1, numericColumns, editableColumns);
    }

    public TableData(String[] colName, String[] rowName, String[][] cells, List<String> warnings,
            String caption, int totalRows, int totalColumns, boolean[] numericColumns,
            boolean[] editableColumns) {
        this(colName, rowName, cells, warnings, caption, totalRows, totalColumns, numericColumns,
                editableColumns, null);
    }

    public TableData(String[] colName, String[] rowName, String[][] cells, List<String> warnings,
            String caption, int totalRows, int totalColumns, boolean[] numericColumns,
            boolean[] editableColumns, boolean[] editableRows) {
        this.colName = colName == null ? new String[0] : colName;
        this.rowName = rowName == null ? new String[0] : rowName;
        this.cells = cells == null ? new String[0][0] : cells;
        this.warnings = warnings == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.caption = caption;
        this.totalRows = totalRows < 0 ? this.rowName.length : totalRows;
        this.totalColumns = totalColumns < 0 ? Math.max(0, this.colName.length - 1) : totalColumns;
        this.numericColumns = numericColumns;
        this.editableColumns = editableColumns;
        this.editableRows = editableRows;
    }

    public String getCaption() {
        return caption;
    }

    public String[] getColName() {
        return colName;
    }

    public String[] getRowName() {
        return rowName;
    }

    public String[][] getCells() {
        return cells;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public int getRowCount() {
        return rowName.length;
    }

    /** Number of rows the variable has, of which {@link #getRowCount()} are loaded. */
    public int getTotalRows() {
        return totalRows;
    }

    public boolean hasMoreRows() {
        return totalRows > rowName.length;
    }

    /** Data columns loaded, excluding the row label column. */
    public int getColCount() {
        return Math.max(0, colName.length - 1);
    }

    /** Data columns the variable has, of which {@link #getColCount()} are loaded. */
    public int getTotalColumns() {
        return totalColumns;
    }

    /** Index 0 is the row label column, which is never numeric nor editable. */
    public boolean isNumericColumn(int column) {
        return numericColumns != null && column >= 0 && column < numericColumns.length
                && numericColumns[column];
    }

    public boolean isEditableColumn(int column) {
        return editableColumns != null && column >= 0 && column < editableColumns.length
                && editableColumns[column];
    }

    /** True when this cell may be edited: the column allows it and the row has an object. */
    public boolean isEditableCell(int row, int column) {
        return isEditableColumn(column) && isEditableRow(row);
    }

    /**
     * False for a row that has no object behind it - a null element of the list.
     * Such a row is shown, blank, but there is nothing in the target to write to.
     */
    public boolean isEditableRow(int row) {
        return editableRows == null || row < 0 || row >= editableRows.length || editableRows[row];
    }

    /** How many loaded rows are empty slots - references that are null. */
    public int getNullRowCount() {
        if (editableRows == null) {
            return 0;
        }
        int count = 0;
        for (boolean editable : editableRows) {
            if (!editable) {
                count++;
            }
        }
        return count;
    }

    public String getCell(int row, int column) {
        if (row < 0 || row >= cells.length) {
            return "";
        }
        String[] line = cells[row];
        if (line == null || column < 0 || column >= line.length) {
            return "";
        }
        return line[column] == null ? "" : line[column];
    }
}
