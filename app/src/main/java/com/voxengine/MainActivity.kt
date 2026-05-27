package com.voxengine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voxengine.data.SettingsRepository
import com.voxengine.ui.navigation.MainNavGraph
import com.voxengine.ui.theme.VoxEngineTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = remember { SettingsRepository(this) }
            val darkMode by settings.darkMode.collectAsState(initial = false)
            VoxEngineTheme(darkTheme = darkMode) {
                MainNavGraph()
            }
        }
    }
}
