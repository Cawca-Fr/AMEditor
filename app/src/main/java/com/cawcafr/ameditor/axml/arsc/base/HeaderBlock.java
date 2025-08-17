package com.cawcafr.ameditor.axml.arsc.base;

import java.io.IOException;
import java.io.OutputStream;

public class HeaderBlock {

    private int chunkType;
    private int headerSize = 8;
    private int chunkSize = 8;
    private int flags = 0;

    public HeaderBlock(int chunkType) {
        this.chunkType = chunkType;
    }

    public int getChunkType() {
        return chunkType;
    }

    public void setChunkType(int t) {
        chunkType = t;
    }

    public int getHeaderSize() {
        return headerSize;
    }

    public void setHeaderSize(int size) {
        headerSize = size;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int sz) {
        chunkSize = sz;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int f) {
        flags = f;
    }

    public void writeBytes(OutputStream out) throws IOException {
        writeInt(out, chunkType);
        writeInt(out, chunkSize);
        // headersize flags if necessery
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }
}