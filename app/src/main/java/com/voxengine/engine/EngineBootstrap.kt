package com.voxengine.engine

import com.voxengine.data.SettingsRepository
import com.voxengine.engine.edge.EdgeTTSEngine
import com.voxengine.engine.mimo.MiMoEngine

object EngineBootstrap {
    @Synchronized
    fun ensureRegistered(settingsRepository: SettingsRepository) {
        if (!EngineRegistry.isRegistered("mimo")) {
            EngineRegistry.register(MiMoEngine(settingsRepository))
        }
        if (!EngineRegistry.isRegistered("edge")) {
            EngineRegistry.register(EdgeTTSEngine(settingsRepository))
        }
    }
}
