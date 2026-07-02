package com.voxengine.ui.screens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.data.SettingsRepository
import com.voxengine.data.SynthesisHistoryEntity
import com.voxengine.engine.EngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }

    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")
    val apiKey by settings.apiKey.collectAsState(initial = "")
    val baseUrl by settings.baseUrl.collectAsState(initial = "https://api.xiaomimimo.com")
    val speed by settings.speed.collectAsState(initial = 1.0f)
    val activeEngine = remember(currentEngineId) { EngineRegistry.get(currentEngineId) }

    val voices by produceState(initialValue = emptyList<com.voxengine.engine.VoiceInfo>(), activeEngine) {
        value = activeEngine?.getVoices() ?: emptyList()
    }
    val styles by produceState(initialValue = emptyList<String>(), activeEngine) {
        value = activeEngine?.getStyles() ?: emptyList()
    }
    
    // 合成历史
    val history by db.synthesisHistoryDao().getRecent(10).collectAsState(initial = emptyList())

    var text by remember { mutableStateOf("你好，欢迎使用 VoxEngine 语音合成引擎！") }
    var selectedVoiceId by remember { mutableStateOf("冰糖") }
    var selectedVoiceName by remember { mutableStateOf("冰糖") }
    var selectedStyle by remember { mutableStateOf("") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var styleExpanded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    // 持有当前 AudioTrack 引用，用于暂停/停止
    var currentTrack by remember { mutableStateOf<AudioTrack?>(null) }
    // 播放协程 Job
    var playJob by remember { mutableStateOf<Job?>(null) }

    val isConfigured = activeEngine?.isConfigured() ?: false

    // 离开页面时停止播放
    DisposableEffect(Unit) {
        onDispose {
            playJob?.cancel()
            currentTrack?.let { track ->
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
            currentTrack = null
        }
    }

    // 停止播放的函数
    val stopPlayback = {
        playJob?.cancel()
        playJob = null
        currentTrack?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
        currentTrack = null
        isPlaying = false
        isPaused = false
        isSynthesizing = false
        statusText = "已停止"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TTS 测试 - ${activeEngine?.name ?: currentEngineId}") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {

        // API Key 状态提示
        if (!isConfigured) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚠️ 当前引擎未配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请先在设置页面完成当前引擎配置，否则无法合成语音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("输入文本") },
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 音色选择
            ExposedDropdownMenuBox(
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedVoiceName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("音色") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text("${voice.name} - ${voice.description}") },
                            onClick = {
                                selectedVoiceId = voice.id
                                selectedVoiceName = voice.name
                                voiceExpanded = false
                            }
                        )
                    }
                }
            }

            // 风格 - 自由输入框
            OutlinedTextField(
                value = selectedStyle,
                onValueChange = { selectedStyle = it },
                label = { Text("风格（可自定义）") },
                placeholder = { Text("如：温柔磁性、东北话") },
                modifier = Modifier.weight(1f)
            )
        }

        // 方言快捷标签
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("四川话", "粤语").forEach { dialect ->
                OutlinedButton(onClick = { selectedStyle = dialect }) {
                    Text(dialect, style = MaterialTheme.typography.bodySmall)
                }
            }
            // 清除风格按钮
            if (selectedStyle.isNotBlank()) {
                TextButton(onClick = { selectedStyle = "" }) {
                    Text("清除", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 当前配置摘要
        val currentStyle = selectedStyle.ifBlank { "无" }
        Text(
            "当前: $selectedVoiceName | 风格: $currentStyle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // 播放控制按钮
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        isSynthesizing = true
                        statusText = "合成中..."
                        elapsedMs = 0
                        try {
                            val engine = activeEngine ?: throw IllegalStateException("未选择引擎")
                            val styleParam = selectedStyle.ifBlank { null }?.takeIf { it != "无" }
                            val result = withContext(Dispatchers.IO) {
                                engine.synthesize(text, selectedVoiceId, styleParam)
                            }
                            elapsedMs = result.elapsedMs
                            isSynthesizing = false
                            statusText = "合成完成 (${elapsedMs}ms)，播放中..."
                            isPlaying = true
                            isPaused = false

                            db.synthesisHistoryDao().insert(
                                SynthesisHistoryEntity(
                                    text = text,
                                    voice = selectedVoiceId,
                                    style = selectedStyle.ifBlank { "无" },
                                    speed = speed,
                                    engineId = currentEngineId
                                )
                            )

                            playJob = scope.launch(Dispatchers.IO) {
                                playAudioWithControl(result.audioData, speed) { track ->
                                    currentTrack = track
                                }
                            }
                            playJob?.join()
                            if (isPlaying) statusText = "播放完成"
                        } catch (e: Exception) {
                            statusText = "错误: ${com.voxengine.util.TtsErrors.friendly(e)}"
                        } finally {
                            currentTrack = null
                            isSynthesizing = false
                            isPlaying = false
                            isPaused = false
                        }
                    }
                },
                enabled = !isSynthesizing && !isPlaying && text.isNotBlank() && isConfigured,
                modifier = Modifier.weight(1f)
            ) {
                if (isSynthesizing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (isSynthesizing) "合成中..." else "合成并播放")
            }

            // 暂停/继续按钮
            if (isPlaying) {
                OutlinedButton(
                    onClick = {
                        currentTrack?.let { track ->
                            if (isPaused) {
                                track.play()
                                isPaused = false
                                statusText = "播放中..."
                            } else {
                                track.pause()
                                isPaused = true
                                statusText = "已暂停"
                            }
                        }
                    }
                ) {
                    Text(if (isPaused) "继续" else "暂停")
                }
            }

            // 停止按钮
            if (isPlaying || isSynthesizing) {
                OutlinedButton(
                    onClick = { stopPlayback() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("停止")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (statusText.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("状态: $statusText")
                    if (elapsedMs > 0) {
                        Text("耗时: ${elapsedMs}ms")
                    }
                }
            }
        }

        // 合成历史
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最近记录", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    scope.launch { db.synthesisHistoryDao().deleteAll() }
                }) {
                    Text("清除全部")
                }
            }
            Spacer(Modifier.height(8.dp))
            
            history.forEach { record ->
                val voiceDisplayName = voices.find { it.id == record.voice }?.name ?: record.voice
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        text = record.text
                        selectedVoiceId = record.voice
                        selectedVoiceName = voiceDisplayName
                        selectedStyle = record.style?.takeIf { it != "无" } ?: ""
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                record.text.take(50) + if (record.text.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "$voiceDisplayName | ${record.style ?: "无"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { db.synthesisHistoryDao().deleteById(record.id) }
                        }) {
                            Icon(Icons.Default.Delete, "删除", modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * 播放音频，通过回调暴露 AudioTrack 引用以便外部暂停/停止
 */
private suspend fun playAudioWithControl(
    wavData: ByteArray,
    speed: Float = 1.0f,
    onTrackReady: (AudioTrack) -> Unit
) = withContext(Dispatchers.IO) {
    val sampleRate = AudioUtils.getWavSampleRate(wavData)
    val channelCount = AudioUtils.getWavChannelCount(wavData)
    val bitsPerSample = AudioUtils.getWavBitsPerSample(wavData)
    val pcmData = AudioUtils.extractPcmData(wavData)

    val channelConfig = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val encoding = if (bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

    val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()
        )
        .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    track.write(pcmData, 0, pcmData.size)
    if (speed > 0f && kotlin.math.abs(speed - 1.0f) > 0.01f) {
        runCatching { track.playbackParams = track.playbackParams.setSpeed(speed) }
    }
    track.play()
    onTrackReady(track) // 暴露 track 引用
    while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        delay(50)
    }
    track.release()
}
