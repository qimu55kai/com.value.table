package com.value.table.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaThread;

import com.value.table.Messages;

/**
 * State of one "read this variable" run: cancellation, size limits, the
 * method-call guard and the warnings collected along the way.
 * <p>
 * Everything a fetch needs to know that is not part of the debug model itself.
 * One instance is created per job and never shared between runs.
 * </p>
 */
public class FetchContext {

    /*
     * Methods that block, take a monitor or terminate the session. Calling one
     * of them to read a value has no upside: it either hangs the evaluation
     * thread forever or kills the debug session. This list is not a heuristic,
     * it is a hard rule and cannot be switched off.
     */
    private static final Set<String> BLOCKING = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "wait", "notify", "notifyAll",
            "join", "sleep", "park", "unpark", "interrupt",
            "lock", "unlock", "lockInterruptibly", "tryLock",
            "await", "awaitNanos", "awaitUninterruptibly", "signal", "signalAll",
            "acquire", "acquireUninterruptibly", "release",
            "close", "shutdown", "shutdownNow", "exit", "halt", "destroy")));

    private final IProgressMonitor monitor;
    private final int maxRows;
    private final int maxCols;
    private final boolean invokeEnabled;
    private final boolean monitorGuard;
    private final Set<String> blacklist;

    private final List<String> warnings = new ArrayList<String>();
    private final Set<String> warningKeys = new LinkedHashSet<String>();

    private IJavaThread thread;
    private int invocations;
    private int skippedInvocations;

    public FetchContext(IProgressMonitor monitor, int maxRows, int maxCols,
            boolean invokeEnabled, boolean monitorGuard, Set<String> blacklist) {
        this.monitor = monitor;
        this.maxRows = maxRows > 0 ? maxRows : Integer.MAX_VALUE;
        this.maxCols = maxCols > 0 ? maxCols : Integer.MAX_VALUE;
        this.invokeEnabled = invokeEnabled;
        this.monitorGuard = monitorGuard;
        this.blacklist = blacklist == null ? Collections.<String>emptySet() : blacklist;
    }

    /**
     * Throws {@link OperationCanceledException} when the user cancelled the job.
     * Called from every loop that talks to the target VM, so cancelling stops
     * the fetch between two round trips instead of after the whole table.
     */
    public void checkCanceled() {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    public void warn(String key, String message) {
        if (message == null) {
            return;
        }
        if (key == null || warningKeys.add(key)) {
            warnings.add(message);
        }
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setThread(IJavaThread thread) {
        this.thread = thread;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public int getMaxCols() {
        return maxCols;
    }

    public int getInvocations() {
        return invocations;
    }

    public int getSkippedInvocations() {
        return skippedInvocations;
    }

    /**
     * Decides whether {@code method} may be invoked on {@code target}.
     * <p>
     * The deadlock this guards against: JDI evaluates with
     * {@code INVOKE_SINGLE_THREADED}, which resumes <em>only</em> the evaluating
     * thread. If the method needs the object monitor and a <em>different,
     * suspended</em> thread still holds it, that holder never runs again, so the
     * call can never return - and a JDI invoke has no timeout and cannot be
     * aborted. The monitor lookup below is the only predictive signal the debug
     * API exposes, so it is checked before every call.
     * </p>
     */
    public boolean canInvoke(IJavaObject target, String method) {
        if (method == null) {
            return false;
        }
        if (!invokeEnabled) {
            skippedInvocations++;
            warn("invoke.off", Messages.get("warn.invoke.off"));
            return false;
        }
        if (BLOCKING.contains(method)) {
            skippedInvocations++;
            warn("invoke.blocking." + method, Messages.bind("warn.invoke.blocking", method));
            return false;
        }
        if (blacklist.contains(method)) {
            skippedInvocations++;
            warn("invoke.blacklisted." + method, Messages.bind("warn.invoke.blacklisted", method));
            return false;
        }
        if (monitorGuard && monitorHeldByAnotherSuspendedThread(target)) {
            skippedInvocations++;
            warn("invoke.monitor", Messages.get("warn.invoke.monitor"));
            return false;
        }
        invocations++;
        return true;
    }

    private boolean monitorHeldByAnotherSuspendedThread(IJavaObject target) {
        try {
            IJavaThread owner = target.getOwningThread();
            if (owner == null || owner.equals(thread)) {
                return false;
            }
            return owner.isSuspended();
        } catch (Exception e) {
            // owningThread() is optional in JDI, and the object may be garbage
            // collected between two commands - never let the guard itself fail
            // the fetch.
            return false;
        }
    }
}
