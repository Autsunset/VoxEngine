package com.voxengine.ui.screens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxengine.audio.AudioUtils
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")
    val apiKey by settings.apiKey.collectAsState(initial = "")
    val baseUrl by settings.baseUrl.collectAsState(initial = "https://api.xiaomimimo.com")
    val activeEngine = remember(currentEngineId) { EngineRegistry.get(currentEngineId) }

    val voices = remember(activeEngine) {
        kotlinx.coroutines.runBlocking { activeEngine?.getVoices() ?: emptyList() }
    }
    val styles = remember(activeEngine) {
        kotlinx.coroutines.runBlocking { activeEngine?.getStyles() ?: emptyList() }
    }

    var text by remember { mutableStateOf("你好，欢迎使用 VoxEngine 语音合成引擎！") }
    var selectedVoice by remember { mutableStateOf("冰糖") }
    var selectedStyle by remember { mutableStateOf("无") }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var styleExpanded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    // 检查 API Key 是否配置
    val isConfigured = apiKey.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TopAppBar(title = { Text("TTS 测试 - ${activeEngine?.name ?: currentEngineId}") })

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
                        "⚠️ API Key 未配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请先在设置页面配置 API Key，否则无法合成语音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "✅ API Key 已配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "API: $baseUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
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
            ExposedDropdownMenuBox(
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedVoice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("音色") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.name) },
                            onClick = { selectedVoice = voice.name; voiceExpanded = false }
                        )
                    }
                }
            }

            if (styles.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = styleExpanded,
                    onExpandedChange = { styleExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedStyle,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("风格") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = styleExpanded, onDismissRequest = { styleExpanded = false }) {
                        styles.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(style) },
                                onClick = { selectedStyle = style; styleExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("语速: ${String.format("%.1f", speed)}x")
        Slider(
            value = speed,
            onValueChange = { speed = it },
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    isSynthesizing = true
                    statusText = "合成中..."
                    elapsedMs = 0
                    try {
                        val engine = activeEngine ?: throw IllegalStateException("未选择引擎")
                        val result = withContext(Dispatchers.IO) {
                            engine.synthesize(text, selectedVoice, selectedStyle, speed)
                        }
                        elapsedMs = result.elapsedMs
                        statusText = "合成完成 (${elapsedMs}ms)，播放中..."

                        withContext(Dispatchers.IO) {
                            playAudio(result.audioData)
                        }
                        statusText = "播放完成"
                    } catch (e: Exception) {
                        statusText = "错误: ${e.message}"
                    } finally {
                        isSynthesizing = false
                    }
                }
            },
            enabled = !isSynthesizing && text.isNotBlank() && isConfigured,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSynthesizing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (isSynthesizing) "合成中..." else "合成并播放")
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

        Spacer(Modifier.height(8.dp))
        Text(
            "提示: 请先在设置页面配置 API Key，然后选择音色和风格进行测试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private suspend fun playAudio(wavData: ByteArray) = withContext(Dispatchers.IO) {
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
    track.play()
    while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        Thread.sleep(50)
    }
    track.release()
}
