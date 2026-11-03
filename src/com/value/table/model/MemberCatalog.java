package com.value.table.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.debug.core.IJavaReferenceType;
import org.eclipse.jdt.debug.core.IJavaValue;

/**
 * The types a column picker can offer for one table, each with the members it
 * declares itself.
 * <p>
 * This is what the "Columns…" dialog shows as tabs, and building it means
 * walking the element type's hierarchy and asking the target what each level
 * declares - two things that must not happen on the UI thread. Keeping the
 * building here, rather than inside the dialog or the window, is what lets a
 * background job produce a finished list and hand it over.
 * </p>
 * <p>
 * Only the element type and its supertypes/interfaces appear. A
 * {@code List<Animal>} holding Dogs does not get a Dog tab: no column of that
 * table is read from Dog, and a rule naming a Dog member would leave every Cat
 * row empty.
 * </p>
 */
public final class MemberCatalog {

    /** One type offered as a tab, with everything it declares itself. */
    public static final class Type {

        /** Fully qualified name, as the target spells it. */
        public final String name;
        /** JVM signature, the key a rule is stored under. */
        public final String signature;
        /** Instance fields declared here, in declaration order. */
        public final List<MemberRef> fields;
        /** No-argument methods declared here, sorted by name. */
        public final List<MemberRef> methods;

        public Type(String name, String signature, List<MemberRef> fields, List<MemberRef> methods) {
            this.name = name;
            this.signature = signature;
            this.fields = fields;
            this.methods = methods;
        }

        /** The name without its package, for a tab that has to stay readable. */
        public String simpleName() {
            return EntityRule.simpleName(name);
        }
    }

    private MemberCatalog() {
    }

    /**
     * The types {@code baseSignature}'s table is read from, in column order:
     * supertypes first, the element's own type last, then its interfaces.
     *
     * @param sample          one element, used only to reach the type objects
     * @param baseSignature   the element type's signature, from the resolved
     *                        layout; null falls back to the sample's own type
     * @param model           the model being built, for its limits and warnings
     */
    public static List<Type> of(IJavaValue sample, String baseSignature, ValueModel model) {
        List<Type> types = new ArrayList<Type>();
        for (IJavaReferenceType type : EntityResolver.candidates(sample, baseSignature)) {
            String signature;
            String name;
            try {
                signature = type.getSignature();
                name = type.getName();
            } catch (Exception e) {
                // A type the target cannot describe is not worth a tab.
                continue;
            }
            List<MemberRef> fields = new ArrayList<MemberRef>();
            List<MemberRef> methods = new ArrayList<MemberRef>();
            for (MemberRef member : EntityResolver.describe(type, sample, model)) {
                if (member.isMethod()) {
                    methods.add(member);
                } else {
                    fields.add(member);
                }
            }
            types.add(new Type(name, signature, fields, methods));
        }
        return types;
    }
}
