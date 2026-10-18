package com.offseason.web

import com.offseason.engine.Adaptation
import com.offseason.engine.AthleteSchedule
import com.offseason.engine.HoursBucket
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.PlanGenerator
import com.offseason.engine.PlanLength
import com.offseason.engine.PlanRequest
import com.offseason.engine.PlannedSession
import com.offseason.engine.RecoveryCycle
import com.offseason.engine.RideSummary
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.WeekRepeats
import com.offseason.engine.daysTo
import com.offseason.engine.dayOfWeekFromIso
import com.offseason.engine.isAfter
import com.offseason.engine.isBefore
import com.offseason.engine.minusDays
import com.offseason.engine.plusDays
import kotlinx.browser.localStorage
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.Date
import kotlin.math.abs

/*
 * The same data format as the Android app (app/.../data/AppData.kt), so an export from
 * one can be imported into the other. Keep the two in step.
 */

@Serializable
data class AppData(
    val version: Int = 1,
    val profile: Profile? = null,
    val ftpHistory: List<FtpEntry> = emptyList(),
    val completions: Map<String, Completion> = emptyMap(),
    val targetAdjustments: List<TargetAdjustment> = emptyList(),
    val repeatedWeeks: List<Int> = emptyList(),
    val easingDeclinedAt: List<String> = emptyList(),
) {
    val currentFtp: Int? get() = ftpHistory.maxByOrNull { it.date }?.ftp
    val startingFtp: Int? get() = ftpHistory.minByOrNull { it.date }?.ftp
}

@Serializable
data class Profile(
    val name: String,
    val experience: Experience,
    val hoursPerWeek: Int,
    val bikeDays: List<Int>,
    val swimDays: List<Int>,
    val runDays: List<Int>,
    val longRunDay: Int? = null,
    val hardRunDays: List<Int> = emptyList(),
    val planWeeks: Int,
    val startDate: String,
    val recoveryCycle: String = RecoveryCycle.THREE_ONE.name,
    val weightKg: Double? = null,
    val showWattsPerKg: Boolean = false,
    val disclaimerAcceptedOn: String,
    val readiness: Map<String, Boolean> = emptyMap(),
) {
    fun toPlanRequest() = PlanRequest(
        startDate = LocalDate.parse(startDate),
        length = PlanLength.ofWeeks(planWeeks),
        hours = HoursBucket.of(hoursPerWeek),
        recoveryCycle = RecoveryCycle.valueOf(recoveryCycle),
        schedule = AthleteSchedule(
            bikeDays = bikeDays.map(::dayOfWeekFromIso).toSet(),
            swimDays = swimDays.map(::dayOfWeekFromIso).toSet(),
            runDays = runDays.map(::dayOfWeekFromIso).toSet(),
            longRunDay = longRunDay?.let(::dayOfWeekFromIso),
            hardRunDays = hardRunDays.map(::dayOfWeekFromIso).toSet(),
        ),
    )
}

@Serializable enum class Experience { NEWER, EXPERIENCED }

@Serializable data class FtpEntry(val date: String, val ftp: Int, val source: String)

@Serializable
enum class Rpe(val label: String) {
    EASY("Easy"), ABOUT_RIGHT("About right"), HARD("Hard"), COULDNT_FINISH("Couldn't finish")
}

@Serializable data class Completion(val date: String, val rpe: Rpe, val ride: RideStats? = null)

@Serializable
data class RideStats(
    val durationSec: Int,
    val avgPower: Int? = null,
    val weightedPower: Int? = null,
    val best20MinPower: Int? = null,
    val ftpAtRide: Int? = null,
    val sessionLoad: Double? = null,
)

@Serializable data class TargetAdjustment(val fromDate: String, val pct: Int)

/** Saves everything in the browser's storage on this phone. Nothing is sent anywhere. */
object Store {
    private const val KEY = "offseason.v1"
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppData = runCatching { localStorage.getItem(KEY)?.let(::decode) }.getOrNull() ?: AppData()
    fun save(d: AppData) = localStorage.setItem(KEY, encode(d))
    fun encode(d: AppData): String = json.encodeToString(AppData.serializer(), d)
    fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text)
}

