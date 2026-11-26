package io.github.albanpixel.tuya.protocol

/**
 * A Tuya LAN message. [payload] is the plaintext (usually JSON) without the
 * protocol version header. [returnCode] is only present on device-to-client frames.
 */
data class TuyaMessage(
    val seq: Int,
    val command: Command,
    val payload: ByteArray,
    val returnCode: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TuyaMessage &&
            seq == other.seq &&
            command == other.command &&
            payload.contentEquals(other.payload) &&
            returnCode == other.returnCode

    override fun hashCode(): Int {
        var result = seq
        result = 31 * result + command.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (returnCode ?: 0)
        return result
    }
}
