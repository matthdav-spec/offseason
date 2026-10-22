package com.offseason.engine

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
 * Everything the app stores, shared by the Android and web apps so exports move between them.
 * The plan itself isn't stored: it's regenerated from [Profile] by the engine, so it always
 * matches the current settings. New fields must have defaults so older saved data still loads.
 */

@Serializable
data class AppData(
    val version: Int = 1,
    val profile: Profile? = null,
    val ftpHistory: List<FtpEntry> = emptyList(),
    /** Keyed by session or run id. */
    val completions: Map<String, Completion> = emptyMap(),
    /** Target changes the athlete accepted, e.g. "lower by 4%" from a date. Cleared at each FTP test. */
    val targetAdjustments: List<TargetAdjustment> = emptyList(),
    /** Original week indexes the athlete chose to repeat, in order. */
    val repeatedWeeks: List<Int> = emptyList(),
    /** Session ids where the athlete said "no thanks" to easing, so we don't keep asking. */
    val easingDeclinedAt: List<String> = emptyList(),
    val lthrHistory: List<LthrEntry> = emptyList(),
    /** CSS results in seconds per 100 m. */
    val cssHistory: List<CssEntry> = emptyList(),
    /** Longest swim without stopping, logged from "Continuous" swims and distance checks. */
    val swimDistanceHistory: List<SwimDistanceEntry> = emptyList(),
) {
    val currentFtp: Int? get() = ftpHistory.maxByOrNull { it.date }?.ftp
    val startingFtp: Int? get() = ftpHistory.minByOrNull { it.date }?.ftp
    val currentLthr: Int? get() = lthrHistory.maxByOrNull { it.date }?.lthr
    val startingLthr: Int? get() = lthrHistory.minByOrNull { it.date }?.lthr
    val currentCss: Int? get() = cssHistory.maxByOrNull { it.date }?.cssSec
    val startingCss: Int? get() = cssHistory.minByOrNull { it.date }?.cssSec
    val bestContinuousM: Int? get() = swimDistanceHistory.maxOfOrNull { it.distanceM }
}

@Serializable
data class Profile(
    val name: String,
    val experience: Experience,
    val hoursPerWeek: Int,
    /** Empty when the athlete is following the run plan only. */
    val bikeDays: List<Int>,
    val swimDays: List<Int>,
    /** With the run plan: days they CAN run. Without: days they already run (for scheduling around). */
    val runDays: List<Int>,
    val longRunDay: Int? = null,
    val hardRunDays: List<Int> = emptyList(),
    val planWeeks: Int,
    /** ISO date, e.g. "2026-10-26". */
    val startDate: String,
    val recoveryCycle: String = RecoveryCycle.THREE_ONE.name,
    val weightKg: Double? = null,
    val showWattsPerKg: Boolean = false,
    val disclaimerAcceptedOn: String,
    val readiness: Map<String, Boolean> = emptyMap(),
    /** Run plan on? (false for everyone in the original bike pilot) */
    val runPlan: Boolean = false,
    val runHoursPerWeek: Int = 3,
    /** Swim plan on? */
    val swimPlan: Boolean = false,
    val swimsPerWeek: Int = 2,
    val poolLengthM: Int = 25,
    /** "BUILD_UP" (can't yet swim 400 m without stopping) or "CSS". */
    val swimLevel: String = SwimLevel.BUILD_UP.name,
    /** Build-up route: how far they could swim without stopping at the start (0 = not sure). */
    val swimStartDistanceM: Int = 0,
    /** Moved up from the build-up route to the CSS plan on this date (ISO), if they have. */
    val swimCssFrom: String? = null,
    /** The athlete's target race, e.g. "IRONMAN 70.3 Cascais". Shown as a countdown. */
    val raceName: String? = null,
    /** ISO date of the target race. */
    val raceDate: String? = null,
) {
    val bikePlan: Boolean get() = bikeDays.isNotEmpty()

    fun toPlanRequest() = PlanRequest(
        startDate = LocalDate.parse(startDate),
        length = PlanLength.ofWeeks(planWeeks),
        hours = HoursBucket.of(hoursPerWeek),
        recoveryCycle = RecoveryCycle.valueOf(recoveryCycle),
        schedule = AthleteSchedule(
            bikeDays = bikeDays.map(::dayOfWeekFromIso).toSet(),
            swimDays = swimDays.map(::dayOfWeekFromIso).toSet(),
            runDays = runDays.map(::dayOfWeekFromIso).toSet(),
            longRunDay = if (runPlan) null else longRunDay?.let(::dayOfWeekFromIso),
            hardRunDays = if (runPlan) emptySet() else hardRunDays.map(::dayOfWeekFromIso).toSet(),
        ),
        run = if (runPlan) RunPlanRequest(
            hours = RunHours.of(runHoursPerWeek),
            runDays = runDays.map(::dayOfWeekFromIso).toSet(),
            preferredLongRunDay = longRunDay?.let(::dayOfWeekFromIso),
        ) else null,
        swim = if (swimPlan) SwimPlanRequest(
            swimsPerWeek = swimsPerWeek.coerceIn(1, 3),
            swimDays = swimDays.map(::dayOfWeekFromIso).toSet(),
            level = SwimLevel.valueOf(swimLevel),
            poolLengthM = poolLengthM,
            startDistanceM = swimStartDistanceM,
            cssFrom = swimCssFrom?.let(LocalDate::parse),
        ) else null,
    )
}

