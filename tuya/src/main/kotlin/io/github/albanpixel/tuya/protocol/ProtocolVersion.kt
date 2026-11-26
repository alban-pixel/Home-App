package io.github.albanpixel.tuya.protocol

/** Tuya LAN protocol versions supported by [MessageCodec]. */
enum class ProtocolVersion(val label: String) {
    V3_3("3.3"),
    V3_4("3.4"),
    V3_5("3.5"),
}
