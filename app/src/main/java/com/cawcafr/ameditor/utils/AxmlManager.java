package com.cawcafr.ameditor.utils;

import android.content.Context;
import android.util.Log;

import com.apk.axml.aXMLDecoder;
import com.apk.axml.aXMLEncoder;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AxmlManager {

    private static final String TAG = "AxmlManager";

    /**
     * ÉTAPE 1 : Décodage (AXML Binaire -> XML Texte)
     * Lit un fichier AndroidManifest.xml binaire et le transforme en String lisible.
     */
    public String decodeAxmlToXml(String cheminEntreeAxml) {
        String xmlDecoded = null;
        try (InputStream is = new FileInputStream(cheminEntreeAxml)) {
            // Création du décodeur avec le flux d'entrée
            aXMLDecoder decoder = new aXMLDecoder(is);

            // La méthode magique qui transforme le binaire en texte
            xmlDecoded = decoder.decodeAsString();

            Log.d(TAG, "Décodage réussi !");
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du décodage AXML -> XML", e);
        }
        return xmlDecoded;
    }

    /**
     * ÉTAPE 2 : Encodage (XML Texte -> AXML Binaire)
     * Prend le texte XML modifié et le recompile en format binaire Android.
     */
    public void convertXmlToAxml(String monXmlEnTexteBrut, String cheminSortieAxml, Context context) {
        try (FileOutputStream fos = new FileOutputStream(cheminSortieAxml)) {
            aXMLEncoder encoder = new aXMLEncoder();

            // Encodage du texte en binaire
            // Note : Le Context est nécessaire pour résoudre certaines ressources (ex: @string/app_name)
            byte[] data = encoder.encodeString(monXmlEnTexteBrut, context);

            fos.write(data);
            Log.d(TAG, "Encodage réussi : Fichier généré à " + cheminSortieAxml);

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'encodage XML -> AXML", e);
        }
    }

    /**
     * Méthode utilitaire pour sauvegarder le texte XML intermédiaire dans un fichier (optionnel)
     * Utile si tu veux appliquer ton patch Python ou Java sur un fichier physique.
     */
    public void saveTextFile(String content, String path) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}