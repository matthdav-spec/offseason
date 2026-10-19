package com.offseason.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offseason.app.format
import com.offseason.app.ui.theme.AppColors
import com.offseason.engine.AppData
import com.offseason.engine.Lthr
import com.offseason.engine.PlannedRun
import com.offseason.engine.RunKind
import com.offseason.engine.RunLibrary
import com.offseason.engine.RunStep
import com.offseason.engine.RunStepType
import com.offseason.engine.RunWorkout
import com.offseason.engine.RunZone
import com.offseason.engine.Rpe

/** Colours for heart-rate zones, matching the bike zone palette in spirit. */
fun runZoneColor(z: RunZone): Color = when (z) {
    RunZone.Z1 -> Color(0xFF8A9BA8)
    RunZone.Z2 -> Color(0xFF4A90D9)
    RunZone.Z3 -> Color(0xFF5CC08A)
    RunZone.Z4 -> Color(0xFFE6C54A)
    RunZone.Z5A -> Color(0xFFF09A3E)
    RunZone.Z5B -> Color(0xFFE5584F)
    RunZone.Z5C -> Color(0xFFB84FD1)
}

fun runKindColor(k: RunKind): Color = when (k) {
    RunKind.EASY, RunKind.STRIDES -> Color(0xFF4A90D9)
    RunKind.LONG -> Color(0xFF3A7CA5)
    RunKind.HILLS -> Color(0xFF5CC08A)
    RunKind.TEMPO -> Color(0xFF5CC08A)
    RunKind.THRESHOLD -> Color(0xFFE6C54A)
    RunKind.LTHR_TEST -> Color(0xFFE5584F)
}

