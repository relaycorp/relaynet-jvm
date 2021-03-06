package tech.relaycorp.relaynet.wrappers.cms

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.KeyAgreeRecipientId
import org.bouncycastle.cms.KeyAgreeRecipientInformation
import org.bouncycastle.cms.KeyTransRecipientId
import org.bouncycastle.cms.KeyTransRecipientInformation
import org.bouncycastle.cms.RecipientInfoGenerator
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyAgreeEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyAgreeRecipientInfoGenerator
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.operator.jcajce.JcaAlgorithmParametersConverter
import tech.relaycorp.relaynet.BC_PROVIDER
import tech.relaycorp.relaynet.HashingAlgorithm
import tech.relaycorp.relaynet.SymmetricEncryption
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

// CBC mode is temporary. See: https://github.com/relaycorp/relayverse/issues/16
private val cmsContentEncryptionAlgorithm = mapOf(
    SymmetricEncryption.AES_128 to CMSAlgorithm.AES128_CBC,
    SymmetricEncryption.AES_192 to CMSAlgorithm.AES192_CBC,
    SymmetricEncryption.AES_256 to CMSAlgorithm.AES256_CBC
)
internal val KEY_WRAP_ALGORITHMS = mapOf(
    SymmetricEncryption.AES_128 to CMSAlgorithm.AES128_WRAP,
    SymmetricEncryption.AES_192 to CMSAlgorithm.AES192_WRAP,
    SymmetricEncryption.AES_256 to CMSAlgorithm.AES256_WRAP
)

internal abstract class EnvelopedData(val bcEnvelopedData: CMSEnvelopedData) {
    companion object {
        @Throws(EnvelopedDataException::class)
        fun deserialize(envelopedDataSerialized: ByteArray): EnvelopedData {
            val bcEnvelopedData = try {
                CMSEnvelopedData(envelopedDataSerialized)
            } catch (exception: CMSException) {
                throw EnvelopedDataException(
                    "Value should be a DER-encoded CMS EnvelopedData",
                    exception
                )
            }

            val recipientsSize = bcEnvelopedData.recipientInfos.size()
            if (recipientsSize != 1) {
                throw EnvelopedDataException(
                    "Exactly one RecipientInfo is required (got $recipientsSize)"
                )
            }

            val envelopedData = when (val recipient = bcEnvelopedData.recipientInfos.first()) {
                is KeyTransRecipientInformation -> SessionlessEnvelopedData(bcEnvelopedData)
                is KeyAgreeRecipientInformation -> SessionEnvelopedData(bcEnvelopedData)
                else -> throw EnvelopedDataException(
                    "Unsupported RecipientInfo (got ${recipient::class.java.simpleName})"
                )
            }

            envelopedData.validate()
            return envelopedData
        }
    }

    fun serialize(): ByteArray {
        return bcEnvelopedData.encoded
    }

    @Throws(EnvelopedDataException::class)
    fun decrypt(privateKey: PrivateKey): ByteArray {
        val recipients = bcEnvelopedData.recipientInfos.recipients
        val recipientInfo = recipients.first()
        val recipient = if (privateKey is ECKey) {
            JceKeyAgreeEnvelopedRecipient(privateKey).setProvider(BC_PROVIDER)
        } else {
            JceKeyTransEnvelopedRecipient(privateKey).setProvider(BC_PROVIDER)
        }
        return try {
            recipientInfo.getContent(recipient)
        } catch (exception: Exception) {
            // BC usually throws CMSException when the key is invalid, but it occasionally
            // throws DataLengthException. The latter isn't reproducible, so to avoid code
            // coverage issues, we're handling all exceptions here. Yes, a name-your-poison thing.
            throw EnvelopedDataException("Could not decrypt value", exception)
        }
    }

    /**
     * Return the id of the recipient's key used to encrypt the content.
     */
    abstract fun getRecipientKeyId(): RecipientIdentifier

    /**
     * Validate EnvelopedData value, post-deserialization.
     */
    @Throws(EnvelopedDataException::class)
    abstract fun validate()
}

internal class SessionlessEnvelopedData(bcEnvelopedData: CMSEnvelopedData) :
    EnvelopedData(bcEnvelopedData) {
    companion object {
        fun encrypt(
            plaintext: ByteArray,
            recipientCertificate: Certificate,
            symmetricEncryptionAlgorithm: SymmetricEncryption = SymmetricEncryption.AES_128
        ): SessionlessEnvelopedData {
            val recipientInfoGenerator = makeRecipientInfoGenerator(recipientCertificate)
            val bcEnvelopedData = bcEncrypt(
                plaintext,
                symmetricEncryptionAlgorithm,
                recipientInfoGenerator
            )
            return SessionlessEnvelopedData(bcEnvelopedData)
        }

        private fun makeRecipientInfoGenerator(
            recipientCertificate: Certificate
        ): JceKeyTransRecipientInfoGenerator {
            val x509Certificate = JcaX509CertificateConverter()
                .getCertificate(recipientCertificate.certificateHolder)
            val algorithmIdentifier = JcaAlgorithmParametersConverter().getAlgorithmIdentifier(
                PKCSObjectIdentifiers.id_RSAES_OAEP,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT
                )
            )
            return JceKeyTransRecipientInfoGenerator(
                x509Certificate,
                algorithmIdentifier
            ).setProvider(BC_PROVIDER)
        }
    }

    override fun getRecipientKeyId(): RecipientIdentifier {
        val rid = bcEnvelopedData.recipientInfos.first().rid as KeyTransRecipientId
        return RecipientSerialNumber(rid.serialNumber)
    }

    @Throws(EnvelopedDataException::class)
    override fun validate() {
        val rid = bcEnvelopedData.recipientInfos.first().rid as KeyTransRecipientId
        if (rid.serialNumber == null) {
            // KeyTransRecipientId doesn't offer an unambiguous way to tell whether the id is
            // using IssuerAndSerialNumber or SubjectKeyIdentifier. On the contrary, its data
            // model allows for the two to be used at the same time, which is illegal per the CMS
            // spec. So for simplicity, we'll assume that if the serial number is missing, the
            // key id is a SubjectKeyIdentifier.
            throw EnvelopedDataException(
                "Required recipient key id to be IssuerAndSerialNumber (got SubjectKeyIdentifier)"
            )
        }
    }
}

