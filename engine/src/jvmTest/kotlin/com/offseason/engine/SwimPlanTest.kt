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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwimPlanTest {

    private val monday = LocalDate(2026, 10, 26)
    private val allDays = DayOfWeek.entries.toSet()

    private fun request(
        swims: Int = 2,
        level: SwimLevel = SwimLevel.CSS,
        swimDays: Set<DayOfWeek> = setOf(MONDAY, WEDNESDAY, FRIDAY),
        coached: Boolean = false,
        pool: Int = 25,
        bikeDays: Set<DayOfWeek> = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY),
        runDays: Set<DayOfWeek>? = setOf(MONDAY, WEDNESDAY, FRIDAY, SUNDAY),
    ) = PlanRequest(
        monday, PlanLength.WEEKS_16, HoursBucket.FOUR, RecoveryCycle.THREE_ONE,
        AthleteSchedule(bikeDays = bikeDays, swimDays = swimDays),
        run = runDays?.let { RunPlanRequest(RunHours.THREE, it) },
        swim = SwimPlanRequest(swims, swimDays, level, pool, coached),
    )

    /** Hard bike and run days: the leg-loading ones that must never be back to back. */
    private fun hardLegDays(w: PlanWeek): List<LocalDate> =
        (w.sessions.filter { it.workout.isHard }.map { it.date } + w.runs.filter { it.workout.isHard }.map { it.date }).distinct().sorted()

    private fun hardDays(w: PlanWeek): List<LocalDate> =
        (w.sessions.filter { it.workout.isHard }.map { it.date } +
            w.runs.filter { it.workout.isHard }.map { it.date } +
            w.swims.filter { it.workout.isHard }.map { it.date }).distinct().sorted()

    // ---- CSS ----

    @Test
    fun `CSS from a 400 m and 200 m time`() {
        // 400 m in 8:00, 200 m in 3:50 → (480 − 230) / 2 = 125 s = 2:05 per 100 m
        assertEquals(125, Css.fromTest(480, 230))
        assertEquals("2:05", Css.format(125))
        assertThrows(IllegalArgumentException::class.java) { Css.fromTest(200, 230) }
    }

    @Test
    fun `reading times typed as m_ss or seconds`() {
        assertEquals(480, Css.parse("8:00"))
        assertEquals(230, Css.parse(" 3:50 "))
        assertEquals(95, Css.parse("95"))
        assertNull(Css.parse("3:75"))
        assertNull(Css.parse("abc"))
    }

    @Test
    fun `zone paces from CSS`() {
        assertEquals("2:05/100 m", Css.describe(SwimZone.CSS, 125))
        assertEquals("2:13/100 m", Css.describe(SwimZone.STEADY, 125))
        assertEquals("2:01/100 m", Css.describe(SwimZone.FAST, 125))
        assertEquals("Endurance", Css.describe(SwimZone.STEADY, null))
    }

    // ---- Plan structure ----

    @Test
    fun `two swims a week, on swim days only`() {
        val r = request()
        val plan = PlanGenerator.generate(r)
        plan.weeks.forEach { w ->
            assertEquals(2, w.swims.size, "week ${w.index}")
            w.swims.forEach { assertTrue(it.date.dayOfWeek in r.schedule.swimDays) }
        }
    }

    @Test
    fun `every recovery week has a CSS test alongside FTP and LTHR`() {
        val plan = PlanGenerator.generate(request())
        plan.weeks.forEach { w -> assertEquals(w.isRecovery, w.hasSwimTest, "week ${w.index}") }
        assertEquals("Recovery week + FTP & LTHR & CSS test", plan.weeks[3].title)
    }

    @Test
    fun `sessions are 30 to 50 minutes for a typical age-grouper`() {
        val plan = PlanGenerator.generate(request(swims = 3, swimDays = setOf(MONDAY, WEDNESDAY, FRIDAY)))
        plan.swims.forEach { s ->
            val min = s.workout.estimatedMin()
            assertTrue(min in 25..55, "${s.workout.code} ${s.workout.name}: $min min")
        }
    }

    @Test
    fun `the main set progresses through the blocks`() {
        val plan = PlanGenerator.generate(request())
        val mains = plan.weeks.filterNot { it.isRecovery }.map { w -> w.swims.first { it.role == SwimRole.MAIN }.workout }
        assertTrue(mains.take(6).none { it.isHard }, "no CSS sets in the first two blocks")
        assertTrue(mains.drop(6).all { it.isHard }, "CSS sets from block 3")
    }

    @Test
    fun `every distance is whole lengths of a 25 m pool`() {
        val plan = PlanGenerator.generate(request(swims = 3))
        plan.swims.flatMap { it.workout.steps }.forEach { assertEquals(0, it.distanceM % 25, "${it.label}: ${it.distanceM} m") }
    }

    @Test
    fun `50 m pool rounds distances up to whole lengths`() {
        val plan = PlanGenerator.generate(request(pool = 50))
        plan.swims.flatMap { it.workout.steps }.forEach { assertEquals(0, it.distanceM % 50) }
    }

    // ---- Beginner build-up route ----

    @Test
    fun `build-up route has no CSS sets or tests and grows towards 400 m`() {
        val plan = PlanGenerator.generate(request(level = SwimLevel.BUILD_UP))
        assertTrue(plan.swims.none { it.workout.kind == SwimKind.CSS_TEST || it.workout.kind == SwimKind.CSS_SET })
        val longest = plan.weeks.filterNot { it.isRecovery }.map { w ->
            w.swims.first { it.role == SwimRole.MAIN }.workout.steps.filter { it.type == SwimStepType.REPEATS }.maxOf { it.distanceM }
        }
        assertEquals(25, longest.first())
        assertTrue(longest.zipWithNext().all { (a, b) -> b >= a }, "swims only get longer: $longest")
        assertTrue(longest.last() >= 150, "builds to longer swims: $longest")
        assertTrue(plan.weeks[3].swims.any { it.workout.name == "Distance check" })
    }

    @Test
    fun `build-up sessions are gentle in volume`() {
        val plan = PlanGenerator.generate(request(level = SwimLevel.BUILD_UP))
        plan.swims.forEach { assertTrue(it.workout.distanceM <= 1000, "${it.workout.name}: ${it.workout.distanceM} m") }
    }

    // ---- Coached ----

    @Test
    fun `coached swims are not planned, but bike and run still plan around swim days`() {
        val plan = PlanGenerator.generate(request(coached = true))
        assertTrue(plan.swims.isEmpty())
        assertTrue(plan.sessions.isNotEmpty() && plan.runs.isNotEmpty())
        assertFalse(plan.request.hasSwim)
    }

    // ---- Three-sport scheduling ----

    @Test
    fun `hard days stay within limits across swim, bike and run`() {
        for (swims in 1..3) {
            val plan = PlanGenerator.generate(request(swims = swims, swimDays = allDays, bikeDays = allDays, runDays = allDays))
            plan.weeks.forEach { w ->
                assertTrue(hardDays(w).size <= JointScheduler.MAX_HARD_DAYS_WITH_SWIM, "$swims swims, week ${w.index}: ${hardDays(w)}")
                val legs = hardLegDays(w)
                assertTrue(legs.size <= JointScheduler.MAX_HARD_DAYS, "$swims swims, week ${w.index}: $legs")
                legs.zipWithNext().forEach { (a, b) -> assertTrue(a.daysTo(b) > 1, "$swims swims, week ${w.index}: hard bike/run on $a and $b") }
            }
        }
    }

    @Test
    fun `a realistic week keeps the rules`() {
        // Swim Mon/Wed/Fri, bike Tue/Thu/Sat/Sun, run Mon/Wed/Fri/Sun
        val plan = PlanGenerator.generate(request())
        plan.weeks.forEach { w ->
            assertTrue(hardDays(w).size <= 4, "week ${w.index}: ${hardDays(w)}")
            val legs = hardLegDays(w)
            legs.zipWithNext().forEach { (a, b) -> assertTrue(a.daysTo(b) > 1, "week ${w.index}: hard bike/run on $a and $b") }
        }
        // And she still gets real CSS swimming in the CSS block
        val cssBlockMains = plan.weeks.filter { it.blockIndex == 2 && !it.isRecovery }.map { w -> w.swims.first { it.role == SwimRole.MAIN }.workout }
        assertTrue(cssBlockMains.count { it.kind == SwimKind.CSS_SET } >= 2, cssBlockMains.joinToString { it.name })
    }

    @Test
    fun `hard swims avoid the day after the long ride or run`() {
        val plan = PlanGenerator.generate(request(swimDays = allDays, bikeDays = allDays, runDays = allDays))
        plan.weeks.forEach { w ->
            val longs = (w.sessions.filter { it.role == SlotRole.LONG } .map { it.date } + w.runs.filter { it.role == RunRole.LONG }.map { it.date })
            w.swims.filter { it.workout.isHard }.forEach { s ->
                assertFalse(longs.any { it.daysTo(s.date) == 1 }, "week ${w.index}: hard swim ${s.date} the day after a long session")
            }
        }
    }

    @Test
    fun `swim only plan works`() {
        val plan = PlanGenerator.generate(request(bikeDays = emptySet(), runDays = null))
        assertTrue(plan.sessions.isEmpty() && plan.runs.isEmpty())
        assertEquals(32, plan.swims.size)
    }

    @Test
    fun `ids are unique across all three sports`() {
        val plan = PlanGenerator.generate(request(swims = 3, swimDays = allDays, bikeDays = allDays, runDays = allDays))
        val ids = plan.sessions.map { it.id } + plan.runs.map { it.id } + plan.swims.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `plans without swimming are unchanged`() {
        val plain = request().copy(swim = null)
        val plan = PlanGenerator.generate(plain)
        assertTrue(plan.swims.isEmpty())
        assertEquals("Recovery week + FTP & LTHR test", plan.weeks[3].title)
    }

    @Test
    fun `swim load is on the same scale`() {
        val hourAtCss = SwimWorkout("T", "T", "t", SwimKind.CSS_SET, listOf(SwimStep(SwimStepType.SWIM, 3000, SwimZone.CSS)))
        // 3000 m at 2:00/100 m = 60 min at CSS ≈ 100
        assertEquals(100.0, SwimLoad.sessionLoad(hourAtCss, 120), 1.0)
    }

    @Test
    fun `repeating a week repeats its swims too`() {
        val plan = PlanGenerator.generate(request())
        val repeated = WeekRepeats.apply(plan, listOf(1))
        assertEquals(plan.weeks[1].swims.size, repeated.weeks[2].swims.size)
        assertTrue(repeated.weeks[2].swims.all { it.id.startsWith("r0-") })
    }
}
