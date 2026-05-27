package com.voxengine.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxengine.data.AppDatabase
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import kotlinx.coroutines.launch

// MiMo API 预设 URL
private val MIMO_API_PRESETS = listOf(
    "按量计费" to "https://api.xiaomimimo.com",
    "Token Plan (中国区)" to "https://token-plan-cn.xiaomimimo.com",
    "Token Plan (新加坡)" to "https://token-plan-sgp.xiaomimimo.com",
    "Token Plan (欧洲)" to "https://token-plan-ams.xiaomimimo.com"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }

    val baseUrl by settings.baseUrl.collectAsState(initial = "https://api.xiaomimimo.com")
    val apiKey by settings.apiKey.collectAsState(initial = "")
    val defaultVoice by settings.defaultVoice.collectAsState(initial = "冰糖")
    val defaultStyle by settings.defaultStyle.collectAsState(initial = "无")
    val speed by settings.speed.collectAsState(initial = 1.0f)
    val darkMode by settings.darkMode.collectAsState(initial = false)
    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")

    var baseUrlInput by remember { mutableStateOf(baseUrl) }
    var apiKeyInput by remember { mutableStateOf(apiKey) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var styleExpanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }

    val activeEngine = EngineRegistry.get(currentEngineId)
    
    // 预设音色
    val presetVoices = remember(activeEngine) {
        kotlinx.coroutines.runBlocking { activeEngine?.getVoices() ?: emptyList() }
    }
    
    // 自定义音色（从数据库加载）
    val customVoices by db.voiceDao().getVoicesByEngine(currentEngineId).collectAsState(initial = emptyList())
    
    // 合并所有音色
    val allVoices = remember(presetVoices, customVoices) {
        val presetNames = presetVoices.map { it.name }.toSet()
        val customAsPreset = customVoices.filter { it.name !in presetNames }.map { voice ->
            com.voxengine.engine.VoiceInfo(
                id = "custom_${voice.id}",
                name = voice.name,
                description = if (voice.type == "clone") "克隆音色" else "设计: ${voice.description}",
                type = com.voxengine.engine.VoiceType.PRESET,
                engineId = currentEngineId
            )
        }
        presetVoices + customAsPreset
    }
    
    val styles = remember(activeEngine) {
        kotlinx.coroutines.runBlocking { activeEngine?.getStyles() ?: emptyList() }
    }

    // 当前选中的预设计费模式
    val currentPreset = MIMO_API_PRESETS.find { it.second == baseUrl }?.first ?: "自定义"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TopAppBar(title = { Text("VoxEngine 设置") })

        // 深色模式
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("深色模式")
                Switch(
                    checked = darkMode,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.updateDarkMode(enabled) }
                    }
                )
            }
        }

        // 引擎选择
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("引擎选择", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = it }
                ) {
                    OutlinedTextField(
                        value = activeEngine?.name ?: currentEngineId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("TTS 引擎") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        EngineRegistry.getAll().forEach { engine ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(engine.name)
                                        Text(engine.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = {
                                    scope.launch { settings.updateCurrentEngine(engine.id) }
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // API 配置
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API 配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // MiMo 预设计费模式选择
                if (currentEngineId == "mimo") {
                    Text("计费模式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = presetExpanded,
                        onExpandedChange = { presetExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentPreset,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("计费模式") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = presetExpanded,
                            onDismissRequest = { presetExpanded = false }
                        ) {
                            MIMO_API_PRESETS.forEach { (name, url) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        baseUrlInput = url
                                        scope.launch { settings.updateBaseUrl(url) }
                                        presetExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "💡 Token Plan 需要使用 tp-xxxxx 格式的 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                Button(onClick = {
                    scope.launch {
                        settings.updateBaseUrl(baseUrlInput)
                        settings.updateApiKey(apiKeyInput)
                    }
                }) { Text("保存 API 配置") }
            }
        }

        // 默认语音
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("默认语音", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = voiceExpanded,
                    onExpandedChange = { voiceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = defaultVoice,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("默认音色") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = voiceExpanded,
                        onDismissRequest = { voiceExpanded = false }
                    ) {
                        // 显示预设音色
                        if (presetVoices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { 
                                    Text("预设音色", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary) 
                                },
                                onClick = { /* 分组标题，不处理 */ }
                            )
                            presetVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text("${voice.name} - ${voice.description}") },
                                    onClick = {
                                        scope.launch { settings.updateDefaultVoice(voice.name) }
                                        voiceExpanded = false
                                    }
                                )
                            }
                        }
                        
                        // 显示自定义音色
                        if (customVoices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { 
                                    Text("自定义音色", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary) 
                                },
                                onClick = { /* 分组标题，不处理 */ }
                            )
                            customVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { 
                                        Text("${voice.name} - ${if (voice.type == "clone") "克隆" else "设计"}") 
                                    },
                                    onClick = {
                                        scope.launch { settings.updateDefaultVoice(voice.name) }
                                        voiceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (styles.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = styleExpanded,
                        onExpandedChange = { styleExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = defaultStyle,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("默认风格") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = styleExpanded,
                            onDismissRequest = { styleExpanded = false }
                        ) {
                            styles.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style) },
                                    onClick = {
                                        scope.launch { settings.updateDefaultStyle(style) }
                                        styleExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text("语速: ${String.format("%.1f", speed)}x")
                Slider(
                    value = speed,
                    onValueChange = { scope.launch { settings.updateSpeed(it) } },
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )
            }
        }

        // 使用说明
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用说明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 在上方设置好 API Key\n" +
                    "2. 系统设置 → 语言和输入 → 文字转语音\n" +
                    "3. 选择 VoxEngine 为首选引擎\n" +
                    "4. 在阅读 APP 中选择系统默认引擎即可",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
