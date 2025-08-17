package com.cawcafr.ameditor.axml.arsc.item;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Représente une chaîne unique dans un StringPool binaire Android (AXML / ARSC).
 * Elle sait s'écrire en UTF-8 ou UTF-16 selon le flag de son StringPool.
 */
public class StringItem {

    private String value;
    private boolean utf8 = true; // par défaut UTF-8

    public StringItem() {
    }

    public String get() {
        return value;
    }

    public void set(String str) {
        this.value = str;
    }

    public boolean isUtf8() {
        return utf8;
    }

    public void setUtf8(boolean utf8) {
        this.utf8 = utf8;
    }

    /**
     * Écrit cette valeur dans le format utilisé par StringPool AXML.
     * @param out flux de sortie
     */
    public void writeBytes(OutputStream out) throws IOException {
        if (value == null) {
            // Chaîne vide
            writeLength(out, 0, utf8);
            if (utf8) {
                out.write(0); // terminateur UTF-8
            } else {
                out.write(0);
                out.write(0); // terminateur UTF-16
            }
            return;
        }
        Charset cs = utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE;
        byte[] bytes = value.getBytes(cs);

        if (utf8) {
            // format : u8len, u8len, data, 0
            writeLength(out, bytes.length, true);  // nombre de caractères
            writeLength(out, bytes.length, true);  // nombre d'octets
            out.write(bytes);
            out.write(0); // terminateur UTF-8
        } else {
            int charCount = value.length();
            writeLength(out, charCount, false); // nombre de caractères
            out.write(bytes);
            out.write(0);
            out.write(0); // terminateur UTF-16
        }
    }

    /**
     * Écrit une longueur au format utilisé dans les StringPool Android.
     */
    private void writeLength(OutputStream out, int length, boolean utf8) throws IOException {
        if (length > 0x7F) {
            // si longueur sur plus de 7 bits → marque MSB à 1 et octet suivant
            int high = (length >> 8) | 0x80;
            int low = length & 0xFF;
            out.write(high);
            out.write(low);
        } else {
            out.write(length);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}