@Serializable enum class Experience { NEWER, EXPERIENCED }

@Serializable data class FtpEntry(val date: String, val ftp: Int, val source: String)

@Serializable data class LthrEntry(val date: String, val lthr: Int, val source: String)

/** A CSS result. [t400Sec]/[t200Sec] are the test times, kept so the athlete can see them. */
@Serializable data class CssEntry(val date: String, val cssSec: Int, val t400Sec: Int? = null, val t200Sec: Int? = null)

@Serializable data class SwimDistanceEntry(val date: String, val distanceM: Int)

@Serializable
enum class Rpe(val label: String) {
    EASY("Easy"), ABOUT_RIGHT("About right"), HARD("Hard"), COULDNT_FINISH("Couldn't finish")
}

@Serializable
data class Completion(
    val date: String,
    val rpe: Rpe,
    /** Present if the athlete added a .fit ride file. */
    val ride: RideStats? = null,
)

/** What we keep from a .fit file. The file itself isn't stored. */
@Serializable
data class RideStats(
    val durationSec: Int,
    val avgPower: Int? = null,
    val weightedPower: Int? = null,
    val best20MinPower: Int? = null,
    /** FTP at the time, so the load stays correct if FTP changes later. */
    val ftpAtRide: Int? = null,
    val sessionLoad: Double? = null,
)

@Serializable
data class TargetAdjustment(
    /** ISO date the change applies from. */
    val fromDate: String,
    /** e.g. -4 for "4% lower". */
    val pct: Int,
)

object DataJson {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(d: AppData): String = json.encodeToString(AppData.serializer(), d)
    fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text)
}

/** App behaviour shared by the Android and web apps. No Android or browser code here. */
object AppLogic {
    fun buildPlan(d: AppData): Plan? = d.profile?.let { p ->
        var plan = WeekRepeats.apply(PlanGenerator.generate(p.toPlanRequest()), d.repeatedWeeks)
        for (adj in d.targetAdjustments) plan = Adaptation.applyTargetAdjustment(plan, LocalDate.parse(adj.fromDate), adj.pct)
        plan
    }

    /** Every planned item's id and date, bike and run, with whether it's optional. */
    private fun dueItems(plan: Plan): List<Triple<String, LocalDate, Boolean>> =
        plan.sessions.map { Triple(it.id, it.date, it.optional) } + plan.runs.map { Triple(it.id, it.date, false) } +
            plan.swims.map { Triple(it.id, it.date, false) }

    /** Fraction of non-optional sessions and runs before today that were completed; null if none are due yet. */
    fun completionRate(plan: Plan, d: AppData, today: LocalDate): Double? {
        val due = dueItems(plan).filter { !it.third && it.second.isBefore(today) }
        if (due.isEmpty()) return null
        return due.count { it.first in d.completions }.toDouble() / due.size
    }

    data class StatusSummary(val message: Status.Message, val series: List<Load.DayLoad>)

    /** Planned load of one item (bike session or run). */
    private fun plannedLoad(plan: Plan, id: String, css: Int? = null): Double =
        plan.session(id)?.let { Load.sessionLoad(it.workout) } ?: plan.run(id)?.let { RunLoad.sessionLoad(it.workout) }
            ?: plan.swim(id)?.let { SwimLoad.sessionLoad(it.workout, css) } ?: 0.0

