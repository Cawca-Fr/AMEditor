package com.cawcafr.ameditor.axml.arsc.base;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Bloc de base pour toutes les structures binaires.
 * Fournit l'API pour écrire en flux et manipuler les données.
 */
public abstract class Block {

    /** Taille totale en octets du bloc lors de l'écriture */
    public abstract int getSize();

    /** Écrit les octets du bloc dans un OutputStream */
    public abstract void writeBytes(OutputStream out) throws IOException;

    /**
     * Pré-écriture, appelée avant writeBytes()
     * (pour recalculer des tailles, offsets, etc.)
     */
    public void onPreWrite() {
        // Par défaut rien
    }
}