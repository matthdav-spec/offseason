package com.offseason.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

class SchedulerTest {

    private val buildWeek = listOf(
        Scheduler.Slot(SlotRole.INTERVALS_A, isHard = true),
        Scheduler.Slot(SlotRole.INTERVALS_B, isHard = true),
        Scheduler.Slot(SlotRole.LONG, isHard = false),
        Scheduler.Slot(SlotRole.EXTRA, isHard = false),
    )

    private fun hardDays(p: Map<SlotRole, DayOfWeek>) = listOfNotNull(p[SlotRole.INTERVALS_A], p[SlotRole.INTERVALS_B])

    @Test
    fun `adjacency wraps around the week`() {
        assertTrue(Scheduler.adjacent(SUNDAY, MONDAY))
        assertTrue(Scheduler.adjacent(TUESDAY, WEDNESDAY))
        assertFalse(Scheduler.adjacent(TUESDAY, THURSDAY))
    }

    @Test
    fun `no back-to-back hard bike days when avoidable`() {
        val schedule = AthleteSchedule(bikeDays = setOf(MONDAY, TUESDAY, WEDNESDAY, SATURDAY))
        val p = Scheduler.place(buildWeek, schedule, MONDAY)
        val (a, b) = hardDays(p)
        assertFalse(Scheduler.adjacent(a, b), "hard days $a and $b are adjacent")
    }

    @Test
    fun `hard bike kept off and away from key runs when there is room`() {
        // Hard run Tuesday, long run Sunday: Thursday and Friday are free of neighbours, but adjacent to
        // each other, so the best option is one hard day there and one next to (not on) a key run.
        val schedule = AthleteSchedule(
            bikeDays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY),
            runDays = setOf(TUESDAY, SUNDAY),
            longRunDay = SUNDAY,
            hardRunDays = setOf(TUESDAY),
        )
        val p = Scheduler.place(buildWeek, schedule, MONDAY)
        val hard = hardDays(p)
        hard.forEach { d -> assertFalse(d in schedule.hardRunOrLongRunDays, "hard bike on key run day $d") }
        assertFalse(Scheduler.adjacent(hard[0], hard[1]), "hard days back to back")
        val nextToKeyRun = hard.count { d -> schedule.hardRunOrLongRunDays.any { Scheduler.adjacent(it, d) } }
        assertTrue(nextToKeyRun <= 1, "both hard days next to key runs: $hard")
    }

    @Test
    fun `with one key run both hard days avoid it entirely`() {
        val schedule = AthleteSchedule(
            bikeDays = DayOfWeek.entries.toSet(),
            runDays = setOf(SUNDAY),
            longRunDay = SUNDAY,
        )
        val p = Scheduler.place(buildWeek, schedule, MONDAY)
        hardDays(p).forEach { d ->
            assertFalse(d == SUNDAY || Scheduler.adjacent(d, SUNDAY), "hard bike on $d")
        }
    }

    @Test
    fun `long ride prefers the weekend and avoids the long run day`() {
        val schedule = AthleteSchedule(
            bikeDays = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY),
            longRunDay = SUNDAY,
            runDays = setOf(SUNDAY),
        )
        val p = Scheduler.place(buildWeek, schedule, MONDAY)
        assertEquals(SATURDAY, p[SlotRole.LONG])
    }

    @Test
    fun `typical triathlete week places sensibly`() {
        // Swims Mon/Wed/Fri, hard run Thursday, long run Sunday, bikes Tue/Wed/Sat/Sun
        val schedule = AthleteSchedule(
            bikeDays = setOf(TUESDAY, WEDNESDAY, SATURDAY, SUNDAY),
            swimDays = setOf(MONDAY, WEDNESDAY, FRIDAY),
            runDays = setOf(THURSDAY, SUNDAY),
            longRunDay = SUNDAY,
            hardRunDays = setOf(THURSDAY),
        )
        val p = Scheduler.place(buildWeek, schedule, MONDAY)
        assertEquals(4, p.size)
        assertEquals(setOf(TUESDAY, WEDNESDAY, SATURDAY, SUNDAY), p.values.toSet())
        val (a, b) = hardDays(p)
        assertFalse(Scheduler.adjacent(a, b))
    }

    @Test
    fun `retest goes late in the week`() {
        val slots = listOf(
            Scheduler.Slot(SlotRole.INTERVALS_B, isHard = true, isTest = true),
            Scheduler.Slot(SlotRole.LONG, isHard = false),
            Scheduler.Slot(SlotRole.INTERVALS_A, isHard = false),
        )
        val schedule = AthleteSchedule(bikeDays = setOf(TUESDAY, THURSDAY, SATURDAY))
        val p = Scheduler.place(slots, schedule, MONDAY)
        assertEquals(SATURDAY, p[SlotRole.INTERVALS_B])
    }

    @Test
    fun `placement is deterministic`() {
        val schedule = AthleteSchedule(bikeDays = DayOfWeek.entries.toSet())
        val first = Scheduler.place(buildWeek, schedule, MONDAY)
        repeat(5) { assertEquals(first, Scheduler.place(buildWeek, schedule, MONDAY)) }
    }

    @Test
    fun `one bike day keeps only the main session`() {
        val p = Scheduler.place(buildWeek, AthleteSchedule(bikeDays = setOf(WEDNESDAY)), MONDAY)
        assertEquals(mapOf(SlotRole.INTERVALS_A to WEDNESDAY), p)
    }
}
