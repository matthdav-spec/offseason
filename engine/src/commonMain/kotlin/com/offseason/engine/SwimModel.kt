package com.offseason.engine

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * The swim side of the plan, for an average age-group triathlete (generic, not coach-tailored).
 *
 * Targets are pace per 100 m relative to CSS (Critical Swim Speed): the pace you could roughly
 * hold for a hard 1500 m. CSS comes from two timed swims, 400 m and 200 m:
 *   CSS (sec/100 m) = (T400 − T200) / 2
 * Athletes who can't yet swim 400 m continuously follow a "build-up" route with no test,
 * where sets are by effort and rest is as needed.
 */

/** Pace zones as seconds per 100 m slower (+) or faster (−) than CSS. */
enum class SwimZone(val label: String, val displayName: String, val offsetSec: Int, val feel: String) {
    EASY("Easy", "Easy / recovery", 15, "Relaxed. Focus on long, smooth strokes"),
    STEADY("Steady", "Endurance", 8, "Comfortable. Could keep going a long time"),
    TEMPO("Tempo", "Tempo", 4, "Working. Breathing harder but in control"),
    CSS("CSS", "Threshold (CSS)", 0, "Hard but sustainable. Race-pace effort"),
    FAST("Fast", "Fast", -4, "Quick. Short efforts, full recovery");

    /** Target pace (sec/100 m) for an athlete's CSS. */
    fun paceSec(cssSec: Int): Int = cssSec + offsetSec
}

object Css {
    /** CSS pace in seconds per 100 m from the 400 m and 200 m times (seconds). */
    fun fromTest(t400Sec: Int, t200Sec: Int): Int {
        require(t400Sec > t200Sec) { "The 400 m time must be slower than the 200 m time" }
        return ((t400Sec - t200Sec) / 2.0).roundToInt()
    }

    /** 95 → "1:35". */
    fun format(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    /** "1:35" or "95" → 95. Null if it can't be read. */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.contains(':')) {
            val (m, s) = t.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: return null) }
            if (s !in 0..59) return null
            return m * 60 + s
        }
        return t.toIntOrNull()
    }

    /** "1:42/100 m" for an athlete's CSS, or the zone name if CSS isn't known. */
    fun describe(zone: SwimZone, cssSec: Int?): String =
        cssSec?.let { "${format(zone.paceSec(it))}/100 m" } ?: zone.displayName
}

enum class SwimStepType { WARMUP, DRILLS, SWIM, REPEATS, PULL, KICK, COOLDOWN, TEST }

/**
 * One block of a pool session.
 * - SWIM / WARMUP / COOLDOWN / PULL / KICK: swim [distanceM] at [zone].
 * - REPEATS: [repeat] × [distanceM] at [zone], with [restSec] rest after each.
 * - DRILLS: [repeat] × [distanceM] of the named drill ([label]), easy, rest as needed.
 * - TEST: a timed, all-out-but-even swim ([label] says which).
 */
data class SwimStep(
    val type: SwimStepType,
    val distanceM: Int,
    val zone: SwimZone,
    val repeat: Int = 1,
    val restSec: Int = 0,
    val label: String? = null,
) {
    init {
        require(distanceM > 0 && repeat > 0)
    }

    val totalDistanceM: Int get() = distanceM * repeat

    /** Rough time for this block: swimming at the zone pace plus rest. */
    fun estimatedSec(cssSec: Int): Int {
        val pace = when (type) {
            SwimStepType.DRILLS -> zone.paceSec(cssSec) + 20 // drills are slower than swimming
            SwimStepType.KICK -> zone.paceSec(cssSec) + 40
            else -> zone.paceSec(cssSec)
        }
        return (totalDistanceM / 100.0 * pace).roundToInt() + restSec * repeat
    }
}

enum class SwimKind { TECHNIQUE, ENDURANCE, CSS_SET, BUILD_UP, CSS_TEST }

data class SwimWorkout(
    val code: String,
    val name: String,
    val purpose: String,
    val kind: SwimKind,
    val steps: List<SwimStep>,
) {
    val distanceM: Int get() = steps.sumOf { it.totalDistanceM }

    /** Estimated duration in minutes, using the athlete's CSS (or a typical age-grouper's if unknown). */
    fun estimatedMin(cssSec: Int? = null): Int = steps.sumOf { it.estimatedSec(cssSec ?: SwimLibrary.TYPICAL_CSS_SEC) } / 60

    /** Counts towards the weekly hard-day limit. */
    val isHard: Boolean get() = kind == SwimKind.CSS_SET || kind == SwimKind.CSS_TEST
}

/** Where the athlete is with swimming. */
enum class SwimLevel {
    /** Can't yet swim 400 m continuously: sets by effort, no CSS test. */
    BUILD_UP,

    /** Can swim 400 m+ continuously: CSS-based plan. */
    CSS,
}

/** The role a swim plays in the week, in priority order when days are short. */
enum class SwimRole { MAIN, TECHNIQUE, EXTRA }

data class SwimPlanRequest(
    val swimsPerWeek: Int,
    val swimDays: Set<DayOfWeek>,
    val level: SwimLevel,
    /** Pool length in metres (25 or 50). Set distances are rounded to whole lengths. */
    val poolLengthM: Int = 25,
    /** Build-up route: how far the athlete can swim without stopping today (0 = not sure). Sets the starting point. */
    val startDistanceM: Int = 0,
    /** Build-up route: the athlete moved up to the CSS plan on this date. Weeks starting on or after it use the CSS route. */
    val cssFrom: LocalDate? = null,
) {
    init {
        require(swimsPerWeek in 1..3)
        require(poolLengthM == 25 || poolLengthM == 50)
    }
}

data class PlannedSwim(
    val id: String,
    val date: LocalDate,
    val weekIndex: Int,
    val role: SwimRole,
    val workout: SwimWorkout,
)
