package io.github.albanpixel.homeapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DeviceKind { LAMP, PROJECTOR, OTHER }

/** A controllable device. The code ids are plain columns (no foreign key) to avoid a cycle with [IrCodeEntity]. */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Material Symbols icon name, e.g. "lightbulb". */
    val icon: String,
    val kind: DeviceKind,
    val onCodeId: Long? = null,
    val offCodeId: Long? = null,
    val toggleCodeId: Long? = null,
    val isOn: Boolean = false,
)
