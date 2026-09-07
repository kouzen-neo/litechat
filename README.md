# LiteChat (LocalGPT)

LiteChat is a standalone, 100% on-device Android AI client and local inference server powered by **Google AI Edge LiteRT-LM** (`com.google.ai.edge.litertlm`). It runs large language models (such as Gemma 4, SmolLM, Llama, and Qwen) directly on smartphone hardware with GPU acceleration (OpenCL) and CPU fallback (Arm NEON / XNNPack).

LiteChat also embeds an OpenAI-compatible HTTP server (`/v1`) using **Ktor CIO**, turning your Android phone into a local LLM inference backend for desktop apps, web interfaces, and IDE extensions (e.g., VS Code Continue, OpenWebUI, SillyTavern) over Wi-Fi / LAN.

---

## Key Features

- **100% On-Device & Offline Inference**: Zero cloud dependencies or subscriptions. Models execute entirely in local RAM and VRAM.
- **Hardware Acceleration**: High-performance OpenCL GPU shader compilation with automatic CPU fallback.
- **Embedded OpenAI-Compatible HTTP Server**:
  - `POST /v1/chat/completions` (Server-Sent Events streaming chunks and standard JSON)
  - `POST /v1/completions` (Raw completion)
  - `GET /v1/models` and `GET /v1/models/{model}`
  - `GET /health`
  - Full CORS support for cross-origin browser and web app integration.
- **Material 3 Expressive Design System**:
  - Adaptive grouped cards and Material 3 controls.
  - Light, Dark, and Pure Black (#000000 OLED) theme modes.
  - Dynamic Material You tonal color palettes.
- **Hugging Face Model Manager**:
  - In-app repository scraper and model browser filtered for `.litertlm` binaries.
  - Resumeable chunked background downloader via OkHttp and Android Foreground Services.
  - SAF (Storage Access Framework) local file importer.
- **Interactive AI Tools**:
  - In-app speed benchmarking tool (TTFT, decode tokens per second, CPU vs. GPU comparison).
  - Custom AI Persona system with persistent system prompt templates.
  - Context window token consumption monitor.
  - Offline Text-to-Speech (TTS) and Voice Input (Speech-to-Text).
  - RAG document and code snippet context attachment.

---

## Architecture & Structure

```
app/src/main/java/com/localgpt/app/
├── MainActivity.kt                      # Compose entry point and notification observer
├── core/
│   ├── engine/
│   │   └── LiteRtEngineManager.kt       # JNI bridge to LiteRT-LM runtime, RAM lifecycle, telemetry
│   └── server/
│       ├── OpenAiServer.kt              # Ktor embedded server (/v1/chat/completions, CORS)
│       ├── ChatServerService.kt         # Foreground Service and sticky notification server
│       ├── PromptBuilder.kt             # Multi-turn conversation and system prompt serializer
│       └── SseProtocol.kt               # SSE chunk formatter for streaming tokens
├── localai/
│   ├── HuggingFaceModelResolver.kt      # HF repo scraper and .litertlm file filter
│   ├── LocalAiCatalog.kt                # Built-in verified model catalog
│   ├── LocalModelDownloader.kt          # Resumeable HTTP downloader
│   └── LocalModelManager.kt             # Storage and SAF file import manager
├── data/
│   ├── ChatRepository.kt                # JSON conversation tree storage
│   └── SettingsRepository.kt            # Jetpack DataStore preferences
├── ui/
│   ├── chat/                            # Main chat screen, composer, persona carousel, modal sheets
│   ├── models/                          # Model cards, parameters, downloader, SAF importer
│   ├── component/                       # Material 3 reusable components and Markdown parser
│   └── theme/                           # Dynamic theming and typography
└── util/
    ├── KLog.kt                          # Structured logging system
    └── NetworkUtils.kt                  # Local LAN / Wi-Fi IP address resolution
```

---

## Build & Installation

### Requirements

- Android Studio Ladybug / Meerkat or later
- JDK 17+
- Android SDK 35 / 36 / 37
- Android Device with ARM64 architecture (Android 8.0+ / API 26+)

### Run Unit Tests

```bash
./gradlew testDebugUnitTest
```

### Build Debug APK

```bash
./gradlew assembleDebug
```

*Output: `app/build/outputs/apk/debug/app-debug.apk`*

### Build Optimized Signed Release APK (arm64-v8a)

```bash
./gradlew assembleRelease -PabiFilter=arm64-v8a
```

*Output: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`*

### Install via ADB

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

---

## License

This project is licensed under the Apache License 2.0.
