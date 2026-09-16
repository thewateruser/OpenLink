package com.openlink.child.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 drops the server-shaped columns from v1 (`policies.appName`) and adds the fields the
 * child now owns because nothing upstream supplies them any more (`schedule_windows.label`,
 * `time_requests.appName` / `responseNote`, a non-null `time_requests.createdAt`).
 */
@Database(
    entities = [UsageEntity::class, PolicyEntity::class, ScheduleEntity::class, TimeRequestEntity::class],
    version = 2,
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
                    // This database is now the source of truth, not a cache, so destructive
                    // migration is a real data-loss event rather than a resync -- it is
                    // tolerable only because v1 databases belong to the deleted server-based
                    // architecture and carry nothing worth keeping. The next schema change
                    // needs a real Migration. See android/README.md.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
