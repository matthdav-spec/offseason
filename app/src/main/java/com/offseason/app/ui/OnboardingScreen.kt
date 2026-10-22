package com.offseason.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offseason.app.R
import com.offseason.app.data.Experience
import com.offseason.app.data.Profile
import com.offseason.engine.Expectations
import com.offseason.engine.PlanGenerator
import com.offseason.engine.PlanLength
import com.offseason.engine.RecoveryCycle
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import com.offseason.engine.*
import com.offseason.app.format
import java.time.format.DateTimeFormatter

val READINESS_QUESTIONS = listOf(
    "heart" to "Has a doctor ever said you have a heart condition, or that you should only exercise under medical supervision?",
    "chest" to "Do you get chest pain, or feel faint or dizzy, when you exercise?",
    "bp" to "Do you have high blood pressure or any other condition that isn't well controlled?",
    "injury" to "Do you have a bone, joint or muscle problem that hard exercise could make worse?",
    "other" to "Is there any other reason (including pregnancy) you shouldn't do hard exercise?",
)

const val DISCLAIMER =
    "DBAP gives general training guidance, not medical advice. Hard interval sessions and FTP tests put real " +
        "strain on your heart and body. If you have any health concerns, or answer \"yes\" to any question below, " +
        "speak to your GP before starting. Stop any session straight away if you feel unwell, dizzy or get chest pain. " +
        "You take part at your own risk."

