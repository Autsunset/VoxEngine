package com.voxengine.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Settings : Screen("settings", "设置")
    data object VoiceManage : Screen("voice_manage", "音色管理")
    data object Test : Screen("test", "测试")
    data object About : Screen("about", "关于")
    data object Log : Screen("log", "日志")
}
