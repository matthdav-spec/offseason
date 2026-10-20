package com.offseason.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.offseason.engine.Adaptation
import com.offseason.engine.Expectations
import com.offseason.engine.Ftp
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.WorkoutLibrary
import com.offseason.engine.dayOfWeekFromIso
import com.offseason.engine.isAfter
import com.offseason.engine.isBefore
import com.offseason.engine.isoNumber
import com.offseason.engine.minusDays
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Hr
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.jetbrains.compose.web.svg.Polyline
import org.jetbrains.compose.web.svg.Svg

// ---- FTP test / retest ----

@Composable
fun FtpTestScreen(data: AppData, sessionId: String?) {
    val test = WorkoutLibrary.ftpTest()
    var avgText by remember { mutableStateOf("") }
    var ramp by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Int?>(null) }
    var rideMessage by remember { mutableStateOf<String?>(null) }
    val previous = remember { data.currentFtp }
    val start = remember { data.startingFtp }
    val input = avgText.toIntOrNull()
    val newFtp = input?.let { if (ramp) Ftp.fromRampTest(it) else Ftp.from20MinTest(it) }

    Div({ classes("app", "stack") }) {
        TopBar("FTP test")
        H1 { Text(if (previous != null) "FTP retest" else "FTP test") }
        P { Text(test.purpose) }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("The protocol (about 70 minutes)") }
                P { Text("1. 20 min easy warm-up, building gradually.") }
                P { Text("2. 3 × 1 min fast pedalling (about 110 rpm), 1 min easy between.") }
                P { Text("3. 5 min all-out, then 10 min very easy. This burns off your 'fresh legs' power so the main effort is accurate.") }
                P { Text("4. 20 min: the highest power you can hold for the whole 20. Start slightly conservative and build. If you blow up early, the result will be low.") }
                P { Text("5. 10–15 min easy cool-down.") }
                Muted("Use ERG mode off / resistance or slope mode for the 20-minute effort, so you control the power.")
            }
        }
        Card { WorkoutProfile(test, height = 80) }
        DownloadButtons(test)
        Muted("With the .mrc, turn ERG mode off for the hard efforts. .mrc files can't do \"ride as hard as you can\".")
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Your result") }
                Btn("⬆ Use my ride file (.fit)") {
                    Files.pickRide { r ->
                        r.onSuccess { ride ->
                            sessionId?.let { AppState.attachRide(it, ride, Rpe.HARD) }
                            val best = ride.best20MinPower
                            if (best == null) rideMessage = "That ride is under 20 minutes, so there's no 20-minute effort in it."
                            else { ramp = false; avgText = best.toString(); rideMessage = "Found your best 20 minutes in the ride file: $best W." }
                        }.onFailure { rideMessage = it.message }
                    }
                }
                rideMessage?.let { Muted(it) }
                Muted("Or type it in:")
                Chips(listOf("20-min test" to !ramp, "Ramp test" to ramp)) { ramp = it == 1 }
                Input(InputType.Text) {
                    value(avgText); attr("inputmode", "numeric")
                    attr("placeholder", if (ramp) "Best 1-minute power (W)" else "20-minute average power (W)")
                    onInput { avgText = it.value.filter(Char::isDigit).take(4) }
                }
                if (newFtp != null) {
                    P({ style { property("font-size", "22px"); property("color", "var(--primary)") } }) { Text("Your FTP: $newFtp W") }
                    Muted(if (ramp) "75% of your best 1-minute power." else "95% of your 20-minute average.")
                }
                Btn("Save FTP", "primary", enabled = newFtp != null && newFtp in 50..600 && saved == null) {
                    AppState.recordFtp(newFtp!!, if (ramp) "ramp" else "20min")
                    sessionId?.let { id -> if (id !in AppState.data.completions) AppState.markDone(id, Rpe.HARD) }
                    saved = newFtp
                }
            }
        }
        saved?.let { ftp ->
            Card {
                Div({ classes("tight") }) {
                    H3 { Text("Saved. All your targets now use $ftp W.") }
                    if (previous != null && previous != ftp) {
                        val change = Ftp.improvementPct(previous, ftp)
                        P {
                            Text(
                                if (change > 0) "Up ${oneDp(change)}% since your last test ($previous W → $ftp W). Great work."
                                else "A little down from $previous W. That happens, especially with fatigue, illness or a bad day. Your targets now match how you are today, which is the point.",
                            )
                        }
                    }
                    if (start != null && start != previous && start != ftp) P { Text("Since you started: ${signedOneDp(Ftp.improvementPct(start, ftp))}% ($start W → $ftp W).") }
                    Muted(if (data.profile?.experience == Experience.EXPERIENCED) Expectations.EXPERIENCED else Expectations.NEWER_RIDER)
                }
            }
        }
    }
}

