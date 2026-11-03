package com.value.table.util;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Maps a mouse point to a cell of a {@link Table}.
 * <p>
 * This cannot use {@code Table.getItem(Point)}: on win32 that method tests the
 * point against the row rectangle, and the row rectangle only covers the first
 * column (a whole 16 column row reports a width of 26 pixels). Every click
 * outside the label column therefore misses the row, which silently disables
 * selection and editing rather than failing loudly.
 * </p>
 * <p>
 * The row is computed from the header height and the item height, then nudged
 * by one row if the measured cell bounds disagree, which covers a table that is
 * scrolled by a pixel rather than by a whole row. The column is read from the
 * measured cell bounds, which do report every column correctly.
 * </p>
 */
public final class CellHit {

    private CellHit() {
    }

    /** The row under a point, or -1 when the point is outside the rows. */
    public static int rowAt(Table table, Point point) {
        if (table == null || table.isDisposed()) {
            return -1;
        }
        int count = table.getItemCount();
        int height = table.getItemHeight();
        int header = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
        /*
         * A point below the last visible row is not a row. Without this a drag
         * past the bottom edge would select whichever row the division happened
         * to land on instead of stopping at the end of what is on screen.
         */
        int widgetHeight = table.getBounds().height;
        if (count == 0 || height <= 0 || point.y < header
                || (widgetHeight > 0 && point.y >= widgetHeight)) {
            return -1;
        }
        int row = table.getTopIndex() + (point.y - header) / height;
        if (row < 0 || row >= count) {
            return -1;
        }
        Rectangle bounds = table.getItem(row).getBounds(0);
        if (bounds.height > 0) {
            if (point.y < bounds.y && row > 0) {
                row--;
            } else if (point.y >= bounds.y + bounds.height && row < count - 1) {
                row++;
            }
        }
        return row;
    }

    /**
     * The column under a point as a global index: {@code offset} plus the cell
     * whose bounds contain it. A click past the last column counts as the last
     * column, which is what aiming at the widest cell means.
     */
    public static int columnAt(Table table, int offset, Point point) {
        int count = table == null || table.isDisposed() ? 0 : table.getColumnCount();
        if (count == 0) {
            return -1;
        }
        int row = rowAt(table, point);
        if (row < 0) {
            return -1;
        }
        TableItem item = table.getItem(row);
        for (int c = 0; c < count; c++) {
            Rectangle bounds = item.getBounds(c);
            if (bounds.width > 0 && point.x >= bounds.x && point.x < bounds.x + bounds.width) {
                return offset + c;
            }
        }
        Rectangle last = item.getBounds(count - 1);
        if (last.width > 0 && point.x >= last.x + last.width) {
            return offset + count - 1;
        }
        return -1;
    }
}
