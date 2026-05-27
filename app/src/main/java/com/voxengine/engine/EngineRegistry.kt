package com.voxengine.engine

object EngineRegistry {
    private val engines = mutableMapOf<String, TTSEngine>()

    fun register(engine: TTSEngine) {
        engines[engine.id] = engine
    }

    fun get(id: String): TTSEngine? = engines[id]

    fun getAll(): List<TTSEngine> = engines.values.toList()

    fun getActive(activeEngineId: String): TTSEngine {
        return engines[activeEngineId] ?: engines.values.firstOrNull()
            ?: throw IllegalStateException("No TTS engines registered")
    }

    fun isRegistered(id: String): Boolean = engines.containsKey(id)
}