private fun dur(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else if (sec < 60) "$sec s" else "${sec / 60}:${"%02d".format(sec % 60)}"

/** A run step as (title, target, small text). */
fun describeRun(step: RunStep, lthr: Int?): Triple<String, String, String> {
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
fun RunStepList(w: RunWorkout, lthr: Int?) {
    w.steps.forEach { step ->
        val (title, target, small) = describeRun(step, lthr)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(runZoneColor(step.zoneHigh))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Muted(small)
            }
            Text(target, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Bar chart of the run: height = zone, width = time. */
@Composable
fun RunProfile(w: RunWorkout, heightDp: Int = 90) {
    val segs = buildList {
        for (s in w.steps) {
            if (s.repeat != null && s.offDurationSec != null) repeat(s.repeat!!) {
                add(s.durationSec to s.zoneHigh); add(s.offDurationSec!! to RunZone.Z1)
            } else add(s.durationSec to s.zoneHigh)
        }
    }
    val total = segs.sumOf { it.first }.toFloat()
    Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
        var x = 0f
        for ((d, z) in segs) {
            val w2 = d / total * size.width
            val h = size.height * (z.ordinal + 1) / RunZone.entries.size
            drawRect(runZoneColor(z), Offset(x, size.height - h), Size(w2, h))
            x += w2
        }
    }
}

@Composable
fun RunZonesCard(lthr: Int?) {
    SectionCard {
        Text("Your run zones", fontWeight = FontWeight.SemiBold)
        if (lthr == null) Muted("Do the LTHR test to see your zones in beats per minute. Until then, use the descriptions.")
        RunZone.entries.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(runZoneColor(z))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${z.label} · ${z.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Muted(z.feel)
                }
                Text(Lthr.describe(z..z, lthr), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A planned run: purpose, steps with heart rates, mark done. */
@Composable
fun RunDetailScreen(
    run: PlannedRun,
    data: AppData,
    onMarkDone: (Rpe) -> Unit,
    onUndo: () -> Unit,
    onTest: () -> Unit,
    offerEasing: Boolean,
    canRepeatWeek: Boolean,
    onRepeatWeek: () -> Unit,
    onDeclineEasing: () -> Unit,
) {
    val lthr = data.currentLthr
    val w = run.workout
    val done = data.completions[run.id]
    var askRpe by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(run.date.format("EEEE d MMMM") + " · Run · " + minutesLabel(w.durationMin))
        Text(w.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(w.purpose)
        if (lthr == null && w.kind != RunKind.LTHR_TEST) Muted("Heart rates appear once you've done the LTHR test. For now, go by the descriptions.")
        SectionCard { RunProfile(w); Muted("Bar height = heart-rate zone.") }
        SectionCard { RunStepList(w, lthr) }
        SectionCard {
            Text("Running by heart rate", fontWeight = FontWeight.SemiBold)
            Muted("Heart rate takes a minute or two to settle at the start of each effort, so ease in rather than chasing the number. On hills, wind or a warm day, slow down to stay in the zone. That's the point.")
        }
        when {
            done != null -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = AppColors.done)
                    Text("  Done · felt ${done.rpe.label.lowercase()}", Modifier.weight(1f))
                    TextButton(onClick = { askRpe = true }) { Text("Change") }
                    TextButton(onClick = onUndo) { Text("Undo") }
                }
            }
            w.kind == RunKind.LTHR_TEST -> Button(onClick = onTest, Modifier.fillMaxWidth()) { Text("Enter my test result") }
            else -> Button(onClick = { askRpe = true }, Modifier.fillMaxWidth()) { Text("Mark done") }
        }
        if (offerEasing && done != null) SectionCard {
            Text("Runs feeling too hard?", fontWeight = FontWeight.SemiBold)
            Text("Your last couple of quality runs felt hard. Running fatigue builds up quietly, and pushing through is how niggles start.")
            if (canRepeatWeek) Button(onClick = onRepeatWeek, Modifier.fillMaxWidth()) { Text("Repeat this week") }
            TextButton(onClick = onDeclineEasing, Modifier.fillMaxWidth()) { Text("No thanks, keep going") }
            Muted("Repeating the week pushes the rest of the plan back by a week. If anything hurts (not just tired), rest and see a physio.")
        }
    }
    if (askRpe) AlertDialog(
        onDismissRequest = { askRpe = false },
        title = { Text("How did it feel?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rpe.entries.forEach { r -> OutlinedButton(onClick = { onMarkDone(r); askRpe = false }, Modifier.fillMaxWidth()) { Text(r.label) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { askRpe = false }) { Text("Cancel") } },
    )
}

/** The LTHR test: protocol, entry, and the new zones. */
@Composable
fun LthrTestScreen(data: AppData, runId: String?, onSave: (Int) -> Unit, onMarkTestDone: (String) -> Unit) {
    val test = RunLibrary.lthrTest()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Int?>(null) }
    val previous = remember { data.currentLthr }
    val value = text.toIntOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (previous != null) "LTHR retest" else "LTHR test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(test.purpose)
        SectionCard {
            Text("The protocol (about 55 minutes)", fontWeight = FontWeight.SemiBold)
            Text("1. 15 min easy warm-up, finishing with a few short, faster pick-ups.")
            Text("2. Start your watch and run for 30 minutes as if it were a race: the hardest effort you can hold evenly for the whole time.")
            Text("3. At 10 minutes, press LAP. This is the important bit.")
            Text("4. At 30 minutes, stop and cool down with 10 min easy jogging or walking.")
            Text("5. Your LTHR is the average heart rate of that second lap (the last 20 minutes).")
            Muted("Run it alone, not in a race or with friends. Pick a flat route or a treadmill at 1% incline, on fresh legs (not the day after a hard session).")
        }
        SectionCard { RunProfile(test, 70) }
        SectionCard {
            Text("Your result", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                label = { Text("Average heart rate, last 20 min (bpm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = value != null && value in 100..220 && saved == null,
                onClick = { onSave(value!!); runId?.let(onMarkTestDone); saved = value },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save LTHR") }
        }
        saved?.let { lthr ->
            SectionCard {
                Text("Saved. Your run zones now use $lthr bpm.", fontWeight = FontWeight.SemiBold)
                if (previous != null && previous != lthr) Text(
                    if (lthr > previous) "Up from $previous bpm. A higher LTHR usually means you can now sustain a harder effort."
                    else "Down from $previous bpm. LTHR moves with fatigue, heat and the course. The best sign of progress is running faster at the same heart rate.",
                )
            }
            RunZonesCard(lthr)
        }
    }
}
