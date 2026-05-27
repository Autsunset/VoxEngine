package com.voxengine.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "synthesis_history")
data class SynthesisHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val voice: String,
    val style: String? = null,
    val speed: Float = 1.0f,
    val engineId: String = "mimo",
    val timestamp: Long = System.currentTimeMillis()
)
