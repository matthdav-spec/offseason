package com.offseason.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private data class Tab(val route: Route, val label: String, val icon: String)

private val tabs = listOf(
    Tab(Route.Week, "This week", "📅"),
    Tab(Route.PlanTab, "Plan", "🗓"),
    Tab(Route.Progress, "Progress", "📈"),
    Tab(Route.Settings, "Settings", "⚙"),
)

@Composable
fun App() {
    if (isPreview) Div({ classes("preview") }) { Text("DBAP preview: running plan (test version, separate from your pilot plan)") }
    val data = AppState.data
    val plan = AppState.plan
    if (data.profile == null || plan == null) {
        Onboarding()
        return
    }
    val today = AppState.today()
    val route = AppState.route
    val summary = Logic.status(plan, data, today)

    when (route) {
        Route.Week -> ThisWeekScreen(plan, data, today, summary.message)
        Route.PlanTab -> PlanScreen(plan, data, today)
        Route.Progress -> ProgressScreen(plan, data, today, summary)
        Route.Settings -> SettingsScreen(data)
        is Route.Session -> SessionScreen(plan, data, route.id)
        is Route.Run -> RunScreen(plan, data, route.id)
        is Route.LthrTest -> LthrTestScreen(data, route.runId)
        is Route.Swim -> SwimScreen(plan, data, route.id)
        is Route.CssTest -> CssTestScreen(data, route.swimId)
        is Route.FtpTest -> FtpTestScreen(data, route.sessionId)
        Route.Install -> InstallGuide()
        is Route.Help -> when (route.topic) {
            "feel" -> FeelHelp()
            "fit" -> FitHelp()
            "run" -> RunHelp(data.currentLthr)
            "swim" -> SwimHelp(data.currentCss)
            else -> TrainerHelp()
        }
    }

    if (tabs.any { it.route == route }) Nav({ classes("tabs") }) {
        tabs.forEach { t ->
            Button({ if (t.route == route) classes("on"); onClick { AppState.go(t.route) } }) {
                Span({ classes("icon") }) { Text(t.icon) }
                Span { Text(t.label) }
            }
        }
    }

    AppState.toast?.let { Div({ classes("toast") }) { Text(it) } }
}
