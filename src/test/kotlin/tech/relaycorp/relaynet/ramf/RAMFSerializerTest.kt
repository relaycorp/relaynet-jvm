package tech.relaycorp.relaynet.ramf

import com.beanit.jasn1.ber.BerLength
import com.beanit.jasn1.ber.BerTag
import com.beanit.jasn1.ber.ReverseByteArrayOutputStream
import com.beanit.jasn1.ber.types.BerDateTime
import com.beanit.jasn1.ber.types.BerGeneralizedTime
import com.beanit.jasn1.ber.types.BerInteger
import com.beanit.jasn1.ber.types.BerOctetString
import com.beanit.jasn1.ber.types.BerType
import com.beanit.jasn1.ber.types.string.BerVisibleString
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERGeneralizedTime
import org.bouncycastle.asn1.DERVisibleString
import org.bouncycastle.asn1.DLTaggedObject
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.cms.SignedDataException
import tech.relaycorp.relaynet.cms.sign
import tech.relaycorp.relaynet.cms.verifySignature
import tech.relaycorp.relaynet.issueStubCertificate
import tech.relaycorp.relaynet.parseDer
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair

private val BER_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

// Pick timezone that's never equivalent to UTC (unlike "Europe/London")
val NON_UTC_ZONE_ID: ZoneId = ZoneId.of("America/Caracas")

class RAMFSerializerTest {
    private val stubCaSenderKeyPair = generateRSAKeyPair()
    private val stubCaCertificate = issueStubCertificate(
        stubCaSenderKeyPair.public,
        stubCaSenderKeyPair.private,
        isCA = true
    )
    val stubSenderCertificateChain = setOf(stubCaCertificate)

    private val stubSenderKeyPair = generateRSAKeyPair()
    private val stubSenderCertificate = issueStubCertificate(
        stubSenderKeyPair.public,
        stubCaSenderKeyPair.private,
        stubCaCertificate
    )

    private val stubMessage = StubRAMFMessage(
        "04334",
        "message-id",
        ZonedDateTime.now(ZoneId.of("UTC")),
        12345,
        "payload".toByteArray(),
        stubSenderCertificate,
        stubSenderCertificateChain
    )

    private val stubSerialization = STUB_SERIALIZER.serialize(
        stubMessage,
        stubSenderKeyPair.private
    )