// ---- Progress ----

@OptIn(ExperimentalComposeWebSvgApi::class)
@Composable
fun ProgressScreen(plan: Plan, data: AppData, today: LocalDate, summary: com.offseason.engine.AppLogic.StatusSummary) {
    val start = data.startingFtp
    val now = data.currentFtp
    val dueIds = plan.sessions.filter { !it.optional && !it.date.isAfter(today) }.map { it.id } +
        plan.runs.filter { !it.date.isAfter(today) }.map { it.id }
    val rate = Logic.completionRate(plan, data, today)
    Div({ classes("app", "stack") }) {
        H1 { Text("Your progress") }
        TrafficLight(summary.message)
        if (plan.request.hasRun) LthrCard(plan, data)
        if (plan.request.hasBike) Card {
            Div({ classes("tight") }) {
                H3 { Text("FTP") }
                if (start == null || now == null) P { Text("Do your FTP test to start tracking.") }
                else {
                    P({ style { property("font-size", "40px"); property("color", "var(--primary)") } }) { Text("$now W") }
                    if (now != start) P { Text("${signedOneDp(Ftp.improvementPct(start, now))}% since you started ($start W)") }
                    else P { Text("Your starting point. Your first retest is at the end of week ${plan.weeks.first { it.hasRetest }.index + 1}.") }
                    data.profile?.let { p -> p.weightKg?.takeIf { p.showWattsPerKg } }?.let { Muted("${com.offseason.engine.fixed(now / it, 2)} W/kg") }
                    if (data.ftpHistory.size > 1) {
                        Hr()
                        data.ftpHistory.sortedBy { it.date }.forEach {
                            Div({ classes("row") }) { Span({ classes("grow") }) { Text(LocalDate.parse(it.date).dayMonth()) }; Text("${it.ftp} W") }
                        }
                    }
                }
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Sessions") }
                if (rate == null) P { Text("Nothing due yet. Your first session is coming up.") }
                else {
                    P { Text("You've done ${dueIds.count { it in data.completions }} of ${dueIds.size} planned sessions so far (${(rate * 100).toInt()}%).") }
                    Div({ classes("progress") }) { Div({ style { property("width", "${(rate * 100).toInt()}%") } }) }
                }
                Muted("${plan.sessions.count { it.id in data.completions } + plan.runs.count { it.id in data.completions }} of ${plan.sessions.count { !it.optional } + plan.runs.size} sessions in the whole plan done.")
                val rpes = data.completions.values.groupingBy { it.rpe }.eachCount()
                if (rpes.isNotEmpty()) Muted("How they felt: " + Rpe.entries.filter { it in rpes }.joinToString(" · ") { "${it.label} ${rpes[it]}" })
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Fitness trend") }
                val series = summary.series.filter { !it.date.isBefore(plan.startDate.minusDays(1)) }
                val first = series.firstOrNull()?.fitness ?: 0.0
                val last = series.lastOrNull()?.fitness ?: 0.0
                P {
                    Text(
                        when {
                            last < 1 -> "Your fitness line starts rising as you complete sessions."
                            last > first -> "Your fitness has risen steadily. That's the training adding up."
                            else -> "Your fitness is holding steady."
                        },
                    )
                }
                if (series.size >= 2) {
                    val max = maxOf(10.0, series.maxOf { it.fitness }) * 1.1
                    val pts = series.flatMapIndexed { i, d -> listOf<Number>(i * 1000.0 / (series.size - 1), 90 - d.fitness / max * 90) }.toTypedArray()
                    Svg(viewBox = "0 0 1000 90", attrs = { classes("profile"); attr("preserveAspectRatio", "none"); style { property("height", "90px") } }) {
                        Polyline(*pts, attrs = { attr("fill", "none"); attr("stroke", "#8CC8F0"); attr("stroke-width", "3"); attr("vector-effect", "non-scaling-stroke") })
                    }
                }
                Muted("Fitness is a rolling 6-week average of how much training you've done" + (if (plan.request.hasRun && plan.request.hasBike) ", bike and run together" else "") + ". It rises slowly with consistent training and dips a little in recovery weeks, which is normal.")
            }
        }
    }
}

