# AGENTS.md — Developer & AI Agent Guide for LiteChat (LocalGPT)

Panduan arsitektur, standar pengembangan, alur kerja, dan instruksi operasional untuk AI Agent maupun pengembang manusia yang bekerja pada repositori **LiteChat** (`com.localgpt.app`).

---

## 📌 1. Ikhtisar Proyek (Project Overview)

**LiteChat** (paket: `com.localgpt.app`) adalah aplikasi Android mandiri (*100% On-Device AI / LM Studio Mini*) yang memanfaatkan **Google AI Edge LiteRT-LM** (`com.google.ai.edge.litertlm`) untuk menjalankan model bahasa besar (LLM seperti Gemma 4, SmolLM, Llama) secara lokal dengan akselerasi hardware GPU (Adreno / Mali via OpenCL) dan fallback CPU (Arm NEON / XNNPack).

Selain berfungsi sebagai antarmuka obrolan AI *offline*, LiteChat menyematkan server HTTP kompatibel OpenAI (`/v1`) berbasis **Ktor CIO** yang memungkinkan aplikasi desktop, web, atau IDE (seperti VS Code Continue, OpenWebUI, SillyTavern) menggunakan smartphone Android sebagai backend mesin inferensi LLM lokal melalui jaringan Wi-Fi/LAN.

---

## 🏗️ 2. Arsitektur & Struktur Direktori

```
app/src/main/java/com/localgpt/app/
├── MainActivity.kt                      # Entry point Compose & pemantau izin notifikasi
├── core/
│   ├── engine/
│   │   └── LiteRtEngineManager.kt       # JNI Bridge ke LiteRT-LM runtime, Load/Unload RAM, telemetry
│   └── server/
│       ├── OpenAiServer.kt              # Ktor embedded server (/v1/chat/completions, /v1/completions, CORS)
│       ├── ChatServerService.kt         # Foreground Service & sticky notification server
│       ├── PromptBuilder.kt             # Serializer multi-turn conversation & system prompt
│       └── SseProtocol.kt               # Format SSE chunks untuk streaming token
├── localai/
│   ├── HuggingFaceModelResolver.kt      # HF repo scraper, file filter (.litertlm), token gated auth
│   ├── LocalAiCatalog.kt                # Katalog model bawaan terverifikasi (SmolLM, Gemma, dll)
│   ├── LocalModelDownloader.kt          # Downloader HTTP resumeable via OkHttp
│   └── LocalModelManager.kt             # Storage & SAF file import manager
├── data/
│   ├── ChatRepository.kt                # Penyimpanan percakapan JSON di filesDir/chats/
│   └── SettingsRepository.kt            # Preferensi & konfigurasi via Jetpack DataStore
├── ui/
│   ├── chat/
│   │   ├── ChatScreen.kt                # Layar utama obrolan, Context Bar, Persona Carousel, TopBar
│   │   ├── ChatViewModel.kt             # State management, TTS, Benchmark, Model management
│   │   └── Sheets.kt                    # Modal sheets: Models, Server Control, History, Settings, Benchmark, Logs
│   ├── models/
│   │   ├── LocalAiModelCards.kt         # Modular cards (Storage, Active Downloads, Installed, Presets, SAF)
│   │   └── LocalAiParametersTab.kt      # Kontrol Temperature, Top-K, Top-P, dan Hardware Acceleration
│   ├── component/
│   │   ├── MarkdownText.kt              # Parser Markdown + Card Code Block (Copy feedback)
│   │   └── AccentColorRow.kt            # Pemilih warna aksen dinamis Material 3
│   └── theme/
│       ├── Theme.kt                     # Dynamic theming via MaterialKolor + Pure Black OLED
│       └── Type.kt                      # Tipografi Jetpack Compose
└── util/
    ├── KLog.kt                          # Logger sistem
    └── NetworkUtils.kt                  # Deteksi otomatis alamat IP Wi-Fi/LAN perangkat
```

---

## ⚙️ 3. Konsep Inti & Panduan Komponen

### A. LiteRT-LM Engine Lifecycle ([`LiteRtEngineManager.kt`](file:///home/kouzen/Documents/Projects/newpj/app/src/main/java/com/localgpt/app/core/engine/LiteRtEngineManager.kt))
- **Thread Safety**: Google AI Edge LiteRT `Engine` tidak mendukung eksekusi prompt secara konkuren. Seluruh proses inferensi disinkronkan melalui `kotlinx.coroutines.sync.Mutex`.
- **Eksplisit Load / Unload**:
  - `load(params)`: Mengompilasi shader OpenCL / memuat model ke RAM/VRAM.
  - `unload()`: Memanggil `Engine.close()` untuk mengembalikan RAM perangkat saat model tidak aktif.
