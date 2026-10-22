package com.offseason.engine

import com.offseason.engine.RunStepType.COOLDOWN
import com.offseason.engine.RunStepType.INTERVALS
import com.offseason.engine.RunStepType.STEADY
import com.offseason.engine.RunStepType.STRIDES
import com.offseason.engine.RunStepType.TIME_TRIAL
import com.offseason.engine.RunStepType.WARMUP
import com.offseason.engine.RunZone.Z1
import com.offseason.engine.RunZone.Z2
import com.offseason.engine.RunZone.Z3
import com.offseason.engine.RunZone.Z4
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Every run in the programme: original sessions built on standard heart-rate training concepts.
 *
 * Winter goal: aerobic base + threshold. Most running is easy (Z1–Z2) to build durability
 * without stealing recovery from the bike; one quality run a week does the specific work.
 *
 * Safety rules built in (and tested):
 *  - weekly run time rises by no more than [MAX_WEEKLY_INCREASE] from one build week to the next
 *  - the long run is never more than a third of the week's running
 *  - recovery weeks cut run time to [RECOVERY_VOLUME_FACTOR] and end with the LTHR test
 */
object RunLibrary {
    const val MAX_WEEKLY_INCREASE = 0.10
    const val RECOVERY_VOLUME_FACTOR = 0.6
    const val LONG_RUN_MAX_SHARE = 1.0 / 3
    private const val MIN_RUN_MIN = 20

    val BLOCK_ORDER = listOf(RunBlock.BASE, RunBlock.TEMPO, RunBlock.THRESHOLD, RunBlock.RACE)

    /** Build-week volume as a fraction of the chosen hours: a gentle ramp in the first block. */
    fun weekFactor(blockIndex: Int, progressionWeek: Int): Double =
        if (blockIndex == 0) listOf(0.85, 0.9, 0.95)[progressionWeek - 1] else listOf(0.9, 0.95, 1.0)[progressionWeek - 1]

    fun rolesFor(hours: RunHours): List<RunRole> = when (hours) {
        RunHours.TWO -> listOf(RunRole.LONG, RunRole.QUALITY, RunRole.EASY)
        RunHours.THREE, RunHours.FOUR -> listOf(RunRole.LONG, RunRole.QUALITY, RunRole.EASY, RunRole.STRIDES)
    }

    /**
     * The week's runs by role.
     * @param progressionWeek 1..3 for build weeks; ignored in recovery weeks.
     */
    fun week(hours: RunHours, blockIndex: Int, progressionWeek: Int, isRecovery: Boolean): Map<RunRole, RunWorkout> {
        val block = BLOCK_ORDER[blockIndex]
        val roles = rolesFor(hours)
        val prefix = "R${blockIndex + 1}" + (if (isRecovery) "R" else "W$progressionWeek")
        val target = hours.hours * 60 * (if (isRecovery) RECOVERY_VOLUME_FACTOR else weekFactor(blockIndex, progressionWeek))

        val quality = if (isRecovery) lthrTest("$prefix-Q") else qualityRun("$prefix-Q", block, progressionWeek, (target * 0.3).roundToInt())

        // Share what's left between the other runs; the long run is capped at a third of the week.
        val rest = (target - quality.durationMin).coerceAtLeast(MIN_RUN_MIN * (roles.size - 1).toDouble())
        val longShare = if (roles.size == 3) 0.55 else 0.45
        val longMin = round5(minOf(rest * longShare, target * LONG_RUN_MAX_SHARE)).coerceAtLeast(MIN_RUN_MIN)
        val others = roles.filter { it == RunRole.EASY || it == RunRole.STRIDES }
        val eachOther = round5((rest - longMin) / others.size).coerceAtLeast(MIN_RUN_MIN)

        return roles.associateWith { role ->
            when (role) {
                RunRole.QUALITY -> quality
                RunRole.LONG -> longRun("$prefix-L", longMin, isRecovery)
                RunRole.EASY -> easyRun("$prefix-E", eachOther, isRecovery)
                RunRole.STRIDES -> stridesRun("$prefix-S", eachOther, isRecovery)
            }
        }
    }

