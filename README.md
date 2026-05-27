# VoxEngine

Android 系统级 TTS 语音合成引擎，支持多引擎切换、音色克隆与设计。注册为系统 TTS 服务后，任意支持系统语音合成的应用（如 Legado 阅读器）均可直接调用。

## 功能特性

- **可插拔引擎架构** — 统一接口设计，当前支持 MiMo TTS，可扩展 OpenAI、Edge TTS 等
- **预设音色** — 内置多种中英文音色（冰糖、茉莉、苏打、白桦、Mia、Chloe 等），开箱即用
- **音色克隆** — 上传或录制一段音频样本，精准复刻目标音色
- **音色设计** — 通过文字描述自动生成定制音色，无需音频文件
- **风格控制** — 支持情绪、语调、方言、角色扮演等风格标签，一句话切换发音风格
- **系统 TTS 集成** — 作为 Android TextToSpeechService 运行，所有支持系统 TTS 的应用均可使用
- **音色导入导出** — 支持将自定义音色导出为 JSON 文件，方便备份与分享

## 图文教程

### 1. 注册 MiMo 平台

前往 [小米 MiMo TTS 平台](https://platform.xiaomimimo.com?ref=S5T7WV) 注册账号，在控制台新建 API Key。

MiMo 提供两种计费模式：

| 计费模式 | API Key 格式 | 说明 |
|---------|-------------|------|
| 按量计费 | `sk-xxxxx` | 限时免费，按调用次数计费 |
| Token Plan | `tp-xxxxx` | 需购买 Token 套餐，中国区/新加坡/欧洲节点可选 |

> 建议新手使用**按量计费**模式（当前限时免费），API Key 以 `sk-` 开头。

![新建 API Key](1新建APIkey1.png)

### 2. 复制 API Key

创建完成后复制 API Key。

![复制 API Key](2复制apikey.png)

### 3. 在 VoxEngine 中填入 API Key

打开 VoxEngine → 设置页面，选择计费模式，填入 API Key，点击「保存 API 配置」。然后选择你喜欢的默认音色和风格。

![填入 API Key](3app界面，这里填入apikey.jpg)

### 4. 进入系统 TTS 设置

在 VoxEngine 设置页点击「前往设置」，跳转到系统文字转语音设置页面。

![点击前往设置](4app界面，点击这里进入系统默认tts引擎切换.jpg)

### 5. 切换首选引擎（第一步）

在系统 TTS 设置中，点击「首选引擎」。

![切换首选引擎](5切换首选引擎1.jpg)

### 6. 选择 VoxEngine（第二步）

在引擎列表中选择 **VoxEngine**，完成！

![选择 VoxEngine](6切换首选引擎2.jpg)

现在任意支持系统 TTS 的应用（如 Legado 阅读器）都可以直接使用 VoxEngine 进行语音合成了。

> 在 Legado 中使用：确保 VoxEngine 已设为系统默认引擎，打开 Legado → 阅读界面 → 点击「朗读」即可。

## 音色说明

### 预设音色（推荐）

> 预设音色开箱即用，效果最佳。预设音色同样支持自定义风格标签，可以自由搭配语调、情绪、方言等风格。

| 音色 | 描述 |
|------|------|
| 冰糖 | 甜美可爱女声 |
| 茉莉 | 温柔知性女声 |
| 苏打 | 活力阳光男声 |
| 白桦 | 沉稳磁性男声 |
| Mia | 英文女声 |
| Chloe | 英文女声 |
| Milo | 英文男声 |
| Dean | 英文男声 |

### 音色克隆

上传或录制一段参考音频（建议 3-10 秒），MiMo 会根据音频特征克隆出相似的音色。适用于复刻特定角色的声音。

> 自定义音色（克隆/设计）效果取决于输入素材和描述，可能需要多次调试才能达到理想效果。

### 音色设计

通过文字描述生成定制音色，例如：
- "温柔磁性的中年男声"
- "活泼可爱的少女音"
- "低沉沙哑的旁白声"

## 支持的风格

| 类型 | 示例 |
|------|------|
| 基础情绪 | 开心、悲伤、愤怒、恐惧、兴奋、平静、冷漠 |
| 复合情绪 | 怅然、欣慰、无奈、愧疚、释然、动情 |
| 整体语调 | 温柔、高冷、活泼、严肃、慵懒、深沉、干练 |
| 音色质感 | 磁性、醇厚、清亮、空灵、甜美、沙哑 |
| 人设腔调 | 夹子音、御姐音、正太音、大叔音、台湾腔 |
| 方言 | 粤语、四川话 |
| 角色扮演 | 孙悟空、林黛玉 |
| 唱歌 | 唱歌 |

风格可以在设置中选择默认风格，也可以在合成时通过风格标签指定，格式为 `(风格)文本`。

## Token Plan 节点

如果使用 Token Plan，可选择以下节点：

| 节点 | URL |
|------|-----|
| 中国区 | `https://token-plan-cn.xiaomimimo.com` |
| 新加坡 | `https://token-plan-sgp.xiaomimimo.com` |
| 欧洲 | `https://token-plan-ams.xiaomimimo.com` |

> Token Plan 可能仅限用于编程开发场景，将其接入第三方应用进行语音合成可能违反小米服务条款，导致账号被封禁。建议使用按量计费模式。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **存储**: Room + DataStore
- **网络**: OkHttp
- **音频**: Android AudioTrack
- **最低版本**: Android 8.0 (API 26)

## 构建

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需配置签名）
./gradlew assembleRelease
```

## 免责声明

本软件为开源项目，仅供学习和个人使用，严禁用于任何违法违规用途。使用本软件即表示您已阅读并同意 [MiMo 用户协议](https://platform.xiaomimimo.com/docs/terms/user-agreement) 和 [MiMo 隐私政策](https://privacy.mi.com/XiaomiMiMoPlatform/zh_CN/)。

## 致谢

- [MiMo TTS](https://platform.xiaomimimo.com?ref=S5T7WV) — 小米 MiMo 语音合成 API
