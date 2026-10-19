package com.offseason.engine

import kotlinx.datetime.DayOfWeek

/**
 * Places a week's bike sessions AND runs together, for athletes using both plans.
 * (Bike-only plans still use [Scheduler], so the original pilot behaves exactly as before.)
 *
 * Rules, as penalties (bigger = worse):
 *  - at most [MAX_HARD_DAYS] hard days a week across both sports
 *  - no two hard days back to back, whichever sport
 *  - avoid two hard sessions on the same day
 *  - the long ride and long run go on different days, and hard sessions stay off those days
 *  - be fresh for the long sessions: avoid a hard session the day before
 *  - long sessions prefer the weekend (or the athlete's preferred long-run day)
 *  - tests go late in the recovery week
 *
 * Done in two passes to stay fast: first the hard and long sessions are placed by trying every
 * combination; then the easy ones fill the remaining days.
 */
object JointScheduler {
    const val MAX_HARD_DAYS = 3

    const val TOO_MANY_HARD_DAYS = 200
    const val BACK_TO_BACK_HARD = 100
    const val TWO_HARD_SAME_DAY = 80
    const val LONGS_SAME_DAY = 60
    const val HARD_ON_LONG_DAY = 60
    const val HARD_DAY_BEFORE_LONG = 20
    const val HARD_DAY_AFTER_LONG = 8
    const val NOT_PREFERRED_LONG_RUN_DAY = 8
    const val LONG_ON_WEEKDAY = 3
    const val TEST_PER_DAY_EARLY = 2
    const val B_BEFORE_A = 1
    const val HARD_BIKE_ON_SWIM_DAY = 1
    const val EASY_RUN_ON_HARD_DAY = 2
    const val EASY_RUN_DAY_AFTER_LONG_RUN = 1

    data class BikeSlot(val role: SlotRole, val isHard: Boolean, val isTest: Boolean = false)
    data class RunSlot(val role: RunRole, val isHard: Boolean, val isTest: Boolean = false)

    data class Placement(val bike: Map<SlotRole, DayOfWeek>, val run: Map<RunRole, DayOfWeek>)

    private data class Item(val bike: Boolean, val key: Any, val hard: Boolean, val long: Boolean, val test: Boolean)

    private val cache = HashMap<String, Placement>()

    /** Slots must be given in priority order; if a sport has too few days, its lowest-priority slots are dropped. */
    fun place(
        bikeSlots: List<BikeSlot>,
        runSlots: List<RunSlot>,
        schedule: AthleteSchedule,
        runDays: Set<DayOfWeek>,
        preferredLongRunDay: DayOfWeek?,
        weekStart: DayOfWeek,
    ): Placement {
        val bikeDayList = schedule.bikeDays.sortedBy { Scheduler.offset(weekStart, it) }
        val runDayList = runDays.sortedBy { Scheduler.offset(weekStart, it) }
        val bikes = bikeSlots.take(bikeDayList.size)
        val runs = runSlots.take(runDayList.size)
        val key = listOf(bikes, runs, bikeDayList, runDayList, schedule.swimDays.sorted(), preferredLongRunDay, weekStart).toString()
        cache[key]?.let { return it }

        val bikeItems = bikes.map { Item(true, it.role, it.isHard, it.role == SlotRole.LONG, it.isTest) }
        val runItems = runs.map { Item(false, it.role, it.isHard, it.role == RunRole.LONG, it.isTest) }
        val (bikeCore, bikeFill) = bikeItems.partition { it.hard || it.long }
        val (runCore, runFill) = runItems.partition { it.hard || it.long }

        // Pass 1: hard and long sessions.
        var bestCore: Map<Item, DayOfWeek> = emptyMap()
        var bestScore = Int.MAX_VALUE
        choose(bikeDayList, bikeCore.size) { bd ->
            choose(runDayList, runCore.size) { rd ->
                val assign = bikeCore.zip(bd).toMap() + runCore.zip(rd).toMap()
                val s = coreScore(assign, schedule, preferredLongRunDay, weekStart)
                if (s < bestScore) { bestScore = s; bestCore = assign }
            }
        }

        // Pass 2: easy sessions on the days left for each sport.
        val freeBike = bikeDayList - bestCore.filterKeys { it.bike }.values.toSet()
        val freeRun = runDayList - bestCore.filterKeys { !it.bike }.values.toSet()
        var bestAll = bestCore
        var bestFill = Int.MAX_VALUE
        choose(freeBike, bikeFill.size) { bd ->
            choose(freeRun, runFill.size) { rd ->
                val assign = bestCore + bikeFill.zip(bd).toMap() + runFill.zip(rd).toMap()
                val s = fillScore(assign)
                if (s < bestFill) { bestFill = s; bestAll = assign }
            }
        }

        val result = Placement(
            bike = bestAll.filterKeys { it.bike }.mapKeys { it.key.key as SlotRole },
            run = bestAll.filterKeys { !it.bike }.mapKeys { it.key.key as RunRole },
        )
        cache[key] = result
        return result
    }

