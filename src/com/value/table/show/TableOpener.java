package com.value.table.show;

import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import com.value.table.Activator;
import com.value.table.Messages;

/**
 * Turns whatever a debug view has selected into a table window.
 * <p>
 * Two shapes reach this class. A variable - from the Variables view, from
 * Inspect, from a hover - is read through {@link IJavaVariable}. An expression
 * is not a variable: the Expressions view selects {@link IExpression}, whose
 * value is the result of the last evaluation, and that is all the table needs.
 * </p>
 * <p>
 * Everything here runs on the UI thread, but nothing here calls into the target
 * beyond asking an expression for a value it already holds: the window opens
 * immediately and a job does the reading.
 * </p>
 */
public final class TableOpener {

    /** The Expressions view, by the id the platform gives it. */
    public static final String EXPRESSION_VIEW_ID = "org.eclipse.debug.ui.ExpressionView";

    /** A window title is not the place for a 300 character expression. */
    private static final int MAX_TITLE = 60;

    private TableOpener() {
    }

    /**
     * @return true when a window was opened; false when the element holds
     *         nothing that can be shown as a table
     */
    public static boolean open(Shell shell, Object element) {
        if (element instanceof IJavaVariable) {
            return openVariable(shell, (IJavaVariable) element);
        }
        if (element instanceof IExpression) {
            return openExpression(shell, (IExpression) element);
        }
        return false;
    }

    private static boolean openVariable(Shell shell, IJavaVariable variable) {
        try {
            new TableWindow(shell, variable);
            return true;
        } catch (LinkageError e) {
            /*
             * Rebuilding the jar under a running Eclipse leaves the JVM holding
             * the old file, so classes it has not loaded yet cannot be read at
             * all. Catching it turns a silent no-op into an instruction the user
             * can act on.
             */
            Activator.log("cannot load the table window", e);
            MessageDialog.openError(shell, "ValueTable",
                    Messages.bind("error.linkage", String.valueOf(e.getMessage())));
            return false;
        } catch (Exception e) {
            Activator.log("cannot show a variable as a table", e);
            MessageDialog.openError(shell, "ValueTable",
                    Messages.bind("error.open", e.getClass().getSimpleName(),
                            String.valueOf(e.getMessage())));
            return false;
        }
    }

    private static boolean openExpression(Shell shell, IExpression expression) {
        IValue value;
        String text;
        try {
            // The view evaluated this already; asking for it does not run the
            // expression again.
            value = expression.getValue();
            text = expression.getExpressionText();
        } catch (Exception e) {
            Activator.log("cannot read the selected expression", e);
            MessageDialog.openError(shell, "ValueTable",
                    Messages.bind("error.expression.read", e.getClass().getSimpleName(),
                            String.valueOf(e.getMessage())));
            return false;
        }
        if (!(value instanceof IJavaValue)) {
            MessageDialog.openInformation(shell, "ValueTable", Messages.get("info.expression.noValue"));
            return false;
        }
        try {
            new TableWindow(shell, (IJavaValue) value, titleOf(text));
            return true;
        } catch (LinkageError e) {
            Activator.log("cannot load the table window", e);
            MessageDialog.openError(shell, "ValueTable",
                    Messages.bind("error.linkage", String.valueOf(e.getMessage())));
            return false;
        } catch (Exception e) {
            Activator.log("cannot show an expression as a table", e);
            MessageDialog.openError(shell, "ValueTable",
                    Messages.bind("error.open", e.getClass().getSimpleName(),
                            String.valueOf(e.getMessage())));
            return false;
        }
    }

    private static String titleOf(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Messages.get("window.title.expression");
        }
        String one = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return one.length() <= MAX_TITLE ? one : one.substring(0, MAX_TITLE) + "...";
    }
}
