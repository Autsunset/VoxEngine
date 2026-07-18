package com.voxengine.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT * FROM voices ORDER BY createdAt DESC")
    fun getAllVoices(): Flow<List<VoiceEntity>>

    /** 仅 id 列表，供导出时逐条加载，避免一次 CursorWindow 拉爆所有 base64。 */
    @Query("SELECT id FROM voices ORDER BY createdAt DESC")
    suspend fun getAllVoiceIds(): List<Long>

    @Query("SELECT id, name, type, model, description, engineId, gender, ageGroup, tags FROM voices ORDER BY createdAt DESC")
    fun getAllVoiceItems(): Flow<List<VoiceListItem>>

    @Query("SELECT id, name, type, model, description, engineId, gender, ageGroup, tags FROM voices WHERE type = :type ORDER BY createdAt DESC")
    fun getVoiceItemsByType(type: String): Flow<List<VoiceListItem>>

    @Query("SELECT id, name, type, model, description, engineId, gender, ageGroup, tags FROM voices WHERE engineId = :engineId ORDER BY createdAt DESC")
    fun getVoiceItemsByEngine(engineId: String): Flow<List<VoiceListItem>>

    @Query("SELECT * FROM voices WHERE engineId = :engineId ORDER BY createdAt DESC")
    fun getVoicesByEngine(engineId: String): Flow<List<VoiceEntity>>

    @Query("SELECT * FROM voices WHERE id = :id")
    suspend fun getVoiceById(id: Long): VoiceEntity?

    /**
     * 导出用：不读 audioBase64（与 voiceParam 冗余）。
     * 旧数据里两列都塞满 base64 时，SELECT * 会 CursorWindow 闪退。
     */
    @Query(
        """
        SELECT id, name, type, model, voiceParam, description, engineId,
               gender, ageGroup, tags, groupId, createdAt
        FROM voices
        WHERE id = :id
        """
    )
    suspend fun getVoiceForExportById(id: Long): VoiceExportItem?

    /** 删除前只取 name 做缓存失效，绝不能 SELECT *（克隆行含大 base64，会 CursorWindow 闪退）。 */
    @Query("SELECT name FROM voices WHERE id = :id")
    suspend fun getVoiceNameById(id: Long): String?

    @Query("SELECT * FROM voices WHERE name = :name LIMIT 1")
    suspend fun getVoiceByName(name: String): VoiceEntity?

    @Query("SELECT * FROM voices WHERE engineId = :engineId AND name = :name LIMIT 1")
    suspend fun getVoiceByEngineAndName(engineId: String, name: String): VoiceEntity?

    /**
     * 合成路径解析音色：只取需要的列。
     * 刻意不 SELECT audioBase64——该列与 voiceParam 冗余，一起拉会轻易超过 CursorWindow ~2MB 上限。
     */
    @Query(
        """
        SELECT type, model, voiceParam, engineId, createdAt
        FROM voices
        WHERE engineId = :engineId AND name = :name
        LIMIT 1
        """
    )
    suspend fun getVoiceResolveByEngineAndName(engineId: String, name: String): VoiceResolveItem?

    @Query("SELECT name, type FROM voices WHERE engineId = :engineId AND name IN (:names)")
    suspend fun getVoiceTypesByEngineAndNames(engineId: String, names: List<String>): List<VoiceTypeItem>

    @Query("SELECT EXISTS(SELECT 1 FROM voices WHERE engineId = :engineId AND name = :name LIMIT 1)")
    suspend fun existsByEngineAndName(engineId: String, name: String): Boolean

    @Insert
    suspend fun insert(voice: VoiceEntity): Long

    @Update
    suspend fun update(voice: VoiceEntity)

    /** 只改元数据，避免 getVoiceById + update 整行（含 base64）导致闪退。 */
    @Query(
        """
        UPDATE voices
        SET gender = :gender, ageGroup = :ageGroup, tags = :tags
        WHERE id = :id
        """
    )
    suspend fun updateVoiceMeta(id: Long, gender: String?, ageGroup: String?, tags: String?)

    @Delete
    suspend fun delete(voice: VoiceEntity)

    @Query("DELETE FROM voices WHERE id = :id")
    suspend fun deleteById(id: Long)
}
