package com.voxengine

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineBootstrap
import com.voxengine.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoxEngineApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogManager.init(this)
        applySavedNightMode()
        registerEngines()
    }

    private fun applySavedNightMode() {
        val settings = SettingsRepository(this)
        // 先用 SharedPreferences 镜像同步应用，避免在主线程阻塞读 DataStore。
        applyNightMode(settings.darkModeMirror())
        // 后台与 DataStore 真值对账，纠正升级后镜像尚未写入的情形；稳态下镜像与真值一致，为无操作、不会触发主题闪烁。
        appScope.launch {
            val actual = settings.darkMode.first()
            if (actual != settings.darkModeMirror()) {
                settings.cacheDarkModeMirror(actual)
                applyNightMode(actual)
            }
        }
    }

    private fun applyNightMode(darkMode: Boolean) {
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
