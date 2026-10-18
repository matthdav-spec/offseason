package com.offseason.engine

import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RideAndAdaptationTest {

    @TempDir
    lateinit var tmp: File

    /** Writes a real .fit file with one record per second at the given powers. */
    private fun fitFile(powers: List<Int>, start: Instant = Instant.parse("2026-10-20T18:00:00Z"), stepSec: Long = 1): ByteArray {
        val f = File(tmp, "ride.fit")
        val enc = FileEncoder(f, com.garmin.fit.Fit.ProtocolVersion.V2_0)
        enc.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            product = 1
            serialNumber = 1L
            timeCreated = DateTime(java.util.Date.from(start))
        })
        powers.forEachIndexed { i, p ->
            enc.write(RecordMesg().apply {
                timestamp = DateTime(java.util.Date.from(start.plusSeconds(i * stepSec)))
                power = p
                cadence = 90.toShort()
            })
        }
        enc.close()
        return f.readBytes()
    }

    @Test
    fun `reads power from a real fit file`() {
        val ride = FitReader.read(fitFile(List(600) { 200 }))
        assertEquals(600, ride.durationSec)
        assertEquals(Instant.parse("2026-10-20T18:00:00Z"), ride.start)
        val s = RideAnalysis.summarize(ride)
        assertEquals(200, s.avgPower)
        assertEquals(200, s.weightedPower)
        assertNull(s.best20MinPower, "a 10-minute ride has no 20-minute best")
    }

    @Test
    fun `reads a fit file inside a zip, like Garmin Connect exports`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("12345_ACTIVITY.fit")); z.write(fitFile(List(120) { 150 })); z.closeEntry()
        }
        assertEquals(120, FitReader.read(out.toByteArray()).durationSec)
    }

    @Test
    fun `rejects files that aren't rides with a friendly message`() {
        val e = assertThrows(RideFileException::class.java) { FitReader.read("<workout_file/>".toByteArray()) }
        assertTrue(e.message!!.contains("isn't a .fit"))
    }

    @Test
    fun `pauses are removed and short dropouts filled`() {
        // 10 s riding, 5-minute pause, 10 s riding; plus 2 s recording interval elsewhere
        val ts = (0L until 10L) + (310L until 320L)
        assertEquals(20, FitReader.toPerSecond(ts, List(20) { 100 }).size)
        // Samples every 2 s from 0 to 18 s cover 19 seconds of riding
        val every2 = (0L until 20L step 2).toList()
        assertEquals(19, FitReader.toPerSecond(every2, List(10) { 100 }).size)
    }

    @Test
    fun `weighted power exceeds average for intervals`() {
        val p = IntArray(1800) { if ((it / 60) % 2 == 0) 300 else 100 }
        val s = RideAnalysis.summarize(RideData(Instant.EPOCH, p))
        assertEquals(200, s.avgPower)
        assertTrue(s.weightedPower!! > 220, "weighted ${s.weightedPower}")
    }

    @Test
    fun `best 20 minutes finds the test effort`() {
        val p = IntArray(70 * 60) { 150 } .also { for (i in 2400 until 3600) it[i] = 260 }
        assertEquals(260, RideAnalysis.summarize(RideData(Instant.EPOCH, p)).best20MinPower)
    }

    @Test
    fun `session load from a ride at FTP for an hour is 100`() {
        val s = RideAnalysis.summarize(RideData(Instant.EPOCH, IntArray(3600) { 250 }))
        assertEquals(100.0, s.sessionLoad(250)!!, 0.5)
    }

    @Test
    fun `ride with no power meter has no power numbers`() {
        val s = RideAnalysis.summarize(RideData(Instant.EPOCH, IntArray(600)))
        assertNull(s.avgPower); assertNull(s.sessionLoad(250))
    }

    // ---- Adaptation ----

    private val plan = PlanGenerator.generate(
        PlanRequest(
            LocalDate.of(2026, 10, 26), PlanLength.WEEKS_8, HoursBucket.FOUR, RecoveryCycle.THREE_ONE,
            AthleteSchedule(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
        ),
    )

    @Test
    fun `offers easing after two hard interval sessions in a row`() {
        val F = Adaptation.Feel.entries
        assertTrue(Adaptation.shouldOfferEasing(listOf(Adaptation.Feel.HARD, Adaptation.Feel.COULDNT_FINISH)))
        assertFalse(Adaptation.shouldOfferEasing(listOf(Adaptation.Feel.HARD, Adaptation.Feel.ABOUT_RIGHT)))
        assertFalse(Adaptation.shouldOfferEasing(listOf(Adaptation.Feel.HARD)))
        assertTrue(F.size == 4)
    }

    @Test
    fun `lowering targets scales future sessions only, never the FTP test`() {
        val from = plan.weeks[1].startDate
        val eased = Adaptation.applyTargetAdjustment(plan, from, -4)
        val before = plan.weeks[0].sessions.first().workout
        assertEquals(before, eased.weeks[0].sessions.first().workout, "past weeks unchanged")
        val orig = plan.weeks[1].sessions.first { it.role == SlotRole.INTERVALS_A }.workout.steps.first { it.type == StepType.INTERVALS }
        val now = eased.weeks[1].sessions.first { it.role == SlotRole.INTERVALS_A }.workout.steps.first { it.type == StepType.INTERVALS }
        assertEquals(Math.round(orig.powerPctLow * 0.96).toInt(), now.powerPctLow)
        val easedA = eased.weeks[1].sessions.first { it.role == SlotRole.INTERVALS_A }.workout
        assertEquals(Zone.SS, easedA.plannedZone(now), "eased sweet spot is still called sweet spot")
        val test = plan.sessions.first { it.workout.kind == SessionKind.FTP_TEST }
        assertEquals(test.workout, eased.session(test.id)!!.workout)
    }

    @Test
    fun `repeating a week inserts a copy and pushes the rest back`() {
        val repeated = WeekRepeats.apply(plan, listOf(1))
        assertEquals(plan.weeks.size + 1, repeated.weeks.size)
        val copy = repeated.weeks[2]
        assertTrue(copy.isRepeat)
        assertEquals(plan.weeks[1].weekInBlock, copy.weekInBlock)
        assertEquals(plan.startDate.plusWeeks(2), copy.startDate)
        // Original ids keep their meaning; copies get new ones
        assertEquals(plan.weeks[1].sessions.map { it.id }, repeated.weeks[1].sessions.map { it.id })
        assertTrue(copy.sessions.all { it.id.startsWith("r0-") })
        assertEquals(repeated.sessions.size, repeated.sessions.map { it.id }.toSet().size)
        // The recovery week moved back by a week
        assertEquals(plan.weeks[3].startDate.plusWeeks(1), repeated.weeks[4].startDate)
        assertTrue(repeated.weeks[4].isRecovery)
        assertFalse(WeekRepeats.canRepeat(plan.weeks[3]))
    }
}
