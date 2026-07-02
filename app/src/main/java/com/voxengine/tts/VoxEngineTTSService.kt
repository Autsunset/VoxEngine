package com.voxengine.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.voxengine.audio.AudioUtils
import com.voxengine.audio.SpeedAdjuster
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineBootstrap
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.mimo.MiMoEngine
import com.voxengine.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class VoxEngineTTSService : TextToSpeechService() {

    private var settings: SettingsRepository? = null
    @Volatile private var currentEngine = "mimo"
    @Volatile private var currentVoice = "冰糖"
    @Volatile private var currentStyle = "无"
    @Volatile private var currentSpeed = 1.0f
    @Volatile private var parallelSynthesis = false
    @Volatile private var ttsConcurrency = 3
    @Volatile private var stopRequested = false
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        try {
            settings = SettingsRepository(applicationContext)
            val s = settings!!

            runBlocking {
                currentEngine = s.currentEngine.first()
                currentVoice = s.defaultVoice.first()
                currentStyle = s.defaultStyle.first()
                currentSpeed = s.speed.first()
                parallelSynthesis = s.parallelSynthesis.first()
                ttsConcurrency = s.ttsConcurrency.first()
            }

            EngineBootstrap.ensureRegistered(s)

            serviceScope.launch { s.currentEngine.collect { currentEngine = it } }
            serviceScope.launch { s.defaultVoice.collect { currentVoice = it } }
            serviceScope.launch { s.defaultStyle.collect { currentStyle = it } }
            serviceScope.launch { s.speed.collect { currentSpeed = it } }
            serviceScope.launch { s.parallelSynthesis.collect { parallelSynthesis = it } }
            serviceScope.launch { s.ttsConcurrency.collect { ttsConcurrency = it } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init TTS engine", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        if (text.isBlank()) {
            callback.start(24000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val s = settings
        if (s == null) {
            Log.e(TAG, "Settings not initialized")
            callback.error()
            return
        }

        stopRequested = false
        // Android 以百分比传语速（100=正常）。与应用内默认语速取较大者，避免双重叠加时过慢。
        val systemSpeed = if (request.speechRate > 0) request.speechRate / 100f else 1.0f
        val effectiveSpeed = maxOf(currentSpeed, systemSpeed).coerceIn(0.5f, 2.0f)

        try {
            Log.d(TAG, "Synthesizing: ${text.take(50)}...")
            LogManager.appendLog("D", TAG, "Synthesizing: ${text.take(50)}...")
            val engine = EngineRegistry.getActive(currentEngine)

            Log.d(TAG, "Voice=$currentVoice, Style=$currentStyle, Parallel=$parallelSynthesis, Concurrency=$ttsConcurrency, Speed=$effectiveSpeed")
            LogManager.appendLog("D", TAG, "Voice=$currentVoice, Style=$currentStyle, Parallel=$parallelSynthesis, Concurrency=$ttsConcurrency, Speed=$effectiveSpeed")
            val style = if (currentStyle == "无") null else currentStyle

            // 24kHz PCM16 单声道（MiMo 输出格式）。先用默认采样率 start，分段写出。
            var started = false
            val ensureStarted = {
                if (!started) {
                    val r = callback.start(24000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                    if (r == TextToSpeech.ERROR) throw IllegalStateException("callback.start returned error")
                    started = true
                }
            }

            if (parallelSynthesis && engine is MiMoEngine) {
                // 流式：分句有界并发，按序就绪即写出，首字延迟≈单句延迟。
                runBlocking {
                    engine.synthesizeStreaming(text, currentVoice, style, ttsConcurrency) { pcm ->
                        ensureStarted()
                        writePcmOrThrow(callback, applySpeed(pcm, 24000, effectiveSpeed))
                    }
                }
            } else {
                val result = runBlocking { engine.synthesize(text, currentVoice, style) }
                Log.d(TAG, "Got audio: ${result.audioData.size} bytes in ${result.elapsedMs}ms")
                LogManager.appendLog("D", TAG, "Got audio: ${result.audioData.size} bytes in ${result.elapsedMs}ms")
                val sampleRate = AudioUtils.getWavSampleRate(result.audioData)
                val pcmData = AudioUtils.extractPcmData(result.audioData)
                if (!started) {
                    val r = callback.start(sampleRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                    if (r == TextToSpeech.ERROR) {
                        throw IllegalStateException("callback.start returned error")
                    }
                    started = true
                }
                writePcmOrThrow(callback, applySpeed(pcmData, sampleRate, effectiveSpeed))
            }

            if (stopRequested) throw SynthesisStoppedException()
            callback.done()
        } catch (e: SynthesisStoppedException) {
            Log.d(TAG, "Synthesis stopped")
            LogManager.appendLog("D", TAG, "Synthesis stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            LogManager.appendLog("E", TAG, "Synthesis failed: ${e.message}")
            callback.error()
        }
    }

    private fun applySpeed(pcm: ByteArray, sampleRate: Int, speed: Float): ByteArray =
        SpeedAdjuster.process(pcm, sampleRate, 1, speed)

    private fun writePcmOrThrow(callback: SynthesisCallback, pcmData: ByteArray) {
        if (writePcm(callback, pcmData)) return
        if (stopRequested) throw SynthesisStoppedException()
        throw IllegalStateException("audioAvailable failed repeatedly")
    }

    /** 把 PCM 分块写入回调。返回 false 表示应中止（被停止或写错误过多）。 */
    private fun writePcm(callback: SynthesisCallback, pcmData: ByteArray): Boolean {
        val chunkSize = 4096
        var offset = 0
        var errorCount = 0
        while (offset < pcmData.size) {
            if (stopRequested) return false
            val length = minOf(chunkSize, pcmData.size - offset)
            // audioAvailable 支持 offset/length，直接切片传入，避免每块 copyOfRange 分配
            val res = callback.audioAvailable(pcmData, offset, length)
            if (res == TextToSpeech.ERROR) {
                errorCount++
                if (errorCount > 3) {
                    Log.e(TAG, "audioAvailable error count exceeded, aborting")
                    return false
                }
            } else {
                errorCount = 0
            }
            offset += length
        }
        return true
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = languageArrayForVoice(currentVoice)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        if (lang == "eng" || lang == "en" || lang == "jpn" || lang == "ja" ||
            lang == "zho" || lang == "chi" || lang == "zh"
        ) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }

    private fun languageArrayForVoice(voice: String): Array<String> = when {
        voice.isEnglishVoice() -> arrayOf("eng", "USA", "")
        voice.isJapaneseVoice() -> arrayOf("jpn", "JPN", "")
        else -> arrayOf("zho", "CHN", "")
    }

    private fun String.isEnglishVoice(): Boolean =
        startsWith("en-", ignoreCase = true) ||
            equals("Mia", ignoreCase = true) ||
            equals("Chloe", ignoreCase = true) ||
            equals("Milo", ignoreCase = true) ||
            equals("Dean", ignoreCase = true)

    private fun String.isJapaneseVoice(): Boolean =
        startsWith("ja-", ignoreCase = true) ||
            this in setOf("七海", "圭太", "葵", "大智", "诗织", "直树", "真由")

    private class SynthesisStoppedException : RuntimeException()

    companion object {
        private const val TAG = "VoxEngineTTS"
    }
}
