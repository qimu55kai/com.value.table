package com.value.table.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassObject;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaInterfaceType;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaReferenceType;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;

import com.value.table.Activator;
import com.value.table.Messages;
import com.value.table.util.Util;

/**
 * Works out how to show a list of objects that no rule matches: which type do
 * they have in common, and which members does that type declare.
 * <p>
 * Everything here is decided from type information. Reading the types costs no
 * call into the target, so the decision is made before a single cell is read -
 * probing by trial and error would mean invoking the rule's methods on every
 * element just to find out whether they apply, with all the side effects and
 * the hang risk that implies.
 * </p>
 * <p>
 * The file reads top to bottom in three blocks: {@link #resolve} and
 * {@link #describe} (the pipeline and its cache), walking types, then reading
 * the members a type declares. Nothing in the last two blocks talks to a
 * widget, which is why the column picker can run them on a job.
 * </p>
 */
public final class EntityResolver {

    /**
     * Above this many rows the method columns are dropped. Every cell of such a
     * column is one call into the target, so a long list would turn a table
     * into tens of thousands of round trips.
     */
    public static final int METHOD_ROW_LIMIT = 200;

    private static final String OBJECT_SIG = "Ljava/lang/Object;";

    private static final Map<String, EntityRule> CACHE = new ConcurrentHashMap<String, EntityRule>();

    /*
     * Types whose toString() is worth calling: they are small, side effect free
     * and far more readable than "com.demo.Money (id=31)". Everything else is
     * shown by its identity, because calling toString() on 20 members of 2000
     * rows is 40000 round trips.
     */
    private static final String[] VALUE_PREFIXES = {
            "java.lang.", "java.math.", "java.time.", "java.sql." };

    private static final Set<String> VALUE_NAMES = new LinkedHashSet<String>();

    static {
        Collections.addAll(VALUE_NAMES,
                "java.util.Date", "java.util.UUID", "java.util.Locale",
                "java.util.Currency", "java.util.Optional", "java.util.BitSet");
    }

