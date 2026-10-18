package com.offseason.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.offseason.engine.Expectations
import com.offseason.engine.PlanGenerator
import com.offseason.engine.PlanLength
import com.offseason.engine.RecoveryCycle
import com.offseason.engine.isBefore
import com.offseason.engine.isoNumber
import com.offseason.engine.plusDays
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

// Same wording as the Android app (OnboardingScreen.kt).
val READINESS_QUESTIONS = listOf(
    "heart" to "Has a doctor ever said you have a heart condition, or that you should only exercise under medical supervision?",
    "chest" to "Do you get chest pain, or feel faint or dizzy, when you exercise?",
    "bp" to "Do you have high blood pressure or any other condition that isn't well controlled?",
    "injury" to "Do you have a bone, joint or muscle problem that hard exercise could make worse?",
    "other" to "Is there any other reason (including pregnancy) you shouldn't do hard exercise?",
)

const val DISCLAIMER =
    "Off-Season gives general training guidance, not medical advice. Hard interval sessions and FTP tests put real " +
        "strain on your heart and body. If you have any health concerns, or answer \"yes\" to any question below, " +
        "speak to your GP before starting. Stop any session straight away if you feel unwell, dizzy or get chest pain. " +
        "You take part at your own risk."

@Composable
fun Onboarding() {
    val today = AppState.today()
    var page by remember { mutableStateOf(0) }
    val pages = 5

    var name by remember { mutableStateOf("") }
    var experience by remember { mutableStateOf(Experience.NEWER) }
    var ftpText by remember { mutableStateOf("") }
    var needsTest by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val readiness = remember { mutableStateMapOf<String, Boolean>() }
    var hours by remember { mutableStateOf(4) }
    var bike by remember { mutableStateOf(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }
    var swim by remember { mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)) }
    var run by remember { mutableStateOf(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)) }
    var longRun by remember { mutableStateOf<DayOfWeek?>(DayOfWeek.SUNDAY) }
    var hardRuns by remember { mutableStateOf(setOf(DayOfWeek.WEDNESDAY)) }
    var weeks by remember { mutableStateOf(12) }
    var cycle by remember { mutableStateOf(RecoveryCycle.THREE_ONE) }
    var start by remember { mutableStateOf(today.plusDays((DayOfWeek.MONDAY.isoNumber - today.dayOfWeek.isoNumber + 6) % 7 + 1)) }

    val ftp = ftpText.toIntOrNull()
    val canContinue = when (page) {
        1 -> accepted && READINESS_QUESTIONS.all { it.first in readiness }
        2 -> name.isNotBlank() && (needsTest || (ftp != null && ftp in 50..600))
        3 -> bike.isNotEmpty()
        else -> true
    }

    Div({ classes("app", "stack") }) {
        Div({ classes("progress") }) { Div({ style { property("width", "${(page + 1) * 100 / pages}%") } }) }
        when (page) {
            0 -> Welcome()
            1 -> Safety(accepted, { accepted = it }, readiness)
            2 -> AboutYou(name, { name = it }, experience, { experience = it }, ftpText, { ftpText = it.filter(Char::isDigit).take(3) }, needsTest, { needsTest = it })
            3 -> YourWeek(
                hours, { hours = it }, bike, { bike = it }, swim, { swim = it },
                run, { run = it; if (longRun !in it) longRun = null; hardRuns = hardRuns intersect it },
                longRun, { longRun = it }, hardRuns, { hardRuns = it },
            )
            4 -> YourPlan(weeks, { weeks = it }, cycle, { cycle = it }, start, { start = it }, today)
        }
        Div({ classes("footer") }) {
            if (page > 0) Btn("Back") { page--; kotlinx.browser.window.scrollTo(0.0, 0.0) }
            Btn(if (page < pages - 1) "Continue" else "Build my plan", "primary", enabled = canContinue) {
                if (page < pages - 1) { page++; kotlinx.browser.window.scrollTo(0.0, 0.0) }
                else AppState.completeOnboarding(
                    Profile(
                        name = name.trim(),
                        experience = experience,
                        hoursPerWeek = hours,
                        bikeDays = bike.map { it.isoNumber }.sorted(),
                        swimDays = swim.map { it.isoNumber }.sorted(),
                        runDays = run.map { it.isoNumber }.sorted(),
                        longRunDay = longRun?.isoNumber,
                        hardRunDays = hardRuns.map { it.isoNumber }.sorted(),
                        planWeeks = weeks,
                        startDate = start.toString(),
                        recoveryCycle = cycle.name,
                        disclaimerAcceptedOn = today.toString(),
                        readiness = readiness.toMap(),
                    ),
                    if (needsTest) null else ftp,
                )
            }
        }
    }
}

