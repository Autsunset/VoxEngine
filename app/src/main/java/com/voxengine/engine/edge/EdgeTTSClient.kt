package com.voxengine.engine.edge

import com.voxengine.audio.AudioUtils
import com.voxengine.audio.Mp3Decoder
import com.voxengine.util.LogManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 微软 Edge 免费 TTS 客户端。通过 WebSocket 连接 readaloud 端点合成语音。
 *
 * 端点只返回 MP3（PCM 输出为 Azure 付费层特性），因此解码为 PCM 后由调用方包成 WAV。
 * 需要 Sec-MS-GEC 滚动令牌握手（参考开源 edge-tts 算法）。
 */
class EdgeTTSClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 合成并返回 WAV 字节（24kHz/采样率随解码、PCM16）。
     * @param rate SSML prosody rate，如 "+0%"；语速统一在下游处理，这里固定常速。
     */
    fun synthesize(text: String, voice: String): ByteArray {
        val mp3Bytes = try {
            connectAndCollect(text, voice)
        } catch (e: ForbiddenException) {
            // 403 多因时钟偏差或令牌过期。用服务器时间校正本地时钟后重试一次。
            val serverDate = e.serverDateMs
            if (serverDate != null) {
                clockSkewMs = serverDate - System.currentTimeMillis()
                LogManager.appendLog("W", TAG, "Edge TTS 403, adjusted clock skew to ${clockSkewMs}ms, retrying")
            } else {
                LogManager.appendLog("W", TAG, "Edge TTS 403 without server date, retrying")
            }
            connectAndCollect(text, voice)
        }

        if (mp3Bytes.isEmpty()) {
            // 已连接并收到 turn.end，但没有音频帧：Edge 对该文本不出声（常见于纯标点/省略号）。
            // 返回静音占位而非抛可重试异常，避免触发重试风暴（2s/8s/18s 退避）并最终中断听书。
            LogManager.appendLog("W", TAG, "Edge TTS 未返回音频，以静音占位 voice=$voice")
            return AudioUtils.silentWav()
        }
        LogManager.appendLog("D", TAG, "Edge TTS got ${mp3Bytes.size} bytes mp3 for voice=$voice")
        val decoded = Mp3Decoder.decodeToPcm(mp3Bytes)
        return AudioUtils.pcmToWav(decoded.pcm, decoded.sampleRate, decoded.channelCount, 16)
    }

    private fun connectAndCollect(text: String, voice: String): ByteArray {
        val gec = generateSecMsGec()
        val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$GEC_VERSION"

        val request = Request.Builder()
            .url(url)
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .build()

        val mp3 = ByteArrayOutputStreamCollector()
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSpeechConfigMessage())
                webSocket.send(buildSsmlMessage(requestId, voice, text))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 文本帧：含 turn.start / turn.end 等控制信息。turn.end 表示本次合成结束。
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制帧：头部为 "...Path:audio\r\n" 后接 MP3 数据。
                val data = bytes.toByteArray()
                val headerEnd = findAudioPayloadStart(data)
                if (headerEnd in 1 until data.size) {
                    mp3.write(data, headerEnd, data.size - headerEnd)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    val dateMs = response.header("Date")?.let { parseHttpDate(it) }
                    error.set(ForbiddenException(dateMs))
                } else {
                    error.set(t)
                }
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        val ws = client.newWebSocket(request, listener)
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                ws.cancel()
                throw java.io.IOException("Edge TTS 合成超时")
            }
        } finally {
            ws.cancel()
        }
        error.get()?.let { throw it }
        return mp3.toByteArray()
    }

    private class ForbiddenException(val serverDateMs: Long?) : RuntimeException("Edge TTS 403 Forbidden")

    private fun parseHttpDate(value: String): Long? = runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        sdf.parse(value)?.time
    }.getOrNull()

    private fun buildSpeechConfigMessage(): String {
        val timestamp = isoTimestamp()
        val config = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
        return "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            config
    }

    private fun buildSsmlMessage(requestId: String, voice: String, text: String): String {
        val timestamp = isoTimestamp()
        // 从音色名推导语言区域（如 ja-JP-NanamiNeural -> ja-JP），让日语等非中文音色用正确的 lang，
        // 否则固定 zh-CN 会让服务端按中文处理，日文汉字被读成中文。
        val lang = localeFromVoice(voice)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'><prosody rate='+0%' pitch='+0Hz'>${escapeXml(text)}</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$timestamp\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    /** 取音色名前两段作为语言区域（语言-国家）。无法解析时回退 zh-CN。 */
    private fun localeFromVoice(voice: String): String {
        val parts = voice.split("-")
        return if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            "${parts[0]}-${parts[1]}"
        } else {
            "zh-CN"
        }
    }

    /** 在二进制帧里定位音频负载起点（"Path:audio\r\n" 之后）。 */
    private fun findAudioPayloadStart(data: ByteArray): Int {
        val marker = "Path:audio\r\n".toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - marker.size) {
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) continue@outer
            }
            return i + marker.size
        }
        // 部分实现头部以 2 字节大端长度开头，回退用通用方式：找首个 \r\n\r\n。
        val sep = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        outer2@ for (i in 0..data.size - sep.size) {
            for (j in sep.indices) {
                if (data[i + j] != sep[j]) continue@outer2
            }
            return i + sep.size
        }
        return -1
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun isoTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * 生成 Sec-MS-GEC 令牌：取当前 Windows FILETIME（1601 纪元、100ns 单位），
     * 向下取整到 5 分钟（即 3,000,000,000 个 100ns 单位），拼上 TrustedClientToken 后 SHA256，大写十六进制。
     */
    private fun generateSecMsGec(): String {
        val unixMs = System.currentTimeMillis() + clockSkewMs
        // Unix 纪元到 Windows 纪元差 11644473600 秒；转 100ns 单位。
        var ticks = (unixMs / 1000 + WIN_EPOCH_DIFF_SECONDS) * 10_000_000L
        ticks -= ticks % 3_000_000_000L
        val strToHash = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** 简单的线程安全字节累加器（onMessage 可能跨线程回调）。 */
    private class ByteArrayOutputStreamCollector {
        private val out = java.io.ByteArrayOutputStream()
        @Synchronized fun write(data: ByteArray, offset: Int, len: Int) = out.write(data, offset, len)
        @Synchronized fun toByteArray(): ByteArray = out.toByteArray()
    }

    companion object {
        private const val TAG = "EdgeTTSClient"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val WIN_EPOCH_DIFF_SECONDS = 11644473600L

        /** 客户端与服务器的时钟偏差（毫秒），由 403 响应的 Date 头校正后累积。 */
        @Volatile private var clockSkewMs = 0L
    }
}
