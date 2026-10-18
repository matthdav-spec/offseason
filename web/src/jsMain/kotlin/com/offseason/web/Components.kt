package com.offseason.web

import androidx.compose.runtime.Composable
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.Step
import com.offseason.engine.StepType
import com.offseason.engine.Workout
import com.offseason.engine.Zone
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.ElementScope
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import org.jetbrains.compose.web.svg.Line
import org.jetbrains.compose.web.svg.Path
import org.jetbrains.compose.web.svg.Rect
import org.jetbrains.compose.web.svg.Svg
import org.w3c.dom.HTMLDivElement

// ---- Colours (same as the Android app) ----

object Colors {
    fun light(l: Status.Light) = when (l) {
        Status.Light.GREEN -> "#6FCF97"
        Status.Light.AMBER -> "#F2C14E"
        Status.Light.RED -> "#EB6F6F"
    }

    fun zone(z: Zone) = when (z) {
        Zone.Z1 -> "#8A9BA8"
        Zone.Z2 -> "#4A90D9"
        Zone.Z3 -> "#5CC08A"
        Zone.SS -> "#E6C54A"
        Zone.Z4 -> "#F09A3E"
        Zone.Z5 -> "#E5584F"
        Zone.Z6, Zone.Z7 -> "#B84FD1"
    }

    fun kind(k: SessionKind) = when (k) {
        SessionKind.INTERVALS -> "#F09A3E"
        SessionKind.FTP_TEST -> "#E5584F"
        SessionKind.TEMPO -> "#5CC08A"
        SessionKind.ENDURANCE -> "#4A90D9"
        SessionKind.CADENCE -> "#7FB3D5"
        SessionKind.EASY, SessionKind.RECOVERY -> "#8A9BA8"
    }
}

// ---- Dates and numbers as text ----

private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

fun DayOfWeek.short() = DAY_SHORT[ordinal]
fun LocalDate.dayMonth() = "$day ${MONTH_SHORT[month.ordinal]}"                    // 20 Oct
fun LocalDate.shortDay() = "${dayOfWeek.short()} ${dayMonth()}"                     // Tue 20 Oct
fun LocalDate.longDay() = "${DAY_LONG[dayOfWeek.ordinal]} $day ${MONTH_LONG[month.ordinal]}" // Tuesday 20 October
fun LocalDate.withYear() = "${dayMonth()} $year"

fun minutesLabel(min: Int): String = if (min >= 60) "${min / 60}h" + (if (min % 60 > 0) " ${min % 60}m" else "") else "$min min"

fun oneDp(x: Double): String = com.offseason.engine.fixed(x, 1)
fun signedOneDp(x: Double): String = (if (x >= 0) "+" else "") + oneDp(x)

// ---- Building blocks ----

@Composable
fun Card(classes: String = "card", content: @Composable ElementScope<HTMLDivElement>.() -> Unit) =
    Div({ classes(*classes.split(" ").toTypedArray()) }, content)

@Composable
fun Muted(text: String) = P({ classes("muted") }) { Text(text) }

@Composable
fun Btn(label: String, kind: String = "", enabled: Boolean = true, onClick: () -> Unit) =
    Button({
        if (kind.isNotEmpty()) classes(*kind.split(" ").toTypedArray())
        if (!enabled) disabled()
        onClick { onClick() }
    }) { Text(label) }

@Composable
fun Chips(options: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) = Div({ classes("chips") }) {
    options.forEachIndexed { i, (label, on) ->
        Button({ classes(*(if (on) arrayOf("chip", "on") else arrayOf("chip"))); onClick { onClick(i) } }) { Text(label) }
    }
}

@Composable
fun DayPicker(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit, enabled: (DayOfWeek) -> Boolean = { true }) =
    Div({ classes("chips") }) {
        DayOfWeek.entries.forEach { d ->
            Button({
                classes(*(if (d in selected) arrayOf("chip", "on") else arrayOf("chip")))
                if (!enabled(d)) disabled()
                onClick { onChange(if (d in selected) selected - d else selected + d) }
            }) { Text(d.short()) }
        }
    }

@Composable
fun TrafficLight(m: Status.Message) = Card {
    Div({ classes("row") }) {
        Span({ classes("light"); style { property("background", Colors.light(m.light)) } })
        Span({ classes("grow"); style { property("font-weight", "600"); property("font-size", "17px") } }) { Text(m.headline) }
    }
    P({ classes("muted"); style { property("margin-top", "8px") } }) { Text(m.detail) }
}

@Composable
fun TopBar(title: String) = Div({ classes("topbar") }) {
    Button({ onClick { AppState.back() }; attr("aria-label", "Back") }) { Text("←") }
    org.jetbrains.compose.web.dom.H2 { Text(title) }
}

// ---- Workout profile chart ----

private fun segments(steps: List<Step>): List<Triple<Int, Double, Double>> = buildList {
    for (s in steps) when (s.type) {
        StepType.WARMUP, StepType.COOLDOWN, StepType.RAMP ->
            add(Triple(s.durationSec, s.powerPctLow.toDouble(), (s.powerPctHigh ?: s.powerPctLow).toDouble()))
        StepType.INTERVALS -> repeat(s.repeat!!) {
            add(Triple(s.durationSec, s.targetPct, s.targetPct))
            val off = s.offPowerPct!!.toDouble()
            add(Triple(s.offDurationSec!!, off, off))
        }
        StepType.STEADY, StepType.FREE_RIDE -> add(Triple(s.durationSec, s.targetPct, s.targetPct))
    }
}

/** Bar height = % FTP, colour = zone (as planned), dashed line at FTP. */
@OptIn(ExperimentalComposeWebSvgApi::class)
@Composable
fun WorkoutProfile(w: Workout, height: Int = 110) {
    val segs = segments(w.steps)
    val total = segs.sumOf { it.first }.toDouble()
    val maxPct = maxOf(125.0, segs.maxOf { maxOf(it.second, it.third) } + 5)
    val width = 1000.0
    fun y(p: Double) = height - p / maxPct * height
    Svg(viewBox = "0 0 1000 $height", attrs = { classes("profile"); attr("preserveAspectRatio", "none") }) {
        var x = 0.0
        for ((dur, from, to) in segs) {
            val segW = dur / total * width
            val color = Colors.zone(Zone.of((from + to) / 2 / w.targetScale))
            if (from == to) Rect(x, y(from), segW, height - y(from), { attr("fill", color) })
            else Path("M$x,$height L$x,${y(from)} L${x + segW},${y(to)} L${x + segW},$height Z", { attr("fill", color) })
            x += segW
        }
        Line(0, y(100.0), width, y(100.0), {
            attr("stroke", "#3A4D5E"); attr("stroke-width", "2"); attr("stroke-dasharray", "8 8")
            attr("vector-effect", "non-scaling-stroke")
        })
    }
}
