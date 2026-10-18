package com.offseason.app.data

import android.content.Context
import com.offseason.engine.AthleteSchedule
import com.offseason.engine.HoursBucket
import com.offseason.engine.PlanLength
import com.offseason.engine.PlanRequest
import com.offseason.engine.RecoveryCycle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Everything the app stores, in one JSON file on the device. No accounts, no server.
 * The plan itself isn't stored: it's regenerated from [Profile] by the engine, so it
 * always matches the current settings.
 */
@Serializable
data class AppData(
    val version: Int = 1,
    val profile: Profile? = null,
    val ftpHistory: List<FtpEntry> = emptyList(),
    /** Keyed by PlannedSession.id. */
    val completions: Map<String, Completion> = emptyMap(),
    /** Target changes the athlete accepted, e.g. "lower by 4%" from a date. Cleared at each retest. */
    val targetAdjustments: List<TargetAdjustment> = emptyList(),
    /** Original week indexes the athlete chose to repeat, in order. */
    val repeatedWeeks: List<Int> = emptyList(),
    /** Session ids where the athlete said "no thanks" to easing, so we don't keep asking. */
    val easingDeclinedAt: List<String> = emptyList(),
) {
    val currentFtp: Int? get() = ftpHistory.maxByOrNull { it.date }?.ftp
    val startingFtp: Int? get() = ftpHistory.minByOrNull { it.date }?.ftp
}

@Serializable
data class Profile(
    val name: String,
    val experience: Experience,
    val hoursPerWeek: Int,
    val bikeDays: List<Int>,
    val swimDays: List<Int>,
    val runDays: List<Int>,
    val longRunDay: Int? = null,
    val hardRunDays: List<Int> = emptyList(),
    val planWeeks: Int,
    /** ISO date, e.g. "2026-10-26". */
    val startDate: String,
    val recoveryCycle: String = RecoveryCycle.THREE_ONE.name,
    val weightKg: Double? = null,
    val showWattsPerKg: Boolean = false,
    val disclaimerAcceptedOn: String,
    val readiness: Map<String, Boolean> = emptyMap(),
) {
    fun toPlanRequest() = PlanRequest(
        startDate = LocalDate.parse(startDate),
        length = PlanLength.ofWeeks(planWeeks),
        hours = HoursBucket.of(hoursPerWeek),
        recoveryCycle = RecoveryCycle.valueOf(recoveryCycle),
        schedule = AthleteSchedule(
            bikeDays = bikeDays.map(DayOfWeek::of).toSet(),
            swimDays = swimDays.map(DayOfWeek::of).toSet(),
            runDays = runDays.map(DayOfWeek::of).toSet(),
            longRunDay = longRunDay?.let(DayOfWeek::of),
            hardRunDays = hardRunDays.map(DayOfWeek::of).toSet(),
        ),
    )
}

@Serializable
enum class Experience { NEWER, EXPERIENCED }

@Serializable
data class FtpEntry(val date: String, val ftp: Int, val source: String)

@Serializable
enum class Rpe(val label: String) {
    EASY("Easy"), ABOUT_RIGHT("About right"), HARD("Hard"), COULDNT_FINISH("Couldn't finish")
}

@Serializable
data class Completion(
    val date: String,
    val rpe: Rpe,
    /** Present if the athlete added a .fit ride file. */
    val ride: RideStats? = null,
)

/** What we keep from a .fit file. The file itself isn't stored. */
@Serializable
data class RideStats(
    val durationSec: Int,
    val avgPower: Int? = null,
    val weightedPower: Int? = null,
    val best20MinPower: Int? = null,
    /** FTP at the time, so the load stays correct if FTP changes later. */
    val ftpAtRide: Int? = null,
    val sessionLoad: Double? = null,
)

@Serializable
data class TargetAdjustment(
    /** ISO date the change applies from. */
    val fromDate: String,
    /** e.g. -4 for "4% lower". */
    val pct: Int,
)

/** Reads and writes [AppData] as a JSON file in the app's private storage. */
class Store(context: Context) {
    private val file = File(context.filesDir, "offseason.json")

    fun load(): AppData = runCatching { decode(file.readText()) }.getOrDefault(AppData())

    fun save(data: AppData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(data))
        tmp.renameTo(file) || run { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)
        fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text)
    }
}
