package com.offseason.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offseason.app.data.AppData
import com.offseason.app.data.Rpe
import com.offseason.app.ui.theme.AppColors
import com.offseason.engine.Expectations
import com.offseason.engine.Ftp
import com.offseason.engine.PlannedSession
import com.offseason.engine.SessionKind
import com.offseason.engine.Step
import com.offseason.engine.StepType
import com.offseason.engine.Workout
import com.offseason.engine.WorkoutLibrary
import com.offseason.engine.Zone
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun watts(pct: Number, ftp: Int?) = ftp?.let { "${Ftp.watts(pct, it)} W" } ?: "${pct.toDouble().roundToInt()}%"

private fun pctRange(low: Int, high: Int?) = if (high == null || high == low) "$low%" else "$low–$high%"

private fun wattRange(low: Int, high: Int?, ftp: Int?) =
    if (ftp == null) pctRange(low, high)
    else if (high == null || high == low) "${Ftp.watts(low, ftp)} W"
    else "${Ftp.watts(low, ftp)}–${Ftp.watts(high, ftp)} W"

private fun dur(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "${sec / 60}:${"%02d".format(sec % 60)}"

/** A step as (title, watts, small % text). */
fun describe(step: Step, ftp: Int?): Triple<String, String, String> {
    val cad = step.cadenceTarget?.let { " · $it rpm" } ?: ""
    val pct = pctRange(step.powerPctLow, step.powerPctHigh)
    return when (step.type) {
        StepType.WARMUP -> Triple(step.label ?: "Warm-up ${dur(step.durationSec)}", "${watts(step.powerPctLow, ftp)} → ${watts(step.powerPctHigh ?: step.powerPctLow, ftp)}", "$pct FTP$cad")
        StepType.COOLDOWN -> Triple(step.label ?: "Cool-down ${dur(step.durationSec)}", "${watts(step.powerPctLow, ftp)} → ${watts(step.powerPctHigh ?: step.powerPctLow, ftp)}", "$pct FTP$cad")
        StepType.RAMP -> Triple(step.label ?: "Ramp ${dur(step.durationSec)}", "${watts(step.powerPctLow, ftp)} → ${watts(step.powerPctHigh ?: step.powerPctLow, ftp)}", "$pct FTP$cad")
        StepType.STEADY -> Triple(
            (step.label?.let { "$it · " } ?: "") + "${dur(step.durationSec)} steady",
            wattRange(step.powerPctLow, step.powerPctHigh, ftp),
            "$pct FTP · ${Zone.of(step.targetPct).displayName}$cad",
        )
        StepType.INTERVALS -> Triple(
            (step.label?.let { "$it · " } ?: "") + "${step.repeat} × ${dur(step.durationSec)} on / ${dur(step.offDurationSec!!)} easy",
            wattRange(step.powerPctLow, step.powerPctHigh, ftp) + " / " + watts(step.offPowerPct!!, ftp),
            "$pct FTP · ${Zone.of(step.targetPct).displayName}$cad",
        )
        StepType.FREE_RIDE -> Triple(step.label ?: "Free ride ${dur(step.durationSec)}", "Max effort", "No target. Ride by feel$cad")
    }
}

@Composable
fun StepList(workout: Workout, ftp: Int?) {
    workout.steps.forEach { step ->
        val (title, w, small) = describe(step, ftp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(AppColors.zone(Zone.of(step.targetPct)))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Muted(small)
            }
            Text(w, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Screen 5: session detail. */
@Composable
fun SessionDetailScreen(
    session: PlannedSession,
    data: AppData,
    onDownload: (Workout) -> Unit,
    onMarkDone: (Rpe) -> Unit,
    onUndo: () -> Unit,
    onRetest: () -> Unit,
) {
    val ftp = data.currentFtp
    val w = session.workout
    val done = data.completions[session.id]
    var askRpe by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(session.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + " · " + minutesLabel(w.durationMin) + if (session.optional) " · optional" else "")
        Text(w.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(w.purpose)
        if (ftp == null) Muted("Targets are shown as % of FTP until you enter your FTP.")
        SectionCard {
            WorkoutProfile(w)
            Muted("Bar height = power. Dashed line = your FTP${ftp?.let { " ($it W)" } ?: ""}.")
        }
        SectionCard { StepList(w, ftp) }
        OutlinedButton(onClick = { onDownload(w) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null); Text("  Download .zwo")
        }
        when {
            done != null -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = AppColors.done)
                    Text("  Done · felt ${done.rpe.label.lowercase()}", Modifier.weight(1f))
                    TextButton(onClick = onUndo) { Text("Undo") }
                }
            }
            w.kind == SessionKind.FTP_TEST -> Button(onClick = onRetest, Modifier.fillMaxWidth()) { Text("Enter my test result") }
            else -> Button(onClick = { askRpe = true }, Modifier.fillMaxWidth()) { Text("Mark done") }
        }
    }
    if (askRpe) {
        AlertDialog(
            onDismissRequest = { askRpe = false },
            title = { Text("How did it feel?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rpe.entries.forEach { r ->
                        OutlinedButton(onClick = { onMarkDone(r); askRpe = false }, Modifier.fillMaxWidth()) { Text(r.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askRpe = false }) { Text("Cancel") } },
        )
    }
}

/** Screen 2: FTP test protocol + result entry. Also used for retests (screen 6). */
@Composable
fun FtpTestScreen(
    data: AppData,
    isRetest: Boolean,
    onDownload: (Workout) -> Unit,
    onSave: (ftp: Int, source: String) -> Unit,
    retestSessionId: String?,
    onMarkTestDone: (String) -> Unit,
) {
    val test = WorkoutLibrary.ftpTest()
    var avgText by remember { mutableStateOf("") }
    var ramp by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Int?>(null) }
    val input = avgText.toIntOrNull()
    val newFtp = input?.let { if (ramp) Ftp.fromRampTest(it) else Ftp.from20MinTest(it) }
    val start = data.startingFtp
    val previous = data.currentFtp

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (isRetest) "FTP retest" else "FTP test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(test.purpose)
        SectionCard {
            Text("The protocol (about 70 minutes)", fontWeight = FontWeight.SemiBold)
            Text("1. 20 min easy warm-up, building gradually.")
            Text("2. 3 × 1 min fast pedalling (about 110 rpm), 1 min easy between.")
            Text("3. 5 min all-out, then 10 min very easy. This burns off your 'fresh legs' power so the main effort is accurate.")
            Text("4. 20 min: the highest power you can hold for the whole 20. Start slightly conservative and build. If you blow up early, the result will be low.")
            Text("5. 10–15 min easy cool-down.")
            Muted("Use ERG mode off / resistance or slope mode for the 20-minute effort, so you control the power.")
        }
        SectionCard { WorkoutProfile(test, heightDp = 80) }
        OutlinedButton(onClick = { onDownload(test) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null); Text("  Download test .zwo")
        }
        SectionCard {
            Text("Your result", fontWeight = FontWeight.SemiBold)
            ChoiceChips(listOf("20-min test" to !ramp, "Ramp test" to ramp)) { ramp = it == 1 }
            OutlinedTextField(
                avgText, { avgText = it.filter(Char::isDigit).take(4) },
                label = { Text(if (ramp) "Best 1-minute power (W)" else "20-minute average power (W)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (newFtp != null) {
                Text("Your FTP: $newFtp W", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Muted(if (ramp) "75% of your best 1-minute power." else "95% of your 20-minute average.")
            }
            Button(
                enabled = newFtp != null && newFtp in 50..600 && saved == null,
                onClick = {
                    onSave(newFtp!!, if (ramp) "ramp" else "20min")
                    retestSessionId?.let(onMarkTestDone)
                    saved = newFtp
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save FTP") }
        }
        saved?.let { ftp -> RetestResult(ftp, previous, start, data.profile?.experience) }
    }
}

@Composable
private fun RetestResult(ftp: Int, previous: Int?, start: Int?, experience: com.offseason.app.data.Experience?) {
    SectionCard {
        Text("Saved. All your targets now use $ftp W.", fontWeight = FontWeight.SemiBold)
        if (previous != null && previous != ftp) {
            val change = Ftp.improvementPct(previous, ftp)
            Text(
                if (change > 0) "Up ${"%.1f".format(change)}% since your last test (${previous} W → $ftp W). Great work."
                else "A little down from $previous W. That happens, especially with fatigue, illness or a bad day. Your targets now match how you are today, which is the point.",
            )
        }
        if (start != null && start != previous && start != ftp) {
            Text("Since you started: ${"%+.1f".format(Ftp.improvementPct(start, ftp))}% ($start W → $ftp W).")
        }
        Muted(if (experience == com.offseason.app.data.Experience.EXPERIENCED) Expectations.EXPERIENCED else Expectations.NEWER_RIDER)
    }
}
