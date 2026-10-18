package com.offseason.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offseason.app.data.Rpe
import com.offseason.app.ui.FeelHelpScreen
import com.offseason.app.ui.FitHelpScreen
import com.offseason.app.ui.FtpTestScreen
import com.offseason.app.ui.Muted
import com.offseason.app.ui.OnboardingScreen
import com.offseason.app.ui.PlanOverviewScreen
import com.offseason.app.ui.ProgressScreen
import com.offseason.app.ui.SessionDetailScreen
import com.offseason.app.ui.SettingsScreen
import com.offseason.app.ui.ThisWeekScreen
import com.offseason.app.ui.TrainerHelpScreen
import com.offseason.app.ui.minutesLabel
import com.offseason.app.ui.theme.OffSeasonTheme
import com.offseason.engine.Plan
import com.offseason.engine.RideSummary
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleSharedFile(intent)
        setContent {
            OffSeasonTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedFile(intent)
    }

    /** A .fit file shared or opened from another app (email, WhatsApp, Files, Drive). */
    private fun handleSharedFile(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        readRideFrom(this, uri, vm).onSuccess(vm::setPendingRide)
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }
}

/** Reads a ride file from a content URI; failures carry a message suitable for the user. */
fun readRideFrom(context: Context, uri: Uri, vm: AppViewModel): Result<RideSummary> {
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return Result.failure(Exception("Couldn't open that file."))
    if (bytes.size > 50 * 1024 * 1024) return Result.failure(Exception("That file is too big to be a ride file."))
    return vm.readRide(bytes)
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("week", "This week", Icons.Default.Today),
    Tab("plan", "Plan", Icons.Default.CalendarMonth),
    Tab("progress", "Progress", Icons.AutoMirrored.Filled.TrendingUp),
    Tab("settings", "Settings", Icons.Default.Settings),
)

