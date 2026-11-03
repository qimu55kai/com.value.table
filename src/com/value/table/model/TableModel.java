package com.value.table.model;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;

import com.value.table.Activator;
import com.value.table.Messages;

/**
 * Two dimensional view model used by {@code TableWindow}.
 * <p>
 * Construction talks to the target VM, so it must never run on the UI thread.
 * The model reads in pages: {@link #PAGE_ROWS} rows by {@link #PAGE_COLS}
 * columns up front, one more page in whichever direction the user scrolls.
 * </p>
 * <p>
 * A row is never materialised as a whole. A 10000 x 10000 matrix has rows of
 * 10000 cells, and reading one of them costs either a single bulk call (for a
 * primitive array) or 10000 round trips (for an object array) - which is why
 * the old implementation appeared to hang on wide matrices. Rows keep their
 * identity only; cells are read for the columns that are actually loaded.
 * </p>
 * <p>
 * Two layouts share this model. A grid of arrays and configured rule types is
 * filled cell by cell (a matrix). A list of objects no rule matches is filled
 * row by row from the members of the type the objects have in common (an entity
 * table) - see {@link EntityResolver}.
 * </p>
 */
public class TableModel extends ValueModel {

    /** Rows read before the table is shown, and per scroll afterwards. */
    public static final int PAGE_ROWS = 100;
    /** Columns read before the table is shown, and per scroll afterwards. */
    public static final int PAGE_COLS = 100;
    /**
     * Rows inspected to decide which layout to use. The whole first page is
     * inspected: a heterogeneous list whose subclasses only appear later would
     * otherwise get a column set that fits the first few rows only.
     */
    private static final int PROBE_ROWS = PAGE_ROWS;

    /**
     * A primitive array up to this length is read with one call per row
     * ({@code getValues()}); above it the loaded columns are read one by one,
     * because a bulk read would pull the whole row over for a 100 column page.
     * 16384 primitive values are about 150 KB per row, which is still cheaper
     * than 10000 round trips, while a truly enormous row stays bounded.
     */
    private static final int BULK_CELLS = 16384;

    private static final int MAX_CELL_CHARS = 200;

    /** Set when the variable itself is an array: rows are read one by one. */
    private IJavaArray rootArray;
    /** Rows of everything else, materialised in one pass by the rule engine. */
    private IJavaValue[] line;

    private int totalRows;
    private int totalCols;
    private int loadedRows;
    private int loadedCols;

    /** One entry per loaded row: the row object itself, never its cells. */
    private IJavaValue[] rowRefs;
    /** Only for rows that are not arrays: the flattened row from the rule engine. */
    private IJavaValue[][] rowValues;
    /** Cells each row can offer (an array's length, or the flattened width). */
    private int[] rowWidths;
    /** Cached text, {@code cells[i].length == loadedCols} for a matrix. */
    private String[][] cells;

    private String[] rowLabels;
    private String[] columnLabels;
    private String[] ruleColumnLabels;
    private boolean[] numericColumns;
    private boolean[] editableColumns;
    /**
     * One entry per loaded row: false for a row that has no object behind it, so
     * the cell editor does not offer to write into {@code null}.
     */
    private boolean[] rowEditable;
    private Boolean matrixEditable;

    /** Non-null when the rows are objects shown one column per member. */
    private EntityRule entityRule;
    private int unreadableCells;

    public TableModel(IJavaVariable variable, FetchContext context) {
        super(variable, Config.tableTypeMap, context);
        build();
    }

    /** The same table for a value with no variable behind it, e.g. an expression. */
    public TableModel(IJavaValue value, FetchContext context) {
        super(value, Config.tableTypeMap, context);
        build();
    }

    // ------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------

