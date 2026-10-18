package com.offseason.engine

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Adjusting the plan from how sessions felt (section 5.8 of the brief).
 * Repeated "Hard" or "Couldn't finish" ratings on interval sessions → offer to
 * lower targets a little, or repeat the week.
 */
object Adaptation {
    enum class Feel { EASY, ABOUT_RIGHT, HARD, COULDNT_FINISH }

    /** How much "Lower my targets" takes off, in % of FTP. Tunable. */
    const val EASE_PCT = 4

    /** Never ease more than this in total before a retest resets things. */
    const val MAX_EASE_PCT = 10

    /** How many interval sessions in a row must feel hard before we offer to ease off. */
    const val STRUGGLES_IN_A_ROW = 2

    private fun Feel.struggled() = this == Feel.HARD || this == Feel.COULDNT_FINISH

    /** @param recentIntervalFeels ratings of interval sessions, oldest first. */
    fun shouldOfferEasing(recentIntervalFeels: List<Feel>): Boolean {
        val last = recentIntervalFeels.takeLast(STRUGGLES_IN_A_ROW)
        return last.size == STRUGGLES_IN_A_ROW && last.all { it.struggled() }
    }

    /**
     * Scales every power target of sessions on or after [fromDate] by (100 + [pct])%.
     * FTP tests are never scaled: they measure, they don't train.
     */
    fun applyTargetAdjustment(plan: Plan, fromDate: LocalDate, pct: Int): Plan {
        if (pct == 0) return plan
        val factor = (100 + pct) / 100.0
        return plan.copy(
            weeks = plan.weeks.map { w ->
                w.copy(sessions = w.sessions.map { s ->
                    if (s.date.isBefore(fromDate) || s.workout.kind == SessionKind.FTP_TEST) s
                    else s.copy(workout = s.workout.scaled(factor))
                })
            },
        )
    }

    fun Workout.scaled(factor: Double): Workout = copy(
        targetScale = targetScale * factor,
        steps = steps.map { st ->
            if (st.type == StepType.FREE_RIDE) st
            else st.copy(
                powerPctLow = (st.powerPctLow * factor).roundToInt(),
                powerPctHigh = st.powerPctHigh?.let { (it * factor).roundToInt() },
                offPowerPct = st.offPowerPct?.let { (it * factor).roundToInt() },
            )
        },
    )
}

/**
 * "Repeat this week": inserts a copy of a build week straight after it and pushes everything
 * after it back a week. Session ids of the original weeks never change, so completions stay
 * attached to the right sessions. Repeated copies get ids like "r0-w02-INTERVALS_A".
 */
object WeekRepeats {
    /** @param repeatedWeeks original week indexes, in the order the athlete chose to repeat them. */
    fun apply(plan: Plan, repeatedWeeks: List<Int>): Plan {
        if (repeatedWeeks.isEmpty()) return plan
        val out = mutableListOf<PlanWeek>()
        val start = plan.startDate
        for (w in plan.weeks) {
            out += shift(w, out.size, start, idPrefix = "")
            repeatedWeeks.forEachIndexed { n, src ->
                if (src == w.sourceIndex) out += shift(w, out.size, start, idPrefix = "r$n-", isRepeat = true)
            }
        }
        return plan.copy(weeks = out)
    }

    private fun shift(w: PlanWeek, newIndex: Int, planStart: java.time.LocalDate, idPrefix: String, isRepeat: Boolean = false): PlanWeek {
        val newStart = planStart.plusWeeks(newIndex.toLong())
        val days = java.time.temporal.ChronoUnit.DAYS.between(w.startDate, newStart)
        return w.copy(
            index = newIndex,
            startDate = newStart,
            isRepeat = isRepeat || w.isRepeat,
            sessions = w.sessions.map { s ->
                s.copy(id = idPrefix + s.id, date = s.date.plusDays(days), weekIndex = newIndex)
            },
        )
    }

    /** Recovery weeks and weeks with a retest can't be repeated: just carry on. */
    fun canRepeat(week: PlanWeek): Boolean = !week.isRecovery && !week.hasRetest
}