@Composable
private fun LthrCard(plan: Plan, data: AppData) = Card {
    Div({ classes("tight") }) {
        H3 { Text("Run: LTHR") }
        val now = data.currentLthr
        val start = data.startingLthr
        if (now == null || start == null) P { Text("Do the 30-minute LTHR test to set your run zones.") }
        else {
            P({ style { property("font-size", "40px"); property("color", "var(--primary)") } }) { Text("$now bpm") }
            if (now == start) P { Text("Your starting point. Your next LTHR test is at the end of week ${plan.weeks.firstOrNull { it.hasRunTest }?.let { it.index + 1 } ?: 4}.") }
            else P { Text("Started at $start bpm.") }
            Muted("LTHR itself doesn't always rise much. The real sign of progress is running faster at the same heart rate, so note your pace on the easy runs.")
            if (data.lthrHistory.size > 1) {
                Hr()
                data.lthrHistory.sortedBy { it.date }.forEach {
                    Div({ classes("row") }) { Span({ classes("grow") }) { Text(LocalDate.parse(it.date).dayMonth()) }; Text("${it.lthr} bpm") }
                }
            }
        }
        Muted("${plan.runs.count { it.id in data.completions }} of ${plan.runs.size} runs done.")
    }
}

// ---- Settings ----

@Composable
fun SettingsScreen(data: AppData) {
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
    fun save() { AppState.update { it.copy(profile = edited) }; AppState.showToast("Plan updated") }
    val canSave = edited != p && (!edited.bikePlan || edited.bikeDays.isNotEmpty()) && (!edited.runPlan || edited.runDays.isNotEmpty())

    Div({ classes("app", "stack") }) {
        H1 { Text("Settings") }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("How-to guides") }
                Btn("Add DBAP to your Home Screen") { AppState.go(Route.Install) }
                Btn("Get sessions into Zwift, MyWhoosh, ROUVY or TPV") { AppState.go(Route.Help("trainer")) }
                Btn("Rating sessions & easing off") { AppState.go(Route.Help("feel")) }
                Btn("Adding your ride files (.fit)") { AppState.go(Route.Help("fit")) }
                if (p.runPlan) Btn("Running by heart rate") { AppState.go(Route.Help("run")) }
            }
        }
        if (p.runPlan) Card {
            Div({ classes("tight") }) {
                H3 { Text("LTHR (run)") }
                Div({ classes("row") }) {
                    Input(InputType.Text) { value(lthrText); attr("inputmode", "numeric"); attr("placeholder", "bpm"); onInput { lthrText = it.value.filter(Char::isDigit).take(3) } }
                    Btn("Update", "primary auto", enabled = lthrText.toIntOrNull()?.let { it in 100..220 && it != data.currentLthr } == true) {
                        AppState.recordLthr(lthrText.toInt(), "manual"); AppState.showToast("LTHR updated")
                    }
                }
                Muted("Use this to correct your LTHR. After a test, use the LTHR test screen instead.")
            }
        }
        if (p.bikePlan) Card {
            Div({ classes("tight") }) {
                H3 { Text("FTP") }
                Div({ classes("row") }) {
                    Input(InputType.Text) { value(ftpText); attr("inputmode", "numeric"); attr("placeholder", "Watts"); onInput { ftpText = it.value.filter(Char::isDigit).take(3) } }
                    Btn("Update", "primary auto", enabled = ftpText.toIntOrNull()?.let { it in 50..600 && it != data.currentFtp } == true) {
                        AppState.recordFtp(ftpText.toInt(), "manual"); AppState.showToast("FTP updated")
                    }
                }
                Muted("Use this to correct your FTP. After a test, use the FTP test screen instead.")
            }
        }
        Card {
            Div({ classes("tight") }) {
                Div({ classes("row") }) {
                    H3({ classes("grow") }) { Text("Your week") }
                    Btn("Save changes", "primary auto", enabled = canSave) { save() }
                }
                Muted("Change anything below, then tap Save changes. Your plan is rebuilt, and sessions you've done stay done.")
                if (p.bikePlan) {
                    P { Text("Bike hours") }; Chips(listOf(3, 4, 5).map { "$it h" to (hours == it) }) { hours = it + 3 }
                    P { Text("Bike days") }; DayPicker(bike, { bike = it })
                }
                P { Text("Swim days") }; DayPicker(swim, { swim = it })
                if (p.runPlan) { P { Text("Run hours") }; Chips(listOf(2, 3, 4).map { "$it h" to (runHours == it) }) { runHours = it + 2 } }
                P { Text(if (p.runPlan) "Days you can run" else "Run days") }; DayPicker(run, { run = it })
                if (run.isNotEmpty()) {
                    P { Text(if (p.runPlan) "Preferred long run day" else "Long run") }
                    val runList = run.sorted()
                    Chips(listOf((if (p.runPlan) "Any" else "None") to (longRun == null)) + runList.map { it.short() to (it == longRun) }) { longRun = if (it == 0) null else runList[it - 1] }
                    if (!p.runPlan) { P { Text("Hard runs") }; DayPicker(hard, { hard = it }, enabled = { it in run }) }
                }
                Btn("Save changes", "primary", enabled = canSave) { save() }
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Units") }
                Input(InputType.Text) { value(weightText); attr("inputmode", "decimal"); attr("placeholder", "Weight (kg, optional)"); onInput { weightText = it.value.filter { c -> c.isDigit() || c == '.' }.take(5) } }
                Label(attrs = { classes("check") }) {
                    Input(InputType.Checkbox) { checked(wkg); onChange { wkg = it.value } }
                    Text("Show watts per kg")
                }
                Btn("Save", "primary", enabled = edited != p) { save() }
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Your data") }
                Muted("Everything is stored only on this phone, in this browser. Export a backup now and then, or send it to Matt for the pilot.")
                Div({ classes("footer") }) {
                    Btn("Export") { Files.shareJson("DBAP_${p.name}_${AppState.today()}.json", Store.encode(AppState.data)) }
                    Btn("Import") {
                        Files.pickFile("") { bytes ->
                            runCatching { Store.decode(bytes.decodeToString()) }
                                .onSuccess { imported -> AppState.update { imported }; AppState.showToast("Data imported") }
                                .onFailure { AppState.showToast("Couldn't read that file") }
                        }
                    }
                }
            }
        }
        Card {
            Div({ classes("tight") }) {
                H3 { Text("Plan: ${p.planWeeks} weeks from ${LocalDate.parse(p.startDate).withYear()}") }
                Btn("Reset plan and start again", "danger") { confirmReset = true }
            }
        }
        Muted("DBAP web version 0.3.0")
    }

    if (confirmReset) Div({ classes("overlay"); onClick { confirmReset = false } }) {
        Div({ classes("dialog"); onClick { it.stopPropagation() } }) {
            H3 { Text("Start again?") }
            P { Text("This deletes your plan, test history and completed sessions from this phone. Export first if you want a backup.") }
            Btn("Delete everything", "danger") { confirmReset = false; AppState.reset() }
            Btn("Cancel", "text") { confirmReset = false }
        }
    }
}

