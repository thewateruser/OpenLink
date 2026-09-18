package com.openlink.child.enforcement

import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.ScheduleEntity
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-window allow-list: "downtime from 22:00 to 07:00, but the phone and messages still
 * work".
 *
 * Downtime is the one rule a family actually feels at 3am, and the exemption is what makes it
 * safe to turn on at all -- a child who cannot dial a phone overnight is a worse outcome than
 * one who stays up late. So the boundaries are pinned here rather than left to be discovered on
 * a real device in the middle of the night.
 *
 * Every test pins an explicit clock. `Calendar.getInstance()` would make these pass or fail
 * depending on what time CI happens to run.
 */
class DowntimeExemptionTest {

    private val phone = "com.android.dialer"
    private val messages = "com.google.android.apps.messaging"
    private val game = "com.example.game"

    /** Wednesday, so a weekday-only window is in force. */
    private fun clockAt(hour: Int, minute: Int = 0, dayOfWeek: Int = Calendar.WEDNESDAY): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    /** 22:00-07:00 every day -- the bedtime case, which wraps past midnight. */
    private fun bedtime(exempt: List<String> = emptyList()) = ScheduleEntity(
        id = 1,
        daysOfWeek = 0b1111111,
        startMinute = 22 * 60,
        endMinute = 7 * 60,
        label = "Bedtime",
        exemptPackages = exempt
    )

    // MARK: - The feature

    @Test
    fun `an exempt app is allowed during downtime`() {
        val schedule = listOf(bedtime(exempt = listOf(phone, messages)))

        assertFalse(EnforcementEngine.isBlockedByDowntime(phone, schedule, clockAt(23)))
        assertFalse(EnforcementEngine.isBlockedByDowntime(messages, schedule, clockAt(3)))
    }

    @Test
    fun `an app that is not exempt is still blocked`() {
        val schedule = listOf(bedtime(exempt = listOf(phone)))
        assertTrue(EnforcementEngine.isBlockedByDowntime(game, schedule, clockAt(23)))
    }

    /** The exemption must not leak outside the window it belongs to. */
    @Test
    fun `an exemption changes nothing outside downtime`() {
        val schedule = listOf(bedtime(exempt = listOf(phone)))
        assertFalse(EnforcementEngine.isBlockedByDowntime(phone, schedule, clockAt(12)))
        assertFalse(EnforcementEngine.isBlockedByDowntime(game, schedule, clockAt(12)))
    }

    @Test
    fun `a window with no exemptions blocks everything, as before`() {
        val schedule = listOf(bedtime())
        assertTrue(EnforcementEngine.isBlockedByDowntime(phone, schedule, clockAt(23)))
        assertTrue(EnforcementEngine.isBlockedByDowntime(game, schedule, clockAt(23)))
    }

    // MARK: - Overlapping windows

    /**
     * Where windows overlap, the strictest wins. Someone who adds a second, tighter window is
     * tightening the rules; a permissive window must not punch a hole in it by accident.
     */
    @Test
    fun `an app must be exempt from every active window to get through`() {
        val homework = ScheduleEntity(
            id = 2,
            daysOfWeek = 0b1111111,
            startMinute = 22 * 60,
            endMinute = 23 * 60,
            label = "No screens before bed",
            exemptPackages = emptyList()
        )
        val schedule = listOf(bedtime(exempt = listOf(phone)), homework)

        // 22:30: both windows are live, and only one exempts the phone.
        assertTrue(EnforcementEngine.isBlockedByDowntime(phone, schedule, clockAt(22, 30)))
        // 23:30: only bedtime is live, so its exemption applies.
        assertFalse(EnforcementEngine.isBlockedByDowntime(phone, schedule, clockAt(23, 30)))
    }

    // MARK: - Interaction with the other rules

    /**
     * An exemption is from downtime, not from the parent's other decisions: a hard block is a
     * deliberate act and outranks it.
     */
    @Test
    fun `a hard blocked app stays blocked even when exempt from downtime`() {
        val decision = EnforcementEngine.evaluate(
            packageName = game,
            schedule = listOf(bedtime(exempt = listOf(game))),
            policy = PolicyEntity(packageName = game, dailyLimitMinutes = null, blocked = true),
            state = AppEnforcementState(game, baseLimitMinutes = null, grantedExtraMinutes = 0, minutesUsedToday = 0),
            alwaysAllowed = false,
            now = clockAt(23)
        )
        assertEquals(EnforcementDecision.Blocked(BlockReason.HARD_BLOCKED), decision)
    }

    /**
     * Being let through bedtime is not a licence to use an app all night: an exempt app still
     * spends its daily limit.
     */
    @Test
    fun `an exempt app still runs out of its daily limit`() {
        val schedule = listOf(bedtime(exempt = listOf(messages)))
        val policy = PolicyEntity(packageName = messages, dailyLimitMinutes = 30, blocked = false)

        val withinLimit = EnforcementEngine.evaluate(
            packageName = messages,
            schedule = schedule,
            policy = policy,
            state = AppEnforcementState(messages, baseLimitMinutes = 30, grantedExtraMinutes = 0, minutesUsedToday = 10),
            alwaysAllowed = false,
            now = clockAt(23)
        )
        assertEquals(EnforcementDecision.Allowed, withinLimit)

        val spent = EnforcementEngine.evaluate(
            packageName = messages,
            schedule = schedule,
            policy = policy,
            state = AppEnforcementState(messages, baseLimitMinutes = 30, grantedExtraMinutes = 0, minutesUsedToday = 30),
            alwaysAllowed = false,
            now = clockAt(23)
        )
        assertEquals(EnforcementDecision.Blocked(BlockReason.LIMIT_REACHED), spent)
    }

    @Test
    fun `an exempt app with no limit is simply allowed during downtime`() {
        val decision = EnforcementEngine.evaluate(
            packageName = phone,
            schedule = listOf(bedtime(exempt = listOf(phone))),
            policy = null,
            state = AppEnforcementState(phone, baseLimitMinutes = null, grantedExtraMinutes = 0, minutesUsedToday = 0),
            alwaysAllowed = false,
            now = clockAt(2)
        )
        assertEquals(EnforcementDecision.Allowed, decision)
    }

    // MARK: - Window matching, which the exemption logic now depends on

    @Test
    fun `a wrapping window is active on both sides of midnight`() {
        val schedule = listOf(bedtime())
        assertTrue(EnforcementEngine.isWithinDowntime(schedule, clockAt(22, 1)))
        assertTrue(EnforcementEngine.isWithinDowntime(schedule, clockAt(6, 59)))
        assertFalse(EnforcementEngine.isWithinDowntime(schedule, clockAt(7, 0)))
        assertFalse(EnforcementEngine.isWithinDowntime(schedule, clockAt(21, 59)))
    }

    @Test
    fun `a window only fires on the days its bitmask names`() {
        val weekdaysOnly = bedtime().copy(daysOfWeek = 0b0111110) // Mon-Fri
        assertTrue(EnforcementEngine.isWithinDowntime(listOf(weekdaysOnly), clockAt(23, 0, Calendar.WEDNESDAY)))
        assertFalse(EnforcementEngine.isWithinDowntime(listOf(weekdaysOnly), clockAt(23, 0, Calendar.SATURDAY)))
    }
}
