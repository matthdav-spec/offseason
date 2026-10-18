package com.offseason.engine

import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/** A completed ride: one power reading per second of riding (pauses removed). */
class RideData(val start: Instant, val power: IntArray) {
    val durationSec: Int get() = power.size
    val hasPower: Boolean get() = power.any { it > 0 }
}

/** The numbers we keep from a ride file. Watts are rounded. */
data class RideSummary(
    val start: Instant,
    val durationSec: Int,
    val avgPower: Int?,
    /** 4th-power weighted average of 30-second rolling power: how hard the ride "felt" to the body. */
    val weightedPower: Int?,
    /** Best 20-minute average power, for FTP tests. Null if the ride was under 20 minutes. */
    val best20MinPower: Int?,
) {
    /** Effort Score = weighted power ÷ FTP. */
    fun effortScore(ftp: Int): Double? = weightedPower?.let { it.toDouble() / ftp }

    fun sessionLoad(ftp: Int): Double? = effortScore(ftp)?.let { Load.sessionLoad(durationSec, it) }
}

class RideFileException(message: String) : Exception(message)

object FitReader {
    /** Gaps longer than this (seconds) are treated as a pause, not riding. */
    const val MAX_GAP_SEC = 5

    /**
     * Reads a .fit activity file, or a .zip containing one (Garmin Connect's "Export Original"
     * gives a zip). Throws [RideFileException] with a plain-English message if it can't.
     */
    fun read(bytes: ByteArray): RideData {
        val fit = if (isZip(bytes)) unzipFirstFit(bytes) else bytes
        val timestamps = mutableListOf<Long>()
        val powers = mutableListOf<Int>()
        try {
            if (!Decode().isFileFit(ByteArrayInputStream(fit))) throw RideFileException("That isn't a .fit ride file.")
            // A fresh decoder: isFileFit() leaves the one it used part-way through the file.
            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            broadcaster.addListener(RecordMesgListener { r ->
                val t = r.timestamp?.date?.toInstant()?.epochSecond ?: return@RecordMesgListener
                timestamps += t
                powers += (r.power ?: 0).coerceIn(0, 3000)
            })
            decode.read(ByteArrayInputStream(fit), broadcaster, broadcaster)
        } catch (e: FitRuntimeException) {
            if (timestamps.isEmpty()) throw RideFileException("The ride file looks damaged and couldn't be read. (${e.message})")
            // Otherwise keep what we read: files cut short at the end are common and still useful.
        }
        if (timestamps.isEmpty()) throw RideFileException("The file has no ride data in it.")
        return RideData(Instant.ofEpochSecond(timestamps.first()), toPerSecond(timestamps, powers))
    }

    /** One value per second; short dropouts are filled, longer gaps (pauses) are skipped. */
    fun toPerSecond(timestamps: List<Long>, powers: List<Int>): IntArray {
        val out = ArrayList<Int>(timestamps.size)
        for (i in timestamps.indices) {
            val gap = if (i + 1 < timestamps.size) timestamps[i + 1] - timestamps[i] else 1L
            val fill = if (gap in 1..MAX_GAP_SEC.toLong()) gap.toInt() else if (gap <= 0) 0 else 1
            repeat(fill) { out += powers[i] }
        }
        return out.toIntArray()
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun unzipFirstFit(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fit")) return zip.readBytes()
            }
        }
        throw RideFileException("That zip file doesn't contain a .fit ride file.")
    }
}

object RideAnalysis {
    fun summarize(ride: RideData): RideSummary {
        if (!ride.hasPower) return RideSummary(ride.start, ride.durationSec, null, null, null)
        val p = ride.power
        return RideSummary(
            start = ride.start,
            durationSec = p.size,
            avgPower = p.average().roundToInt(),
            weightedPower = weightedPower(p).roundToInt(),
            best20MinPower = bestAverage(p, 20 * 60)?.roundToInt(),
        )
    }

    /** 30-second rolling average, raised to the 4th power, averaged, then 4th root. */
    fun weightedPower(p: IntArray): Double {
        if (p.size < 30) return p.average()
        var window = 0.0
        var sum4 = 0.0
        var n = 0
        for (i in p.indices) {
            window += p[i]
            if (i >= 30) window -= p[i - 30]
            if (i >= 29) { sum4 += (window / 30).pow(4); n++ }
        }
        return (sum4 / n).pow(0.25)
    }

    /** Highest average power over any [seconds]-long stretch, or null if the ride is shorter. */
    fun bestAverage(p: IntArray, seconds: Int): Double? {
        if (p.size < seconds) return null
        var sum = 0L
        for (i in 0 until seconds) sum += p[i]
        var best = sum
        for (i in seconds until p.size) {
            sum += p[i] - p[i - seconds]
            if (sum > best) best = sum
        }
        return best.toDouble() / seconds
    }
}