private fun titleFor(route: String?) = when {
    route == null -> ""
    route.startsWith("ftp") -> "FTP test"
    route.startsWith("help") -> "How-to"
    else -> "Session"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val pendingRide by vm.pendingRide.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today: LocalDate = vm.today()

    val currentPlan = plan
    if (data.profile == null || currentPlan == null) {
        OnboardingScreen(onFinish = { profile, ftp -> vm.completeOnboarding(profile, ftp, "entered") }, today = today)
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTab = tabs.any { it.route == route }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        val result = text?.let(vm::importJson)
        Toast.makeText(context, if (result?.isSuccess == true) "Data imported" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
    }

    // One file picker for ride files; what happens with the result depends on who asked.
    var onRidePicked by remember { mutableStateOf<((Result<RideSummary>) -> Unit)?>(null) }
    val rideLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = onRidePicked
        onRidePicked = null
        if (uri != null && cb != null) cb(readRideFrom(context, uri, vm))
    }
    fun pickRide(callback: (Result<RideSummary>) -> Unit) {
        onRidePicked = callback
        // .fit files rarely have a proper file type on Android, so allow any file and check it ourselves.
        rideLauncher.launch(arrayOf("*/*"))
    }

    // Which session has just been rated, to decide whether to offer easing off.
    var justRated by remember { mutableStateOf<String?>(null) }

    val status = vm.status(currentPlan, data, today)

    Scaffold(
        topBar = {
            if (!isTab) TopAppBar(
                title = { Text(titleFor(route)) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "week", modifier = Modifier.padding(padding)) {
            composable("week") {
                ThisWeekScreen(
                    currentPlan, data, today, status.message,
                    onOpenSession = { nav.navigate("session/$it") },
                    onDownloadWeek = { Sharing.shareWeek(context, it) },
                    onFtpTest = { nav.navigate("ftp") },
                )
            }
            composable("plan") { PlanOverviewScreen(currentPlan, data, today) { nav.navigate("session/$it") } }
            composable("progress") {
                ProgressScreen(currentPlan, data, today, status.message, status.series, vm.completionRate(currentPlan, data, today))
            }
            composable("settings") {
                SettingsScreen(
                    data,
                    onSaveProfile = { vm.updateProfile(it); Toast.makeText(context, "Plan updated", Toast.LENGTH_SHORT).show() },
                    onSaveFtp = { vm.recordFtp(it, "manual"); Toast.makeText(context, "FTP updated", Toast.LENGTH_SHORT).show() },
                    onExport = { Sharing.shareText(context, "OffSeason_${data.profile?.name}_$today.json", vm.exportJson()) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onReset = vm::resetPlan,
                    onHelp = { nav.navigate(it) },
                )
            }
            composable("help/trainer") { TrainerHelpScreen() }
            composable("help/feel") { FeelHelpScreen() }
            composable("help/fit") { FitHelpScreen() }
            composable("session/{id}") { entry ->
                val session = currentPlan.session(entry.arguments?.getString("id") ?: "") ?: return@composable
                SessionDetailScreen(
                    session, data,
                    onDownloadZwo = { Sharing.shareWorkout(context, it) },
                    onDownloadMrc = { Sharing.shareWorkoutMrc(context, it) },
                    onMarkDone = { vm.markDone(session.id, it); justRated = session.id },
                    onUndo = { vm.unmarkDone(session.id) },
                    onRetest = { nav.navigate("ftp?session=${session.id}") },
                    onAddRideFile = {
                        pickRide { r ->
                            r.onSuccess {
                                vm.attachRide(session.id, it)
                                Toast.makeText(context, "Ride added. How did it feel?", Toast.LENGTH_SHORT).show()
                            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    },
                    offerEasing = justRated == session.id && vm.shouldOfferEasing(currentPlan, data, session.id),
                    canRepeatWeek = vm.canRepeatWeekOf(currentPlan, session.id),
                    onLowerTargets = {
                        vm.lowerTargets(); justRated = null
                        Toast.makeText(context, "Targets lowered. Download upcoming sessions again for your trainer app.", Toast.LENGTH_LONG).show()
                    },
                    onRepeatWeek = {
                        vm.repeatWeekOf(session.id); justRated = null
                        Toast.makeText(context, "This week will repeat next week.", Toast.LENGTH_LONG).show()
                    },
                    onDeclineEasing = { vm.declineEasing(session.id); justRated = null },
                )
            }
            composable(
                "ftp?session={session}",
                arguments = listOf(navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val sessionId = entry.arguments?.getString("session")
                FtpTestScreen(
                    data,
                    isRetest = data.currentFtp != null,
                    onDownloadZwo = { Sharing.shareWorkout(context, it) },
                    onDownloadMrc = { Sharing.shareWorkoutMrc(context, it) },
                    onSave = vm::recordFtp,
                    retestSessionId = sessionId,
                    onMarkTestDone = { vm.markDone(it, Rpe.HARD) },
                    onPickTestRide = { callback ->
                        pickRide { r ->
                            callback(r.mapCatching { ride ->
                                sessionId?.let { id -> vm.attachRide(id, ride, Rpe.HARD) }
                                ride.best20MinPower ?: throw Exception("That ride is under 20 minutes, so there's no 20-minute effort in it.")
                            })
                        }
                    },
                )
            }
        }
    }

    // A ride file shared in from another app: ask which session it was.
    pendingRide?.let { ride ->
        AttachSharedRideDialog(
            ride, currentPlan, data.completions.keys,
            suggested = vm.suggestSession(currentPlan, ride)?.id,
            onAttach = { id ->
                vm.attachRide(id, ride)
                vm.setPendingRide(null)
                nav.navigate("session/$id")
                Toast.makeText(context, "Ride added. How did it feel?", Toast.LENGTH_SHORT).show()
            },
            onCancel = { vm.setPendingRide(null) },
        )
    }
}

@Composable
private fun AttachSharedRideDialog(
    ride: RideSummary,
    plan: Plan,
    doneIds: Set<String>,
    suggested: String?,
    onAttach: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val rideDate = ride.start.atZone(ZoneId.systemDefault()).toLocalDate()
    val nearby = plan.sessions.filter { !it.date.isBefore(rideDate.minusDays(3)) && !it.date.isAfter(rideDate.plusDays(3)) }
        .sortedWith(compareBy({ it.id != suggested }, { it.date }))
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Which session was this?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ride on ${rideDate.format(fmt)} · ${minutesLabel(ride.durationSec / 60)}" +
                        (ride.avgPower?.let { " · avg $it W" } ?: " · no power data"),
                )
                if (nearby.isEmpty()) Muted("No planned sessions within 3 days of this ride.")
                nearby.forEach { s ->
                    OutlinedButton(onClick = { onAttach(s.id) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                (if (s.id == suggested) "Best match · " else "") + s.date.format(fmt),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(s.workout.name + if (s.id in doneIds) " (already done)" else "", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
