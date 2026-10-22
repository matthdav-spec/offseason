package com.offseason.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offseason.app.data.Store
import com.offseason.engine.AppData
import com.offseason.engine.AppLogic
import com.offseason.engine.FitReader
import com.offseason.engine.Plan
import com.offseason.engine.PlannedSession
import com.offseason.engine.Profile
import com.offseason.engine.RideAnalysis
import com.offseason.engine.RideFileException
import com.offseason.engine.RideSummary
import com.offseason.engine.Rpe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

/**
 * Holds the app's data and the plan generated from it. Every screen reads from here.
 * The behaviour itself is in the shared engine ([AppLogic]), so the web app works the same way.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _plan = MutableStateFlow(AppLogic.buildPlan(_data.value))
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
        if (next.profile != old.profile || next.targetAdjustments != old.targetAdjustments || next.repeatedWeeks != old.repeatedWeeks) {
            _plan.value = AppLogic.buildPlan(next)
        }
    }

    // ---- Actions ----

    fun completeOnboarding(profile: Profile, ftp: Int?, lthr: Int?) = update { AppLogic.completeOnboarding(profile, ftp, lthr, today()) }
    fun updateProfile(profile: Profile) = update { it.copy(profile = profile) }
    fun recordFtp(ftp: Int, source: String) = update { AppLogic.recordFtp(it, ftp, source, today()) }
    fun recordLthr(lthr: Int, source: String) = update { AppLogic.recordLthr(it, lthr, source, today()) }
    fun markDone(id: String, rpe: Rpe) = update { AppLogic.markDone(it, id, rpe, today()) }
    fun unmarkDone(id: String) = update { it.copy(completions = it.completions - id) }
    fun resetPlan() = update { AppData() }
    fun exportJson(): String = Store.encode(_data.value)
    fun importJson(text: String): Result<Unit> = runCatching { val imported = Store.decode(text); update { imported } }

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

    /** Attaches a ride to a session and marks it done (keeping any rating). The ride's own date is used. */
    fun attachRide(sessionId: String, ride: RideSummary, rpe: Rpe? = null) = update { d ->
        val existing = d.completions[sessionId]
        val date = localDateOfEpochSec(ride.startEpochSec).toString()
        d.copy(completions = d.completions + (sessionId to com.offseason.engine.Completion(date, rpe ?: existing?.rpe ?: Rpe.ABOUT_RIGHT, AppLogic.rideStats(ride, d.currentFtp))))
    }

    fun suggestSession(plan: Plan, ride: RideSummary): PlannedSession? =
        AppLogic.suggestSession(plan, _data.value, localDateOfEpochSec(ride.startEpochSec), ride.durationSec)

    // ---- Adapting to how sessions felt ----

    fun shouldOfferEasing(plan: Plan, d: AppData, id: String) = AppLogic.shouldOfferEasing(plan, d, id)
    fun lowerTargets() = update { AppLogic.lowerTargets(it, today()) }
    fun repeatWeekOf(id: String) = update { d -> _plan.value?.let { AppLogic.repeatWeekOf(d, it, id) } ?: d }
    fun declineEasing(id: String) = update { it.copy(easingDeclinedAt = it.easingDeclinedAt + id) }
    fun canRepeatWeekOf(plan: Plan, id: String) = AppLogic.canRepeatWeekOf(plan, id)

    // ---- Derived values for the screens ----

    fun completionRate(plan: Plan, d: AppData, today: LocalDate = today()) = AppLogic.completionRate(plan, d, today)
    fun status(plan: Plan, d: AppData, today: LocalDate = today()) = AppLogic.status(plan, d, today)
}
