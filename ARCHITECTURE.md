# VoxEngine 可插拔 TTS 引擎架构设计

## 核心抽象

```kotlin
// 统一的 TTS 引擎接口
interface TTSEngine {
    val id: String
    val name: String
    val description: String
    val supportsVoiceClone: Boolean
    val supportsVoiceDesign: Boolean
    
    suspend fun synthesize(
        text: String,
        voice: String,
        style: String? = null,
        speed: Float = 1.0f
    ): SynthesisResult
    
    suspend fun getVoices(): List<VoiceInfo>
    suspend fun getStyles(): List<String>
    fun isConfigured(): Boolean
}

data class SynthesisResult(
    val audioData: ByteArray,
    val format: AudioFormat,  // WAV, MP3, PCM
    val sampleRate: Int,
    val elapsedMs: Long
)

data class VoiceInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: VoiceType  // PRESET, CLONE, DESIGN
)

enum class VoiceType { PRESET, CLONE, DESIGN }
enum class AudioFormat { WAV, MP3, PCM }
```

## 引擎注册表

```kotlin
object EngineRegistry {
    private val engines = mutableMapOf<String, TTSEngine>()
    
    fun register(engine: TTSEngine)
    fun get(id: String): TTSEngine?
    fun getAll(): List<TTSEngine>
    fun getActive(): TTSEngine  // 当前选中的引擎
}
```

## 引擎实现结构

```
com.voxengine/
├── engine/
│   ├── TTSEngine.kt           # 接口定义
│   ├── EngineRegistry.kt      # 引擎注册表
│   ├── mimo/
│   │   ├── MiMoEngine.kt      # MiMo 实现
│   │   └── MiMoModels.kt      # MiMo 数据模型
│   ├── openai/                 # 预留
│   └── edge/                   # 预留
├── api/                        # 保留原有 API 客户端
├── bridge/                     # HTTP Bridge
├── data/                       # 设置存储
├── ui/                         # UI 界面
└── MainActivity.kt
```

## 配置管理

每个引擎有独立的配置，存储在 DataStore 中：

```kotlin
// 引擎配置键
sealed class EngineConfigKey(val engineId: String, val key: String) {
    class ApiKey(engineId: String) : EngineConfigKey(engineId, "api_key")
    class BaseUrl(engineId: String) : EngineConfigKey(engineId, "base_url")
    class DefaultVoice(engineId: String) : EngineConfigKey(engineId, "default_voice")
}

// 示例
val mimoApiKey = EngineConfigKey.ApiKey("mimo")
val openaiApiKey = EngineConfigKey.ApiKey("openai")
```

## UI 更新

1. 设置页面：增加引擎选择下拉框
2. 音色管理：根据当前引擎动态显示可用音色
3. 测试页面：引擎切换后自动刷新可用选项

## Legado 集成

1. TTS Engine：系统级引擎，Legado 直接调用
2. HTTP Bridge：通用接口，支持引擎切换
   ```
   /tts?text=xxx&engine=mimo&voice=冰糖&style=开心
   /tts?text=xxx&engine=openai&voice=alloy
   ```
