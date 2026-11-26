package io.github.albanpixel.homeapp.data

import kotlinx.coroutines.flow.Flow

class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val irCodeDao: IrCodeDao,
) {
    fun devices(): Flow<List<DeviceEntity>> = deviceDao.observeAll()

    suspend fun addCode(deviceId: Long, label: String, payload: String): Long =
        irCodeDao.insert(
            IrCodeEntity(
                deviceId = deviceId,
                label = label,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun setPowerCode(deviceId: Long, on: Boolean, codeId: Long) {
        if (on) deviceDao.setOnCode(deviceId, codeId) else deviceDao.setOffCode(deviceId, codeId)
    }

    suspend fun setOn(deviceId: Long, isOn: Boolean) = deviceDao.setOn(deviceId, isOn)

    suspend fun code(id: Long): IrCodeEntity? = irCodeDao.byId(id)
}
