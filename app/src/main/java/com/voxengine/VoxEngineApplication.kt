package com.voxengine

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineBootstrap
import com.voxengine.util.LogManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class VoxEngineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LogManager.init(this)
        applySavedNightMode()
        registerEngines()
    }

    private fun applySavedNightMode() {
        val settings = SettingsRepository(this)
        val darkMode = runBlocking { settings.darkMode.first() }
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun registerEngines() {
        val settings = SettingsRepository(this)
        EngineBootstrap.ensureRegistered(settings)
    }

    companion object {
        lateinit var instance: VoxEngineApplication
            private set
    }
}