    private fun round5(x: Double) = ((x / 5).roundToInt() * 5)

    // ---- Quality run: the one specific session each week ----

    private fun qualityRun(code: String, block: RunBlock, w: Int, budgetMin: Int): RunWorkout {
        val i = w - 1
        return when (block) {
            RunBlock.BASE -> {
                val reps = listOf(6, 8, 10)[i]
                build(
                    code, "Hill strides ×$reps", RunKind.HILLS, budgetMin,
                    "Easy running with short uphill efforts. They build leg strength and running form with very little fatigue, which is ideal groundwork for winter.",
                    listOf(RunStep(STRIDES, 30, Z1, Z2, repeat = reps, offDurationSec = 90, label = "Hill strides: 30 s strong uphill, jog back down")),
                )
            }
            RunBlock.TEMPO -> {
                val (reps, min) = listOf(2 to 10, 2 to 12, 2 to 15)[i]
                build(
                    code, "Tempo $reps×$min", RunKind.TEMPO, budgetMin,
                    "Steady running at a pace you could hold for well over an hour. It raises your aerobic engine and teaches you to run efficiently when tired.",
                    listOf(RunStep(INTERVALS, min * 60, Z3, repeat = reps, offDurationSec = 180, label = "3 min easy jog between")),
                )
            }
            RunBlock.THRESHOLD -> {
                val (reps, min) = listOf(3 to 8, 4 to 8, 2 to 15)[i]
                build(
                    code, "Threshold $reps×$min", RunKind.THRESHOLD, budgetMin,
                    "Efforts just below your threshold heart rate. This is what lifts the pace you can hold off the bike, without the injury risk of fast track work.",
                    listOf(RunStep(INTERVALS, min * 60, Z4, repeat = reps, offDurationSec = 180, label = "3 min easy jog between")),
                )
            }
            RunBlock.RACE -> {
                val main = listOf(
                    listOf(RunStep(INTERVALS, 10 * 60, Z4, repeat = 2, offDurationSec = 180), RunStep(STEADY, 10 * 60, Z3, label = "Settle into race rhythm")),
                    listOf(RunStep(INTERVALS, 12 * 60, Z4, repeat = 2, offDurationSec = 180), RunStep(STEADY, 12 * 60, Z3, label = "Settle into race rhythm")),
                    listOf(RunStep(STEADY, 15 * 60, Z3), RunStep(STEADY, 15 * 60, Z4, label = "Build to sub-threshold")),
                )[i]
                build(
                    code, "Threshold + tempo", RunKind.THRESHOLD, budgetMin,
                    "Sub-threshold running followed by steady tempo. It's the feel of the run in a middle-distance triathlon, practised on fresh legs first.",
                    main,
                )
            }
        }
    }

    // ---- The rest of the week ----

    private fun longRun(code: String, minutes: Int, isRecovery: Boolean): RunWorkout = RunWorkout(
        code, if (isRecovery) "Easy long run" else "Long run",
        if (isRecovery) "A shorter long run, all easy. Recovery week means less volume so your body absorbs the last few weeks."
        else "Your most important run for endurance. Keep it truly easy, and hold back on the hills. The benefit comes from the time on your feet, not the pace.",
        RunKind.LONG,
        buildList {
            add(RunStep(STEADY, minutes * 60, Z1, Z2, label = "Easy and conversational throughout"))
        },
    )

    private fun easyRun(code: String, minutes: Int, isRecovery: Boolean) = RunWorkout(
        code, "Easy run",
        if (isRecovery) "Easy running to keep your legs moving while you recover." else "Relaxed aerobic running. Easy days are what let the hard days work, so keep it genuinely easy.",
        RunKind.EASY,
        listOf(RunStep(STEADY, minutes * 60, Z1, Z2, label = "Easy. You should be able to chat")),
    )

