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
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import com.offseason.engine.*
import com.offseason.app.format
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
    val dueIds = plan.sessions.filter { !it.optional && !it.date.isAfter(today) }.map { it.id } +
        plan.runs.filter { !it.date.isAfter(today) }.map { it.id }
    val done = plan.sessions.count { it.id in data.completions } + plan.runs.count { it.id in data.completions }
    val total = plan.sessions.count { !it.optional } + plan.runs.size
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your progress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TrafficLight(status)

        if (plan.request.hasRun) LthrCard(plan, data)

        if (plan.request.hasBike) SectionCard {
            Text("FTP", fontWeight = FontWeight.SemiBold)
            if (start == null || now == null) Text("Do your FTP test to start tracking.")
            else {
                Text("$now W", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                if (now != start) Text("${"%+.1f".format(Ftp.improvementPct(start, now))}% since you started ($start W)")
                else Text("Your starting point. Your first retest is at the end of week ${plan.weeks.first { it.hasRetest }.index + 1}.")
                data.profile?.let { p -> p.weightKg?.takeIf { p.showWattsPerKg } }?.let { Muted("%.2f W/kg".format(now / it)) }
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
            val doneDue = dueIds.count { it in data.completions }
            if (completionRate == null) Text("Nothing due yet. Your first session is coming up.")
            else {
                Text("You've done $doneDue of ${dueIds.size} planned sessions so far (${(completionRate * 100).toInt()}%).")
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
                    last > first -> "Your fitness has risen steadily. That's the training adding up."
                    else -> "Your fitness is holding steady."
                },
            )
            FitnessLine(series.filter { !it.date.isBefore(plan.startDate.minusDays(1)) })
            Muted("Fitness is a rolling 6-week average of how much training you've done" + (if (plan.request.hasRun && plan.request.hasBike) ", bike and run together" else "") + ". It rises slowly with consistent training and dips a little in recovery weeks, which is normal.")
        }
    }
}

