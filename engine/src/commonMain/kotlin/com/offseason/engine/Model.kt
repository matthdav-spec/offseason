package com.offseason.engine

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Core data types for the plan engine.
 *
 * All power targets are stored as a percentage of FTP (e.g. 90 = 90% FTP) and only
 * turned into watts at display/export time, so a new FTP updates every target automatically.
 */

enum class StepType { WARMUP, STEADY, INTERVALS, COOLDOWN, RAMP, FREE_RIDE }

/**
 * One block of a workout.
 *
 * - STEADY: hold [powerPctLow]..[powerPctHigh] (or just [powerPctLow]) for [durationSec].
 * - WARMUP / COOLDOWN / RAMP: power changes smoothly from [powerPctLow] (start) to [powerPctHigh] (end).
 * - INTERVALS: [repeat] × ([durationSec] "on" at powerPctLow..powerPctHigh, then [offDurationSec] at [offPowerPct]).
 * - FREE_RIDE: no target (used for all-out test efforts). [powerPctLow] is only an estimate,
 *   used to predict load.
 */
data class Step(
    val type: StepType,
    val durationSec: Int,
    val powerPctLow: Int,
    val powerPctHigh: Int? = null,
    val repeat: Int? = null,
    val offDurationSec: Int? = null,
    val offPowerPct: Int? = null,
    val cadenceTarget: Int? = null,
    val label: String? = null,
) {
    init {
        require(durationSec > 0) { "durationSec must be positive" }
        if (type == StepType.INTERVALS) {
            require((repeat ?: 0) > 0 && (offDurationSec ?: 0) > 0 && offPowerPct != null) {
                "INTERVALS needs repeat, offDurationSec and offPowerPct"
            }
        }
    }

    /** Midpoint of the target range: what ERG mode aims for. */
    val targetPct: Double get() = (powerPctLow + (powerPctHigh ?: powerPctLow)) / 2.0

    val totalDurationSec: Int
        get() = if (type == StepType.INTERVALS) repeat!! * (durationSec + offDurationSec!!) else durationSec
}

enum class SessionKind { INTERVALS, TEMPO, ENDURANCE, EASY, CADENCE, RECOVERY, FTP_TEST }

data class Workout(
    /** Short code, e.g. "B1W2-A". Used in file names. */
    val code: String,
    val name: String,
    /** One or two plain sentences: why you're doing this session. */
    val purpose: String,
    val kind: SessionKind,
    val steps: List<Step>,
    /** 1.0 normally; e.g. 0.96 after "lower my targets by 4%". Used to keep zone names as planned. */
    val targetScale: Double = 1.0,
) {
    /** The zone a step was designed for, ignoring any easing. */
    fun plannedZone(step: Step): Zone = Zone.of(step.targetPct / targetScale)

    val durationSec: Int get() = steps.sumOf { it.totalDurationSec }
    val durationMin: Int get() = durationSec / 60
    val isHard: Boolean get() = kind == SessionKind.INTERVALS || kind == SessionKind.FTP_TEST
}

enum class BlockType(val displayName: String) {
    SWEET_SPOT("Sweet spot"),
    THRESHOLD("Threshold"),
    VO2("VO2max"),
    RACE_SPECIFIC("Race-specific"),
}

enum class HoursBucket(val hours: Int) {
    THREE(3), FOUR(4), FIVE(5);

    companion object {
        fun of(hours: Int): HoursBucket = entries.first { it.hours == hours }
    }
}

/** 4/8/12/16-week options = 1/2/3/4 stacked blocks. */
enum class PlanLength(val blocks: Int) {
    WEEKS_4(1), WEEKS_8(2), WEEKS_12(3), WEEKS_16(4);

    val nominalWeeks: Int get() = blocks * 4

    companion object {
        fun ofWeeks(weeks: Int): PlanLength = entries.first { it.nominalWeeks == weeks }
    }
}

/** 3 build weeks + 1 recovery (default), or 2 + 1 for athletes who recover more slowly. */
enum class RecoveryCycle(val buildWeeks: Int, val label: String) {
    THREE_ONE(3, "3:1"),
    TWO_ONE(2, "2:1");

    val weeksPerBlock: Int get() = buildWeeks + 1
}

/** The athlete's week. Long and hard runs should also appear in [runDays]. */
data class AthleteSchedule(
    val bikeDays: Set<DayOfWeek>,
    val swimDays: Set<DayOfWeek> = emptySet(),
    val runDays: Set<DayOfWeek> = emptySet(),
    val longRunDay: DayOfWeek? = null,
    val hardRunDays: Set<DayOfWeek> = emptySet(),
) {
    /** Days a hard bike session should keep away from. */
    val hardRunOrLongRunDays: Set<DayOfWeek> get() = hardRunDays + setOfNotNull(longRunDay)
}

