package io.github.albanpixel.tuya.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MessageCodecTest {

    data class Fixture(
        val name: String,
        val version: ProtocolVersion,
        val seq: Int,
        val cmd: Int,
        val key: ByteArray,
        val payload: String,
        val bytes: ByteArray,
        val retcode: Int?,
        val iv: ByteArray?,
    ) {
        override fun toString() = name
        val message get() = TuyaMessage(seq, Command.fromCode(cmd)!!, payload.toByteArray(), retcode)
        fun codec() = MessageCodec(version, ivProvider = { iv ?: error("no IV in fixture") })
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `encode matches tinytuya bytes for every fixture`(f: Fixture) {
        assertEquals(f.bytes.hex(), f.codec().encode(f.message, f.key).hex())
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `decode roundtrips every fixture`(f: Fixture) {
        val decoded = f.codec().decode(f.bytes, f.key)
        assertEquals(f.seq, decoded.seq)
        assertEquals(f.cmd, decoded.command.code)
        assertEquals(f.retcode, decoded.returnCode)
        assertEquals(f.payload, decoded.payload.decodeToString())
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `frameLength reads the total frame size from the header`(f: Fixture) {
        val codec = f.codec()
        assertEquals(f.bytes.size, codec.frameLength(f.bytes.copyOf(codec.headerLength)))
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `decode with wrong key throws DecryptionException`(f: Fixture) {
        val wrongKey = "fedcba9876543210".toByteArray()
        assertFalse(wrongKey.contentEquals(f.key))
        assertThrows<DecryptionException> { f.codec().decode(f.bytes, wrongKey) }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `decode truncated frame throws MalformedMessageException`(f: Fixture) {
        val codec = f.codec()
        assertThrows<MalformedMessageException> { codec.decode(f.bytes.copyOf(f.bytes.size - 1), f.key) }
        assertThrows<MalformedMessageException> { codec.decode(f.bytes.copyOf(10), f.key) }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `decode corrupted suffix throws MalformedMessageException`(f: Fixture) {
        val corrupted = f.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows<MalformedMessageException> { f.codec().decode(corrupted, f.key) }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `decode wrong version prefix throws MalformedMessageException`(f: Fixture) {
        val other = if (f.version == ProtocolVersion.V3_5) ProtocolVersion.V3_4 else ProtocolVersion.V3_5
        assertThrows<MalformedMessageException> { MessageCodec(other).decode(f.bytes, f.key) }
    }

    companion object {
        @JvmStatic
        fun fixtures(): List<Fixture> {
            val text = checkNotNull(MessageCodecTest::class.java.getResource("/fixtures/messages.json")).readText()
            return Json.parseToJsonElement(text).jsonArray.map { e ->
                val o = e.jsonObject
                fun s(k: String) = o.getValue(k).jsonPrimitive.content
                Fixture(
                    name = s("name"),
                    version = ProtocolVersion.entries.single { it.label == s("version") },
                    seq = o.getValue("seq").jsonPrimitive.int,
                    cmd = o.getValue("cmd").jsonPrimitive.int,
                    key = s("key_hex").unhex(),
                    payload = s("payload_utf8"),
                    bytes = s("bytes_hex").unhex(),
                    retcode = o["retcode"]?.jsonPrimitive?.intOrNull,
                    iv = o["iv_hex"]?.jsonPrimitive?.content?.unhex(),
                )
            }.also { check(it.size >= 9) }
        }

        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
