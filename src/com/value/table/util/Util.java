package com.value.table.util;

import java.util.ArrayList;
import java.util.List;

import com.value.table.model.Config;
import com.value.table.model.MemberRef;
import com.value.table.model.UserClassType;

public final class Util {

    private Util() {
    }

    public static String formatStr(String s) {
        if (s == null) {
            return null;
        }
        s = s.replace(" ", "").replace("\t", "");
        if (s.equals("") || s.equals("null")) {
            return null;
        }
        return s;
    }

    /**
     * Rough pixel width of a string for a fixed {@link Config#fontSize}.
     */
    public static int getStringW(String s) {
        if (s == null) {
            return 0;
        }
        int num = 0;
        for (int i = 0; i < s.length(); i++) {
            num += s.charAt(i) > 0x80 ? 2 : 1;
        }
        return (num + 1) * Config.fontSize / 2 + Config.fontSize;
    }

    /**
     * Parses one "class &amp; arrayGetter &amp; rowGetter &amp; colGetter" tuple.
     */
    public static UserClassType createUserClassType(String typeName, String arrayName,
            String rowName, String colName) {
        if (typeName == null) {
            return null;
        }
        UserClassType ct = new UserClassType();
        ct.signature = getSignature(typeName);

        if (arrayName != null) {
            /*
             * A bracket list means "a row is these members, in this order":
             * [key(),value()]. It is checked before the single-accessor
             * spelling, because that one looks for the first "()" and would
             * read the whole list as one method name.
             */
            if (arrayName.startsWith("[") && arrayName.endsWith("]")) {
                ct.cellRefs = parseCellRefs(arrayName.substring(1, arrayName.length() - 1));
            } else {
                int idx = arrayName.indexOf("()");
                if (idx >= 0) {
                    ct.arrayName = arrayName.substring(0, idx);
                    ct.arrayReturnType = "()" + getSignature(arrayName.substring(idx + 2));
                    ct.arrayIsField = false;
                } else {
                    ct.arrayName = arrayName;
                    ct.arrayIsField = true;
                }
            }
        }

        if (rowName != null) {
            int idx = rowName.indexOf("()");
            if (idx >= 0) {
                ct.rowName = rowName.substring(0, idx);
                ct.rowReturnType = "()" + getSignature(rowName.substring(idx + 2));
                ct.rowIsField = false;
            } else if (rowName.startsWith("[") && rowName.endsWith("]")) {
                ct.rows = rowName.substring(1, rowName.length() - 1).split(",");
            } else {
                ct.rowName = rowName;
                ct.rowIsField = true;
            }
        }

        if (colName != null) {
            int idx = colName.indexOf("()");
            if (idx >= 0) {
                ct.colName = colName.substring(0, idx);
                ct.colReturnType = "()" + getSignature(colName.substring(idx + 2));
                ct.colIsField = false;
            } else if (colName.startsWith("[") && colName.endsWith("]")) {
                ct.cols = colName.substring(1, colName.length() - 1).split(",");
            } else {
                ct.colName = colName;
                ct.colIsField = true;
            }
        }
        return ct;
    }

    /**
     * Parses the members of a multi-member row: {@code key,value()} or
     * {@code key(),value()java.lang.Object}.
     * <p>
     * The return type is optional and defaults to {@code Object}, which is what
     * a generic getter such as {@code Map.Entry.getKey()} really declares after
     * erasure. A getter that returns something narrower - a primitive, or a
     * concrete type - has to name it, exactly as the single-accessor rules
     * always have: the call is resolved by name and signature on the target.
     * </p>
     */
    private static MemberRef[] parseCellRefs(String list) {
        String[] parts = list.split(",");
        List<MemberRef> refs = new ArrayList<MemberRef>();
        for (String part : parts) {
            MemberRef ref = parseMemberRef(part);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs.isEmpty() ? null : refs.toArray(new MemberRef[0]);
    }

    /**
     * Parses one member of a rule: {@code name} for a field, {@code name()} for
     * a method whose return type is {@code Object}, {@code name()int} or
     * {@code name()java.lang.String} when the type has to be named.
     *
     * @return {@code null} for an empty item
     */
    public static MemberRef parseMemberRef(String item) {
        if (item == null) {
            return null;
        }
        String text = item.trim();
        if (text.isEmpty()) {
            return null;
        }
        int idx = text.indexOf("()");
        if (idx < 0) {
            return MemberRef.field(text, null);
        }
        String name = text.substring(0, idx);
        if (name.isEmpty()) {
            return null;
        }
        String ret = text.substring(idx + 2).trim();
        return MemberRef.method(name,
                "()" + (ret.isEmpty() ? "Ljava/lang/Object;" : getSignature(ret)), null);
    }

    /** The rule syntax for one member, the inverse of {@link #parseMemberRef}. */
    public static String formatMemberRef(MemberRef ref) {
        if (ref == null) {
            return "";
        }
        if (!ref.isMethod() || "()Ljava/lang/Object;".equals(ref.returnSig)) {
            return ref.label;
        }
        return ref.label + typeNameOf(ref.returnSig);
    }

    /**
     * Turns a JVM signature back into a source level type name, so that a member
     * can be written as {@code getLevel()int} and read back by
     * {@link #getSignature(String)}.
     */
    public static String typeNameOf(String methodSig) {
        if (methodSig == null || methodSig.length() < 3) {
            return "java.lang.Object";
        }
        return classNameOf(methodSig.substring(2));
    }

    /** The source level name of one JVM type signature, e.g. {@code Ljava/util/Map;}. */
    public static String classNameOf(String signature) {
        if (signature == null || signature.isEmpty()) {
            return "java.lang.Object";
        }
        String sig = signature;
        int dims = 0;
        while (sig.startsWith("[")) {
            dims++;
            sig = sig.substring(1);
        }
        String base;
        if (sig.startsWith("L") && sig.endsWith(";")) {
            base = sig.substring(1, sig.length() - 1).replace('/', '.');
        } else if (sig.length() == 1) {
            switch (sig.charAt(0)) {
                case 'V': base = "void"; break;
                case 'Z': base = "boolean"; break;
                case 'B': base = "byte"; break;
                case 'C': base = "char"; break;
                case 'S': base = "short"; break;
                case 'I': base = "int"; break;
                case 'J': base = "long"; break;
                case 'F': base = "float"; break;
                case 'D': base = "double"; break;
                default: base = "java.lang.Object"; break;
            }
        } else {
            base = "java.lang.Object";
        }
        StringBuilder out = new StringBuilder(base);
        for (int i = 0; i < dims; i++) {
            out.append("[]");
        }
        return out.toString();
    }

    /**
     * Converts a java type name such as {@code int[]} or {@code java.util.Map}
     * into a JVM signature.
     */
    public static String getSignature(String name) {
        StringBuilder start = new StringBuilder();
        while (name.endsWith("[]")) {
            start.append('[');
            name = name.substring(0, name.length() - 2);
        }
        if ("void".equals(name)) {
            return start + "V";
        }
        if ("boolean".equals(name)) {
            return start + "Z";
        }
        if ("byte".equals(name)) {
            return start + "B";
        }
        if ("char".equals(name)) {
            return start + "C";
        }
        if ("short".equals(name)) {
            return start + "S";
        }
        if ("int".equals(name)) {
            return start + "I";
        }
        if ("long".equals(name)) {
            return start + "J";
        }
        if ("float".equals(name)) {
            return start + "F";
        }
        if ("double".equals(name)) {
            return start + "D";
        }
        return start + "L" + name.replace('.', '/') + ";";
    }
}
