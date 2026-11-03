package com.value.table.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Layout used to render a list of entity objects as a table: one row per
 * object, one column per member.
 * <p>
 * The members come from the type the objects have in common. Without a rule of
 * its own, that type contributes every level of its hierarchy - an Employee row
 * holds what Person declares as well as what Employee adds. With a rule, the
 * rule is the whole row, and it belongs to this type's list only.
 * </p>
 */
public final class EntityRule {

    /** JVM signature of the resolved type. */
    public final String signature;
    /** Simple name of the resolved type, used for display only. */
    public final String typeName;
    public final List<MemberRef> members;

    EntityRule(String signature, String typeName, List<MemberRef> members) {
        this.signature = signature;
        this.typeName = typeName;
        this.members = Collections.unmodifiableList(new ArrayList<MemberRef>(members));
    }

    /**
     * {@code com.demo.User} becomes {@code User}, {@code Outer$Inner} becomes
     * {@code Outer.Inner} - the form a Java reader expects in a header.
     */
    public static String simpleName(String name) {
        if (name == null || name.isEmpty()) {
            return "object";
        }
        int dot = name.lastIndexOf('.');
        String simple = dot >= 0 ? name.substring(dot + 1) : name;
        return simple.replace('$', '.');
    }

    @Override
    public String toString() {
        return typeName + members;
    }
}
