# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VoxEngine is an Android TTS (Text-to-Speech) app with a pluggable engine architecture. It serves speech two distinct ways:

1. **System-level TTS service** via Android's `TextToSpeechService` — other apps (e.g. Legado/阅读) bind to it for synthesis.
2. **In-app 听书 (listen-to-book) reader** — imports local `.txt` novels and plays them aloud through a foreground service.

Engines: **MiMo** (小米 MiMo TTS, primary, full clone/design support) and **Edge** (free Microsoft Edge TTS). UI is Jetpack Compose.

## Build & Run

```bash
./gradlew assembleDebug        # debug APK
./gradlew installDebug         # install on connected device
./gradlew assembleRelease      # signed + minified + resource-shrunk release
./gradlew test                 # JVM unit tests (app/src/test)
./gradlew test --tests "com.voxengine.reader.TxtNovelParserTest"   # single test class
./gradlew clean
```

Release signing reads `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` from the environment and `../voxengine.jks`; release builds fail with null passwords if these are unset.

**Build resource caution:** Gradle builds have nearly frozen this machine. Heap is already capped (`org.gradle.jvmargs=-Xmx2048m`); prefer narrow tasks, pass `--max-workers=2` for full builds, and avoid redundant rebuilds.

Tests are JUnit4 JVM unit tests. They cover the **pure** logic only: `ReaderPlaybackPlanner`, `TxtNovelParser`, `TtsErrors`. No instrumented tests; no linter beyond Android defaults.

## Architecture

### Pluggable engine system

`TTSEngine` (`engine/TTSEngine.kt`) is the interface every provider implements. `EngineRegistry` (`engine/EngineRegistry.kt`) is a singleton `id -> TTSEngine` map. `EngineBootstrap.ensureRegistered()` registers engines **idempotently** and is called from *both* `VoxEngineApplication.onCreate()` and `VoxEngineTTSService.onCreate()` — the TTS service can be started by the system without the Activity, so it cannot assume the Application already registered engines.

- **MiMoEngine** (`engine/mimo/`) — full clone + design support.
- **EdgeTTSEngine** (`engine/edge/`) — free; `isConfigured()` is always true, but `cloneVoice`/`designVoice` throw `NotImplementedError`.

### MiMo API integration

`MiMoTTSClient` (`engine/mimo/MiMoModels.kt`) wraps OkHttp. The API is an **OpenAI-compatible chat-completions** call to `{baseUrl}/v1/chat/completions`, auth via `api-key` header, three model variants:
- `mimo-v2.5-tts` — preset voices (voice in `audio.voice`)
- `mimo-v2.5-tts-voiceclone` — clone (voice param is a base64 audio data URI)
- `mimo-v2.5-tts-voicedesign` — design (voice param is a text description)

Style is sent as a **natural-language instruction in the `user` message** (the text goes in the `assistant` message) — it is *not* prepended to the spoken text — so the model doesn't read the style word aloud.

### Edge TTS

`EdgeTTSClient` (`engine/edge/`) opens a WebSocket to Bing's `readaloud` endpoint, using a rolling `Sec-MS-GEC` SHA-256 token. The endpoint returns **MP3** (PCM is a paid-tier feature), so output is decoded by `Mp3Decoder` (`audio/`) to PCM and wrapped as WAV. On `403` it corrects local clock skew from the server `Date` header and retries once.

### Two playback paths (important — they are separate)

**A. System TTS path** — `VoxEngineTTSService` (`tts/`) extends `TextToSpeechService` and bridges Android's synchronous callback API to the engines. `runBlocking` is required here because `onSynthesizeText` is a blocking callback. When `parallelSynthesis` is on and the engine is MiMo, it uses `MiMoEngine.synthesizeStreaming` (bounded-concurrency sentence prefetch, written in original order for low first-byte latency); otherwise a single `synthesize`. PCM is run through `SpeedAdjuster` and written in 4 KB chunks. The `tts/` package also holds the `CheckVoiceData` / `GetSampleText` / `InstallVoiceData` activities the TTS engine contract requires.

**B. In-app 听书 path** — `ReaderPlaybackService` (`reader/`) is a `mediaPlayback` foreground service that synthesizes ahead of playback and plays via raw `AudioTrack`. It prefetches the rest of the current chapter (plus a growing window of the next chapter) as a **sequentially-chained** set of `Deferred`s, plays each chunk on `AudioTrack`, and persists progress per paragraph. Clone/design voices are throttled (`conservativeRequestIntervalMs`). It talks to `ReaderScreen` two ways: package-scoped **broadcasts** (`ACTION_PROGRESS` / `ACTION_PLAYBACK_STATE`) and a static `AtomicReference<PlaybackSnapshot>` (`getPlaybackSnapshot`) so a freshly-opened screen can resync.

