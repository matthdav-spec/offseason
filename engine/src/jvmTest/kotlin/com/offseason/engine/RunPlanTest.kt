package com.offseason.engine

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SATURDAY
import kotlinx.datetime.DayOfWeek.SUNDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RunPlanTest {

    private val monday = LocalDate(2026, 10, 26)
    private val allDays = DayOfWeek.entries.toSet()

    private fun request(
        runHours: RunHours = RunHours.THREE,
        bikeHours: HoursBucket = HoursBucket.FOUR,
        bikeDays: Set<DayOfWeek> = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY),
        runDays: Set<DayOfWeek> = setOf(MONDAY, WEDNESDAY, FRIDAY, SUNDAY),
        length: PlanLength = PlanLength.WEEKS_16,
        cycle: RecoveryCycle = RecoveryCycle.THREE_ONE,
        longRunDay: DayOfWeek? = null,
        swimDays: Set<DayOfWeek> = emptySet(),
    ) = PlanRequest(
        monday, length, bikeHours, cycle,
        AthleteSchedule(bikeDays = bikeDays, swimDays = swimDays),
        RunPlanRequest(runHours, runDays, longRunDay),
    )

    /** Every day that has a hard bike session or a hard run. */
    private fun hardDays(w: PlanWeek): List<LocalDate> =
        (w.sessions.filter { it.workout.isHard }.map { it.date } + w.runs.filter { it.workout.isHard }.map { it.date }).distinct().sorted()

    // ---- Zones and test ----

    @Test
    fun `run zones match the published LTHR bands`() {
        assertEquals(RunZone.Z1, RunZone.ofPct(84.0))
        assertEquals(RunZone.Z2, RunZone.ofPct(87.0))
        assertEquals(RunZone.Z3, RunZone.ofPct(92.0))
        assertEquals(RunZone.Z4, RunZone.ofPct(97.0))
        assertEquals(RunZone.Z5A, RunZone.ofPct(101.0))
        assertEquals(RunZone.Z5B, RunZone.ofPct(105.0))
        assertEquals(RunZone.Z5C, RunZone.ofPct(110.0))
    }

    @Test
    fun `zone heart rates for an LTHR of 165`() {
        // 85% of 165 = 140.25; 90% = 148.5, so 148 bpm (89.7%) is still Z2
        assertEquals(140..148, RunZone.Z2.bpm(165))
        assertEquals(149..156, RunZone.Z3.bpm(165))
        assertEquals(157..164, RunZone.Z4.bpm(165))
        assertEquals("140–148 bpm", Lthr.describe(RunZone.Z2..RunZone.Z2, 165))
        assertEquals("under 149 bpm", Lthr.describe(RunZone.Z1..RunZone.Z2, 165))
        assertEquals("Z1–Z2", Lthr.describe(RunZone.Z1..RunZone.Z2, null))
        // Zones meet without gaps or overlaps
        val all = RunZone.entries.dropLast(1).map { it.bpm(165) }
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `LTHR test is a 30 minute time trial`() {
        val t = RunLibrary.lthrTest()
        assertEquals(30 * 60, t.steps.first { it.type == RunStepType.TIME_TRIAL }.durationSec)
        assertTrue(t.isHard)
    }

    // ---- Run plan structure ----

    @Test
    fun `run blocks are base, tempo, threshold, race`() {
        val plan = PlanGenerator.generate(request())
        assertEquals(listOf(RunBlock.BASE, RunBlock.TEMPO, RunBlock.THRESHOLD, RunBlock.RACE), plan.weeks.mapNotNull { it.runBlock }.distinct())
    }

    @Test
    fun `every recovery week has an LTHR test and the FTP retest`() {
        val plan = PlanGenerator.generate(request())
        plan.weeks.forEach { w ->
            assertEquals(w.isRecovery, w.hasRunTest, "week ${w.index}")
            assertEquals(w.isRecovery, w.hasRetest, "week ${w.index}")
        }
    }

    @ParameterizedTest
    @EnumSource(RunHours::class)
    fun `weekly run time never rises more than 10 percent between build weeks`(hours: RunHours) {
        // Compared with the last build week, so the week after a recovery week returns to (not above) the previous level.
        val plan = PlanGenerator.generate(request(runHours = hours, runDays = allDays))
        var lastBuild: Int? = null
        plan.weeks.filterNot { it.isRecovery }.forEach { w ->
            val min = w.runs.sumOf { it.workout.durationMin }
            lastBuild?.let { assertTrue(min <= it * (1 + RunLibrary.MAX_WEEKLY_INCREASE) + 5, "week ${w.index}: $it → $min min") }
            lastBuild = min
        }
    }

    @ParameterizedTest
    @EnumSource(RunHours::class)
    fun `run volume stays close to the chosen hours`(hours: RunHours) {
        val plan = PlanGenerator.generate(request(runHours = hours, runDays = allDays))
        plan.weeks.filterNot { it.isRecovery }.forEach { w ->
            val min = w.runs.sumOf { it.workout.durationMin }
            assertTrue(min <= hours.hours * 60 + 15, "week ${w.index} has $min min for ${hours.hours} h")
            assertTrue(min >= hours.hours * 60 * 0.75, "week ${w.index} has only $min min for ${hours.hours} h")
        }
    }

    @Test
    fun `long run is never more than about a third of the week`() {
        for (hours in RunHours.entries) {
            val plan = PlanGenerator.generate(request(runHours = hours, runDays = allDays))
            plan.weeks.forEach { w ->
                val total = w.runs.sumOf { it.workout.durationMin }
                val long = w.runs.firstOrNull { it.role == RunRole.LONG }?.workout?.durationMin ?: 0
                assertTrue(long <= total * RunLibrary.LONG_RUN_MAX_SHARE + 5, "${hours.hours}h week ${w.index}: long $long of $total")
            }
        }
    }

    @Test
    fun `recovery week cuts run time`() {
        val plan = PlanGenerator.generate(request(runDays = allDays))
        val build = plan.weeks[2].runs.sumOf { it.workout.durationMin }
        val rec = plan.weeks[3].runs.sumOf { it.workout.durationMin }
        assertTrue(rec < build * 0.8, "recovery $rec vs build $build")
    }

    @Test
    fun `aerobic base block has no hard runs, only hill strides`() {
        val plan = PlanGenerator.generate(request())
        plan.weeks.take(3).forEach { w -> assertTrue(w.runs.none { it.workout.isHard }, "week ${w.index}") }
        assertTrue(plan.weeks[0].runs.any { it.workout.kind == RunKind.HILLS })
    }

    @Test
    fun `most running is easy`() {
        val plan = PlanGenerator.generate(request(runDays = allDays))
        plan.weeks.filterNot { it.isRecovery }.forEach { w ->
            var easySec = 0; var total = 0
            w.runs.flatMap { it.workout.steps }.forEach { s ->
                total += s.totalDurationSec
                if (s.zoneHigh <= RunZone.Z2 && s.type != RunStepType.TIME_TRIAL) easySec += s.totalDurationSec
            }
            assertTrue(easySec >= total * 0.75, "week ${w.index}: only ${easySec * 100 / total}% easy")
        }
    }

    // ---- Combined bike + run scheduling ----

    @Test
    fun `at most three hard days a week across both sports, never back to back`() {
        for (bike in HoursBucket.entries) for (run in RunHours.entries) {
            val plan = PlanGenerator.generate(request(runHours = run, bikeHours = bike, bikeDays = allDays, runDays = allDays))
            plan.weeks.forEach { w ->
                val hard = hardDays(w)
                assertTrue(hard.size <= JointScheduler.MAX_HARD_DAYS, "${bike}/${run} week ${w.index}: ${hard.size} hard days")
                hard.zipWithNext().forEach { (a, b) -> assertTrue(a.daysTo(b) > 1, "${bike}/${run} week ${w.index}: hard on $a and $b") }
            }
        }
    }

    @Test
    fun `a typical triathlete week keeps hard days apart`() {
        // Bike Tue/Thu/Sat/Sun, run Mon/Wed/Fri/Sun: the realistic case from the pilot
        val plan = PlanGenerator.generate(request())
        plan.weeks.forEach { w ->
            val hard = hardDays(w)
            hard.zipWithNext().forEach { (a, b) -> assertTrue(a.daysTo(b) > 1, "week ${w.index}: hard on $a and $b") }
            assertTrue(hard.size <= 3)
        }
    }

    @Test
    fun `long ride and long run are on different days, with no hard session on either`() {
        val plan = PlanGenerator.generate(request(bikeDays = allDays, runDays = allDays))
        plan.weeks.forEach { w ->
            val longRide = w.sessions.firstOrNull { it.role == SlotRole.LONG }?.date
            val longRun = w.runs.firstOrNull { it.role == RunRole.LONG }?.date
            assertNotEquals(longRide, longRun, "week ${w.index}")
            val hard = hardDays(w)
            assertFalse(longRide in hard, "week ${w.index}: hard session on long ride day")
            assertFalse(longRun in hard, "week ${w.index}: hard session on long run day")
        }
    }

    @Test
    fun `preferred long run day is respected in build weeks when the days allow it`() {
        // In recovery weeks the FTP and LTHR tests take priority for the late-week days.
        val plan = PlanGenerator.generate(request(longRunDay = SUNDAY, bikeDays = allDays, runDays = allDays))
        plan.weeks.filterNot { it.isRecovery }.forEach { w ->
            assertEquals(SUNDAY, w.runs.first { it.role == RunRole.LONG }.date.dayOfWeek, "week ${w.index}")
        }
    }

    @Test
    fun `preferred long run day always kept in the aerobic base block`() {
        // No hard runs yet, so even tight schedules fit.
        val plan = PlanGenerator.generate(request(longRunDay = SUNDAY))
        plan.weeks.take(3).forEach { w -> assertEquals(SUNDAY, w.runs.first { it.role == RunRole.LONG }.date.dayOfWeek) }
    }

    @Test
    fun `runs only land on run days and rides on bike days`() {
        val r = request()
        val plan = PlanGenerator.generate(r)
        plan.runs.forEach { assertTrue(it.date.dayOfWeek in r.run!!.runDays) }
        plan.sessions.forEach { assertTrue(it.date.dayOfWeek in r.schedule.bikeDays) }
    }

    @Test
    fun `too few run days keeps the long run and quality run first`() {
        val plan = PlanGenerator.generate(request(runDays = setOf(WEDNESDAY, SUNDAY)))
        assertEquals(setOf(RunRole.LONG, RunRole.QUALITY), plan.weeks[0].runs.map { it.role }.toSet())
        assertTrue(plan.weeks[3].hasRunTest, "the LTHR test still fits in a recovery week")
    }

    @Test
    fun `run only plan works with no bike days`() {
        val plan = PlanGenerator.generate(request(bikeDays = emptySet(), runDays = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY)))
        assertTrue(plan.sessions.isEmpty())
        assertTrue(plan.runs.isNotEmpty())
        assertEquals("Aerobic base: week 1", plan.weeks[0].title)
    }

    @Test
    fun `bike only plans are unchanged by the run feature`() {
        val bikeOnly = PlanRequest(monday, PlanLength.WEEKS_12, HoursBucket.FOUR, RecoveryCycle.THREE_ONE, AthleteSchedule(setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY)))
        val plan = PlanGenerator.generate(bikeOnly)
        assertTrue(plan.runs.isEmpty())
        assertEquals("w00-INTERVALS_A", plan.weeks[0].sessions.first { it.role == SlotRole.INTERVALS_A }.id)
    }

    @Test
    fun `ids are unique across bike and run`() {
        val plan = PlanGenerator.generate(request(bikeDays = allDays, runDays = allDays))
        val ids = plan.sessions.map { it.id } + plan.runs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `repeating a week repeats its runs too`() {
        val plan = PlanGenerator.generate(request())
        val repeated = WeekRepeats.apply(plan, listOf(1))
        assertEquals(plan.weeks[1].runs.size, repeated.weeks[2].runs.size)
        assertTrue(repeated.weeks[2].runs.all { it.id.startsWith("r0-") })
    }

    @Test
    fun `run load is on the same scale as bike load`() {
        val hour = RunWorkout("T", "T", "t", RunKind.EASY, listOf(RunStep(RunStepType.STEADY, 3600, RunZone.Z5A)))
        assertEquals(100.0, RunLoad.sessionLoad(hour), 3.0)
        val easy = RunWorkout("T", "T", "t", RunKind.EASY, listOf(RunStep(RunStepType.STEADY, 3600, RunZone.Z1, RunZone.Z2)))
        assertTrue(RunLoad.sessionLoad(easy) in 55.0..75.0)
    }

    @Test
    fun `a 16 week plan with every day available builds quickly`() {
        val start = System.nanoTime()
        PlanGenerator.generate(request(bikeHours = HoursBucket.FIVE, runHours = RunHours.FOUR, bikeDays = allDays, runDays = allDays))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 3000, "took $ms ms")
    }
}
