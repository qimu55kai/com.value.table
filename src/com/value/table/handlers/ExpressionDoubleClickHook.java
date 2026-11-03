package com.value.table.handlers;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.debug.core.model.IExpression;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.value.table.Activator;
import com.value.table.show.TableOpener;

/**
 * Opens the table of an expression when its row is double clicked.
 * <p>
 * The platform has an extension point for context menus but none for a double
 * click inside somebody else's view, so the control of the Expressions view is
 * hooked here: a part listener notices the view, and one {@code MouseDoubleClick}
 * listener on its viewer's tree is what makes "click it to see it" work.
 * </p>
 * <p>
 * The hook marks the control it already hooked, because a view is reported as
 * opened, visible and activated in turn - without the mark the same control
 * would get the listener three times and open three windows per click. A view
 * that is closed and reopened gets a new control, which is hooked again.
 * </p>
 */
public final class ExpressionDoubleClickHook implements IWindowListener {

    /** Key on the control, so one control is hooked exactly once. */
    private static final String HOOKED = "com.value.table.expressionDoubleClick";

    /**
     * Pages that already have the listener. Weak keys: a closed page must not be
     * kept alive by this class, and its listener goes with it.
     */
    private static final Set<IWorkbenchPage> PAGES = Collections
            .newSetFromMap(new WeakHashMap<IWorkbenchPage, Boolean>());

    private static final IPartListener2 PARTS = new PartHook();

    private ExpressionDoubleClickHook() {
    }

    /** Called once from the early startup. Runs on the UI thread. */
    public static void install() {
        ExpressionDoubleClickHook hook = new ExpressionDoubleClickHook();
        try {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                hook.windowOpened(window);
            }
            PlatformUI.getWorkbench().addWindowListener(hook);
        } catch (Exception e) {
            Activator.log("cannot watch the Expressions view for double clicks", e);
        }
    }

    @Override
    public void windowOpened(IWorkbenchWindow window) {
        final IWorkbenchPage[] pages = window.getPages();
        for (IWorkbenchPage page : pages) {
            hookPage(page);
        }
        window.addPageListener(new IPageListener() {
            @Override
            public void pageOpened(IWorkbenchPage page) {
                hookPage(page);
            }

            @Override
            public void pageActivated(IWorkbenchPage page) {
                hookPage(page);
            }

            @Override
            public void pageClosed(IWorkbenchPage page) {
                // The listener goes with the page.
            }
        });
    }

    @Override
    public void windowActivated(IWorkbenchWindow window) {
        // Nothing to do: the page listener covers pages opened later.
    }

    @Override
    public void windowDeactivated(IWorkbenchWindow window) {
        // Nothing to do.
    }

    @Override
    public void windowClosed(IWorkbenchWindow window) {
        // The listeners go with the window.
    }

    private static void hookPage(IWorkbenchPage page) {
        if (page == null) {
            return;
        }
        if (PAGES.add(page)) {
            page.addPartListener(PARTS);
        }
        /*
         * A view that was already open when this plug-in was loaded never sends
         * partOpened, so the ones that exist now are hooked directly.
         */
        for (IViewReference reference : page.getViewReferences()) {
            hookPart(reference.getPart(false));
        }
    }

    private static void hookPart(IWorkbenchPart part) {
        if (part == null || part.getSite() == null) {
            return;
        }
        if (!TableOpener.EXPRESSION_VIEW_ID.equals(part.getSite().getId())) {
            return;
        }
        final ISelectionProvider provider = part.getSite().getSelectionProvider();
        if (!(provider instanceof StructuredViewer)) {
            return;
        }
        final Control control = ((StructuredViewer) provider).getControl();
        if (control == null || control.isDisposed() || control.getData(HOOKED) != null) {
            return;
        }
        control.setData(HOOKED, Boolean.TRUE);
        control.addListener(SWT.MouseDoubleClick, event -> {
            /*
             * A double click on the empty area below the rows keeps the last
             * selection, and would otherwise reopen the same table.
             */
            if (!overARow(control, event)) {
                return;
            }
            openSelection(control, provider);
        });
    }

    private static boolean overARow(Control control, Event event) {
        if (!(control instanceof Tree)) {
            return true;
        }
        Tree tree = (Tree) control;
        return tree.getItem(new Point(event.x, event.y)) != null;
    }

    private static void openSelection(Control control, ISelectionProvider provider) {
        ISelection selection = provider.getSelection();
        if (!(selection instanceof IStructuredSelection) || selection.isEmpty()) {
            return;
        }
        Object first = ((IStructuredSelection) selection).getFirstElement();
        if (first instanceof IExpression || first instanceof IJavaVariable) {
            TableOpener.open(control.getShell(), first);
        }
    }

    /** The part callbacks that mean "the Expressions view is there now". */
    private static final class PartHook implements IPartListener2 {

        @Override
        public void partOpened(IWorkbenchPartReference reference) {
            hook(reference);
        }

        @Override
        public void partVisible(IWorkbenchPartReference reference) {
            hook(reference);
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference reference) {
            hook(reference);
        }

        private void hook(IWorkbenchPartReference reference) {
            if (reference != null) {
                hookPart(reference.getPart(false));
            }
        }

        @Override
        public void partClosed(IWorkbenchPartReference reference) {
            // The control is gone with the part; nothing to unhook.
        }

        @Override
        public void partActivated(IWorkbenchPartReference reference) {
            // Covered by partBroughtToTop and partVisible.
        }

        @Override
        public void partDeactivated(IWorkbenchPartReference reference) {
            // Nothing to do.
        }

        @Override
        public void partHidden(IWorkbenchPartReference reference) {
            // Nothing to do.
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference reference) {
            // Nothing to do.
        }
    }
}
