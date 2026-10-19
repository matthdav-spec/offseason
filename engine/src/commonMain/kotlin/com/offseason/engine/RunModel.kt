package com.offseason.engine

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * The run side of the plan. Kept separate from the bike types so the bike plan (already in
 * the pilot) is unchanged when running is switched off.
 *
 * Run targets are heart-rate zones based on LTHR (lactate threshold heart rate), using the
 * standard percentage bands from Joe Friel's published guide, with our own zone names.
 * LTHR comes from a 30-minute solo time trial: the average heart rate of the last 20 minutes.
 */

/** Heart-rate zones as % of LTHR. [highPct] is inclusive; null means no upper limit. */
enum class RunZone(val label: String, val displayName: String, val lowPct: Int, val highPct: Int?, val feel: String) {
    Z1("Z1", "Recovery", 0, 84, "Very easy. Full conversation"),
    Z2("Z2", "Aerobic", 85, 89, "Easy. You could chat in sentences"),
    Z3("Z3", "Tempo", 90, 94, "Steady. Short phrases only"),
    Z4("Z4", "Sub-threshold", 95, 99, "Comfortably hard. A few words"),
    Z5A("Z5a", "Threshold", 100, 102, "Hard. Race effort for about an hour"),
    Z5B("Z5b", "Aerobic capacity", 103, 106, "Very hard. 3–8 minute efforts"),
    Z5C("Z5c", "Anaerobic", 107, null, "Flat out. Short sprints");

    /** Heart-rate range in bpm for an athlete's LTHR. */
    fun bpm(lthr: Int): IntRange {
        val low = if (lowPct == 0) 0 else (lthr * lowPct / 100.0).roundToInt()
        val high = highPct?.let { (lthr * (it + 1) / 100.0).roundToInt() - 1 } ?: 230
        return low..high
    }

    companion object {
        fun ofPct(pct: Double): RunZone {
            val p = pct.roundToInt()
            return entries.first { it.highPct == null || p <= it.highPct }
        }
    }
}

object Lthr {
    /** LTHR = average heart rate over the last 20 minutes of a 30-minute solo time trial. */
    fun fromTest(avgHrLast20Min: Int): Int = avgHrLast20Min

    /** "Z2" → "128–135 bpm" for an LTHR; just the zone name if LTHR isn't known yet. */
    fun describe(zones: ClosedRange<RunZone>, lthr: Int?): String {
        if (lthr == null) return if (zones.start == zones.endInclusive) zones.start.label else "${zones.start.label}–${zones.endInclusive.label}"
        val low = zones.start.bpm(lthr).first
        val high = zones.endInclusive.bpm(lthr).last
        return when {
            low == 0 -> "under ${high + 1} bpm"
            zones.endInclusive.highPct == null -> "over ${low - 1} bpm"
            else -> "$low–$high bpm"
        }
    }
}

enum class RunStepType { WARMUP, STEADY, INTERVALS, STRIDES, COOLDOWN, TIME_TRIAL }

/**
 * One block of a run.
 * - STEADY / WARMUP / COOLDOWN: run in [zoneLow]..[zoneHigh] for [durationSec].
 * - INTERVALS: [repeat] × ([durationSec] in zoneLow..zoneHigh, then [offDurationSec] easy jog).
 * - STRIDES: [repeat] short, fast-but-relaxed efforts. Heart rate lags too much to use, so by feel.
 * - TIME_TRIAL: as hard as you can hold for the whole time (the LTHR test). No target.
 */
data class RunStep(
    val type: RunStepType,
    val durationSec: Int,
    val zoneLow: RunZone,
    val zoneHigh: RunZone = zoneLow,
    val repeat: Int? = null,
    val offDurationSec: Int? = null,
    val label: String? = null,
) {
    init {
        require(durationSec > 0)
        if (type == RunStepType.INTERVALS || type == RunStepType.STRIDES) require((repeat ?: 0) > 0 && (offDurationSec ?: 0) > 0)
    }

    val zones: ClosedRange<RunZone> get() = zoneLow..zoneHigh

    val totalDurationSec: Int
        get() = if (repeat != null && offDurationSec != null) repeat * (durationSec + offDurationSec) else durationSec

    /** Midpoint % of LTHR, used to estimate load. */
    val targetPct: Double
        get() = when (type) {
            RunStepType.TIME_TRIAL -> 101.0
            RunStepType.STRIDES -> 88.0 // too short for heart rate to rise much
            else -> (zoneLow.lowPct.coerceAtLeast(75) + (zoneHigh.highPct ?: 110)) / 2.0
        }
}

enum class RunKind { EASY, LONG, STRIDES, HILLS, TEMPO, THRESHOLD, LTHR_TEST }

data class RunWorkout(
    val code: String,
    val name: String,
    val purpose: String,
    val kind: RunKind,
    val steps: List<RunStep>,
) {
    val durationSec: Int get() = steps.sumOf { it.totalDurationSec }
    val durationMin: Int get() = durationSec / 60

    /** Counts towards the "hard days" limit and is kept away from other hard days. */
    val isHard: Boolean get() = kind == RunKind.TEMPO || kind == RunKind.THRESHOLD || kind == RunKind.LTHR_TEST
}

/** Weekly run time. 2–4 hours suits a triathlete also riding 3–5 hours. */
enum class RunHours(val hours: Int) {
    TWO(2), THREE(3), FOUR(4);

    companion object {
        fun of(hours: Int): RunHours = entries.first { it.hours == hours }
    }
}

/** The role a run plays in the week. Also its priority order when there aren't enough run days. */
enum class RunRole { LONG, QUALITY, EASY, STRIDES }

enum class RunBlock(val displayName: String) {
    BASE("Aerobic base"),
    TEMPO("Tempo"),
    THRESHOLD("Threshold"),
    RACE("Race-specific"),
}

data class RunPlanRequest(
    val hours: RunHours,
    /** Days the athlete can run. */
    val runDays: Set<DayOfWeek>,
    /** Where they'd like the long run, if anywhere in particular. */
    val preferredLongRunDay: DayOfWeek? = null,
)

data class PlannedRun(
    /** Stable id, e.g. "w03-RUN_LONG". Distinct from bike ids, so both share one completions list. */
    val id: String,
    val date: LocalDate,
    val weekIndex: Int,
    val role: RunRole,
    val workout: RunWorkout,
)