@Composable
private fun Welcome() {
    P({ classes("brand") }) { Text("Off-Season") }
    H2 { Text("Turn this winter into a stronger bike.") }
    P {
        Text(
            "A structured indoor plan to raise your FTP (the power you can hold for about an hour) on just 3–5 hours " +
                "a week, built around your swimming and running.",
        )
    }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("How it works") }
            P { Text("1. Do an FTP test (or enter a recent result).") }
            P { Text("2. Tell us your hours and which days you swim, run and ride.") }
            P { Text("3. Get a plan with exact watt targets. Download sessions for Zwift, MyWhoosh, ROUVY or TrainingPeaks Virtual.") }
            P { Text("4. Retest every 4 weeks. Your targets update automatically.") }
        }
    }
    Card {
        Div({ classes("tight") }) {
            H3 { Text("What to expect") }
            P { Text(Expectations.NEWER_RIDER) }
            P { Text(Expectations.EXPERIENCED) }
            Muted("Consistency beats heroics: hitting most of your sessions matters more than smashing any single one.")
        }
    }
    Muted("On iPhone: tap Share › Add to Home Screen to open Off-Season like an app. Settings › How-to guides explains more.")
}

@Composable
private fun Safety(accepted: Boolean, onAccept: (Boolean) -> Unit, answers: MutableMap<String, Boolean>) {
    H1 { Text("Before you start") }
    Card { P { Text(DISCLAIMER) } }
    H3 { Text("Quick readiness check") }
    READINESS_QUESTIONS.forEach { (key, q) ->
        Card {
            Div({ classes("tight") }) {
                P { Text(q) }
                Chips(listOf("No" to (answers[key] == false), "Yes" to (answers[key] == true))) { answers[key] = it == 1 }
            }
        }
    }
    if (answers.values.any { it }) Card {
        P({ classes("error") }) {
            Text("You answered yes to at least one question. Please check with your GP before doing the FTP test or hard intervals. You can still set up your plan.")
        }
    }
    Label(attrs = { classes("check") }) {
        Input(InputType.Checkbox) { checked(accepted); onChange { onAccept(it.value) } }
        Text("I've read and accept this")
    }
}

@Composable
private fun AboutYou(
    name: String, onName: (String) -> Unit,
    exp: Experience, onExp: (Experience) -> Unit,
    ftpText: String, onFtp: (String) -> Unit,
    needsTest: Boolean, onNeedsTest: (Boolean) -> Unit,
) {
    H1 { Text("About you") }
    Input(InputType.Text) {
        value(name); attr("placeholder", "First name"); attr("autocomplete", "given-name")
        onInput { onName(it.value) }
    }
    H3 { Text("Cycling experience") }
    Chips(listOf("Fairly new to structured training" to (exp == Experience.NEWER), "Experienced" to (exp == Experience.EXPERIENCED))) {
        onExp(if (it == 0) Experience.NEWER else Experience.EXPERIENCED)
    }
    H3 { Text("Your FTP") }
    Muted("Functional Threshold Power: the highest power you can hold for about an hour, in watts.")
    Input(InputType.Text) {
        value(ftpText); attr("placeholder", "FTP (watts)"); attr("inputmode", "numeric")
        if (needsTest) disabled()
        onInput { onFtp(it.value) }
    }
    Label(attrs = { classes("check") }) {
        Input(InputType.Checkbox) { checked(needsTest); onChange { onNeedsTest(it.value) } }
        Text("I don't know it. I need to test first.")
    }
    if (needsTest) Muted("No problem. We'll guide you through a 20-minute FTP test as your first session.")
}

