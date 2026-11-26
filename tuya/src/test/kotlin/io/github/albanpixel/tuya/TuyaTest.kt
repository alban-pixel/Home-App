package io.github.albanpixel.tuya

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TuyaTest {
    @Test
    fun `module name is tuya`() {
        assertEquals("tuya", Tuya.NAME)
    }
}