    /**
     * Fitness/Fatigue from completed bike sessions and runs, plus the traffic-light message.
     * Uses the real load from a ride file where there is one, otherwise the planned load.
     */
    fun status(plan: Plan, d: AppData, today: LocalDate): StatusSummary {
        val doneIds = dueItems(plan).map { it.first }.filter { it in d.completions }
        val daily = doneIds.groupBy { LocalDate.parse(d.completions.getValue(it).date) }
            .mapValues { (_, ids) -> ids.sumOf { d.completions.getValue(it).ride?.sessionLoad ?: plannedLoad(plan, it, d.currentCss) } }
        val from = minOf(plan.startDate, today).minusDays(7)
        // Triathletes aren't starting from zero: assume they arrive already absorbing about the
        // plan's first-week load, so the traffic light reacts to changes rather than to starting at all.
        val first = plan.weeks.first()
        val baseline = (first.sessions.filterNot { it.optional }.sumOf { Load.sessionLoad(it.workout) } +
            first.runs.sumOf { RunLoad.sessionLoad(it.workout) } + first.swims.sumOf { SwimLoad.sessionLoad(it.workout, d.currentCss) }) / 7.0
        val series = Load.series(daily, from, maxOf(today, from), startFitness = baseline, startFatigue = baseline)
        val now = series.last()
        val weekAgo = series.getOrNull(series.size - 8) ?: series.first()
        val message = Status.forWeek(plan.weekFor(today), now.freshness, now.fitness - weekAgo.fitness, completionRate(plan, d, today))
        return StatusSummary(message, series)
    }

    private fun feel(r: Rpe) = when (r) {
        Rpe.EASY -> Adaptation.Feel.EASY
        Rpe.ABOUT_RIGHT -> Adaptation.Feel.ABOUT_RIGHT
        Rpe.HARD -> Adaptation.Feel.HARD
        Rpe.COULDNT_FINISH -> Adaptation.Feel.COULDNT_FINISH
    }

    /**
     * Whether to offer "lower targets / repeat the week" after [id] was rated: the last two
     * quality sessions of that sport felt hard, and the athlete hasn't already declined for this one.
     * For runs only "repeat the week" is offered (targets are heart rate, which self-adjusts).
     */
    fun shouldOfferEasing(plan: Plan, d: AppData, id: String): Boolean {
        if (id in d.easingDeclinedAt) return false
        plan.session(id)?.let { session ->
            if (session.workout.kind != SessionKind.INTERVALS) return false
            val rated = plan.sessions
                .filter { it.workout.kind == SessionKind.INTERVALS && it.id in d.completions && !it.date.isAfter(session.date) }
                .sortedBy { it.date }
                .map { feel(d.completions.getValue(it.id).rpe) }
            return Adaptation.shouldOfferEasing(rated) && d.targetAdjustments.sumOf { it.pct } > -Adaptation.MAX_EASE_PCT
        }
        plan.run(id)?.let { run ->
            if (!run.workout.isHard || run.workout.kind == RunKind.LTHR_TEST) return false
            val rated = plan.runs
                .filter { it.workout.isHard && it.workout.kind != RunKind.LTHR_TEST && it.id in d.completions && !it.date.isAfter(run.date) }
                .sortedBy { it.date }
                .map { feel(d.completions.getValue(it.id).rpe) }
            return Adaptation.shouldOfferEasing(rated) && canRepeatWeekOf(plan, id)
        }
        return false
    }

    fun weekOf(plan: Plan, id: String): PlanWeek? =
        plan.weeks.firstOrNull { w -> w.sessions.any { it.id == id } || w.runs.any { it.id == id } || w.swims.any { it.id == id } }

    fun canRepeatWeekOf(plan: Plan, id: String): Boolean = weekOf(plan, id)?.let(WeekRepeats::canRepeat) == true

    fun rideStats(ride: RideSummary, ftp: Int?) = RideStats(
        durationSec = ride.durationSec,
        avgPower = ride.avgPower,
        weightedPower = ride.weightedPower,
        best20MinPower = ride.best20MinPower,
        ftpAtRide = ftp,
        sessionLoad = ftp?.let { ride.sessionLoad(it) },
    )

