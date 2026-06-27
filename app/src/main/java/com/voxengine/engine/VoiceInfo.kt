package com.voxengine.engine

data class VoiceInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: VoiceType = VoiceType.PRESET,
    val engineId: String = "",
    val gender: String? = null,
    val ageGroup: String? = null,
    val tags: List<String> = emptyList()
)
