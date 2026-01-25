package com.cawcafr.ameditor.util

import android.util.Log
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

object SignerUtils {

    /**
     * MÉTHODE 1 : Signature via Keystore (PKCS12)
     */
    fun signApk(
        inputApk: File,
        outputApk: File,
        keystoreFile: File,
        keystorePass: String,
        keyAlias: String,
        keyPass: String
    ) {
        try {
            val keyStore = loadKeyStore(keystoreFile, keystorePass)

            val privateKeyEntry = keyStore.getEntry(
                keyAlias,
                KeyStore.PasswordProtection(keyPass.toCharArray())
            ) as? KeyStore.PrivateKeyEntry
                ?: throw RuntimeException("Alias '$keyAlias' not found or wrong key password.")

            val privateKey: PrivateKey = privateKeyEntry.privateKey
            val certificate: X509Certificate = privateKeyEntry.certificate as X509Certificate

            performSign(inputApk, outputApk, privateKey, listOf(certificate))

        } catch (e: Exception) {
            Log.e("SignerUtils", "Sign Error", e)
            throw RuntimeException("Signing Error: ${e.message}")
        }
    }

    /**
     * MÉTHODE 2 : Signature via Paire de Fichiers (PK8 + PEM/X509)
     */
    fun signApkWithFilePair(
        inputApk: File,
        outputApk: File,
        pk8File: File,
        pemFile: File
    ) {
        try {
            // 1. Charger la clé privée (PKCS#8 non chiffrée)
            val privateKeyBytes = pk8File.readBytes()
            val keySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val keyFactory = KeyFactory.getInstance("RSA") // Ou "EC" selon la clé, RSA est le plus commun
            val privateKey = try {
                keyFactory.generatePrivate(keySpec)
            } catch (e: Exception) {
                // Fallback si c'est une clé EC (Elliptic Curve)
                KeyFactory.getInstance("EC").generatePrivate(keySpec)
            }

            // 2. Charger le certificat (X.509)
            val certFactory = CertificateFactory.getInstance("X.509")
            val certificate = FileInputStream(pemFile).use {
                certFactory.generateCertificate(it)
            } as X509Certificate

            // 3. Signer
            performSign(inputApk, outputApk, privateKey, listOf(certificate))

        } catch (e: Exception) {
            Log.e("SignerUtils", "PK8/PEM Sign Error", e)
            throw RuntimeException("PK8/PEM Error: ${e.message}")
        }
    }

    /**
     * Logique commune de signature (apksig)
     */
    private fun performSign(
        inputApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        certs: List<X509Certificate>
    ) {
        val signer = ApkSigner.Builder(listOf(
            ApkSigner.SignerConfig.Builder("USER_CERT", privateKey, certs).build()
        ))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)

        signer.build().sign()
        Log.d("SignerUtils", "Signature success!")
    }

    /**
     * Chargeur PKCS12 uniquement.
     */
    fun loadKeyStore(file: File, password: String): KeyStore {
        try {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { ks.load(it, password.toCharArray()) }
            return ks
        } catch (e: Exception) {
            throw RuntimeException("Failed to load PKCS12 keystore. Wrong password or unsupported format.")
        }
    }
}
