package com.voxengine.ui.screens.reader

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voxengine.reader.ReaderSleepTimer
import kotlinx.coroutines.delay

@Composable
internal fun rememberSleepTimeRemaining(deadlineElapsedRealtime: Long): String {
    var now by remember(deadlineElapsedRealtime) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadlineElapsedRealtime) {
        while (deadlineElapsedRealtime > now) {
            delay(1_000L)
            now = SystemClock.elapsedRealtime()
        }
    }
    return ReaderSleepTimer.formatRemaining(ReaderSleepTimer.remainingMillis(deadlineElapsedRealtime, now))
}

@Composable
internal fun ReaderSleepTimerDialog(
    minutes: Int,
    deadlineElapsedRealtime: Long,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf(minutes.takeIf { it > 0 }?.toString() ?: "30") }
    val selectedMinutes = input.toIntOrNull()
    val valid = selectedMinutes != null && selectedMinutes in 1..ReaderSleepTimer.MAX_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Timer, contentDescription = null) },
        title = { Text("定时关闭") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (deadlineElapsedRealtime > 0L) {
                    Text(
                        "剩余 ${rememberSleepTimeRemaining(deadlineElapsedRealtime)}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                listOf(15, 30, 45, 60, 90, 120).chunked(3).forEach { presets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = selectedMinutes == preset,
                                onClick = { input = preset.toString() },
                                label = { Text("${preset}分钟") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.all { it in '0'..'9' }) input = value
                    },
                    label = { Text("自定义分钟") },
                    supportingText = { Text("可设置 1–${ReaderSleepTimer.MAX_MINUTES} 分钟") },
                    isError = input.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "到时停止朗读，不退出应用。暂停时继续计时，换章不重置；播放中应用新时长会重新计时。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "时长会保存，并用于下次听书。仅对内置听书生效，不影响预缓存或其他阅读应用。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { selectedMinutes?.let(onApply) }, enabled = valid) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(0) }) { Text("关闭定时") }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        }
    )
}
