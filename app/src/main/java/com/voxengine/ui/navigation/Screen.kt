package com.voxengine.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Settings : Screen("settings", "设置")
    data object VoiceManage : Screen("voice_manage", "音色管理")
    data object Test : Screen("test", "测试")
}
