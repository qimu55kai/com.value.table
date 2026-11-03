package com.value.table.util;

import org.eclipse.core.runtime.jobs.ISchedulingRule;

/**
 * Keeps at most one fetch running per debug target.
 * <p>
 * The debug model is not safe for concurrent evaluation: two table windows (or
 * a table plus the Expressions view) issuing JDI commands at the same time can
 * interleave replies and return values that belong to the other request. Jobs
 * sharing this rule are queued instead of running in parallel.
 * </p>
 */
public class TargetRule implements ISchedulingRule {

    private final Object target;

    public TargetRule(Object target) {
        this.target = target;
    }

    @Override
    public boolean contains(ISchedulingRule rule) {
        return this == rule;
    }

    @Override
    public boolean isConflicting(ISchedulingRule rule) {
        return rule instanceof TargetRule && ((TargetRule) rule).target == target;
    }
}