### Reader subsystem (`reader/`)

- `TxtNovelParser` — charset detection/decode, chapter splitting, and `paginate()`. Defines `TxtChapter` / `TxtPage`.
- `ReaderPlaybackPlanner` — **pure, tested** chunking/prefetch logic (`splitTextForTts`, `buildPrefetchWindow`, `normalizePosition`, `nextPosition`). ⚠️ `ReaderPlaybackService` currently keeps its **own private duplicate** of these functions and the `MIN/MAX_TTS_CHUNK_CHARS` constants rather than delegating to the planner — the code that *runs* is not the code that's *tested*. Keep them in sync, or refactor the service to call the planner.
- `ReaderChapterCache` (in `ReaderMeasuredPageCache.kt`) and `ReaderMeasuredPageCache` — in-memory caches keyed by book URI. Pagination is **measured in Compose** with `TextMeasurer` against the real viewport (`ReaderScreen.measurePagesForViewport`), then fed back to the service via `ReaderMeasuredPageCache` so on-screen pages and spoken pages match.

### Data layer

- **Room** `AppDatabase` (`data/`), version **4**, four tables: `voices`, `synthesis_history`, `reader_books`, `reader_chapters`. Uses **real `Migration`s (1→2→3→4)**, not destructive fallback — bump the version and add a migration for any schema change. `MIGRATION_1_2` uses an `addColumnIfMissing` PRAGMA guard.
- **DataStore** `SettingsRepository` (`data/`) — all preferences (API key, base URL, voice/style/speed, dark mode, current engine, parallel/concurrency, reader gap/sleep/retry settings). Backed by a top-level `by preferencesDataStore` delegate, so constructing multiple `SettingsRepository`s is safe. Per-engine config uses the `{engineId}_{key}` key convention.

### Audio & caching

- `AudioUtils` (`audio/`) — WAV header parse / PCM extract / `pcmToWav`.
- `SpeedAdjuster` — PCM time-stretch for the system-TTS speed control.
- `AudioCache` (`engine/`) — global in-memory LRU **bounded by bytes (32 MB)** with a 5-minute TTL, keyed by MD5 of `version|engineId|voiceFingerprint|text|style`. The voice fingerprint includes custom-voice param hash + createdAt so re-cloned voices don't collide.

### Cross-cutting utilities (`util/`)

- `RetryPolicy.withRetry` — exponential backoff. `isRetryable` currently matches only `429` (by message substring) or `IOException`.
- `LogManager` — persistent 7-day rotating file logs under `filesDir/logs`, with base64/audio-data redaction and line truncation; surfaced in `LogScreen`.
- `SpeechTextNormalizer` (text cleanup before synthesis), `TtsErrors.friendly` (user-facing error strings).

### UI

Jetpack Compose, bottom-nav (`ui/navigation/`). Screens: `SettingsScreen`, `VoiceManageScreen` (clone via in-app `AudioRecord` recording or file pick), `TestScreen`, `ReaderScreen` (bookshelf + reader), `LogScreen`, `AboutScreen`. `ReaderScreen` is the most complex; its state and side-effects (parsing, position/progress, listening control, playback-snapshot sync, reader settings) live in **`ReaderViewModel`** (`ui/screens/ReaderViewModel.kt`, an `AndroidViewModel` exposing a single `ReaderUiState` StateFlow — the project's only ViewModel, so reader state survives config changes). The composable keeps only composition-bound state: menu/panel visibility, the playback `BroadcastReceiver`, and the `TextMeasurer`/`BoxWithConstraints` viewport pagination (which feeds measured pages back via `viewModel.onPagesMeasured`). Other screens still hold their state inline.

## Key Conventions

- **Never declare `android:permission` on `VoxEngineTTSService`.** `BIND_TEXT_TO_SPEECH_SERVICE` is not a real Android permission; adding it makes callers (Legado, etc.) unable to bind and throws `SecurityException` on strict ROMs (MIUI / Android 11). The service stays `exported` with no permission — see the comment in `AndroidManifest.xml`.
- Engines receive `SettingsRepository` via constructor; there is **no DI framework** — construction is manual in `VoxEngineApplication` / `EngineBootstrap`.
- Engines reach the database through the `VoxEngineApplication.instance` global.
- Custom voices are matched by **name** in Room during synthesis (`getVoiceByEngineAndName`).
- `runBlocking` is acceptable only on the TTS service's blocking callback thread. Avoid adding main-thread `runBlocking` elsewhere (it already appears in `VoxEngineApplication.applySavedNightMode` and `MiMoEngine.isConfigured`).
- Kotlin coroutines throughout.
