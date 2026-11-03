package com.value.table.show;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.value.table.Activator;
import com.value.table.Messages;
import com.value.table.model.Config;
import com.value.table.model.EntityRule;
import com.value.table.model.EntityRules;
import com.value.table.model.FetchContext;
import com.value.table.model.MemberCatalog;
import com.value.table.model.MemberRef;
import com.value.table.model.TableData;
import com.value.table.model.TableModel;
import com.value.table.model.ValueModel;
import com.value.table.util.CellHit;
import com.value.table.util.Icons;
import com.value.table.util.Keys;
import com.value.table.util.TargetRule;
import com.value.table.util.Util;

/**
 * Floating window that renders the selected variable as a table.
 * <p>
 * Reading a variable means a round trip to the target VM per cell, and a single
 * call can fail to return at all, so the window never does that work itself: it
 * opens immediately with a progress area and lets a background job fill the
 * table. The UI thread only ever copies finished strings into widgets.
 * </p>
 * <p>
 * The table is filled in pages. The first page arrives when the value is read,
 * further pages when the vertical or horizontal scroll bar reaches its end, so a
 * huge matrix costs what is on screen rather than what exists.
 * </p>
 * <p>
 * The row label lives in its own table pinned to the left, because SWT cannot
 * freeze a column: the main table scrolls horizontally, the narrow one never
 * does, and the two are kept in step vertically. Everything below treats a
 * column index as global - 0 is the label, 1..n are the data columns.
 * </p>
 */
public class TableWindow {

    private static final int CELL_W = 70;
    private static final int MAX_COL_W = 480;
    private static final int INDEX_W = 52;
    /** Prefix of the build stamp; technical, so it is not translated. */
    private static final String BUILD_PREFIX = "build ";
    /**
     * Each item carries its row number, because painting a cell has to know
     * which row it belongs to and {@code Table.indexOf} walks the whole native
     * list to find out - once per cell, on every scroll.
     */
    private static final String ROW_KEY = "valuetable.row";

    private final IJavaVariable variable;
    /**
     * Set instead of {@link #variable} when the table shows a value with no
     * variable behind it - the result of a watch expression. The two are
     * exclusive; everything that used to ask the variable asks
     * {@link #sourceValue()} instead.
     */
    private final IJavaValue fixedValue;
    private final String title;
    private final Shell shell;
    private final Display display;

    private Group tableGroup;
    private Composite loadingArea;
    private Label loadingLabel;
    private ProgressBar progressBar;

    /** Pinned row label column, left of {@link #mainTable}. */
    private Table frozenTable;
    private TableColumn frozenColumn;
    /** The scrolling table: every data column, no label. */
    private Table mainTable;
    private TableColumn[] columns;

    private Text warningArea;
    private Label statusLabel;
    private Button editToggle;
    private CCombo colorMode;

    /** Last snapshot the widgets show, and how much of it is on screen. */
    private TableData data;
    private int renderedRows;
    private int renderedCols;

    /**
     * The cell rectangle the user dragged out, in global columns. The table's
     * own selection is only ever one row - a table row is a record - so the
     * rectangle is tracked here and painted as cell backgrounds by
     * {@link #hookCellPainting}, which is also what makes Ctrl+C copy a block
     * rather than a row.
     */
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;
    private int selFocusRow = -1;
    private int selFocusCol = -1;
    private boolean selecting;

    /**
     * The block that was selected before the click that is being handled now.
     * A click always shrinks the block to the one cell under the pointer, but a
     * double click only wants to open an editor - it should not cost the user
     * the block they had just dragged out, so the block is remembered here and
     * put back when the editor closes.
     */
    private int[] selectionBeforeClick;
    /** The cell that click hit, so a stale snapshot is never put back later. */
    private int lastClickRow = -1;
    private int lastClickCol = -1;
    /** The block to restore when the open editor closes; null when there is none. */
    private int[] editSelection;

    /** Only ever touched from the jobs, which are serialised per target. */
    private volatile TableModel model;
    private volatile boolean loadingMore;

    private Job job;
    private volatile Job moreJob;

    /** Heat map colours and per-column ranges; owns its own brushes. */
    private HeatMap heatMap;
    private Color zebraColor;
    private Color selColor;
    private Color selEdgeColor;

    private boolean syncPending;

    /**
     * Ctrl+C for the whole window, not just for the table.
     * <p>
     * The tables and the cell editor handle the key themselves (they know what
     * to copy and when the field really means "copy the text I typed"), so this
     * only covers the rest of the window: after clicking the copy button or the
     * edit check box the focus sits on a button, where a key listener on the
     * table would never see the keystroke. The filter is scoped to this shell
     * being the active one, so it cannot take Ctrl+C away from the editor the
     * user has open behind the window.
     * </p>
     */
    private Listener keyFilter;

    public TableWindow(Shell parentShell, IJavaVariable variable) {
        this(parentShell, variable, null, variableTitle(variable));
    }

    /**
     * The table of an expression result: the Expressions view hands out an
     * {@code IExpression}, whose value is already computed, so there is no
     * variable to read it from.
     */
    public TableWindow(Shell parentShell, IJavaValue value, String name) {
        this(parentShell, null, value, name);
    }

