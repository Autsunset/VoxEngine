package com.voxengine

import android.app.Application
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.edge.EdgeTTSEngine
import com.voxengine.engine.mimo.MiMoEngine
import com.voxengine.util.LogManager

class VoxEngineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LogManager.init(this)
        registerEngines()
    }

    private fun registerEngines() {
        val settings = SettingsRepository(this)
        val mimoEngine = MiMoEngine(settings)
        EngineRegistry.register(mimoEngine)
        EngineRegistry.register(EdgeTTSEngine(settings))
    }

    companion object {
        lateinit var instance: VoxEngineApplication
            private set
    }
}
