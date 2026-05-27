package com.voxengine.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.voxengine.ui.navigation.Screen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController = rememberNavController()) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TopAppBar(title = { Text("关于") })

        // 项目信息
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("VoxEngine", style = MaterialTheme.typography.headlineMedium)
                Text("版本 2026.05.28.1", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android 系统级 TTS 语音合成引擎，支持多引擎切换、音色克隆与设计。" +
                    "作为开源项目发布，不收取任何费用。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openUrl("https://github.com/Autsunset/VoxEngine") }) {
                    Text("GitHub 项目页")
                }
                TextButton(onClick = { navController.navigate(Screen.Log.route) }) {
                    Text("查看日志")
                }
            }
        }

        // 服务条款与隐私协议
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("服务条款与隐私协议", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "本软件使用小米 MiMo TTS API 进行语音合成，请遵守以下条款：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openUrl("https://platform.xiaomimimo.com/docs/terms/user-agreement") }) {
                    Text("MiMo 用户协议")
                }
                TextButton(onClick = { openUrl("https://privacy.mi.com/XiaomiMiMoPlatform/zh_CN/") }) {
                    Text("MiMo 隐私政策")
                }
            }
        }

        // 免责声明
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("免责声明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 本软件为开源项目，开发者不收取任何费用，不提供任何商业服务。" +
                    "\n\n2. 本软件仅供学习和个人使用，严禁用于任何违法违规用途，包括但不限于：\n" +
                    "   - 生成虚假信息、诈骗内容或误导性语音\n" +
                    "   - 侵犯他人肖像权、声音权等合法权益\n" +
                    "   - 批量自动化调用或用于商业牟利\n" +
                    "   - 其他违反法律法规的行为" +
                    "\n\n3. 本软件调用小米 MiMo API，用户须遵守小米 MiMo 平台的用户协议和使用条款。" +
                    "任何因违反小米平台规则导致的后果（包括但不限于账号封禁），由用户自行承担。" +
                    "\n\n4. Token Plan 可能仅限用于编程开发场景，将其接入第三方应用（如阅读器）进行语音合成" +
                    "可能违反小米服务条款，导致账号被封禁。建议使用按量计费模式（当前限时免费）。" +
                    "\n\n5. 开发者不对因使用本软件产生的任何直接或间接损失承担责任。" +
                    "\n\n6. 使用本软件即表示您已阅读并同意上述条款。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
            }
        }
    }
}
