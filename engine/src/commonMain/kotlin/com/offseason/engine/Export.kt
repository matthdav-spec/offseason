package com.offseason.engine

import kotlin.math.abs
import kotlin.math.roundToLong

/** Formats a number with exactly [decimals] decimal places, the same on every platform ("0.90", not "0,90"). */
fun fixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return sign + whole
    return sign + whole + "." + (scaled % factor).toString().padStart(decimals, '0')
}

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

    private fun f(pct: Number) = fixed(pct.toDouble() / 100.0, 2)

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun fileName(workout: Workout, prefix: String = ""): String =
        (prefix + workout.code + "_" + workout.name).replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".zwo"

    /**
     * Every session in a week, named by date so they sort in order, as (file name, contents) pairs.
     * Each session is included as .zwo (Zwift, MyWhoosh) and .mrc (ROUVY, TrainingPeaks Virtual).
     * Zipping is done by each platform ([zipFiles]).
     */
    fun weekFiles(week: PlanWeek): List<Pair<String, String>> = week.sessions.flatMap { session ->
        val name = fileName(session.workout, prefix = "${session.date}_")
        listOf(name to toZwo(session.workout), MrcExporter.fileName(name) to MrcExporter.toMrc(session.workout))
    }

    fun weekBundle(week: PlanWeek): ByteArray = zipFiles(weekFiles(week))

    fun weekBundleName(week: PlanWeek) = "OffSeason_week${week.index + 1}_${week.startDate}.zip"
}

/**
 * The older .mrc format: a list of (minutes, % FTP) points joined by straight lines.
 * Steady blocks need two points at the same time to make a sharp step.
 * .mrc has no "free ride", so all-out test efforts use their estimated % FTP. Do FTP tests
 * with the .zwo, or with ERG mode off.
 */
object MrcExporter {
    fun toMrc(workout: Workout): String {
        val points = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        fun hold(sec: Int, pct: Double) {
            points += t to pct
            t += sec / 60.0
            points += t to pct
        }
        for (s in workout.steps) when (s.type) {
            StepType.WARMUP, StepType.COOLDOWN, StepType.RAMP -> {
                points += t to s.powerPctLow.toDouble()
                t += s.durationSec / 60.0
                points += t to (s.powerPctHigh ?: s.powerPctLow).toDouble()
            }
            StepType.INTERVALS -> repeat(s.repeat!!) {
                hold(s.durationSec, s.targetPct)
                hold(s.offDurationSec!!, s.offPowerPct!!.toDouble())
            }
            StepType.STEADY, StepType.FREE_RIDE -> hold(s.durationSec, s.targetPct)
        }
        return buildString {
            appendLine("[COURSE HEADER]")
            appendLine("VERSION = 2")
            appendLine("UNITS = ENGLISH")
            appendLine("DESCRIPTION = ${workout.name.replace(Regex("[\r\n]"), " ")}")
            appendLine("FILE NAME = ${workout.code}")
            appendLine("MINUTES PERCENT")
            appendLine("[END COURSE HEADER]")
            appendLine("[COURSE DATA]")
            for ((min, pct) in points) appendLine(fixed(min, 2) + "\t" + fixed(pct, 0))
            appendLine("[END COURSE DATA]")
        }
    }

    /** Same name as the .zwo, with the .mrc extension. */
    fun fileName(zwoName: String) = zwoName.removeSuffix(".zwo") + ".mrc"
}