    private TableWindow(Shell parentShell, IJavaVariable variable, IJavaValue value, String title) {
        this.variable = variable;
        this.fixedValue = value;
        this.title = title == null || title.trim().isEmpty() ? "ValueTable" : title;
        this.shell = new Shell(parentShell, SWT.CLOSE | SWT.RESIZE | SWT.TITLE | SWT.MIN);
        this.display = this.shell.getDisplay();
        this.shell.setImage(Icons.getImage());
        this.shell.setText(this.title);
        this.shell.setLayout(new GridLayout(1, false));

        this.heatMap = new HeatMap(this.display);
        this.createTableGroup();
        this.createLoadingArea();
        this.fitBounds();
        this.installKeyFilter();
        this.shell.addDisposeListener(e -> {
            if (this.keyFilter != null) {
                this.display.removeFilter(SWT.KeyDown, this.keyFilter);
                this.keyFilter = null;
            }
            cancelJob();
            disposeColors();
        });
        this.shell.open();

        this.startFetch();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void createTableGroup() {
        this.tableGroup = new Group(this.shell, SWT.NONE);
        this.tableGroup.setText(Messages.get("window.group"));
        this.tableGroup.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true, 1, 1));
        this.tableGroup.setLayout(new GridLayout(1, false));
    }

    private void createLoadingArea() {
        this.loadingArea = new Composite(this.tableGroup, SWT.NONE);
        this.loadingArea.setLayout(new GridLayout(1, true));
        this.loadingArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        this.loadingLabel = new Label(this.loadingArea, SWT.WRAP | SWT.CENTER);
        this.loadingLabel.setText(Messages.get("window.reading"));
        this.loadingLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));

        this.progressBar = new ProgressBar(this.loadingArea, SWT.HORIZONTAL | SWT.INDETERMINATE);
        this.progressBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createTableArea() {
        if (this.loadingArea != null && !this.loadingArea.isDisposed()) {
            this.loadingArea.dispose();
        }

        Composite grid = new Composite(this.tableGroup, SWT.NONE);
        grid.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
        GridLayout gridLayout = new GridLayout(2, false);
        gridLayout.horizontalSpacing = 0;
        gridLayout.marginWidth = 0;
        gridLayout.marginHeight = 0;
        grid.setLayout(gridLayout);

        this.frozenTable = new Table(grid, SWT.BORDER | SWT.SINGLE);
        this.frozenTable.setHeaderVisible(true);
        this.frozenTable.setLinesVisible(true);
        GridData frozenData = new GridData(SWT.FILL, SWT.FILL, false, true);
        frozenData.widthHint = INDEX_W + 6;
        this.frozenTable.setLayoutData(frozenData);
        this.frozenColumn = new TableColumn(this.frozenTable, SWT.NONE);
        this.frozenColumn.setText(Messages.get("window.index"));
        this.frozenColumn.setWidth(INDEX_W);

        this.mainTable = new Table(grid, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        this.mainTable.setHeaderVisible(true);
        this.mainTable.setLinesVisible(true);
        this.mainTable.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));

        this.zebraColor = new Color(this.display, 246, 246, 246);
        this.selColor = new Color(this.display, 205, 226, 248);
        this.selEdgeColor = new Color(this.display, 163, 200, 240);

        /*
         * A selected row is drawn by the platform in the list selection colour,
         * which would hide both the heat map and the rectangle. Clearing the
         * SELECTED flag while erasing makes the platform draw the cell as if it
         * were not selected; the fill painted below is then the only highlight.
         */
        hookCellPainting(this.frozenTable, 0);
        hookCellPainting(this.mainTable, 1);

        this.hookInput(this.frozenTable, 0);
        this.hookInput(this.mainTable, 1);

        /*
         * The label column has no scroll bar of its own, so it is scrolled from
         * the main table. Painting is the one event every kind of scrolling ends
         * with - thumb, wheel, keyboard - which makes it the reliable place to
         * notice that the two have drifted apart.
         */
        this.mainTable.addListener(SWT.Paint, event -> {
            if (syncPending || frozenTable == null || frozenTable.isDisposed()) {
                return;
            }
            if (frozenTable.getTopIndex() != mainTable.getTopIndex()) {
                syncPending = true;
                display.asyncExec(() -> {
                    syncPending = false;
                    syncTop();
                });
            }
        });

        this.mainTable.getVerticalBar().addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncTop();
                maybeLoadMoreRows();
            }
        });
        this.mainTable.getHorizontalBar().addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (atEnd(mainTable.getHorizontalBar())) {
                    loadMore(false, true);
                }
            }
        });

        this.createBottomBar();
    }

    /**
     * Paints the fill behind every cell: the selection block, the heat map and
     * the zebra stripe, all decided by {@link #backgroundFor}.
     * <p>
     * The fill is painted here, in EraseItem, instead of being handed to
     * {@code TableItem.setBackground}. With item colours the platform erases
     * the one cell that holds the focus again after the colour was set, so the
     * row a drag started on stayed white with a dotted rectangle around it
     * while the rest of the block was selected - the copy said four rows, the
     * picture said three. Drawing the rectangle ourselves also means only the
     * cells on screen are ever coloured: the old code called setBackground once
     * per cell of every loaded row, which is thousands of invalidations for a
     * table whose colour mode nobody changed on screen.
     * </p>
     *
     * @param offset global column of this table's first column: 0 for the
     *               pinned label table, 1 for the main one
     */
    private void hookCellPainting(final Table table, final int offset) {
        table.addListener(SWT.EraseItem, event -> {
            /*
             * Three detail bits off, one line each:
             * SELECTED - the platform would paint its own selection colour
             *            over the fill below;
             * FOCUSED  - would ring the focused cell in a dotted rectangle,
             *            which is wrong for a block of many cells;
             * BACKGROUND - would fill the cell before we do.
             */
            event.detail &= ~(SWT.SELECTED | SWT.FOCUSED | SWT.BACKGROUND);
            if (event.item == null || this.data == null || this.columns == null) {
                return;
            }
            TableItem item = (TableItem) event.item;
            Object tag = item.getData(ROW_KEY);
            int row = tag instanceof Integer ? ((Integer) tag).intValue() : table.indexOf(item);
            Color fill = backgroundFor(item, row, event.index + offset, event.index,
                    colorModeIndex(), table.getBackground());
            event.gc.setBackground(fill);
            event.gc.fillRectangle(event.x, event.y, event.width, event.height);
        });
    }

    /** The colour mode the combo box is on; 0 before the bar exists. */
    private int colorModeIndex() {
        return this.colorMode == null ? 0 : this.colorMode.getSelectionIndex();
    }

    private void installKeyFilter() {
        this.keyFilter = event -> {
            if (this.shell.isDisposed() || this.display.getActiveShell() != this.shell) {
                return;
            }
            Control focus = this.display.getFocusControl();
            /*
             * The tables have their own listener and the cell editor decides
             * for itself whether Ctrl+C lifts a value out of the field or
             * copies the block, so both are left alone here.
             */
            if (focus == this.mainTable || focus == this.frozenTable
                    || focus instanceof Text || focus instanceof CCombo) {
                return;
            }
            if (Keys.isCopy(event.stateMask, event.keyCode, event.character)) {
                event.doit = false;
                copySelection();
            }
        };
        this.display.addFilter(SWT.KeyDown, this.keyFilter);
    }

    /** Mouse, wheel and keyboard, bound once per table. */
    private void hookInput(final Table table, final int offset) {
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (Keys.isCopy(e)) {
                    e.doit = false;
                    copySelection();
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                beginSelection(table, offset, e);
            }

            @Override
            public void mouseDoubleClick(MouseEvent e) {
                startCellEditor(table, offset, e);
            }
        });
        table.addListener(SWT.MouseMove, event -> extendSelection(table, offset, event));
        table.addListener(SWT.MouseUp, event -> selecting = false);
        table.addListener(SWT.MouseWheel, event -> {
            if (table == frozenTable) {
                // The label column cannot scroll on its own; move the real table.
                event.doit = false;
                int step = event.count > 0 ? 3 : -3;
                int top = Math.max(0, Math.min(mainTable.getTopIndex() + step, mainTable.getItemCount() - 1));
                mainTable.setTopIndex(top);
                syncTop();
            }
            display.asyncExec(this::maybeLoadMoreRows);
        });
    }

    /** True when the scroll bar sits at (or within a pixel of) its end. */
    private static boolean atEnd(ScrollBar bar) {
        if (bar == null) {
            return false;
        }
        int max = bar.getMaximum() - bar.getThumb();
        return max > 0 && bar.getSelection() >= max - 1;
    }

    /**
     * Called after any vertical scrolling, however it happened: the wheel does
     * not move the scroll bar's thumb through a selection event, so listening to
     * the bar alone would miss the common case.
     */
    private void maybeLoadMoreRows() {
        if (this.mainTable == null || this.mainTable.isDisposed()) {
            return;
        }
        ScrollBar bar = this.mainTable.getVerticalBar();
        if (bar == null || !bar.getVisible()) {
            return;
        }
        if (atEnd(bar)) {
            loadMore(true, false);
        }
    }

    /** Keeps the pinned label column on the same top row as the main table. */
    private void syncTop() {
        if (this.frozenTable == null || this.frozenTable.isDisposed()
                || this.mainTable == null || this.mainTable.isDisposed()) {
            return;
        }
        int count = this.frozenTable.getItemCount();
        if (count == 0) {
            return;
        }
        int top = Math.min(this.mainTable.getTopIndex(), count - 1);
        if (this.frozenTable.getTopIndex() != top) {
            this.frozenTable.setTopIndex(top);
        }
    }

    // ------------------------------------------------------------------
    // Cell rectangle selection
    // ------------------------------------------------------------------

    private void beginSelection(Table table, int offset, MouseEvent e) {
        if (e.button != 1 || table == null || table.isDisposed() || this.data == null) {
            return;
        }
        table.setFocus();
        Point point = new Point(e.x, e.y);
        int row = CellHit.rowAt(table, point);
        int column = CellHit.columnAt(table, offset, point);
        if (row < 0 || column < 0) {
            clearSelection();
            return;
        }
        this.selecting = true;
        /*
         * Remember what was selected before this click shrank it. A platform
         * that reports the second half of a double click as another mouse down
         * would land on the cell that is already selected, so a repeat click on
         * the same cell keeps the older, wider block instead of overwriting it
         * with itself.
         */
        boolean repeat = hasSelection()
                && this.selAnchorRow == row && this.selFocusRow == row
                && this.selAnchorCol == column && this.selFocusCol == column;
        if (!repeat) {
            this.selectionBeforeClick = hasSelection()
                    ? new int[] { this.selAnchorRow, this.selAnchorCol, this.selFocusRow, this.selFocusCol }
                    : null;
        }
        this.lastClickRow = row;
        this.lastClickCol = column;
        setSelection(row, column, row, column);
    }

    private void extendSelection(Table table, int offset, Event e) {
        if ((e.stateMask & SWT.BUTTON1) == 0) {
            this.selecting = false;
        }
        if (!this.selecting || this.selAnchorRow < 0) {
            return;
        }
        Point point = new Point(e.x, e.y);
        int row = CellHit.rowAt(table, point);
        if (row < 0) {
            row = e.y < 0 ? 0 : Math.max(0, table.getItemCount() - 1);
        }
        int column = CellHit.columnAt(table, offset, point);
        if (column < 0) {
            column = e.x < 0 ? offset : offset + Math.max(0, table.getColumnCount() - 1);
        }
        setSelection(this.selAnchorRow, this.selAnchorCol, row, column);
    }

    private void setSelection(int anchorRow, int anchorCol, int focusRow, int focusCol) {
        int maxRow = Math.max(0, this.mainTable == null ? 0 : this.mainTable.getItemCount() - 1);
        int maxCol = Math.max(0, this.columns == null ? 0 : this.columns.length);
        int row = Math.min(Math.max(focusRow, 0), maxRow);
        int column = Math.min(Math.max(focusCol, 0), maxCol);
        if (anchorRow == this.selAnchorRow && anchorCol == this.selAnchorCol
                && row == this.selFocusRow && column == this.selFocusCol) {
            return;
        }

        boolean had = hasSelection();
        int oldMinRow = Math.min(this.selAnchorRow, this.selFocusRow);
        int oldMaxRow = Math.max(this.selAnchorRow, this.selFocusRow);
        int oldMinCol = Math.min(this.selAnchorCol, this.selFocusCol);
        int oldMaxCol = Math.max(this.selAnchorCol, this.selFocusCol);

        this.selAnchorRow = anchorRow;
        this.selAnchorCol = anchorCol;
        this.selFocusRow = row;
        this.selFocusCol = column;

        if (had) {
            // Repaint only the cells whose state changed: a drag fires on every
            // mouse move, and repainting a 100 x 100 page each time is visible.
            repaint(Math.min(oldMinRow, Math.min(anchorRow, row)), Math.max(oldMaxRow, Math.max(anchorRow, row)),
                    Math.min(oldMinCol, Math.min(anchorCol, column)),
                    Math.max(oldMaxCol, Math.max(anchorCol, column)));
        } else {
            repaint(Math.min(anchorRow, row), Math.max(anchorRow, row),
                    Math.min(anchorCol, column), Math.max(anchorCol, column));
        }
    }

    private void clearSelection() {
        if (this.selAnchorRow < 0) {
            return;
        }
        int minRow = Math.min(this.selAnchorRow, this.selFocusRow);
        int maxRow = Math.max(this.selAnchorRow, this.selFocusRow);
        int minCol = Math.min(this.selAnchorCol, this.selFocusCol);
        int maxCol = Math.max(this.selAnchorCol, this.selFocusCol);
        this.selAnchorRow = -1;
        this.selAnchorCol = -1;
        this.selFocusRow = -1;
        this.selFocusCol = -1;
        repaint(minRow, maxRow, minCol, maxCol);
    }

    /**
     * Redraws a block of cells, global columns, so the next paint asks
     * {@link #backgroundFor} again for every cell in it.
     * <p>
     * Only the block is invalidated: a drag fires on every mouse move, and
     * repainting a 100 x 100 page each time is visible.
     * </p>
     */
    private void repaint(int rowFrom, int rowTo, int colFrom, int colTo) {
        if (this.data == null) {
            return;
        }
        if (this.frozenTable != null && !this.frozenTable.isDisposed() && colFrom <= 0 && colTo >= 0) {
            int last = Math.min(rowTo, this.frozenTable.getItemCount() - 1);
            for (int i = Math.max(rowFrom, 0); i <= last; i++) {
                redrawCell(this.frozenTable, i, 0);
            }
        }
        if (this.mainTable == null || this.mainTable.isDisposed() || this.columns == null) {
            return;
        }
        int lo = Math.max(colFrom - 1, 0);
        int hi = Math.min(colTo - 1, this.columns.length - 1);
        int last = Math.min(rowTo, this.mainTable.getItemCount() - 1);
        for (int i = Math.max(rowFrom, 0); i <= last; i++) {
            for (int c = lo; c <= hi; c++) {
                redrawCell(this.mainTable, i, c);
            }
        }
    }

    /**
     * Invalidates one cell. A row that is scrolled out of sight has no
     * rectangle to invalidate, which is fine: it is erased again - and coloured
     * again - the moment it is scrolled back in.
     */
    private static void redrawCell(Table table, int row, int cell) {
        if (cell >= table.getColumnCount()) {
            return;
        }
        Rectangle bounds = table.getItem(row).getBounds(cell);
        if (bounds.width > 0 && bounds.height > 0) {
            table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
        }
    }

    private boolean hasSelection() {
        return this.selAnchorRow >= 0 && this.selFocusRow >= 0;
    }

    private boolean isSelected(int row, int column) {
        if (!hasSelection()) {
            return false;
        }
        int r0 = Math.min(this.selAnchorRow, this.selFocusRow);
        int r1 = Math.max(this.selAnchorRow, this.selFocusRow);
        int c0 = Math.min(this.selAnchorCol, this.selFocusCol);
        int c1 = Math.max(this.selAnchorCol, this.selFocusCol);
        return row >= r0 && row <= r1 && column >= c0 && column <= c1;
    }

    /** The moving end of the drag, drawn a shade darker so the block is readable. */
    private boolean isAnchorCell(int row, int column) {
        return hasSelection() && row == this.selFocusRow && column == this.selFocusCol;
    }

    /**
     * The strip under the table. It is attached to the table, not drawn as a
     * row inside it: a table row is a record, and a row that holds a check box
     * and a drop down breaks sorting and selection in ways users notice.
     */
    private void createBottomBar() {
        Composite bar = new Composite(this.tableGroup, SWT.NONE);
        bar.setLayoutData(new GridData(GridData.FILL, GridData.END, true, false));
        RowLayout row = new RowLayout(SWT.HORIZONTAL);
        row.center = true;
        row.spacing = 8;
        bar.setLayout(row);

        this.editToggle = new Button(bar, SWT.CHECK);
        this.editToggle.setText(Messages.get("window.edit"));
        this.editToggle.setToolTipText(Messages.get("window.edit.tip"));

        Label colorLabel = new Label(bar, SWT.NONE);
        colorLabel.setText(Messages.get("window.colour"));

        this.colorMode = new CCombo(bar, SWT.READ_ONLY | SWT.BORDER);
        this.colorMode.setItems(colorModes());
        this.colorMode.select(0);
        this.colorMode.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                heatMap.invalidate();
                applyColors();
            }
        });

        Button copy = new Button(bar, SWT.PUSH);
        copy.setText(Messages.get("window.copy"));
        copy.setToolTipText(Messages.get("window.copy.tip"));
        copy.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                copySelection();
            }
        });

        Button rules = new Button(bar, SWT.PUSH);
        rules.setText(Messages.get("window.columns"));
        boolean entityTable = this.model != null && this.model.isEntityTable();
        rules.setEnabled(entityTable);
        rules.setToolTipText(entityTable
                ? Messages.get("window.columns.tip")
                : Messages.get("window.columns.tip.disabled"));
        rules.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openRulesDialog();
            }
        });

        this.statusLabel = new Label(bar, SWT.NONE);
        this.statusLabel.setText(Messages.get("window.status.reading"));

        /*
         * Which jar is actually running. Eclipse keeps the plugin jar it read
         * at start-up, so a rebuild does nothing until the IDE is restarted -
         * and from the outside that looks exactly like "the fix did not work".
         * Showing the build time turns that into something you can read off the
         * window instead of guess at.
         */
        String stamp = buildStamp();
        if (!stamp.isEmpty()) {
            Label build = new Label(bar, SWT.NONE);
            build.setText(stamp);
            build.setForeground(this.display.getSystemColor(SWT.COLOR_DARK_GRAY));
            build.setToolTipText(Messages.bind("window.build.tip",
                    stamp.substring(BUILD_PREFIX.length())));
        }
    }

    /** The colour modes, in the order the combo box and the paint code assume. */
    private static String[] colorModes() {
        return new String[] {
                Messages.get("window.colour.heat"),
                Messages.get("window.colour.zebra"),
                Messages.get("window.colour.none") };
    }

    /** The compile time stamped into the jar's manifest, or "" when absent. */
    private static String buildStamp() {
        Activator plugin = Activator.getDefault();
        if (plugin == null) {
            return "";
        }
        Object time = plugin.getBundle().getHeaders().get("Build-Time");
        return time == null ? "" : BUILD_PREFIX + time;
    }

    // ------------------------------------------------------------------
    // which members become columns
    // ------------------------------------------------------------------

    /**
     * Opens the column picker for the types behind this table.
     * <p>
     * The lists are read on a job: listing a type's methods is a call into the
     * target, and a call on the UI thread is the one thing this window never
     * does. Nothing changes until the dialog is confirmed.
     * </p>
     */
    private void openRulesDialog() {
        final TableModel current = this.model;
        if (current == null || this.shell.isDisposed()) {
            return;
        }
        final EntityRule layout = current.getEntityRule();
        if (layout == null) {
            return;
        }
        final String name = safeName();
        Job reader = new Job(Messages.bind("job.members", name)) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                List<MemberCatalog.Type> types = MemberCatalog.of(current.sampleElement(),
                        layout.signature, current);
                display.asyncExec(() -> {
                    if (shell.isDisposed()) {
                        return;
                    }
                    EntityRuleDialog dialog = new EntityRuleDialog(shell, name, types,
                            layout.signature, layout.typeName, columnsShown(layout));
                    if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.getRules() != null) {
                        EntityRules.save(dialog.getRules());
                        reloadTable();
                    }
                });
                return Status.OK_STATUS;
            }
        };
        reader.setUser(false);
        reader.setRule(new TargetRule(safeTarget()));
        reader.schedule();
    }

    /** The column headers in the table right now, which the dialog starts from. */
    private static List<String> columnsShown(EntityRule layout) {
        List<String> labels = new ArrayList<String>();
        for (MemberRef member : layout.members) {
            labels.add(member.label);
        }
        return labels;
    }

    /**
     * Throws the tables away and reads the variable again.
     * <p>
     * The widgets only ever grow - rows are appended, columns are added - so a
     * column set that got smaller cannot be shown in place. Rebuilding is also
     * the honest answer to "the rule changed": nothing of the old layout
     * survives to be half applied.
     * </p>
     */
    private void reloadTable() {
        cancelJob();
        if (this.moreJob != null) {
            this.moreJob.cancel();
        }
        this.model = null;
        this.data = null;
        this.columns = null;
        this.frozenColumn = null;
        this.mainTable = null;
        this.frozenTable = null;
        this.renderedRows = 0;
        this.renderedCols = 0;
        this.heatMap.invalidate();
        this.loadingMore = false;
        this.selAnchorRow = -1;
        this.selAnchorCol = -1;
        this.selFocusRow = -1;
        this.selFocusCol = -1;
        this.selectionBeforeClick = null;
        this.editSelection = null;
        this.lastClickRow = -1;
        this.lastClickCol = -1;
        this.selecting = false;
        if (this.warningArea != null && !this.warningArea.isDisposed()) {
            this.warningArea.setVisible(false);
        }
        /*
         * The colours are owned by the table area, which is about to be
         * disposed; they have to go with it or every rebuild would leak a
         * handful of system brushes.
         */
        if (this.tableGroup != null && !this.tableGroup.isDisposed()) {
            this.tableGroup.dispose();
        }
        disposeColors();
        this.editToggle = null;
        this.colorMode = null;
        this.statusLabel = null;
        this.tableGroup = null;
        this.loadingArea = null;
        createTableGroup();
        /*
         * The warning area is a child of the shell and was created after the
         * table group, so a fresh group would otherwise appear below it.
         */
        if (this.warningArea != null && !this.warningArea.isDisposed()) {
            this.tableGroup.moveAbove(this.warningArea);
        }
        createLoadingArea();
        this.shell.layout(true, true);
        startFetch();
    }

    private void fitBounds() {
        int w = Config.minWindowsW;
        int h = Config.minWindowsH;
        if (this.frozenColumn != null && this.mainTable != null) {
            w = 60 + this.frozenColumn.getWidth();
            for (TableColumn c : this.columns == null ? new TableColumn[0] : this.columns) {
                w += c.getWidth();
            }
            h = (this.mainTable.getItemCount() + 1) * 25 + 170;
            w = Math.min(w, Config.screenWidth());
            h = Math.min(h, Config.screenHeight());
            if (w < Config.minWindowsW) {
                double d = (double) Config.minWindowsW / w;
                for (TableColumn c : this.columns == null ? new TableColumn[0] : this.columns) {
                    c.setWidth((int) (c.getWidth() * d));
                }
                w = Config.minWindowsW;
            }
            h = Math.max(h, Config.minWindowsH);
        }
        this.shell.setBounds((Config.screenWidth() - w) / 2, (Config.screenHeight() - h) / 2, w, h);
    }

    // ------------------------------------------------------------------
    // Background fetch
    // ------------------------------------------------------------------

    private void startFetch() {
        final String name = safeName();
        this.job = new Job(Messages.bind("job.read", name)) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                FetchContext context = new FetchContext(monitor, Config.getMaxRows(),
                        Config.isInvokeEnabled(), Config.isMonitorGuard(), Config.getBlacklist());
                TableData snapshot = null;
                Throwable failure = null;
                try {
                    snapshot = fetch(context);
                } catch (OperationCanceledException e) {
                    return Status.CANCEL_STATUS;
                } catch (Throwable t) {
                    failure = t;
                }
                final TableData result = snapshot;
                final Throwable error = failure;
                // display.asyncExec is the one SWT call allowed off the UI
                // thread; the display itself was captured on it.
                display.asyncExec(() -> {
                    if (shell.isDisposed()) {
                        return;
                    }
                    if (error != null) {
                        showError(error);
                    } else {
                        applyData(result);
                    }
                });
                if (error != null) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                            Messages.bind("error.status.read", name), error);
                }
                return Status.OK_STATUS;
            }
        };
        // Not a user job: the window itself shows progress, and a modal dialog
        // on top of a modal-ish debug session is more noise than information.
        this.job.setUser(false);
        // One fetch per debug target at a time, otherwise two windows would
        // interleave JDI commands on the same target.
        this.job.setRule(new TargetRule(safeTarget()));
        this.job.schedule();
    }

    /**
     * Runs on the job thread. Touches the debug model, never widgets.
     */
    private TableData fetch(FetchContext context) throws Exception {
        IJavaValue value = sourceValue();
        if (value != null
                && ValueModel.getUserClassType(value, Config.tableTypeMap) == null
                && !(value instanceof IJavaArray)
                && !ValueModel.hasInterface(value, "Ljava/util/Iterator;")
                && !ValueModel.hasInterface(value, "Ljava/lang/Iterable;")) {
            context.warn("no.rule", Messages.bind("warn.noRule", safeTypeName(value)));
        }
        TableModel tableModel = this.fixedValue != null
                ? new TableModel(this.fixedValue, context)
                : new TableModel(this.variable, context);
        // Kept for the follow-up pages; jobs on one target are serialised, so
        // no two of them ever touch it at the same time.
        this.model = tableModel;
        return tableModel.toData();
    }

    /**
     * Asks the model for the next page, then appends it. The read happens on a
     * job because a cell is a round trip, and the UI thread must never make one.
     */
    private void loadMore(final boolean rowsWanted, final boolean colsWanted) {
        if (this.loadingMore || this.model == null) {
            return;
        }
        final boolean rows = rowsWanted && this.model.getLoadedRows() < this.model.getTotalRows();
        final boolean cols = colsWanted && this.model.hasMoreColumns();
        if (!rows && !cols) {
            updateStatus();
            return;
        }
        this.loadingMore = true;
        if (this.statusLabel != null && !this.statusLabel.isDisposed()) {
            this.statusLabel.setText(Messages.get("window.status.readingMore"));
        }
        Job more = new Job(Messages.get("job.readMore")) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                TableData snapshot = null;
                Throwable failure = null;
                try {
                    TableModel m = model;
                    int wantRows = rows ? m.getLoadedRows() + TableModel.PAGE_ROWS : m.getLoadedRows();
                    int wantCols = cols ? m.getLoadedCols() + TableModel.PAGE_COLS : m.getLoadedCols();
                    m.ensureShape(wantRows, wantCols);
                    snapshot = m.toData();
                } catch (OperationCanceledException e) {
                    return Status.CANCEL_STATUS;
                } catch (Throwable t) {
                    failure = t;
                }
                final TableData result = snapshot;
                final Throwable error = failure;
                display.asyncExec(() -> {
                    loadingMore = false;
                    if (shell.isDisposed()) {
                        return;
                    }
                    if (error != null) {
                        // The table is already on screen; the loading area is
                        // gone, so the failure has to go into the status line.
                        statusLabel.setText(Messages.bind("window.status.readMoreFailed",
                                error.getClass().getSimpleName()
                                        + (error.getMessage() == null ? "" : " - " + error.getMessage())));
                    } else {
                        applyData(result);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        more.setUser(false);
        more.setRule(new TargetRule(safeTarget()));
        this.moreJob = more;
        more.schedule();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void applyData(TableData snapshot) {
        if (snapshot == null) {
            return;
        }
        /*
         * A saved column list that named members the class no longer has was
         * shortened while this table was read. Writing it here, on the UI
         * thread, is what makes the cleanup stick - the read itself runs on a
         * job, where notifying preference listeners would be a thread violation.
         */
        EntityRules.flush();
        this.data = snapshot;
        this.heatMap.invalidate();
        boolean firstRender = this.renderedRows == 0;
        if (this.mainTable == null || this.mainTable.isDisposed()) {
            this.createTableArea();
        } else if (hasSelection() && this.selFocusRow >= snapshot.getRowCount()) {
            clearSelection();
        }
        if (snapshot.getCaption() != null) {
            this.tableGroup.setText(snapshot.getCaption());
        }
        this.syncTable();
        this.applyColors();
        this.updateStatus();
        this.showWarnings(snapshot);
        if (firstRender) {
            this.fitBounds();
        }
        this.shell.layout(true, true);
    }

    /**
     * Copies the snapshot into the widgets, growing them where it grew.
     * <p>
     * Adding a column is nothing like adding a row to the native table. A row
     * is one new item; a column makes Windows insert a cell into <em>every</em>
     * item and lay the whole control out again, which measured at a quarter of
     * a second per column with a hundred rows on screen - a page of hundred
     * columns took 28 seconds, while a page of hundred rows took 35
     * milliseconds. The same loop with redraw switched off takes 7 ms, so the
     * cost is the repaints in between, not the work itself. Everything below
     * therefore runs with redraw off whenever the shape is about to change.
     * </p>
     */
    private void syncTable() {
        String[] headers = this.data.getColName();
        int dataColumns = Math.max(0, headers.length - 1);
        int rows = this.data.getRowCount();
        boolean grows = rows > this.renderedRows
                || dataColumns > (this.columns == null ? 0 : this.columns.length);
        if (grows) {
            this.mainTable.setRedraw(false);
            this.frozenTable.setRedraw(false);
        }
        try {
            syncColumns(headers, dataColumns);
            syncCells(dataColumns, rows);
        } finally {
            if (grows) {
                this.mainTable.setRedraw(true);
                this.frozenTable.setRedraw(true);
            }
        }
    }

    private void syncColumns(String[] headers, int dataColumns) {
        if (dataColumns > 0 && (this.columns == null || this.columns.length < dataColumns)) {
            TableColumn[] grown = new TableColumn[dataColumns];
            if (this.columns != null) {
                System.arraycopy(this.columns, 0, grown, 0, this.columns.length);
            }
            for (int c = this.columns == null ? 0 : this.columns.length; c < dataColumns; c++) {
                TableColumn column = new TableColumn(this.mainTable, SWT.NONE);
                column.setText(headers[c + 1] == null ? "" : headers[c + 1]);
                column.setWidth(CELL_W);
                grown[c] = column;
            }
            this.columns = grown;
        }
        for (int c = 0; this.columns != null && c < this.columns.length && c + 1 < headers.length; c++) {
            String text = headers[c + 1] == null ? "" : headers[c + 1];
            if (!text.equals(this.columns[c].getText())) {
                this.columns[c].setText(text);
            }
        }
    }

    private void syncCells(int dataColumns, int rows) {
        int firstNewRow = this.renderedRows;
        /* Columns already on screen; a new row needs all of them, an old one only the rest. */
        int knownCols = this.renderedCols;

        for (int i = firstNewRow; i < rows; i++) {
            new TableItem(this.frozenTable, SWT.NONE);
            new TableItem(this.mainTable, SWT.NONE);
        }
        for (int i = 0; i < rows; i++) {
            TableItem label = this.frozenTable.getItem(i);
            label.setData(ROW_KEY, Integer.valueOf(i));
            String name = this.data.getRowName()[i];
            String text = name == null ? "" : name;
            if (!text.equals(label.getText(0))) {
                label.setText(text);
            }
            TableItem item = this.mainTable.getItem(i);
            item.setData(ROW_KEY, Integer.valueOf(i));
            if (dataColumns == 0) {
                continue;
            }
            if (i >= firstNewRow) {
                String[] line = new String[dataColumns];
                for (int j = 0; j < dataColumns; j++) {
                    line[j] = this.data.getCell(i, j);
                }
                item.setText(line);
            } else if (knownCols < dataColumns) {
                /*
                 * Only the columns that just arrived: rebuilding the whole line
                 * for every loaded row would allocate and hand the platform
                 * thousands of strings it already has.
                 */
                for (int j = knownCols; j < dataColumns; j++) {
                    item.setText(j, this.data.getCell(i, j));
                }
            }
        }

        if (firstNewRow == 0) {
            this.autosizeColumns();
        } else {
            this.autosizeNewColumns();
        }
        this.renderedRows = rows;
        this.renderedCols = Math.max(knownCols, dataColumns);
        this.trimRows(rows);
        this.styleNullRows();
        this.syncTop();
    }

    /**
     * Draws a row with no object behind it in grey.
     * <p>
     * Such a row is kept where it is - dropping it would renumber the rows
     * around it and hide the empty slot - but its cells are blank by design, and
     * a blank row with no mark of its own reads as data that failed to load.
     * The status line says how many such rows there are.
     * </p>
     */
    private void styleNullRows() {
        if (this.data == null || this.mainTable == null || this.mainTable.isDisposed()) {
            return;
        }
        Color normal = this.mainTable.getForeground();
        Color grey = this.display.getSystemColor(SWT.COLOR_DARK_GRAY);
        for (int i = 0; i < this.renderedRows; i++) {
            Color color = this.data.isEditableRow(i) ? normal : grey;
            this.mainTable.getItem(i).setForeground(color);
            this.frozenTable.getItem(i).setForeground(color);
        }
    }

    /** Drops items when a snapshot is smaller than what is on screen. */
    private void trimRows(int rows) {
        for (Table t : new Table[] { this.frozenTable, this.mainTable }) {
            while (t.getItemCount() > rows) {
                t.getItem(t.getItemCount() - 1).dispose();
            }
        }
    }

    private void autosizeColumns() {
        setFrozenWidth(widthOf(0));
        for (int c = 0; this.columns != null && c < this.columns.length; c++) {
            this.columns[c].setWidth(widthOf(c + 1));
        }
    }

    private void autosizeNewColumns() {
        // Index labels get wider as rows are added ("99" -> "1000").
        setFrozenWidth(widthOf(0));
        for (int c = 0; this.columns != null && c < this.columns.length; c++) {
            if (this.columns[c].getWidth() <= CELL_W) {
                this.columns[c].setWidth(widthOf(c + 1));
            }
        }
    }

    private void setFrozenWidth(int width) {
        int w = Math.max(INDEX_W, Math.min(width, MAX_COL_W));
        this.frozenColumn.setWidth(w);
        Object layout = this.frozenTable.getLayoutData();
        if (layout instanceof GridData) {
            ((GridData) layout).widthHint = w + 6;
        }
    }

    /** Width of a column given its global index, from header plus the first rows. */
    private int widthOf(int globalColumn) {
        Table table = globalColumn == 0 ? this.frozenTable : this.mainTable;
        int cell = globalColumn == 0 ? 0 : globalColumn - 1;
        if (table == null || table.isDisposed() || cell >= table.getColumnCount()) {
            return CELL_W;
        }
        int w = Util.getStringW(table.getColumn(cell).getText());
        int rows = Math.min(table.getItemCount(), 40);
        for (int i = 0; i < rows; i++) {
            int textW = Util.getStringW(table.getItem(i).getText(cell));
            if (textW > w) {
                w = textW;
            }
        }
        return Math.max(globalColumn == 0 ? INDEX_W - 6 : 50, Math.min(w, MAX_COL_W));
    }

    private void updateStatus() {
        if (this.statusLabel == null || this.statusLabel.isDisposed() || this.data == null) {
            return;
        }
        int shown = this.data.getRowCount();
        int total = this.data.getTotalRows();
        int cols = this.data.getColCount();
        int totalCols = this.data.getTotalColumns();

        /*
         * "100 / 5000 rows" while more is available, "100 rows" once everything
         * is loaded. Built from whole phrases rather than by gluing numbers to a
         * unit, because the order of the two differs between languages.
         */
        StringBuilder sb = new StringBuilder();
        sb.append(total > shown
                ? Messages.bind("window.status.rows", shown + " / " + total)
                : Messages.bind("window.status.rowCount", shown));
        sb.append(" · ");
        sb.append(totalCols > cols
                ? Messages.bind("window.status.cols", cols + " / " + totalCols)
                : Messages.bind("window.status.colCount", cols));
        int nullRows = this.data.getNullRowCount();
        if (nullRows > 0) {
            // A blank row that is really an empty slot, not data that failed.
            sb.append(" · ").append(Messages.bind("window.status.nullRows", nullRows));
        }
        if (total > shown || totalCols > cols) {
            sb.append(" · ").append(Messages.get("window.status.more"));
        }
        this.statusLabel.setText(sb.toString());
    }

    // ------------------------------------------------------------------
    // Colouring
    // ------------------------------------------------------------------

    /**
     * PyCharm style: numeric columns get a red/blue heat map scaled to the
     * column's own range, everything else falls back to zebra stripes. Colouring
     * a column by its own range (rather than the table's) is what makes a small
     * column next to a column of millions readable.
     */
    private void applyColors() {
        if (this.mainTable == null || this.mainTable.isDisposed() || this.data == null) {
            return;
        }
        if (this.columns == null || this.columns.length == 0) {
            return;
        }
        int rows = Math.min(this.mainTable.getItemCount(), this.data.getRowCount());
        this.heatMap.ensureRanges(this.data, rows, this.columns.length);
        /*
         * Nothing to apply cell by cell: the colours are chosen while a cell is
         * painted, so invalidating the two tables is all it takes, and only the
         * cells on screen cost anything.
         */
        if (this.frozenTable != null && !this.frozenTable.isDisposed()) {
            this.frozenTable.redraw();
        }
        this.mainTable.redraw();
    }

    /**
     * One cell's colour, and the only place selection, heat map and zebra
     * stripes are combined. Selection wins: while a block is being dragged out,
     * the block is what the user is looking at.
     */
    private Color backgroundFor(TableItem item, int row, int globalColumn, int cell, int mode, Color plain) {
        if (isSelected(row, globalColumn)) {
            return isAnchorCell(row, globalColumn) ? this.selEdgeColor : this.selColor;
        }
        if (mode == 0 && globalColumn > 0) {
            Color color = this.heatMap.colorFor(globalColumn, item.getText(cell));
            if (color != null) {
                return color;
            }
        }
        if (mode == 1 && (row % 2) == 1) {
            return this.zebraColor;
        }
        return plain;
    }

    private void disposeColors() {
        if (this.heatMap != null) {
            this.heatMap.dispose();
        }
        if (this.zebraColor != null && !this.zebraColor.isDisposed()) {
            this.zebraColor.dispose();
        }
        if (this.selColor != null && !this.selColor.isDisposed()) {
            this.selColor.dispose();
        }
        if (this.selEdgeColor != null && !this.selEdgeColor.isDisposed()) {
            this.selEdgeColor.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Editing and copying
    // ------------------------------------------------------------------

    private void startCellEditor(Table table, int offset, MouseEvent e) {
        if (this.editToggle == null || !this.editToggle.getSelection() || this.data == null) {
            return;
        }
        if (table != this.mainTable || this.columns == null || this.columns.length == 0) {
            return;
        }
        Point point = new Point(e.x, e.y);
        final int row = CellHit.rowAt(table, point);
        final int column = CellHit.columnAt(table, offset, point);
        if (row < 0 || column <= 0 || !this.data.isEditableCell(row, column)) {
            return;
        }
        final int cell = column - 1;
        final TableItem item = table.getItem(row);

        this.editSelection = this.lastClickRow == row && this.lastClickCol == column
                ? blockAround(row, column)
                : null;
        this.selectionBeforeClick = null;

        final TableEditor editor = new TableEditor(table);
        final Text text = new Text(table, SWT.NONE);
        text.setText(item.getText(cell));
        text.selectAll();
        text.setFocus();
        editor.setEditor(text, item, cell);
        editor.grabHorizontal = true;

        final boolean[] discarded = { false };
        final Runnable close = () -> {
            if (text.isDisposed()) {
                return;
            }
            String value = text.getText();
            /*
             * TableEditor.dispose() only stops the editor from tracking the
             * cell - the text field itself stays alive, sitting white on top of
             * the cell it was editing. One of those per edit is what leaves a
             * trail of colourless cells behind, and the last one keeps the focus
             * and swallows Ctrl+C, so it copies its own one value.
             */
            editor.dispose();
            text.dispose();
            restoreEditSelection(row, column);
            if (!discarded[0] && !value.equals(item.getText(cell))) {
                commitCell(row, column, value);
            }
        };

        /*
         * Disposing the editor from inside its own focus event throws on some
         * platforms, so closing is deferred to the next event loop turn.
         */
        final Runnable commit = () -> this.display.asyncExec(close);
        text.addFocusListener(new org.eclipse.swt.events.FocusAdapter() {
            @Override
            public void focusLost(org.eclipse.swt.events.FocusEvent event) {
                // Before the commit is queued, so that a click on the copy
                // button copies the block rather than the cell being edited.
                restoreEditSelection(row, column);
                commit.run();
            }
        });
        text.addTraverseListener(event -> {
            if (event.detail == SWT.TRAVERSE_RETURN || event.detail == SWT.TRAVERSE_ESCAPE) {
                event.doit = false;
                discarded[0] = event.detail == SWT.TRAVERSE_ESCAPE;
                restoreEditSelection(row, column);
                // Back to the table at once: the next Ctrl+C has to be the
                // table's. While this text field has the focus it would copy
                // the one value being edited and nothing else.
                this.mainTable.setFocus();
                commit.run();
            }
        });
        /*
         * Ctrl+C while the editor is open, untouched, would copy the one value
         * in the text field - which reads as "the block I dragged out was lost".
         * When the field still holds the original value, whole and selected,
         * the user is not trying to lift a value out of it: close the editor and
         * copy the block, exactly like the copy button. A partly selected or
         * edited field keeps the ordinary text behaviour.
         */
        text.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (!Keys.isCopy(event)) {
                    return;
                }
                if (!text.getText().equals(item.getText(cell))
                        || text.getSelectionCount() != text.getText().length()) {
                    return;
                }
                event.doit = false;
                restoreEditSelection(row, column);
                TableWindow.this.copySelection();
                TableWindow.this.mainTable.setFocus();
                commit.run();
            }
        });
    }

    /**
     * The block the click replaced, but only when it is a block and only when
     * it contains the cell being edited: changing a value in the middle of a
     * dragged block should not cost the user the block, while a click that
     * already moved on to another cell has said what it wants.
     *
     * @return {anchorRow, anchorCol, focusRow, focusCol}, or null for nothing
     */
    private int[] blockAround(int row, int column) {
        int[] saved = this.selectionBeforeClick;
        if (saved == null || (saved[0] == saved[2] && saved[1] == saved[3])) {
            return null;
        }
        int r0 = Math.min(saved[0], saved[2]);
        int r1 = Math.max(saved[0], saved[2]);
        int c0 = Math.min(saved[1], saved[3]);
        int c1 = Math.max(saved[1], saved[3]);
        return row >= r0 && row <= r1 && column >= c0 && column <= c1 ? saved : null;
    }

    /**
     * Puts that block back when the editor closes, so a value can be changed in
     * the middle of a dragged block and the block is still there to copy.
     * <p>
     * Only restored while the selection is still the single edited cell: a click
     * on some other cell means the user has already chosen what they want, and
     * putting the old block back would take that away again.
     * </p>
     */
    private void restoreEditSelection(int row, int column) {
        int[] saved = this.editSelection;
        this.editSelection = null;
        if (saved == null || !hasSelection()) {
            return;
        }
        boolean stillThatCell = Math.min(this.selAnchorRow, this.selFocusRow) == row
                && Math.max(this.selAnchorRow, this.selFocusRow) == row
                && Math.min(this.selAnchorCol, this.selFocusCol) == column
                && Math.max(this.selAnchorCol, this.selFocusCol) == column;
        if (stillThatCell) {
            setSelection(saved[0], saved[1], saved[2], saved[3]);
        }
    }

    private void commitCell(final int row, final int column, final String value) {
        if (this.model == null) {
            return;
        }
        Job writer = new Job(Messages.get("job.write")) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                String now;
                try {
                    now = model.setCell(row, column - 1, value);
                } catch (Throwable t) {
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            org.eclipse.jface.dialogs.MessageDialog.openError(shell, "ValueTable",
                                    Messages.bind("window.error.write", t.getClass().getSimpleName(),
                                            String.valueOf(t.getMessage())));
                        }
                    });
                    return new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                            Messages.get("error.status.write"), t);
                }
                final String text = now == null ? "" : now;
                display.asyncExec(() -> {
                    if (shell.isDisposed() || mainTable.isDisposed()) {
                        return;
                    }
                    TableItem item = mainTable.getItem(row);
                    item.setText(column - 1, text);
                    // The new value may move the column's heat range.
                    heatMap.invalidate();
                    applyColors();
                });
                return Status.OK_STATUS;
            }
        };
        writer.setUser(false);
        writer.setRule(new TargetRule(safeTarget()));
        writer.schedule();
    }

    /**
     * Copies the dragged block as tab separated text - the shape a spreadsheet
     * expects. With nothing selected the whole loaded table is copied, which is
     * what a plain Ctrl+C on a table means everywhere else.
     */
    private void copySelection() {
        if (this.mainTable == null || this.mainTable.isDisposed() || this.columns == null) {
            return;
        }
        int lastRow = Math.min(this.mainTable.getItemCount(), this.frozenTable.getItemCount()) - 1;
        int lastCol = this.columns.length;
        if (lastRow < 0 || lastCol < 0) {
            return;
        }
        int r0;
        int r1;
        int c0;
        int c1;
        if (hasSelection()) {
            r0 = Math.min(this.selAnchorRow, this.selFocusRow);
            r1 = Math.min(Math.max(this.selAnchorRow, this.selFocusRow), lastRow);
            c0 = Math.min(this.selAnchorCol, this.selFocusCol);
            c1 = Math.min(Math.max(this.selAnchorCol, this.selFocusCol), lastCol);
        } else {
            r0 = 0;
            r1 = lastRow;
            c0 = 0;
            c1 = lastCol;
        }

        StringBuilder text = new StringBuilder();
        for (int i = r0; i <= r1; i++) {
            if (i > r0) {
                text.append('\n');
            }
            for (int c = c0; c <= c1; c++) {
                if (c > c0) {
                    text.append('\t');
                }
                text.append(textAt(i, c));
            }
        }
        Clipboard clipboard = new Clipboard(this.display);
        try {
            clipboard.setContents(new Object[] { text.toString() },
                    new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }

        if (this.statusLabel != null && !this.statusLabel.isDisposed()) {
            this.statusLabel.setText(Messages.bind("window.status.copied", r1 - r0 + 1, c1 - c0 + 1));
            this.display.timerExec(1500, this::updateStatus);
        }
    }

    /** One cell as text, by global column. */
    private String textAt(int row, int column) {
        if (column == 0) {
            return this.frozenTable.getItem(row).getText(0);
        }
        return this.mainTable.getItem(row).getText(column - 1);
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    private void showWarnings(TableData snapshot) {
        if (snapshot.getWarnings().isEmpty()) {
            if (this.warningArea != null && !this.warningArea.isDisposed()) {
                this.warningArea.setVisible(false);
                ((GridData) this.warningArea.getLayoutData()).heightHint = 0;
                this.warningArea.getParent().layout(true, true);
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : snapshot.getWarnings()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(w);
        }
        if (this.warningArea == null || this.warningArea.isDisposed()) {
            this.warningArea = new Text(this.shell, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.BORDER);
            this.warningArea.setLayoutData(new GridData(GridData.FILL, GridData.END, true, false, 1, 1));
        }
        this.warningArea.setText(sb.toString());
        this.warningArea.setVisible(true);
        ((GridData) this.warningArea.getLayoutData()).heightHint = 66;
        this.shell.layout(true, true);
    }

    private void showError(Throwable t) {
        if (this.loadingLabel == null || this.loadingLabel.isDisposed()) {
            return;
        }
        if (this.progressBar != null && !this.progressBar.isDisposed()) {
            this.progressBar.setVisible(false);
        }
        this.loadingLabel.setForeground(this.loadingLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        this.loadingLabel.setText(Messages.bind("window.error.read", t.getClass().getSimpleName(),
                String.valueOf(t.getMessage())));
        this.shell.layout(true, true);
    }

    private void cancelJob() {
        if (this.job != null) {
            this.job.cancel();
        }
        if (this.moreJob != null) {
            this.moreJob.cancel();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String safeName() {
        return this.title;
    }

    /** The value behind the table, whichever way the window was opened. */
    private IJavaValue sourceValue() throws Exception {
        return this.fixedValue != null ? this.fixedValue : (IJavaValue) this.variable.getValue();
    }

    private static String variableTitle(IJavaVariable variable) {
        try {
            return variable.getName();
        } catch (Exception e) {
            Activator.log("cannot read the variable name", e);
            return "ValueTable";
        }
    }

    private static String safeTypeName(IJavaValue value) {
        try {
            return value.getReferenceTypeName();
        } catch (Exception e) {
            return "this type";
        }
    }

    /**
     * The object the scheduling rule is keyed on. Falls back to the variable
     * itself when the debug target is not reachable any more.
     */
    private Object safeTarget() {
        Object fallback = this.variable != null ? this.variable : this.fixedValue;
        Object source = fallback;
        try {
            IDebugTarget target;
            if (this.fixedValue != null) {
                target = this.fixedValue.getDebugTarget();
            } else {
                target = this.variable.getDebugTarget();
            }
            return target != null ? target : source;
        } catch (Exception e) {
            return source;
        }
    }
}
