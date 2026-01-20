package com.cawcafr.ameditor.util

import android.util.Log
import com.android.apksig.ApkSigner
import com.android.apksig.apk.ApkUtils
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object SignerUtils {

    fun signApk(
        inputApk: File,
        outputApk: File,
        keystoreFile: File,
        keystorePass: String,
        keyAlias: String,
        keyPass: String
    ) {
        try {
            // 1. Charger le KeyStore (JKS ou PKCS12 automatiquement)
            val keyStore = loadKeyStore(keystoreFile, keystorePass)

            // 2. Récupérer la clé privée et le certificat
            val privateKeyEntry = keyStore.getEntry(keyAlias, KeyStore.PasswordProtection(keyPass.toCharArray())) as? KeyStore.PrivateKeyEntry
                ?: throw RuntimeException("Alias '$keyAlias' not found or wrong password.")

            val privateKey: PrivateKey = privateKeyEntry.privateKey
            val certificate: X509Certificate = privateKeyEntry.certificate as X509Certificate
            val certs = listOf(certificate)

            // 3. Configurer le Signer (Google apksig)
            val signer = ApkSigner.Builder(listOf(
                ApkSigner.SignerConfig.Builder(
                    "USER_CERT", // Nom arbitraire
                    privateKey,
                    certs
                ).build()
            ))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true) // Signature classique (compatible vieux Android)
                .setV2SigningEnabled(true) // Signature V2 (Obligatoire pour Android 11+)
                .setV3SigningEnabled(true) // Signature V3 (Rotation de clés)

            // 4. Signer
            signer.build().sign()

            Log.d("SignerUtils", "Signature V1+V2+V3 réussie !")

        } catch (e: Exception) {
            Log.e("SignerUtils", "Erreur apksig", e)
            // On renvoie l'exception pour l'afficher dans l'interface
            throw RuntimeException("Signing Error: ${e.message}")
        }
    }

    /**
     * Tente de charger le keystore en PKCS12, puis fallback en JKS/BKS si besoin.
     */
    private fun loadKeyStore(file: File, password: String): KeyStore {
        // Essai 1 : Type par défaut (souvent PKCS12 sur les JDK récents)
        try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            FileInputStream(file).use { ks.load(it, password.toCharArray()) }
            return ks
        } catch (e: Exception) {
            // Essai 2 : Forcer JKS (pour les vieux keystores)
            try {
                val ks = KeyStore.getInstance("JKS")
                FileInputStream(file).use { ks.load(it, password.toCharArray()) }
                return ks
            } catch (e2: Exception) {
                // Essai 3 : Forcer BKS (BouncyCastle, fréquent sur Android)
                val ks = KeyStore.getInstance("BKS")
                FileInputStream(file).use { ks.load(it, password.toCharArray()) }
                return ks
            }
        }
    }
}