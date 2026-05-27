package com.voxengine.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private const val TAG = "LogManager"
    private const val LOG_DIR = "logs"
    private const val LOG_PREFIX = "voxengine_"
    private const val LOG_EXT = ".log"
    private const val MAX_DAYS = 7

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        // 删除7天前的日志
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

        // 获取今天的日志文件
        val today = dateFormat.format(Date())
        logFile = File(logDir, "$LOG_PREFIX$today$LOG_EXT")
    }

    fun appendLog(level: String, tag: String, message: String) {
        val file = logFile ?: return
        val timestamp = timeFormat.format(Date())
        val line = "$timestamp $level/$tag: $message\n"
        try {
            file.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}")
        }
    }

    fun readAllLogs(context: Context): String {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) return ""

        val logs = mutableListOf<Pair<String, String>>()
        logDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(LOG_EXT)) {
                val name = file.nameWithoutExtension.removePrefix(LOG_PREFIX)
                try {
                    val content = file.readText()
                    logs.add(Pair(name, content))
                } catch (_: Exception) {}
            }
        }

        // 按日期排序
        logs.sortBy { it.first }

        return logs.joinToString("\n") { it.second }
    }

    fun clearLogs(context: Context) {
        val logDir = File(context.filesDir, LOG_DIR)
        logDir.listFiles()?.forEach { it.delete() }
        logFile = null
        init(context)
    }

    fun exportLog(context: Context): File? {
        val content = readAllLogs(context)
        if (content.isEmpty()) return null
        val exportFile = File(context.cacheDir, "voxengine_full_log.txt")
        exportFile.writeText(content)
        return exportFile
    }
}
