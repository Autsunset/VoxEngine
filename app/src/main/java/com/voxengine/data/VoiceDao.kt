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

    @Query("SELECT * FROM voices WHERE name = :name LIMIT 1")
    suspend fun getVoiceByName(name: String): VoiceEntity?

    @Query("SELECT * FROM voices WHERE engineId = :engineId AND name = :name LIMIT 1")
    suspend fun getVoiceByEngineAndName(engineId: String, name: String): VoiceEntity?

    @Insert
    suspend fun insert(voice: VoiceEntity): Long

    @Update
    suspend fun update(voice: VoiceEntity)

    @Delete
    suspend fun delete(voice: VoiceEntity)

    @Query("DELETE FROM voices WHERE id = :id")
    suspend fun deleteById(id: Long)
}
