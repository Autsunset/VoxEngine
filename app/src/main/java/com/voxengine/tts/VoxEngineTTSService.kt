package com.voxengine.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.voxengine.audio.AudioUtils
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.mimo.MiMoEngine
import com.voxengine.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class VoxEngineTTSService : TextToSpeechService() {

    private var settings: SettingsRepository? = null
    private var currentVoice = "冰糖"
    private var currentStyle = "无"
    private var currentSpeed = 1.0f
    private var parallelSynthesis = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        try {
            settings = SettingsRepository(applicationContext)
            val s = settings!!

            val engineId = runBlocking { s.currentEngine.first() }
            currentVoice = runBlocking { s.defaultVoice.first() }
            currentStyle = runBlocking { s.defaultStyle.first() }
            currentSpeed = runBlocking { s.speed.first() }
            parallelSynthesis = runBlocking { s.parallelSynthesis.first() }

            if (!EngineRegistry.isRegistered(engineId)) {
                val engine = MiMoEngine(s)
                EngineRegistry.register(engine)
            }

            serviceScope.launch { s.defaultVoice.collect { currentVoice = it } }
            serviceScope.launch { s.defaultStyle.collect { currentStyle = it } }
            serviceScope.launch { s.speed.collect { currentSpeed = it } }
            serviceScope.launch { s.parallelSynthesis.collect { parallelSynthesis = it } }
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

        try {
            Log.d(TAG, "Synthesizing: ${text.take(50)}...")
            LogManager.appendLog("D", TAG, "Synthesizing: ${text.take(50)}...")
            val engineId = runBlocking { s.currentEngine.first() }
            val engine = EngineRegistry.getActive(engineId)

            Log.d(TAG, "Voice=$currentVoice, Style=$currentStyle, Parallel=$parallelSynthesis")
            LogManager.appendLog("D", TAG, "Voice=$currentVoice, Style=$currentStyle, Parallel=$parallelSynthesis")
            val style = if (currentStyle == "无") null else currentStyle

            val result = runBlocking {
                if (parallelSynthesis && engine is MiMoEngine) {
                    engine.synthesizeParallel(text, currentVoice, style)
                } else {
                    engine.synthesize(text, currentVoice, style)
                }
            }

            Log.d(TAG, "Got audio: ${result.audioData.size} bytes in ${result.elapsedMs}ms")
            LogManager.appendLog("D", TAG, "Got audio: ${result.audioData.size} bytes in ${result.elapsedMs}ms")

            val sampleRate = AudioUtils.getWavSampleRate(result.audioData)
            val pcmData = AudioUtils.extractPcmData(result.audioData)

            val startResult = callback.start(sampleRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            if (startResult == TextToSpeech.ERROR) {
                Log.e(TAG, "callback.start returned error, aborting")
                return
            }

            val chunkSize = 4096
            var offset = 0
            var errorCount = 0
            while (offset < pcmData.size) {
                val end = minOf(offset + chunkSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                val res = callback.audioAvailable(chunk, 0, chunk.size)
                if (res == TextToSpeech.ERROR) {
                    errorCount++
                    if (errorCount > 3) {
                        Log.e(TAG, "audioAvailable error count exceeded, aborting")
                        break
                    }
                } else {
                    errorCount = 0
                }
                offset = end
            }

            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    override fun onStop() {}

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("zho", "CHN", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    companion object {
        private const val TAG = "VoxEngineTTS"
    }
}