/** First-run setup: welcome → safety → about you → your week → your plan. */
@Composable
fun OnboardingScreen(
    onFinish: (Profile, ftp: Int?, lthr: Int?) -> Unit,
    today: LocalDate,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = 5

    // Form state
    var name by rememberSaveable { mutableStateOf("") }
    var experience by rememberSaveable { mutableStateOf(Experience.NEWER) }
    var ftpText by rememberSaveable { mutableStateOf("") }
    var needsTest by rememberSaveable { mutableStateOf(false) }
    var doBike by rememberSaveable { mutableStateOf(true) }
    var doRun by rememberSaveable { mutableStateOf(false) }
    var doSwim by rememberSaveable { mutableStateOf(false) }
    var swimsPerWeek by rememberSaveable { mutableIntStateOf(2) }
    var pool by rememberSaveable { mutableIntStateOf(25) }
    var canSwim400 by rememberSaveable { mutableStateOf(false) }
    var swimStartM by rememberSaveable { mutableIntStateOf(0) }
    var hasRace by rememberSaveable { mutableStateOf(false) }
    var raceName by rememberSaveable { mutableStateOf("") }
    var raceDate by remember { mutableStateOf<LocalDate?>(null) }
    var runHours by rememberSaveable { mutableIntStateOf(3) }
    var lthrText by rememberSaveable { mutableStateOf("") }
    var needsLthrTest by rememberSaveable { mutableStateOf(false) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    val readiness = remember { mutableStateMapOf<String, Boolean>() }
    var hours by rememberSaveable { mutableIntStateOf(4) }
    var bikeDays by remember { mutableStateOf(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }
    var swimDays by remember { mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)) }
    var runDays by remember { mutableStateOf(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)) }
    var longRun by remember { mutableStateOf<DayOfWeek?>(DayOfWeek.SUNDAY) }
    var hardRuns by remember { mutableStateOf(setOf(DayOfWeek.WEDNESDAY)) }
    var weeks by rememberSaveable { mutableIntStateOf(12) }
    var cycle by rememberSaveable { mutableStateOf(RecoveryCycle.THREE_ONE) }
    var startDate by remember { mutableStateOf(today.plusDays((DayOfWeek.MONDAY.isoNumber - today.dayOfWeek.isoNumber + 6) % 7 + 1)) }

    val ftp = ftpText.toIntOrNull()
    val lthr = lthrText.toIntOrNull()
    val canContinue = when (page) {
        1 -> accepted && READINESS_QUESTIONS.all { it.first in readiness }
        2 -> name.isNotBlank() && (doBike || doRun || doSwim) &&
            (!doBike || needsTest || (ftp != null && ftp in 50..600)) &&
            (!doRun || needsLthrTest || (lthr != null && lthr in 100..220))
        3 -> (!doBike || bikeDays.isNotEmpty()) && (!doRun || runDays.isNotEmpty()) && (!doSwim || swimDays.isNotEmpty())
        4 -> !hasRace || (raceName.isNotBlank() && raceDate != null)
        else -> true
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        LinearProgressIndicator(progress = { (page + 1f) / pages }, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (page) {
                0 -> WelcomePage()
                1 -> SafetyPage(accepted, { accepted = it }, readiness)
                2 -> AboutYouPage(
                    name, { name = it }, experience, { experience = it },
                    doBike, { doBike = it }, doRun, { doRun = it }, doSwim, { doSwim = it },
                    ftpText, { ftpText = it.filter(Char::isDigit).take(3) }, needsTest, { needsTest = it },
                    lthrText, { lthrText = it.filter(Char::isDigit).take(3) }, needsLthrTest, { needsLthrTest = it },
                )
                3 -> WeekPage(
                    doBike, doRun, doSwim, swimsPerWeek, { swimsPerWeek = it }, pool, { pool = it },
                    canSwim400, { canSwim400 = it }, swimStartM, { swimStartM = it },
                    hours, { hours = it }, runHours, { runHours = it }, bikeDays, { bikeDays = it }, swimDays, { swimDays = it },
                    runDays, { runDays = it; if (longRun !in it) longRun = null; hardRuns = hardRuns intersect it },
                    longRun, { longRun = it }, hardRuns, { hardRuns = it },
                )
                4 -> PlanPage(
                    weeks, { weeks = it }, cycle, { cycle = it }, startDate, { startDate = it }, today,
                    hasRace, { hasRace = it }, raceName, { raceName = it }, raceDate, { raceDate = it },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) OutlinedButton(onClick = { page-- }, Modifier.weight(1f)) { Text("Back") }
            Button(
                enabled = canContinue,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (page < pages - 1) page++
                    else onFinish(
                        Profile(
                            name = name.trim(),
                            experience = experience,
                            hoursPerWeek = hours,
                            bikeDays = if (doBike) bikeDays.map { it.isoNumber }.sorted() else emptyList(),
                            swimDays = swimDays.map { it.isoNumber }.sorted(),
                            runDays = runDays.map { it.isoNumber }.sorted(),
                            longRunDay = longRun?.isoNumber,
                            hardRunDays = hardRuns.map { it.isoNumber }.sorted(),
                            planWeeks = weeks,
                            startDate = startDate.toString(),
                            recoveryCycle = cycle.name,
                            disclaimerAcceptedOn = today.toString(),
                            readiness = readiness.toMap(),
                            runPlan = doRun,
                            runHoursPerWeek = runHours,
                            swimPlan = doSwim,
                            swimsPerWeek = swimsPerWeek,
                            poolLengthM = pool,
                            swimLevel = if (canSwim400) "CSS" else "BUILD_UP",
                            swimStartDistanceM = if (canSwim400) 0 else swimStartM,
                            raceName = if (hasRace) raceName.trim() else null,
                            raceDate = if (hasRace) raceDate?.toString() else null,
                        ),
                        if (!doBike || needsTest) null else ftp,
                        if (!doRun || needsLthrTest) null else lthr,
                    )
                },
            ) { Text(if (page < pages - 1) "Continue" else "Build my plan") }
        }
    }
}

@Composable
private fun WelcomePage() {
    Image(painterResource(R.drawable.dbap_wordmark), contentDescription = "DBAP", modifier = Modifier.padding(top = 12.dp).width(220.dp))
    Text("Built Different", style = MaterialTheme.typography.titleLarge)
    Text(
        "A structured winter plan for swim, bike and run: pick any or all three. Pool sessions built on your swim pace, " +
            "indoor bike sessions to raise your FTP, and running by heart-rate zones, all scheduled around each other.",
    )
    SectionCard {
        Text("How it works", fontWeight = FontWeight.SemiBold)
        Text("1. Choose swim, bike, run or all three. Do the tests (or enter recent results): CSS for the swim, FTP for the bike, LTHR for the run. New to swimming? Start on the build-up route instead.")
        Text("2. Tell us your hours and which days you swim, ride and run.")
        Text("3. Get a plan with exact targets: pace per 100 m in the pool, watts for the bike (download for Zwift, MyWhoosh, ROUVY or TPV) and heart rate for the run.")
        Text("4. Retest every 4 weeks. Your targets update automatically.")
    }
    SectionCard {
        Text("What to expect", fontWeight = FontWeight.SemiBold)
        Text(Expectations.NEWER_RIDER)
        Text(Expectations.EXPERIENCED)
        Muted("Consistency beats heroics: hitting most of your sessions matters more than smashing any single one.")
    }
}

