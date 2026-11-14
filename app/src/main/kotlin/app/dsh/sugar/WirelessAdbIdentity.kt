package app.dsh.sugar

import android.util.AtomicFile
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

internal class WirelessAdbIdentity(val key: PrivateKey, val certificate: X509Certificate) {
    companion object {
        fun load(directory: File): WirelessAdbIdentity {
            val store = AtomicFile(File(directory, "identity"))
            val existing = try { store.openRead() } catch (_: FileNotFoundException) { null }
            if (existing != null) {
                return DataInputStream(existing).use { input ->
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(readBlock(input)))
                    val certificate = decodeCertificate(readBlock(input))
                    WirelessAdbIdentity(key, certificate)
                }
            }
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=DSH Sugar")
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val now = System.currentTimeMillis()
            val body = V3TBSCertificateGenerator().apply {
                setSignature(algorithm)
                setSerialNumber(ASN1Integer(BigInteger(127, SecureRandom()).setBit(127)))
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(now - 86_400_000L)))
                setEndDate(Time(Date(now + 10L * 366 * 86_400_000L)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
            }.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(key.private)
                update(body.encoded)
            }.sign()
            val der = DERSequence(arrayOf(body, algorithm, DERBitString(signature))).encoded
            val certificate = decodeCertificate(der)
            val stream = store.startWrite()
            try {
                val output = DataOutputStream(stream)
                for (block in listOf(key.private.encoded, der)) {
                    output.writeInt(block.size)
                    output.write(block)
                }
                output.flush()
                store.finishWrite(stream)
            } catch (error: Throwable) {
                store.failWrite(stream)
                throw error
            }
            return WirelessAdbIdentity(key.private, certificate)
        }

        private fun readBlock(input: DataInputStream): ByteArray {
            val length = input.readInt()
            if (length !in 1..8192) throw IOException("本机 ADB 身份文件损坏")
            return ByteArray(length).also(input::readFully)
        }

        private fun decodeCertificate(der: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}