- **Pembatalan Generasi**: Memanfaatkan `Conversation.cancelProcess()` untuk membatalkan proses generasi teks secara instan tanpa membuat native bridge *crash*.
- **Metrik Inferensi**: Menghitung *Time to First Token (TTFT ms)*, *Tokens per second (tok/s)*, dan mencatat telemetry log ke disk cache.

### B. Embedded OpenAI-Compatible Server ([`OpenAiServer.kt`](file:///home/kouzen/Documents/Projects/newpj/app/src/main/java/com/localgpt/app/core/server/OpenAiServer.kt))
- **Endpoint yang Didukung**:
  - `POST /v1/chat/completions`: Streaming SSE (`stream: true`) atau single JSON response (`stream: false`).
  - `POST /v1/completions`: Raw text completion untuk extension IDE.
  - `GET /v1/models` & `GET /v1/models/{model}`: Metadata model aktif.
  - `GET /health`: Health check string.
- **CORS Penuh**: Menyediakan penanganan preflight `OPTIONS` global dan header `Access-Control-Allow-Origin: *` agar Web App di browser laptop/komputer dapat memanggil server HP tanpa hambatan.
- **Deteksi LAN IP**: Menggunakan [`NetworkUtils.kt`](file:///home/kouzen/Documents/Projects/newpj/app/src/main/java/com/localgpt/app/util/NetworkUtils.kt) untuk menampilkan URL lokal langsung (`http://192.168.x.x:8080/v1`).

### C. Fitur Khas LM Studio di Antarmuka
1. **Speed Benchmark Tool (`⚡`)**:
   - Diakses dari tombol petir di TopBar.
   - Menguji performa LLM dengan metrik standar (TTFT, decode tok/s, durasi) dan perbandingan GPU vs CPU.
2. **Custom AI Persona (`+ Persona`)**:
   - Memungkinkan pengguna membuat persona kustom (Emoji, Nama, Instruksi Sistem) yang disimpan secara permanen di DataStore.
3. **Context Window Token Bar**:
   - Menampilkan rasio token yang terpakai dalam percakapan aktif (`Context: X / Y tok · Z%`) dengan peringatan visual adaptif.
4. **Offline Text-to-Speech (TTS) & Regenerate**:
   - Pembacaan respon AI via `android.speech.tts.TextToSpeech` dan tombol coba ulang generasi.

---

## 🛠️ 4. Perintah Build, Test & Distribusi

### Menjalankan Pengujian Unit (Unit Tests)
```bash
./gradlew testDebugUnitTest
```

### Mengompilasi Debug APK
```bash
./gradlew assembleDebug
```
*Output: `app/build/outputs/apk/debug/app-debug.apk`*

### Mengompilasi Signed Release APK (Optimasi arm64-v8a)
```bash
./gradlew assembleRelease -PabiFilter=arm64-v8a
```
*Output: `app/build/outputs/apk/release/app-arm64-v8a-release.apk` (atau `./LiteChat-arm64-v8a-release.apk`)*

> [!NOTE]
> Parameter `-PabiFilter=arm64-v8a` memastikan APK hanya memaketkan pustaka *native* 64-bit ARM untuk menghasilkan ukuran file minimal (~24 MB) dengan performa maksimal.

### Mengirim APK ke Smartphone via KDE Connect CLI
```bash
# 1. Cari Device ID yang terhubung
kdeconnect-cli -l

# 2. Kirim berkas APK ke perangkat
kdeconnect-cli --device <DEVICE_ID> --share ./LiteChat-arm64-v8a-release.apk
```

### Memasang APK via ADB (USB / Wireless)
```bash
# Mode Kabel USB
adb install -r LiteChat-arm64-v8a-release.apk

# Mode Wireless Debugging (Android 11+)
adb connect <IP-HP>:<PORT>
adb install -r LiteChat-arm64-v8a-release.apk
```

---

## 📐 5. Konvensi Koding & Panduan Kontribusi

1. **Jetpack Compose & Material 3**:
   - Gunakan komponen Material 3 Expressive (`FilterChip`, `ModalBottomSheet`, `AssistChip`, `CardDefaults`).
   - Terapkan `Modifier.imePadding()` pada area input composer untuk menangani keyboard secara halus.
   - Hindari hardcoded colors; gunakan `MaterialTheme.colorScheme` untuk mendukung tema gelap, terang, dan OLED Pure Black secara dinamis.
2. **Pengelolaan State**:
   - Gunakan `StateFlow` dan `collectAsState()` untuk reaktivitas state asinkron dari repository/service.
   - Pastikan operasi IO berat (pembacaan berkas model, parsing JSON, download) selalu dijalankan pada `Dispatchers.IO`.
3. **ProGuard & R8**:
   - Setiap data class baru yang diserialisasi ke JSON (via Gson) atau berinteraksi dengan native JNI harus didaftarkan di [`proguard-rules.pro`](file:///home/kouzen/Documents/Projects/newpj/app/proguard-rules.pro) dengan tag `-keep class ...`.