// ---- How-to guides ----

@Composable
private fun Guide(title: String, content: @Composable () -> Unit) = Div({ classes("app", "stack") }) {
    TopBar("How-to")
    H1 { Text(title) }
    content()
}

@Composable
private fun Section(title: String, vararg lines: String, note: String? = null) = Card {
    Div({ classes("tight") }) {
        H3 { Text(title) }
        lines.forEach { P { Text(it) } }
        note?.let { Muted(it) }
    }
}

@Composable
fun InstallGuide() = Guide("Add DBAP to your Home Screen") {
    P { Text("DBAP isn't in the App Store. It's a web app. Add it to your Home Screen once and it opens full screen like any other app, and works without signal.") }
    Section(
        "iPhone",
        "1. Open this page in Safari. On iOS 26 and later, Chrome, Edge and other browsers work too.",
        "2. Tap the Share button (the square with an arrow pointing up). In Safari it's at the bottom of the screen, or behind the ⋯ button.",
        "3. Scroll down and tap Add to Home Screen.",
        "4. If you see an \"Open as Web App\" switch, leave it on. Tap Add.",
        "5. From now on, open DBAP from its icon, not from the browser.",
        note = "Important: your plan is saved on the Home Screen version. If you set it up in the browser first and then add it to your Home Screen, use Settings › Export in the browser and Import in the Home Screen app.",
    )
    Section(
        "Android",
        "Use the proper Android app instead: ask Matt for the install file. Or, in Chrome, tap ⋮ › Add to Home screen.",
    )
    Section(
        "Keep your data safe",
        "Everything stays on your phone, and nothing is uploaded. That also means deleting the Home Screen icon deletes your plan.",
        "Every few weeks, tap Settings › Export and save the file to Files or iCloud Drive. If anything goes wrong, Settings › Import puts it all back.",
    )
}

