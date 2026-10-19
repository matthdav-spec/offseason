package com.offseason.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.offseason.engine.Adaptation
import com.offseason.engine.AthleteSchedule
import com.offseason.engine.Ftp
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.PlannedSession
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.Step
import com.offseason.engine.StepType
import com.offseason.engine.Workout
import com.offseason.engine.Zone
import com.offseason.engine.isAfter
import com.offseason.engine.isBefore
import com.offseason.engine.plusDays
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Hr
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/** Other sports on a day, shown greyed for context. Planned runs are shown as sessions, so skipped here. */
fun otherSports(day: DayOfWeek, schedule: AthleteSchedule?, planned: Boolean = false): String {
    if (schedule == null) return ""
    val parts = mutableListOf<String>()
    if (day in schedule.swimDays) parts += "Swim"
    if (planned) return parts.joinToString(" · ")
    if (day == schedule.longRunDay) parts += "Long run"
    else if (day in schedule.hardRunDays) parts += "Hard run"
    else if (day in schedule.runDays) parts += "Run"
    return parts.joinToString(" · ")
}

// ---- This week ----

@Composable
fun ThisWeekScreen(plan: Plan, data: AppData, today: LocalDate, status: Status.Message) {
    val week = plan.weekFor(today) ?: if (today.isBefore(plan.startDate)) plan.weeks.first() else plan.weeks.last()
    val schedule = data.profile?.toPlanRequest()?.schedule
    val hasBike = plan.request.hasBike
    val hasRun = plan.request.hasRun
    Div({ classes("app", "stack") }) {
        Div {
            H1 { Text("Hi ${data.profile?.name ?: ""}") }
            Muted(
                when {
                    today.isBefore(plan.startDate) -> "Your plan starts ${plan.startDate.shortDay()}."
                    today.isAfter(plan.endDate) -> "Plan complete. Well done!"
                    else -> "Week ${week.index + 1} of ${plan.weeks.size} · ${week.title}"
                },
            )
        }
        if (hasRun && data.currentLthr == null) Card {
            Div({ classes("tight") }) {
                H3 { Text("Find your LTHR") }
                P { Text("Your run zones are based on LTHR. Do the 30-minute test in your first week. Until then, run easy by feel.") }
                Btn("LTHR test", "primary") { AppState.go(Route.LthrTest(null)) }
            }
        }
        if (hasBike && data.currentFtp == null) Card {
            Div({ classes("tight") }) {
                H3 { Text("First, find your FTP") }
                P { Text("Every target in your plan is based on FTP. Do the 20-minute test, then enter your result.") }
                Btn("FTP test", "primary") { AppState.go(Route.FtpTest(null)) }
            }
        }
        TrafficLight(status)
        Div({ classes("row") }) {
            H3({ classes("grow") }) { Text("${week.startDate.shortDay()} – ${week.endDate.shortDay()}") }
            if (hasBike) Btn("⬇ Week's rides", "auto") { Files.shareWeek(week) }
        }
        (0..6).map { week.startDate.plusDays(it) }.forEach { date ->
            val isToday = date == today
            Card(if (isToday) "card today" else "card low") {
                Div({ classes("row") }) {
                    Span({ classes("grow"); if (isToday) style { property("font-weight", "700") } }) {
                        Text((if (isToday) "Today · " else "") + date.shortDay())
                    }
                    val other = otherSports(date.dayOfWeek, schedule, planned = hasRun)
                    if (other.isNotEmpty()) Span({ classes("other") }) { Text(other) }
                }
                val sessions = week.sessions.filter { it.date == date }
                val runs = week.runs.filter { it.date == date }
                if (sessions.isEmpty() && runs.isEmpty()) Muted(if (hasRun && hasBike) "Rest day" else if (hasRun) "No run" else "No bike session")
                sessions.forEach { s -> SessionRow(s, data) }
                runs.forEach { r -> RunRow(r, data) }
            }
        }
        if (hasBike) Muted("Download the rides, then load them into Zwift, MyWhoosh, ROUVY or TPV. See Settings › How-to guides.")
    }
}

