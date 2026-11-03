package com.value.table.model;

import java.util.Arrays;

/**
 * One user defined rendering rule bound to a JVM type signature.
 */
public class UserClassType {

    public static final String ALL_FIELDS = "@allFields";

    public String signature;
    public String arrayName;
    public String rowName;
    public String colName;
    public String arrayReturnType;
    public String rowReturnType;
    public String colReturnType;
    public boolean arrayIsField;
    public boolean rowIsField;
    public boolean colIsField;
    /**
     * When a row is made of several members instead of one accessor, the
     * members to read, in column order. Written as {@code [key,value()]} in the
     * "rows from" slot; {@code null} for the ordinary one-accessor rule.
     * <p>
     * A {@code Map} needs this: its entries are objects, and what a debugger
     * wants from one is the key and the value, not the entry's {@code toString}.
     * </p>
     */
    public MemberRef[] cellRefs;
    public String[] rows;
    public String[] cols;

    @Override
    public String toString() {
        return "UserClassType [signature=" + signature + ", arrayName=" + arrayName
                + ", rowName=" + rowName + ", colName=" + colName
                + ", arrayReturnType=" + arrayReturnType + ", rowReturnType=" + rowReturnType
                + ", colReturnType=" + colReturnType + ", arrayIsField=" + arrayIsField
                + ", rowIsField=" + rowIsField + ", colIsField=" + colIsField
                + ", cells=" + Arrays.toString(cellRefs)
                + ", rows=" + Arrays.toString(rows) + ", cols=" + Arrays.toString(cols) + "]";
    }
}