    @Nested
    inner class Serialize {
        @Test
        fun `Magic constant should be ASCII string Relaynet`() {
            val magicSignature = stubSerialization.copyOfRange(0, 8)
            assertEquals("Relaynet", magicSignature.toString(Charset.forName("ASCII")))
        }

        @Test
        fun `Concrete message type should be set`() {
            assertEquals(STUB_SERIALIZER.concreteMessageType, stubSerialization[8])
        }

        @Test
        fun `Concrete message version should be set`() {
            assertEquals(STUB_SERIALIZER.concreteMessageVersion, stubSerialization[9])
        }

        @Nested
        inner class SignedData {
            @Test
            fun `Message fields should be wrapped in a CMS SignedData value`() {
                val cmsSignedDataSerialized = skipFormatSignature(stubSerialization)

                verifySignature(cmsSignedDataSerialized)
            }

            @Test
            fun `Sender certificate should be attached`() {
                val cmsSignedDataSerialized = skipFormatSignature(stubSerialization)

                val signerCertificate = verifySignature(cmsSignedDataSerialized).signerCertificate
                assertEquals(stubSenderCertificate, signerCertificate)
            }

            @Test
            fun `Sender certificate chain should be attached`() {
                val cmsSignedDataSerialized = skipFormatSignature(stubSerialization)

                val attachedCertificates =
                    verifySignature(cmsSignedDataSerialized).attachedCertificates
                assertEquals(
                    stubSenderCertificateChain.union(setOf(stubSenderCertificate)),
                    attachedCertificates
                )
            }
        }

        @Nested
        inner class FieldSet {
            @Test
            fun `Recipient should be stored as an ASN1 VisibleString`() {
                val sequence = getFieldSequence(stubSerialization)
                val recipientRaw = sequence.getObjectAt(0) as DLTaggedObject
                val recipientDer = DERVisibleString.getInstance(recipientRaw, false)
                assertEquals(stubMessage.recipientAddress, recipientDer.string)
            }

            @Test
            fun `Message id should be stored as an ASN1 VisibleString`() {
                val sequence = getFieldSequence(stubSerialization)
                val messageIdRaw = sequence.getObjectAt(1) as DLTaggedObject
                val messageIdDer = DERVisibleString.getInstance(messageIdRaw, false)
                assertEquals(stubMessage.messageId, messageIdDer.string)
            }

            @Nested
            inner class CreationTime {
                @Test
                fun `Creation time should be stored as an ASN1 DateTime`() {
                    val sequence = getFieldSequence(stubSerialization)
                    val creationTimeRaw = sequence.getObjectAt(2) as DLTaggedObject
                    // We should technically be using a DateTime type instead of GeneralizedTime, but BC
                    // doesn't support it.
                    val creationTimeDer = DERGeneralizedTime.getInstance(creationTimeRaw, false)
                    assertEquals(
                        stubMessage.creationTime.format(BER_DATETIME_FORMATTER),
                        creationTimeDer.timeString
                    )
                }

                @Test
                fun `Creation time should be converted to UTC when provided in different timezone`() {
                    val nowTimezoneUnaware = LocalDateTime.now()
                    val message = StubRAMFMessage(
                        stubMessage.recipientAddress,
                        stubMessage.messageId,
                        ZonedDateTime.of(nowTimezoneUnaware, NON_UTC_ZONE_ID),
                        stubMessage.ttl,
                        stubMessage.payload,
                        stubSenderCertificate,
                        stubSenderCertificateChain
                    )
                    val messageSerialized = STUB_SERIALIZER.serialize(
                        message,
                        stubSenderKeyPair.private
                    )

                    val sequence = getFieldSequence(messageSerialized)

                    val creationTimeRaw = sequence.getObjectAt(2) as DLTaggedObject
                    // We should technically be using a DateTime type instead of GeneralizedTime, but BC
                    // doesn't support it.
                    val creationTimeDer = DERGeneralizedTime.getInstance(creationTimeRaw, false)
                    assertEquals(
                        message.creationTime.withZoneSameInstant(ZoneId.of("UTC"))
                            .format(BER_DATETIME_FORMATTER),
                        creationTimeDer.timeString
                    )
                }
            }

            @Test
            fun `TTL should be stored as an ASN1 Integer`() {
                val sequence = getFieldSequence(stubSerialization)
                val ttlRaw = sequence.getObjectAt(3) as DLTaggedObject
                val ttlDer = ASN1Integer.getInstance(ttlRaw, false)
                assertEquals(stubMessage.ttl, ttlDer.intPositiveValueExact())
            }

            @Test
            fun `Payload should be stored as an ASN1 Octet String`() {
                val sequence = getFieldSequence(stubSerialization)
                val payloadRaw = sequence.getObjectAt(4) as DLTaggedObject
                val payloadDer = ASN1OctetString.getInstance(payloadRaw, false)
                assertEquals(stubMessage.payload.asList(), payloadDer.octets.asList())
            }

            private fun getFieldSequence(serialization: ByteArray): ASN1Sequence {
                val signedData = skipFormatSignature(serialization)
                val asn1Serialization = verifySignature(signedData).plaintext
                return ASN1Sequence.getInstance(parseDer(asn1Serialization))
            }
        }
    }

    @Nested
    inner class Deserialize {
        private val octetsIn9Mib = 9437184

        @Test
        fun `Messages up to 9 MiB should be accepted`() {
            val invalidSerialization = "a".repeat(octetsIn9Mib).toByteArray()

            // Deserialization still fails, but for a different reason
            val exception = assertThrows<RAMFException> {
                STUB_SERIALIZER.deserialize(invalidSerialization, ::StubRAMFMessage)
            }
            assertEquals(
                "Format signature should start with magic constant 'Relaynet'",
                exception.message
            )
        }

        @Test
        fun `Messages larger than 9 MiB should be refused`() {
            val invalidSerialization = "a".repeat(octetsIn9Mib + 1).toByteArray()

            val exception =
                assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization,
                        ::StubRAMFMessage
                    )
                }

