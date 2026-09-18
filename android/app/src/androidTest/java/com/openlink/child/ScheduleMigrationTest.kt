package com.openlink.child

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlink.child.data.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades a real version-2 database to version 3 and checks nothing is lost.
 *
 * This database is not a cache -- it *is* the policy. Every limit, schedule, usage tally and
 * pending request a family has set up lives only here. A migration that Room rejects throws on
 * the first `getInstance()`, which happens while the app is starting, so the failure mode is not
 * "the new field doesn't work" but "the app crashes on launch and the settings are gone".
 *
 * Room validates the migrated schema against the entities, including column defaults, so the
 * `DEFAULT ''` in MIGRATION_2_3 and the `@ColumnInfo(defaultValue = "")` on ScheduleEntity have
 * to agree exactly. That mismatch is invisible to the compiler and only appears on a device that
 * already had data -- which is to say, never in CI unless something like this exists, and always
 * on the phone of whoever has been using the app longest.
 *
 * The v2 schema below is written out by hand rather than exported, so it is a restatement of
 * what v2 was. If Room's validation starts failing here after an entity change, suspect this
 * file as readily as the migration.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleMigrationTest {

    private val databaseName = "migration-v2-to-v3-test.db"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun removeAnyLeftovers() = deleteDatabaseFiles()

    @After
    fun cleanUp() = deleteDatabaseFiles()

    @Test
    fun aVersion2DatabaseUpgradesAndKeepsItsData() {
        createVersion2Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()

        try {
            // Opening is lazy; this is what actually runs the migration and Room's schema
            // validation, and what would throw on a device mid-launch.
            val windows = runBlocking { database.scheduleDao().getAllOnce() }

            assertEquals("The existing downtime window did not survive the upgrade", 1, windows.size)
            val window = windows.first()
            assertEquals(0b1111111, window.daysOfWeek)
            assertEquals(22 * 60, window.startMinute)
            assertEquals(7 * 60, window.endMinute)
            assertEquals("Bedtime", window.label)
            // The point of the backfill: an existing window keeps blocking everything, which is
            // precisely what it did before the column existed.
            assertEquals(emptyList<String>(), window.exemptPackages)

            val policies = runBlocking { database.policyDao().getAllOnce() }
            assertEquals("A policy was lost in the upgrade", 1, policies.size)
            assertEquals(45, policies.first().dailyLimitMinutes)
        } finally {
            database.close()
        }
    }

    /** And the new column is writable afterwards, not merely present. */
    @Test
    fun exemptionsCanBeSavedAfterTheUpgrade() {
        createVersion2Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()

        try {
            val saved = runBlocking {
                val existing = database.scheduleDao().getAllOnce().first()
                database.scheduleDao().replaceAll(
                    listOf(existing.copy(exemptPackages = listOf("com.android.dialer", "com.example.sms")))
                )
                database.scheduleDao().getAllOnce().single()
            }
            assertEquals(listOf("com.android.dialer", "com.example.sms"), saved.exemptPackages)
        } finally {
            database.close()
        }
    }

    // MARK: - A hand-built v2 database

    private fun createVersion2Database() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `usage` (
                    `packageName` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    `minutesUsed` INTEGER NOT NULL,
                    `lastUpdated` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`, `date`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `policies` (
                    `packageName` TEXT NOT NULL,
                    `dailyLimitMinutes` INTEGER,
                    `blocked` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `schedule_windows` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `daysOfWeek` INTEGER NOT NULL,
                    `startMinute` INTEGER NOT NULL,
                    `endMinute` INTEGER NOT NULL,
                    `label` TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `time_requests` (
                    `id` TEXT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT,
                    `minutesRequested` INTEGER NOT NULL,
                    `message` TEXT,
                    `status` TEXT NOT NULL,
                    `grantedMinutes` INTEGER,
                    `responseNote` TEXT,
                    `createdAt` TEXT NOT NULL,
                    `respondedAt` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                "INSERT INTO schedule_windows (daysOfWeek, startMinute, endMinute, label) " +
                    "VALUES (127, ${22 * 60}, ${7 * 60}, 'Bedtime')"
            )
            db.execSQL(
                "INSERT INTO policies (packageName, dailyLimitMinutes, blocked) " +
                    "VALUES ('com.example.game', 45, 0)"
            )

            db.version = 2
        } finally {
            db.close()
        }

        assertTrue("The v2 fixture database was not created", file.exists())
    }

    private fun deleteDatabaseFiles() {
        val file = context.getDatabasePath(databaseName)
        // SQLite keeps -wal and -shm beside the database; leaving them behind would let one run
        // contaminate the next.
        for (suffix in listOf("", "-wal", "-shm")) {
            File(file.path + suffix).takeIf { it.exists() }?.delete()
        }
    }
}