@Composable
private fun SafetyPage(accepted: Boolean, onAccept: (Boolean) -> Unit, answers: MutableMap<String, Boolean>) {
    Text("Before you start", style = MaterialTheme.typography.headlineSmall)
    SectionCard { Text(DISCLAIMER, style = MaterialTheme.typography.bodyMedium) }
    Text("Quick readiness check", style = MaterialTheme.typography.titleMedium)
    READINESS_QUESTIONS.forEach { (key, q) ->
        SectionCard {
            Text(q, style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(listOf("No" to (answers[key] == false), "Yes" to (answers[key] == true))) { answers[key] = it == 1 }
        }
    }
    if (answers.values.any { it }) {
        SectionCard {
            Text(
                "You answered yes to at least one question. Please check with your GP before doing the FTP test or hard " +
                    "intervals. You can still set up your plan.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    CheckRow(accepted, onAccept, "I've read and accept this")
}

@Composable
private fun AboutYouPage(
    name: String, onName: (String) -> Unit,
    exp: Experience, onExp: (Experience) -> Unit,
    doBike: Boolean, onDoBike: (Boolean) -> Unit,
    doRun: Boolean, onDoRun: (Boolean) -> Unit,
    doSwim: Boolean, onDoSwim: (Boolean) -> Unit,
    ftpText: String, onFtp: (String) -> Unit,
    needsTest: Boolean, onNeedsTest: (Boolean) -> Unit,
    lthrText: String, onLthr: (String) -> Unit,
    needsLthrTest: Boolean, onNeedsLthrTest: (Boolean) -> Unit,
) {
    Text("About you", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(name, onName, label = { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("What would you like a plan for?", style = MaterialTheme.typography.titleSmall)
    Muted("Pick one or more.")
    ChoiceChips(listOf("Swim" to doSwim, "Bike" to doBike, "Run" to doRun)) { i ->
        when (i) { 0 -> onDoSwim(!doSwim); 1 -> onDoBike(!doBike); else -> onDoRun(!doRun) }
    }
    Muted(
        when (listOf(doSwim, doBike, doRun).count { it }) {
            0 -> "Choose at least one sport."
            1 -> if (doRun) "A heart-rate-based run plan." else if (doSwim) "A pool plan, from building up to 400 m through to race-pace sets." else "An indoor bike plan to raise your FTP."
            else -> "Your sports are scheduled together, so hard days never land back to back."
        },
    )
    Text("Training experience", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf("Fairly new to structured training" to (exp == Experience.NEWER), "Experienced" to (exp == Experience.EXPERIENCED))) {
        onExp(if (it == 0) Experience.NEWER else Experience.EXPERIENCED)
    }
    if (doRun) {
        Spacer(Modifier.padding(4.dp))
        Text("Your LTHR (run)", style = MaterialTheme.typography.titleSmall)
        Muted("Lactate threshold heart rate: your average heart rate over the last 20 minutes of a hard 30-minute solo run.")
        OutlinedTextField(
            lthrText, onLthr, enabled = !needsLthrTest, label = { Text("LTHR (bpm)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        CheckRow(needsLthrTest, onNeedsLthrTest, "I don't know it. I need to test first.")
        if (needsLthrTest) Muted("No problem. The run plan starts easy, so do the 30-minute LTHR test in your first week. Until then, run by feel.")
    }
    if (!doBike) return
    Spacer(Modifier.padding(4.dp))
    Text("Your FTP (bike)", style = MaterialTheme.typography.titleSmall)
    Muted("Functional Threshold Power: the highest power you can hold for about an hour, in watts.")
    OutlinedTextField(
        ftpText, onFtp, enabled = !needsTest, label = { Text("FTP (watts)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    CheckRow(needsTest, onNeedsTest, "I don't know it. I need to test first.")
    if (needsTest) Muted("No problem. We'll guide you through a 20-minute FTP test as your first session.")
}

@Composable
private fun WeekPage(
    doBike: Boolean, doRun: Boolean, doSwim: Boolean,
    swimsPerWeek: Int, onSwimsPerWeek: (Int) -> Unit,
    pool: Int, onPool: (Int) -> Unit,
    canSwim400: Boolean, onCanSwim400: (Boolean) -> Unit,
    swimStartM: Int, onSwimStartM: (Int) -> Unit,
    hours: Int, onHours: (Int) -> Unit,
    runHours: Int, onRunHours: (Int) -> Unit,
    bike: Set<DayOfWeek>, onBike: (Set<DayOfWeek>) -> Unit,
    swim: Set<DayOfWeek>, onSwim: (Set<DayOfWeek>) -> Unit,
    run: Set<DayOfWeek>, onRun: (Set<DayOfWeek>) -> Unit,
    longRun: DayOfWeek?, onLongRun: (DayOfWeek?) -> Unit,
    hardRuns: Set<DayOfWeek>, onHardRuns: (Set<DayOfWeek>) -> Unit,
) {
    val needed = if (hours == 3) 3 else 4
    Text("Your week", style = MaterialTheme.typography.headlineSmall)
    if (doBike) BikeWeek(hours, onHours, bike, onBike, needed)
    if (doSwim) SwimWeek(swimsPerWeek, onSwimsPerWeek, pool, onPool, canSwim400, onCanSwim400, swimStartM, onSwimStartM, swim, onSwim)
    else {
        Text("Which days do you swim?", style = MaterialTheme.typography.titleSmall)
        DayPicker(swim, onSwim)
    }
    if (doRun) {
        Text("Run hours per week", style = MaterialTheme.typography.titleSmall)
        ChoiceChips(listOf(2, 3, 4).map { "$it hours" to (runHours == it) }) { onRunHours(it + 2) }
        Muted(
            when (runHours) {
                2 -> "A long run, one quality run and an easy run."
                3 -> "A long run, one quality run, an easy run and an easy run with strides."
                else -> "As 3 hours, with a longer long run and more easy running."
            } + " Mostly easy running: the plan builds by no more than 10% a week.",
        )
        Text("Which days can you run?", style = MaterialTheme.typography.titleSmall)
        DayPicker(run, onRun)
        val runNeeded = if (runHours == 2) 3 else 4
        if (run.size < runNeeded) Muted("Pick $runNeeded days for the full plan. With fewer, we'll keep the long run and quality run.")
        if (run.isNotEmpty()) {
            Text("Preferred long run day", style = MaterialTheme.typography.titleSmall)
            val runList = run.sorted()
            ChoiceChips(listOf("Any" to (longRun == null)) + runList.map { it.short() to (it == longRun) }) {
                onLongRun(if (it == 0) null else runList[it - 1])
            }
            if (doBike) Muted("Bike and run are planned together: at most 3 hard days a week, never two in a row, and the long ride and long run on different days.")
        }
        return
    }
    Text("Which days do you run?", style = MaterialTheme.typography.titleSmall)
    DayPicker(run, onRun)
    if (run.isNotEmpty()) {
        Text("Long run day", style = MaterialTheme.typography.titleSmall)
        val runList = run.sorted()
        ChoiceChips(listOf("None" to (longRun == null)) + runList.map { it.short() to (it == longRun) }) {
            onLongRun(if (it == 0) null else runList[it - 1])
        }
        Text("Hard run sessions (intervals, tempo)", style = MaterialTheme.typography.titleSmall)
        DayPicker(hardRuns, onHardRuns, enabled = { it in run })
        Muted("We'll try to keep hard bike sessions away from your long and hard runs, so you're fresh for each.")
    }
}

@Composable
private fun SwimWeek(
    perWeek: Int, onPerWeek: (Int) -> Unit,
    pool: Int, onPool: (Int) -> Unit,
    can400: Boolean, onCan400: (Boolean) -> Unit,
    startM: Int, onStartM: (Int) -> Unit,
    days: Set<DayOfWeek>, onDays: (Set<DayOfWeek>) -> Unit,
) {
    Text("Swims per week", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf(1, 2, 3).map { "$it" to (perWeek == it) }) { onPerWeek(it + 1) }
    Text("Which days can you swim?", style = MaterialTheme.typography.titleSmall)
    DayPicker(days, onDays)
    if (days.size < perWeek) Muted("Pick $perWeek days, or fewer swims per week.")
    Text("Pool length", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf("25 m" to (pool == 25), "50 m" to (pool == 50))) { onPool(if (it == 0) 25 else 50) }
    Text("Can you swim 400 m front crawl without stopping?", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf("Yes" to can400, "Not yet" to !can400)) { onCan400(it == 0) }
    if (can400) Muted("Great. Your plan starts with technique and endurance, and the CSS test sets your paces.")
    else {
        Muted("No problem, that's what the build-up plan is for. Short swims with plenty of rest, getting a little longer each week until you can swim 400 m.")
        Text("Roughly how far can you swim without stopping?", style = MaterialTheme.typography.titleSmall)
        val options = listOf(0, 50, 100, 200, 300)
        ChoiceChips(options.map { (if (it == 0) "Not sure" else "$it m") to (startM == it) }) { onStartM(options[it]) }
    }
}

@Composable
private fun BikeWeek(hours: Int, onHours: (Int) -> Unit, bike: Set<DayOfWeek>, onBike: (Set<DayOfWeek>) -> Unit, needed: Int) {
    Text("Bike hours per week", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf(3, 4, 5).map { "$it hours" to (hours == it) }) { onHours(it + 3) }
    Muted(
        when (hours) {
            3 -> "Two 60-min interval sessions + one 60-min steady ride."
            4 -> "Two 60-min interval sessions, a 90-min endurance ride, and an optional 30-min easy spin."
            else -> "Two 70-min interval sessions, a 2-hour endurance ride and a 45-min cadence session."
        },
    )
    Text("Which days can you ride?", style = MaterialTheme.typography.titleSmall)
    DayPicker(bike, onBike)
    if (bike.size < needed) Muted("Pick $needed days for the full plan. With fewer, we'll keep the most important sessions.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPage(
    weeks: Int, onWeeks: (Int) -> Unit,
    cycle: RecoveryCycle, onCycle: (RecoveryCycle) -> Unit,
    start: LocalDate, onStart: (LocalDate) -> Unit,
    today: LocalDate,
    hasRace: Boolean, onHasRace: (Boolean) -> Unit,
    raceName: String, onRaceName: (String) -> Unit,
    raceDate: LocalDate?, onRaceDate: (LocalDate) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var pickingRace by remember { mutableStateOf(false) }
    Text("Your plan", style = MaterialTheme.typography.headlineSmall)
    Text("How long?", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(listOf(4, 8, 12, 16).map { "$it weeks" to (weeks == it) }) { onWeeks((it + 1) * 4) }
    Muted(
        when (weeks) {
            4 -> "One block: sweet spot."
            8 -> "Sweet spot, then threshold."
            12 -> "Sweet spot, threshold, then VO2max."
            else -> "Sweet spot, threshold, VO2max, then race-specific work."
        } + " Each block ends with a recovery week and an FTP retest.",
    )
    Text("Recovery rhythm", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(
        listOf("3 weeks on, 1 easy (standard)" to (cycle == RecoveryCycle.THREE_ONE), "2 on, 1 easy (recover slower)" to (cycle == RecoveryCycle.TWO_ONE)),
    ) { onCycle(RecoveryCycle.entries[it]) }
    if (cycle == RecoveryCycle.TWO_ONE) {
        Muted("Your plan will be ${PlanGenerator.totalWeeks(PlanLength.ofWeeks(weeks), cycle)} weeks: 3-week blocks with more frequent recovery.")
    }
    Text("Start date", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { picking = true }) { Text(start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))) }
    Muted("Weeks run from this day. A Monday start is usually easiest.")

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.toEpochDays().toLong() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onStart(LocalDate.fromEpochDays((it / 86_400_000L).toInt())) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (start.isBefore(today)) Muted("That date is in the past. The plan will start part-way through.")

    Text("Got a race in the diary?", style = MaterialTheme.typography.titleSmall)
    CheckRow(hasRace, onHasRace, "Yes, I'm training for a race")
    if (hasRace) {
        OutlinedTextField(raceName, onRaceName, label = { Text("Race, e.g. IRONMAN 70.3 Cascais") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { pickingRace = true }) {
            Text(raceDate?.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")) ?: "Choose race date")
        }
        Muted("DBAP shows a countdown. This winter plan builds your base; a race-build plan working back from the date is coming later.")
    }
    if (pickingRace) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (raceDate ?: today).toEpochDays().toLong() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickingRace = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onRaceDate(LocalDate.fromEpochDays((it / 86_400_000L).toInt())) }
                    pickingRace = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingRace = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** A checkbox with its label; tapping the words ticks it too. */
@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}
