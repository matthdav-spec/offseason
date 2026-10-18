package com.offseason.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offseason.app.data.AppData
import com.offseason.app.data.Completion
import com.offseason.app.data.FtpEntry
import com.offseason.app.data.Profile
import com.offseason.app.data.RideStats
import com.offseason.app.data.Rpe
import com.offseason.app.data.Store
import com.offseason.app.data.TargetAdjustment
import com.offseason.engine.Adaptation
import com.offseason.engine.FitReader
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.PlanGenerator
import com.offseason.engine.PlannedSession
import com.offseason.engine.RideAnalysis
import com.offseason.engine.RideFileException
import com.offseason.engine.RideSummary
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.WeekRepeats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import com.offseason.engine.*
import com.offseason.app.format
import kotlin.math.abs

/** Holds the app's data and the plan generated from it. Every screen reads from here. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _plan = MutableStateFlow(buildPlan(_data.value))
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    /** A .fit file shared into the app from another app, waiting for the athlete to pick a session. */
    private val _pendingRide = MutableStateFlow<RideSummary?>(null)
    val pendingRide: StateFlow<RideSummary?> = _pendingRide.asStateFlow()

    fun today(): LocalDate = todayLocal()

    private fun update(change: (AppData) -> AppData) {
        val old = _data.value
        val next = change(old)
        store.save(next)
        _data.value = next
        if (next.profile?.toPlanRequest() != old.profile?.toPlanRequest() ||
            next.targetAdjustments != old.targetAdjustments || next.repeatedWeeks != old.repeatedWeeks
        ) _plan.value = buildPlan(next)
    }

    /** The generated plan, with any repeated weeks and accepted target changes applied. */
    private fun buildPlan(d: AppData): Plan? = d.profile?.let { p ->
        var plan = WeekRepeats.apply(PlanGenerator.generate(p.toPlanRequest()), d.repeatedWeeks)
        for (adj in d.targetAdjustments) plan = Adaptation.applyTargetAdjustment(plan, LocalDate.parse(adj.fromDate), adj.pct)
        plan
    }

    // ---- Actions ----

    fun completeOnboarding(profile: Profile, ftp: Int?, ftpSource: String) = update {
        AppData(
            profile = profile,
            ftpHistory = ftp?.let { listOf(FtpEntry(today().toString(), it, ftpSource)) } ?: emptyList(),
        )
    }

    fun updateProfile(profile: Profile) = update { it.copy(profile = profile) }

    /**
     * Records a new FTP (initial test, retest or manual edit). All targets update automatically.
     * A new FTP also clears any "lower my targets" changes: the new number already reflects how you are.
     */
    fun recordFtp(ftp: Int, source: String) = update { d ->
        val date = today().toString()
        d.copy(
            ftpHistory = d.ftpHistory.filterNot { it.date == date } + FtpEntry(date, ftp, source),
            targetAdjustments = emptyList(),
        )
    }

    fun markDone(sessionId: String, rpe: Rpe) = update { d ->
        val existing = d.completions[sessionId]
        d.copy(completions = d.completions + (sessionId to Completion(existing?.date ?: today().toString(), rpe, existing?.ride)))
    }

    fun unmarkDone(sessionId: String) = update { it.copy(completions = it.completions - sessionId) }

    fun resetPlan() = update { AppData() }

    fun exportJson(): String = Store.encode(_data.value)

    fun importJson(text: String): Result<Unit> = runCatching {
        val imported = Store.decode(text)
        update { imported }
    }

    // ---- Ride files (.fit) ----

    /** Reads a ride file. Errors come back as plain-English messages. */
    fun readRide(bytes: ByteArray): Result<RideSummary> = runCatching {
        try {
            RideAnalysis.summarize(FitReader.read(bytes))
        } catch (e: RideFileException) {
            throw e
        } catch (e: Exception) {
            throw RideFileException("That file couldn't be read as a ride. Is it a .fit file?")
        }
    }

    fun setPendingRide(ride: RideSummary?) { _pendingRide.value = ride }

    /**
     * Attaches a ride to a session: stores its numbers and marks the session done (keeping any
     * existing rating; the screen then asks how it felt). The ride's own date is used.
     */
    fun attachRide(sessionId: String, ride: RideSummary, rpe: Rpe? = null) = update { d ->
        val ftp = d.currentFtp
        val stats = RideStats(
            durationSec = ride.durationSec,
            avgPower = ride.avgPower,
            weightedPower = ride.weightedPower,
            best20MinPower = ride.best20MinPower,
            ftpAtRide = ftp,
            sessionLoad = ftp?.let { ride.sessionLoad(it) },
        )
        val existing = d.completions[sessionId]
        val date = localDateOfEpochSec(ride.startEpochSec).toString()
        d.copy(completions = d.completions + (sessionId to Completion(date, rpe ?: existing?.rpe ?: Rpe.ABOUT_RIGHT, stats)))
    }

    /** The planned session nearest the ride's date (same day first), to suggest when a file is shared in. */
    fun suggestSession(plan: Plan, ride: RideSummary): PlannedSession? {
        val date = localDateOfEpochSec(ride.startEpochSec)
        return plan.sessions
            .filter { abs(it.date.daysTo(date)) <= 3 }
            .minWithOrNull(compareBy<PlannedSession> { it.id in _data.value.completions }
                .thenBy { abs(it.date.daysTo(date)) }
                .thenBy { abs(it.workout.durationSec - ride.durationSec) })
    }

    // ---- Adapting to how sessions felt ----

    /**
     * Whether to offer "lower targets / repeat the week" after [sessionId] was rated: the last
     * two interval sessions felt hard, and the athlete hasn't already declined for this one.
     */
    fun shouldOfferEasing(plan: Plan, d: AppData, sessionId: String): Boolean {
        if (sessionId in d.easingDeclinedAt) return false
        val session = plan.session(sessionId) ?: return false
        if (session.workout.kind != SessionKind.INTERVALS) return false
        val totalEase = d.targetAdjustments.sumOf { it.pct }
        val rated = plan.sessions
            .filter { it.workout.kind == SessionKind.INTERVALS && it.id in d.completions && !it.date.isAfter(session.date) }
            .sortedBy { it.date }
            .map { feel(d.completions.getValue(it.id).rpe) }
        return Adaptation.shouldOfferEasing(rated) && totalEase > -Adaptation.MAX_EASE_PCT
    }

    private fun feel(r: Rpe) = when (r) {
        Rpe.EASY -> Adaptation.Feel.EASY
        Rpe.ABOUT_RIGHT -> Adaptation.Feel.ABOUT_RIGHT
        Rpe.HARD -> Adaptation.Feel.HARD
        Rpe.COULDNT_FINISH -> Adaptation.Feel.COULDNT_FINISH
    }

    /** "Lower my targets": future sessions from tomorrow get [Adaptation.EASE_PCT]% easier. */
    fun lowerTargets() = update {
        it.copy(targetAdjustments = it.targetAdjustments + TargetAdjustment(today().plusDays(1).toString(), -Adaptation.EASE_PCT))
    }

    /** "Repeat this week": the week the session is in gets done again next week. */
    fun repeatWeekOf(sessionId: String) = update { d ->
        val week = _plan.value?.weeks?.firstOrNull { w -> w.sessions.any { it.id == sessionId } } ?: return@update d
        if (!WeekRepeats.canRepeat(week)) d else d.copy(repeatedWeeks = d.repeatedWeeks + week.sourceIndex)
    }

    fun declineEasing(sessionId: String) = update { it.copy(easingDeclinedAt = it.easingDeclinedAt + sessionId) }

    fun canRepeatWeekOf(plan: Plan, sessionId: String): Boolean =
        plan.weeks.firstOrNull { w -> w.sessions.any { it.id == sessionId } }?.let(WeekRepeats::canRepeat) == true

    // ---- Derived values for the screens ----

    /** Fraction of non-optional sessions before today that were completed; null if none are due yet. */
    fun completionRate(plan: Plan, d: AppData, today: LocalDate = today()): Double? {
        val due = plan.sessions.filter { !it.optional && it.date.isBefore(today) }
        if (due.isEmpty()) return null
        return due.count { it.id in d.completions }.toDouble() / due.size
    }

    /**
     * Fitness/Fatigue from completed sessions, plus the traffic-light message. Uses the real load
     * from a ride file where there is one, otherwise the session's planned load.
     */
    fun status(plan: Plan, d: AppData, today: LocalDate = today()): StatusSummary {
        val done = plan.sessions.filter { it.id in d.completions }
        val daily = done.groupBy { LocalDate.parse(d.completions.getValue(it.id).date) }
            .mapValues { (_, s) -> s.sumOf { d.completions.getValue(it.id).ride?.sessionLoad ?: Load.sessionLoad(it.workout) } }
        val from = minOf(plan.startDate, today).minusDays(7)
        // Triathletes aren't starting from zero: assume they arrive already absorbing about the
        // plan's first-week load, so the traffic light reacts to changes rather than to starting at all.
        val baseline = plan.weeks.first().sessions.filterNot { it.optional }.sumOf { Load.sessionLoad(it.workout) } / 7.0
        val series = Load.series(daily, from, maxOf(today, from), startFitness = baseline, startFatigue = baseline)
        val now = series.last()
        val weekAgo = series.getOrNull(series.size - 8) ?: series.first()
        val message = Status.forWeek(plan.weekFor(today), now.freshness, now.fitness - weekAgo.fitness, completionRate(plan, d, today))
        return StatusSummary(message, series)
    }

    data class StatusSummary(val message: Status.Message, val series: List<Load.DayLoad>)
}
