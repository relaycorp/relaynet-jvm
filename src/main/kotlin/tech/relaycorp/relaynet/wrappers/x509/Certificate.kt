package tech.relaycorp.relaynet.wrappers.x509

import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.sql.Date
import java.time.LocalDateTime
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import tech.relaycorp.relaynet.wrappers.generateRandomBigInteger

class Certificate constructor(val certificateHolder: X509CertificateHolder) {
    companion object {
        private const val DEFAULT_ALGORITHM = "SHA256WithRSAEncryption"

        @Throws(CertificateException::class)
        fun issue(
            subjectCommonName: String,
            subjectPublicKey: PublicKey,
            issuerPrivateKey: PrivateKey,
            validityEndDate: LocalDateTime,
            issuerCertificate: Certificate? = null,
            isCA: Boolean = false,
            pathLenConstraint: Int = 0,
            validityStartDate: LocalDateTime = LocalDateTime.now()
        ): Certificate {
            if (validityStartDate >= validityEndDate) {
                throw CertificateException("The end date must be later than the start date")
            }
            if (issuerCertificate != null) {
                requireCertificateToBeCA(issuerCertificate)
            }

            val subjectDistinguishedName = buildDistinguishedName(subjectCommonName)
            val issuerDistinguishedName = if (issuerCertificate != null)
                issuerCertificate.certificateHolder.issuer
            else
                subjectDistinguishedName
            val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(subjectPublicKey.encoded)

            val builder = X509v3CertificateBuilder(
                issuerDistinguishedName,
                generateRandomBigInteger(),
                Date.valueOf(validityStartDate.toLocalDate()),
                Date.valueOf(validityEndDate.toLocalDate()),
                subjectDistinguishedName,
                subjectPublicKeyInfo
            )

            val basicConstraints = BasicConstraintsExtension(isCA, pathLenConstraint)
            builder.addExtension(Extension.basicConstraints, true, basicConstraints)

            val subjectPublicKeyDigest = getPublicKeyInfoDigest(subjectPublicKeyInfo)
            val ski = SubjectKeyIdentifier(subjectPublicKeyDigest)
            builder.addExtension(Extension.subjectKeyIdentifier, false, ski)

            val issuerPublicKeyDigest = if (issuerCertificate != null)
                getPublicKeyInfoDigest(issuerCertificate.certificateHolder.subjectPublicKeyInfo)
            else
                subjectPublicKeyDigest
            val aki = AuthorityKeyIdentifier(issuerPublicKeyDigest)
            builder.addExtension(Extension.authorityKeyIdentifier, false, aki)

            val signerBuilder = makeSigner(issuerPrivateKey)
            return Certificate(builder.build(signerBuilder))
        }

        private fun getPublicKeyInfoDigest(keyInfo: SubjectPublicKeyInfo): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(keyInfo.parsePublicKey().encoded)
        }

        @Throws(CertificateException::class)
        private fun buildDistinguishedName(commonName: String): X500Name {
            val builder = X500NameBuilder(BCStyle.INSTANCE)
            builder.addRDN(BCStyle.C, commonName)
            return builder.build()
        }

        private fun requireCertificateToBeCA(issuerCertificate: Certificate) {
            val issuerBasicConstraintsExtension =
                issuerCertificate.certificateHolder.getExtension(Extension.basicConstraints)
                    ?: throw CertificateException(
                        "Issuer certificate should have basic constraints extension"
                    )
            val issuerBasicConstraints = BasicConstraints.getInstance(issuerBasicConstraintsExtension.parsedValue)
            if (!issuerBasicConstraints.isCA) {
                throw CertificateException("Issuer certificate should be marked as CA")
            }
        }

        private fun makeSigner(issuerPrivateKey: PrivateKey): ContentSigner {
            val signatureAlgorithm = DefaultSignatureAlgorithmIdentifierFinder().find(DEFAULT_ALGORITHM)
            val digestAlgorithm = DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)
            val privateKeyParam: AsymmetricKeyParameter = PrivateKeyFactory.createKey(issuerPrivateKey.encoded)
            val contentSignerBuilder = BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm)
            return contentSignerBuilder.build(privateKeyParam)
        }

        @Throws(CertificateException::class)
        fun deserialize(certificateSerialized: ByteArray): Certificate {
            val certificateHolder = try {
                X509CertificateHolder(certificateSerialized)
            } catch (_: IOException) {
                throw CertificateException(
                    "Value should be a DER-encoded, X.509 v3 certificate"
                )
            }
            return Certificate(certificateHolder)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Certificate) {
            return false
        }
        return certificateHolder == other.certificateHolder
    }

    override fun hashCode(): Int {
        return certificateHolder.hashCode()
    }

    fun serialize(): ByteArray {
        return certificateHolder.encoded
    }
}