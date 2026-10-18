package com.offseason.engine

import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Android: reads .fit files with Garmin's Java FIT SDK. */
actual object FitReader {
    actual fun read(bytes: ByteArray): RideData {
        val fit = if (RideSamples.isZip(bytes)) unzipFirstFit(bytes) else bytes
        val timestamps = mutableListOf<Long>()
        val powers = mutableListOf<Int>()
        try {
            if (!Decode().isFileFit(ByteArrayInputStream(fit))) throw RideFileException("That isn't a .fit ride file.")
            // A fresh decoder: isFileFit() leaves the one it used part-way through the file.
            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            broadcaster.addListener(RecordMesgListener { r ->
                val t = r.timestamp?.date?.time?.div(1000) ?: return@RecordMesgListener
                timestamps += t
                powers += r.power ?: 0
            })
            decode.read(ByteArrayInputStream(fit), broadcaster, broadcaster)
        } catch (e: FitRuntimeException) {
            if (timestamps.isEmpty()) throw RideFileException("The ride file looks damaged and couldn't be read.")
            // Otherwise keep what we read: files cut short at the end are common and still useful.
        }
        return RideSamples.build(timestamps, powers)
    }

    private fun unzipFirstFit(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fit")) return zip.readBytes()
            }
        }
        throw RideFileException("That zip file doesn't contain a .fit ride file.")
    }
}

actual fun zipFiles(files: List<Pair<String, String>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, text) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
