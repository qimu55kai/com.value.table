package com.value.table;

import org.eclipse.ui.IStartup;

import com.value.table.handlers.ExpressionDoubleClickHook;

/**
 * Runs once per workbench start, on the UI thread, while the workbench is being
 * built - the one place from which a hook can be attached to a view that is not
 * ours.
 * <p>
 * It does nothing but install the double click hook; the plug-in's real work
 * happens when the user asks for a table.
 * </p>
 */
public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        ExpressionDoubleClickHook.install();
    }
}
