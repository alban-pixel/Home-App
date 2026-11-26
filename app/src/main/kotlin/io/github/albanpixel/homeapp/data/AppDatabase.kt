package io.github.albanpixel.homeapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeviceEntity::class, IrCodeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun irCodeDao(): IrCodeDao

    /** Seeds the two default devices when the database file is first created. */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO devices (name, icon, kind, isOn) VALUES ('Lampe', 'lightbulb', 'LAMP', 0)",
            )
            db.execSQL(
                "INSERT INTO devices (name, icon, kind, isOn) VALUES ('Projecteur', 'videocam', 'PROJECTOR', 0)",
            )
        }
    }

    companion object {
        const val NAME = "home-app.db"
    }
}
