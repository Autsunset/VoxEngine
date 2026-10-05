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
    val characters: Map<String, RoleVoiceStyle> = emptyMap(),
    val matchRules: List<RoleMatchRule> = emptyList()
)

/** Rules run in order before built-in speaker detection. Context never becomes spoken text. */
data class RoleMatchRule(
    val character: String = "",
    val pattern: String = "",
    val afterDialogue: Boolean = false,
    val enabled: Boolean = true
) {
    fun validate() {
        require(character.isNotBlank()) { "请填写目标角色名" }
        require(pattern.isNotBlank() && pattern.length <= 512) { "规则需为 1–512 字符的正则表达式" }
        Regex(pattern)
    }
}

/** [RoleProfile] 的 JSON 序列化/反序列化（Gson）。ViewModel 写入、Service 解析共用。 */
object RoleProfileJson {
    private val gson by lazy { com.google.gson.Gson() }

    fun serialize(profile: RoleProfile): String = gson.toJson(profile)

    fun parse(json: String?): RoleProfile {
        if (json.isNullOrBlank()) return RoleProfile()
        return runCatching { import(json) }.getOrNull() ?: RoleProfile()
    }

    /** Strict import: malformed files must not silently overwrite the current configuration. */
    fun import(json: String): RoleProfile {
        require(json.length <= 1_000_000) { "角色配置文件过大" }
        val root = com.google.gson.JsonParser.parseString(json)
        require(root.isJsonObject && root.asJsonObject.entrySet().any {
            it.key in setOf("narration", "dialogue", "characters", "matchRules")
        }) { "不是角色配置文件" }
        val profile = gson.fromJson(root, RoleProfile::class.java)
        // Gson may supply null for explicit JSON null, including non-null Kotlin properties.
        val narration = requireNotNull(profile.narration) { "旁白配置不能为空" }
        val dialogue = requireNotNull(profile.dialogue) { "对话配置不能为空" }
        val characters = requireNotNull(profile.characters) { "角色列表不能为空" }
        val rules = requireNotNull(profile.matchRules) { "规则列表不能为空" }
        require(characters.size <= 500 && rules.size <= 200) { "角色或规则数量过多" }
        characters.forEach { (name, assignment) ->
            require(name.isNotBlank() && assignment != null) { "角色配置无效" }
        }
        rules.forEach { rule ->
            requireNotNull(rule).validate()
            require(rule.character in characters) { "规则目标角色不存在：${rule.character}" }
        }
        return RoleProfile(narration, dialogue, characters, rules)
    }
}
