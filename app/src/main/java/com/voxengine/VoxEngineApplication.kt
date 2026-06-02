package com.voxengine

import android.app.Application
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.edge.EdgeTTSEngine
import com.voxengine.engine.mimo.MiMoEngine
import com.voxengine.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoxEngineApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