@Composable
private fun LthrCard(plan: Plan, data: AppData) {
    SectionCard {
        Text("Run: LTHR", fontWeight = FontWeight.SemiBold)
        val now = data.currentLthr
        val start = data.startingLthr
        if (now == null || start == null) Text("Do the 30-minute LTHR test to set your run zones.")
        else {
            Text("$now bpm", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            if (now == start) Text("Your starting point. Your next LTHR test is at the end of week ${plan.weeks.firstOrNull { it.hasRunTest }?.let { it.index + 1 } ?: 4}.")
            else Text("Started at $start bpm.")
            Muted("LTHR itself doesn't always rise much. The real sign of progress is running faster at the same heart rate, so note your pace on the easy runs.")
            if (data.lthrHistory.size > 1) {
                HorizontalDivider()
                data.lthrHistory.sortedBy { it.date }.forEach {
                    Row { Text(LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("d MMM")), Modifier.weight(1f)); Text("${it.lthr} bpm") }
                }
            }
        }
        val runsDone = plan.runs.count { it.id in data.completions }
        Muted("$runsDone of ${plan.runs.size} runs done.")
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

private fun canSave(p: Profile) = (!p.bikePlan || p.bikeDays.isNotEmpty()) && (!p.runPlan || p.runDays.isNotEmpty())

/** Screen 8: settings. */
@Composable
fun SettingsScreen(
    data: AppData,
    onSaveProfile: (Profile) -> Unit,
    onSaveFtp: (Int) -> Unit,
    onSaveLthr: (Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onHelp: (route: String) -> Unit,
) {
    val p = data.profile ?: return
    var hours by remember(p) { mutableStateOf(p.hoursPerWeek) }
    var bike by remember(p) { mutableStateOf(p.bikeDays.map(::dayOfWeekFromIso).toSet()) }
    var swim by remember(p) { mutableStateOf(p.swimDays.map(::dayOfWeekFromIso).toSet()) }
    var run by remember(p) { mutableStateOf(p.runDays.map(::dayOfWeekFromIso).toSet()) }
    var hard by remember(p) { mutableStateOf(p.hardRunDays.map(::dayOfWeekFromIso).toSet()) }
    var longRun by remember(p) { mutableStateOf(p.longRunDay?.let(::dayOfWeekFromIso)) }
    var ftpText by remember(data.currentFtp) { mutableStateOf(data.currentFtp?.toString() ?: "") }
    var lthrText by remember(data.currentLthr) { mutableStateOf(data.currentLthr?.toString() ?: "") }
    var runHours by remember(p) { mutableStateOf(p.runHoursPerWeek) }
    var weightText by remember(p) { mutableStateOf(p.weightKg?.toString() ?: "") }
    var wkg by remember(p) { mutableStateOf(p.showWattsPerKg) }
    var confirmReset by remember { mutableStateOf(false) }

    val edited = p.copy(
        hoursPerWeek = hours,
        bikeDays = bike.map { it.isoNumber }.sorted(),
        swimDays = swim.map { it.isoNumber }.sorted(),
        runDays = run.map { it.isoNumber }.sorted(),
        hardRunDays = if (p.runPlan) emptyList() else (hard intersect run).map { it.isoNumber }.sorted(),
        longRunDay = longRun?.takeIf { it in run }?.isoNumber,
        runHoursPerWeek = runHours,
        weightKg = weightText.toDoubleOrNull(),
        showWattsPerKg = wkg,
    )

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard {
            Text("How-to guides", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { onHelp("help/trainer") }, Modifier.fillMaxWidth()) { Text("Get sessions into Zwift, MyWhoosh, ROUVY or TPV") }
            OutlinedButton(onClick = { onHelp("help/feel") }, Modifier.fillMaxWidth()) { Text("Rating sessions & easing off") }
            OutlinedButton(onClick = { onHelp("help/fit") }, Modifier.fillMaxWidth()) { Text("Adding your ride files (.fit)") }
            if (p.runPlan) OutlinedButton(onClick = { onHelp("help/run") }, Modifier.fillMaxWidth()) { Text("Running by heart rate") }
        }

        if (p.runPlan) SectionCard {
            Text("LTHR (run)", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    lthrText, { lthrText = it.filter(Char::isDigit).take(3) }, label = { Text("bpm") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = lthrText.toIntOrNull()?.let { it in 100..220 && it != data.currentLthr } == true,
                    onClick = { onSaveLthr(lthrText.toInt()) },
                ) { Text("Update") }
            }
            Muted("Use this to correct your LTHR. After a test, use the LTHR test screen instead.")
        }

        if (p.bikePlan) SectionCard {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your week", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(enabled = edited != p && canSave(edited), onClick = { onSaveProfile(edited) }) { Text("Save changes") }
            }
            Muted("Change anything below, then tap Save changes. Your plan is rebuilt, and sessions you've done stay done.")
            if (p.bikePlan) {
                Text("Bike hours"); ChoiceChips(listOf(3, 4, 5).map { "$it h" to (hours == it) }) { hours = it + 3 }
                Text("Bike days"); DayPicker(bike, { bike = it })
            }
            Text("Swim days"); DayPicker(swim, { swim = it })
            if (p.runPlan) {
                Text("Run hours"); ChoiceChips(listOf(2, 3, 4).map { "$it h" to (runHours == it) }) { runHours = it + 2 }
            }
            Text(if (p.runPlan) "Days you can run" else "Run days"); DayPicker(run, { run = it })
            if (run.isNotEmpty()) {
                Text(if (p.runPlan) "Preferred long run day" else "Long run")
                val runList = run.sorted()
                ChoiceChips(listOf((if (p.runPlan) "Any" else "None") to (longRun == null)) + runList.map { it.short() to (it == longRun) }) {
                    longRun = if (it == 0) null else runList[it - 1]
                }
                if (!p.runPlan) { Text("Hard runs"); DayPicker(hard, { hard = it }, enabled = { it in run }) }
            }
            Button(enabled = edited != p && canSave(edited), onClick = { onSaveProfile(edited) }, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
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
            Muted("Everything is stored only on this phone. Export a backup now and then.")
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
        text = { Text("This deletes your plan, test history and completed sessions from this phone. Export first if you want a backup.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Delete everything") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
fun TrainerHelpScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Getting sessions onto your trainer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("DBAP creates workout files in two formats: .zwo for Zwift and MyWhoosh, and .mrc for ROUVY and TrainingPeaks Virtual. Targets are a percentage of FTP, so the watts scale to the FTP set in that app. Keep your FTP the same in both places.")
        SectionCard {
            Text("1. Get the files to your trainer computer", fontWeight = FontWeight.SemiBold)
            Text("For the whole week: tap \"Week's sessions\" on the This Week screen. For one session: open it and tap the button for your app. Email the file to yourself or save it to Google Drive, then open it on the PC, Mac or tablet you ride with. Unzip the week's zip first.")
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
        Muted("Riding Zwift on an iPad or Apple TV? Zwift only reads custom workouts from a computer's Workouts folder. Copy them across on a PC or Mac once, and they sync to your other devices on the same Zwift account.")
    }
}

@Composable
fun FeelHelpScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rating sessions & easing off", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("After each ride, open the session and tap Mark done. Then pick how it felt. This is how DBAP knows whether the plan suits you.")
        SectionCard {
            Text("What to pick", fontWeight = FontWeight.SemiBold)
            Text("Easy: you could have done more.")
            Text("About right: hard where it should be, but you finished strong.")
            Text("Hard: you got through it, but it was a real struggle.")
            Text("Couldn't finish: you had to stop early or cut the intervals short.")
            Muted("Change your mind? Open the session and tap Change.")
        }
        SectionCard {
            Text("When sessions keep feeling too hard", fontWeight = FontWeight.SemiBold)
            Text("If two interval sessions in a row are rated Hard or Couldn't finish, DBAP offers three choices:")
            Text("• Lower my targets: every session from tomorrow is ${com.offseason.engine.Adaptation.EASE_PCT}% easier. This lasts until your next FTP test, which sets new targets anyway.")
            Text("• Repeat this week: do this week again before moving on. The rest of the plan, including retests, moves back a week.")
            Text("• No thanks: carry on as planned. Sometimes a hard week is just a hard week.")
            Muted("A bad night's sleep or a big run can make any session feel hard. If it keeps happening, easing off is the smart choice, not a failure.")
        }
        SectionCard {
            Text("Updating your trainer app", fontWeight = FontWeight.SemiBold)
            Text("If you lower your targets, download the sessions again so your trainer app gets the new targets.")
        }
    }
}

@Composable
fun FitHelpScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Adding your ride files (.fit)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("This is optional. Every app you ride with saves a .fit file of your ride. Give it to DBAP and it reads your real power, so your progress and fitness trend are based on what you actually did, not just the plan.")
        SectionCard {
            Text("Two ways to add one", fontWeight = FontWeight.SemiBold)
            Text("1. Open the session in DBAP and tap \"Add ride file (.fit)\". Then pick the file from your phone, Google Drive or Downloads.")
            Text("2. Or find the file in another app (email, WhatsApp, Files or Drive), tap Share, and choose DBAP. It suggests which session the ride belongs to.")
            Muted("Zip files from Garmin Connect work too. No need to unzip.")
        }
        SectionCard {
            Text("Where to find your .fit file", fontWeight = FontWeight.SemiBold)
            Text("Zwift: on your PC or Mac, Documents › Zwift › Activities. Or on zwift.com: your profile › Activities › pick the ride › Download.")
            Text("MyWhoosh: on the MyWhoosh website, open your activities, pick the ride and download the .fit file.")
            Text("ROUVY: on the ROUVY website, open your activity history, pick the ride and export or download the .fit file.")
            Text("TrainingPeaks Virtual: rides upload to TrainingPeaks. On the TrainingPeaks website, open the workout, then Files › download the .fit.")
            Text("Garmin Connect: open the activity, tap the cog › Export Original.")
            Text("Strava (your own rides): open the activity on the website, then ⋯ › Export Original.")
            Muted("Menu names change from time to time. If something doesn't match, look for \"Export\" or \"Download\" on the ride.")
        }
        SectionCard {
            Text("What you'll see", fontWeight = FontWeight.SemiBold)
            Text("The session is marked done, with your time, average and weighted power, and whether you hit the planned effort. For an FTP test, it finds your best 20 minutes and works out your new FTP for you.")
            Muted("If the file has no power data (e.g. a ride without a power meter), the session still counts, using the planned effort.")
        }
    }
}


@Composable
fun RunHelpScreen(lthr: Int?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Running by heart rate", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Your run plan uses heart-rate zones based on your LTHR (lactate threshold heart rate). They work on any route, hilly or flat, and adjust to how you are on the day.")
        SectionCard {
            Text("Finding your LTHR", fontWeight = FontWeight.SemiBold)
            Text("Run 30 minutes on your own, as hard as you can hold evenly, like a race. Press LAP at 10 minutes. Your LTHR is the average heart rate of the last 20 minutes. You'll redo it at the end of each 4-week block.")
        }
        RunZonesCard(lthr)
        SectionCard {
            Text("Tips", fontWeight = FontWeight.SemiBold)
            Text("• Most of your running is Z1–Z2. It should feel too easy. That's what builds the engine without wrecking your bike sessions.")
            Text("• Heart rate takes 1–2 minutes to rise at the start of an effort. Ease in rather than sprinting to the number.")
            Text("• On hills, heat or tired days, slow down to stay in the zone, even if that means walking up a steep hill.")
            Text("• Strides are too short for heart rate: run them quick and relaxed, by feel.")
            Text("• A chest strap is more accurate than a wrist sensor, especially for intervals.")
        }
        SectionCard {
            Text("Getting sessions onto your watch", fontWeight = FontWeight.SemiBold)
            Text("For now, check the session in DBAP before you head out. Most runs are a single easy zone. For quality sessions, set your watch's heart-rate alert to the zone shown, or build the workout in your watch's own app.")
            Muted("Sending runs straight to Garmin, COROS and others is planned for a later version.")
        }
        SectionCard {
            Text("If something hurts", fontWeight = FontWeight.SemiBold)
            Text("Tired is normal, pain isn't. If a niggle changes how you run, stop, skip the next run and see a physio if it doesn't settle. The plan will wait.")
        }
    }
}
