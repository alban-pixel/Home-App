package io.github.albanpixel.tuya.protocol

/** Tuya LAN command codes (the `cmd` field of a frame). */
enum class Command(val code: Int) {
    /** UDP discovery broadcast (ports 6666/6667). */
    UDP(0),
    SESS_KEY_NEG_START(3),
    SESS_KEY_NEG_RESP(4),
    SESS_KEY_NEG_FINISH(5),
    CONTROL(7),
    STATUS(8),
    HEART_BEAT(9),
    DP_QUERY(10),
    CONTROL_NEW(13),
    DP_QUERY_NEW(16),
    /** UDP discovery broadcast (port 7000, 3.5 frames). */
    UDP_NEW(19),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): Command? = byCode[code]
    }
}
