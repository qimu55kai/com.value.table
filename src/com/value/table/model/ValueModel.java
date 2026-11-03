package com.value.table.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaInterfaceType;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;

import com.value.table.Activator;
import com.value.table.Messages;

/**
 * Turns a suspended JDI value into a flat / nested value structure that the
 * views can render.
 */
public abstract class ValueModel {

    protected IJavaThread thread;
    protected IJavaValue value;
    protected final Map<String, UserClassType> userTypeMap;
    protected final FetchContext context;

    protected final Map<String, UserClassType> typeMap = new HashMap<String, UserClassType>();
    protected final Map<String, Boolean> hasIterableMap = new HashMap<String, Boolean>();
    protected final Map<String, Boolean> hasIteratorMap = new HashMap<String, Boolean>();
    protected final Map<String, String> errorMap = new HashMap<String, String>();

    public ValueModel(IJavaVariable variable, Map<String, UserClassType> userTypeMap, FetchContext context) {
        this(userTypeMap, context);
        try {
            this.value = (IJavaValue) variable.getValue();
        } catch (Exception e) {
            // A GUI launched Eclipse has no console, so stderr would be lost.
            Activator.log("cannot read the value of a variable", e);
        }
        initThread(variable);
        this.context.setThread(this.thread);
    }

    /**
     * A value that has no variable behind it - the result of a watch expression,
     * for instance, which the Expressions view hands out as an {@code IExpression}
     * rather than as a variable. The debug target is found through the value
     * itself.
     */
    protected ValueModel(IJavaValue value, Map<String, UserClassType> userTypeMap, FetchContext context) {
        this(userTypeMap, context);
        this.value = value;
        initThread(value);
        this.context.setThread(this.thread);
    }

    private ValueModel(Map<String, UserClassType> userTypeMap, FetchContext context) {
        this.userTypeMap = userTypeMap;
        this.context = context == null
                ? new FetchContext(null, 0, 0, true, true, null)
                : context;
    }

    public UserClassType getUserClassType(IJavaValue v) {
        return getUserClassType(v, userTypeMap);
    }

    public static UserClassType getUserClassType(IJavaValue v, Map<String, UserClassType> userTypeMap) {
        if (v == null || userTypeMap == null) {
            return null;
        }
        try {
            IJavaClassType type = (IJavaClassType) v.getJavaType();
            UserClassType t = userTypeMap.get(type.getSignature());
            if (t != null) {
                return t;
            }
            for (IJavaInterfaceType i : type.getAllInterfaces()) {
                t = userTypeMap.get(i.getSignature());
                if (t != null) {
                    return t;
                }
            }
            type = type.getSuperclass();
            while (type != null) {
                t = userTypeMap.get(type.getSignature());
                if (t != null) {
                    return t;
                }
                type = type.getSuperclass();
            }
        } catch (Exception e) {
            // type information is not always available, ignore
        }
        return null;
    }

    public String[] getString(IJavaValue[] values) {
        if (values == null) {
            return new String[0];
        }
        String[] s = new String[values.length];
        for (int i = 0; i < s.length; i++) {
            s[i] = getString(values[i]);
        }
        return s;
    }

