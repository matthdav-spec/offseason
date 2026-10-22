package com.offseason.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.offseason.engine.Css
import com.offseason.engine.Plan
import com.offseason.engine.SwimKind
import com.offseason.engine.SwimLibrary
import com.offseason.engine.SwimStep
import com.offseason.engine.SwimStepType
import com.offseason.engine.SwimWorkout
import com.offseason.engine.SwimZone
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Hr
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

fun swimZoneColor(z: SwimZone) = when (z) {
    SwimZone.EASY -> "#8A9BA8"
    SwimZone.STEADY -> "#4A90D9"
    SwimZone.TEMPO -> "#5CC08A"
    SwimZone.CSS -> "#E6C54A"
    SwimZone.FAST -> "#E5584F"
}

fun swimKindColor(k: SwimKind) = when (k) {
    SwimKind.TECHNIQUE, SwimKind.BUILD_UP -> "#5BC0BE"
    SwimKind.ENDURANCE -> "#3A9AD9"
    SwimKind.CSS_SET -> "#E6C54A"
    SwimKind.CSS_TEST -> "#E5584F"
}

private fun rest(sec: Int) = if (sec >= 60) "${sec / 60}:${(sec % 60).toString().padStart(2, '0')} rest" else "$sec s rest"

/** One line of the set, poolside-sized: "6 × 100 m" big, then what to do and the pace. */
private fun swimLine(step: SwimStep, css: Int?, beginner: Boolean): Triple<String, String, String> {
    val dist = if (step.repeat > 1) "${step.repeat} × ${step.distanceM} m" else "${step.distanceM} m"
    val what = step.label ?: when (step.type) {
        SwimStepType.WARMUP -> "Warm-up"
        SwimStepType.COOLDOWN -> "Cool-down"
        SwimStepType.KICK -> "Kick"
        SwimStepType.PULL -> "Pull"
        SwimStepType.DRILLS -> "Drills"
        else -> step.zone.displayName
    }
    val pace = when {
        step.type == SwimStepType.TEST -> "Timed: best even effort"
        beginner || css == null -> step.zone.displayName + (if (step.repeat > 1 && step.restSec > 0) " · " + (if (beginner) "rest as needed" else rest(step.restSec)) else "")
        else -> "${Css.format(step.zone.paceSec(css))}/100 m" + (if (step.repeat > 1 && step.restSec > 0) " · " + rest(step.restSec) else "")
    }
    return Triple(dist, what, pace)
}

@Composable
private fun SwimSet(w: SwimWorkout, css: Int?) = Card {
    val beginner = w.kind == SwimKind.BUILD_UP
    Div({ classes("stack") }) {
        w.steps.forEach { step ->
            val (dist, what, pace) = swimLine(step, css, beginner)
            Div({ classes("row") }) {
                Span({ classes("bar"); style { property("background", swimZoneColor(step.zone)) } })
                Div({ classes("grow") }) {
                    P({ classes("swimdist") }) { Text(dist) }
                    P { Text(what) }
                    Muted(pace)
                }
            }
        }
        Hr()
        Div({ classes("row") }) {
            Span({ classes("grow"); style { property("font-weight", "600") } }) { Text("Total ${w.distanceM} m") }
            Span({ classes("muted") }) { Text("about ${w.estimatedMin(css)} min") }
        }
    }
}

