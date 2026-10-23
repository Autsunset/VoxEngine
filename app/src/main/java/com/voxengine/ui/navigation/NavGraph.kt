package com.voxengine.ui.navigation

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voxengine.ui.screens.AboutScreen
import com.voxengine.ui.screens.LogScreen
import com.voxengine.ui.screens.ReaderScreen
import com.voxengine.ui.screens.SettingsScreen
import com.voxengine.ui.screens.TestScreen
import com.voxengine.ui.screens.VoiceManageScreen
import com.voxengine.data.AppThemeStyle
import com.voxengine.ui.components.LiquidGlassDock
import com.voxengine.ui.components.LocalVoxNavigationInset
import com.voxengine.ui.theme.GlassScene
import com.voxengine.ui.theme.LocalAppThemeStyle
import com.voxengine.ui.theme.recordGlassBackdrop
import com.voxengine.ui.theme.rememberGlassBackdrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp

data class BottomNavItem(val screen: Screen, val icon: ImageVector)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Settings, Icons.Default.Home),
    BottomNavItem(Screen.VoiceManage, Icons.Default.RecordVoiceOver),
    BottomNavItem(Screen.Reader, Icons.AutoMirrored.Filled.MenuBook),
    BottomNavItem(Screen.Test, Icons.Default.Mic),
    BottomNavItem(Screen.About, Icons.Default.Info)
)

@Composable
fun MainNavGraph() {
    val navController = rememberNavController()
    var readerImmersive by remember { mutableStateOf(false) }
    val glass = LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
    val backdrop = rememberGlassBackdrop()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val navigateTo: (BottomNavItem) -> Unit = { item ->
        navController.navigate(item.screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = if (glass && !readerImmersive) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        } else Modifier,
        containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!readerImmersive) {
                if (glass) {
                    val index = bottomNavItems.indexOfFirst { item ->
                        currentDestination?.hierarchy?.any { it.route == item.screen.route } == true ||
                            (item.screen == Screen.About && currentDestination?.route == Screen.Log.route)
                    }.coerceAtLeast(0)
                    LiquidGlassDock(
                        items = bottomNavItems,
                        selectedIndex = index,
                        backdrop = backdrop,
                        onSelect = navigateTo
                    )
                } else NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.screen.title) },
                            label = { Text(item.screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = { navigateTo(item) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().then(
                if (glass && !readerImmersive && Build.VERSION.SDK_INT >= 31) {
                    Modifier.recordGlassBackdrop(backdrop)
                } else Modifier
            )
        ) {
            if (glass && !readerImmersive) GlassScene(Modifier.fillMaxSize())
            CompositionLocalProvider(
                LocalVoxNavigationInset provides if (glass) innerPadding.calculateBottomPadding() else 0.dp
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Settings.route,
                    modifier = if (glass) Modifier.fillMaxSize() else Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Settings.route) { SettingsScreen() }
                    composable(Screen.VoiceManage.route) { VoiceManageScreen() }
                    composable(Screen.Reader.route) { ReaderScreen(onReadingModeChanged = { readerImmersive = it }) }
                    composable(Screen.Test.route) { TestScreen() }
                    composable(Screen.About.route) { AboutScreen(navController) }
                    composable(Screen.Log.route) { LogScreen() }
                }
            }
        }
    }
}
