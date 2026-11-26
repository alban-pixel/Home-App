package io.github.albanpixel.tuya.protocol

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encodes and decodes single Tuya LAN frames, mirroring tinytuya's framing.
 *
 * - 3.3: `55AA` header, retcode (device->client), `"3.3"+12x00` (outside the ciphertext, non-query
 *   commands), AES-128-ECB/PKCS7 payload, CRC32, `AA55`.
 * - 3.4: same frame, version header encrypted with the payload, HMAC-SHA256(key) trailer.
 * - 3.5: `6699` header, IV(12), AES-128-GCM over retcode + version header + payload (AAD = the
 *   14 header bytes after the prefix), tag(16), `9966`.
 *
 * [ivProvider] supplies the 12-byte GCM nonce for 3.5; it must never repeat for a given key.
 */
class MessageCodec(
    val version: ProtocolVersion,
    private val ivProvider: () -> ByteArray = ::randomIv,
) {
    private val is6699 = version == ProtocolVersion.V3_5

    /** Number of bytes to read before [frameLength] can tell the size of the whole frame. */
    val headerLength: Int = if (is6699) HEADER_6699 else HEADER_55AA

    private val versionHeader = version.label.toByteArray() + ByteArray(12)

    fun encode(msg: TuyaMessage, key: ByteArray): ByteArray {
        val rc = msg.returnCode?.let { ByteBuffer.allocate(4).putInt(it).array() } ?: ByteArray(0)
        val vh = if (msg.command in NO_VERSION_HEADER) ByteArray(0) else versionHeader
        return when (version) {
            ProtocolVersion.V3_3 -> frame55aa(msg, rc + vh + aesEcb(Cipher.ENCRYPT_MODE, key, msg.payload)) { crc32(it) }
            ProtocolVersion.V3_4 -> frame55aa(msg, rc + aesEcb(Cipher.ENCRYPT_MODE, key, vh + msg.payload)) { hmac(key, it) }
            ProtocolVersion.V3_5 -> encode6699(msg, key, rc + vh + msg.payload)
        }
    }

    fun decode(bytes: ByteArray, key: ByteArray): TuyaMessage {
        val total = frameLength(bytes)
        if (bytes.size != total) malformed("frame is ${bytes.size} bytes, header says $total")
        val buf = ByteBuffer.wrap(bytes)
        if (buf.getInt(total - 4) != if (is6699) SUFFIX_6699 else SUFFIX_55AA) malformed("bad suffix")
        val seq = buf.getInt(if (is6699) 6 else 4)
        val code = buf.getInt(if (is6699) 10 else 8)
        val command = Command.fromCode(code) ?: malformed("unknown command $code")
        return if (is6699) decode6699(bytes, key, seq, command) else decode55aa(bytes, key, seq, command)
    }

    private fun frame55aa(msg: TuyaMessage, body: ByteArray, check: (ByteArray) -> ByteArray): ByteArray {
        val trailer = checkLength(version) + 4
        val head = ByteBuffer.allocate(HEADER_55AA + body.size)
            .putInt(PREFIX_55AA).putInt(msg.seq).putInt(msg.command.code).putInt(body.size + trailer)
            .put(body).array()
        return head + check(head) + ByteBuffer.allocate(4).putInt(SUFFIX_55AA).array()
    }

    private fun encode6699(msg: TuyaMessage, key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ivProvider()
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
        val header = ByteBuffer.allocate(HEADER_6699)
            .putInt(PREFIX_6699).putShort(0).putInt(msg.seq).putInt(msg.command.code)
            .putInt(IV_LENGTH + plain.size + TAG_LENGTH)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, iv))
        cipher.updateAAD(header, 4, HEADER_6699 - 4)
        val sealed = cipher.doFinal(plain) // ciphertext || tag
        return header + iv + sealed + ByteBuffer.allocate(4).putInt(SUFFIX_6699).array()
    }

    private fun decode55aa(bytes: ByteArray, key: ByteArray, seq: Int, command: Command): TuyaMessage {
        val checkLen = checkLength(version)
        val bodyEnd = bytes.size - checkLen - 4
        if (bodyEnd < HEADER_55AA) malformed("length too small")
        val signed = bytes.copyOfRange(0, bodyEnd)
        val check = bytes.copyOfRange(bodyEnd, bodyEnd + checkLen)
        if (version == ProtocolVersion.V3_3) {
            if (!crc32(signed).contentEquals(check)) malformed("bad CRC32")
        } else if (!MessageDigest.isEqual(hmac(key, signed), check)) {
            throw DecryptionException("bad HMAC")
        }

        var body = bytes.copyOfRange(HEADER_55AA, bodyEnd)
        // ECB ciphertext is a multiple of 16; the optional 4-byte retcode and (3.3 only) the
        // 15-byte clear version header give distinct residues, so their presence is unambiguous.
        val residue = body.size % 16
        val hasRetcode = residue == 4 || residue == 3
        val returnCode = if (hasRetcode) ByteBuffer.wrap(body).int else null
        if (hasRetcode) body = body.copyOfRange(4, body.size)
        if (body.size % 16 == 15) {
            if (version != ProtocolVersion.V3_3 || !body.startsWith(versionHeader)) malformed("bad payload length")
            body = body.copyOfRange(versionHeader.size, body.size)
        }
        if (body.size % 16 != 0) malformed("bad payload length")
        val plain = if (body.isEmpty()) body else aesEcb(Cipher.DECRYPT_MODE, key, body)
        return TuyaMessage(seq, command, stripVersionHeader(plain), returnCode)
    }

    private fun decode6699(bytes: ByteArray, key: ByteArray, seq: Int, command: Command): TuyaMessage {
        if (bytes.size < HEADER_6699 + IV_LENGTH + TAG_LENGTH + 4) malformed("length too small")
        val iv = bytes.copyOfRange(HEADER_6699, HEADER_6699 + IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, iv))
            cipher.updateAAD(bytes, 4, HEADER_6699 - 4)
            cipher.doFinal(bytes, HEADER_6699 + IV_LENGTH, bytes.size - HEADER_6699 - IV_LENGTH - 4)
        } catch (e: AEADBadTagException) {
            throw DecryptionException("bad GCM tag", e)
        } catch (e: GeneralSecurityException) {
            throw DecryptionException("cannot decrypt", e)
        }
        // Same heuristic as tinytuya: a retcode precedes the payload when it does not start
        // with JSON or the version header (only device->client frames carry one).
        val hasRetcode = plain.size >= 4 && plain[0] != '{'.code.toByte() && !plain.startsWith(versionHeader)
        val returnCode = if (hasRetcode) ByteBuffer.wrap(plain).int else null
        val payload = if (hasRetcode) plain.copyOfRange(4, plain.size) else plain
        return TuyaMessage(seq, command, stripVersionHeader(payload), returnCode)
    }

    private fun stripVersionHeader(plain: ByteArray): ByteArray =
        if (plain.startsWith(versionHeader)) plain.copyOfRange(versionHeader.size, plain.size) else plain

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    internal companion object {
        const val HEADER_55AA = 16
        const val HEADER_6699 = 18
        const val PREFIX_55AA = 0x000055AA
        const val SUFFIX_55AA = 0x0000AA55
        const val PREFIX_6699 = 0x00006699
        const val SUFFIX_6699 = 0x00009966
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16

        /** Upper bound for the header's length field, to catch a desynced stream early. */
        const val MAX_FRAME_LENGTH = 64 * 1024

        /** tinytuya's NO_PROTOCOL_HEADER_CMDS (restricted to the commands we model). */
        val NO_VERSION_HEADER = setOf(
            Command.DP_QUERY, Command.DP_QUERY_NEW, Command.HEART_BEAT,
            Command.SESS_KEY_NEG_START, Command.SESS_KEY_NEG_RESP, Command.SESS_KEY_NEG_FINISH,
        )

        fun checkLength(version: ProtocolVersion) = if (version == ProtocolVersion.V3_3) 4 else 32

        fun aesEcb(mode: Int, key: ByteArray, data: ByteArray): ByteArray = try {
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(mode, SecretKeySpec(key, "AES"))
                doFinal(data)
            }
        } catch (e: GeneralSecurityException) {
            if (mode == Cipher.DECRYPT_MODE) throw DecryptionException("cannot decrypt", e) else throw e
        }

        fun crc32(data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(data) }.value.toInt()
            return ByteBuffer.allocate(4).putInt(crc).array()
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private val random = SecureRandom()

        fun randomIv(): ByteArray = ByteArray(IV_LENGTH).also(random::nextBytes)

        fun malformed(reason: String): Nothing = throw MalformedMessageException(reason)
    }
}

/**
 * Total size in bytes of the frame starting with [header] (at least [MessageCodec.headerLength]
 * bytes), so a stream reader knows how much more to read.
 */
fun MessageCodec.frameLength(header: ByteArray): Int {
    if (header.size < headerLength) MessageCodec.malformed("need $headerLength header bytes, got ${header.size}")
    val buf = ByteBuffer.wrap(header)
    val is6699 = version == ProtocolVersion.V3_5
    val prefix = if (is6699) MessageCodec.PREFIX_6699 else MessageCodec.PREFIX_55AA
    if (buf.getInt(0) != prefix) MessageCodec.malformed("bad prefix %08x".format(buf.getInt(0)))
    val length = buf.getInt(if (is6699) 14 else 12)
    if (length < 0 || length > MessageCodec.MAX_FRAME_LENGTH) MessageCodec.malformed("bad length $length")
    return headerLength + length + if (is6699) 4 else 0
}
