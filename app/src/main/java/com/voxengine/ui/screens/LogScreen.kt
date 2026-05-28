package com.voxengine.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.voxengine.util.LogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    val context = LocalContext.current
    var dates by remember { mutableStateOf(emptyList<String>()) }
    var selectedDate by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("全部") }
    var startTime by remember { mutableStateOf("00:00") }
    var endTime by remember { mutableStateOf("23:59") }
    var keyword by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<LogManager.LogEntry>()) }
    var dateExpanded by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }

    fun refresh() {
        dates = LogManager.getAvailableDates(context)
        if (selectedDate.isBlank() && dates.isNotEmpty()) selectedDate = dates.first()
        entries = LogManager.readEntries(
            context,
            LogManager.LogFilter(
                date = selectedDate.ifBlank { null },
                startMinute = startTime.toMinutesOrDefault(0),
                endMinute = endTime.toMinutesOrDefault(23 * 60 + 59),
                level = level,
                keyword = keyword
            )
        )
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("日志查询") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ExposedDropdownMenuBox(
                            expanded = dateExpanded,
                            onExpandedChange = { dateExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedDate.ifBlank { "全部日期" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("日期") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dateExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(expanded = dateExpanded, onDismissRequest = { dateExpanded = false }) {
                                DropdownMenuItem(text = { Text("全部日期") }, onClick = { selectedDate = ""; dateExpanded = false; refresh() })
                                dates.forEach { date ->
                                    DropdownMenuItem(text = { Text(date) }, onClick = { selectedDate = date; dateExpanded = false; refresh() })
                                }
                            }
                        }
                        ExposedDropdownMenuBox(
                            expanded = levelExpanded,
                            onExpandedChange = { levelExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = level,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("级别") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(levelExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(expanded = levelExpanded, onDismissRequest = { levelExpanded = false }) {
                                listOf("全部", "E", "W", "I", "D").forEach { value ->
                                    DropdownMenuItem(text = { Text(value) }, onClick = { level = value; levelExpanded = false; refresh() })
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = startTime,
                            onValueChange = { startTime = it.take(5) },
                            label = { Text("开始 HH:mm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = { endTime = it.take(5) },
                            label = { Text("结束 HH:mm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("关键词") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { refresh() }) { Text("查询") }
                        OutlinedButton(onClick = {
                            val text = LogManager.formatEntries(entries)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("VoxEngine Log", text))
                            Toast.makeText(context, "已复制 ${entries.size} 条", Toast.LENGTH_SHORT).show()
                        }) { Text("复制结果") }
                        OutlinedButton(onClick = {
                            try {
                                val file = LogManager.exportEntries(context, entries)
                                if (file == null) {
                                    Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
                                } else {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "导出日志"))
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("导出结果") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            LogManager.clearLogs(context)
                            selectedDate = ""
                            refresh()
                            Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                        }) { Text("清空日志") }
                        Text(
                            "${entries.size} 条结果，日志保留7天",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(entries, key = { it.timestamp.toString() + it.raw }) { entry ->
                    val color = when (entry.level) {
                        "E" -> MaterialTheme.colorScheme.error
                        "W" -> MaterialTheme.colorScheme.tertiary
                        "D" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "${entry.date} ${entry.time} ${entry.level}/${entry.tag}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun String.toMinutesOrDefault(defaultValue: Int): Int {
    val parts = split(':')
    if (parts.size != 2) return defaultValue
    val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return defaultValue
    val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return defaultValue
    return hour * 60 + minute
}