fun todayLocal(): LocalDate {
    val d = Date()
    return LocalDate(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** The phone's local date for a time in seconds since 1970. */
fun localDateOfEpochSec(sec: Long): LocalDate {
    val d = Date(sec * 1000.0)
    return LocalDate(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** The same logic as the Android AppViewModel, minus Android. */
object Logic {
    fun buildPlan(d: AppData): Plan? = d.profile?.let { p ->
        var plan = WeekRepeats.apply(PlanGenerator.generate(p.toPlanRequest()), d.repeatedWeeks)
        for (adj in d.targetAdjustments) plan = Adaptation.applyTargetAdjustment(plan, LocalDate.parse(adj.fromDate), adj.pct)
        plan
    }

    fun completionRate(plan: Plan, d: AppData, today: LocalDate): Double? {
        val due = plan.sessions.filter { !it.optional && it.date.isBefore(today) }
        if (due.isEmpty()) return null
        return due.count { it.id in d.completions }.toDouble() / due.size
    }

    data class StatusSummary(val message: Status.Message, val series: List<Load.DayLoad>)

    fun status(plan: Plan, d: AppData, today: LocalDate): StatusSummary {
        val done = plan.sessions.filter { it.id in d.completions }
        val daily = done.groupBy { LocalDate.parse(d.completions.getValue(it.id).date) }
            .mapValues { (_, s) -> s.sumOf { d.completions.getValue(it.id).ride?.sessionLoad ?: Load.sessionLoad(it.workout) } }
        val from = minOf(plan.startDate, today).minusDays(7)
        val baseline = plan.weeks.first().sessions.filterNot { it.optional }.sumOf { Load.sessionLoad(it.workout) } / 7.0
        val series = Load.series(daily, from, maxOf(today, from), startFitness = baseline, startFatigue = baseline)
        val now = series.last()
        val weekAgo = series.getOrNull(series.size - 8) ?: series.first()
        val message = Status.forWeek(plan.weekFor(today), now.freshness, now.fitness - weekAgo.fitness, completionRate(plan, d, today))
        return StatusSummary(message, series)
    }

    fun shouldOfferEasing(plan: Plan, d: AppData, sessionId: String): Boolean {
        if (sessionId in d.easingDeclinedAt) return false
        val session = plan.session(sessionId) ?: return false
        if (session.workout.kind != SessionKind.INTERVALS) return false
        val rated = plan.sessions
            .filter { it.workout.kind == SessionKind.INTERVALS && it.id in d.completions && !it.date.isAfter(session.date) }
            .sortedBy { it.date }
            .map { feel(d.completions.getValue(it.id).rpe) }
        return Adaptation.shouldOfferEasing(rated) && d.targetAdjustments.sumOf { it.pct } > -Adaptation.MAX_EASE_PCT
    }

    private fun feel(r: Rpe) = when (r) {
        Rpe.EASY -> Adaptation.Feel.EASY
        Rpe.ABOUT_RIGHT -> Adaptation.Feel.ABOUT_RIGHT
        Rpe.HARD -> Adaptation.Feel.HARD
        Rpe.COULDNT_FINISH -> Adaptation.Feel.COULDNT_FINISH
    }

    fun canRepeatWeekOf(plan: Plan, sessionId: String): Boolean =
        plan.weeks.firstOrNull { w -> w.sessions.any { it.id == sessionId } }?.let(WeekRepeats::canRepeat) == true

    fun rideStats(ride: RideSummary, ftp: Int?) = RideStats(
        durationSec = ride.durationSec,
        avgPower = ride.avgPower,
        weightedPower = ride.weightedPower,
        best20MinPower = ride.best20MinPower,
        ftpAtRide = ftp,
        sessionLoad = ftp?.let { ride.sessionLoad(it) },
    )

    fun suggestSession(plan: Plan, d: AppData, ride: RideSummary): PlannedSession? {
        val date = localDateOfEpochSec(ride.startEpochSec)
        return plan.sessions
            .filter { abs(it.date.daysTo(date)) <= 3 }
            .minWithOrNull(compareBy<PlannedSession> { it.id in d.completions }
                .thenBy { abs(it.date.daysTo(date)) }
                .thenBy { abs(it.workout.durationSec - ride.durationSec) })
    }
}
