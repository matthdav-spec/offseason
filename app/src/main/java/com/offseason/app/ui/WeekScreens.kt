package com.offseason.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offseason.app.data.AppData
import com.offseason.app.ui.theme.AppColors
import com.offseason.engine.Plan
import com.offseason.engine.PlanWeek
import com.offseason.engine.PlannedSession
import com.offseason.engine.RunKind
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import com.offseason.engine.*
import com.offseason.app.format
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** Screen 4: this week, today highlighted, traffic light, download bundle. */
@Composable
fun ThisWeekScreen(
    plan: Plan,
    data: AppData,
    today: LocalDate,
    status: Status.Message,
    onOpenSession: (String) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenSwim: (String) -> Unit,
    onMoveUp: () -> Unit,
    onDownloadWeek: (PlanWeek) -> Unit,
    onFtpTest: () -> Unit,
    onLthrTest: () -> Unit,
) {
    val week = plan.weekFor(today) ?: if (today.isBefore(plan.startDate)) plan.weeks.first() else plan.weeks.last()
    val ftp = data.currentFtp
    val hasBike = plan.request.hasBike
    val hasRun = plan.request.hasRun
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Hi ${data.profile?.name ?: ""}", style = MaterialTheme.typography.headlineSmall)
            Muted(
                when {
                    today.isBefore(plan.startDate) -> "Your plan starts ${plan.startDate.format(dayFmt)}."
                    today.isAfter(plan.endDate) -> "Plan complete. Well done!"
                    else -> "Week ${week.index + 1} of ${plan.weeks.size} · ${week.title}"
                },
            )
        }
        if (data.profile?.raceName != null) item { RaceCountdown(data, today) }
        if (AppLogic.readyToMoveUp(data)) item { MoveUpCard(onMoveUp) }
        if (hasRun && data.currentLthr == null) item {
            SectionCard {
                Text("Find your LTHR", fontWeight = FontWeight.SemiBold)
                Text("Your run zones are based on LTHR. Do the 30-minute test in your first week. Until then, run easy by feel.")
                Button(onClick = onLthrTest) { Text("LTHR test") }
            }
        }
        if (hasBike && ftp == null) item {
            SectionCard {
                Text("First, find your FTP", fontWeight = FontWeight.SemiBold)
                Text("Every target in your plan is based on FTP. Do the 20-minute test, then enter your result.")
                Button(onClick = onFtpTest) { Icon(Icons.Default.Speed, null); Text("  FTP test") }
            }
        }
        item { TrafficLight(status) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${week.startDate.format(dayFmt)} – ${week.endDate.format(dayFmt)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hasBike) OutlinedButton(onClick = { onDownloadWeek(week) }) {
                    Icon(Icons.Default.Download, null); Text(" Week's rides")
                }
            }
        }
        items((0..6).map { week.startDate.plusDays(it.toLong()) }) { date ->
            DayRow(date, week.sessions.filter { it.date == date }, week.runs.filter { it.date == date }, week.swims.filter { it.date == date }, plan, data, ftp, isToday = date == today, onOpenSession, onOpenRun, onOpenSwim)
        }
        if (hasBike) item {
            Muted("Download the rides, then load them into Zwift, MyWhoosh, ROUVY or TPV. See Settings › How-to guides.")
        }
    }
}

@Composable
private fun DayRow(
    date: LocalDate,
    sessions: List<PlannedSession>,
    runs: List<com.offseason.engine.PlannedRun>,
    swims: List<com.offseason.engine.PlannedSwim>,
    plan: Plan,
    data: AppData,
    ftp: Int?,
    isToday: Boolean,
    onOpen: (String) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenSwim: (String) -> Unit,
) {
    val profile = data.profile
    val req = plan.request
    val other = otherSports(date.dayOfWeek, profile?.toPlanRequest()?.schedule, planned = req.hasRun, swimsPlanned = req.hasSwim)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (isToday) "Today · " else "") + date.format(dayFmt),
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (other.isNotBlank()) Text(other, color = AppColors.greyedOut, style = MaterialTheme.typography.bodySmall)
            }
            val sports = listOf(req.hasBike, req.hasRun, req.hasSwim).count { it }
            if (sessions.isEmpty() && runs.isEmpty() && swims.isEmpty() && other.isEmpty()) Muted(
                if (sports > 1) "Rest day" else if (req.hasRun) "No run" else if (req.hasSwim) "No swim" else "No bike session",
            )
            swims.forEach { sw ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenSwim(sw.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).height(36.dp).background(swimKindColor(sw.workout.kind), RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Swim · " + sw.workout.name, fontWeight = FontWeight.SemiBold)
                        Muted("${sw.workout.distanceM} m · about ${sw.workout.estimatedMin(data.currentCss)} min")
                    }
                    if (sw.id in data.completions) Icon(Icons.Default.CheckCircle, "Done", tint = AppColors.done)
                }
            }
            sessions.forEach { s ->
                val done = s.id in data.completions
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).height(36.dp).background(AppColors.kind(s.workout.kind), RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(s.workout.name + if (s.optional) " (optional)" else "", fontWeight = FontWeight.SemiBold)
                        Muted(minutesLabel(s.workout.durationMin) + (ftp?.let { "" } ?: " · enter FTP for watts"))
                    }
                    if (done) Icon(Icons.Default.CheckCircle, "Done", tint = AppColors.done)
                }
            }
            runs.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenRun(r.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).height(36.dp).background(runKindColor(r.workout.kind), RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Run · " + r.workout.name, fontWeight = FontWeight.SemiBold)
                        Muted(minutesLabel(r.workout.durationMin) + if (data.currentLthr == null) " · by feel until LTHR test" else "")
                    }
                    if (r.id in data.completions) Icon(Icons.Default.CheckCircle, "Done", tint = AppColors.done)
                }
            }
        }
    }
}