    private EntityResolver() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * @return the member layout for {@code elements}, or {@code null} when the
     *         objects have nothing worth showing in common.
     */
    public static EntityRule resolve(IJavaValue[] elements, ValueModel model) {
        if (elements == null || elements.length == 0) {
            return null;
        }
        try {
            List<IJavaClassType> types = new ArrayList<IJavaClassType>();
            for (IJavaValue v : elements) {
                /*
                 * A null element is a slot, not a type: skipping it is what
                 * makes a list with one empty entry still a table of the type
                 * its other rows share. Bailing out here used to drop the whole
                 * layout and leave a single column of "null"s.
                 */
                if (isNull(v)) {
                    continue;
                }
                IJavaType t = v.getJavaType();
                if (!(t instanceof IJavaClassType)) {
                    /*
                     * One row of an unknown kind does not decide the table
                     * either: the type it shares with the others is still the
                     * useful answer, and the row simply has no value for the
                     * columns that come from elsewhere.
                     */
                    continue;
                }
                IJavaClassType ct = (IJavaClassType) t;
                if (!hasSignature(types, ct.getSignature())) {
                    types.add(ct);
                }
            }
            if (types.isEmpty()) {
                // Every row was null, or none of them carried a class type.
                return null;
            }

            IJavaReferenceType base = commonType(types);
            if (base == null) {
                return null;
            }

            boolean wantMethods = Config.isEntityMethods();
            boolean withMethods = wantMethods && elements.length <= METHOD_ROW_LIMIT;
            if (wantMethods && !withMethods && model != null) {
                model.getContext().warn("entity.methods.rows",
                        Messages.bind("warn.methods.rows", elements.length));
            }

            /*
             * The columns are the state of the object, not the state of one
             * class in its hierarchy: an Employee's name and id are as much part
             * of a row as its salary. So a type without a rule of its own is
             * read from every level of the hierarchy.
             *
             * A rule, however, belongs to exactly one list: the one whose
             * elements have that type. "Person#id,name" describes a table of
             * Person objects and nothing else - an Employee table is not a
             * Person table with extra columns, so editing the Person line must
             * not silently add or drop Employee columns. When the element's own
             * type has a rule, that rule is the whole row: it names the
             * inherited members it wants, and the levels above contribute
             * nothing - otherwise unticking "id" under Person in the dialog
             * would not remove it, because the Person level would put it back.
             */
            List<IJavaReferenceType> levels = hierarchy(base);
            IJavaValue sample = firstUsable(elements);
            List<MemberRef> own = EntityRules.current().get(base.getSignature());
            if (own != null && !own.isEmpty()) {
                /*
                 * The class may have changed since the rule was written - a
                 * field renamed, a method deleted. A column that names a member
                 * the type no longer has is empty in every row and useless in
                 * the picker, so the rule is shortened here and written back.
                 * Reading the type is two round trips; reading forty empty cells
                 * per row is not cheaper, and the fix is permanent.
                 */
                List<MemberRef> kept = prune(own, sample, levels, model);
                if (kept != own) {
                    EntityRules.prune(base.getSignature(), kept);
                    own = kept;
                }
            }
            boolean configured = own != null && !own.isEmpty();

            String key = base.getSignature() + (withMethods ? "|M" : "|F");
            if (!configured) {
                // With a rule in play the layout depends on preferences that can
                // change while the session is open, so it is not cached.
                EntityRule cached = CACHE.get(key);
                if (cached != null) {
                    return cached;
                }
            }

            List<MemberRef> members = new ArrayList<MemberRef>();
            Set<String> taken = new LinkedHashSet<String>();
            for (IJavaReferenceType level : levels) {
                List<MemberRef> contribution = new ArrayList<MemberRef>();
                if (sameSignature(level, base)) {
                    if (configured) {
                        stripStatic(own, sample, contribution);
                    } else {
                        addDeclared(level, base, sample, withMethods, model, contribution);
                    }
                } else if (!configured) {
                    // A supertype only contributes while the element's own type
                    // has no rule. Its own rule describes its own list only.
                    addDeclared(level, base, sample, withMethods, model, contribution);
                }
                /*
                 * Deduplicated by label: a rule written by the dialog is the
                 * whole row, and a field that is not on every row of a
                 * heterogeneous list still deserves one column, not one per
                 * class that happens to declare it.
                 */
                for (MemberRef member : contribution) {
                    if (taken.add(member.label)) {
                        members.add(member);
                    }
                }
            }
            if (members.isEmpty()) {
                return null;
            }

            EntityRule rule = new EntityRule(base.getSignature(), EntityRule.simpleName(base.getName()),
                    limit(members, model));
            if (!configured) {
                CACHE.put(key, rule);
            }
            return rule;
        } catch (Exception e) {
            Activator.log("cannot resolve the members of the selected objects", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Walking types
    //
    // Everything below answers "what type are these objects, and where does
    // that type sit". None of it reads a value, so none of it can hang.
    // ------------------------------------------------------------------

    /**
     * The types the rules dialog offers as tabs: what the table of elements of
     * {@code baseSignature} would be read from, and nothing else.
     * <p>
     * The element type decides this, not the objects in the list. A
     * {@code List<Animal>} holds Dogs and Cats, but it is an Animal list: its
     * columns come from Animal and from Animal's supertypes, so Dog is not one
     * of the types on offer. Offering it would let a rule name {@code legs} -
     * a member half the rows do not have - and would make the picker show a
     * class this table never reads.
     * </p>
     * <p>
     * The sample is only used to reach that type object: the runtime class is
     * walked up until the element type is found, and that is the one whose
     * hierarchy is offered. The order is the order of the columns, so a tab's
     * members appear in the table where the tab says they do.
     * </p>
     */
    public static List<IJavaReferenceType> candidates(IJavaValue sample, String baseSignature) {
        List<IJavaReferenceType> types = new ArrayList<IJavaReferenceType>();
        IJavaReferenceType base = baseType(sample, baseSignature);
        if (base == null) {
            return types;
        }
        return hierarchy(base);
    }

    /**
     * The declared type of the elements, found by walking up from the object at
     * hand - the first element of a list is usually a subclass of it.
     *
     * @return the sample's own type when the signature cannot be placed in its
     *         chain, so a stale rule still leaves the dialog usable
     */
    private static IJavaReferenceType baseType(IJavaValue sample, String baseSignature) {
        if (!(sample instanceof IJavaObject)) {
            return null;
        }
        try {
            IJavaType type = sample.getJavaType();
            if (!(type instanceof IJavaReferenceType)) {
                return null;
            }
            IJavaReferenceType own = (IJavaReferenceType) type;
            if (baseSignature == null || baseSignature.isEmpty()) {
                return own;
            }
            IJavaReferenceType found = inChain(own, baseSignature);
            return found != null ? found : own;
        } catch (Exception e) {
            Activator.log("cannot find the element type of the selected objects", e);
            return null;
        }
    }

    /** The type carrying {@code signature}, among the sample's classes or interfaces. */
    private static IJavaReferenceType inChain(IJavaReferenceType type, String signature) throws Exception {
        if (signature.equals(type.getSignature())) {
            return type;
        }
        if (!(type instanceof IJavaClassType)) {
            return null;
        }
        IJavaClassType cls = (IJavaClassType) type;
        while (cls != null) {
            if (signature.equals(cls.getSignature())) {
                return cls;
            }
            cls = cls.getSuperclass();
        }
        for (IJavaInterfaceType i : ((IJavaClassType) type).getAllInterfaces()) {
            if (signature.equals(i.getSignature())) {
                return i;
            }
        }
        return null;
    }

    /** {@code getName()} throws when the target is gone; a log line is not worth that. */
    private static String safeName(IJavaReferenceType type) {
        try {
            return type.getName();
        } catch (Exception e) {
            return "?";
        }
    }

    private static boolean containsSignature(List<IJavaReferenceType> types, String signature) throws Exception {
        for (IJavaReferenceType type : types) {
            if (type.getSignature().equals(signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every member of one type, for the rules dialog: what it declares itself,
     * fields first and then methods.
     * <p>
     * The list ignores the "show methods" preference on purpose - the dialog is
     * where that decision is made per type, so it has to be able to offer the
     * choices. Reading the methods costs a call into the target per type, which
     * is why the caller runs this on a job.
     * </p>
     */
    public static List<MemberRef> describe(IJavaReferenceType type, IJavaValue sample, ValueModel model) {
        List<MemberRef> members = new ArrayList<MemberRef>();
        if (type == null) {
            return members;
        }
        try {
            if (!(type instanceof IJavaInterfaceType)) {
                addFields(type, sample, members);
            }
            addMethods(type, model, members);
        } catch (Exception e) {
            Activator.log("cannot describe " + safeName(type), e);
        }
        return members;
    }

    /**
     * The types a row's cells come from, in column order: the topmost superclass
     * first, the element's own type last, then interfaces.
     * <p>
     * Superclass first is the order a class is read in - the state it inherits,
     * then the state it adds - and it keeps the method columns of {@code Object}
     * out of the table entirely.
     * </p>
     */
    private static List<IJavaReferenceType> hierarchy(IJavaReferenceType base) {
        List<IJavaReferenceType> chain = new ArrayList<IJavaReferenceType>();
        List<IJavaReferenceType> interfaces = new ArrayList<IJavaReferenceType>();
        try {
            if (!(base instanceof IJavaClassType)) {
                chain.add(base);
                return chain;
            }
            IJavaClassType cls = (IJavaClassType) base;
            while (cls != null) {
                if (!OBJECT_SIG.equals(cls.getSignature())) {
                    chain.add(cls);
                }
                cls = cls.getSuperclass();
            }
            for (IJavaInterfaceType i : ((IJavaClassType) base).getAllInterfaces()) {
                if (!containsSignature(chain, i.getSignature()) && !containsSignature(interfaces, i.getSignature())) {
                    interfaces.add(i);
                }
            }
        } catch (Exception e) {
            Activator.log("cannot walk the type hierarchy of " + safeName(base), e);
            chain.clear();
            chain.add(base);
            interfaces.clear();
        }
        Collections.reverse(chain);
        chain.addAll(interfaces);
        return chain;
    }

    // ------------------------------------------------------------------
    // Reading members
    //
    // One level of a hierarchy at a time: the fields it declares, the methods
    // it declares, and the checks that keep a static field or a member the
    // class no longer has out of the columns.
    // ------------------------------------------------------------------

    /**
     * What one level of the hierarchy contributes while the element's type has
     * no rule: the fields it declares, and its no-argument methods when those
     * are wanted.
     * <p>
     * An interface has no state, so only its getters can be a column, and only
     * when the table is a list typed as that interface - matching what a
     * debugger shows for a {@code List} element.
     * </p>
     */
    private static void addDeclared(IJavaReferenceType level, IJavaReferenceType base, IJavaValue sample,
            boolean withMethods, ValueModel model, List<MemberRef> out) {
        if (level instanceof IJavaInterfaceType) {
            if (withMethods) {
                addMethods(level, model, out);
            }
            return;
        }
        /*
         * A class that extends a platform type - a Swing panel, an exception, a
         * map implementation - inherits dozens of fields nobody wants in a row.
         * The element's own type is still shown, because that is the class the
         * user is looking at; only the platform levels above it are dropped.
         */
        if (isPlatformType(level) && !sameSignature(level, base)) {
            return;
        }
        try {
            addFields(level, sample, out);
        } catch (Exception e) {
            // A level whose fields cannot be listed still leaves the rest of the
            // row in place; losing the whole table over one type is worse.
            Activator.log("cannot list the fields of " + safeName(level), e);
        }
        if (withMethods) {
            addMethods(level, model, out);
        }
    }

    private static boolean sameSignature(IJavaReferenceType a, IJavaReferenceType b) {
        try {
            return a.getSignature().equals(b.getSignature());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPlatformType(IJavaReferenceType level) {
        try {
            String name = level.getName();
            if (name == null) {
                return false;
            }
            return name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("com.sun.");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True for values that should stay a single cell instead of becoming a
     * table: {@code String}, numbers, dates, enums and arrays have no state a
     * debugger user wants to see, and expanding them would be noise.
     */
    public static boolean isValueType(IJavaValue v) {
        if (v == null || v instanceof IJavaArray) {
            return true;
        }
        if (!(v instanceof IJavaObject)) {
            return true;
        }
        try {
            IJavaType t = v.getJavaType();
            if (!(t instanceof IJavaReferenceType)) {
                return true;
            }
            String name = t.getName();
            if (name == null || VALUE_NAMES.contains(name)) {
                return true;
            }
            for (String prefix : VALUE_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return t instanceof IJavaClassType && ((IJavaClassType) t).isEnum();
        } catch (Exception e) {
            // Unknown type: treat it as a plain value rather than guessing.
            return true;
        }
    }

    public static boolean canBeEntity(IJavaValue v) {
        return v instanceof IJavaObject && !isNull(v) && !isValueType(v);
    }

    // ------------------------------------------------------------------
    // common type
    // ------------------------------------------------------------------

    /**
     * The most specific type every element shares.
     * <p>
     * Walking superclasses alone is not enough: a list of {@code Circle} and
     * {@code Square} shares only the interface {@code Shape}, and their only
     * common class is {@code Object}, which has no member worth a column.
     * </p>
     */
    private static IJavaReferenceType commonType(List<IJavaClassType> types) throws Exception {
        if (types.size() == 1) {
            return types.get(0);
        }
        List<Set<String>> ancestors = new ArrayList<Set<String>>();
        for (IJavaClassType t : types) {
            ancestors.add(ancestorsOf(t));
        }
        IJavaClassType c = types.get(0);
        while (c != null) {
            String sig = c.getSignature();
            if (!OBJECT_SIG.equals(sig) && shared(sig, ancestors)) {
                return c;
            }
            c = c.getSuperclass();
        }
        for (IJavaInterfaceType i : types.get(0).getAllInterfaces()) {
            if (shared(i.getSignature(), ancestors)) {
                return i;
            }
        }
        return null;
    }

    private static Set<String> ancestorsOf(IJavaClassType type) throws Exception {
        Set<String> set = new LinkedHashSet<String>();
        IJavaClassType c = type;
        while (c != null) {
            set.add(c.getSignature());
            c = c.getSuperclass();
        }
        for (IJavaInterfaceType i : type.getAllInterfaces()) {
            set.add(i.getSignature());
        }
        return set;
    }

    private static boolean shared(String signature, List<Set<String>> ancestors) {
        for (Set<String> set : ancestors) {
            if (!set.contains(signature)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSignature(List<IJavaClassType> types, String signature) throws Exception {
        for (IJavaClassType t : types) {
            if (t.getSignature().equals(signature)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // members
    // ------------------------------------------------------------------

    /**
     * Fields the resolved type declares itself.
     * <p>
     * The list is taken from one instance rather than from
     * {@code getDeclaredFieldNames()}, for two reasons: that call also returns
     * static fields ({@code serialVersionUID} would become a column), and
     * reading an object's variables lets the declaring type be checked, which
     * is exactly the "own fields, not inherited" rule the table follows.
     * </p>
     */
    private static void addFields(IJavaReferenceType base, IJavaValue sample, List<MemberRef> out)
            throws Exception {
        String baseSignature = base.getSignature();
        /*
         * Collected aside and appended at the end: "did this type contribute
         * anything" decides whether the name based fallback is worth a call, and
         * the caller's list already holds the other levels of the hierarchy.
         */
        List<MemberRef> found = new ArrayList<MemberRef>();
        try {
            if (sample instanceof IJavaObject) {
                boolean sawField = false;
                for (IVariable var : ((IJavaObject) sample).getVariables()) {
                    if (!(var instanceof IJavaFieldVariable)) {
                        continue;
                    }
                    sawField = true;
                    IJavaFieldVariable field = (IJavaFieldVariable) var;
                    /*
                     * Static fields belong to the class, not to the object: a
                     * debugger looking at one row of users wants its state, not
                     * serialVersionUID, a logger, or a shared counter. They are
                     * declared by the class itself, so the declaring-type check
                     * below would happily let them through.
                     */
                    if (isStatic(field)) {
                        continue;
                    }
                    IJavaType owner = field.getDeclaringType();
                    if (owner != null && !baseSignature.equals(owner.getSignature())) {
                        continue;
                    }
                    addFieldName(field.getName(), baseSignature, found);
                }
                /*
                 * The object answered with its fields, so that answer is the
                 * complete one. Falling through to the name based list here used
                 * to put the statics straight back - a class whose only declared
                 * field is static has nothing left to show, and that is a
                 * correct row of no columns, not a reason to ask again another
                 * way.
                 */
                if (sawField) {
                    out.addAll(found);
                    return;
                }
            }
        } catch (Exception e) {
            // Fall through to the name based lookup below.
        }
        try {
            String[] names = base.getDeclaredFieldNames();
            if (names != null) {
                for (String name : names) {
                    /*
                     * No variable to ask, so the field is looked up by name. A
                     * name that cannot be resolved is not this object's instance
                     * field either - the variable list above would have had it -
                     * so it is skipped rather than guessed at.
                     */
                    if (sample instanceof IJavaObject && notAnInstanceField((IJavaObject) sample, name)) {
                        continue;
                    }
                    addFieldName(name, baseSignature, found);
                }
            }
        } catch (Exception e) {
            Activator.log("cannot list the fields of " + base.getName(), e);
        }
        out.addAll(found);
    }

    /**
     * Drops the members of a saved rule that the types no longer declare.
     * <p>
     * A rule outlives the class it was written for: rename {@code name} to
     * {@code fullName} and the column silently becomes empty in every row. The
     * names are checked against the declared members of the whole hierarchy -
     * type information, not one instance, so a heterogeneous list does not lose
     * a column because its first row happens to be a subclass without it.
     * </p>
     * <p>
     * When the members cannot be listed the rule is returned untouched: a type
     * that could not be read is not evidence that a member is gone.
     * </p>
     *
     * @return the same list when nothing is missing, so the caller can tell
     *         "unchanged" apart from "shortened"
     */
    private static List<MemberRef> prune(List<MemberRef> rule, IJavaValue sample,
            List<IJavaReferenceType> levels, ValueModel model) {
        boolean methodsNamed = false;
        for (MemberRef member : rule) {
            if (member.isMethod()) {
                methodsNamed = true;
                break;
            }
        }
        Set<String> fields = new LinkedHashSet<String>();
        if (!collectFields(levels, fields)) {
            return rule;
        }
        Set<String> methods = new LinkedHashSet<String>();
        boolean methodsKnown = !methodsNamed || collectMethods(levels, model, methods);

        List<MemberRef> kept = new ArrayList<MemberRef>();
        boolean changed = false;
        for (MemberRef member : rule) {
            boolean exists;
            if (member.isMethod()) {
                exists = !methodsKnown || methods.contains(member.name);
            } else {
                exists = fields.contains(member.name) && !isStatic(sample, member.name);
            }
            if (exists) {
                kept.add(member);
            } else {
                changed = true;
            }
        }
        return changed ? kept : rule;
    }

    /** @return false when a level's fields could not be listed */
    private static boolean collectFields(List<IJavaReferenceType> levels, Set<String> out) {
        for (IJavaReferenceType level : levels) {
            if (level instanceof IJavaInterfaceType) {
                continue;
            }
            try {
                String[] names = level.getDeclaredFieldNames();
                if (names != null) {
                    Collections.addAll(out, names);
                }
            } catch (Exception e) {
                Activator.log("cannot list the fields of " + safeName(level), e);
                return false;
            }
        }
        return true;
    }

    /**
     * The declared method names of every level, read through the class objects.
     * Only the name is asked for - one call per method instead of the six a full
     * description costs - because the question here is just "does it exist".
     *
     * @return false when the methods could not be listed
     */
    private static boolean collectMethods(List<IJavaReferenceType> levels, ValueModel model, Set<String> out) {
        if (model == null) {
            return false;
        }
        for (IJavaReferenceType level : levels) {
            try {
                IJavaClassObject cls = level.getClassObject();
                IJavaValue raw = model.invokeMethod(cls, "getDeclaredMethods",
                        "()[Ljava/lang/reflect/Method;");
                if (!(raw instanceof IJavaArray)) {
                    return false;
                }
                for (IJavaValue m : ((IJavaArray) raw).getValues()) {
                    if (!(m instanceof IJavaObject)) {
                        continue;
                    }
                    String name = model.invokeMethod((IJavaObject) m, "getName",
                            "()Ljava/lang/String;").getValueString();
                    if (name != null) {
                        out.add(name);
                    }
                }
            } catch (Exception e) {
                Activator.log("cannot list the methods of " + safeName(level), e);
                return false;
            }
        }
        return true;
    }

    /** True when the sample carries {@code name} as a static field. */
    private static boolean isStatic(IJavaValue sample, String name) {
        if (!(sample instanceof IJavaObject)) {
            return false;
        }
        IJavaFieldVariable field = findField((IJavaObject) sample, name);
        return field != null && isStatic(field);
    }

    /**
     * Drops the members a saved rule names that turn out to be static.
     * <p>
     * The automatic list never offers them, so a static field can only be in a
     * rule written by an older build or by hand. Checking costs a variable
     * lookup per field, which the model answers from the fields it already
     * holds, and a constant is not the state of a row.
     * </p>
     */
    private static void stripStatic(List<MemberRef> rule, IJavaValue sample, List<MemberRef> out) {
        for (MemberRef member : rule) {
            if (member.isMethod() || !(sample instanceof IJavaObject)) {
                out.add(member);
                continue;
            }
            IJavaFieldVariable field = findField((IJavaObject) sample, member.name);
            if (field == null || !isStatic(field)) {
                out.add(member);
            }
        }
    }

    private static IJavaFieldVariable findField(IJavaObject object, String name) {
        try {
            IJavaFieldVariable field = object.getField(name, true);
            if (field != null) {
                return field;
            }
        } catch (Exception e) {
            // The other spelling may still find it.
        }
        try {
            return object.getField(name, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isStatic(IJavaFieldVariable field) {
        try {
            return field.isStatic();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True for a name that is certainly not an instance field of the object:
     * either it is static, or the object does not have it at all. Both are
     * reasons not to give it a column in the fallback below.
     */
    private static boolean notAnInstanceField(IJavaObject object, String name) {
        try {
            IJavaFieldVariable field = findField(object, name);
            return field == null || isStatic(field);
        } catch (Exception e) {
            return true;
        }
    }

    private static void addFieldName(String name, String declaredIn, List<MemberRef> out) {
        if (name == null || name.isEmpty() || name.startsWith("this$")) {
            return;
        }
        out.add(MemberRef.field(name, declaredIn));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * The first element that is really there: a null entry carries no type to
     * read the members from, so it is not a usable sample.
     */
    private static IJavaValue firstUsable(IJavaValue[] values) {
        for (IJavaValue v : values) {
            if (!isNull(v)) {
                return v;
            }
        }
        return null;
    }

    /** A reference that is null in the target, or no reference at all. */
    private static boolean isNull(IJavaValue v) {
        return v == null || v.isNull();
    }

    /**
     * Lists the methods of {@code base} that can be read without asking the user
     * anything: no parameters, a return value, and not static.
     * <p>
     * The debug model has no API for this - {@code IJavaClassType} exposes
     * fields but not methods - so the class object is asked through reflection
     * on the target. Each class is resolved once and then cached.
     * </p>
     */
    private static void addMethods(IJavaReferenceType base, ValueModel model, List<MemberRef> out) {
        if (model == null) {
            return;
        }
        try {
            IJavaClassObject cls = base.getClassObject();
            IJavaValue raw = model.invokeMethod(cls, "getDeclaredMethods",
                    "()[Ljava/lang/reflect/Method;");
            if (!(raw instanceof IJavaArray)) {
                return;
            }
            List<MemberRef> found = new ArrayList<MemberRef>();
            for (IJavaValue m : ((IJavaArray) raw).getValues()) {
                if (!(m instanceof IJavaObject)) {
                    continue;
                }
                IJavaObject method = (IJavaObject) m;
                int modifiers = intOf(model.invokeMethod(method, "getModifiers", "()I"));
                // 0x8 static, 0x40 bridge, 0x1000 synthetic
                if ((modifiers & 0x8) != 0 || (modifiers & 0x40) != 0 || (modifiers & 0x1000) != 0) {
                    continue;
                }
                if (intOf(model.invokeMethod(method, "getParameterCount", "()I")) != 0) {
                    continue;
                }
                IJavaValue rt = model.invokeMethod(method, "getReturnType", "()Ljava/lang/Class;");
                if (!(rt instanceof IJavaObject)) {
                    continue;
                }
                String rtName = model.invokeMethod((IJavaObject) rt, "getName",
                        "()Ljava/lang/String;").getValueString();
                if (rtName == null || "void".equals(rtName)) {
                    continue;
                }
                String name = model.invokeMethod(method, "getName",
                        "()Ljava/lang/String;").getValueString();
                if (name == null || name.startsWith("lambda$")) {
                    continue;
                }
                found.add(MemberRef.method(name, "()" + signatureOf(rtName), base.getSignature()));
            }
            Collections.sort(found);
            out.addAll(found);
        } catch (Exception e) {
            // Older targets have no getParameterCount(); fields alone still work.
            Activator.log("cannot list the methods of the resolved type", e);
            model.getContext().warn("entity.methods.failed", Messages.get("warn.methods.failed"));
        }
    }

    private static int intOf(IJavaValue v) {
        try {
            return Integer.parseInt(v.getValueString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * JVM signature of a return type, as named by {@code Class.getName()}.
     * <p>
     * An array arrives already in JVM form ({@code [I}, {@code [Ljava.lang.String;}),
     * so it only needs the dots replaced - handing it to {@code Util.getSignature}
     * would produce {@code L[I;} and the call would never be found.
     * </p>
     */
    private static String signatureOf(String className) {
        if (className.startsWith("[")) {
            return className.replace('.', '/');
        }
        return Util.getSignature(className);
    }

    private static List<MemberRef> limit(List<MemberRef> members, ValueModel model) {
        int max = model == null ? Integer.MAX_VALUE : model.getContext().getMaxCols();
        if (members.size() <= max) {
            return members;
        }
        if (model != null) {
            model.getContext().warn("entity.cols.limit",
                    Messages.bind("warn.columns.limit", max, members.size()));
        }
        return new ArrayList<MemberRef>(members.subList(0, max));
    }
}