@Composable
private fun SessionRow(s: PlannedSession, data: AppData) {
    Div({ classes("row", "session"); onClick { AppState.go(Route.Session(s.id)) } }) {
        Span({ classes("bar"); style { property("background", Colors.kind(s.workout.kind)) } })
        Div({ classes("grow") }) {
            P({ style { property("font-weight", "600") } }) { Text(s.workout.name + if (s.optional) " (optional)" else "") }
            Muted(minutesLabel(s.workout.durationMin) + if (data.currentFtp == null) " · enter FTP for watts" else "")
        }
        if (s.id in data.completions) Span({ classes("tick") }) { Text("✓") }
        Span({ classes("muted") }) { Text("›") }
    }
}

@Composable
private fun RunRow(r: com.offseason.engine.PlannedRun, data: AppData) {
    Div({ classes("row", "session"); onClick { AppState.go(Route.Run(r.id)) } }) {
        Span({ classes("bar"); style { property("background", runKindColor(r.workout.kind)) } })
        Div({ classes("grow") }) {
            P({ style { property("font-weight", "600") } }) { Text("Run · " + r.workout.name) }
            Muted(minutesLabel(r.workout.durationMin) + if (data.currentLthr == null) " · by feel until LTHR test" else "")
        }
        if (r.id in data.completions) Span({ classes("tick") }) { Text("✓") }
        Span({ classes("muted") }) { Text("›") }
    }
}

// ---- Plan overview ----

