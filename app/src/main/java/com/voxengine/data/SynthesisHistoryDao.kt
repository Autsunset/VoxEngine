package com.voxengine.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SynthesisHistoryDao {
    @Query("SELECT * FROM synthesis_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<SynthesisHistoryEntity>>

    @Query("SELECT * FROM synthesis_history WHERE engineId = :engineId ORDER BY timestamp DESC LIMIT :limit")
    fun getByEngine(engineId: String, limit: Int = 20): Flow<List<SynthesisHistoryEntity>>

    @Insert
    suspend fun insert(history: SynthesisHistoryEntity)

    @Query("DELETE FROM synthesis_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM synthesis_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM synthesis_history")
    suspend fun count(): Int
}
