package com.voxengine.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "LogManager"
    private const val LOG_DIR = "logs"
    private const val LOG_PREFIX = "voxengine_"
    private const val LOG_EXT = ".log"
    private const val MAX_DAYS = 7
    private const val MAX_LOG_LINE_LENGTH = 3000
    private const val BASE64_PREVIEW_CHARS = 16

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lineRegex = Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3}) ([A-Z])/([^:]+): (.*)$""")
    private val dataAudioRegex = Regex("""data:audio/[\w.+-]+;base64,[A-Za-z0-9+/=]+""")
    private val base64BlobRegex = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{180,}={0,2}(?![A-Za-z0-9+/])""")

    data class LogEntry(
        val timestamp: Long,
        val date: String,
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
        val raw: String
    )

    data class LogFilter(
        val date: String? = null,
        val startMinute: Int = 0,
        val endMinute: Int = 24 * 60 - 1,
        val level: String = "全部",
        val keyword: String = ""
    )

    fun init(context: Context) {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        val cutoff = System.currentTimeMillis() - MAX_DAYS * 24 * 60 * 60 * 1000L
        logDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(LOG_EXT)) {
                val name = file.nameWithoutExtension.removePrefix(LOG_PREFIX)
                try {
                    val fileDate = dateFormat.parse(name)
                    if (fileDate != null && fileDate.time < cutoff) {
                        file.delete()
                        Log.d(TAG, "Deleted old log: ${file.name}")
                    }
                } catch (_: Exception) {}
            }
        }

        val today = dateFormat.format(Date())
        logFile = File(logDir, "$LOG_PREFIX$today$LOG_EXT")
    }

    @Synchronized
    fun appendLog(level: String, tag: String, message: String) {
        val file = logFile ?: return
        val timestamp = timeFormat.format(Date())
        val safeMessage = sanitizeMessage(message)
        val line = "$timestamp $level/$tag: $safeMessage\n"
        try {
            file.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}")
        }
    }

    fun getAvailableDates(context: Context): List<String> {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) return emptyList()
        return logDir.listFiles()
            ?.filter { it.name.startsWith(LOG_PREFIX) && it.name.endsWith(LOG_EXT) }
            ?.map { it.nameWithoutExtension.removePrefix(LOG_PREFIX) }
            ?.sortedDescending()
            .orEmpty()
    }

    fun readEntries(context: Context, filter: LogFilter = LogFilter()): List<LogEntry> {
        val dates = filter.date?.let(::listOf) ?: getAvailableDates(context)
        val keyword = filter.keyword.trim()
        return dates.asSequence()
            .flatMap { date -> readEntriesForDate(context, date).asSequence() }
            .filter { entry ->
                val minute = entry.time.take(5).toMinutesOrNull() ?: entry.minuteOfDay()
                minute.matchesRange(filter.startMinute, filter.endMinute) &&
                    (filter.level == "全部" || entry.level == filter.level) &&
                    (keyword.isBlank() || entry.raw.contains(keyword, ignoreCase = true))
            }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    fun formatEntries(entries: List<LogEntry>): String = entries.joinToString("\n") { it.raw }

    fun readAllLogs(context: Context): String {
        return formatEntries(readEntries(context).sortedBy { it.timestamp })
    }

    fun clearLogs(context: Context) {
        val logDir = File(context.filesDir, LOG_DIR)
        logDir.listFiles()?.forEach { it.delete() }
        logFile = null
        init(context)
    }

    fun exportLog(context: Context): File? = exportEntries(context, readEntries(context), "voxengine_full_log.txt")

    fun exportEntries(context: Context, entries: List<LogEntry>, fileName: String = "voxengine_filtered_log.txt"): File? {
        if (entries.isEmpty()) return null
        val exportFile = File(context.cacheDir, fileName)
        exportFile.writeText(formatEntries(entries.sortedBy { it.timestamp }))
        return exportFile
    }

    private fun readEntriesForDate(context: Context, date: String): List<LogEntry> {
        val file = File(File(context.filesDir, LOG_DIR), "$LOG_PREFIX$date$LOG_EXT")
        if (!file.exists()) return emptyList()
        return runCatching {
            file.useLines { lines -> lines.mapNotNull { line -> parseLine(date, line) }.toList() }
        }.getOrDefault(emptyList())
    }

    private fun parseLine(date: String, line: String): LogEntry? {
        val match = lineRegex.matchEntire(line) ?: run {
            val safeLine = sanitizeMessage(line)
            return LogEntry(
                timestamp = dateFormat.parse(date)?.time ?: 0L,
                date = date,
                time = "00:00:00.000",
                level = "I",
                tag = "Unknown",
                message = safeLine,
                raw = safeLine
            )
        }
        val safeMessage = sanitizeMessage(match.groupValues[9])
        val year = date.substringBefore('-').toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        val hour = match.groupValues[3].toInt()
        val minute = match.groupValues[4].toInt()
        val second = match.groupValues[5].toInt()
        val millis = match.groupValues[6].toInt()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }
        return LogEntry(
            timestamp = calendar.timeInMillis,
            date = date,
            time = "%02d:%02d:%02d.%03d".format(hour, minute, second, millis),
            level = match.groupValues[7],
            tag = match.groupValues[8],
            message = safeMessage,
            raw = "${match.groupValues[1]}-${match.groupValues[2]} ${match.groupValues[3]}:${match.groupValues[4]}:${match.groupValues[5]}.${match.groupValues[6]} ${match.groupValues[7]}/${match.groupValues[8]}: $safeMessage"
        )
    }

    private fun sanitizeMessage(message: String): String {
        val escaped = message
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val redactedAudio = dataAudioRegex.replace(escaped) { match ->
            val value = match.value
            val prefix = value.substringBefore(",")
            val payloadLength = value.length - prefix.length - 1
            val preview = value.substringAfter(",").take(BASE64_PREVIEW_CHARS)
            "$prefix,$preview...(base64 redacted, ${payloadLength} chars)"
        }
        val redactedBlobs = base64BlobRegex.replace(redactedAudio) { match ->
            "${match.value.take(BASE64_PREVIEW_CHARS)}...(base64 redacted, ${match.value.length} chars)"
        }
        return if (redactedBlobs.length > MAX_LOG_LINE_LENGTH) {
            redactedBlobs.take(MAX_LOG_LINE_LENGTH) + "...(truncated)"
        } else {
            redactedBlobs
        }
    }

    private fun LogEntry.minuteOfDay(): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun Int.matchesRange(start: Int, end: Int): Boolean =
        if (start <= end) this in start..end else this >= start || this <= end

    private fun String.toMinutesOrNull(): Int? {
        val parts = split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour * 60 + minute
    }
}
