package com.voxengine.ui.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxengine.engine.VoiceInfo
import com.voxengine.reader.RoleProfile
import com.voxengine.reader.RoleVoiceStyle

/** 角色音色选择下拉（旁白/对话复用）。选"默认"即传 null，回落到主音色。下拉展开态内部自管。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoleVoicePicker(
    label: String,
    selectedName: String,
    voices: List<VoiceInfo>,
    onSelected: (VoiceInfo?) -> Unit,
    allowDefault: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowDefault) {
                DropdownMenuItem(
                    text = { Text("默认（同主音色）") },
                    onClick = { onSelected(null); expanded = false }
                )
            }
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.name} - ${voice.description}") },
                    onClick = { onSelected(voice); expanded = false }
                )
            }
        }
    }
}

/**
 * 角色名→（音色+风格） 编辑器。列出已配置角色，每项可编辑（改音色/风格）或删除；底部"添加"弹对话框。
 * 角色音色必填——这是修复"添加后不显示/无法删除"的关键：保存按钮仅在选中音色后可用。
 */
@Composable
internal fun CharacterVoiceEditor(
    roleProfile: RoleProfile,
    voices: List<VoiceInfo>,
    onSave: (name: String, voice: String, style: String) -> Unit,
    onRemove: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, RoleVoiceStyle>?>(null) }

    Text("角色音色（按说话人）", style = MaterialTheme.typography.bodyMedium)
    if (roleProfile.characters.isEmpty()) {
        Text(
            "尚未配置角色音色。对话中识别到这些名字时改用对应音色与风格；未命中的对话仍用对话音色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        roleProfile.characters.forEach { (name, vs) ->
            val voiceName = voices.firstOrNull { it.id == vs.voice || it.name == vs.voice }?.name
                ?: vs.voice ?: "默认"
            val desc = buildString {
                append("音色：$voiceName")
                vs.style?.takeIf { it.isNotBlank() }?.let { append(" · 风格：$it") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editing = name to vs }) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑角色")
                }
                IconButton(onClick = { onRemove(name) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除角色")
                }
            }
        }
    }
    OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text("+ 添加角色音色")
    }

    if (showAddDialog) {
        CharacterVoiceDialog(
            title = "添加角色音色",
            initialName = "",
            initialVoice = null,
            initialStyle = "",
            voices = voices,
            onConfirm = { name, voice, style -> onSave(name, voice, style); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }
    editing?.let { (name, vs) ->
        CharacterVoiceDialog(
            title = "编辑角色：$name",
            initialName = name,
            initialVoice = vs.voice,
            initialStyle = vs.style ?: "",
            voices = voices,
            onConfirm = { _, voice, style -> onSave(name, voice, style); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/** 添加/编辑角色共用对话框。name 编辑时不可改（它是 map key）；音色必填、风格可选。 */
@Composable
internal fun CharacterVoiceDialog(
    title: String,
    initialName: String,
    initialVoice: String?,
    initialStyle: String,
    voices: List<VoiceInfo>,
    onConfirm: (name: String, voice: String, style: String) -> Unit,
    onDismiss: () -> Unit
) {
    val adding = initialName.isBlank()
    var name by remember { mutableStateOf(initialName) }
    var selectedVoice by remember {
        mutableStateOf(voices.firstOrNull { it.id == initialVoice || it.name == initialVoice })
    }
    var style by remember { mutableStateOf(initialStyle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名（如：张三）") },
                    enabled = adding,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                RoleVoicePicker(
                    label = "音色",
                    selectedName = selectedVoice?.name ?: "请选择音色",
                    voices = voices,
                    onSelected = { selectedVoice = it },
                    allowDefault = false
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text("风格（可选）") },
                    placeholder = { Text("留空用默认风格") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "提示：朗读时从对话前的「XX说：」识别角色名，匹配则用此音色与风格。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selectedVoice != null,
                onClick = { onConfirm(name.trim(), selectedVoice!!.id, style) }
            ) { Text(if (adding) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
