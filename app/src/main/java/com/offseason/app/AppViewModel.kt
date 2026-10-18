package com.offseason.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offseason.app.data.AppData
import com.offseason.app.data.Completion
import com.offseason.app.data.FtpEntry
import com.offseason.app.data.Profile
import com.offseason.app.data.Rpe
import com.offseason.app.data.Store
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.PlanGenerator
import com.offseason.engine.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Holds the app's data and the plan generated from it. Every screen reads from here. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _plan = MutableStateFlow(buildPlan(_data.value))
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    fun today(): LocalDate = LocalDate.now()

    private fun update(change: (AppData) -> AppData) {
        val next = change(_data.value)
        store.save(next)
        val oldRequest = _data.value.profile?.toPlanRequest()
        _data.value = next
        if (next.profile?.toPlanRequest() != oldRequest) _plan.value = buildPlan(next)
    }

    private fun buildPlan(d: AppData): Plan? = d.profile?.let { PlanGenerator.generate(it.toPlanRequest()) }

    // ---- Actions ----

    fun completeOnboarding(profile: Profile, ftp: Int?, ftpSource: String) = update {
        AppData(
            profile = profile,
            ftpHistory = ftp?.let { listOf(FtpEntry(today().toString(), it, ftpSource)) } ?: emptyList(),
        )
    }

    fun updateProfile(profile: Profile) = update { it.copy(profile = profile) }

    /** Records a new FTP (initial test, retest or manual edit). All targets update automatically. */
    fun recordFtp(ftp: Int, source: String) = update { d ->
        val date = today().toString()
        d.copy(ftpHistory = d.ftpHistory.filterNot { it.date == date } + FtpEntry(date, ftp, source))
    }

    fun markDone(sessionId: String, rpe: Rpe) = update {
        it.copy(completions = it.completions + (sessionId to Completion(today().toString(), rpe)))
    }

    fun unmarkDone(sessionId: String) = update { it.copy(completions = it.completions - sessionId) }

    fun resetPlan() = update { AppData() }

    fun exportJson(): String = Store.encode(_data.value)

    fun importJson(text: String): Result<Unit> = runCatching {
        val imported = Store.decode(text)
        update { imported }
    }

    // ---- Derived values for the screens ----

    /** Fraction of non-optional sessions before today that were completed; null if none are due yet. */
    fun completionRate(plan: Plan, d: AppData, today: LocalDate = today()): Double? {
        val due = plan.sessions.filter { !it.optional && it.date.isBefore(today) }
        if (due.isEmpty()) return null
        return due.count { it.id in d.completions }.toDouble() / due.size
    }

    /**
     * Fitness/Fatigue from completed sessions (using each workout's planned load, since
     * we don't read ride files yet), plus the traffic-light message.
     */
    fun status(plan: Plan, d: AppData, today: LocalDate = today()): StatusSummary {
        val done = plan.sessions.filter { it.id in d.completions }
        val daily = done.groupBy { LocalDate.parse(d.completions.getValue(it.id).date) }
            .mapValues { (_, s) -> s.sumOf { Load.sessionLoad(it.workout) } }
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
