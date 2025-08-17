package com.cawcafr.ameditor.axml.arsc.value;

public enum ValueType {
    NULL(0x00),
    REFERENCE(0x01),
    ATTRIBUTE(0x02),
    STRING(0x03),
    FLOAT(0x04),
    DIMENSION(0x05),
    FRACTION(0x06),
    DYNAMIC_REFERENCE(0x07),
    INT_DEC(0x10),
    INT_HEX(0x11),
    INT_BOOLEAN(0x12),
    INT_COLOR_ARGB8(0x1c),
    INT_COLOR_RGB8(0x1d),
    INT_COLOR_ARGB4(0x1e),
    INT_COLOR_RGB4(0x1f);

    private final int id;

    ValueType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ValueType fromId(int id) {
        for (ValueType t : values())
            if (t.id == id) return t;
        return null;
    }
}