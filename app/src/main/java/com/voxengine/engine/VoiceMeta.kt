package com.voxengine.engine

/**
 * 音色元数据常量与工具。
 *
 * 性别 / 年龄段在库里以 String 存储（便于迁移与导出），UI 侧统一通过这里的常量与 [labelOf] 取中文展示。
 * 标签 [VoiceTags] 以英文/中文逗号分隔的字符串入库，提供解析与合并工具，供"分组打标签"与角色路由复用。
 */
object VoiceGender {
    const val MALE = "male"
    const val FEMALE = "female"
    const val NEUTRAL = "neutral"

    val ALL = listOf(MALE, FEMALE, NEUTRAL)

    fun labelOf(value: String?): String = when (value) {
        MALE -> "男声"
        FEMALE -> "女声"
        NEUTRAL -> "中性"
        else -> "未分类"
    }
}

object VoiceAgeGroup {
    const val CHILD = "child"
    const val YOUNG = "young"
    const val MIDDLE = "middle"
    const val OLD = "old"

    val ALL = listOf(CHILD, YOUNG, MIDDLE, OLD)

    fun labelOf(value: String?): String = when (value) {
        CHILD -> "儿童"
        YOUNG -> "青年"
        MIDDLE -> "中年"
        OLD -> "老年"
        else -> "未设置"
    }
}

object VoiceTags {
    /** 标签以英文/中文逗号或换行分隔入库。 */
    fun parse(tags: String?): List<String> =
        tags?.split(',', '，', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun join(tags: List<String>): String = tags.joinToString(",")
}