    public String getString(IJavaValue v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof IJavaObject) {
            try {
                return invokeMethod((IJavaObject) v, "toString", "()Ljava/lang/String;").getValueString();
            } catch (Exception e) {
                // fall through to getValueString
            }
        }
        try {
            return v.getValueString();
        } catch (DebugException e) {
            return "error";
        }
    }

    public IJavaValue[] getArrays(IJavaValue v) throws DebugException {
        return getArrays(v, true);
    }

    /**
     * Flattens one level of {@code v}: arrays are taken element by element, a
     * configured user type is resolved through its accessor, anything else is
     * walked through {@link Iterable} / {@link java.util.Iterator}.
     */
    public IJavaValue[] getArrays(IJavaValue v, boolean next) throws DebugException {
        if (v instanceof IJavaArray) {
            return getVariableValues(v);
        }

        if (next) {
            String signature = safeSignature(v);
            if (signature != null && errorMap.get(signature) != null) {
                return new IJavaValue[] { v };
            }

            UserClassType t;
            if (signature != null && typeMap.containsKey(signature)) {
                t = typeMap.get(signature);
            } else {
                t = getUserClassType(v);
                if (signature != null) {
                    typeMap.put(signature, t);
                }
            }

            if (t != null && t.cellRefs != null && v instanceof IJavaObject) {
                return readCellRefs((IJavaObject) v, t.cellRefs);
            }

            if (t != null && t.arrayName != null) {
                if (t.arrayIsField) {
                    if (UserClassType.ALL_FIELDS.equals(t.arrayName)) {
                        return getVariableValues(v);
                    }
                    try {
                        IJavaValue field = (IJavaValue) ((IJavaObject) v).getField(t.arrayName, false).getValue();
                        return getArrays(field, false);
                    } catch (Exception e) {
                        if (signature != null) {
                            errorMap.put(signature,
                                    signature + " find field " + t.arrayName + " error!");
                        }
                    }
                }
                try {
                    IJavaValue result = invokeMethod((IJavaObject) v, t.arrayName, t.arrayReturnType);
                    return getArrays(result, false);
                } catch (Exception e) {
                    if (signature != null) {
                        errorMap.put(signature, signature + " invoke method " + t.arrayName
                                + t.arrayReturnType + " error!");
                    }
                }
            }
        }

        try {
            return getArraysByIterable(v);
        } catch (Exception e) {
            Activator.log("cannot iterate a value, falling back to a single row", e);
            return new IJavaValue[] { v };
        }
    }

    /**
     * A row built from several members, in the order the rule names them.
     * <p>
     * A member that cannot be read becomes {@code null} rather than shortening
     * the row: the columns are positional, so dropping one would shift every
     * later value into the wrong column. A missing field is normal - the rule's
     * type is often an interface, and not every implementation has every
     * member.
     * </p>
     */
    private IJavaValue[] readCellRefs(IJavaObject object, MemberRef[] refs) {
        IJavaValue[] row = new IJavaValue[refs.length];
        for (int i = 0; i < refs.length; i++) {
            MemberRef ref = refs[i];
            try {
                if (ref.isMethod()) {
                    row[i] = invokeMethod(object, ref.name, ref.returnSig);
                } else {
                    row[i] = readField(object, ref.name);
                }
            } catch (Exception e) {
                // Leave null: the cell shows empty and the rest of the row is intact.
                row[i] = null;
            }
        }
        return row;
    }

    /**
     * Reads one field of {@code object}.
     * <p>
     * Both spellings of {@code getField} are tried: in this platform version
     * the {@code true} form returns {@code null} for a field it cannot find that
     * way instead of throwing, and the {@code false} form throws. A field that
     * is simply not there - the rule names a member of an interface, and this
     * implementation does not have it - is a normal answer, not an error.
     * </p>
     */
    private static IJavaValue readField(IJavaObject object, String name) throws Exception {
        IJavaFieldVariable field = null;
        try {
            field = object.getField(name, true);
        } catch (Exception e) {
            field = null;
        }
        if (field == null) {
            field = object.getField(name, false);
        }
        return field == null ? null : (IJavaValue) field.getValue();
    }

    /**
     * True when {@code v} can be broken down into a row of cells: an array, a
     * type a rule matches, or something with an iterator.
     * <p>
     * Uses the same signature keyed caches as {@link #getArrays(IJavaValue)},
     * so asking this question before building the table costs nothing extra.
     * </p>
     */
    public boolean isExpandable(IJavaValue v) {
        if (v instanceof IJavaArray) {
            return true;
        }
        /*
         * A null reference shares the declared type of the slot, not the type of
         * an object: asking it whether it is iterable or has a rule would either
         * cost a call that cannot succeed or answer about a type this row is not
         * an instance of. It is a row with nothing in it, not an expandable one.
         */
        if (v == null || v.isNull()) {
            return false;
        }
        String signature = safeSignature(v);
        // Same signature keyed cache as getArrays: a rule lookup walks the
        // interfaces and the superclass chain, which is wasted work per row.
        UserClassType rule;
        if (signature != null && typeMap.containsKey(signature)) {
            rule = typeMap.get(signature);
        } else {
            rule = getUserClassType(v);
            if (signature != null) {
                typeMap.put(signature, rule);
            }
        }
        if (rule != null) {
            return true;
        }
        Boolean iterable = signature == null ? null : hasIterableMap.get(signature);
        if (iterable == null) {
            iterable = Boolean.valueOf(hasInterface(v, "Ljava/lang/Iterable;"));
            if (signature != null) {
                hasIterableMap.put(signature, iterable);
            }
        }
        if (iterable.booleanValue()) {
            return true;
        }
        Boolean iterator = signature == null ? null : hasIteratorMap.get(signature);
        if (iterator == null) {
            iterator = Boolean.valueOf(hasInterface(v, "Ljava/util/Iterator;"));
            if (signature != null) {
                hasIteratorMap.put(signature, iterator);
            }
        }
        return iterator.booleanValue();
    }

    private static String safeSignature(IJavaValue v) {
        try {
            return v == null ? null : v.getSignature();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The values of {@code v}'s variables, which is either an array's elements
     * or - for {@link UserClassType#ALL_FIELDS} - an object's fields.
     * <p>
     * Static fields are skipped: they belong to the class, so a row built from
     * them would repeat the same constant in every row. Array elements are not
     * fields and are all kept.
     * </p>
     */
    private IJavaValue[] getVariableValues(IJavaValue v) throws DebugException {
        IVariable[] line = v.getVariables();
        List<IJavaValue> values = new ArrayList<IJavaValue>(line.length);
        for (IVariable var : line) {
            if (var instanceof IJavaFieldVariable && isStatic((IJavaFieldVariable) var)) {
                continue;
            }
            values.add((IJavaValue) var.getValue());
        }
        return values.toArray(new IJavaValue[0]);
    }

    private static boolean isStatic(IJavaFieldVariable field) {
        try {
            return field.isStatic();
        } catch (Exception e) {
            return false;
        }
    }

    private IJavaValue[] getArraysByIterable(IJavaValue v) throws DebugException {
        String signature = safeSignature(v);
        Boolean flag = signature == null ? null : hasIterableMap.get(signature);
        if (flag == null) {
            flag = Boolean.valueOf(hasInterface(v, "Ljava/lang/Iterable;"));
            if (signature != null) {
                hasIterableMap.put(signature, flag);
            }
        }
        if (flag.booleanValue()) {
            IJavaValue iterator = invokeMethod((IJavaObject) v, "iterator", "()Ljava/util/Iterator;");
            return getArraysByIterator(iterator, false);
        }
        return getArraysByIterator(v, true);
    }

    private IJavaValue[] getArraysByIterator(IJavaValue v, boolean checkType) throws DebugException {
        Boolean flag;
        if (!checkType) {
            flag = Boolean.TRUE;
        } else {
            String signature = safeSignature(v);
            flag = signature == null ? null : hasIteratorMap.get(signature);
            if (flag == null) {
                flag = Boolean.valueOf(hasInterface(v, "Ljava/lang/Iterator;"));
                if (signature != null) {
                    hasIteratorMap.put(signature, flag);
                }
            }
        }

        if (flag.booleanValue()) {
            IJavaObject iterable = (IJavaObject) v;
            List<IJavaValue> list = new ArrayList<IJavaValue>();
            int limit = context.getMaxRows();
            while ("true".equals(invokeMethod(iterable, "hasNext", "()Z").getValueString())) {
                context.checkCanceled();
                if (list.size() >= limit) {
                    context.warn("iterator.limit", Messages.bind("warn.iterator.limit", limit));
                    break;
                }
                list.add(invokeMethod(iterable, "next", "()Ljava/lang/Object;"));
                if ((list.size() & 255) == 0) {
                    context.checkCanceled();
                }
            }
            return list.toArray(new IJavaValue[0]);
        }
        return new IJavaValue[] { v };
    }

    public static boolean hasInterface(IJavaValue v, String signature) {
        if (v == null) {
            return false;
        }
        try {
            IJavaClassType type = (IJavaClassType) v.getJavaType();
            for (IJavaInterfaceType i : type.getAllInterfaces()) {
                if (i.getSignature().equals(signature)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Single choke point for every method call made on the target VM. All
     * accessors, {@code toString()} and iterator walks go through here so the
     * guard in {@link FetchContext} cannot be bypassed.
     */
    public IJavaValue invokeMethod(IJavaObject obj, String method, String signature) throws DebugException {
        if (obj == null || !context.canInvoke(obj, method)) {
            throw new DebugException(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                    "call to " + method + "() was skipped"));
        }
        return obj.sendMessage(method, signature, new IJavaValue[0], thread, (String) null);
    }

    public FetchContext getContext() {
        return context;
    }

    public void initThread(IDebugElement element) {
        if (element == null) {
            return;
        }
        try {
            IThread[] threads = element.getDebugTarget().getThreads();
            for (int i = threads.length - 1; i >= 0; i--) {
                if (threads[i].isSuspended()
                        && ((IJavaThread) threads[i]).getThreadGroupName().equals("main")) {
                    thread = (IJavaThread) threads[i];
                    return;
                }
            }
            for (int i = threads.length - 1; i >= 0; i--) {
                if (threads[i].isSuspended()) {
                    thread = (IJavaThread) threads[i];
                    return;
                }
            }
            if (threads.length > 0) {
                thread = (IJavaThread) threads[threads.length - 1];
            }
        } catch (Exception e) {
            Activator.log("cannot find a suspended thread for the value", e);
        }
    }

    public IJavaThread getThread() {
        return thread;
    }

    public void setThread(IJavaThread thread) {
        this.thread = thread;
    }

    public IJavaValue getValue() {
        return value;
    }

    public void setValue(IJavaValue value) {
        this.value = value;
    }

    public Map<String, String> getErrorMap() {
        return errorMap;
    }

    public void setErrorMap(Map<String, String> errorMap) {
        if (errorMap != null) {
            this.errorMap.clear();
            this.errorMap.putAll(errorMap);
        }
    }
}
