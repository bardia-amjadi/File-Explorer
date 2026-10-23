package com.bardia.fileexplorer.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
    }

    fun date(millis: Long): String =
        if (millis <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun shortDate(millis: Long): String =
        if (millis <= 0) "—" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun duration(ms: Long): String {
        if (ms < 0) return "—"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
