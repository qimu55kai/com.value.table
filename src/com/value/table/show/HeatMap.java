package com.value.table.show;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

import com.value.table.model.TableData;

/**
 * The heat map: one colour per numeric cell, scaled to the range of its own
 * column rather than to the table's.
 * <p>
 * Scaling per column is what makes a column of percentages readable next to a
 * column of millions. The ramp runs blue (low) - white (middle) - red (high),
 * and a column whose values cannot be parsed, or whose values are all equal,
 * simply stays uncoloured.
 * </p>
 * <p>
 * The colours are created once per window and disposed with it; SWT hands out
 * system resources, and one brush per render would exhaust them. Nothing here
 * touches a widget, which is what keeps the arithmetic testable.
 * </p>
 */
final class HeatMap {

    private static final int STEPS = 32;

    /** Below two rows a range says nothing about a column. */
    private static final int MIN_ROWS = 2;

    private final Display display;
    private final Color[] ramp = new Color[STEPS + 1];

    /** Per data column {min, max}, or null when that column is not numeric. */
    private double[][] ranges;
    private boolean dirty = true;

    HeatMap(Display display) {
        this.display = display;
    }

    /** The data or the colour mode changed: the ranges have to be read again. */
    void invalidate() {
        this.dirty = true;
    }

    /**
     * Recomputes the per-column ranges when needed.
     *
     * @param data    snapshot on screen
     * @param rows    rows that are loaded
     * @param columns number of data columns (the label column is not counted)
     */
    void ensureRanges(TableData data, int rows, int columns) {
        if (!this.dirty && this.ranges != null && this.ranges.length == columns) {
            return;
        }
        this.ranges = compute(data, rows, columns);
        this.dirty = false;
    }

    /**
     * The colour for one cell.
     *
     * @param globalColumn column index as the window counts it, 1..n
     * @param text         the cell's text, as shown
     * @return null when the cell should keep the table's background
     */
    Color colorFor(int globalColumn, String text) {
        if (globalColumn <= 0 || this.ranges == null || globalColumn - 1 >= this.ranges.length) {
            return null;
        }
        double[] range = this.ranges[globalColumn - 1];
        if (range == null) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            // A stray "null" in an otherwise numeric column.
            return null;
        }
        return rampStep(range[0], range[1], value);
    }

    private Color rampStep(double min, double max, double value) {
        double t = max > min ? (value - min) / (max - min) : 0.5;
        int step = (int) Math.round(t * STEPS);
        step = Math.max(0, Math.min(STEPS, step));
        if (this.ramp[step] == null) {
            this.ramp[step] = create(step);
        }
        return this.ramp[step];
    }

    private double[][] compute(TableData data, int rows, int columns) {
        double[][] computed = new double[columns][];
        if (data == null || rows < MIN_ROWS) {
            return computed;
        }
        for (int c = 0; c < columns; c++) {
            if (!data.isNumericColumn(c + 1)) {
                continue;
            }
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            boolean any = false;
            for (int i = 0; i < rows; i++) {
                String text = data.getCell(i, c);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                try {
                    double v = Double.parseDouble(text.trim());
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    any = true;
                } catch (NumberFormatException e) {
                    // Ignored: the cell stays uncoloured.
                }
            }
            if (any && max > min) {
                computed[c] = new double[] { min, max };
            }
        }
        return computed;
    }

    /** Blue at the low end, white in the middle, red at the high end. */
    private Color create(int step) {
        int half = STEPS / 2;
        int r;
        int g;
        int b;
        if (step <= half) {
            double t = (double) step / half;
            r = (int) Math.round(222 + (255 - 222) * t);
            g = (int) Math.round(235 + (255 - 235) * t);
            b = (int) Math.round(247 + (255 - 247) * t);
        } else {
            double t = (double) (step - half) / (STEPS - half);
            r = (int) Math.round(255 + (248 - 255) * t);
            g = (int) Math.round(255 + (206 - 255) * t);
            b = (int) Math.round(255 + (204 - 255) * t);
        }
        return new Color(this.display, r, g, b);
    }

    /** Frees every brush; the window calls this when it closes or rebuilds. */
    void dispose() {
        for (int i = 0; i < this.ramp.length; i++) {
            if (this.ramp[i] != null && !this.ramp[i].isDisposed()) {
                this.ramp[i].dispose();
            }
            this.ramp[i] = null;
        }
        this.ranges = null;
        this.dirty = true;
    }

    /** Kept for the tests: the step count, so a ramp change is visible there. */
    static int steps() {
        return STEPS;
    }
}