    private fun stridesRun(code: String, minutes: Int, isRecovery: Boolean): RunWorkout {
        val strides = if (isRecovery) 4 else 6
        val stridesMin = strides * 2
        val easy = (minutes - stridesMin - 5).coerceAtLeast(10)
        return RunWorkout(
            code, "Easy run + strides",
            "An easy run with a few short, fast strides at the end. They keep your running smooth and quick without being hard work.",
            RunKind.STRIDES,
            listOf(
                RunStep(STEADY, easy * 60, Z1, Z2, label = "Easy"),
                RunStep(STRIDES, 20, Z1, Z2, repeat = strides, offDurationSec = 100, label = "Strides: 20 s quick and relaxed, walk or jog back"),
                RunStep(COOLDOWN, 5 * 60, Z1, label = "Easy jog"),
            ),
        )
    }

    // ---- LTHR test: 30-minute solo time trial ----

    fun lthrTest(code: String = "LTHR-TEST") = RunWorkout(
        code, "LTHR test (30 min)",
        "A 30-minute solo time trial to find your lactate threshold heart rate (LTHR). Your LTHR is your average heart rate over the last 20 minutes. Every run zone updates automatically.",
        RunKind.LTHR_TEST,
        listOf(
            RunStep(WARMUP, 15 * 60, Z1, Z2, label = "Easy warm-up, with a few short pick-ups at the end"),
            RunStep(TIME_TRIAL, 30 * 60, RunZone.Z5A, label = "30 min as hard as you can hold, like a race. Press LAP at 10 minutes"),
            RunStep(COOLDOWN, 10 * 60, Z1, label = "Easy jog or walk"),
        ),
    )

    // ---- Builder ----

    /** Warm-up, main set (cut back if it won't fit the time), easy running to fill, cool-down. */
    private fun build(code: String, name: String, kind: RunKind, budgetMin: Int, purpose: String, main: List<RunStep>): RunWorkout {
        val warm = 12
        val cool = 8
        var set = main
        // On lower-volume plans, drop a rep rather than make the session too long for the week.
        while (warm + cool + set.sumOf { it.totalDurationSec } / 60 > budgetMin + 10 && set.any { (it.repeat ?: 1) > 1 }) {
            set = set.map { if ((it.repeat ?: 1) > 1 && it.type == INTERVALS) it.copy(repeat = it.repeat!! - 1) else it }
            if (set.none { it.type == INTERVALS && (it.repeat ?: 1) > 1 }) break
        }
        val mainMin = set.sumOf { it.totalDurationSec } / 60
        val fill = budgetMin - warm - cool - mainMin
        val steps = buildList {
            add(RunStep(WARMUP, warm * 60, Z1, Z2, label = "Easy jog, building gradually"))
            addAll(set)
            if (fill >= 5) add(RunStep(STEADY, fill * 60, Z1, Z2, label = "Easy"))
            add(RunStep(COOLDOWN, cool * 60, Z1, label = "Easy jog"))
        }
        val actualName = set.firstOrNull { it.type == INTERVALS }?.let { iv ->
            if (iv.repeat != main.first { it.type == INTERVALS }.repeat) name.replace(Regex("\\d+×"), "${iv.repeat}×") else name
        } ?: name
        return RunWorkout(code, actualName, purpose, kind, steps)
    }
}

/** Run load, on the same scale as bike Session Load (1 hour at threshold = 100). */
object RunLoad {
    /** Effort Score from heart rate: a weighted average of % LTHR. */
    fun effortScore(w: RunWorkout): Double {
        var sum2 = 0.0
        var secs = 0
        for (s in w.steps) {
            val t = s.totalDurationSec
            sum2 += (s.targetPct / 100).pow(2) * t
            secs += t
        }
        return if (secs == 0) 0.0 else sqrt(sum2 / secs)
    }

    fun sessionLoad(w: RunWorkout): Double = Load.sessionLoad(w.durationSec, effortScore(w))
}
