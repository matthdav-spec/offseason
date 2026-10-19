package com.offseason.web

import com.offseason.engine.Adaptation
import com.offseason.engine.AthleteSchedule
import com.offseason.engine.HoursBucket
import com.offseason.engine.Load
import com.offseason.engine.Plan
import com.offseason.engine.PlanGenerator
import com.offseason.engine.PlanLength
import com.offseason.engine.PlanRequest
import com.offseason.engine.PlannedSession
import com.offseason.engine.RecoveryCycle
import com.offseason.engine.RideSummary
import com.offseason.engine.SessionKind
import com.offseason.engine.Status
import com.offseason.engine.WeekRepeats
import com.offseason.engine.daysTo
import com.offseason.engine.dayOfWeekFromIso
import com.offseason.engine.isAfter
import com.offseason.engine.isBefore
import com.offseason.engine.minusDays
import com.offseason.engine.plusDays
import kotlinx.browser.localStorage
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.Date
import kotlin.math.abs

// The data model (AppData, Profile, …) and app behaviour (AppLogic) live in the shared engine,
// so the Android and web apps always read and write the same format and behave the same way.
typealias AppData = com.offseason.engine.AppData
typealias Profile = com.offseason.engine.Profile
typealias Experience = com.offseason.engine.Experience
typealias Rpe = com.offseason.engine.Rpe
typealias RideStats = com.offseason.engine.RideStats

/** Saves everything in the browser's storage on this phone. Nothing is sent anywhere. */
object Store {
    private const val KEY = "offseason.v1"

    fun load(): AppData = runCatching { localStorage.getItem(KEY)?.let(::decode) }.getOrNull() ?: AppData()
    fun save(d: AppData) = localStorage.setItem(KEY, encode(d))
    fun encode(d: AppData): String = com.offseason.engine.DataJson.encode(d)
    fun decode(text: String): AppData = com.offseason.engine.DataJson.decode(text)
}

fun todayLocal(): LocalDate {
    val d = Date()
    return LocalDate(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** The phone's local date for a time in seconds since 1970. */
fun localDateOfEpochSec(sec: Long): LocalDate {
    val d = Date(sec * 1000.0)
    return LocalDate(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** Shared behaviour, under the name the web screens use. */
val Logic = com.offseason.engine.AppLogic
