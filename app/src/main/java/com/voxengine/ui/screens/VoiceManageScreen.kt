package com.voxengine.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.data.VoiceEntity
import com.voxengine.engine.mimo.MiMoTTSClient
import com.voxengine.engine.EngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceManageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val settings = remember { com.voxengine.data.SettingsRepository(context) }
    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")

    val activeEngine = EngineRegistry.get(currentEngineId)
    val voices by db.voiceDao().getVoiceItemsByEngine(currentEngineId).collectAsState(initial = emptyList())
    val presetVoices by produceState(initialValue = emptyList(), activeEngine) {
        value = activeEngine?.getVoices() ?: emptyList()
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showDesignDialog by remember { mutableStateOf(false) }
    var editingVoice by remember { mutableStateOf<com.voxengine.data.VoiceListItem?>(null) }
    var previewingVoice by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val supportsClone = activeEngine?.supportsVoiceClone ?: false
    val supportsDesign = activeEngine?.supportsVoiceDesign ?: false

    // 导出音色配置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val allVoices = db.voiceDao().getAllVoices().first()
                val json = Gson().toJson(allVoices)
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(json.toByteArray())
                }
                Toast.makeText(context, "已导出 ${allVoices.size} 个音色", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 导入音色配置
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
                    val type = object : TypeToken<List<VoiceEntity>>() {}.type
                    val voices: List<VoiceEntity> = Gson().fromJson(json, type)
                    var count = 0
                    for (voice in voices) {
                        val existing = db.voiceDao().getVoiceByEngineAndName(voice.engineId, voice.name)
                        if (existing == null) {
                            db.voiceDao().insert(voice.copy(id = 0))
                            count++
                        }
                    }
                    Toast.makeText(context, "导入完成，新增 $count 个音色", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("音色管理 - ${activeEngine?.name ?: currentEngineId}") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 导入导出
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("voxengine_voices.json") },
                        modifier = Modifier.weight(1f)
                    ) { Text("导出音色", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("导入音色", style = MaterialTheme.typography.bodySmall) }
                }
            }

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
                            VoiceMetaLine(gender = voice.gender, ageGroup = voice.ageGroup, tags = voice.tags)
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
                // 按性别分组：男声 / 女声 / 中性 / 未分类，便于按角色挑音色。
                val grouped = voices.groupBy { it.gender ?: "unspecified" }
                val sectionOrder = listOf(
                    com.voxengine.engine.VoiceGender.MALE to "男声",
                    com.voxengine.engine.VoiceGender.FEMALE to "女声",
                    com.voxengine.engine.VoiceGender.NEUTRAL to "中性",
                    "unspecified" to "未分类"
                )
                sectionOrder.forEach { (key, label) ->
                    val group = grouped[key]
                    if (!group.isNullOrEmpty()) {
                        item {
                            Text(
                                "$label（${group.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                        items(group) { voice ->
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
                                        VoiceMetaLine(
                                            gender = voice.gender,
                                            ageGroup = voice.ageGroup,
                                            tags = com.voxengine.engine.VoiceTags.parse(voice.tags)
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
                                    IconButton(onClick = { editingVoice = voice }) {
                                        Icon(Icons.Default.Edit, "编辑标签")
                                    }
                                    IconButton(onClick = {
                                        scope.launch { db.voiceDao().deleteById(voice.id) }
                                    }) {
                                        Icon(Icons.Default.Delete, "删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 添加音色按钮（在列表最下方）
            if (supportsClone || supportsDesign) {
                item {
                    Spacer(Modifier.height(16.dp))
                    if (supportsDesign) {
                        OutlinedButton(
                            onClick = { showDesignDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("设计新音色（文字描述生成）")
                        }
                    }
                    if (supportsClone) {
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("克隆音色（音频录制/上传）")
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
                            model = MiMoTTSClient.MODEL_CLONE,
                            voiceParam = "data:audio/wav;base64,$audioBase64",
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
                            model = MiMoTTSClient.MODEL_DESIGN,
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

    editingVoice?.let { voice ->
        EditVoiceMetaDialog(
            voice = voice,
            onDismiss = { editingVoice = null },
            onSave = { gender, ageGroup, tags ->
                scope.launch {
                    val entity = db.voiceDao().getVoiceById(voice.id)
                    if (entity != null) {
                        db.voiceDao().update(entity.copy(gender = gender, ageGroup = ageGroup, tags = tags))
                    }
                    editingVoice = null
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
    var isRecording by remember { mutableStateOf(false) }
    var recordedSeconds by remember { mutableLongStateOf(0L) }
    var cloneVoiceHint by remember { mutableStateOf("") }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            recorder?.let {
                try { it.stop(); it.release() } catch (_: Exception) {}
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            audioUri = it
            val inputStream = context.contentResolver.openInputStream(it)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            audioBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // permission granted, user can tap record again
        } else {
            Toast.makeText(context, "需要录音权限才能录制音频", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val sampleRate = 24000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize
        )
        rec.startRecording()
        recorder = rec
        isRecording = true
        recordedSeconds = 0L
        cloneVoiceHint = ""

        val pcmBuffer = java.io.ByteArrayOutputStream()
        val readBuffer = ByteArray(bufferSize)
        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        recordingThread = Thread {
            while (isRecording) {
                val read = rec.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    pcmBuffer.write(readBuffer, 0, read)
                }
                handler.post {
                    if (isRecording) {
                        recordedSeconds = (System.currentTimeMillis() - startTime) / 1000
                        cloneVoiceHint = if (recordedSeconds > 10) {
                            "参考音频已经超过10秒。如果后续克隆失败，请缩短到10秒以内再试。"
                        } else {
                            ""
                        }
                    }
                }
            }
            rec.stop()
            rec.release()

            val pcmData = pcmBuffer.toByteArray()
            pcmBuffer.close()
            val wavData = encodeWav(pcmData, sampleRate, 1, 16)
            val b64 = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)

            handler.post {
                audioBase64 = b64
                audioUri = null
                isRecording = false
                recorder = null
                recordedSeconds = (System.currentTimeMillis() - startTime) / 1000
                cloneVoiceHint = if (recordedSeconds > 10) {
                    "参考音频已经超过10秒。如果后续克隆失败，请缩短到10秒以内再试。"
                } else {
                    ""
                }
            }
        }.also { it.start() }
    }

    fun stopRecording() {
        isRecording = false
        // recorder 的 stop/release 由录音线程处理
        recordingThread?.join(5000)
        recordingThread = null
    }

    AlertDialog(
        onDismissRequest = {
            if (isRecording) stopRecording()
            onDismiss()
        },
        title = { Text("添加克隆音色") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { launcher.launch("audio/*") }) {
                        Text(if (audioUri != null) "已选择文件" else "选择音频文件")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (isRecording) stopRecording() else startRecording()
                        }
                    ) {
                        if (isRecording) {
                            Icon(Icons.Default.Stop, "停止", tint = Color.Red, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("停止 (${recordedSeconds}s)", color = Color.Red)
                        } else {
                            Icon(Icons.Default.Mic, "录音", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("录音")
                        }
                    }
                }

                if (audioBase64.isNotBlank()) {
                    Text(
                        if (audioUri != null) "已选择音频文件" else if (!isRecording && recordedSeconds > 0) "已录制 ${recordedSeconds}秒" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (cloneVoiceHint.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cloneVoiceHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：不会自动截断录音；建议录制10秒以内的清晰人声。若克隆失败，请缩短录音时间后重试。音频仅支持 WAV/MP3 格式，Base64 编码后不超过 10MB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (recordedSeconds > 10) {
                        Toast.makeText(context, "参考音频超过10秒，若克隆失败请缩短后重试", Toast.LENGTH_LONG).show()
                    }
                    onSave(name, description, audioBase64)
                },
                enabled = name.isNotBlank() && audioBase64.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = { if (isRecording) stopRecording(); onDismiss() }) { Text("取消") } }
    )
}

@Composable
fun DesignVoiceDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPreviewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = remember { com.voxengine.data.SettingsRepository(context) }
    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")

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
                    placeholder = { Text("例如：一个温柔的年轻女性声音，语速缓慢") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            isPreviewing = true
                            try {
                                val engine = EngineRegistry.getActive(currentEngineId)
                                val result = withContext(Dispatchers.IO) {
                                    engine.synthesize(
                                        text = "",
                                        voice = description,
                                        optimizeTextPreview = true
                                    )
                                }
                                playAudio(result.audioData)
                            } catch (_: Exception) {
                            } finally {
                                isPreviewing = false
                            }
                        }
                    },
                    enabled = description.isNotBlank() && !isPreviewing
                ) {
                    if (isPreviewing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("试听音色")
                    }
                }
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
        delay(50)
    }
    track.release()
}

private fun encodeWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcmData.size
    val totalSize = 44 + dataSize

    val header = ByteArray(44)
    // RIFF header
    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
    header[4] = (totalSize and 0xFF).toByte(); header[5] = (totalSize shr 8 and 0xFF).toByte()
    header[6] = (totalSize shr 16 and 0xFF).toByte(); header[7] = (totalSize shr 24 and 0xFF).toByte()
    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
    // fmt chunk
    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
    header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // chunk size
    header[20] = 1; header[21] = 0 // PCM format
    header[22] = channels.toByte(); header[23] = 0
    header[24] = (sampleRate and 0xFF).toByte(); header[25] = (sampleRate shr 8 and 0xFF).toByte()
    header[26] = (sampleRate shr 16 and 0xFF).toByte(); header[27] = (sampleRate shr 24 and 0xFF).toByte()
    header[28] = (byteRate and 0xFF).toByte(); header[29] = (byteRate shr 8 and 0xFF).toByte()
    header[30] = (byteRate shr 16 and 0xFF).toByte(); header[31] = (byteRate shr 24 and 0xFF).toByte()
    header[32] = blockAlign.toByte(); header[33] = 0
    header[34] = bitsPerSample.toByte(); header[35] = 0
    // data chunk
    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
    header[40] = (dataSize and 0xFF).toByte(); header[41] = (dataSize shr 8 and 0xFF).toByte()
    header[42] = (dataSize shr 16 and 0xFF).toByte(); header[43] = (dataSize shr 24 and 0xFF).toByte()

    return header + pcmData
}

/** 一行展示音色的性别 / 年龄段 / 自定义标签，无值则不占行。 */
@Composable
private fun VoiceMetaLine(
    gender: String?,
    ageGroup: String?,
    tags: List<String>
) {
    val parts = mutableListOf<String>()
    gender?.let { parts += com.voxengine.engine.VoiceGender.labelOf(it) }
    ageGroup?.let { parts += com.voxengine.engine.VoiceAgeGroup.labelOf(it) }
    parts += tags
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 编辑自定义音色的性别 / 年龄段 / 标签。预设音色不入库，不经过此对话框。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditVoiceMetaDialog(
    voice: com.voxengine.data.VoiceListItem,
    onDismiss: () -> Unit,
    onSave: (gender: String?, ageGroup: String?, tags: String) -> Unit
) {
    // null 表示"未分类/未设置"，与库里的 null 对齐（存 null 而非空串）。
    var gender by remember { mutableStateOf(voice.gender) }
    var ageGroup by remember { mutableStateOf(voice.ageGroup) }
    var tagsText by remember { mutableStateOf(voice.tags ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑音色信息") },
        text = {
            Column {
                Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("性别", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    FilterChip(selected = gender == null, onClick = { gender = null }, label = { Text("未分类") })
                    com.voxengine.engine.VoiceGender.ALL.forEach { g ->
                        FilterChip(
                            selected = gender == g,
                            onClick = { gender = if (gender == g) null else g },
                            label = { Text(com.voxengine.engine.VoiceGender.labelOf(g)) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("年龄段", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    FilterChip(selected = ageGroup == null, onClick = { ageGroup = null }, label = { Text("未设置") })
                    com.voxengine.engine.VoiceAgeGroup.ALL.forEach { a ->
                        FilterChip(
                            selected = ageGroup == a,
                            onClick = { ageGroup = if (ageGroup == a) null else a },
                            label = { Text(com.voxengine.engine.VoiceAgeGroup.labelOf(a)) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("自定义标签") },
                    placeholder = { Text("用逗号分隔，如：旁白,温柔") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(gender, ageGroup, com.voxengine.engine.VoiceTags.parse(tagsText).let { com.voxengine.engine.VoiceTags.join(it) })
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
