package com.voxengine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxengine.data.SettingsRepository
import com.voxengine.ui.navigation.MainNavGraph
import com.voxengine.ui.theme.VoxEngineTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            val settings = remember { SettingsRepository(applicationContext) }
            val initialTheme = remember { settings.uiThemeMirror() }
            val themeStyle by settings.uiTheme.collectAsStateWithLifecycle(initialValue = initialTheme)
            val darkTheme = isSystemInDarkTheme()
            LaunchedEffect(themeStyle) { settings.cacheUiThemeMirror(themeStyle) }
            VoxEngineTheme(themeStyle = themeStyle) {
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = background.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavGraph()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}
