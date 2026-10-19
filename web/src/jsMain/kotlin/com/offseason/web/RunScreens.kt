package com.offseason.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.offseason.engine.Lthr
import com.offseason.engine.Plan
import com.offseason.engine.RunKind
import com.offseason.engine.RunLibrary
import com.offseason.engine.RunStep
import com.offseason.engine.RunStepType
import com.offseason.engine.RunWorkout
import com.offseason.engine.RunZone
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

// Same wording as the Android run screens (app/.../ui/RunScreens.kt).

fun runZoneColor(z: RunZone) = when (z) {
    RunZone.Z1 -> "#8A9BA8"
    RunZone.Z2 -> "#4A90D9"
    RunZone.Z3 -> "#5CC08A"
    RunZone.Z4 -> "#E6C54A"
    RunZone.Z5A -> "#F09A3E"
    RunZone.Z5B -> "#E5584F"
    RunZone.Z5C -> "#B84FD1"
}

fun runKindColor(k: RunKind) = when (k) {
    RunKind.EASY, RunKind.STRIDES -> "#4A90D9"
    RunKind.LONG -> "#3A7CA5"
    RunKind.HILLS, RunKind.TEMPO -> "#5CC08A"
    RunKind.THRESHOLD -> "#E6C54A"
    RunKind.LTHR_TEST -> "#E5584F"
}

private fun dur(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else if (sec < 60) "$sec s" else "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

private fun describeRun(step: RunStep, lthr: Int?): Triple<String, String, String> {
    val hr = Lthr.describe(step.zones, lthr)
    val zoneNames = if (step.zoneLow == step.zoneHigh) "${step.zoneLow.label} · ${step.zoneLow.displayName}" else "${step.zoneLow.label}–${step.zoneHigh.label}"
    return when (step.type) {
        RunStepType.WARMUP, RunStepType.COOLDOWN, RunStepType.STEADY ->
            Triple((step.label?.let { "$it · " } ?: "") + dur(step.durationSec), hr, zoneNames)
        RunStepType.INTERVALS ->
            Triple("${step.repeat} × ${dur(step.durationSec)}, ${dur(step.offDurationSec!!)} easy jog between", hr, "$zoneNames · ${step.zoneHigh.feel}")
        RunStepType.STRIDES ->
            Triple(step.label ?: "${step.repeat} × ${dur(step.durationSec)} strides", "By feel", "Too short for heart rate to catch up. Quick, relaxed and smooth, not a sprint")
        RunStepType.TIME_TRIAL ->
            Triple(step.label ?: "Time trial ${dur(step.durationSec)}", "Max effort", "Even pace. Hardest you can hold for the full 30 minutes")
    }
}

@Composable
private fun RunProfile(w: RunWorkout) {
    val segs = buildList {
        for (s in w.steps) {
            val rep = s.repeat; val off = s.offDurationSec
            if (rep != null && off != null) repeat(rep) { add(s.durationSec to s.zoneHigh); add(off to RunZone.Z1) }
            else add(s.durationSec to s.zoneHigh)
        }
    }
    val total = segs.sumOf { it.first }.toDouble()
    Div({ style { property("display", "flex"); property("align-items", "flex-end"); property("height", "80px") } }) {
        segs.forEach { (d, z) ->
            Div({
                style {
                    property("width", "${d / total * 100}%")
                    property("height", "${(z.ordinal + 1) * 100 / RunZone.entries.size}%")
                    property("background", runZoneColor(z))
                }
            })
        }
    }
}

@Composable
fun RunZonesCard(lthr: Int?) = Card {
    Div({ classes("stack") }) {
        H3 { Text("Your run zones") }
        if (lthr == null) Muted("Do the LTHR test to see your zones in beats per minute. Until then, use the descriptions.")
        RunZone.entries.forEach { z ->
            Div({ classes("row") }) {
                Span({ classes("dot"); style { property("background", runZoneColor(z)) } })
                Div({ classes("grow") }) { P { Text("${z.label} · ${z.displayName}") }; Muted(z.feel) }
                Span({ classes("right") }) { Text(Lthr.describe(z..z, lthr)) }
            }
        }
    }
}

@Composable
fun RunScreen(plan: Plan, data: AppData, id: String) {
    val planned = plan.run(id)
    if (planned == null) { AppState.back(); return }
    val w = planned.workout
    val lthr = data.currentLthr
    val done = data.completions[id]
    var askRpe by remember { mutableStateOf(false) }

    Div({ classes("app", "stack") }) {
        TopBar("Run")
        Muted(planned.date.longDay() + " · Run · " + minutesLabel(w.durationMin))
        H1 { Text(w.name) }
        P { Text(w.purpose) }
        if (lthr == null && w.kind != RunKind.LTHR_TEST) Muted("Heart rates appear once you've done the LTHR test. For now, go by the descriptions.")
        Card { RunProfile(w); Muted("Bar height = heart-rate zone.") }
        Card {
            Div({ classes("stack") }) {
                w.steps.forEach { step ->
                    val (title, target, small) = describeRun(step, lthr)
                    Div({ classes("row") }) {
                        Span({ classes("dot"); style { property("background", runZoneColor(step.zoneHigh)) } })
                        Div({ classes("grow") }) { P { Text(title) }; Muted(small) }
                        Span({ classes("right") }) { Text(target) }
                    }
                }
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Running by heart rate") }
                Muted("Heart rate takes a minute or two to settle at the start of each effort, so ease in rather than chasing the number. On hills, wind or a warm day, slow down to stay in the zone. That's the point.")
            }
        }
        when {
            done != null -> Card {
                Div({ classes("row") }) {
                    Span({ classes("tick") }) { Text("✓") }
                    Span({ classes("grow") }) { Text("Done · felt ${done.rpe.label.lowercase()}") }
                    Btn("Change", "text") { askRpe = true }
                    Btn("Undo", "text") { AppState.unmarkDone(id) }
                }
            }
            w.kind == RunKind.LTHR_TEST -> Btn("Enter my test result", "primary") { AppState.go(Route.LthrTest(id)) }
            else -> Btn("Mark done", "primary") { askRpe = true }
        }
        if (done != null && AppState.justRated == id && Logic.shouldOfferEasing(plan, data, id)) Card {
            Div({ classes("tight") }) {
                H3 { Text("Runs feeling too hard?") }
                P { Text("Your last couple of quality runs felt hard. Running fatigue builds up quietly, and pushing through is how niggles start.") }
                if (Logic.canRepeatWeekOf(plan, id)) Btn("Repeat this week", "primary") {
                    AppState.repeatWeekOf(id); AppState.showToast("This week will repeat next week.")
                }
                Btn("No thanks, keep going", "text") { AppState.declineEasing(id) }
                Muted("Repeating the week pushes the rest of the plan back by a week. If anything hurts (not just tired), rest and see a physio.")
            }
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
fun LthrTestScreen(data: AppData, runId: String?) {
    val test = RunLibrary.lthrTest()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Int?>(null) }
    val previous = remember { data.currentLthr }
    val value = text.toIntOrNull()
    Div({ classes("app", "stack") }) {
        TopBar("LTHR test")
        H1 { Text(if (previous != null) "LTHR retest" else "LTHR test") }
        P { Text(test.purpose) }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("The protocol (about 55 minutes)") }
                P { Text("1. 15 min easy warm-up, finishing with a few short, faster pick-ups.") }
                P { Text("2. Start your watch and run for 30 minutes as if it were a race: the hardest effort you can hold evenly for the whole time.") }
                P { Text("3. At 10 minutes, press LAP. This is the important bit.") }
                P { Text("4. At 30 minutes, stop and cool down with 10 min easy jogging or walking.") }
                P { Text("5. Your LTHR is the average heart rate of that second lap (the last 20 minutes).") }
                Muted("Run it alone, not in a race or with friends. Pick a flat route or a treadmill at 1% incline, on fresh legs (not the day after a hard session).")
            }
        }
        Card { RunProfile(test) }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Your result") }
                Input(InputType.Text) {
                    value(text); attr("inputmode", "numeric"); attr("placeholder", "Average heart rate, last 20 min (bpm)")
                    onInput { text = it.value.filter(Char::isDigit).take(3) }
                }
                Btn("Save LTHR", "primary", enabled = value != null && value in 100..220 && saved == null) {
                    AppState.recordLthr(value!!, "30min")
                    runId?.let { id -> if (id !in AppState.data.completions) AppState.markDone(id, Rpe.HARD) }
                    saved = value
                }
            }
        }
        saved?.let { lthr ->
            Card {
                Div({ classes("tight") }) {
                    H3 { Text("Saved. Your run zones now use $lthr bpm.") }
                    if (previous != null && previous != lthr) P {
                        Text(
                            if (lthr > previous) "Up from $previous bpm. A higher LTHR usually means you can now sustain a harder effort."
                            else "Down from $previous bpm. LTHR moves with fatigue, heat and the course. The best sign of progress is running faster at the same heart rate.",
                        )
                    }
                }
            }
            RunZonesCard(lthr)
        }
    }
}

