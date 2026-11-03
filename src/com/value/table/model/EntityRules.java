package com.value.table.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import com.value.table.Activator;
import com.value.table.util.Util;

/**
 * Member lists the user picked for one type, stored as text:
 * <pre>
 * com.demo.User#name,age,getLevel()int
 * com.demo.Person#id
 * </pre>
 * <p>
 * This is a different question from {@link Config#USER_TABLE_TYPE}: those rules
 * say how a value becomes a table at all (an array getter, a row accessor, the
 * column labels). These say which members of an <em>entity</em> are worth a
 * column - the list the user sees in the rules dialog, one tab per type.
 * </p>
 * <p>
 * The list is kept as text rather than as serialised objects on purpose: it can
 * be read, copied between workspaces, and reviewed in the preference page, and a
 * saved rule survives a plug-in upgrade that renames an internal class.
 * </p>
 */
public final class EntityRules {

    /** Preference key holding every rule, one per line. */
    public static final String KEY = "entityRules";

    private static final char TYPE_SEP = '#';

    /** Parsed rules, dropped whenever they are saved again. */
    private static Map<String, List<MemberRef>> cache;

    /** True when a rule was shortened and has not been written yet. */
    private static boolean pendingWrite;

    private EntityRules() {
    }

    /**
     * The rules as they are configured right now, keyed by JVM signature.
     * <p>
     * Read from the preference store on first use and kept until
     * {@link #save(Map)}: resolving happens once per opened table, but a rule
     * list is a file read each time, and the dialog can open and close without
     * touching the target at all.
     * </p>
     */
    public static synchronized Map<String, List<MemberRef>> current() {
        if (cache == null) {
            IPreferenceStore store = store();
            cache = parse(store == null ? null : store.getString(KEY));
        }
        return cache;
    }

    public static synchronized void save(Map<String, List<MemberRef>> rules) {
        Map<String, List<MemberRef>> copy = new LinkedHashMap<String, List<MemberRef>>();
        if (rules != null) {
            copy.putAll(rules);
        }
        IPreferenceStore store = store();
        if (store != null) {
            store.setValue(KEY, format(copy));
        }
        cache = copy;
        pendingWrite = false;
        // A rule changes which columns a type has, and that list is cached.
        EntityResolver.clearCache();
    }

    /**
     * Replaces one type's rule with the members that still exist, and remembers
     * that the list has to be written.
     * <p>
     * The writing is deferred to {@link #flush()} on purpose: this is called
     * while a table is being read, which happens on a job thread, and a
     * preference change notifies listeners that expect the UI thread.
     * </p>
     */
    public static synchronized void prune(String signature, List<MemberRef> members) {
        if (signature == null) {
            return;
        }
        Map<String, List<MemberRef>> next = new LinkedHashMap<String, List<MemberRef>>(current());
        if (members == null || members.isEmpty()) {
            next.remove(signature);
        } else {
            next.put(signature, members);
        }
        cache = next;
        pendingWrite = true;
        EntityResolver.clearCache();
    }

    /**
     * Writes a shortened rule list, if there is one. Call on the UI thread.
     *
     * @return true when something was written
     */
    public static synchronized boolean flush() {
        if (!pendingWrite) {
            return false;
        }
        pendingWrite = false;
        IPreferenceStore store = store();
        if (store == null || cache == null) {
            return false;
        }
        store.setValue(KEY, format(cache));
        return true;
    }

    // ------------------------------------------------------------------
    // text form
    // ------------------------------------------------------------------

    public static Map<String, List<MemberRef>> parse(String text) {
        Map<String, List<MemberRef>> rules = new LinkedHashMap<String, List<MemberRef>>();
        if (text == null || text.trim().isEmpty()) {
            return rules;
        }
        for (String line : text.split("[\\r\\n;]+")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int sep = entry.indexOf(TYPE_SEP);
            if (sep <= 0 || sep == entry.length() - 1) {
                continue;
            }
            String typeName = Util.formatStr(entry.substring(0, sep));
            if (typeName == null) {
                continue;
            }
            List<MemberRef> members = new ArrayList<MemberRef>();
            for (String item : entry.substring(sep + 1).split(",")) {
                MemberRef ref = Util.parseMemberRef(item);
                if (ref != null) {
                    members.add(ref);
                }
            }
            String signature = Util.getSignature(typeName);
            if (members.isEmpty() || signature == null) {
                continue;
            }
            rules.put(signature, members);
        }
        return rules;
    }

    public static String format(Map<String, List<MemberRef>> rules) {
        StringBuilder text = new StringBuilder();
        if (rules == null) {
            return "";
        }
        for (Map.Entry<String, List<MemberRef>> entry : rules.entrySet()) {
            List<MemberRef> members = entry.getValue();
            if (members == null || members.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(Util.classNameOf(entry.getKey())).append(TYPE_SEP);
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(Util.formatMemberRef(members.get(i)));
            }
        }
        return text.toString();
    }

    private static IPreferenceStore store() {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getPreferenceStore();
    }
}