    private fun coreScore(
        a: Map<Item, DayOfWeek>,
        schedule: AthleteSchedule,
        preferredLongRunDay: DayOfWeek?,
        weekStart: DayOfWeek,
    ): Int {
        var score = 0
        val hardItems = a.filterKeys { it.hard }
        val hardDays = hardItems.values.toList()
        val distinctHard = hardDays.toSet()

        score += (hardDays.size - distinctHard.size) * TWO_HARD_SAME_DAY
        if (distinctHard.size > MAX_HARD_DAYS) score += (distinctHard.size - MAX_HARD_DAYS) * TOO_MANY_HARD_DAYS
        val hd = distinctHard.toList()
        for (i in hd.indices) for (j in i + 1 until hd.size) if (Scheduler.adjacent(hd[i], hd[j])) score += BACK_TO_BACK_HARD

        val longRide = a.entries.firstOrNull { it.key.bike && it.key.long }?.value
        val longRun = a.entries.firstOrNull { !it.key.bike && it.key.long }?.value
        if (longRide != null && longRide == longRun) score += LONGS_SAME_DAY
        for (long in listOfNotNull(longRide, longRun)) {
            if (long in distinctHard) score += HARD_ON_LONG_DAY
            if (distinctHard.any { dayBefore(it, long) }) score += HARD_DAY_BEFORE_LONG
            if (distinctHard.any { dayBefore(long, it) }) score += HARD_DAY_AFTER_LONG
            if (long != DayOfWeek.SATURDAY && long != DayOfWeek.SUNDAY) score += LONG_ON_WEEKDAY
        }
        if (preferredLongRunDay != null && longRun != null && longRun != preferredLongRunDay) score += NOT_PREFERRED_LONG_RUN_DAY

        for ((item, day) in a) {
            if (item.test) score += (6 - Scheduler.offset(weekStart, day)) * TEST_PER_DAY_EARLY
            if (item.bike && item.hard && day in schedule.swimDays) score += HARD_BIKE_ON_SWIM_DAY
        }

        val aDay = a.entries.firstOrNull { it.key.bike && it.key.key == SlotRole.INTERVALS_A }
        val bDay = a.entries.firstOrNull { it.key.bike && it.key.key == SlotRole.INTERVALS_B }
        if (aDay != null && bDay != null && !aDay.key.test && !bDay.key.test &&
            Scheduler.offset(weekStart, bDay.value) < Scheduler.offset(weekStart, aDay.value)
        ) score += B_BEFORE_A
        return score
    }

    private fun fillScore(a: Map<Item, DayOfWeek>): Int {
        var score = 0
        val hardDays = a.filterKeys { it.hard }.values.toSet()
        val longRun = a.entries.firstOrNull { !it.key.bike && it.key.long }?.value
        for ((item, day) in a) if (!item.bike && !item.hard && !item.long) {
            if (day in hardDays) score += EASY_RUN_ON_HARD_DAY
            if (longRun != null && dayBefore(longRun, day)) score += EASY_RUN_DAY_AFTER_LONG_RUN
        }
        return score
    }

    /** True if [a] is the day immediately before [b] (the week wraps). */
    private fun dayBefore(a: DayOfWeek, b: DayOfWeek) = (b.isoNumber - a.isoNumber + 7) % 7 == 1

    /** Calls [visit] with every ordered choice of [k] distinct days. */
    private fun choose(items: List<DayOfWeek>, k: Int, visit: (List<DayOfWeek>) -> Unit) {
        if (k == 0) { visit(emptyList()); return }
        val chosen = ArrayList<DayOfWeek>(k)
        val used = BooleanArray(items.size)
        fun go() {
            if (chosen.size == k) { visit(chosen.toList()); return }
            for (i in items.indices) if (!used[i]) {
                used[i] = true; chosen.add(items[i])
                go()
                chosen.removeAt(chosen.size - 1); used[i] = false
            }
        }
        go()
    }
}