/** Other sports on a day, shown greyed for context. Planned runs and swims are shown as sessions, so skipped here. */
fun otherSports(day: DayOfWeek, schedule: com.offseason.engine.AthleteSchedule?, planned: Boolean = false, swimsPlanned: Boolean = false): String {
    if (schedule == null) return ""
    val parts = mutableListOf<String>()
    if (day in schedule.swimDays && !swimsPlanned) parts += "Swim"
    if (planned) return parts.joinToString(" · ")
    if (day == schedule.longRunDay) parts += "Long run"
    else if (day in schedule.hardRunDays) parts += "Hard run"
    else if (day in schedule.runDays) parts += "Run"
    return parts.joinToString(" · ")
}

/** Screen 3: whole plan, week by week. */
@Composable
fun PlanOverviewScreen(plan: Plan, data: AppData, today: LocalDate, onOpenSession: (String) -> Unit, onOpenRun: (String) -> Unit = {}, onOpenSwim: (String) -> Unit = {}) {
    val current = plan.weekFor(today)?.index ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val schedule = data.profile?.toPlanRequest()?.schedule
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(plan.weeks) { week ->
            val isCurrent = week.contains(today)
            val blockColor = if (week.isRecovery) Color(0xFF6FCF97) else AppColors.kind(SessionKind.INTERVALS)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(blockColor)
                        Text(
                            "Week ${week.index + 1} · ${week.title}",
                            Modifier.padding(start = 8.dp).weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Muted(week.startDate.format(DateTimeFormatter.ofPattern("d MMM")))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..6).forEach { i ->
                            val date = week.startDate.plusDays(i.toLong())
                            val s = week.sessions.firstOrNull { it.date == date }
                            val r = week.runs.firstOrNull { it.date == date }
                            val sw = week.swims.firstOrNull { it.date == date }
                            val other = otherSports(date.dayOfWeek, schedule, planned = plan.request.hasRun, swimsPlanned = plan.request.hasSwim)
                            Column(
                                Modifier.weight(1f)
                                    .background(
                                        s?.let { AppColors.kind(it.workout.kind).copy(alpha = 0.85f) } ?: MaterialTheme.colorScheme.surfaceContainer,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable(enabled = s != null) { s?.let { onOpenSession(it.id) } }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(date.dayOfWeek.short().take(2), style = MaterialTheme.typography.labelSmall, color = if (s != null) Color.Black else AppColors.greyedOut)
                                Text(
                                    when {
                                        s == null -> if (r == null && sw == null) other.split(" ").firstOrNull()?.take(4) ?: "" else ""
                                        s.workout.kind == SessionKind.FTP_TEST -> "TEST"
                                        s.id in data.completions -> "✓"
                                        else -> "${s.workout.durationMin}'"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (s != null) Color.Black else AppColors.greyedOut,
                                )
                                if (sw != null) Text(
                                    when {
                                        sw.workout.kind == SwimKind.CSS_TEST -> "CSS"
                                        sw.id in data.completions -> "S✓"
                                        else -> "S${sw.workout.distanceM}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .background(swimKindColor(sw.workout.kind), RoundedCornerShape(4.dp))
                                        .clickable { onOpenSwim(sw.id) }
                                        .padding(horizontal = 3.dp),
                                )
                                if (r != null) Text(
                                    when {
                                        r.workout.kind == RunKind.LTHR_TEST -> "LTHR"
                                        r.id in data.completions -> "R✓"
                                        else -> "R${r.workout.durationMin}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .background(runKindColor(r.workout.kind), RoundedCornerShape(4.dp))
                                        .clickable { onOpenRun(r.id) }
                                        .padding(horizontal = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
