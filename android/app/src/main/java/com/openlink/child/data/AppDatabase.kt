package com.openlink.child.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 drops the server-shaped columns from v1 (`policies.appName`) and adds the fields the
 * child now owns because nothing upstream supplies them any more (`schedule_windows.label`,
 * `time_requests.appName` / `responseNote`, a non-null `time_requests.createdAt`).
 *
 * Version 3 adds `schedule_windows.exemptPackages`, the per-window allow-list.
 */
@Database(
    entities = [UsageEntity::class, PolicyEntity::class, ScheduleEntity::class, TimeRequestEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(PackageListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun policyDao(): PolicyDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun requestDao(): TimeRequestDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds the per-window allow-list, backfilling every existing window with "no exemptions"
         * -- which is exactly the behaviour those windows had before the column existed, so an
         * upgrade changes nothing about how the device already enforces downtime.
         *
         * The `DEFAULT ''` is required twice over: SQLite cannot add a NOT NULL column without
         * one, and Room compares it against the entity's `@ColumnInfo(defaultValue = "")` when
         * it validates the migrated schema.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE schedule_windows ADD COLUMN exemptPackages TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openlink.db"
                )
                    // A real migration, as the v2 comment said the next schema change would
                    // need: this database is the source of truth, so dropping it would throw
                    // away every limit, schedule and usage tally the family has set up.
                    .addMigrations(MIGRATION_2_3)
                    // Still a destructive fallback for v1, which belongs to the deleted
                    // server-based architecture and carries nothing worth keeping.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
