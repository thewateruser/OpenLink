package com.openlink.child.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UsageEntity::class, PolicyEntity::class, ScheduleEntity::class, TimeRequestEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun policyDao(): PolicyDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun requestDao(): TimeRequestDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openlink.db"
                )
                    // MVP: schema is small and versioned at 1; a real release needs real
                    // migrations instead of this.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