    private void build() {
        try {
            IJavaValue value = getValue();
            if (value == null) {
                context.warn("value.null", Messages.get("warn.value.null"));
                return;
            }

            /*
             * An array is never materialised up front: one getLength() plus one
             * call per row shown is what keeps a huge matrix from being copied
             * into mirrors before anything is drawn.
             */
            if (value instanceof IJavaArray) {
                rootArray = (IJavaArray) value;
                totalRows = rootArray.getLength();
            } else {
                line = getArrays(value);
                if (line == null) {
                    return;
                }
                totalRows = line.length;
            }

            int rowLimit = context.getMaxRows();
            if (totalRows > rowLimit) {
                context.warn("rows.limit", Messages.bind("warn.rows.limit", rowLimit, totalRows));
                totalRows = rowLimit;
            }
            if (totalRows == 0) {
                return;
            }

            int probe = Math.min(totalRows, PROBE_ROWS);
            rowRefs = new IJavaValue[probe];
            rowWidths = new int[probe];
            for (int i = 0; i < probe; i++) {
                context.checkCanceled();
                rowRefs[i] = element(i);
            }

            /*
             * A row that can be expanded further means a matrix: an array, a
             * type with a rule, or something with an iterator. Only when nothing
             * can be expanded, and the objects carry state of their own, is the
             * entity layout worth trying.
             *
             * A rule on the value itself wins, too: a Map is configured with
             * its own column labels, and expanding its entries instead would
             * show HashMap internals rather than the keys and values.
             */
            if (getUserClassType(value) == null && !hasExpandableRow(Arrays.copyOf(rowRefs, probe))) {
                entityRule = EntityResolver.resolve(Arrays.copyOf(rowRefs, probe), this);
                if (entityRule == null) {
                    context.warn("entity.none", Messages.get("warn.entity.none"));
                }
            }

            readRowLabels();
            readColumnLabels();
            ensureShape(PAGE_ROWS, PAGE_COLS);
        } catch (OperationCanceledException e) {
            throw e;
        } catch (Exception e) {
            Activator.log("cannot build the table model", e);
            context.warn("model.error", Messages.bind("warn.model.error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * True when at least one row can still be broken down, or when none of them
     * is an object with state - a list of strings stays a plain single column.
     */
    private boolean hasExpandableRow(IJavaValue[] values) {
        int expandable = 0;
        int entity = 0;
        for (IJavaValue v : values) {
            if (v == null) {
                continue;
            }
            if (isExpandable(v)) {
                expandable++;
            } else if (EntityResolver.canBeEntity(v)) {
                entity++;
            }
        }
        return expandable > 0 || entity == 0;
    }

    /**
     * Reads more of the table. Rows and columns already loaded are never
     * re-read, so this is the call a scroll makes.
     *
     * @return true when the shape actually grew
     */
    public boolean ensureShape(int wantRows, int wantCols) {
        int rows = Math.min(Math.max(wantRows, loadedRows), totalRows);
        int beforeRows = loadedRows;
        int beforeCols = loadedCols;

        if (rows > loadedRows) {
            int from = loadedRows;
            resize(rows);
            for (int i = from; i < rows; i++) {
                context.checkCanceled();
                readRow(i);
            }
            loadedRows = rows;
            /*
             * readRow only learns a row's identity and width; cells are read for
             * the columns that are loaded at that moment. The column pass below
             * runs only when the column count grows, so without this loop every
             * page after the first showed row labels and empty cells.
             */
            for (int i = from; i < rows; i++) {
                growRow(i, loadedCols);
            }
        }

        int cols = Math.min(Math.max(wantCols, loadedCols), context.getMaxCols());
        if (entityRule != null) {
            cols = Math.min(cols, entityRule.members.size());
        } else {
            cols = Math.min(cols, totalCols);
        }
        if (cols < 0) {
            cols = 0;
        }

        if (cells == null || cells.length < loadedRows) {
            cells = cells == null ? new String[loadedRows][] : Arrays.copyOf(cells, loadedRows);
        }
        if (cols > loadedCols) {
            for (int i = 0; i < loadedRows; i++) {
                growRow(i, cols);
            }
            loadedCols = cols;
        }
        buildColumnMeta();
        return loadedRows != beforeRows || loadedCols != beforeCols;
    }

    private void resize(int rows) {
        rowRefs = rowRefs == null ? new IJavaValue[rows] : Arrays.copyOf(rowRefs, rows);
        rowWidths = rowWidths == null ? new int[rows] : Arrays.copyOf(rowWidths, rows);
        rowValues = rowValues == null ? new IJavaValue[rows][] : Arrays.copyOf(rowValues, rows);
        cells = cells == null ? new String[rows][] : Arrays.copyOf(cells, rows);
        rowEditable = rowEditable == null ? new boolean[rows] : Arrays.copyOf(rowEditable, rows);
    }

    /**
     * True for a reference that is null in the target, either as a missing row
     * reference or as a value that says so itself.
     */
    private static boolean isNull(IJavaValue v) {
        return v == null || v.isNull();
    }

    /**
     * Reads the identity and the width of one row - never its cells, except for
     * the entity layout where the members are the row and there are only a few
     * of them.
     */
    private void readRow(int i) {
        try {
            IJavaValue v = element(i);
            rowRefs[i] = v;
            rowEditable[i] = !(entityRule != null && isNull(v));
            if (entityRule != null) {
                List<MemberRef> members = entityRule.members;
                String[] row = new String[members.size()];
                /*
                 * A null row is kept, blank: the list has that many entries and
                 * dropping one would renumber the rows and lose the gap the user
                 * is looking for. Nothing is read from it - a null reference has
                 * no fields, and asking it for them only produces errors that
                 * would look like missing data.
                 */
                if (!isNull(v)) {
                    for (int j = 0; j < members.size(); j++) {
                        row[j] = readMember(v, members.get(j));
                    }
                }
                cells[i] = row;
                rowWidths[i] = members.size();
                if (rowWidths[i] > totalCols) {
                    totalCols = rowWidths[i];
                }
                return;
            }
            if (v == null) {
                rowWidths[i] = 0;
                return;
            }
            if (v instanceof IJavaArray) {
                rowWidths[i] = ((IJavaArray) v).getLength();
            } else {
                IJavaValue[] values = getArrays(v);
                rowValues[i] = values;
                rowWidths[i] = values == null ? 0 : values.length;
            }
            if (rowWidths[i] > totalCols) {
                totalCols = rowWidths[i];
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (Exception e) {
            unreadableCells++;
            rowWidths[i] = 0;
        }
    }

    /** Reads the cells of one row from what it already has up to {@code to}. */
    private void growRow(int i, int to) {
        String[] old = cells[i];
        int have = old == null ? 0 : old.length;
        if (have >= to) {
            return;
        }
        if (entityRule != null) {
            // Members are all read at once; nothing more can be added.
            return;
        }
        String[] row = new String[to];
        if (old != null) {
            System.arraycopy(old, 0, row, 0, have);
        }
        IJavaValue[] values = rowValues[i];
        int width = rowWidths[i];
        int upto = Math.min(to, width);
        if (values != null) {
            for (int j = have; j < upto; j++) {
                row[j] = cellText(values[j]);
            }
        } else if (rowRefs[i] instanceof IJavaArray) {
            IJavaArray array = (IJavaArray) rowRefs[i];
            if (width <= BULK_CELLS && isPrimitive(array)) {
                /*
                 * One call for the whole row. Primitive values carry their text
                 * with them, so the cells cost nothing after the read.
                 */
                try {
                    IJavaValue[] all = array.getValues();
                    for (int j = have; j < upto; j++) {
                        row[j] = cellText(all[j]);
                    }
                } catch (Exception e) {
                    readCells(array, row, have, upto);
                }
            } else {
                readCells(array, row, have, upto);
            }
        }
        cells[i] = row;
    }

    private void readCells(IJavaArray array, String[] row, int from, int to) {
        for (int j = from; j < to; j++) {
            context.checkCanceled();
            try {
                row[j] = cellText(array.getValue(j));
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception e) {
                unreadableCells++;
            }
        }
    }

    private static boolean isPrimitive(IJavaArray array) {
        try {
            String signature = array.getSignature();
            return signature != null && signature.length() == 2 && "ZBCSIJFD".indexOf(signature.charAt(1)) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private IJavaValue element(int i) {
        if (rowRefs != null && i >= 0 && i < rowRefs.length && rowRefs[i] != null) {
            return rowRefs[i];
        }
        try {
            if (line != null) {
                return i < line.length ? line[i] : null;
            }
            return rootArray == null ? null : rootArray.getValue(i);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // cells
    // ------------------------------------------------------------------

    private String textAt(int i, int j) {
        String[] row = cells == null ? null : cells[i];
        return row != null && j < row.length && row[j] != null ? row[j] : "";
    }

    private String readMember(IJavaValue v, MemberRef member) {
        if (!(v instanceof IJavaObject) || isNull(v)) {
            return "";
        }
        IJavaObject object = (IJavaObject) v;
        try {
            if (member.isMethod()) {
                return cellText(invokeMethod(object, member.name, member.returnSig));
            }
            IJavaValue field = readField(object, member.name);
            if (field == null) {
                unreadableCells++;
                return "";
            }
            return cellText(field);
        } catch (Exception e) {
            unreadableCells++;
            return "";
        }
    }

    private static IJavaValue readField(IJavaObject object, String name) {
        IJavaFieldVariable field = findField(object, name);
        if (field == null) {
            return null;
        }
        try {
            return (IJavaValue) field.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The second argument of {@code getField} has changed meaning between
     * platform versions - in this one it returns {@code null} instead of
     * throwing when the field is not found that way. Both spellings are tried,
     * and {@code null} is a normal answer rather than an error: a heterogeneous
     * list has rows that simply do not carry the member.
     */
    private static IJavaFieldVariable findField(IJavaObject object, String name) {
        try {
            IJavaFieldVariable field = object.getField(name, true);
            if (field != null) {
                return field;
            }
        } catch (Exception ignored) {
            // try the other spelling below
        }
        try {
            return object.getField(name, false);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Renders one cell. {@code String} is read through {@code getValueString},
     * which is one call; everything else goes through {@code toString} only for
     * value types, because a call per cell is exactly the cost an entity table
     * cannot afford.
     */
    private String cellText(IJavaValue v) {
        if (v == null) {
            return "";
        }
        try {
            if (v instanceof IJavaObject) {
                if (EntityResolver.isValueType(v)) {
                    String type = v.getReferenceTypeName();
                    if ("java.lang.String".equals(type)) {
                        return abbreviate(v.getValueString());
                    }
                    return abbreviate(getString(v));
                }
            }
            return abbreviate(v.getValueString());
        } catch (Exception e) {
            return "?";
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_CELL_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CELL_CHARS) + "...";
    }

    /**
     * One object from the rows, for the rules dialog: the first loaded row when
     * the rows are objects, otherwise the variable's own value.
     * <p>
     * Both are useful. A list of entities has its type on the rows; a map or a
     * value with its own accessor rule has it on the variable, and the columns
     * that can be configured are the ones that type declares.
     * </p>
     */
    /**
     * True when the table shows objects one column per member, which is the only
     * shape whose columns can be chosen per type.
     * <p>
     * A matrix, a map and a collection get their columns from a reading rule
     * instead, so offering "pick the columns" for them would promise something
     * the columns do not obey.
     * </p>
     */
    public boolean isEntityTable() {
        return entityRule != null;
    }

    /**
     * A row object to read types from, for the column picker.
     * <p>
     * Null rows are skipped: the picker lists what a type declares, and a null
     * reference cannot list anything - the first row of a list is allowed to be
     * an empty slot.
     * </p>
     */
    public IJavaValue sampleElement() {
        if (rowRefs != null) {
            for (IJavaValue value : rowRefs) {
                if (value instanceof IJavaObject && !isNull(value)) {
                    return value;
                }
            }
        }
        IJavaValue value = getValue();
        return value != null && !isNull(value) ? value : null;
    }

    // ------------------------------------------------------------------
    // labels and column metadata
    // ------------------------------------------------------------------

    private void readRowLabels() {
        rowLabels = new String[totalRows];
        if (entityRule != null) {
            for (int i = 0; i < totalRows; i++) {
                rowLabels[i] = String.valueOf(i);
            }
            return;
        }
        String[] labels = null;
        UserClassType t = getUserClassType(value);
        try {
            if (t != null && t.rows != null) {
                labels = t.rows;
            } else if (t != null && t.rowName != null) {
                if (t.rowIsField) {
                    if (!UserClassType.ALL_FIELDS.equals(t.rowName)) {
                        IJavaValue field = (IJavaValue) ((IJavaObject) value).getField(t.rowName, false).getValue();
                        labels = getString(getArrays(field));
                    }
                } else {
                    labels = getString(getArrays(invokeMethod((IJavaObject) value, t.rowName,
                            t.rowReturnType)));
                }
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (Exception e) {
            labels = null;
        }
        for (int i = 0; i < totalRows; i++) {
            rowLabels[i] = (labels != null && i < labels.length && labels[i] != null)
                    ? labels[i]
                    : String.valueOf(i);
        }
    }

    private void readColumnLabels() {
        UserClassType t = getUserClassType(value);
        if (t == null) {
            return;
        }
        try {
            if (t.cols != null) {
                ruleColumnLabels = t.cols;
            } else if (t.colName != null) {
                if (t.colIsField) {
                    if (!UserClassType.ALL_FIELDS.equals(t.colName)) {
                        IJavaValue field = (IJavaValue) ((IJavaObject) value).getField(t.colName, false).getValue();
                        ruleColumnLabels = getString(getArrays(field));
                    }
                } else {
                    ruleColumnLabels = getString(getArrays(invokeMethod((IJavaObject) value, t.colName,
                            t.colReturnType)));
                }
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (Exception e) {
            ruleColumnLabels = null;
        }
    }

    private void buildColumnMeta() {
        columnLabels = new String[loadedCols + 1];
        columnLabels[0] = "Index";
        numericColumns = new boolean[loadedCols + 1];
        editableColumns = new boolean[loadedCols + 1];
        for (int j = 0; j < loadedCols; j++) {
            columnLabels[j + 1] = labelOf(j);
            numericColumns[j + 1] = allNumeric(j);
            editableColumns[j + 1] = canEdit(j);
        }
    }

    private String labelOf(int j) {
        if (entityRule != null) {
            return entityRule.members.get(j).label;
        }
        if (ruleColumnLabels != null && j < ruleColumnLabels.length && ruleColumnLabels[j] != null) {
            return ruleColumnLabels[j];
        }
        return String.valueOf(j);
    }

    /**
     * True when every non-empty cell of the column parses as a number, which is
     * what the heat map colouring keys on. A column of ids is numeric but flat,
     * so a single distinct value also turns the colouring off.
     */
    private boolean allNumeric(int column) {
        boolean seen = false;
        double first = 0;
        boolean varied = false;
        for (int i = 0; i < loadedRows; i++) {
            String[] row = cells[i];
            if (row == null || column >= row.length) {
                continue;
            }
            String text = row[column];
            if (text == null || text.isEmpty() || "null".equals(text) || "?".equals(text)) {
                continue;
            }
            double d;
            try {
                d = Double.parseDouble(text.trim());
            } catch (RuntimeException e) {
                return false;
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return false;
            }
            if (!seen) {
                first = d;
                seen = true;
            } else if (d != first) {
                varied = true;
            }
        }
        return seen && varied;
    }

    private boolean canEdit(int column) {
        if (entityRule != null) {
            return column < entityRule.members.size() && !entityRule.members.get(column).isMethod();
        }
        if (matrixEditable == null) {
            matrixEditable = Boolean.valueOf(computeMatrixEditable());
        }
        return matrixEditable.booleanValue();
    }

    /**
     * Writing back is only offered where the type is unambiguous: a primitive or
     * a String. Anything else would need an object graph built inside the target,
     * which is not something a cell editor should guess at.
     */
    private boolean computeMatrixEditable() {
        try {
            for (int i = 0; i < loadedRows; i++) {
                IJavaValue v = rowRefs[i];
                if (v instanceof IJavaArray) {
                    return writableComponent(((IJavaArray) v).getSignature());
                }
                if (rootArray != null && v != null) {
                    // A flat array: the row is the cell.
                    return writableComponent(rootArray.getSignature());
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean writableComponent(String arraySignature) {
        if (arraySignature == null || !arraySignature.startsWith("[")) {
            return false;
        }
        String component = arraySignature.substring(1);
        if (component.startsWith("[")) {
            return false;
        }
        if (component.startsWith("L")) {
            return "Ljava/lang/String;".equals(component);
        }
        return component.length() == 1 && "ZBCSIJFD".indexOf(component.charAt(0)) >= 0;
    }

    // ------------------------------------------------------------------
    // writing back
    // ------------------------------------------------------------------

    /**
     * Writes {@code text} into one cell of the target VM and returns what the
     * cell now reads as. Runs on a background job like everything else.
     */
    public String setCell(int row, int column, String text) throws Exception {
        context.checkCanceled();
        if (row < 0 || row >= loadedRows || column < 0 || column >= loadedCols) {
            throw new IllegalArgumentException(Messages.get("error.cell.notLoaded"));
        }
        if (entityRule != null) {
            MemberRef member = entityRule.members.get(column);
            if (member.isMethod()) {
                throw new IllegalArgumentException(Messages.bind("error.cell.computed", member.label));
            }
            IJavaValue element = rowRefs[row];
            if (isNull(element)) {
                /*
                 * A null row is shown, but there is nothing to write into: no
                 * field of it exists, and the list itself is not the object
                 * being edited. Refused here as well as in the editor, because
                 * the model can be reached from a stale widget.
                 */
                throw new IllegalArgumentException(
                        Messages.bind("error.cell.nullRow", row + 1, member.name));
            }
            if (!(element instanceof IJavaObject)) {
                throw new IllegalArgumentException(Messages.get("error.cell.noObject"));
            }
            IJavaObject object = (IJavaObject) element;
            IJavaFieldVariable field = findField(object, member.name);
            if (field == null) {
                throw new IllegalArgumentException(
                        Messages.bind("error.cell.noField", row + 1, object.getReferenceTypeName(), member.name));
            }
            field.setValue(text);
            setCellText(row, column, readMember(object, member));
            return textAt(row, column);
        }

        IJavaValue target = rowRefs[row];
        IJavaValue newValue;
        if (target instanceof IJavaArray) {
            IJavaArray array = (IJavaArray) target;
            newValue = newValue(array.getSignature(), text);
            array.setValue(column, newValue);
        } else if (rootArray != null && column == 0) {
            newValue = newValue(rootArray.getSignature(), text);
            rootArray.setValue(row, newValue);
            rowRefs[row] = newValue;
            rowValues[row] = null;
            rowWidths[row] = 1;
        } else {
            throw new IllegalArgumentException(Messages.get("error.cell.notWritable"));
        }
        setCellText(row, column, cellText(newValue));
        return textAt(row, column);
    }

    private void setCellText(int row, int column, String text) {
        if (cells == null || row >= cells.length) {
            return;
        }
        String[] line = cells[row];
        if (line == null || column >= line.length) {
            return;
        }
        line[column] = text;
    }

    private IJavaValue newValue(String arraySignature, String text) throws Exception {
        IDebugTarget target = getValue().getDebugTarget();
        if (!(target instanceof IJavaDebugTarget)) {
            throw new IllegalStateException("The target is gone.");
        }
        IJavaDebugTarget jdt = (IJavaDebugTarget) target;
        String component = arraySignature.substring(1);
        String trimmed = text == null ? "" : text.trim();
        if ("Ljava/lang/String;".equals(component)) {
            return jdt.newValue(text);
        }
        char kind = component.charAt(0);
        switch (kind) {
            case 'Z':
                return jdt.newValue("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)
                        || "yes".equalsIgnoreCase(trimmed));
            case 'B':
                return jdt.newValue(Byte.parseByte(trimmed));
            case 'C':
                return jdt.newValue(trimmed.isEmpty() ? ' ' : trimmed.charAt(0));
            case 'S':
                return jdt.newValue(Short.parseShort(trimmed));
            case 'I':
                return jdt.newValue(Integer.parseInt(trimmed));
            case 'J':
                return jdt.newValue(Long.parseLong(trimmed));
            case 'F':
                return jdt.newValue(Float.parseFloat(trimmed));
            case 'D':
                return jdt.newValue(Double.parseDouble(trimmed));
            default:
                throw new IllegalArgumentException(Messages.bind("error.cell.type", component));
        }
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    /**
     * Materialises the loaded part into plain strings. Safe to call from a
     * worker thread; the returned object is what the UI thread consumes.
     */
    public TableData toData() {
        String[] headers = columnLabels == null ? new String[] { Messages.get("window.index") } : columnLabels;
        String[] rows = new String[loadedRows];
        for (int i = 0; i < loadedRows; i++) {
            rows[i] = rowLabels != null && i < rowLabels.length && rowLabels[i] != null
                    ? rowLabels[i]
                    : String.valueOf(i);
        }
        if (entityRule != null && unreadableCells > 0) {
            context.warn("entity.unreadable", Messages.bind("warn.unreadable", unreadableCells));
        }
        String caption = entityRule == null ? null
                : Messages.bind("window.caption.members", entityRule.typeName);
        return new TableData(headers, rows, cells == null ? new String[0][] : cells,
                context.getWarnings(), caption, totalRows, totalCols, numericColumns, editableColumns,
                rowEditable);
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getLoadedRows() {
        return loadedRows;
    }

    public int getLoadedCols() {
        return loadedCols;
    }

    /** True while there are columns the table has not read yet. */
    public boolean hasMoreColumns() {
        return loadedCols < Math.min(totalCols, context.getMaxCols());
    }

    public EntityRule getEntityRule() {
        return entityRule;
    }
}
