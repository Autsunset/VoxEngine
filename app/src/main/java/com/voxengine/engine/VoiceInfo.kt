package com.voxengine.engine

data class VoiceInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: VoiceType = VoiceType.PRESET,
    val engineId: String = ""
)
