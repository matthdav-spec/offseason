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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offseason.app.data.AppData
import com.offseason.app.data.Profile
import com.offseason.app.data.Rpe
import com.offseason.engine.Ftp
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Screen 7: progress in plain English. The anti-TrainingPeaks screen. */
@Composable
fun ProgressScreen(
    plan: Plan,
    data: AppData,
    today: LocalDate,
    status: Status.Message,
    series: List<Load.DayLoad>,
    completionRate: Double?,
) {
    val start = data.startingFtp
    val now = data.currentFtp
    val due = plan.sessions.filter { !it.optional && !it.date.isAfter(today) }
    val done = plan.sessions.count { it.id in data.completions }
    val total = plan.sessions.count { !it.optional }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your progress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TrafficLight(status)

        SectionCard {
            Text("FTP", fontWeight = FontWeight.SemiBold)
            if (start == null || now == null) Text("Do your FTP test to start tracking.")
            else {
                Text("$now W", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                if (now != start) Text("${"%+.1f".format(Ftp.improvementPct(start, now))}% since you started ($start W)")
                else Text("Your starting point. Your first retest is at the end of week ${plan.weeks.first { it.hasRetest }.index + 1}.")
                data.profile?.weightKg?.takeIf { data.profile.showWattsPerKg }?.let { Muted("%.2f W/kg".format(now / it)) }
                if (data.ftpHistory.size > 1) {
                    HorizontalDivider()
                    data.ftpHistory.sortedBy { it.date }.forEach {
                        Row { Text(LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("d MMM")), Modifier.weight(1f)); Text("${it.ftp} W") }
                    }
                }
            }
        }

        SectionCard {
            Text("Sessions", fontWeight = FontWeight.SemiBold)
            val doneDue = due.count { it.id in data.completions }
            if (completionRate == null) Text("Nothing due yet. Your first session is coming up.")
            else {
                Text("You've done $doneDue of ${due.size} planned sessions so far (${(completionRate * 100).toInt()}%).")
                LinearProgressIndicator(progress = { completionRate.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            Muted("$done of $total sessions in the whole plan done.")
            val rpes = data.completions.values.groupingBy { it.rpe }.eachCount()
            if (rpes.isNotEmpty()) Muted("How they felt: " + Rpe.entries.filter { it in rpes }.joinToString(" · ") { "${it.label} ${rpes[it]}" })
        }

        SectionCard {
            Text("Fitness trend", fontWeight = FontWeight.SemiBold)
            val first = series.firstOrNull { it.date >= plan.startDate }?.fitness ?: 0.0
            val last = series.lastOrNull()?.fitness ?: 0.0
            Text(
                when {
                    last < 1 -> "Your fitness line starts rising as you complete sessions."
                    last > first -> "Your bike fitness has risen steadily. That's the training adding up."
                    else -> "Your fitness is holding steady."
                },
            )
            FitnessLine(series.filter { !it.date.isBefore(plan.startDate.minusDays(1)) })
            Muted("Fitness is a rolling 6-week average of how much training you've done. It rises slowly with consistent riding and dips a little in recovery weeks, which is normal.")
        }
    }
}

@Composable
private fun FitnessLine(series: List<Load.DayLoad>) {
    if (series.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val max = maxOf(10.0, series.maxOf { it.fitness }) * 1.1
        val p = Path()
        series.forEachIndexed { i, d ->
            val x = i * size.width / (series.size - 1)
            val y = size.height - (d.fitness / max * size.height).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(width = 5f))
        drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 2f)
    }
}

/** Screen 8: settings. */
@Composable
fun SettingsScreen(
    data: AppData,
    onSaveProfile: (Profile) -> Unit,
    onSaveFtp: (Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onHelp: () -> Unit,
) {
    val p = data.profile ?: return
    var hours by remember(p) { mutableStateOf(p.hoursPerWeek) }
    var bike by remember(p) { mutableStateOf(p.bikeDays.map(DayOfWeek::of).toSet()) }
    var swim by remember(p) { mutableStateOf(p.swimDays.map(DayOfWeek::of).toSet()) }
    var run by remember(p) { mutableStateOf(p.runDays.map(DayOfWeek::of).toSet()) }
    var hard by remember(p) { mutableStateOf(p.hardRunDays.map(DayOfWeek::of).toSet()) }
    var longRun by remember(p) { mutableStateOf(p.longRunDay?.let(DayOfWeek::of)) }
    var ftpText by remember(data.currentFtp) { mutableStateOf(data.currentFtp?.toString() ?: "") }
    var weightText by remember(p) { mutableStateOf(p.weightKg?.toString() ?: "") }
    var wkg by remember(p) { mutableStateOf(p.showWattsPerKg) }
    var confirmReset by remember { mutableStateOf(false) }

    val edited = p.copy(
        hoursPerWeek = hours,
        bikeDays = bike.map { it.value }.sorted(),
        swimDays = swim.map { it.value }.sorted(),
        runDays = run.map { it.value }.sorted(),
        hardRunDays = (hard intersect run).map { it.value }.sorted(),
        longRunDay = longRun?.takeIf { it in run }?.value,
        weightKg = weightText.toDoubleOrNull(),
        showWattsPerKg = wkg,
    )

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onHelp, Modifier.fillMaxWidth()) { Text("How to load sessions into Zwift / MyWhoosh / TPV") }

        SectionCard {
            Text("FTP", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    ftpText, { ftpText = it.filter(Char::isDigit).take(3) }, label = { Text("Watts") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = ftpText.toIntOrNull()?.let { it in 50..600 && it != data.currentFtp } == true,
                    onClick = { onSaveFtp(ftpText.toInt()) },
                ) { Text("Update") }
            }
            Muted("Use this to correct your FTP. After a test, use the FTP test screen instead.")
        }

        SectionCard {
            Text("Your week", fontWeight = FontWeight.SemiBold)
            ChoiceChips(listOf(3, 4, 5).map { "$it h" to (hours == it) }) { hours = it + 3 }
            Text("Bike days"); DayPicker(bike, { bike = it })
            Text("Swim days"); DayPicker(swim, { swim = it })
            Text("Run days"); DayPicker(run, { run = it })
            if (run.isNotEmpty()) {
                Text("Long run")
                val runList = run.sorted()
                ChoiceChips(listOf("None" to (longRun == null)) + runList.map { it.short() to (it == longRun) }) {
                    longRun = if (it == 0) null else runList[it - 1]
                }
                Text("Hard runs"); DayPicker(hard, { hard = it }, enabled = { it in run })
            }
            Button(enabled = edited != p && bike.isNotEmpty(), onClick = { onSaveProfile(edited) }) { Text("Save and rebuild plan") }
            Muted("Your plan is rebuilt from these settings. Sessions you've already marked done stay done.")
        }

        SectionCard {
            Text("Units", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                weightText, { weightText = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Weight (kg, optional)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show watts per kg", Modifier.weight(1f))
                Switch(wkg, { wkg = it })
            }
            Button(enabled = edited != p, onClick = { onSaveProfile(edited) }) { Text("Save") }
        }

        SectionCard {
            Text("Your data", fontWeight = FontWeight.SemiBold)
            Muted("Everything is stored only on this phone. Export a backup or send it to Matt for the pilot.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, Modifier.weight(1f)) { Text("Export") }
                OutlinedButton(onClick = onImport, Modifier.weight(1f)) { Text("Import") }
            }
        }

        SectionCard {
            Text("Plan: ${p.planWeeks} weeks from ${LocalDate.parse(p.startDate).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", fontWeight = FontWeight.SemiBold)
            Button(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Reset plan and start again") }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Start again?") },
        text = { Text("This deletes your plan, FTP history and completed sessions from this phone. Export first if you want a backup.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Delete everything") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
fun HelpScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Getting sessions onto your trainer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Off-Season creates workout files in two formats: .zwo for Zwift and MyWhoosh, and .mrc for ROUVY and TrainingPeaks Virtual. Targets are a percentage of FTP, so the watts scale to the FTP set in that app. Keep your FTP the same in both places.")
        SectionCard {
            Text("1. Get the files to your trainer computer", fontWeight = FontWeight.SemiBold)
            Text("Tap \"Week's sessions\" on the This Week screen, then email the zip to yourself or save it to Google Drive. Open it on the PC, Mac or tablet you ride with, and unzip it.")
        }
        SectionCard {
            Text("2a. Zwift", fontWeight = FontWeight.SemiBold)
            Text("Copy the .zwo files into Documents › Zwift › Workouts › (your Zwift ID number). Restart Zwift. They appear under Workouts › Custom Workouts.")
        }
        SectionCard {
            Text("2b. MyWhoosh", fontWeight = FontWeight.SemiBold)
            Text("Open the MyWhoosh workout builder on the MyWhoosh website, choose Import, and pick the .zwo file. It then appears in your custom workouts in the app.")
        }
        SectionCard {
            Text("2c. ROUVY", fontWeight = FontWeight.SemiBold)
            Text("Log in to the ROUVY website on a computer and open Workouts. Create a workout by importing a file, and pick the .mrc file (ROUVY doesn't take .zwo). Save it, then open ROUVY on your trainer device. The workout is under your own workouts, and runs in ERG mode at your ROUVY FTP.")
        }
        SectionCard {
            Text("2d. TrainingPeaks Virtual", fontWeight = FontWeight.SemiBold)
            Text("Go to Workouts › Import, and pick the .mrc file.")
        }
        Muted("The week's zip has every session as both .zwo and .mrc. Use whichever your app needs. For FTP tests, use the .zwo or turn ERG mode off: .mrc can't do a \"ride as hard as you can\" effort.")
        Muted("On a tablet or phone running Zwift, the easiest route is to use a computer once, or wait for Intervals.icu sync in a future version.")
    }
}
