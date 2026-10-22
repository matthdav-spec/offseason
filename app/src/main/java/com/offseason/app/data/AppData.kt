package com.offseason.app.data

import android.content.Context
import com.offseason.engine.AppData
import com.offseason.engine.DataJson
import java.io.File

/*
 * The data model (AppData, Profile, …) lives in the shared engine (engine/.../AppData.kt),
 * so the Android and web apps always read and write the same format.
 */
typealias AppData = com.offseason.engine.AppData
typealias Profile = com.offseason.engine.Profile
typealias Experience = com.offseason.engine.Experience
typealias FtpEntry = com.offseason.engine.FtpEntry
typealias Rpe = com.offseason.engine.Rpe
typealias Completion = com.offseason.engine.Completion
typealias RideStats = com.offseason.engine.RideStats
typealias TargetAdjustment = com.offseason.engine.TargetAdjustment

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
        fun encode(data: AppData): String = DataJson.encode(data)
        fun decode(text: String): AppData = DataJson.decode(text)
    }
}