@Composable
fun SwimScreen(plan: Plan, data: AppData, id: String) {
    val planned = plan.swim(id)
    if (planned == null) { AppState.back(); return }
    val w = planned.workout
    val css = data.currentCss
    val done = data.completions[id]
    var askRpe by remember { mutableStateOf(false) }
    var askDistance by remember { mutableStateOf(false) }
    val continuous = w.kind == SwimKind.BUILD_UP && w.steps.any { it.type == SwimStepType.SWIM }

    Div({ classes("app", "stack") }) {
        TopBar("Swim")
        Muted(planned.date.longDay() + " · Swim · ${w.distanceM} m")
        H1 { Text(w.name) }
        P { Text(w.purpose) }
        if (css == null && w.kind != SwimKind.BUILD_UP && w.kind != SwimKind.CSS_TEST) Muted("Paces appear once you've done the CSS test. For now, go by the effort words.")
        SwimSet(w, css)
        Muted("Tip: screenshot this before you go, or read it out to yourself at the end of each length.")

        when {
            done != null -> Card {
                Div({ classes("row") }) {
                    Span({ classes("tick") }) { Text("✓") }
                    Span({ classes("grow") }) { Text("Done · felt ${done.rpe.label.lowercase()}") }
                    Btn("Change", "text") { askRpe = true }
                    Btn("Undo", "text") { AppState.unmarkDone(id) }
                }
            }
            w.kind == SwimKind.CSS_TEST -> Btn("Enter my test times", "primary") { AppState.go(Route.CssTest(id)) }
            continuous -> Btn("Mark done", "primary") { askDistance = true }
            else -> Btn("Mark done", "primary") { askRpe = true }
        }
        if (Logic.readyToMoveUp(data)) MoveUpCard(plan)
    }

    if (askDistance) Div({ classes("overlay"); onClick { askDistance = false } }) {
        Div({ classes("dialog"); onClick { it.stopPropagation() } }) {
            H3 { Text("How far did you swim without stopping?") }
            val target = w.steps.first { it.type == SwimStepType.SWIM }.distanceM
            listOf(target, target * 3 / 4, target / 2, target / 4).map { (it / 25) * 25 }.distinct().forEach { m ->
                Btn(if (m == target) "All $m m!" else "$m m") {
                    AppState.recordSwimDistance(m); askDistance = false; askRpe = true
                }
            }
            Btn("Cancel", "text") { askDistance = false }
        }
    }
    if (askRpe) Div({ classes("overlay"); onClick { askRpe = false } }) {
        Div({ classes("dialog"); onClick { it.stopPropagation() } }) {
            H3 { Text("How did it feel?") }
            Rpe.entries.forEach { r -> Btn(r.label) { AppState.markDone(id, r); askRpe = false } }
            Btn("Cancel", "text") { askRpe = false }
        }
    }
}

@Composable
fun MoveUpCard(plan: Plan) = Card("card today") {
    Div({ classes("tight") }) {
        H3 { Text("🎉 You swam 400 m without stopping!") }
        P { Text("That's the goal of the build-up plan. You're ready to move up to the CSS plan: from next week your swims get paces based on your own speed. It starts with the CSS test.") }
        Btn("Move up to the CSS plan", "primary") { AppState.moveUpToCss(); AppState.showToast("Moved up. Next week starts with your CSS test.") }
        Muted("Not ready? Carry on as you are. This offer stays here.")
    }
}

/** A distance check (recovery week on the build-up route): log how far without stopping. */
@Composable
fun SwimDistanceButtons(onPicked: (Int) -> Unit) = Div({ classes("chips") }) {
    listOf(50, 100, 150, 200, 250, 300, 350, 400).forEach { m -> Btn("$m m", "chip") { onPicked(m) } }
}

@Composable
fun CssTestScreen(data: AppData, swimId: String?) {
    val test = SwimLibrary.cssTest()
    var t400 by remember { mutableStateOf("") }
    var t200 by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Int?>(null) }
    val previous = remember { data.currentCss }
    val a = Css.parse(t400)
    val b = Css.parse(t200)
    val css = if (a != null && b != null && a > b && a in 180..1200 && b in 80..600) Css.fromTest(a, b) else null
    Div({ classes("app", "stack") }) {
        TopBar("CSS test")
        H1 { Text("CSS test") }
        P { Text(test.purpose) }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("How to do it") }
                P { Text("1. Warm up: 400 m easy, then 4 × 50 m building faster.") }
                P { Text("2. Swim 400 m as fast as you can hold evenly. Note the time.") }
                P { Text("3. Rest with 5 minutes of easy swimming.") }
                P { Text("4. Swim 200 m flat out but even. Note the time.") }
                P { Text("5. Easy cool-down.") }
                Muted("Push off the wall each time (no dive). Ask a friend or the lifeguard to time you, or use a waterproof watch.")
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Your times") }
                Input(InputType.Text) { value(t400); attr("placeholder", "400 m time, e.g. 8:20"); attr("inputmode", "numeric"); onInput { t400 = it.value } }
                Input(InputType.Text) { value(t200); attr("placeholder", "200 m time, e.g. 3:55"); attr("inputmode", "numeric"); onInput { t200 = it.value } }
                if (css != null) {
                    P({ style { property("font-size", "22px"); property("color", "var(--primary)") } }) { Text("Your CSS: ${Css.format(css)}/100 m") }
                    Muted("Half the difference between your two times.")
                } else if (t400.isNotBlank() && t200.isNotBlank()) Muted("Type times like 8:20. The 400 m time must be slower than the 200 m.")
                Btn("Save CSS", "primary", enabled = css != null && saved == null) {
                    AppState.recordCss(css!!, a, b)
                    swimId?.let { id -> if (id !in AppState.data.completions) AppState.markDone(id, Rpe.HARD) }
                    saved = css
                }
            }
        }
        saved?.let { s ->
            Card {
                Div({ classes("tight") }) {
                    H3 { Text("Saved. Your swim paces now use ${Css.format(s)}/100 m.") }
                    if (previous != null && previous != s) P {
                        Text(if (s < previous) "That's ${previous - s} s per 100 m faster than last time. Great work." else "A little slower than last time (${Css.format(previous)}). Tiredness and a busy pool both show up here. The paces now match you today.")
                    }
                }
            }
            SwimZonesCard(s)
        }
    }
}

