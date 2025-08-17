package com.cawcafr.ameditor.axml.arsc.chunk;

public class ChunkType {
    public static final int NULL = 0x0000;
    public static final int STRING_POOL = 0x0001;
    public static final int TABLE = 0x0002;
    public static final int XML = 0x0003;

    public static final int XML_FIRST_CHUNK = 0x0100;
    public static final int XML_START_NAMESPACE = 0x0100;
    public static final int XML_END_NAMESPACE = 0x0101;
    public static final int XML_START_ELEMENT = 0x0102;
    public static final int XML_END_ELEMENT = 0x0103;
    public static final int XML_CDATA = 0x0104;
    public static final int XML_LAST_CHUNK = 0x017f;

    public static final int XML_RESOURCE_MAP = 0x0180;
    public static final int TABLE_PACKAGE = 0x0200;
    public static final int TABLE_TYPE = 0x0201;
    public static final int TABLE_TYPE_SPEC = 0x0202;

    // Tu peux ajouter d'autres types si tu traites ResTable, etc.
}