data class PlanRequest(
    val startDate: LocalDate,
    val length: PlanLength,
    val hours: HoursBucket,
    val recoveryCycle: RecoveryCycle,
    /** With a run plan, [AthleteSchedule.runDays] are the days the athlete CAN run, and its hard/long run days are ignored. */
    val schedule: AthleteSchedule,
    /** Null = no run plan (bike only, as in the original pilot). */
    val run: RunPlanRequest? = null,
    /** Null = no swim plan. A coached swim plan schedules around [AthleteSchedule.swimDays] without planning swims. */
    val swim: SwimPlanRequest? = null,
) {
    val hasBike: Boolean get() = schedule.bikeDays.isNotEmpty()
    val hasRun: Boolean get() = run != null
    /** Swims are planned by the app (not by a coach). */
    val hasSwim: Boolean get() = swim != null && !swim.coached
}

/** The role a session plays in the week. Also its priority order when days are short. */
enum class SlotRole { INTERVALS_A, INTERVALS_B, LONG, EXTRA }

data class PlannedSession(
    /** Stable id, e.g. "w03-INTERVALS_A". Used to record completions. */
    val id: String,
    val date: LocalDate,
    val weekIndex: Int,
    val role: SlotRole,
    val workout: Workout,
    val optional: Boolean,
)

data class PlanWeek(
    /** 0-based week number within the plan. */
    val index: Int,
    val startDate: LocalDate,
    val blockIndex: Int,
    val blockType: BlockType,
    /** 1-based week within the block. The last week of a block is the recovery week. */
    val weekInBlock: Int,
    val isRecovery: Boolean,
    val sessions: List<PlannedSession>,
    /** The week's index in the original plan. Differs from [index] once a week has been repeated. */
    val sourceIndex: Int = index,
    /** True if this is an extra copy of an earlier week, added by "repeat this week". */
    val isRepeat: Boolean = false,
    /** Planned runs this week (empty without a run plan). */
    val runs: List<PlannedRun> = emptyList(),
    val runBlock: RunBlock? = null,
    /** Planned swims this week (empty without a swim plan, or when a coach sets the swims). */
    val swims: List<PlannedSwim> = emptyList(),
) {
    val endDate: LocalDate get() = startDate.plusDays(6)
    val hasRetest: Boolean get() = sessions.any { it.workout.kind == SessionKind.FTP_TEST }
    val hasRunTest: Boolean get() = runs.any { it.workout.kind == RunKind.LTHR_TEST }
    val hasSwimTest: Boolean get() = swims.any { it.workout.kind == SwimKind.CSS_TEST }
    val title: String
        get() = (if (isRecovery) "Recovery week" + listOfNotNull(if (hasRetest) "FTP" else null, if (hasRunTest) "LTHR" else null, if (hasSwimTest) "CSS" else null)
            .let { if (it.isEmpty()) "" else " + " + it.joinToString(" & ") + " test" }
        else if (sessions.isEmpty() && runBlock != null) "${runBlock.displayName}: week $weekInBlock"
        else if (sessions.isEmpty() && swims.isNotEmpty()) "${SwimLibrary.BLOCK_NAMES[blockIndex]}: week $weekInBlock"
        else "${blockType.displayName}: week $weekInBlock") + if (isRepeat) " (repeat)" else ""

    fun contains(date: LocalDate): Boolean = date >= startDate && date <= endDate
}

data class Plan(val request: PlanRequest, val weeks: List<PlanWeek>) {
    val startDate: LocalDate get() = request.startDate
    val endDate: LocalDate get() = weeks.last().endDate
    val sessions: List<PlannedSession> get() = weeks.flatMap { it.sessions }
    val runs: List<PlannedRun> get() = weeks.flatMap { it.runs }
    val swims: List<PlannedSwim> get() = weeks.flatMap { it.swims }

    fun session(id: String): PlannedSession? = sessions.firstOrNull { it.id == id }
    fun run(id: String): PlannedRun? = runs.firstOrNull { it.id == id }
    fun swim(id: String): PlannedSwim? = swims.firstOrNull { it.id == id }
    fun weekFor(date: LocalDate): PlanWeek? = weeks.firstOrNull { it.contains(date) }
}

// ---- Small date helpers so the engine reads the same on every platform ----

fun LocalDate.plusDays(n: Int): LocalDate = this + DatePeriod(days = n)
fun LocalDate.plusDays(n: Long): LocalDate = plusDays(n.toInt())
fun LocalDate.minusDays(n: Int): LocalDate = plusDays(-n)
fun LocalDate.plusWeeks(n: Int): LocalDate = plusDays(n * 7)
fun LocalDate.plusWeeks(n: Long): LocalDate = plusWeeks(n.toInt())
fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
fun LocalDate.isAfter(other: LocalDate): Boolean = this > other

/** Days from [this] to [other] (negative if [other] is earlier). */
fun LocalDate.daysTo(other: LocalDate): Int = other.toEpochDays().toInt() - toEpochDays().toInt()

/** ISO day number: Monday = 1 … Sunday = 7. */
val DayOfWeek.isoNumber: Int get() = ordinal + 1
fun dayOfWeekFromIso(n: Int): DayOfWeek = DayOfWeek.entries[n - 1]