@Composable
fun SwimZonesCard(css: Int?) = Card {
    Div({ classes("stack") }) {
        H3 { Text("Your swim paces") }
        if (css == null) Muted("Do the CSS test to see your paces per 100 m. Until then, go by the effort words.")
        SwimZone.entries.forEach { z ->
            Div({ classes("row") }) {
                Span({ classes("dot"); style { property("background", swimZoneColor(z)) } })
                Div({ classes("grow") }) { P { Text(z.displayName) }; Muted(z.feel) }
                Span({ classes("right") }) { Text(Css.describe(z, css)) }
            }
        }
    }
}

@Composable
fun SwimProgressCard(plan: Plan, data: AppData) = Card {
    Div({ classes("tight") }) {
        val p = data.profile ?: return@Div
        val onBuildUp = p.swimLevel == com.offseason.engine.SwimLevel.BUILD_UP.name && p.swimCssFrom == null
        H3 { Text("Swim") }
        if (onBuildUp) {
            val best = data.bestContinuousM ?: 0
            P({ style { property("font-size", "34px"); property("color", "var(--primary)") } }) { Text(if (best > 0) "$best m" else "–") }
            P { Text(if (best >= 400) "Without stopping. You've hit the 400 m goal!" else "Your longest swim without stopping. Goal: 400 m.") }
            Div({ classes("progress") }) { Div({ style { property("width", "${(best.coerceAtMost(400) * 100 / 400)}%") } }) }
            if (data.swimDistanceHistory.size > 1) {
                Hr()
                data.swimDistanceHistory.sortedBy { it.date }.forEach {
                    Div({ classes("row") }) { Span({ classes("grow") }) { Text(LocalDate.parse(it.date).dayMonth()) }; Text("${it.distanceM} m") }
                }
            }
        } else {
            val now = data.currentCss
            val start = data.startingCss
            if (now == null) P { Text("Do the CSS test to see your swim pace.") }
            else {
                P({ style { property("font-size", "34px"); property("color", "var(--primary)") } }) { Text("${Css.format(now)}/100 m") }
                if (start != null && start != now) P { Text("Started at ${Css.format(start)}. That's ${start - now} s per 100 m ${if (now < start) "faster" else "slower"}.") }
                else P { Text("Your CSS pace. Retest at the end of each block.") }
            }
        }
        Muted("${plan.swims.count { it.id in data.completions }} of ${plan.swims.size} swims done.")
    }
}

@Composable
fun SwimHelp(css: Int?) = Div({ classes("app", "stack") }) {
    TopBar("How-to")
    H1 { Text("Swimming with DBAP") }
    P { Text("Your swims are pool sessions written as sets, like \"6 × 100 m, 20 s rest\". Each one shows the distance, what to do, and how hard.") }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("If you're building up to 400 m") }
            P { Text("Swim the set distances at an easy pace, resting as long as you need between each. Each week the swims get a little longer.") }
            P { Text("When you swim 400 m without stopping, DBAP offers to move you up to the CSS plan. You choose when.") }
        }
    }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("CSS: your swim pace") }
            P { Text("CSS (Critical Swim Speed) is roughly the pace you could hold for 1500 m. Time a 400 m and a 200 m; your CSS is half the difference. For example, 8:20 and 3:55 gives 2:12/100 m.") }
            P { Text("You'll retest at the end of each 4-week block, alongside your bike and run tests.") }
        }
    }
    SwimZonesCard(css)
    Card {
        Div({ classes("tight") }) {
            H3 { Text("Tips") }
            P { Text("• Breathe out steadily under water, so you only need to breathe in when you turn your head.") }
            P { Text("• Count strokes per length on the easy swims. Fewer strokes at the same effort means better technique.") }
            P { Text("• Use the pace clock on the wall for rests, or just count breaths.") }
        }
    }
}
