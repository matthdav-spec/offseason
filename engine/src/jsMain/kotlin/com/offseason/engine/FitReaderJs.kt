package com.offseason.engine

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

@JsModule("@garmin/fitsdk")
@JsNonModule
private external object GarminFit {
    class Stream {
        companion object {
            fun fromByteArray(data: dynamic): Stream
        }
    }

    class Decoder(stream: Stream) {
        fun isFIT(): Boolean
        fun read(options: dynamic): dynamic
    }
}

@JsModule("fflate")
@JsNonModule
private external object Fflate {
    fun unzipSync(data: Uint8Array): dynamic
    fun zipSync(files: dynamic): Uint8Array
    fun strToU8(text: String): Uint8Array
}

private fun ByteArray.toUint8(): Uint8Array {
    val i8 = unsafeCast<Int8Array>()
    return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
}

private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it].toByte() }

/** Web app: reads .fit files with Garmin's JavaScript FIT SDK. */
actual object FitReader {
    actual fun read(bytes: ByteArray): RideData {
        val fit = if (RideSamples.isZip(bytes)) unzipFirstFit(bytes) else bytes
        val decoder = GarminFit.Decoder(GarminFit.Stream.fromByteArray(fit.toUint8()))
        if (!decoder.isFIT()) throw RideFileException("That isn't a .fit ride file.")
        val opts: dynamic = js("({})")
        opts.convertDateTimesToDates = true
        opts.includeUnknownData = false
        val result = decoder.read(opts)
        val records: dynamic = result.messages.recordMesgs
        val timestamps = mutableListOf<Long>()
        val powers = mutableListOf<Int>()
        if (records != null && records != undefined) {
            val n = records.length as Int
            for (i in 0 until n) {
                val r = records[i]
                val ts = r.timestamp ?: continue
                timestamps += ((ts.getTime() as Double) / 1000).toLong()
                val p = r.power
                powers += if (p == null || p == undefined) 0 else (p as Number).toInt()
            }
        }
        if (timestamps.isEmpty()) {
            val errors = result.errors
            if (errors != null && (errors.length as Int) > 0) throw RideFileException("The ride file looks damaged and couldn't be read.")
        }
        return RideSamples.build(timestamps, powers)
    }

    private fun unzipFirstFit(bytes: ByteArray): ByteArray {
        val files = try { Fflate.unzipSync(bytes.toUint8()) } catch (e: Throwable) {
            throw RideFileException("That zip file couldn't be opened.")
        }
        val names = js("Object.keys")(files).unsafeCast<Array<String>>()
        val name = names.firstOrNull { it.lowercase().endsWith(".fit") }
            ?: throw RideFileException("That zip file doesn't contain a .fit ride file.")
        return files[name].unsafeCast<Uint8Array>().toByteArray()
    }
}

actual fun zipFiles(files: List<Pair<String, String>>): ByteArray {
    val obj: dynamic = js("({})")
    for ((name, text) in files) obj[name] = Fflate.strToU8(text)
    return Fflate.zipSync(obj).toByteArray()
}
