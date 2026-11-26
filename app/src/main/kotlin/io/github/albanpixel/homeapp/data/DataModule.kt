package io.github.albanpixel.homeapp.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addCallback(AppDatabase.SeedCallback)
            .build()

    @Provides
    @Singleton
    fun deviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    @Singleton
    fun irCodeDao(db: AppDatabase): IrCodeDao = db.irCodeDao()

    @Provides
    @Singleton
    fun deviceRepository(deviceDao: DeviceDao, irCodeDao: IrCodeDao): DeviceRepository =
        DeviceRepository(deviceDao, irCodeDao)
}
