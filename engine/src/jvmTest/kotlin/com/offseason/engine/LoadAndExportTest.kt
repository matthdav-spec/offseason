package com.offseason.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LoadAndExportTest {

    private fun steadyWorkout(pct: Int, minutes: Int) = Workout(
        "T", "Test", "test", SessionKind.TEMPO, listOf(Step(StepType.STEADY, minutes * 60, pct)),
    )

    @Test
    fun `one hour at FTP is a Session Load of 100`() {
        val w = steadyWorkout(100, 60)
        assertEquals(1.0, Load.effortScore(w), 1e-9)
        assertEquals(100.0, Load.sessionLoad(w), 1e-6)
    }

    @Test
    fun `one hour at 70 percent is about 49`() {
        assertEquals(49.0, Load.sessionLoad(steadyWorkout(70, 60)), 0.01)
    }

    @Test
    fun `intervals weigh more than their average`() {
        val w = Workout(
            "T", "T", "t", SessionKind.INTERVALS,
            listOf(Step(StepType.INTERVALS, 60, 120, repeat = 10, offDurationSec = 60, offPowerPct = 50)),
        )
        assertTrue(Load.effortScore(w) > 0.85, "weighted power should exceed the 85% average")
    }

    @Test
    fun `planned session loads are in a sensible range`() {
        val plan = PlanGenerator.generate(
            PlanRequest(
                LocalDate(2026, 10, 26), PlanLength.WEEKS_16, HoursBucket.FIVE, RecoveryCycle.THREE_ONE,
                AthleteSchedule(DayOfWeek.entries.toSet()),
            ),
        )
        plan.sessions.forEach { s ->
            val es = Load.effortScore(s.workout)
            assertTrue(es in 0.5..0.95, "${s.workout.code} effort score $es")
        }
    }

    @Test
    fun `fitness rise per week stays under the cap from a modest base`() {
        val plan = PlanGenerator.generate(
            PlanRequest(
                LocalDate(2026, 10, 26), PlanLength.WEEKS_16, HoursBucket.FIVE, RecoveryCycle.THREE_ONE,
                AthleteSchedule(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
            ),
        )
        // A triathlete arriving with a modest base (Fitness ~ 30 from swim/run/bike combined isn't modelled; bike-only ~25)
        val series = Load.series(Load.plannedDailyLoad(plan), plan.startDate, plan.endDate, startFitness = 25.0, startFatigue = 25.0)
        series.windowed(8, 7).forEach { w ->
            val rise = w.last().fitness - w.first().fitness
            assertTrue(rise <= Load.MAX_WEEKLY_FITNESS_RISE, "fitness rose $rise in week starting ${w.first().date}")
        }
    }

    @Test
    fun `fitness and fatigue follow exponential averages`() {
        val d0 = LocalDate(2026, 1, 1)
        val loads = (0 until 100).associate { d0.plusDays(it) to 50.0 }
        val s = Load.series(loads, d0, d0.plusDays(99))
        assertEquals(50.0, s.last().fatigue, 0.1)
        assertTrue(s.last().fitness in 40.0..50.0)
        assertTrue(s[6].fatigue > s[6].fitness, "fatigue responds faster")
    }

    @Test
    fun `ftp from tests`() {
        assertEquals(238, Ftp.from20MinTest(250))
        assertEquals(225, Ftp.fromRampTest(300))
        assertEquals(225, Ftp.watts(90, 250))
        assertEquals(6.0, Ftp.improvementPct(200, 212), 1e-9)
    }

    @Test
    fun `zones`() {
        assertEquals(Zone.Z1, Zone.of(50.0))
        assertEquals(Zone.Z2, Zone.of(70.0))
        assertEquals(Zone.SS, Zone.of(90.0))
        assertEquals(Zone.Z4, Zone.of(100.0))
        assertEquals(Zone.Z5, Zone.of(115.0))
        assertEquals(Zone.Z7, Zone.of(200.0))
    }

    @Test
    fun `zwo is well-formed xml with fractional powers`() {
        val w = WorkoutLibrary.workout(HoursBucket.FOUR, BlockType.SWEET_SPOT, 0, 2, false, SlotRole.INTERVALS_A)
        val xml = ZwoExporter.toZwo(w)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val interval = doc.getElementsByTagName("IntervalsT").item(0).attributes
        assertEquals("3", interval.getNamedItem("Repeat").nodeValue)
        assertEquals("720", interval.getNamedItem("OnDuration").nodeValue)
        assertEquals("0.91", interval.getNamedItem("OnPower").nodeValue)
        assertEquals("bike", doc.getElementsByTagName("sportType").item(0).textContent)
        assertTrue(xml.contains("<author>Off-Season</author>"))
    }

    @Test
    fun `ftp test zwo includes free ride and parses`() {
        val xml = ZwoExporter.toZwo(WorkoutLibrary.ftpTest())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(2, doc.getElementsByTagName("FreeRide").length)
        assertTrue(doc.getElementsByTagName("textevent").length > 0)
    }

    @Test
    fun `week bundle contains one zwo per session`() {
        val plan = PlanGenerator.generate(
            PlanRequest(
                LocalDate(2026, 10, 26), PlanLength.WEEKS_4, HoursBucket.FOUR, RecoveryCycle.THREE_ONE,
                AthleteSchedule(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
            ),
        )
        val week = plan.weeks[0]
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ZwoExporter.weekBundle(week))).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals(week.sessions.size, names.count { it.endsWith(".zwo") })
        assertEquals(week.sessions.size, names.count { it.endsWith(".mrc") })
        assertTrue(names.all { it.startsWith("2026-") })
    }

    @Test
    fun `mrc matches the workout's length and shape`() {
        val w = WorkoutLibrary.workout(HoursBucket.FOUR, BlockType.SWEET_SPOT, 0, 2, false, SlotRole.INTERVALS_A)
        val mrc = MrcExporter.toMrc(w)
        assertTrue(mrc.startsWith("[COURSE HEADER]"))
        assertTrue(mrc.contains("MINUTES PERCENT"))
        val points = mrc.lines()
            .dropWhile { it != "[COURSE DATA]" }.drop(1).takeWhile { it != "[END COURSE DATA]" }
            .map { line -> line.split("	").let { it[0].toDouble() to it[1].toDouble() } }
        assertEquals(0.0, points.first().first)
        assertEquals(w.durationSec / 60.0, points.last().first, 0.01)
        assertTrue(points.zipWithNext().all { (a, b) -> b.first >= a.first }, "time must never go backwards")
        assertEquals(3, points.count { it.second == 91.0 } / 2, "three sweet spot efforts at 91%")
    }

    @Test
    fun `status messages`() {
        assertEquals(Status.Light.RED, Status.forWeek(dummyWeek(false), -30.0, 2.0, 1.0).light)
        assertEquals(Status.Light.GREEN, Status.forWeek(dummyWeek(true), -30.0, 8.0, 1.0).light)
        assertEquals(Status.Light.AMBER, Status.forWeek(dummyWeek(false), 0.0, 2.0, 0.4).light)
        assertEquals(Status.Light.GREEN, Status.forWeek(dummyWeek(false), -5.0, 3.0, 0.9).light)
    }

    private fun dummyWeek(recovery: Boolean) =
        PlanWeek(0, LocalDate(2026, 10, 26), 0, BlockType.SWEET_SPOT, if (recovery) 4 else 1, recovery, emptyList())
}
