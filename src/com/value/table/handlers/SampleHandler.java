package com.value.table.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com.value.table.show.TableOpener;

/**
 * Reads what is selected in a debug view - a variable in the Variables view, or
 * an expression in the Expressions view - and shows it as a table.
 * <p>
 * The selection is obtained through the public workbench selection service
 * instead of reaching into {@code org.eclipse.debug.internal.ui}, which is not
 * a stable API and is restricted to friend bundles.
 * </p>
 */
public class SampleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        }
        if (window == null) {
            return null;
        }
        final Shell shell = window.getShell();
        IStructuredSelection selection = getStructuredSelection(event, window);
        if (selection == null || selection.isEmpty()) {
            return null;
        }

        /*
         * No debug model access happens here: reading a variable means a round
         * trip to the target VM, and one of those can block forever. The window
         * opens immediately and a background job fills it.
         */
        for (Object element : selection.toList()) {
            if (element instanceof IJavaVariable || element instanceof IExpression) {
                TableOpener.open(shell, element);
            }
        }
        return null;
    }

    /**
     * Prefers the selection carried by the command event, and falls back to the
     * active part selection provider (which is what the toolbar button needs,
     * because the toolbar itself is not a selection provider).
     */
    private IStructuredSelection getStructuredSelection(ExecutionEvent event, IWorkbenchWindow window) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (isUsable(selection)) {
            return (IStructuredSelection) selection;
        }

        ISelectionService service = window.getSelectionService();
        if (service != null) {
            selection = service.getSelection();
            if (isUsable(selection)) {
                return (IStructuredSelection) selection;
            }
        }

        IWorkbenchPage page = window.getActivePage();
        if (page != null) {
            IWorkbenchPart part = page.getActivePart();
            if (part != null && part.getSite() != null
                    && part.getSite().getSelectionProvider() != null) {
                selection = part.getSite().getSelectionProvider().getSelection();
                if (isUsable(selection)) {
                    return (IStructuredSelection) selection;
                }
            }
        }
        return null;
    }

    private static boolean isUsable(ISelection selection) {
        return selection instanceof IStructuredSelection && !selection.isEmpty();
    }
}