@Composable
fun PlanScreen(plan: Plan, data: AppData, today: LocalDate) {
    val schedule = data.profile?.toPlanRequest()?.schedule
    Div({ classes("app", "stack") }) {
        H1 { Text("Your plan") }
        plan.weeks.forEach { week ->
            val current = week.contains(today)
            Card(if (current) "card today" else "card") {
                Div({ classes("row") }) {
                    Span({ classes("dot"); style { property("background", if (week.isRecovery) "#6FCF97" else Colors.kind(SessionKind.INTERVALS)) } })
                    Span({ classes("grow"); style { property("font-weight", "600") } }) { Text("Week ${week.index + 1} · ${week.title}") }
                    Span({ classes("muted") }) { Text(week.startDate.dayMonth()) }
                }
                Div({ classes("weekgrid") }) {
                    (0..6).forEach { i ->
                        val date = week.startDate.plusDays(i)
                        val s = week.sessions.firstOrNull { it.date == date }
                        val r = week.runs.firstOrNull { it.date == date }
                        Div({
                            classes(*(if (s != null) arrayOf("day", "has") else arrayOf("day")))
                            if (s != null) {
                                style { property("background", Colors.kind(s.workout.kind)) }
                                onClick { AppState.go(Route.Session(s.id)) }
                            }
                        }) {
                            Div { Text(date.dayOfWeek.short().take(2)) }
                            Div {
                                Text(
                                    when {
                                        s == null -> if (r == null) otherSports(date.dayOfWeek, schedule, planned = plan.request.hasRun).split(" ").firstOrNull()?.take(4) ?: "" else ""
                                        s.workout.kind == SessionKind.FTP_TEST -> "TEST"
                                        s.id in data.completions -> "✓"
                                        else -> "${s.workout.durationMin}'"
                                    },
                                )
                            }
                            if (r != null) Span({
                                classes("runchip")
                                style { property("background", runKindColor(r.workout.kind)) }
                                onClick { it.stopPropagation(); AppState.go(Route.Run(r.id)) }
                            }) {
                                Text(
                                    when {
                                        r.workout.kind == com.offseason.engine.RunKind.LTHR_TEST -> "LTHR"
                                        r.id in data.completions -> "R✓"
                                        else -> "R${r.workout.durationMin}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Session detail ----

private fun pctRange(low: Int, high: Int?) = if (high == null || high == low) "$low%" else "$low–$high%"
private fun watts(pct: Number, ftp: Int?) = ftp?.let { "${Ftp.watts(pct, it)} W" } ?: "${pct.toDouble().roundToInt()}%"
private fun wattRange(low: Int, high: Int?, ftp: Int?) =
    if (ftp == null) pctRange(low, high)
    else if (high == null || high == low) "${Ftp.watts(low, ftp)} W"
    else "${Ftp.watts(low, ftp)}–${Ftp.watts(high, ftp)} W"
private fun dur(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

private fun describe(step: Step, ftp: Int?, zone: Zone): Triple<String, String, String> {
    val cad = step.cadenceTarget?.let { " · $it rpm" } ?: ""
    val pct = pctRange(step.powerPctLow, step.powerPctHigh)
    val ramp = "${watts(step.powerPctLow, ftp)} → ${watts(step.powerPctHigh ?: step.powerPctLow, ftp)}"
    return when (step.type) {
        StepType.WARMUP -> Triple(step.label ?: "Warm-up ${dur(step.durationSec)}", ramp, "$pct FTP$cad")
        StepType.COOLDOWN -> Triple(step.label ?: "Cool-down ${dur(step.durationSec)}", ramp, "$pct FTP$cad")
        StepType.RAMP -> Triple(step.label ?: "Ramp ${dur(step.durationSec)}", ramp, "$pct FTP$cad")
        StepType.STEADY -> Triple(
            (step.label?.let { "$it · " } ?: "") + "${dur(step.durationSec)} steady",
            wattRange(step.powerPctLow, step.powerPctHigh, ftp), "$pct FTP · ${zone.displayName}$cad",
        )
        StepType.INTERVALS -> Triple(
            (step.label?.let { "$it · " } ?: "") + "${step.repeat} × ${dur(step.durationSec)} on / ${dur(step.offDurationSec!!)} easy",
            wattRange(step.powerPctLow, step.powerPctHigh, ftp) + " / " + watts(step.offPowerPct!!, ftp),
            "$pct FTP · ${zone.displayName}$cad",
        )
        StepType.FREE_RIDE -> Triple(step.label ?: "Free ride ${dur(step.durationSec)}", "Max effort", "No target. Ride by feel$cad")
    }
}

@Composable
fun StepList(w: Workout, ftp: Int?) = Card {
    Div({ classes("stack") }) {
        w.steps.forEach { step ->
            val zone = w.plannedZone(step)
            val (title, wt, small) = describe(step, ftp, zone)
            Div({ classes("row") }) {
                Span({ classes("dot"); style { property("background", Colors.zone(zone)) } })
                Div({ classes("grow") }) { P { Text(title) }; Muted(small) }
                Span({ classes("right") }) { Text(wt) }
            }
        }
    }
}

@Composable
fun DownloadButtons(w: Workout) = Card {
    Div({ classes("tight") }) {
        H3 { Text("Download for your trainer app") }
        Btn("⬇ Zwift / MyWhoosh (.zwo)") { Files.shareZwo(w) }
        Btn("⬇ ROUVY / TPV (.mrc)") { Files.shareMrc(w) }
        Muted("On iPhone, choose Save to Files, or Mail it to yourself. Not sure how? Settings › How-to guides.")
    }
}

@Composable
fun SessionScreen(plan: Plan, data: AppData, id: String) {
    val session = plan.session(id) ?: run { AppState.back(); return }
    val w = session.workout
    val ftp = data.currentFtp
    val done = data.completions[id]
    var askRpe by remember { mutableStateOf(false) }

    Div({ classes("app", "stack") }) {
        TopBar("Session")
        Muted(session.date.longDay() + " · " + minutesLabel(w.durationMin) + if (session.optional) " · optional" else "")
        H1 { Text(w.name) }
        P { Text(w.purpose) }
        if (ftp == null) Muted("Targets are shown as % of FTP until you enter your FTP.")
        Card {
            WorkoutProfile(w)
            Muted("Bar height = power. Dashed line = your FTP${ftp?.let { " ($it W)" } ?: ""}.")
        }
        StepList(w, ftp)
        DownloadButtons(w)

        when {
            done != null -> Card {
                Div({ classes("row") }) {
                    Span({ classes("tick") }) { Text("✓") }
                    Span({ classes("grow") }) { Text("Done · felt ${done.rpe.label.lowercase()}") }
                    Btn("Change", "text") { askRpe = true }
                    Btn("Undo", "text") { AppState.unmarkDone(id) }
                }
                done.ride?.let { RideResult(it, w, ftp) }
            }
            w.kind == SessionKind.FTP_TEST -> Btn("Enter my test result", "primary") { AppState.go(Route.FtpTest(id)) }
            else -> Btn("Mark done", "primary") { askRpe = true }
        }

        if (done != null && AppState.justRated == id && Logic.shouldOfferEasing(plan, data, id)) {
            Card {
                Div({ classes("tight") }) {
                    H3 { Text("Sessions feeling too hard?") }
                    P {
                        Text(
                            "Your last couple of interval sessions felt hard. That can mean your FTP is set a bit high, or you're " +
                                "carrying fatigue from swimming, running or life. Easing off now beats burning out in January.",
                        )
                    }
                    Btn("Lower my targets by ${Adaptation.EASE_PCT}%", "primary") {
                        AppState.lowerTargets(); AppState.showToast("Targets lowered. Download upcoming sessions again for your trainer app.")
                    }
                    if (Logic.canRepeatWeekOf(plan, id)) Btn("Repeat this week") {
                        AppState.repeatWeekOf(id); AppState.showToast("This week will repeat next week.")
                    }
                    Btn("No thanks, keep going", "text") { AppState.declineEasing(id) }
                    Muted("Lowering targets lasts until your next FTP test. Repeating the week pushes the rest of the plan back by a week.")
                }
            }
        }

        if (done?.ride == null) {
            Btn("⬆ Add ride file (.fit). Optional") {
                Files.pickRide { r ->
                    r.onSuccess { AppState.attachRide(id, it); AppState.showToast("Ride added. How did it feel?") }
                        .onFailure { AppState.showToast(it.message ?: "Couldn't read that file.") }
                }
            }
            Muted("Adding the .fit file from your ride gives Off-Season your real power, so your progress is more accurate. You don't have to: \"Mark done\" is fine too.")
        }
    }

    if (askRpe) Div({ classes("overlay"); onClick { askRpe = false } }) {
        Div({ classes("dialog"); onClick { it.stopPropagation() } }) {
            H3 { Text("How did it feel?") }
            Rpe.entries.forEach { r -> Btn(r.label) { AppState.markDone(id, r); askRpe = false } }
            Muted("Be honest! If sessions keep feeling too hard, Off-Season will offer to ease off.")
            Btn("Cancel", "text") { askRpe = false }
        }
    }
}

@Composable
private fun RideResult(ride: RideStats, w: Workout, ftp: Int?) {
    Hr()
    Div({ classes("tight") }) {
        H3 { Text("From your ride file") }
        P { Text("${minutesLabel(ride.durationSec / 60)} ridden (planned ${minutesLabel(w.durationMin)})") }
        if (ride.avgPower == null) {
            Muted("No power in this file, so we've counted it as the planned session.")
            return@Div
        }
        P { Text("Average ${ride.avgPower} W · Weighted ${ride.weightedPower} W") }
        val rideFtp = ride.ftpAtRide ?: ftp
        val weighted = ride.weightedPower
        if (rideFtp != null && weighted != null && w.kind != SessionKind.FTP_TEST) {
            val pct = weighted * 100.0 / (Load.effortScore(w) * rideFtp)
            Muted(
                when {
                    pct >= 97 -> "Right on target. Nicely done."
                    pct >= 90 -> "A little under the planned effort (${pct.toInt()}%). Fine for a hard day."
                    else -> "Quite a bit under the planned effort (${pct.toInt()}%). If that keeps happening, your targets may be too high."
                },
            )
        }
        ride.best20MinPower?.takeIf { w.kind == SessionKind.FTP_TEST }?.let {
            P({ style { property("color", "var(--primary)") } }) { Text("Best 20 minutes: $it W → FTP ${Ftp.from20MinTest(it)} W") }
        }
    }
}