    /** The planned bike session nearest a ride file's date (same day first). */
    fun suggestSession(plan: Plan, d: AppData, rideDate: LocalDate, rideDurationSec: Int): PlannedSession? =
        plan.sessions
            .filter { abs(it.date.daysTo(rideDate)) <= 3 }
            .minWithOrNull(compareBy<PlannedSession> { it.id in d.completions }
                .thenBy { abs(it.date.daysTo(rideDate)) }
                .thenBy { abs(it.workout.durationSec - rideDurationSec) })

    // ---- Changes (pure: AppData in, AppData out) ----

    fun completeOnboarding(profile: Profile, ftp: Int?, lthr: Int?, today: LocalDate, cssSec: Int? = null) = AppData(
        profile = profile,
        cssHistory = cssSec?.let { listOf(CssEntry(today.toString(), it)) } ?: emptyList(),
        swimDistanceHistory = if (profile.swimPlan && profile.swimStartDistanceM > 0) listOf(SwimDistanceEntry(today.toString(), profile.swimStartDistanceM)) else emptyList(),
        ftpHistory = ftp?.let { listOf(FtpEntry(today.toString(), it, "entered")) } ?: emptyList(),
        lthrHistory = lthr?.let { listOf(LthrEntry(today.toString(), it, "entered")) } ?: emptyList(),
    )

    /** A new FTP also clears "lower my targets" changes: the new number already reflects how you are. */
    fun recordFtp(d: AppData, ftp: Int, source: String, today: LocalDate): AppData {
        val date = today.toString()
        return d.copy(ftpHistory = d.ftpHistory.filterNot { it.date == date } + FtpEntry(date, ftp, source), targetAdjustments = emptyList())
    }

    fun recordCss(d: AppData, cssSec: Int, t400Sec: Int?, t200Sec: Int?, today: LocalDate): AppData {
        val date = today.toString()
        return d.copy(cssHistory = d.cssHistory.filterNot { it.date == date } + CssEntry(date, cssSec, t400Sec, t200Sec))
    }

    fun recordSwimDistance(d: AppData, distanceM: Int, today: LocalDate): AppData {
        val date = today.toString()
        val best = d.swimDistanceHistory.filter { it.date == date }.maxOfOrNull { it.distanceM } ?: 0
        return if (distanceM <= best) d
        else d.copy(swimDistanceHistory = d.swimDistanceHistory.filterNot { it.date == date } + SwimDistanceEntry(date, distanceM))
    }

    /** Ready to leave the build-up route? (Has swum 400 m without stopping and isn't on the CSS plan yet.) */
    fun readyToMoveUp(d: AppData): Boolean {
        val p = d.profile ?: return false
        return p.swimPlan && p.swimLevel == SwimLevel.BUILD_UP.name && p.swimCssFrom == null &&
            (d.bestContinuousM ?: 0) >= 400
    }

    /** Moves to the CSS plan from the start of next week (in the plan's week rhythm). Earlier weeks are unchanged. */
    fun moveUpToCss(d: AppData, plan: Plan, today: LocalDate): AppData {
        val p = d.profile ?: return d
        val next = plan.weeks.firstOrNull { it.startDate > today }?.startDate ?: return d
        return d.copy(profile = p.copy(swimCssFrom = next.toString()))
    }

    fun recordLthr(d: AppData, lthr: Int, source: String, today: LocalDate): AppData {
        val date = today.toString()
        return d.copy(lthrHistory = d.lthrHistory.filterNot { it.date == date } + LthrEntry(date, lthr, source))
    }

    fun markDone(d: AppData, id: String, rpe: Rpe, today: LocalDate): AppData {
        val existing = d.completions[id]
        return d.copy(completions = d.completions + (id to Completion(existing?.date ?: today.toString(), rpe, existing?.ride)))
    }

    fun lowerTargets(d: AppData, today: LocalDate) =
        d.copy(targetAdjustments = d.targetAdjustments + TargetAdjustment(today.plusDays(1).toString(), -Adaptation.EASE_PCT))

    fun repeatWeekOf(d: AppData, plan: Plan, id: String): AppData {
        val week = weekOf(plan, id) ?: return d
        return if (!WeekRepeats.canRepeat(week)) d else d.copy(repeatedWeeks = d.repeatedWeeks + week.sourceIndex)
    }
}
