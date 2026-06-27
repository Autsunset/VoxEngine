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
    // 音色元数据：性别 / 年龄段 / 标签 / 分组，用于分组管理与分角色路由。均可空，老数据迁移后为 null。
    val gender: String? = null,
    val ageGroup: String? = null,
    val tags: String? = null,
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class VoiceListItem(
    val id: Long,
    val name: String,
    val type: String,
    val model: String,
    val description: String,
    val engineId: String,
    val gender: String?,
    val ageGroup: String?,
    val tags: String?
)
