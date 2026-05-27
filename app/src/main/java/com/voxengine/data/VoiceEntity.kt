package com.voxengine.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voices")
data class VoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val model: String,
    val voiceParam: String,
    val description: String = "",
    val audioBase64: String? = null,
    val engineId: String = "mimo",
    val createdAt: Long = System.currentTimeMillis()
)
