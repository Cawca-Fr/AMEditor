package com.cawcafr.ameditor.axml.arsc.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Outil de lecture depuis un flux binaire, utilisé pour parser un fichier AXML.
 */
public class BlockReader {

    private final InputStream input;
    private int position = 0;

    public BlockReader(InputStream in) {
        this.input = in;
    }

    public int getPosition() {
        return position;
    }

    public int readByte() throws IOException {
        int b = input.read();
        if (b == -1) {
            throw new EOFException("Unexpected EOF at position " + position);
        }
        position++;
        return b & 0xFF;
    }

    public int readShort() throws IOException {
        int low = readByte();
        int high = readByte();
        return (high << 8) | low;
    }

    public int readInt() throws IOException {
        int b1 = readByte();
        int b2 = readByte();
        int b3 = readByte();
        int b4 = readByte();
        return ((b4 << 24) | (b3 << 16) | (b2 << 8) | b1);
    }

    public byte[] readBytes(int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int r = input.read(buf, read, length - read);
            if (r == -1) throw new EOFException("Unexpected EOF");
            read += r;
        }
        position += length;
        return buf;
    }

    public String readUtf8(int length) throws IOException {
        byte[] data = readBytes(length);
        return new String(data, StandardCharsets.UTF_8);
    }

    public void skip(int count) throws IOException {
        if (count <= 0) return;
        long skipped = input.skip(count);
        if (skipped < count) throw new EOFException("Unexpected EOF when skipping");
        position += skipped;
    }

    public void close() throws IOException {
        input.close();
    }
}