            assertEquals("Message should not be larger than 9 MiB", exception.message)
        }

        @Nested
        inner class FormatSignature {
            @Test
            fun `Format signature must be present`() {
                val formatSignatureLength = 10
                val invalidSerialization = "a".repeat(formatSignatureLength - 1).toByteArray()

                val exception =
                    assertThrows<RAMFException> {
                        STUB_SERIALIZER.deserialize(
                            invalidSerialization,
                            ::StubRAMFMessage
                        )
                    }

                assertEquals(
                    "Serialization is too short to contain format signature",
                    exception.message
                )
            }

            @Test
            fun `Magic constant should be ASCII string Relaynet`() {
                val incompleteSerialization = "Relaynope01234".toByteArray()

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        incompleteSerialization,
                        ::StubRAMFMessage
                    )
                }

                assertEquals(
                    "Format signature should start with magic constant 'Relaynet'",
                    exception.message
                )
            }

            @Test
            fun `Concrete message type should match expected one`() {
                val invalidMessageType = STUB_SERIALIZER.concreteMessageType.inc()
                val invalidSerialization = ByteArrayOutputStream(10)
                invalidSerialization.write("Relaynet".toByteArray())
                invalidSerialization.write(invalidMessageType.toInt())
                invalidSerialization.write(STUB_SERIALIZER.concreteMessageVersion.toInt())

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals(
                    "Message type should be ${STUB_SERIALIZER.concreteMessageType} (got $invalidMessageType)",
                    exception.message
                )
            }

            @Test
            fun `Concrete message version should match expected one`() {
                val invalidMessageVersion = STUB_SERIALIZER.concreteMessageVersion.inc()
                val invalidSerialization = ByteArrayOutputStream(10)
                invalidSerialization.write("Relaynet".toByteArray())
                invalidSerialization.write(STUB_SERIALIZER.concreteMessageType.toInt())
                invalidSerialization.write(invalidMessageVersion.toInt())

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals(
                    "Message version should be ${STUB_SERIALIZER.concreteMessageVersion} (got $invalidMessageVersion)",
                    exception.message
                )
            }
        }

        @Nested
        inner class SignedData {
            @Test
            fun `Invalid signature should be refused`() {
                val invalidSerialization = ByteArrayOutputStream()
                invalidSerialization.write("Relaynet".toByteArray())
                invalidSerialization.write(STUB_SERIALIZER.concreteMessageType.toInt())
                invalidSerialization.write(STUB_SERIALIZER.concreteMessageVersion.toInt())
                invalidSerialization.write("Not really CMS SignedData".toByteArray())

                val exception = assertThrows<SignedDataException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals("Value is not DER-encoded", exception.message)
            }

            @Test
            fun `Message should take sender certificate from valid SignedData value`() {
                val messageDeserialized =
                    STUB_SERIALIZER.deserialize(stubSerialization, ::StubRAMFMessage)

                assertEquals(stubSenderCertificate, messageDeserialized.senderCertificate)
            }

            @Test
            fun `Message should take sender certificate chain from valid SignedData value`() {
                val messageDeserialized =
                    STUB_SERIALIZER.deserialize(stubSerialization, ::StubRAMFMessage)

                assertEquals(
                    stubSenderCertificateChain.union(setOf(stubSenderCertificate)),
                    messageDeserialized.senderCertificateChain
                )
            }
        }

        @Nested
        inner class FieldSet {
            private val formatSignature: ByteArray = "Relaynet".toByteArray() + byteArrayOf(
                STUB_SERIALIZER.concreteMessageType,
                STUB_SERIALIZER.concreteMessageVersion
            )

            @Test
            fun `Fields should be DER-serialized`() {
                val invalidSerialization = ByteArrayOutputStream(11)
                invalidSerialization.write(formatSignature)
                invalidSerialization.write(
                    sign(
                        "not DER".toByteArray(),
                        stubSenderKeyPair.private,
                        stubSenderCertificate
                    )
                )

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals("Message fields are not a DER-encoded", exception.message)
            }

            @Test
            fun `Fields should be stored as a universal, constructed sequence`() {
                val invalidSerialization = ByteArrayOutputStream(11)
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization = ReverseByteArrayOutputStream(100)
                BerOctetString("Not a sequence".toByteArray()).encode(fieldSetSerialization)
                invalidSerialization.write(
                    sign(
                        fieldSetSerialization.array,
                        stubSenderKeyPair.private,
                        stubSenderCertificate
                    )
                )

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals("Message fields are not a ASN.1 sequence", exception.message)
            }

            @Test
            fun `Fields should be a sequence of no more than 5 items`() {
                val invalidSerialization = ByteArrayOutputStream()
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization = serializeSequence(
                    BerVisibleString("1"),
                    BerVisibleString("2"),
                    BerVisibleString("3"),
                    BerVisibleString("4"),
                    BerVisibleString("5"),
                    BerVisibleString("6")
                )
                invalidSerialization.write(
                    sign(
                        fieldSetSerialization,
                        stubSenderKeyPair.private,
                        stubSenderCertificate
                    )
                )

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals(
                    "Field sequence should contain 5 items (got 6)",
                    exception.message
                )
            }

            @Test
            fun `Message fields should be output when the serialization is valid`() {
                val serialization = STUB_SERIALIZER.serialize(
                    stubMessage,
                    stubSenderKeyPair.private
                )

                val parsedMessage = STUB_SERIALIZER.deserialize(serialization, ::StubRAMFMessage)

                assertEquals(stubMessage.recipientAddress, parsedMessage.recipientAddress)

                assertEquals(stubMessage.messageId, parsedMessage.messageId)

                assertEquals(
                    stubMessage.creationTime.withNano(0),
                    parsedMessage.creationTime
                )

                assertEquals(stubMessage.ttl, parsedMessage.ttl)

                assertEquals(stubMessage.payload.asList(), parsedMessage.payload.asList())
            }

            @Test
            fun `Creation time in a format other than ASN1 DATE-TIME should be refused`() {
                // For example, a GeneralizedTime value (which includes timezone) should be refused
                val invalidSerialization = ByteArrayOutputStream()
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization =
                    serializeFieldSet(creationTime = BerGeneralizedTime("20200307173323-03"))
                invalidSerialization.write(
                    sign(
                        fieldSetSerialization,
                        stubSenderKeyPair.private,
                        stubSenderCertificate
                    )
                )

                val exception = assertThrows<RAMFException> {
                    STUB_SERIALIZER.deserialize(
                        invalidSerialization.toByteArray(),
                        ::StubRAMFMessage
                    )
                }

                assertEquals(
                    "Creation time should be an ASN.1 DATE-TIME value",
                    exception.message
                )
            }

            @Test
            fun `Creation time should be parsed as UTC`() {
                val message = StubRAMFMessage(
                    stubMessage.recipientAddress,
                    stubMessage.messageId,
                    stubMessage.creationTime.withZoneSameInstant(NON_UTC_ZONE_ID),
                    stubMessage.ttl,
                    stubMessage.payload,
                    stubSenderCertificate,
                    stubSenderCertificateChain
                )
                val serialization = STUB_SERIALIZER.serialize(message, stubSenderKeyPair.private)

                val parsedMessage = STUB_SERIALIZER.deserialize(serialization, ::StubRAMFMessage)

                assertEquals(parsedMessage.creationTime.zone, ZoneId.of("UTC"))
            }

            private fun serializeFieldSet(
                recipientAddress: BerType = BerVisibleString(stubMessage.recipientAddress),
                messageId: BerType = BerVisibleString(stubMessage.messageId),
                creationTime: BerType = BerDateTime(
                    stubMessage.creationTime.format(
                        BER_DATETIME_FORMATTER
                    )
                ),
                ttl: BerType = BerInteger(stubMessage.ttl.toBigInteger()),
                payload: BerType = BerOctetString(stubMessage.payload)
            ): ByteArray {
                return serializeSequence(
                    recipientAddress,
                    messageId,
                    creationTime,
                    ttl,
                    payload
                )
            }

            private fun serializeSequence(
                vararg items: BerType
            ): ByteArray {
                val reverseOS = ReverseByteArrayOutputStream(256, true)
                val lastIndex = 0x80 + items.size - 1
                val serializationLength =
                    items.reversed()
                        .mapIndexed { i, v -> serializeItem(v, reverseOS, lastIndex - i) }.sum()

                BerLength.encodeLength(reverseOS, serializationLength)
                BerTag(BerTag.UNIVERSAL_CLASS, BerTag.CONSTRUCTED, 16).encode(reverseOS)
                return reverseOS.array
            }

            private fun serializeItem(
                item: BerType,
                reverseOS: ReverseByteArrayOutputStream,
                index: Int
            ): Int {
                val length = when (item) {
                    is BerVisibleString -> item.encode(reverseOS, false)
                    is BerInteger -> item.encode(reverseOS, false)
                    is BerOctetString -> item.encode(reverseOS, false)
                    else -> throw Exception("Unsupported BER type")
                }
                reverseOS.write(index)
                return length + 1
            }
        }
    }
}

fun skipFormatSignature(ramfMessage: ByteArray): ByteArray {
    return ramfMessage.copyOfRange(10, ramfMessage.lastIndex + 1)
}