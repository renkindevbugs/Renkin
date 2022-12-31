package com.kaanelloed.iconeration

//Copied from ReVanced Manager
//https://github.com/revanced/revanced-manager/blob/flutter/android/app/src/main/kotlin/app/revanced/manager/flutter/utils/signing/Signer.kt
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*

internal class Signer(
    private val cn: String, password: String
) {
    private val passwordCharArray = password.toCharArray()

    fun signApk(input: File, output: File, ks: File) {
        Security.addProvider(BouncyCastleProvider())

        val keyStore = KeyStore.getInstance("BKS", "BC")
        FileInputStream(ks).use { fis -> keyStore.load(fis, null) }
        val alias = keyStore.aliases().nextElement()

        val config = ApkSigner.SignerConfig.Builder(
            cn,
            keyStore.getKey(alias, passwordCharArray) as PrivateKey,
            listOf(keyStore.getCertificate(alias) as X509Certificate)
        ).build()

        val signer = ApkSigner.Builder(listOf(config))
        signer.setCreatedBy(cn)
        signer.setInputApk(input)
        signer.setOutputApk(output)

        signer.build().sign()
    }
}