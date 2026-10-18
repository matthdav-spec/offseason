package com.offseason.engine

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Training load, using our own names (see section 5.4 of the brief):
 *  - Effort Score = weighted power ÷ FTP
 *  - Session Load = hours × Effort Score² × 100 (1 hour at FTP = 100)
 *  - Fitness = 42-day exponentially weighted average of daily Session Load
 *  - Fatigue = 7-day exponentially weighted average
 *  - Freshness = Fitness − Fatigue
 */
object Load {
    const val FITNESS_DAYS = 42
    const val FATIGUE_DAYS = 7

    /** Tunable: the most Fitness should rise in a week before we call it overreaching. */
    const val MAX_WEEKLY_FITNESS_RISE = 5.0

    /**
     * Effort Score for a planned workout, from its structure. Weighted power is the
     * 4th-power mean of second-by-second power; ramps are sampled every second.
     */
    fun effortScore(workout: Workout): Double {
        var sum4 = 0.0
        var seconds = 0
        fun add(pct: Double, secs: Int) {
            sum4 += (pct / 100.0).pow(4) * secs
            seconds += secs
        }
        for (step in workout.steps) when (step.type) {
            StepType.WARMUP, StepType.COOLDOWN, StepType.RAMP -> {
                val from = step.powerPctLow.toDouble()
                val to = (step.powerPctHigh ?: step.powerPctLow).toDouble()
                for (s in 0 until step.durationSec) add(from + (to - from) * (s + 0.5) / step.durationSec, 1)
            }
            StepType.INTERVALS -> repeat(step.repeat!!) {
                add(step.targetPct, step.durationSec)
                add(step.offPowerPct!!.toDouble(), step.offDurationSec!!)
            }
            StepType.STEADY, StepType.FREE_RIDE -> add(step.targetPct, step.durationSec)
        }
        return if (seconds == 0) 0.0 else sqrt(sqrt(sum4 / seconds))
    }

    fun sessionLoad(durationSec: Int, effortScore: Double): Double = durationSec / 3600.0 * effortScore * effortScore * 100.0

    fun sessionLoad(workout: Workout): Double = sessionLoad(workout.durationSec, effortScore(workout))

    data class DayLoad(val date: LocalDate, val fitness: Double, val fatigue: Double) {
        val freshness: Double get() = fitness - fatigue
    }

    /**
     * Fitness/Fatigue for every day from [from] to [to] inclusive, starting from [startFitness]
     * and [startFatigue] (zero if unknown).
     */
    fun series(
        dailyLoad: Map<LocalDate, Double>,
        from: LocalDate,
        to: LocalDate,
        startFitness: Double = 0.0,
        startFatigue: Double = 0.0,
    ): List<DayLoad> {
        val kFit = 1 - exp(-1.0 / FITNESS_DAYS)
        val kFat = 1 - exp(-1.0 / FATIGUE_DAYS)
        var fit = startFitness
        var fat = startFatigue
        val out = mutableListOf<DayLoad>()
        var d = from
        while (!d.isAfter(to)) {
            val load = dailyLoad[d] ?: 0.0
            fit += (load - fit) * kFit
            fat += (load - fat) * kFat
            out += DayLoad(d, fit, fat)
            d = d.plusDays(1)
        }
        return out
    }

    /** Planned load per day for a plan (optional sessions included). */
    fun plannedDailyLoad(plan: Plan): Map<LocalDate, Double> =
        plan.sessions.groupBy { it.date }.mapValues { (_, s) -> s.sumOf { sessionLoad(it.workout) } }
}

/** Traffic-light status with a plain-English sentence: the anti-chart. */
object Status {
    enum class Light { GREEN, AMBER, RED }

    data class Message(val light: Light, val headline: String, val detail: String)

    /**
     * @param completionRate fraction of past (non-optional) sessions completed, or null before the first session.
     * @param weeklyFitnessRise how much Fitness rose over the last 7 days.
     */
    fun forWeek(
        week: PlanWeek?,
        freshness: Double,
        weeklyFitnessRise: Double,
        completionRate: Double?,
    ): Message = when {
        week == null -> Message(Light.GREEN, "Plan not started yet", "Your first session is coming up. Easy riding until then.")
        week.isRecovery -> Message(
            Light.GREEN, "Recovery week",
            "This week is lighter on purpose so your body can absorb the work. Finish with the FTP retest.",
        )
        weeklyFitnessRise > Load.MAX_WEEKLY_FITNESS_RISE || freshness < -25 -> Message(
            Light.RED, "Carrying a lot of fatigue",
            "You've been building load quickly. Keep the easy rides truly easy, and skip the optional spin.",
        )
        completionRate != null && completionRate < 0.6 -> Message(
            Light.AMBER, "Let's get back on track",
            "You've missed a few sessions. That's normal. Just focus on this week's two interval sessions.",
        )
        freshness < -10 -> Message(
            Light.AMBER, "Building, with some fatigue",
            "That's expected mid-block. Sleep and fuel well, and the recovery week is coming.",
        )
        else -> Message(Light.GREEN, "You're building nicely", "Keep hitting the key sessions and the gains will come.")
    }
}
