package io.github.albanpixel.tuya

import io.github.albanpixel.tuya.protocol.ProtocolVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryTest {

    class Fixture(val name: String, val port: Int, val packet: ByteArray, val expected: DiscoveredDevice?)

    private fun fixture(name: String) = fixtures.single { it.name == name }

    @Test
    fun `parse 6667 fixture`() {
        val f = fixture("6667 ECB 3.3")
        assertEquals(
            DiscoveredDevice("192.168.1.52", "fakedeviceid0000000000", "fakeproductkey00", ProtocolVersion.V3_3),
            Discovery.parse(f.packet, f.port),
        )
    }

    @Test
    fun `parse 7000 fixture`() {
        val f = fixture("7000 GCM 3.5")
        assertEquals(
            DiscoveredDevice("192.168.1.53", "fakedeviceid0000000000", "fakeproductkey00", ProtocolVersion.V3_5),
            Discovery.parse(f.packet, f.port),
        )
    }

    @Test
    fun `parse 6666 plaintext fixture`() {
        val f = fixture("6666 plaintext 3.3")
        assertEquals(
            DiscoveredDevice("192.168.1.51", "fakedeviceid0000000000", "fakeproductkey00", ProtocolVersion.V3_3),
            Discovery.parse(f.packet, f.port),
        )
    }

    @Test
    fun `parse returns null for unsupported protocol version 3_1`() {
        val f = fixture("6666 plaintext 3.1 (unsupported)")
        assertNull(Discovery.parse(f.packet, f.port))
    }

    @Test
    fun `parse returns null for the app's own info request on 7000`() {
        val f = fixture("7000 app request (no gwId)")
        assertNull(Discovery.parse(f.packet, f.port))
    }

    @Test
    fun `parse garbage returns null`() {
        assertNull(Discovery.parse(ByteArray(0), 6667))
        assertNull(Discovery.parse(byteArrayOf(1, 2, 3, 4, 5), 6667))
        assertNull(Discovery.parse(ByteArray(200) { it.toByte() }, 7000))
        assertNull(Discovery.parse("not json at all".toByteArray(), 6666))
        // valid frames, corrupted
        val ecb = fixture("6667 ECB 3.3").packet
        assertNull(Discovery.parse(ecb.copyOf().also { it[30] = (it[30] + 1).toByte() }, 6667))
        assertNull(Discovery.parse(ecb.copyOf(ecb.size - 3), 6667))
        val gcm = fixture("7000 GCM 3.5").packet
        assertNull(Discovery.parse(gcm.copyOf().also { it[40] = (it[40] + 1).toByte() }, 7000))
        assertNull(Discovery.parse(gcm.copyOf(gcm.size - 3), 7000))
    }

    @Test
    fun `listen emits each device once and completes after the timeout`() = runBlocking {
        val port = DatagramSocket(0).use { it.localPort }
        val packet = fixture("6667 ECB 3.3").packet
        val garbage = byteArrayOf(9, 9, 9)
        val result = async { Discovery.listen(timeoutMs = 1_500, ports = listOf(port)).toList() }
        delay(400)
        DatagramSocket().use { s ->
            val to = InetAddress.getLoopbackAddress()
            repeat(2) { s.send(DatagramPacket(packet, packet.size, to, port)) }
            s.send(DatagramPacket(garbage, garbage.size, to, port))
        }
        assertEquals(listOf(fixture("6667 ECB 3.3").expected), result.await())
    }

    companion object {
        private val fixtures: List<Fixture> by lazy {
            val text = DiscoveryTest::class.java.getResource("/fixtures/discovery.json")!!.readText()
            Json.parseToJsonElement(text).jsonArray.map { e ->
                val o = e.jsonObject
                val exp = o["expected"]!!
                Fixture(
                    name = o["name"]!!.jsonPrimitive.content,
                    port = o["port"]!!.jsonPrimitive.int,
                    packet = o["packet_hex"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    expected = if (exp is JsonNull) null else exp.jsonObject.let {
                        DiscoveredDevice(
                            it["ip"]!!.jsonPrimitive.content,
                            it["deviceId"]!!.jsonPrimitive.content,
                            it["productKey"]!!.jsonPrimitive.content,
                            ProtocolVersion.entries.single { v -> v.label == it["version"]!!.jsonPrimitive.content },
                        )
                    },
                )
            }
        }
    }
}
