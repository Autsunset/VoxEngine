# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VoxEngine is an Android TTS (Text-to-Speech) engine app with a pluggable engine architecture. It registers as a **system-level TTS service** via Android's `TextToSpeechService`, allowing other apps (e.g., Legado reading app) to use it directly for speech synthesis. The primary engine is MiMo (小米 MiMo TTS), with stubs for OpenAI and Edge TTS.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Build release (unsigned, minify disabled)
./gradlew assembleRelease

# Clean
./gradlew clean
```

No test suite exists in this project. No linting configuration beyond Android defaults.

## Architecture

### Pluggable Engine System

The core abstraction is `TTSEngine` (`engine/TTSEngine.kt`) — all TTS providers implement this interface. `EngineRegistry` (`engine/EngineRegistry.kt`) is a singleton map of `id -> TTSEngine`.

Engines are registered at app startup in `VoxEngineApplication.registerEngines()`. Currently three engines exist:
- **MiMoEngine** (`engine/mimo/`) — fully implemented, supports voice clone and voice design
- **OpenAIEngine** (`engine/openai/`) — stub, all methods throw `NotImplementedError`
- **EdgeTTSEngine** (`engine/edge/`) — stub, all methods throw `NotImplementedError`

### MiMo API Integration

`MiMoTTSClient` (`engine/mimo/MiMoModels.kt`) wraps the HTTP client. The API uses an **OpenAI-compatible chat completions format** at `{baseUrl}/v1/chat/completions` with three model variants:
- `mimo-v2.5-tts` — preset voices
- `mimo-v2.5-tts-voiceclone` — cloned voices (voice param is base64 audio)
- `mimo-v2.5-tts-voicedesign` — designed voices (voice param is text description)

Style tags are prepended to assistant content as `(风格)文本`. Auth uses `api-key` header.

### Android TTS Service Integration

`VoxEngineTTSService` (`tts/VoxEngineTTSService.kt`) extends `TextToSpeechService`. It bridges Android's TTS framework to the engine system. The `tts/` package also contains `CheckVoiceData`, `GetSampleText`, and `InstallVoiceData` activities required by the TTS engine contract.

The service is declared in `AndroidManifest.xml` with `BIND_TEXT_TO_SPEECH_SERVICE` permission and `tts_engine.xml` metadata.

### Data Layer

- **Room** (`data/`): `AppDatabase` with two tables — `voices` (custom clone/design voices) and `synthesis_history`. Uses `fallbackToDestructiveMigration()`.
- **DataStore** (`SettingsRepository`): Stores all user preferences (API key, base URL, default voice/style, speed, bridge settings, dark mode, current engine). Per-engine config uses `{engineId}_{key}` naming convention.

### UI

Jetpack Compose with three screens via bottom navigation:
- **SettingsScreen** — engine config, API keys, bridge settings
- **VoiceManageScreen** — manage custom voices (clone/design)
- **TestScreen** — test synthesis with selected voice/style

Navigation defined in `ui/navigation/NavGraph.kt` and `Screen.kt`.

### Audio Pipeline

`AudioUtils` (`audio/AudioUtils.kt`) handles WAV header parsing and PCM extraction. `AudioCache` (`engine/AudioCache.kt`) provides an in-memory LRU cache (50 entries, 5 min TTL) keyed by MD5 of `text|voice|style`.

## Key Conventions

- All engine implementations receive `SettingsRepository` via constructor injection
- Custom voices are matched by `name` in the Room database during synthesis
- The app uses `VoxEngineApplication.instance` as a global application reference for database access within engines
- Kotlin coroutines throughout; `runBlocking` is used in the TTS service (required by Android's synchronous TTS callback interface)
- No dependency injection framework — manual construction in `VoxEngineApplication`
