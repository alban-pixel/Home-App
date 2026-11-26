package io.github.albanpixel.tuya

import io.github.albanpixel.tuya.protocol.DecryptionException
import io.github.albanpixel.tuya.protocol.MalformedMessageException
import io.github.albanpixel.tuya.protocol.MessageCodec
import io.github.albanpixel.tuya.protocol.ProtocolVersion
import io.github.albanpixel.tuya.protocol.frameLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A Tuya device that announced itself over UDP broadcast. */
data class DiscoveredDevice(
    val ip: String,
    val deviceId: String,
    val productKey: String?,
    val version: ProtocolVersion,
)

/**
 * Passive discovery of Tuya devices on the LAN by listening to their UDP broadcasts
 * (6666 plaintext, 6667 AES-ECB, 7000 AES-GCM), like tinytuya's scanner.
 */
object Discovery {
    /** Every UDP broadcast, whatever the port, is keyed with MD5("yGAdlopoPVldABfn"). */
    private val UDP_KEY: ByteArray = MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray())

    private val DEFAULT_PORTS = listOf(6666, 6667, 7000)
    private const val RECEIVE_BUFFER = 4096
    private const val POLL_MS = 250

    private val ecbCodec = MessageCodec(ProtocolVersion.V3_3)
    private val gcmCodec = MessageCodec(ProtocolVersion.V3_5)

    /**
     * Decodes one broadcast datagram, or returns null when it is not a usable device
     * announcement (garbage, bad checksum/tag, the app's own info request, no `gwId`, no `ip`,
     * or a protocol version other than 3.3/3.4/3.5 such as 3.1).
     *
     * The framing is detected from the frame prefix like tinytuya does (a device may announce 3.5
     * on 6667), so [port] is informational. [sourceIp] is the datagram's sender, used when the
     * JSON carries no `ip`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(packet: ByteArray, port: Int, sourceIp: String? = null): DiscoveredDevice? {
        val json = try {
            payloadOf(packet)?.let { Json.parseToJsonElement(it.trimEnd('\u0000')) as? JsonObject }
        } catch (_: IllegalArgumentException) { // malformed JSON (SerializationException) or bad UTF-8
            null
        } catch (_: IOException) { // MalformedMessageException / DecryptionException
            null
        } ?: return null

        val deviceId = json.str("gwId") ?: return null
        val ip = json.str("ip") ?: sourceIp ?: return null
        val version = ProtocolVersion.entries.firstOrNull { it.label == json.str("version") } ?: return null
        return DiscoveredDevice(ip, deviceId, json.str("productKey"), version)
    }

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** Returns the decoded JSON text of a 55AA (plaintext or ECB) or 6699 (GCM) broadcast. */
    private fun payloadOf(packet: ByteArray): String? {
        if (packet.size < 4) return null
        return when (ByteBuffer.wrap(packet).int) {
            0x000055AA -> {
                // 6666 sends the JSON in clear inside a 55AA frame; 6667 encrypts it with ECB.
                val total = ecbCodec.frameLength(packet.copyOf(minOf(packet.size, ecbCodec.headerLength)))
                if (packet.size != total) return null
                clearPayload(packet) ?: ecbCodec.decode(packet, UDP_KEY).payload.toString(Charsets.UTF_8)
            }
            0x00006699 -> gcmCodec.decode(packet, UDP_KEY).payload.toString(Charsets.UTF_8)
            else -> null
        }
    }

    /**
     * The JSON of a 55AA frame whose payload is not encrypted: header(16) + retcode(4) + `{...}` +
     * CRC32(4) + suffix(4). The CRC is verified; null when the body is not plaintext JSON.
     */
    private fun clearPayload(frame: ByteArray): String? {
        val start = 20
        val end = frame.size - 8
        if (end <= start || frame[start] != '{'.code.toByte() || frame[end - 1] != '}'.code.toByte()) return null
        if (!MessageCodec.crc32(frame.copyOf(end)).contentEquals(frame.copyOfRange(end, end + 4))) {
            throw MalformedMessageException("bad CRC32")
        }
        return String(frame, start, end - start, Charsets.UTF_8)
    }

    /**
     * Listens on UDP 6666, 6667 and 7000 for [timeoutMs] and emits each device (by id) once.
     * The flow completes after the timeout; collecting coroutine cancellation closes the sockets.
     * A port that cannot be bound (in use) is skipped; if none can be, collection fails with an
     * [IOException].
     */
    fun listen(timeoutMs: Long = 6_000): Flow<DiscoveredDevice> = listen(timeoutMs, DEFAULT_PORTS)

    internal fun listen(timeoutMs: Long, ports: List<Int>): Flow<DiscoveredDevice> = channelFlow {
        val sockets = ports.mapNotNull { port ->
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = POLL_MS
                    bind(InetSocketAddress(port))
                }
            } catch (_: IOException) {
                null
            }
        }
        try {
            if (sockets.isEmpty()) throw IOException("cannot bind any of UDP ports $ports")
            val seen = ConcurrentHashMap.newKeySet<String>()
            withTimeoutOrNull(timeoutMs) {
                sockets.map { socket ->
                    launch(Dispatchers.IO) {
                        val buffer = ByteArray(RECEIVE_BUFFER)
                        val datagram = DatagramPacket(buffer, buffer.size)
                        while (isActive) {
                            try {
                                datagram.length = buffer.size
                                socket.receive(datagram)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val data = buffer.copyOf(datagram.length)
                            val device = parse(data, socket.localPort, datagram.address?.hostAddress)
                            if (device != null && seen.add(device.deviceId)) send(device)
                        }
                    }
                }.joinAll()
            }
        } finally {
            sockets.forEach { it.close() }
        }
    }
}
