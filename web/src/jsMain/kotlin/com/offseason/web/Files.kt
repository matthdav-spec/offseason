package com.offseason.web

import com.offseason.engine.FitReader
import com.offseason.engine.MrcExporter
import com.offseason.engine.PlanWeek
import com.offseason.engine.RideAnalysis
import com.offseason.engine.RideFileException
import com.offseason.engine.RideSummary
import com.offseason.engine.Workout
import com.offseason.engine.ZwoExporter
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader

/**
 * Getting files out of and into the web app.
 *
 * On iPhone, the best route is the Share sheet (navigator.share): it offers Save to Files, Mail,
 * AirDrop, WhatsApp, etc. Where that isn't available (most desktop browsers), we fall back to
 * an ordinary download.
 */
object Files {
    fun shareZwo(w: Workout) = shareFile(ZwoExporter.fileName(w), ZwoExporter.toZwo(w).encodeToByteArray(), "application/xml")

    fun shareMrc(w: Workout) =
        shareFile(MrcExporter.fileName(ZwoExporter.fileName(w)), MrcExporter.toMrc(w).encodeToByteArray(), "text/plain")

    fun shareWeek(week: PlanWeek) = shareFile(ZwoExporter.weekBundleName(week), ZwoExporter.weekBundle(week), "application/zip")

    fun shareJson(name: String, text: String) = shareFile(name, text.encodeToByteArray(), "application/json")

    private fun ByteArray.toBlobPart(): Uint8Array {
        val i8 = unsafeCast<Int8Array>()
        return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
    }

    fun shareFile(name: String, bytes: ByteArray, mime: String) {
        val options = BlobPropertyBag(type = mime)
        val file = File(arrayOf(bytes.toBlobPart()), name, js("({type: mime})").unsafeCast<org.w3c.files.FilePropertyBag>())
        val nav: dynamic = window.navigator
        val shareData: dynamic = js("({})")
        shareData.files = arrayOf(file)
        val canShareFiles = nav.canShare != undefined && nav.share != undefined &&
            (nav.canShare(shareData) as? Boolean ?: false)
        if (canShareFiles) {
            shareData.title = name
            (nav.share(shareData) as kotlin.js.Promise<Any?>).catch { e ->
                // The user closing the share sheet is fine; anything else, fall back to a download.
                if ((e.asDynamic().name as? String) != "AbortError") download(name, Blob(arrayOf(bytes.toBlobPart()), options))
            }
        } else {
            download(name, Blob(arrayOf(bytes.toBlobPart()), options))
        }
    }

    private fun download(name: String, blob: Blob) {
        val url = js("URL").createObjectURL(blob) as String
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = url
        a.download = name
        document.body?.appendChild(a)
        a.click()
        a.remove()
        window.setTimeout({ js("URL").revokeObjectURL(url) }, 10_000)
    }

    /** Opens the phone's file picker; [onPicked] gets the file's bytes. */
    fun pickFile(accept: String, onPicked: (ByteArray) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        // iPhone's picker greys out files whose type it doesn't recognise, and .fit isn't one
        // it knows, so we don't restrict the type there.
        if (accept.isNotEmpty()) input.accept = accept
        input.onchange = {
            val f = input.files?.item(0)
            if (f != null) {
                val reader = FileReader()
                reader.onload = {
                    val buf = reader.result.unsafeCast<ArrayBuffer>()
                    onPicked(Int8Array(buf).unsafeCast<ByteArray>())
                }
                reader.readAsArrayBuffer(f)
            }
        }
        input.click()
    }

    /** Picks a .fit (or Garmin .zip) and reads it. [onResult] gets the ride or a friendly error. */
    fun pickRide(onResult: (Result<RideSummary>) -> Unit) = pickFile("") { bytes ->
        onResult(
            runCatching {
                if (bytes.size > 50 * 1024 * 1024) throw RideFileException("That file is too big to be a ride file.")
                try {
                    RideAnalysis.summarize(FitReader.read(bytes))
                } catch (e: RideFileException) {
                    throw e
                } catch (e: Throwable) {
                    throw RideFileException("That file couldn't be read as a ride. Is it a .fit file?")
                }
            },
        )
    }
}
