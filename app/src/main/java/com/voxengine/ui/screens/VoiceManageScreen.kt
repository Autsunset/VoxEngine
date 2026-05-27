package com.voxengine.ui.screens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.voxengine.data.VoiceEntity
import com.voxengine.engine.EngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceManageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val voices by db.voiceDao().getAllVoices().collectAsState(initial = emptyList())
    val settings = remember { com.voxengine.data.SettingsRepository(context) }
    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")

    val activeEngine = EngineRegistry.get(currentEngineId)
    val presetVoices = remember(activeEngine) {
        kotlinx.coroutines.runBlocking { activeEngine?.getVoices() ?: emptyList() }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showDesignDialog by remember { mutableStateOf(false) }
    var previewingVoice by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val supportsClone = activeEngine?.supportsVoiceClone ?: false
    val supportsDesign = activeEngine?.supportsVoiceDesign ?: false

    Scaffold(
        topBar = { TopAppBar(title = { Text("音色管理 - ${activeEngine?.name ?: currentEngineId}") }) },
        floatingActionButton = {
            if (supportsClone || supportsDesign) {
                Column {
                    if (supportsDesign) {
                        FloatingActionButton(
                            onClick = { showDesignDialog = true },
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) { Icon(Icons.Default.Add, "设计音色") }
                    }
                    if (supportsClone) {
                        FloatingActionButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, "克隆音色")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("预设音色", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(presetVoices) { voice ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                            Text(voice.description, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    previewingVoice = voice.name
                                    isPlaying = true
                                    try {
                                        val engine = EngineRegistry.getActive(currentEngineId)
                                        val result = withContext(Dispatchers.IO) {
                                            engine.synthesize("你好，我是${voice.name}，这是试听。", voice.name)
                                        }
                                        playAudio(result.audioData)
                                    } catch (e: Exception) {
                                        // ignore
                                    } finally {
                                        isPlaying = false
                                        previewingVoice = null
                                    }
                                }
                            },
                            enabled = !isPlaying
                        ) {
                            if (previewingVoice == voice.name && isPlaying) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.PlayArrow, "试听")
                            }
                        }
                    }
                }
            }

            if (voices.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("自定义音色", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                items(voices) { voice ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (voice.type == "clone") "克隆音色" else "设计: ${voice.description}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        previewingVoice = voice.name
                                        isPlaying = true
                                        try {
                                            val engine = EngineRegistry.getActive(currentEngineId)
                                            val result = withContext(Dispatchers.IO) {
                                                engine.synthesize("你好，这是试听。", voice.name)
                                            }
                                            playAudio(result.audioData)
                                        } catch (e: Exception) {
                                            // ignore
                                        } finally {
                                            isPlaying = false
                                            previewingVoice = null
                                        }
                                    }
                                },
                                enabled = !isPlaying
                            ) {
                                if (previewingVoice == voice.name && isPlaying) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    Icon(Icons.Default.PlayArrow, "试听")
                                }
                            }
                            IconButton(onClick = {
                                scope.launch { db.voiceDao().delete(voice) }
                            }) {
                                Icon(Icons.Default.Delete, "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CloneVoiceDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, description, audioBase64 ->
                scope.launch {
                    db.voiceDao().insert(
                        VoiceEntity(
                            name = name,
                            type = "clone",
                            model = "clone",
                            voiceParam = "data:audio/mpeg;base64,$audioBase64",
                            description = description,
                            audioBase64 = audioBase64,
                            engineId = currentEngineId
                        )
                    )
                    showAddDialog = false
                }
            }
        )
    }

    if (showDesignDialog) {
        DesignVoiceDialog(
            onDismiss = { showDesignDialog = false },
            onSave = { name, description ->
                scope.launch {
                    db.voiceDao().insert(
                        VoiceEntity(
                            name = name,
                            type = "design",
                            model = "design",
                            voiceParam = description,
                            description = description,
                            engineId = currentEngineId
                        )
                    )
                    showDesignDialog = false
                }
            }
        )
    }
}

@Composable
fun CloneVoiceDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var audioBase64 by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            audioUri = it
            val inputStream = context.contentResolver.openInputStream(it)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            audioBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加克隆音色") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { launcher.launch("audio/*") }) {
                    Text(if (audioUri != null) "已选择音频" else "选择参考音频")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, audioBase64) }, enabled = name.isNotBlank() && audioBase64.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun DesignVoiceDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设计新音色") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("音色描述") },
                    placeholder = { Text("例如：一个温柔的年轻女性声音") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank() && description.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
