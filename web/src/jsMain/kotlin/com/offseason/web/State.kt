package com.offseason.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.offseason.engine.Plan
import com.offseason.engine.RideSummary
import com.offseason.engine.WeekRepeats
import com.offseason.engine.plusDays
import kotlinx.browser.window
import kotlinx.datetime.LocalDate

/** Where the app is. Tabs at the top level; the rest open on top with a back button. */
sealed class Route {
    data object Week : Route()
    data object PlanTab : Route()
    data object Progress : Route()
    data object Settings : Route()
    data class Session(val id: String) : Route()
    data class Run(val id: String) : Route()
    data class LthrTest(val runId: String?) : Route()
    data class Swim(val id: String) : Route()
    data class CssTest(val swimId: String?) : Route()
    data class FtpTest(val sessionId: String?) : Route()
    data class Help(val topic: String) : Route()
    data object Install : Route()
}

/** All app state. Every change is saved straight away. */
object AppState {
    var data by mutableStateOf(Store.load())
        private set
    var plan by mutableStateOf<Plan?>(Logic.buildPlan(data))
        private set
    var route by mutableStateOf<Route>(Route.Week)
    private val history = mutableListOf<Route>()

    var toast by mutableStateOf<String?>(null)
    var pendingRide by mutableStateOf<RideSummary?>(null)
    /** The session just rated, to decide whether to offer easing off. */
    var justRated by mutableStateOf<String?>(null)

    fun today(): LocalDate = todayLocal()

    fun update(change: (AppData) -> AppData) {
        val old = data
        val next = change(old)
        Store.save(next)
        data = next
        if (next.profile != old.profile || next.targetAdjustments != old.targetAdjustments || next.repeatedWeeks != old.repeatedWeeks) {
            plan = Logic.buildPlan(next)
        }
    }

    fun go(r: Route) {
        if (r is Route.Week || r is Route.PlanTab || r is Route.Progress || r is Route.Settings) history.clear()
        else history += route
        route = r
        window.scrollTo(0.0, 0.0)
    }

    fun back() {
        route = history.removeLastOrNull() ?: Route.Week
        window.scrollTo(0.0, 0.0)
    }

    fun showToast(text: String) {
        toast = text
        window.setTimeout({ if (toast == text) toast = null }, 3500)
    }

    // ---- Actions (same behaviour as the Android app) ----

    fun completeOnboarding(profile: Profile, ftp: Int?, lthr: Int?) = update { Logic.completeOnboarding(profile, ftp, lthr, today()) }
    fun recordFtp(ftp: Int, source: String) = update { Logic.recordFtp(it, ftp, source, today()) }
    fun recordLthr(lthr: Int, source: String) = update { Logic.recordLthr(it, lthr, source, today()) }
    fun recordCss(cssSec: Int, t400: Int?, t200: Int?) = update { Logic.recordCss(it, cssSec, t400, t200, today()) }
    fun recordSwimDistance(m: Int) = update { Logic.recordSwimDistance(it, m, today()) }
    fun moveUpToCss() = update { d -> plan?.let { Logic.moveUpToCss(d, it, today()) } ?: d }

    fun markDone(id: String, rpe: Rpe) {
        update { Logic.markDone(it, id, rpe, today()) }
        justRated = id
    }

    fun unmarkDone(id: String) = update { it.copy(completions = it.completions - id) }

    fun attachRide(id: String, ride: RideSummary, rpe: Rpe? = null) = update { d ->
        val existing = d.completions[id]
        val date = localDateOfEpochSec(ride.startEpochSec).toString()
        d.copy(completions = d.completions + (id to com.offseason.engine.Completion(date, rpe ?: existing?.rpe ?: Rpe.ABOUT_RIGHT, Logic.rideStats(ride, d.currentFtp))))
    }

    fun lowerTargets() {
        update { Logic.lowerTargets(it, today()) }
        justRated = null
    }

    fun repeatWeekOf(id: String) {
        update { d -> plan?.let { Logic.repeatWeekOf(d, it, id) } ?: d }
        justRated = null
    }

    fun declineEasing(id: String) {
        update { it.copy(easingDeclinedAt = it.easingDeclinedAt + id) }
        justRated = null
    }

    fun reset() {
        update { AppData() }
        history.clear()
        route = Route.Week
    }
}
