package com.offseason.engine

import kotlinx.datetime.DayOfWeek

/**
 * Places a week's sessions on the athlete's chosen bike days, following the triathlete rules:
 *  - no back-to-back hard bike days
 *  - keep hard bike sessions off, and away from, hard-run and long-run days
 *  - prefer the long ride at the weekend, away from the long run
 *  - FTP retests go as late in the (recovery) week as possible
 *
 * The weekly space is small (at most 7 days × 4 sessions), so we try every assignment and
 * pick the one with the lowest penalty score. This is simple, deterministic and easy to test.
 * If there are fewer bike days than sessions, the lowest-priority sessions are dropped
 * (priority: intervals A, intervals B, long ride, extra).
 */
object Scheduler {

    // Penalty weights: bigger = worse. Tunable.
    const val BACK_TO_BACK_HARD_BIKE = 100
    const val HARD_BIKE_SAME_DAY_AS_KEY_RUN = 60
    const val HARD_BIKE_NEXT_TO_KEY_RUN = 25
    const val LONG_RIDE_SAME_DAY_AS_LONG_RUN = 30
    const val LONG_RIDE_NEXT_TO_HARD_BIKE = 4
    const val LONG_RIDE_ON_WEEKDAY = 3
    const val HARD_BIKE_SAME_DAY_AS_SWIM = 1
    const val B_BEFORE_A = 1
    const val TEST_PER_DAY_EARLY = 2

    data class Slot(val role: SlotRole, val isHard: Boolean, val isTest: Boolean = false)

    /**
     * @return role → day for each placed slot. Slots that don't fit are left out.
     * Slots must be given in priority order.
     */
    fun place(slots: List<Slot>, schedule: AthleteSchedule, weekStart: DayOfWeek): Map<SlotRole, DayOfWeek> {
        val days = schedule.bikeDays.sortedBy { offset(weekStart, it) }
        val kept = slots.take(days.size)
        if (kept.isEmpty()) return emptyMap()

        var best: List<DayOfWeek>? = null
        var bestScore = Int.MAX_VALUE
        permutations(days, kept.size) { candidate ->
            val s = score(kept, candidate, schedule, weekStart)
            if (s < bestScore) {
                bestScore = s
                best = candidate.toList()
            }
        }
        return kept.map { it.role }.zip(best!!).toMap()
    }

    fun score(slots: List<Slot>, days: List<DayOfWeek>, schedule: AthleteSchedule, weekStart: DayOfWeek): Int {
        var score = 0
        val keyRuns = schedule.hardRunOrLongRunDays
        val hardDays = slots.indices.filter { slots[it].isHard }.map { days[it] }

        // Hard bike next to hard bike. The week wraps around (Sunday is next to the following Monday).
        for (i in hardDays.indices) for (j in i + 1 until hardDays.size) {
            if (adjacent(hardDays[i], hardDays[j])) score += BACK_TO_BACK_HARD_BIKE
        }
        for (d in hardDays) {
            if (d in keyRuns) score += HARD_BIKE_SAME_DAY_AS_KEY_RUN
            score += keyRuns.count { adjacent(it, d) } * HARD_BIKE_NEXT_TO_KEY_RUN
            if (d in schedule.swimDays) score += HARD_BIKE_SAME_DAY_AS_SWIM
        }

        slots.forEachIndexed { i, slot ->
            val day = days[i]
            when (slot.role) {
                SlotRole.LONG -> if (!slot.isHard) {
                    if (day == schedule.longRunDay) score += LONG_RIDE_SAME_DAY_AS_LONG_RUN
                    if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) score += LONG_RIDE_ON_WEEKDAY
                    score += hardDays.count { adjacent(it, day) } * LONG_RIDE_NEXT_TO_HARD_BIKE
                }
                else -> {}
            }
            if (slot.isTest) score += (6 - offset(weekStart, day)) * TEST_PER_DAY_EARLY
        }

        val a = slots.indexOfFirst { it.role == SlotRole.INTERVALS_A }
        val b = slots.indexOfFirst { it.role == SlotRole.INTERVALS_B }
        if (a >= 0 && b >= 0 && !slots[a].isTest && !slots[b].isTest &&
            offset(weekStart, days[b]) < offset(weekStart, days[a])
        ) score += B_BEFORE_A
        return score
    }

    /** Days since [weekStart], 0..6. */
    fun offset(weekStart: DayOfWeek, day: DayOfWeek): Int = (day.isoNumber - weekStart.isoNumber + 7) % 7

    fun adjacent(a: DayOfWeek, b: DayOfWeek): Boolean {
        val diff = (a.isoNumber - b.isoNumber + 7) % 7
        return diff == 1 || diff == 6
    }

    private fun permutations(items: List<DayOfWeek>, k: Int, visit: (List<DayOfWeek>) -> Unit) {
        val chosen = ArrayList<DayOfWeek>(k)
        val used = BooleanArray(items.size)
        fun go() {
            if (chosen.size == k) { visit(chosen); return }
            for (i in items.indices) if (!used[i]) {
                used[i] = true; chosen.add(items[i])
                go()
                chosen.removeAt(chosen.size - 1); used[i] = false
            }
        }
        go()
    }
}
