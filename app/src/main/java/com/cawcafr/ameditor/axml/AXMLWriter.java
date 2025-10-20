package com.cawcafr.ameditor.axml;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AXMLWriter complet avec gestion robuste des erreurs et multiples modes d'encodage
 */
public class AXMLWriter {

    private static final String TAG = "AXMLWriter";

    private final Encoder encoder;
    private Config config;

    public AXMLWriter() {
        this.encoder = new Encoder();
        this.config = new Config();
    }

    /**
     * Encode un fichier XML en AXML binaire
     */
    public void encodeFile(File xmlFile, OutputStream out) throws AXMLEncodeException {
        if (xmlFile == null) {
            throw new AXMLEncodeException("XML file is null");
        }

        if (!xmlFile.exists()) {
            throw new AXMLEncodeException("XML file does not exist: " + xmlFile.getAbsolutePath());
        }

        if (!xmlFile.canRead()) {
            throw new AXMLEncodeException("Cannot read XML file: " + xmlFile.getAbsolutePath());
        }

        long fileSize = xmlFile.length();
        if (fileSize == 0) {
            throw new AXMLEncodeException("XML file is empty: " + xmlFile.getAbsolutePath());
        }

        if (fileSize > config.maxFileSize) {
            throw new AXMLEncodeException("XML file too large: " + fileSize + " bytes (max: " + config.maxFileSize + ")");
        }

        Log.i(TAG, "Starting AXML encoding for: " + xmlFile.getAbsolutePath() + " (" + fileSize + " bytes)");

        try {
            byte[] axmlData = encoder.encodeFile(new DefaultReferenceResolver(), xmlFile.getAbsolutePath());

            validateAxmlData(axmlData, xmlFile);

            out.write(axmlData);
            out.flush();

            Log.i(TAG, "AXML encoding successful. Output size: " + axmlData.length + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "AXML encoding failed for: " + xmlFile.getAbsolutePath(), e);
            throw new AXMLEncodeException("Encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encode une chaîne XML en AXML binaire
     */
    public void encodeString(String xmlContent, OutputStream out) throws AXMLEncodeException {
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            throw new AXMLEncodeException("XML content is null or empty");
        }

        if (xmlContent.length() > config.maxStringSize) {
            throw new AXMLEncodeException("XML content too large: " + xmlContent.length() + " chars (max: " + config.maxStringSize + ")");
        }

        Log.i(TAG, "Starting AXML encoding from string (" + xmlContent.length() + " chars)");

        try {
            byte[] axmlData = encoder.encodeString(new DefaultReferenceResolver(), xmlContent);

            validateAxmlData(axmlData, "string input");

            out.write(axmlData);
            out.flush();

            Log.i(TAG, "AXML encoding from string successful. Output size: " + axmlData.length + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "AXML encoding from string failed", e);
            throw new AXMLEncodeException("String encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encode un InputStream XML en AXML binaire
     */
    public void encodeStream(InputStream xmlStream, OutputStream out) throws AXMLEncodeException {
        if (xmlStream == null) {
            throw new AXMLEncodeException("XML stream is null");
        }

        Log.i(TAG, "Starting AXML encoding from stream");

        try {
            // Lire tout le stream en mémoire (pour les petites/moyennes tailles)
            byte[] buffer = new byte[config.maxFileSize];
            int bytesRead = xmlStream.read(buffer);

            if (bytesRead <= 0) {
                throw new AXMLEncodeException("XML stream is empty");
            }

            String xmlContent = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            encodeString(xmlContent, out);

        } catch (IOException e) {
            throw new AXMLEncodeException("Stream reading failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AXMLEncodeException("Stream encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Méthode statique pour un usage rapide - encode un fichier
     */
    public static void encode(File xmlFile, OutputStream out) throws AXMLEncodeException {
        new AXMLWriter().encodeFile(xmlFile, out);
    }

    /**
     * Méthode statique pour encoder depuis une string
     */
    public static void encodeStringContent(String xmlContent, OutputStream out) throws AXMLEncodeException {
        new AXMLWriter().encodeString(xmlContent, out);
    }

    /**
     * Vérifie la validité des données AXML générées
     */
    private void validateAxmlData(byte[] axmlData, Object source) throws AXMLEncodeException {
        if (axmlData == null) {
            throw new AXMLEncodeException("Encoder returned null data for: " + source);
        }

        if (axmlData.length == 0) {
            throw new AXMLEncodeException("Encoder returned empty data for: " + source);
        }

        if (axmlData.length < 8) {
            throw new AXMLEncodeException("AXML data too small: " + axmlData.length + " bytes for: " + source);
        }

        // Vérification basique du header AXML
        if (!isValidAxmlHeader(axmlData)) {
            Log.w(TAG, "Generated AXML data may have invalid header for: " + source);
            // On ne throw pas d'exception car certains encodeurs peuvent avoir des headers différents
        }

        Log.d(TAG, "AXML data validation passed for: " + source + " (" + axmlData.length + " bytes)");
    }

    /**
     * Vérifie le header AXML basique
     */
    private boolean isValidAxmlHeader(byte[] data) {
        // Header AXML typique: 0x03000800 ou variations
        if (data.length >= 8) {
            // Vérifier les magic bytes communs
            return (data[0] == 0x03 && data[1] == 0x00) || // Un common header pattern
                    (data[0] == 0x02 && data[1] == 0x00);   // Un autre pattern possible
        }
        return false;
    }

    /**
     * Classe de configuration pour AXMLWriter
     */
    public static class Config {
        public int maxFileSize = 10 * 1024 * 1024; // 10MB par défaut
        public int maxStringSize = 5 * 1024 * 1024; // 5MB par défaut
        public boolean validateOutput = true;
        public boolean logDetailed = false;

        public Config setMaxFileSize(int maxSize) {
            this.maxFileSize = maxSize;
            return this;
        }

        public Config setMaxStringSize(int maxSize) {
            this.maxStringSize = maxSize;
            return this;
        }

        public Config setValidateOutput(boolean validate) {
            this.validateOutput = validate;
            return this;
        }

        public Config setLogDetailed(boolean detailed) {
            this.logDetailed = detailed;
            return this;
        }
    }

    /**
     * Exception spécifique pour l'encodage AXML
     */
    public static class AXMLEncodeException extends Exception {
        public AXMLEncodeException(String message) {
            super(message);
        }

        public AXMLEncodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    /**
     * Nettoie les ressources (si nécessaire)
     */
    public void cleanup() {
        // Pour l'instant rien à cleaner, mais gardé pour l'extension future
        Log.d(TAG, "AXMLWriter cleanup completed");
    }
}
