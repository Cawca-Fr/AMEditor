package com.cawcafr.ameditor.axml.arsc.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Outil d'écriture dans un flux binaire, utilisé pour générer un fichier AXML.
 */
public class BlockWriter {

    private final OutputStream out;
    private int position = 0;

    public BlockWriter(OutputStream out) {
        this.out = out;
    }

    public int getPosition() {
        return position;
    }

    public void writeByte(int b) throws IOException {
        out.write(b & 0xFF);
        position++;
    }

    public void writeShort(int v) throws IOException {
        writeByte(v);
        writeByte(v >> 8);
    }

    public void writeInt(int v) throws IOException {
        writeByte(v);
        writeByte(v >> 8);
        writeByte(v >> 16);
        writeByte(v >> 24);
    }

    public void writeBytes(byte[] data) throws IOException {
        out.write(data);
        position += data.length;
    }

    public void writeUtf8(String str) throws IOException {
        if (str == null) str = "";
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        writeBytes(data);
    }

    public void close() throws IOException {
        out.flush();
        out.close();
    }
}