package com.cawcafr.ameditor.axml.arsc.chunk;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.array.StringArray;
import com.cawcafr.ameditor.axml.arsc.item.StringItem;

import java.io.IOException;
import java.io.OutputStream;

/**
 * StringPool est utilisé pour stocker toutes les chaînes référencées par index dans le binaire AXML.
 */
public class StringPool extends BaseChunk {

    public static final int UTF8_FLAG = 0x00000100;

    private final StringArray mStringArray;

    public StringPool(HeaderBlock headerBlock) {
        super(headerBlock);
        mStringArray = new StringArray();
    }

    public StringPool() {
        this(new HeaderBlock(ChunkType.STRING_POOL));
    }

    public boolean isUTF8() {
        return (getFlags() & UTF8_FLAG) != 0;
    }

    public void setUTF8(boolean utf8) {
        int flags = getFlags();
        if (utf8) {
            flags |= UTF8_FLAG;
        } else {
            flags &= ~UTF8_FLAG;
        }
        setFlags(flags);
    }

    public int getFlags() {
        return getHeaderBlock().getFlags();
    }

    public void setFlags(int flags) {
        getHeaderBlock().setFlags(flags);
    }

    public int size() {
        return mStringArray.size();
    }

    public String get(int index) {
        StringItem item = mStringArray.get(index);
        return item != null ? item.get() : null;
    }

    public void add(String str) {
        mStringArray.add(str);
    }

    public int indexOf(String str) {
        return mStringArray.indexOf(str);
    }

    @Override
    public void onPreWrite() {
        super.onPreWrite();
        mStringArray.refresh();
    }

    public StringArray getStringArray() {
        return mStringArray;
    }

    @Override
    public int getSize() {
        // Doit retourner la taille totale en octets du StringPool chunk (à affiner si besoin)
        return 8 + mStringArray.size(); // Header + strings
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        getHeaderBlock().writeBytes(out);
        mStringArray.writeBytes(out);
    }

    @Override
    public String toString() {
        return "StringPool (" + size() + " strings, UTF8=" + isUTF8() + ")";
    }
}