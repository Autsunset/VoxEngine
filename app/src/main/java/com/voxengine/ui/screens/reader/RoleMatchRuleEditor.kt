package com.voxengine.ui.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.voxengine.reader.RoleMatchRule
import com.voxengine.reader.RoleProfile

@Composable
internal fun RoleMatchRuleEditor(
    profile: RoleProfile,
    onRulesChange: (List<RoleMatchRule>) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Text("角色匹配规则（按顺序优先匹配）", style = MaterialTheme.typography.bodyMedium)
    Text("规则未命中时沿用默认识别。可将别名、动作或说话习惯匹配到已配置角色。", style = MaterialTheme.typography.bodySmall)
    profile.matchRules.forEachIndexed { index, rule ->
        Row {
            TextButton(onClick = { editing = index }, modifier = Modifier.weight(1f)) {
                Text("${index + 1}. ${rule.character} · ${if (rule.afterDialogue) "对话后" else "对话前"}：${rule.pattern}")
            }
            Switch(rule.enabled, { enabled -> onRulesChange(profile.matchRules.mapIndexed { i, r -> if (i == index) r.copy(enabled = enabled) else r }) })
            TextButton(onClick = { onRulesChange(profile.matchRules.filterIndexed { i, _ -> i != index }) }) { Text("删除") }
        }
    }
    OutlinedButton(onClick = { editing = profile.matchRules.size }, enabled = profile.characters.isNotEmpty()) { Text("添加匹配规则") }
    Row {
        TextButton(onClick = onImport) { Text("导入角色与规则") }
        TextButton(onClick = onExport) { Text("导出角色与规则") }
    }
    editing?.let { index ->
        val original = profile.matchRules.getOrNull(index) ?: RoleMatchRule()
        var character by remember(index) { mutableStateOf(original.character) }
        var pattern by remember(index) { mutableStateOf(original.pattern) }
        var after by remember(index) { mutableStateOf(original.afterDialogue) }
        val rule = RoleMatchRule(character.trim(), pattern, after, original.enabled)
        val error = runCatching {
            rule.validate()
            require(rule.character in profile.characters) { "目标角色需先添加音色" }
        }.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑匹配规则") },
            text = {
                Column {
                    OutlinedTextField(character, { character = it }, label = { Text("目标角色名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("正则表达式") }, placeholder = { Text("例如：(小张|张公子).*笑道") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        Text("匹配对话后的旁白", modifier = Modifier.weight(1f))
                        Switch(after, { after = it })
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = error == null, onClick = {
                onRulesChange(profile.matchRules.toMutableList().apply { if (index < size) set(index, rule) else add(rule) })
                editing = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
