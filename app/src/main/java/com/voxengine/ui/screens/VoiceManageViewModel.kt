package com.voxengine.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxengine.data.AppDatabase
import com.voxengine.data.SettingsRepository
import com.voxengine.data.VoiceEntity
import com.voxengine.data.VoiceListItem
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.VoiceInfo
import com.voxengine.engine.mimo.MiMoTTSClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VoiceManageScreen 的状态层：把音色列表加载、预览合成/播放、增删改副作用从 composable 移出。
 * 导入/导出涉及 ActivityResult launcher + ContentResolver + Toast，仍由 composable 驱动；
 * 录音（AudioRecord）生命周期紧耦合对话框状态，也留在 CloneVoiceDialog 内。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VoiceManageViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsRepository(app)
    private val db = AppDatabase.getDatabase(app)

    val currentEngineId: StateFlow<String> =
        settings.currentEngine.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "mimo")

    val voices: StateFlow<List<VoiceListItem>> = settings.currentEngine
        .distinctUntilChanged()
        .flatMapLatest { engineId -> db.voiceDao().getVoiceItemsByEngine(engineId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presetVoices: StateFlow<List<VoiceInfo>> = currentEngineId
        .map { EngineRegistry.get(it)?.getVoices() ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val supportsClone: StateFlow<Boolean> = currentEngineId
        .map { EngineRegistry.get(it)?.supportsVoiceClone ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val supportsDesign: StateFlow<Boolean> = currentEngineId
        .map { EngineRegistry.get(it)?.supportsVoiceDesign ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _previewingVoice = MutableStateFlow<String?>(null)
    val previewingVoice: StateFlow<String?> = _previewingVoice.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun previewVoice(voiceName: String, text: String) {
        if (_isPlaying.value) return
        viewModelScope.launch {
            _previewingVoice.value = voiceName
            _isPlaying.value = true
            try {
                val engine = EngineRegistry.getActive(currentEngineId.value)
                val result = withContext(Dispatchers.IO) { engine.synthesize(text, voiceName) }
                playAudio(result.audioData)
            } catch (_: Exception) {
                // 预览失败静默，避免打断列表浏览
            } finally {
                _isPlaying.value = false
                _previewingVoice.value = null
            }
        }
    }

    fun saveCloneVoice(name: String, description: String, audioBase64: String) {
        viewModelScope.launch {
            db.voiceDao().insert(
                VoiceEntity(
                    name = name,
                    type = "clone",
                    model = MiMoTTSClient.MODEL_CLONE,
                    voiceParam = "data:audio/wav;base64,$audioBase64",
                    description = description,
                    audioBase64 = audioBase64,
                    engineId = currentEngineId.value
                )
            )
        }
    }

    fun saveDesignVoice(name: String, description: String) {
        viewModelScope.launch {
            db.voiceDao().insert(
                VoiceEntity(
                    name = name,
                    type = "design",
                    model = MiMoTTSClient.MODEL_DESIGN,
                    voiceParam = description,
                    description = description,
                    engineId = currentEngineId.value
                )
            )
        }
    }

    fun saveVoiceMeta(voice: VoiceListItem, gender: String?, ageGroup: String?, tags: String) {
        viewModelScope.launch {
            val entity = db.voiceDao().getVoiceById(voice.id) ?: return@launch
            db.voiceDao().update(entity.copy(gender = gender, ageGroup = ageGroup, tags = tags))
        }
    }

    fun deleteVoice(id: Long) {
        viewModelScope.launch { db.voiceDao().deleteById(id) }
    }

    /** 导出全部音色（供 composable 序列化后写入 launcher 返回的 Uri）。 */
    suspend fun voicesForExport(): List<VoiceEntity> =
        db.voiceDao().getAllVoices().first()

    /** 导入音色列表：跳过已存在（同 engineId+name），返回新增数量。 */
    suspend fun importVoices(voices: List<VoiceEntity>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (voice in voices) {
            val existing = db.voiceDao().getVoiceByEngineAndName(voice.engineId, voice.name)
            if (existing == null) {
                db.voiceDao().insert(voice.copy(id = 0))
                count++
            }
        }
        count
    }

    private suspend fun playAudio(wavData: ByteArray) = withContext(Dispatchers.IO) {
        val sampleRate = com.voxengine.audio.AudioUtils.getWavSampleRate(wavData)
        val channelCount = com.voxengine.audio.AudioUtils.getWavChannelCount(wavData)
        val bitsPerSample = com.voxengine.audio.AudioUtils.getWavBitsPerSample(wavData)
        val pcmData = com.voxengine.audio.AudioUtils.extractPcmData(wavData)

        val channelConfig = if (channelCount == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO
        else android.media.AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (bitsPerSample == 16) android.media.AudioFormat.ENCODING_PCM_16BIT
        else android.media.AudioFormat.ENCODING_PCM_8BIT

        val bufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        track.play()
        while (track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
            kotlinx.coroutines.delay(50)
        }
        track.release()
    }
}
