package com.offseason.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offseason.app.ui.FtpTestScreen
import com.offseason.app.ui.HelpScreen
import com.offseason.app.ui.OnboardingScreen
import com.offseason.app.ui.PlanOverviewScreen
import com.offseason.app.ui.ProgressScreen
import com.offseason.app.ui.SessionDetailScreen
import com.offseason.app.ui.SettingsScreen
import com.offseason.app.ui.ThisWeekScreen
import com.offseason.app.ui.theme.OffSeasonTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OffSeasonTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("week", "This week", Icons.Default.Today),
    Tab("plan", "Plan", Icons.Default.CalendarMonth),
    Tab("progress", "Progress", Icons.AutoMirrored.Filled.TrendingUp),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
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

    val status = vm.status(currentPlan, data, today)

    Scaffold(
        topBar = {
            if (!isTab) TopAppBar(
                title = { Text(if (route?.startsWith("ftp") == true) "FTP test" else if (route == "help") "How-to" else "Session") },
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
                    onHelp = { nav.navigate("help") },
                )
            }
            composable("help") { HelpScreen() }
            composable("session/{id}") { entry ->
                val session = currentPlan.session(entry.arguments?.getString("id") ?: "") ?: return@composable
                SessionDetailScreen(
                    session, data,
                    onDownload = { Sharing.shareWorkout(context, it) },
                    onMarkDone = { vm.markDone(session.id, it) },
                    onUndo = { vm.unmarkDone(session.id) },
                    onRetest = { nav.navigate("ftp?session=${session.id}") },
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
                    onDownload = { Sharing.shareWorkout(context, it) },
                    onSave = vm::recordFtp,
                    retestSessionId = sessionId,
                    onMarkTestDone = { vm.markDone(it, com.offseason.app.data.Rpe.HARD) },
                )
            }
        }
    }
}
