package com.value.table.model;

/**
 * One readable member of an entity type: either a field, or a method without
 * parameters.
 * <p>
 * Instances are immutable and hold no reference to the target VM, so a resolved
 * member list can be cached for the rest of the session.
 * </p>
 */
public final class MemberRef implements Comparable<MemberRef> {

    /** Name shown in the column header, e.g. {@code name} or {@code getLevel()}. */
    public final String label;
    /** Name used to talk to the target VM. */
    public final String name;
    /** {@code ()Ret} for a method, {@code null} for a field. */
    public final String returnSig;
    /** JVM signature of the type that declares this member. */
    public final String declaredIn;

    private MemberRef(String label, String name, String returnSig, String declaredIn) {
        this.label = label;
        this.name = name;
        this.returnSig = returnSig;
        this.declaredIn = declaredIn;
    }

    public static MemberRef field(String name, String declaredIn) {
        return new MemberRef(name, name, null, declaredIn);
    }

    public static MemberRef method(String name, String returnSig, String declaredIn) {
        return new MemberRef(name + "()", name, returnSig, declaredIn);
    }

    public boolean isMethod() {
        return returnSig != null;
    }

    @Override
    public int compareTo(MemberRef other) {
        return label.compareToIgnoreCase(other.label);
    }

    @Override
    public String toString() {
        return label;
    }
}
