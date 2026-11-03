package com.value.table.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;

import com.value.table.Activator;
import com.value.table.util.Util;

/**
 * Global configuration / registry of user defined table rules.
 */
public class Config {

    public static final String COL_SPLIT = "&";
    public static final String ROW_SPLIT = ";";
    public static final String USER_TABLE_TYPE = "userTableType";

    /*
     * Safety knobs for calling methods on the debug target.
     */
    public static final String INVOKE_ENABLED = "invokeEnabled";
    public static final String MONITOR_GUARD = "monitorGuard";
    public static final String BLACKLIST = "invokeBlacklist";
    public static final String FETCH_TIMEOUT = "fetchTimeoutSeconds";
    public static final String MAX_ROWS = "maxRows";
    public static final String MAX_COLS = "maxCols";
    /** Also show no-argument methods as columns of an entity table. */
    public static final String ENTITY_METHODS = "entityMethods";

    /** Language of the user interface: system, en or zh. */
    public static final String LANGUAGE = "language";
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_CHINESE = "zh";

    public static final String DEFAULT_BLACKLIST = "delete,remove,clear,put,add,set,append,"
            + "commit,rollback,flush,save,write,connect,disconnect,stop,start";

    /** A {@code Map.Entry} row: the key and the value, not the entry's toString. */
    public static final String ENTRY_RULE = "java.util.Map$Entry&[key(),value()]&null&[key,value]";

    /**
     * Default rule set:
     * <pre>
     * class &amp; arrayGetter &amp; rowNameGetter &amp; colNameGetter
     * </pre>
     * <p>
     * The second line is what makes a {@code Map} read as two columns. The first
     * line turns the map into its entries; the entries themselves are objects,
     * and without a rule of their own each one would be a single cell holding
     * {@code "a=1"}. {@code [key(),value()]} says a row is made of those two
     * members, in that order.
     * </p>
     */
    public static final String DEFAULT_USER_TABLE_TYPE =
            "java.util.Map&entrySet()java.util.Set&null&[key,value]"
            + ROW_SPLIT
            + ENTRY_RULE
            + ROW_SPLIT
            + "joinery.DataFrame&iterrows()java.util.ListIterator&index()java.util.Set&columns()java.util.Set";

    public static int fontSize = 14;
    public static int minWindowsW = 600;
    public static int minWindowsH = 400;

    public static final Map<String, UserClassType> tableTypeMap = new HashMap<String, UserClassType>();

    /**
     * Screen size, asked for when a window opens rather than while this class
     * loads. A class load can happen on any thread - a background job touching
     * the model used to create an SWT Display - and the answer is only needed
     * on the UI thread anyway.
     */
    public static int screenWidth() {
        return org.eclipse.swt.widgets.Display.getDefault().getClientArea().width;
    }

    public static int screenHeight() {
        return org.eclipse.swt.widgets.Display.getDefault().getClientArea().height;
    }

    public static void init() {
        tableTypeMap.clear();

        Activator activator = Activator.getDefault();
        if (activator == null) {
            return;
        }
        String str = activator.getPreferenceStore().getString(USER_TABLE_TYPE);
        if (str == null || str.isEmpty()) {
            str = DEFAULT_USER_TABLE_TYPE;
        }

        for (String line : str.split(ROW_SPLIT)) {
            putRule(line, false);
        }
        /*
         * A rule list saved by an older version has no Map.Entry rule, and the
         * preference page writes the whole list back as soon as it is opened -
         * so without this the entries would quietly go back to one cell of
         * "a=1" after an upgrade. The built-in rule is only added when the
         * signature is missing, so a rule the user wrote for the same type still
         * wins.
         */
        putRule(ENTRY_RULE, true);
    }

    /**
     * Adds one rule line to {@link #tableTypeMap}.
     *
     * @param onlyIfAbsent keep the rule already registered for that signature
     */
    private static void putRule(String line, boolean onlyIfAbsent) {
        String[] cols = line.split(COL_SPLIT);
        /*
         * Rule lines written before 1.0.0 started with a style column
         * ("Table&..." / "Tree&..."). Tree rendering is gone and the style
         * column no longer exists, so drop the leading token instead of
         * ignoring the whole line - otherwise every rule a user had saved
         * would silently disappear after the upgrade.
         */
        if (cols.length == 5) {
            String[] shifted = new String[4];
            System.arraycopy(cols, 1, shifted, 0, 4);
            cols = shifted;
        }
        if (cols.length != 4) {
            return;
        }
        for (int i = 0; i < cols.length; i++) {
            cols[i] = Util.formatStr(cols[i]);
        }
        if (cols[0] == null || cols[1] == null) {
            return;
        }
        UserClassType type = Util.createUserClassType(cols[0], cols[1], cols[2], cols[3]);
        if (type == null || type.signature == null) {
            return;
        }
        if (onlyIfAbsent && tableTypeMap.containsKey(type.signature)) {
            return;
        }
        tableTypeMap.put(type.signature, type);
    }

    /*
     * The safety preferences are read once per fetch, so they are plain getters
     * rather than cached statics: changing them takes effect on the next table
     * without restarting Eclipse.
     */

    public static boolean isInvokeEnabled() {
        return store() == null || store().getBoolean(INVOKE_ENABLED);
    }

    public static boolean isMonitorGuard() {
        return store() == null || store().getBoolean(MONITOR_GUARD);
    }

    /**
     * Whether no-argument methods become columns of an entity table.
     * <p>
     * Off by default: an object's state is its fields. A getter is usually
     * derived from them, so adding one column per getter doubles the width of
     * the table and costs a call into the target per cell. Users who want
     * computed values (an interface with no fields at all, say) turn it on
     * under Preferences &gt; Java &gt; Debug &gt; ValueTable.
     * </p>
     */
    public static boolean isEntityMethods() {
        return store() != null && store().getBoolean(ENTITY_METHODS);
    }

    public static int getTimeoutSeconds() {
        int v = store() == null ? 10 : store().getInt(FETCH_TIMEOUT);
        return v <= 0 ? 10 : v;
    }

    public static int getMaxRows() {
        int v = store() == null ? 2000 : store().getInt(MAX_ROWS);
        return v <= 0 ? 2000 : v;
    }

    public static int getMaxCols() {
        int v = store() == null ? 200 : store().getInt(MAX_COLS);
        return v <= 0 ? 200 : v;
    }

    public static Set<String> getBlacklist() {
        Set<String> set = new HashSet<String>();
        String raw = store() == null ? DEFAULT_BLACKLIST : store().getString(BLACKLIST);
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }

    private static IPreferenceStore store() {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getPreferenceStore();
    }
}