internal class SessionEnvelopedData(bcEnvelopedData: CMSEnvelopedData) :
    EnvelopedData(bcEnvelopedData) {
    companion object {
        val ecdhAlgorithmByHashingAlgorithm = mapOf(
            HashingAlgorithm.SHA256 to CMSAlgorithm.ECDH_SHA256KDF,
            HashingAlgorithm.SHA384 to CMSAlgorithm.ECDH_SHA384KDF,
            HashingAlgorithm.SHA512 to CMSAlgorithm.ECDH_SHA512KDF
        )

        fun encrypt(
            plaintext: ByteArray,
            recipientCertificate: Certificate,
            originatorKeyPair: KeyPair,
            symmetricEncryptionAlgorithm: SymmetricEncryption = SymmetricEncryption.AES_128,
            hashingAlgorithm: HashingAlgorithm = HashingAlgorithm.SHA256
        ): SessionEnvelopedData {
            val recipientX509Certificate = JcaX509CertificateConverter()
                .getCertificate(recipientCertificate.certificateHolder)
            return encrypt(
                plaintext,
                originatorKeyPair,
                symmetricEncryptionAlgorithm,
                hashingAlgorithm
            ) { addRecipient(recipientX509Certificate) }
        }

        fun encrypt(
            plaintext: ByteArray,
            recipientKeyId: ByteArray,
            recipientKey: PublicKey,
            originatorKeyPair: KeyPair,
            symmetricEncryptionAlgorithm: SymmetricEncryption = SymmetricEncryption.AES_128,
            hashingAlgorithm: HashingAlgorithm = HashingAlgorithm.SHA256
        ): SessionEnvelopedData {
            return encrypt(
                plaintext,
                originatorKeyPair,
                symmetricEncryptionAlgorithm,
                hashingAlgorithm
            ) { addRecipient(recipientKeyId, recipientKey) }
        }

        private fun encrypt(
            plaintext: ByteArray,
            originatorKeyPair: KeyPair,
            symmetricEncryptionAlgorithm: SymmetricEncryption,
            hashingAlgorithm: HashingAlgorithm,
            recipientInfoAppender: JceKeyAgreeRecipientInfoGenerator.() -> Unit
        ): SessionEnvelopedData {
            val recipientInfoGenerator = makeRecipientInfoGenerator(
                originatorKeyPair,
                symmetricEncryptionAlgorithm,
                hashingAlgorithm
            )
            recipientInfoAppender(recipientInfoGenerator)

            val bcEnvelopedData = bcEncrypt(
                plaintext,
                symmetricEncryptionAlgorithm,
                recipientInfoGenerator
            )
            return SessionEnvelopedData(bcEnvelopedData)
        }

        private fun makeRecipientInfoGenerator(
            originatorKeyPair: KeyPair,
            symmetricEncryptionAlgorithm: SymmetricEncryption,
            hashingAlgorithm: HashingAlgorithm
        ): JceKeyAgreeRecipientInfoGenerator {
            val ecdhAlgorithm = ecdhAlgorithmByHashingAlgorithm[hashingAlgorithm]
            val keyWrapCipher = KEY_WRAP_ALGORITHMS[symmetricEncryptionAlgorithm]
            return JceKeyAgreeRecipientInfoGenerator(
                ecdhAlgorithm,
                originatorKeyPair.private,
                originatorKeyPair.public,
                keyWrapCipher
            ).setProvider(BC_PROVIDER)
        }
    }

    override fun getRecipientKeyId(): RecipientIdentifier {
        val rid = bcEnvelopedData.recipientInfos.first().rid as KeyAgreeRecipientId
        return if (rid.serialNumber == null) {
            RecipientKeyIdentifier(rid.subjectKeyIdentifier)
        } else {
            RecipientSerialNumber(rid.serialNumber)
        }
    }

    override fun validate() {
        // No validation needed.
    }
}

internal fun bcEncrypt(
    plaintext: ByteArray,
    symmetricEncryptionAlgorithm: SymmetricEncryption,
    recipientInfoGenerator: RecipientInfoGenerator
): CMSEnvelopedData {
    // We'd ideally take the plaintext as an InputStream but the Bouncy Castle class
    // CMSProcessableInputStream doesn't seem to be accessible here
    val cmsEnvelopedDataGenerator = CMSEnvelopedDataGenerator()

    cmsEnvelopedDataGenerator.addRecipientInfoGenerator(recipientInfoGenerator)

    val msg = CMSProcessableByteArray(plaintext)
    val contentEncryptionAlgorithm =
        cmsContentEncryptionAlgorithm[symmetricEncryptionAlgorithm]
    val encryptorBuilder =
        JceCMSContentEncryptorBuilder(contentEncryptionAlgorithm).setProvider(BC_PROVIDER)
    return cmsEnvelopedDataGenerator.generate(msg, encryptorBuilder.build())
}
