package io.github.albanpixel.homeapp.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: DeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.SeedCallback)
            .build()
        repository = DeviceRepository(db.deviceDao(), db.irCodeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `seed creates lamp and projector`() = runTest {
        val devices = repository.devices().first()

        assertEquals(listOf("Lampe", "Projecteur"), devices.map { it.name })
        assertEquals(listOf(DeviceKind.LAMP, DeviceKind.PROJECTOR), devices.map { it.kind })
        assertEquals(listOf("lightbulb", "videocam"), devices.map { it.icon })
        assertTrue(
            devices.all { !it.isOn && it.onCodeId == null && it.offCodeId == null && it.toggleCodeId == null },
        )
    }

    @Test
    fun `setPowerCode then code returns payload`() = runTest {
        val lamp = repository.devices().first().first { it.kind == DeviceKind.LAMP }

        val onId = repository.addCode(lamp.id, "Allumer", "payload-on")
        val offId = repository.addCode(lamp.id, "Eteindre", "payload-off")
        repository.setPowerCode(lamp.id, on = true, codeId = onId)
        repository.setPowerCode(lamp.id, on = false, codeId = offId)

        val updated = repository.devices().first().first { it.id == lamp.id }
        assertEquals(onId, updated.onCodeId)
        assertEquals(offId, updated.offCodeId)
        val code = repository.code(onId)
        assertNotNull(code)
        assertEquals("payload-on", code!!.payload)
        assertEquals(lamp.id, code.deviceId)
        assertNull(repository.code(9999))
    }

    @Test
    fun `setOn updates flow`() = runTest {
        val projector = repository.devices().first().first { it.kind == DeviceKind.PROJECTOR }
        assertFalse(projector.isOn)

        repository.setOn(projector.id, true)

        assertTrue(repository.devices().first().first { it.id == projector.id }.isOn)
    }
}