@Composable
fun RunHelp(lthr: Int?) = Div({ classes("app", "stack") }) {
    TopBar("How-to")
    H1 { Text("Running by heart rate") }
    P { Text("Your run plan uses heart-rate zones based on your LTHR (lactate threshold heart rate). They work on any route, hilly or flat, and adjust to how you are on the day.") }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("Finding your LTHR") }
            P { Text("Run 30 minutes on your own, as hard as you can hold evenly, like a race. Press LAP at 10 minutes. Your LTHR is the average heart rate of the last 20 minutes. You'll redo it at the end of each 4-week block.") }
        }
    }
    RunZonesCard(lthr)
    Card {
        Div({ classes("tight") }) {
            H3 { Text("Tips") }
            P { Text("• Most of your running is Z1–Z2. It should feel too easy. That's what builds the engine without wrecking your bike sessions.") }
            P { Text("• Heart rate takes 1–2 minutes to rise at the start of an effort. Ease in rather than sprinting to the number.") }
            P { Text("• On hills, heat or tired days, slow down to stay in the zone, even if that means walking up a steep hill.") }
            P { Text("• Strides are too short for heart rate: run them quick and relaxed, by feel.") }
            P { Text("• A chest strap is more accurate than a wrist sensor, especially for intervals.") }
        }
    }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("Getting sessions onto your watch") }
            P { Text("For now, check the session in Off-Season before you head out. Most runs are a single easy zone. For quality sessions, set your watch's heart-rate alert to the zone shown, or build the workout in your watch's own app.") }
            Muted("Sending runs straight to Garmin, COROS and others is planned for a later version.")
        }
    }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("If something hurts") }
            P { Text("Tired is normal, pain isn't. If a niggle changes how you run, stop, skip the next run and see a physio if it doesn't settle. The plan will wait.") }
        }
    }
}
