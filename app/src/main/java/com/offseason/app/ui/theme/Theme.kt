package com.offseason.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.Zone

/** A dark, wintry palette: deep navy with icy blues. */
private val Colors = darkColorScheme(
    primary = Color(0xFF8CC8F0),
    onPrimary = Color(0xFF06243A),
    primaryContainer = Color(0xFF1D4361),
    onPrimaryContainer = Color(0xFFD3ECFF),
    secondary = Color(0xFFB7C9D9),
    background = Color(0xFF0E1A24),
    onBackground = Color(0xFFE3EAF0),
    surface = Color(0xFF0E1A24),
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF1B2A37),
    onSurfaceVariant = Color(0xFFA6B6C4),
    surfaceContainer = Color(0xFF15232F),
    surfaceContainerHigh = Color(0xFF1B2A37),
    outline = Color(0xFF3A4D5E),
)

@Composable
fun OffSeasonTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

object AppColors {
    val greyedOut = Color(0xFF5E6E7C)
    val swim = Color(0xFF4F7A8C)
    val run = Color(0xFF7A6F5A)
    val done = Color(0xFF6FCF97)

    fun light(l: Status.Light) = when (l) {
        Status.Light.GREEN -> Color(0xFF6FCF97)
        Status.Light.AMBER -> Color(0xFFF2C14E)
        Status.Light.RED -> Color(0xFFEB6F6F)
    }

    fun zone(z: Zone) = when (z) {
        Zone.Z1 -> Color(0xFF8A9BA8)
        Zone.Z2 -> Color(0xFF4A90D9)
        Zone.Z3 -> Color(0xFF5CC08A)
        Zone.SS -> Color(0xFFE6C54A)
        Zone.Z4 -> Color(0xFFF09A3E)
        Zone.Z5 -> Color(0xFFE5584F)
        Zone.Z6, Zone.Z7 -> Color(0xFFB84FD1)
    }

    fun kind(k: SessionKind) = when (k) {
        SessionKind.INTERVALS -> Color(0xFFF09A3E)
        SessionKind.FTP_TEST -> Color(0xFFE5584F)
        SessionKind.TEMPO -> Color(0xFF5CC08A)
        SessionKind.ENDURANCE -> Color(0xFF4A90D9)
        SessionKind.CADENCE -> Color(0xFF7FB3D5)
        SessionKind.EASY, SessionKind.RECOVERY -> Color(0xFF8A9BA8)
    }
}
