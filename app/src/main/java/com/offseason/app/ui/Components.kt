package com.offseason.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offseason.app.ui.theme.AppColors
import com.offseason.engine.Status
import com.offseason.engine.Step
import com.offseason.engine.StepType
import com.offseason.engine.Workout
import com.offseason.engine.Zone
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.UK)

fun minutesLabel(min: Int): String = if (min >= 60) "${min / 60}h" + (if (min % 60 > 0) " ${min % 60}m" else "") else "$min min"

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun TrafficLight(message: Status.Message, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).background(AppColors.light(message.light), CircleShape))
            Text(message.headline, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(message.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seven day chips, multi-select. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayPicker(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit, enabled: (DayOfWeek) -> Boolean = { true }) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { d ->
            FilterChip(
                selected = d in selected,
                enabled = enabled(d),
                onClick = { onChange(if (d in selected) selected - d else selected + d) },
                label = { Text(d.short()) },
            )
        }
    }
}

/** Flattens steps into (duration, start %, end %) segments for drawing. */
private fun segments(steps: List<Step>): List<Triple<Int, Double, Double>> = buildList {
    for (s in steps) when (s.type) {
        StepType.WARMUP, StepType.COOLDOWN, StepType.RAMP ->
            add(Triple(s.durationSec, s.powerPctLow.toDouble(), (s.powerPctHigh ?: s.powerPctLow).toDouble()))
        StepType.INTERVALS -> repeat(s.repeat!!) {
            add(Triple(s.durationSec, s.targetPct, s.targetPct))
            val off = s.offPowerPct!!.toDouble()
            add(Triple(s.offDurationSec!!, off, off))
        }
        StepType.STEADY, StepType.FREE_RIDE -> add(Triple(s.durationSec, s.targetPct, s.targetPct))
    }
}

/** A simple workout profile: bar height = % FTP, colour = zone. Dashed line at FTP. */
@Composable
fun WorkoutProfile(workout: Workout, modifier: Modifier = Modifier, heightDp: Int = 110) {
    val segs = segments(workout.steps)
    val total = segs.sumOf { it.first }.toFloat()
    val maxPct = maxOf(125.0, segs.maxOf { maxOf(it.second, it.third) } + 5)
    val ftpLine = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(heightDp.dp)) {
        var x = 0f
        val pxPerSec = size.width / total
        fun y(pct: Double) = size.height - (pct / maxPct * size.height).toFloat()
        for ((dur, from, to) in segs) {
            val w = dur * pxPerSec
            val color = AppColors.zone(Zone.of((from + to) / 2))
            if (from == to) {
                drawRect(color, Offset(x, y(from)), Size(w, size.height - y(from)))
            } else {
                val p = Path().apply {
                    moveTo(x, size.height); lineTo(x, y(from)); lineTo(x + w, y(to)); lineTo(x + w, size.height); close()
                }
                drawPath(p, color)
            }
            x += w
        }
        val fy = y(100.0)
        var dx = 0f
        while (dx < size.width) {
            drawLine(ftpLine, Offset(dx, fy), Offset(minOf(dx + 8f, size.width), fy), strokeWidth = 2f)
            dx += 16f
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            FilterChip(selected = sel, onClick = { onClick(i) }, label = { Text(label) })
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).background(color, CircleShape))
