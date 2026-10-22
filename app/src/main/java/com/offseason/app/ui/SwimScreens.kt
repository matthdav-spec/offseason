package com.offseason.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offseason.app.format
import com.offseason.app.ui.theme.AppColors
import com.offseason.engine.AppData
import com.offseason.engine.Css
import com.offseason.engine.Plan
import com.offseason.engine.PlannedSwim
import com.offseason.engine.Rpe
import com.offseason.engine.SwimKind
import com.offseason.engine.SwimLevel
import com.offseason.engine.SwimLibrary
import com.offseason.engine.SwimStep
import com.offseason.engine.SwimStepType
import com.offseason.engine.SwimWorkout
import com.offseason.engine.SwimZone
import kotlinx.datetime.LocalDate

// Same screens and wording as the web app (web/.../SwimScreens.kt).

fun swimZoneColor(z: SwimZone): Color = when (z) {
    SwimZone.EASY -> Color(0xFF8A9BA8)
    SwimZone.STEADY -> Color(0xFF4A90D9)
    SwimZone.TEMPO -> Color(0xFF5CC08A)
    SwimZone.CSS -> Color(0xFFE6C54A)
    SwimZone.FAST -> Color(0xFFE5584F)
}

fun swimKindColor(k: SwimKind): Color = when (k) {
    SwimKind.TECHNIQUE, SwimKind.BUILD_UP -> Color(0xFF5BC0BE)
    SwimKind.ENDURANCE -> Color(0xFF3A9AD9)
    SwimKind.CSS_SET -> Color(0xFFE6C54A)
    SwimKind.CSS_TEST -> Color(0xFFE5584F)
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
private fun SwimSet(w: SwimWorkout, css: Int?) = SectionCard {
    val beginner = w.kind == SwimKind.BUILD_UP
    w.steps.forEach { step ->
        val (dist, what, pace) = swimLine(step, css, beginner)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Box(Modifier.width(6.dp).height(64.dp).background(swimZoneColor(step.zone), RoundedCornerShape(3.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(dist, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(what)
                Muted(pace)
            }
        }
    }
    HorizontalDivider()
    Row {
        Text("Total ${w.distanceM} m", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Muted("about ${w.estimatedMin(css)} min")
    }
}

/** A planned swim, big enough to read at the poolside. */
@Composable
fun SwimDetailScreen(
    swim: PlannedSwim,
    data: AppData,
    onMarkDone: (Rpe) -> Unit,
    onUndo: () -> Unit,
    onTest: () -> Unit,
    onDistance: (Int) -> Unit,
    readyToMoveUp: Boolean,
    onMoveUp: () -> Unit,
) {
    val w = swim.workout
    val css = data.currentCss
    val done = data.completions[swim.id]
    var askRpe by remember { mutableStateOf(false) }
    var askDistance by remember { mutableStateOf(false) }
    val continuous = w.kind == SwimKind.BUILD_UP && w.steps.any { it.type == SwimStepType.SWIM }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(swim.date.format("EEEE d MMMM") + " · Swim · ${w.distanceM} m")
        Text(w.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(w.purpose)
        if (css == null && w.kind != SwimKind.BUILD_UP && w.kind != SwimKind.CSS_TEST) Muted("Paces appear once you've done the CSS test. For now, go by the effort words.")
        SwimSet(w, css)
        Muted("Tip: screenshot this before you go, or read it out to yourself at the end of each length.")
        when {
            done != null -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = AppColors.done)
                    Text("  Done · felt ${done.rpe.label.lowercase()}", Modifier.weight(1f))
                    TextButton(onClick = { askRpe = true }) { Text("Change") }
                    TextButton(onClick = onUndo) { Text("Undo") }
                }
            }
            w.kind == SwimKind.CSS_TEST -> Button(onClick = onTest, Modifier.fillMaxWidth()) { Text("Enter my test times") }
            continuous -> Button(onClick = { askDistance = true }, Modifier.fillMaxWidth()) { Text("Mark done") }
            else -> Button(onClick = { askRpe = true }, Modifier.fillMaxWidth()) { Text("Mark done") }
        }
        if (readyToMoveUp) MoveUpCard(onMoveUp)
    }

    if (askDistance) {
        val target = w.steps.first { it.type == SwimStepType.SWIM }.distanceM
        AlertDialog(
            onDismissRequest = { askDistance = false },
            title = { Text("How far did you swim without stopping?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(target, target * 3 / 4, target / 2, target / 4).map { (it / 25) * 25 }.distinct().forEach { m ->
                        OutlinedButton(onClick = { onDistance(m); askDistance = false; askRpe = true }, Modifier.fillMaxWidth()) {
                            Text(if (m == target) "All $m m!" else "$m m")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askDistance = false }) { Text("Cancel") } },
        )
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

@Composable
fun MoveUpCard(onMoveUp: () -> Unit) = SectionCard {
    Text("🎉 You swam 400 m without stopping!", fontWeight = FontWeight.SemiBold)
    Text("That's the goal of the build-up plan. You're ready to move up to the CSS plan: from next week your swims get paces based on your own speed. It starts with the CSS test.")
    Button(onClick = onMoveUp, Modifier.fillMaxWidth()) { Text("Move up to the CSS plan") }
    Muted("Not ready? Carry on as you are. This offer stays here.")
}

/** The CSS test: protocol, two times, and the new paces. */
@Composable
fun CssTestScreen(data: AppData, swimId: String?, onSave: (css: Int, t400: Int, t200: Int) -> Unit, onMarkTestDone: (String) -> Unit) {
    val test = SwimLibrary.cssTest()
    var t400 by remember { mutableStateOf("") }
    var t200 by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Int?>(null) }
    val previous = remember { data.currentCss }
    val a = Css.parse(t400)
    val b = Css.parse(t200)
    val css = if (a != null && b != null && a > b && a in 180..1200 && b in 80..600) Css.fromTest(a, b) else null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CSS test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(test.purpose)
        SectionCard {
            Text("How to do it", fontWeight = FontWeight.SemiBold)
            Text("1. Warm up: 400 m easy, then 4 × 50 m building faster.")
            Text("2. Swim 400 m as fast as you can hold evenly. Note the time.")
            Text("3. Rest with 5 minutes of easy swimming.")
            Text("4. Swim 200 m flat out but even. Note the time.")
            Text("5. Easy cool-down.")
            Muted("Push off the wall each time (no dive). Ask a friend or the lifeguard to time you, or use a waterproof watch.")
        }
        SectionCard {
            Text("Your times", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                t400, { t400 = it.take(6) }, label = { Text("400 m time, e.g. 8:20") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                t200, { t200 = it.take(6) }, label = { Text("200 m time, e.g. 3:55") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth(),
            )
            if (css != null) {
                Text("Your CSS: ${Css.format(css)}/100 m", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                Muted("Half the difference between your two times.")
            } else if (t400.isNotBlank() && t200.isNotBlank()) Muted("Type times like 8:20. The 400 m time must be slower than the 200 m.")
            Button(
                enabled = css != null && saved == null,
                onClick = { onSave(css!!, a!!, b!!); swimId?.let(onMarkTestDone); saved = css },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save CSS") }
        }
        saved?.let { s ->
            SectionCard {
                Text("Saved. Your swim paces now use ${Css.format(s)}/100 m.", fontWeight = FontWeight.SemiBold)
                if (previous != null && previous != s) Text(
                    if (s < previous) "That's ${previous - s} s per 100 m faster than last time. Great work."
                    else "A little slower than last time (${Css.format(previous)}). Tiredness and a busy pool both show up here. The paces now match you today.",
                )
            }
            SwimZonesCard(s)
        }
    }
}

@Composable
fun SwimZonesCard(css: Int?) = SectionCard {
    Text("Your swim paces", fontWeight = FontWeight.SemiBold)
    if (css == null) Muted("Do the CSS test to see your paces per 100 m. Until then, go by the effort words.")
    SwimZone.entries.forEach { z ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(swimZoneColor(z))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(z.displayName, style = MaterialTheme.typography.bodyMedium)
                Muted(z.feel)
            }
            Text(Css.describe(z, css), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SwimProgressCard(plan: Plan, data: AppData) = SectionCard {
    val p = data.profile ?: return@SectionCard
    val onBuildUp = p.swimLevel == SwimLevel.BUILD_UP.name && p.swimCssFrom == null
    Text("Swim", fontWeight = FontWeight.SemiBold)
    if (onBuildUp) {
        val best = data.bestContinuousM ?: 0
        Text(if (best > 0) "$best m" else "–", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(if (best >= 400) "Without stopping. You've hit the 400 m goal!" else "Your longest swim without stopping. Goal: 400 m.")
        LinearProgressIndicator(progress = { best.coerceAtMost(400) / 400f }, modifier = Modifier.fillMaxWidth())
        if (data.swimDistanceHistory.size > 1) {
            HorizontalDivider()
            data.swimDistanceHistory.sortedBy { it.date }.forEach {
                Row { Text(LocalDate.parse(it.date).format("d MMM"), Modifier.weight(1f)); Text("${it.distanceM} m") }
            }
        }
    } else {
        val now = data.currentCss
        val start = data.startingCss
        if (now == null) Text("Do the CSS test to see your swim pace.")
        else {
            Text("${Css.format(now)}/100 m", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            if (start != null && start != now) Text("Started at ${Css.format(start)}. That's ${kotlin.math.abs(start - now)} s per 100 m ${if (now < start) "faster" else "slower"}.")
            else Text("Your CSS pace. Retest at the end of each block.")
        }
    }
    Muted("${plan.swims.count { it.id in data.completions }} of ${plan.swims.size} swims done.")
}

@Composable
fun SwimHelpScreen(css: Int?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Swimming with DBAP", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Your swims are pool sessions written as sets, like \"6 × 100 m, 20 s rest\". Each one shows the distance, what to do, and how hard.")
        SectionCard {
            Text("If you're building up to 400 m", fontWeight = FontWeight.SemiBold)
            Text("Swim the set distances at an easy pace, resting as long as you need between each. Each week the swims get a little longer.")
            Text("When you swim 400 m without stopping, DBAP offers to move you up to the CSS plan. You choose when.")
        }
        SectionCard {
            Text("CSS: your swim pace", fontWeight = FontWeight.SemiBold)
            Text("CSS (Critical Swim Speed) is roughly the pace you could hold for 1500 m. Time a 400 m and a 200 m; your CSS is half the difference. For example, 8:20 and 3:55 gives 2:12/100 m.")
            Text("You'll retest at the end of each 4-week block, alongside your bike and run tests.")
        }
        SwimZonesCard(css)
        SectionCard {
            Text("Tips", fontWeight = FontWeight.SemiBold)
            Text("• Breathe out steadily under water, so you only need to breathe in when you turn your head.")
            Text("• Count strokes per length on the easy swims. Fewer strokes at the same effort means better technique.")
            Text("• Use the pace clock on the wall for rests, or just count breaths.")
        }
    }
}

/** "IRONMAN 70.3 Cascais · 52 weeks to go" */
@Composable
fun RaceCountdown(data: AppData, today: LocalDate) {
    val p = data.profile ?: return
    val name = p.raceName ?: return
    val date = p.raceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
    val days = (date.toEpochDays() - today.toEpochDays()).toInt()
    if (days < 0) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Muted(date.format("EEEE d MMMM yyyy"))
            }
            Text(if (days == 0) "Race day!" else if (days < 14) "$days days to go" else "${days / 7} weeks to go", fontWeight = FontWeight.SemiBold)
        }
    }
}
