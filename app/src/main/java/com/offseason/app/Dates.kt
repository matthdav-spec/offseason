package com.offseason.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The engine uses kotlinx-datetime so it can also run in the iPhone web app.
 * These helpers bridge to Android's own date formatting.
 */

fun todayLocal(): LocalDate = java.time.LocalDate.now().toKotlinLocalDate()

fun LocalDate.format(formatter: DateTimeFormatter): String = toJavaLocalDate().format(formatter)

fun LocalDate.format(pattern: String): String = format(DateTimeFormatter.ofPattern(pattern, Locale.UK))

/** The phone's local date for a time in seconds since 1970. */
fun localDateOfEpochSec(sec: Long): LocalDate =
    Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()
