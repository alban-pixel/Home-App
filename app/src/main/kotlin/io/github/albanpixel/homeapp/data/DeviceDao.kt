package io.github.albanpixel.homeapp.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY id")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("UPDATE devices SET onCodeId = :codeId WHERE id = :deviceId")
    suspend fun setOnCode(deviceId: Long, codeId: Long)

    @Query("UPDATE devices SET offCodeId = :codeId WHERE id = :deviceId")
    suspend fun setOffCode(deviceId: Long, codeId: Long)

    @Query("UPDATE devices SET isOn = :isOn WHERE id = :deviceId")
    suspend fun setOn(deviceId: Long, isOn: Boolean)
}
