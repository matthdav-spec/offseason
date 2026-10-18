package com.offseason.engine

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Turns workouts into files that trainer apps can load. */
object ZwoExporter {
    const val AUTHOR = "Off-Season"

    /** Zwift workout XML. Powers are fractions of FTP, so the file works at any FTP. */
    fun toZwo(workout: Workout): String = buildString {
        appendLine("<workout_file>")
        appendLine("  <author>$AUTHOR</author>")
        appendLine("  <name>${xml("${workout.code} - ${workout.name}")}</name>")
        appendLine("  <description>${xml(workout.purpose)}</description>")
        appendLine("  <sportType>bike</sportType>")
        appendLine("  <workout>")
        for (step in workout.steps) appendLine("    " + element(step))
        appendLine("  </workout>")
        appendLine("</workout_file>")
    }

    private fun element(s: Step): String {
        val cad = s.cadenceTarget?.let { " Cadence=\"$it\"" } ?: ""
        val text = s.label?.let { "><textevent timeoffset=\"0\" message=\"${xml(it)}\"/></" }
        fun close(tag: String) = if (text != null) "$text$tag>" else "/>"
        return when (s.type) {
            StepType.WARMUP -> "<Warmup Duration=\"${s.durationSec}\" PowerLow=\"${f(s.powerPctLow)}\" PowerHigh=\"${f(s.powerPctHigh ?: s.powerPctLow)}\"$cad${close("Warmup")}"
            StepType.COOLDOWN -> "<Cooldown Duration=\"${s.durationSec}\" PowerLow=\"${f(s.powerPctLow)}\" PowerHigh=\"${f(s.powerPctHigh ?: s.powerPctLow)}\"$cad${close("Cooldown")}"
            StepType.RAMP -> "<Ramp Duration=\"${s.durationSec}\" PowerLow=\"${f(s.powerPctLow)}\" PowerHigh=\"${f(s.powerPctHigh ?: s.powerPctLow)}\"$cad${close("Ramp")}"
            StepType.STEADY -> "<SteadyState Duration=\"${s.durationSec}\" Power=\"${f(s.targetPct)}\"$cad${close("SteadyState")}"
            StepType.INTERVALS -> "<IntervalsT Repeat=\"${s.repeat}\" OnDuration=\"${s.durationSec}\" OffDuration=\"${s.offDurationSec}\" " +
                "OnPower=\"${f(s.targetPct)}\" OffPower=\"${f(s.offPowerPct!!)}\"$cad${close("IntervalsT")}"
            StepType.FREE_RIDE -> "<FreeRide Duration=\"${s.durationSec}\" FlatRoad=\"1\"$cad${close("FreeRide")}"
        }
    }

    private fun f(pct: Number) = String.format(Locale.ROOT, "%.2f", pct.toDouble() / 100.0)

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun fileName(workout: Workout, prefix: String = ""): String =
        (prefix + workout.code + "_" + workout.name).replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".zwo"

    /** A zip of every session in a week, named by date so they sort in order. */
    fun weekBundle(week: PlanWeek): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (session in week.sessions) {
                zip.putNextEntry(ZipEntry(fileName(session.workout, prefix = "${session.date}_")))
                zip.write(toZwo(session.workout).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun weekBundleName(week: PlanWeek) = "OffSeason_week${week.index + 1}_${week.startDate}.zip"
}
