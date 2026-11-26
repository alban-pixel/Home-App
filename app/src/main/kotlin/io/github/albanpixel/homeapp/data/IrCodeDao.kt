package io.github.albanpixel.homeapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface IrCodeDao {
    @Insert
    suspend fun insert(code: IrCodeEntity): Long

    @Query("SELECT * FROM ir_codes WHERE id = :id")
    suspend fun byId(id: Long): IrCodeEntity?
}
