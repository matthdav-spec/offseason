package com.offseason.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.offseason.engine.PlanWeek
import com.offseason.engine.Workout
import com.offseason.engine.ZwoExporter
import java.io.File

/**
 * Sends workout files out of the app through Android's share sheet: email to yourself,
 * save to Google Drive/Files, send over WhatsApp, etc. From there you copy them into
 * Zwift / MyWhoosh / TrainingPeaks Virtual's workouts folder.
 */
object Sharing {
    fun shareWorkout(context: Context, workout: Workout) =
        shareFile(context, ZwoExporter.fileName(workout), ZwoExporter.toZwo(workout).toByteArray(), "application/xml")

    fun shareWeek(context: Context, week: PlanWeek) =
        shareFile(context, ZwoExporter.weekBundleName(week), ZwoExporter.weekBundle(week), "application/zip")

    fun shareText(context: Context, fileName: String, text: String) =
        shareFile(context, fileName, text.toByteArray(), "application/json")

    private fun shareFile(context: Context, name: String, bytes: ByteArray, mime: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Save or send $name"))
    }
}