@Composable
fun TrainerHelp() = Guide("Getting sessions onto your trainer") {
    P { Text("DBAP creates workout files in two formats: .zwo for Zwift and MyWhoosh, and .mrc for ROUVY and TrainingPeaks Virtual. Targets are a percentage of FTP, so the watts scale to the FTP set in that app. Keep your FTP the same in both places.") }
    Section(
        "1. Get the files to your trainer computer",
        "For the whole week: tap \"Week's sessions\" on the This Week screen. For one session: open it and tap the button for your app.",
        "On iPhone, the Share sheet opens: choose Mail to send it to yourself, Save to Files (iCloud Drive), or AirDrop it to a Mac. Then open it on the computer or tablet you ride with. Unzip the week's zip first.",
    )
    Section("2a. Zwift", "Copy the .zwo files into Documents › Zwift › Workouts › (your Zwift ID number). Restart Zwift. They appear under Workouts › Custom Workouts.")
    Section("2b. MyWhoosh", "Open the MyWhoosh workout builder on the MyWhoosh website, choose Import, and pick the .zwo file. It then appears in your custom workouts in the app.")
    Section("2c. ROUVY", "Log in to the ROUVY website on a computer and open Workouts. Create a workout by importing a file, and pick the .mrc file (ROUVY doesn't take .zwo). Save it, then open ROUVY on your trainer device. The workout is under your own workouts, and runs in ERG mode at your ROUVY FTP.")
    Section("2d. TrainingPeaks Virtual", "Go to Workouts › Import, and pick the .mrc file.")
    Muted("The week's zip has every session as both .zwo and .mrc. Use whichever your app needs. For FTP tests, use the .zwo or turn ERG mode off: .mrc can't do a \"ride as hard as you can\" effort.")
    Muted("Riding Zwift on an iPad or Apple TV? Zwift only reads custom workouts from a computer's Workouts folder. Copy them across on a PC or Mac once, and they sync to your other devices on the same Zwift account.")
}

@Composable
fun FeelHelp() = Guide("Rating sessions & easing off") {
    P { Text("After each ride, open the session and tap Mark done. Then pick how it felt. This is how DBAP knows whether the plan suits you.") }
    Section(
        "What to pick",
        "Easy: you could have done more.",
        "About right: hard where it should be, but you finished strong.",
        "Hard: you got through it, but it was a real struggle.",
        "Couldn't finish: you had to stop early or cut the intervals short.",
        note = "Change your mind? Open the session and tap Change.",
    )
    Section(
        "When sessions keep feeling too hard",
        "If two interval sessions in a row are rated Hard or Couldn't finish, DBAP offers three choices:",
        "• Lower my targets: every session from tomorrow is ${Adaptation.EASE_PCT}% easier. This lasts until your next FTP test, which sets new targets anyway.",
        "• Repeat this week: do this week again before moving on. The rest of the plan, including retests, moves back a week.",
        "• No thanks: carry on as planned. Sometimes a hard week is just a hard week.",
        note = "A bad night's sleep or a big run can make any session feel hard. If it keeps happening, easing off is the smart choice, not a failure.",
    )
    Section("Updating your trainer app", "If you lower your targets, download the sessions again so your trainer app gets the new targets.")
}

@Composable
fun FitHelp() = Guide("Adding your ride files (.fit)") {
    P { Text("This is optional. Every app you ride with saves a .fit file of your ride. Give it to DBAP and it reads your real power, so your progress and fitness trend are based on what you actually did, not just the plan.") }
    Section(
        "How to add one",
        "Open the session in DBAP and tap \"Add ride file (.fit)\". Then pick the file: on iPhone, tap Browse to find it in Files, iCloud Drive or Downloads.",
        "Got the file in Mail or WhatsApp? Save it to Files first (tap it, then Share › Save to Files), then add it as above.",
        note = "Zip files from Garmin Connect work too. No need to unzip.",
    )
    Section(
        "Where to find your .fit file",
        "Zwift: on your PC or Mac, Documents › Zwift › Activities. Or on zwift.com: your profile › Activities › pick the ride › Download.",
        "MyWhoosh: on the MyWhoosh website, open your activities, pick the ride and download the .fit file.",
        "ROUVY: on the ROUVY website, open your activity history, pick the ride and export or download the .fit file.",
        "TrainingPeaks Virtual: rides upload to TrainingPeaks. On the TrainingPeaks website, open the workout, then Files › download the .fit.",
        "Garmin Connect: open the activity, tap the cog › Export Original.",
        "Strava (your own rides): open the activity on the website, then ⋯ › Export Original.",
        note = "Menu names change from time to time. If something doesn't match, look for \"Export\" or \"Download\" on the ride.",
    )
    Section(
        "What you'll see",
        "The session is marked done, with your time, average and weighted power, and whether you hit the planned effort. For an FTP test, it finds your best 20 minutes and works out your new FTP for you.",
        note = "If the file has no power data (e.g. a ride without a power meter), the session still counts, using the planned effort.",
    )
}
