package com.voxengine.reader

/**
 * 分角色朗读配置模型。一个 [RoleVoiceStyle] = 音色 + 可选风格；角色档由旁白 / 对话 / 具名角色三部分组成。
 *
 * 纯数据，序列化为 JSON 持久化（[RoleProfileJson]）。voice 为 null 表示该项回落到默认（主音色/主风格）；
 * style 为 null 表示该项沿用默认风格。空 [RoleProfile] 等价于关闭分角色（全部用主音色）。
 */
data class RoleVoiceStyle(
    val voice: String? = null,
    val style: String? = null
) {
    /** 既未指定音色也未指定风格——视为空槽，展示"默认"。 */
    fun isEmpty() = voice.isNullOrBlank() && style.isNullOrBlank()
}

data class RoleProfile(
    val narration: RoleVoiceStyle = RoleVoiceStyle(),
    val dialogue: RoleVoiceStyle = RoleVoiceStyle(),
    val characters: Map<String, RoleVoiceStyle> = emptyMap()
)

/** [RoleProfile] 的 JSON 序列化/反序列化（Gson）。ViewModel 写入、Service 解析共用。 */
object RoleProfileJson {
    private val gson by lazy { com.google.gson.Gson() }

    fun serialize(profile: RoleProfile): String = gson.toJson(profile)

    fun parse(json: String?): RoleProfile {
        if (json.isNullOrBlank()) return RoleProfile()
        return runCatching { gson.fromJson(json, RoleProfile::class.java) }.getOrNull() ?: RoleProfile()
    }
}