@Composable
private fun YourWeek(
    hours: Int, onHours: (Int) -> Unit,
    bike: Set<DayOfWeek>, onBike: (Set<DayOfWeek>) -> Unit,
    swim: Set<DayOfWeek>, onSwim: (Set<DayOfWeek>) -> Unit,
    run: Set<DayOfWeek>, onRun: (Set<DayOfWeek>) -> Unit,
    longRun: DayOfWeek?, onLongRun: (DayOfWeek?) -> Unit,
    hardRuns: Set<DayOfWeek>, onHardRuns: (Set<DayOfWeek>) -> Unit,
) {
    val needed = if (hours == 3) 3 else 4
    H1 { Text("Your week") }
    H3 { Text("Bike hours per week") }
    Chips(listOf(3, 4, 5).map { "$it hours" to (hours == it) }) { onHours(it + 3) }
    Muted(
        when (hours) {
            3 -> "Two 60-min interval sessions + one 60-min steady ride."
            4 -> "Two 60-min interval sessions, a 90-min endurance ride, and an optional 30-min easy spin."
            else -> "Two 70-min interval sessions, a 2-hour endurance ride and a 45-min cadence session."
        },
    )
    H3 { Text("Which days can you ride?") }
    DayPicker(bike, onBike)
    if (bike.size < needed) Muted("Pick $needed days for the full plan. With fewer, we'll keep the most important sessions.")
    H3 { Text("Which days do you swim?") }
    DayPicker(swim, onSwim)
    H3 { Text("Which days do you run?") }
    DayPicker(run, onRun)
    if (run.isNotEmpty()) {
        H3 { Text("Long run day") }
        val runList = run.sorted()
        Chips(listOf("None" to (longRun == null)) + runList.map { it.short() to (it == longRun) }) {
            onLongRun(if (it == 0) null else runList[it - 1])
        }
        H3 { Text("Hard run sessions (intervals, tempo)") }
        DayPicker(hardRuns, onHardRuns, enabled = { it in run })
        Muted("We'll try to keep hard bike sessions away from your long and hard runs, so you're fresh for each.")
    }
}

@Composable
private fun YourPlan(
    weeks: Int, onWeeks: (Int) -> Unit,
    cycle: RecoveryCycle, onCycle: (RecoveryCycle) -> Unit,
    start: LocalDate, onStart: (LocalDate) -> Unit,
    today: LocalDate,
) {
    H1 { Text("Your plan") }
    H3 { Text("How long?") }
    Chips(listOf(4, 8, 12, 16).map { "$it weeks" to (weeks == it) }) { onWeeks((it + 1) * 4) }
    Muted(
        when (weeks) {
            4 -> "One block: sweet spot."
            8 -> "Sweet spot, then threshold."
            12 -> "Sweet spot, threshold, then VO2max."
            else -> "Sweet spot, threshold, VO2max, then race-specific work."
        } + " Each block ends with a recovery week and an FTP retest.",
    )
    H3 { Text("Recovery rhythm") }
    Chips(listOf("3 weeks on, 1 easy (standard)" to (cycle == RecoveryCycle.THREE_ONE), "2 on, 1 easy (recover slower)" to (cycle == RecoveryCycle.TWO_ONE))) {
        onCycle(RecoveryCycle.entries[it])
    }
    if (cycle == RecoveryCycle.TWO_ONE) {
        Muted("Your plan will be ${PlanGenerator.totalWeeks(PlanLength.ofWeeks(weeks), cycle)} weeks: 3-week blocks with more frequent recovery.")
    }
    H3 { Text("Start date") }
    Input(InputType.Date) {
        value(start.toString())
        onInput { e -> runCatching { LocalDate.parse(e.value) }.getOrNull()?.let(onStart) }
    }
    Muted("Weeks run from this day (${start.longDay()}). A Monday start is usually easiest.")
    if (start.isBefore(today)) Muted("That date is in the past. The plan will start part-way through.")
}
