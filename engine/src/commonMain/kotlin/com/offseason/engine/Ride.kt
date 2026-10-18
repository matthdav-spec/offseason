package com.offseason.engine

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A completed ride: one power reading per second of riding (pauses removed).
 * [startEpochSec] is the ride's start time in seconds since 1970 (UTC).
 */
class RideData(val startEpochSec: Long, val power: IntArray) {
    val durationSec: Int get() = power.size
    val hasPower: Boolean get() = power.any { it > 0 }
}

/** The numbers we keep from a ride file. Watts are rounded. */
data class RideSummary(
    val startEpochSec: Long,
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

/**
 * Reads .fit ride files (or a .zip containing one, as Garmin Connect exports).
 * Android uses Garmin's Java FIT SDK; the web app uses Garmin's JavaScript FIT SDK.
 * Throws [RideFileException] with a plain-English message if the file can't be read.
 */
expect object FitReader {
    fun read(bytes: ByteArray): RideData
}

/** Shared helpers for both FIT readers. */
object RideSamples {
    /** Gaps longer than this (seconds) are treated as a pause, not riding. */
    const val MAX_GAP_SEC = 5

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

    fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** Builds [RideData] from raw record timestamps (seconds since 1970) and powers. */
    fun build(timestamps: List<Long>, powers: List<Int>): RideData {
        if (timestamps.isEmpty()) throw RideFileException("The file has no ride data in it.")
        return RideData(timestamps.first(), toPerSecond(timestamps, powers.map { it.coerceIn(0, 3000) }))
    }
}

object RideAnalysis {
    fun summarize(ride: RideData): RideSummary {
        if (!ride.hasPower) return RideSummary(ride.startEpochSec, ride.durationSec, null, null, null)
        val p = ride.power
        return RideSummary(
            startEpochSec = ride.startEpochSec,
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
