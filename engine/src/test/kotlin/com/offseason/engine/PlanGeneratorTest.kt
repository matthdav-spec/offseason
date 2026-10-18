package com.offseason.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class PlanGeneratorTest {

    private val monday = LocalDate.of(2026, 10, 26)

    private fun request(
        length: PlanLength = PlanLength.WEEKS_12,
        hours: HoursBucket = HoursBucket.FOUR,
        cycle: RecoveryCycle = RecoveryCycle.THREE_ONE,
        schedule: AthleteSchedule = AthleteSchedule(bikeDays = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY)),
        start: LocalDate = monday,
    ) = PlanRequest(start, length, hours, cycle, schedule)

    @ParameterizedTest
    @EnumSource(PlanLength::class)
    fun `plan length maps to stacked 4-week blocks`(length: PlanLength) {
        val plan = PlanGenerator.generate(request(length = length))
        assertEquals(length.nominalWeeks, plan.weeks.size)
        assertEquals(length.blocks, plan.weeks.map { it.blockIndex }.distinct().size)
    }

    @Test
    fun `blocks run sweet spot, threshold, VO2, race-specific`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16))
        assertEquals(
            listOf(BlockType.SWEET_SPOT, BlockType.THRESHOLD, BlockType.VO2, BlockType.RACE_SPECIFIC),
            plan.weeks.map { it.blockType }.distinct(),
        )
    }

    @Test
    fun `every 4th week is recovery with an FTP retest`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16))
        plan.weeks.forEach { week ->
            val expectRecovery = week.weekInBlock == 4
            assertEquals(expectRecovery, week.isRecovery, "week ${week.index}")
            assertEquals(expectRecovery, week.hasRetest, "week ${week.index}")
        }
    }

    @Test
    fun `2-1 cycle gives 3-week blocks with retest in week 3`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_12, cycle = RecoveryCycle.TWO_ONE))
        assertEquals(9, plan.weeks.size)
        assertEquals(listOf(false, false, true), plan.weeks.take(3).map { it.isRecovery })
        assertEquals(9, PlanGenerator.totalWeeks(PlanLength.WEEKS_12, RecoveryCycle.TWO_ONE))
    }

    @Test
    fun `weeks are consecutive and start on the start date's weekday`() {
        val wed = LocalDate.of(2026, 10, 28)
        val plan = PlanGenerator.generate(request(start = wed))
        plan.weeks.forEachIndexed { i, w -> assertEquals(wed.plusWeeks(i.toLong()), w.startDate) }
        plan.sessions.forEach { s ->
            val week = plan.weeks[s.weekIndex]
            assertTrue(week.contains(s.date), "${s.id} on ${s.date} outside its week")
            assertTrue(s.date.dayOfWeek in request().schedule.bikeDays)
        }
    }

    @ParameterizedTest
    @EnumSource(HoursBucket::class)
    fun `never more than two hard sessions a week`(hours: HoursBucket) {
        val days = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16, hours = hours, schedule = AthleteSchedule(days)))
        plan.weeks.forEach { w -> assertTrue(w.sessions.count { it.workout.isHard } <= 2, "week ${w.index}") }
    }

    @ParameterizedTest
    @EnumSource(HoursBucket::class)
    fun `build-week volume matches the hours bucket`(hours: HoursBucket) {
        val days = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16, hours = hours, schedule = AthleteSchedule(days)))
        plan.weeks.filterNot { it.isRecovery }.forEach { w ->
            val minutes = w.sessions.sumOf { it.workout.durationMin }
            assertEquals(hours.hours * 60.0, minutes.toDouble(), 10.0, "week ${w.index}")
        }
    }

    @Test
    fun `recovery week cuts volume and removes intensity apart from the test`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_4))
        val build = plan.weeks[2].sessions.filter { it.workout.kind != SessionKind.FTP_TEST }.sumOf { it.workout.durationMin }
        val rec = plan.weeks[3]
        val recNonTest = rec.sessions.filter { it.workout.kind != SessionKind.FTP_TEST }
        val buildEquivalent = plan.weeks[2].sessions.filter { it.role in recNonTest.map { s -> s.role } }.sumOf { it.workout.durationMin }
        assertTrue(recNonTest.sumOf { it.workout.durationMin } <= buildEquivalent * 0.65, "recovery should be about 40% less")
        assertTrue(build > 0)
        recNonTest.forEach { s ->
            s.workout.steps.forEach { step ->
                assertTrue(step.targetPct <= 75.0, "${s.workout.code} has a step at ${step.targetPct}% in recovery week")
            }
        }
    }

    @Test
    fun `main set progresses each build week`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16))
        for (block in 0 until 3) {
            // Harder = more total time on, or the same time in longer efforts.
            val progression = (0..2).map { wk ->
                val w = plan.weeks[block * 4 + wk]
                val a = w.sessions.first { it.role == SlotRole.INTERVALS_A }.workout
                val sets = a.steps.filter { it.type == StepType.INTERVALS }
                sets.sumOf { it.repeat!! * it.durationSec * it.targetPct } to sets.maxOf { it.durationSec }
            }
            progression.zipWithNext().forEach { (prev, next) ->
                assertTrue(
                    next.first > prev.first || (next.first == prev.first && next.second > prev.second),
                    "block $block main set doesn't progress: $progression",
                )
            }
        }
    }

    @Test
    fun `sweet spot block 1 main set matches the brief`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_4))
        val sets = plan.weeks.take(3).map { w ->
            val step = w.sessions.first { it.role == SlotRole.INTERVALS_A }.workout.steps.first { it.type == StepType.INTERVALS }
            "${step.repeat}x${step.durationSec / 60}@${step.powerPctLow}-${step.powerPctHigh}"
        }
        assertEquals(listOf("3x10@88-94", "3x12@88-94", "2x20@88-94"), sets)
    }

    @Test
    fun `4-hour optional spin is flagged optional, others are not`() {
        val plan = PlanGenerator.generate(request(hours = HoursBucket.FOUR))
        plan.sessions.forEach { assertEquals(it.role == SlotRole.EXTRA, it.optional) }
        val plan5 = PlanGenerator.generate(request(hours = HoursBucket.FIVE))
        assertFalse(plan5.sessions.any { it.optional })
    }

    @Test
    fun `fewer bike days than sessions drops lowest priority first`() {
        val plan = PlanGenerator.generate(
            request(hours = HoursBucket.FIVE, schedule = AthleteSchedule(bikeDays = setOf(TUESDAY, THURSDAY))),
        )
        val week0Roles = plan.weeks[0].sessions.map { it.role }.toSet()
        assertEquals(setOf(SlotRole.INTERVALS_A, SlotRole.INTERVALS_B), week0Roles)
        // Recovery week keeps the retest even with only two days
        assertTrue(plan.weeks[3].hasRetest)
    }

    @Test
    fun `session ids are unique`() {
        val plan = PlanGenerator.generate(request(length = PlanLength.WEEKS_16, hours = HoursBucket.FIVE))
        assertEquals(plan.sessions.size, plan.sessions.map { it.id }.toSet().size)
    }

    @Test
    fun `every workout in every combination is valid`() {
        for (hours in HoursBucket.entries) for (cycle in RecoveryCycle.entries) {
            val days = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
            val plan = PlanGenerator.generate(request(PlanLength.WEEKS_16, hours, cycle, AthleteSchedule(days)))
            plan.sessions.forEach { s ->
                val w = s.workout
                assertTrue(w.steps.first().type == StepType.WARMUP, "${w.code} should start with a warm-up")
                assertTrue(w.steps.last().type == StepType.COOLDOWN, "${w.code} should end with a cool-down")
                assertTrue(w.purpose.isNotBlank())
            }
        }
    }
}
