# PLAN.md — Catatan Arsitektur (jangan hilang)

> Aplikasi Android "LM Studio mini": **chat AI on-device via LiteRT-LM** + **server OpenAI-compatible `/v1`**
> yang bisa dipakai aplikasi lain (device sama via `127.0.0.1`, atau LAN via `0.0.0.0`).
>
> Nama project: **TBD placeholder "LiteChat"** — applicationId `com.localgpt.app` (mudah diganti).
> Sumber inspirasi/porting: `/home/kouzen/Documents/Projects/kzkt`.

---

## 1. Keputusan desain (final)

| Topik | Keputusan | Alasan |
|---|---|---|
| Engine inference | `com.google.ai.edge.litertlm:litertlm-android:0.16.0` | Sudah terbukti di kzkt; sudah ter-cache Gradle |
| HTTP server | **Ktor Server CIO** (`ktor-server-core` + `ktor-server-cio`) 3.x | Coroutine-native, SSE bila native; satu-satunya dep baru (~5–10 MB) |
| Persistence chat | **File JSON per percakapan** di `filesDir/chats/` + index, via kotlinx.serialization | Tanpa Room/KSP (Room cache PC ini tidak lengkap; hindari risiko AGP 9 + KSP) |
| Settings | DataStore Preferences | Pola kzkt, sudah ter-cache |
| DI | Tidak ada — singleton manual (`getInstance`), VM AndroidViewModel | Pola kzkt |
| Streaming token UI | `SharedFlow`/callback dari engine → coalescer 33 ms (~30 Hz) ke snapshot state | Template `MainViewModel.post()` kzkt |
| Konversi stateless↔stateful | Per request `/v1`: buat `Conversation` BARU, replay `messages[]` jadi 1 prompt berpenanda role | API OpenAI stateless vs Conversation stateful; aman dari context overflow |
| Concurrency | Mutex tunggal di engine manager; request UI & server mengantre | LLM tidak bisa paralel sungguhan |
| Lifecycle server | Foreground Service (`startForeground` + notifikasi) | Kebal Doze; stop service = unload engine + release socket |

## 2. Audit environment PC (2026-08-23)

- JDK 17.0.19 ✅ · SDK hingga android-37.0 ✅ · Gradle wrapper 9.6.1 (cache) ✅
- Toolchain target = persis kzkt: **AGP 9.3.0, Kotlin 2.4.10 (built-in AGP), compileSdk 37, minSdk 26, targetSdk 36**, Compose BOM `2026.01.01`, Material3 `1.5.0-alpha25`, material-kolor 5.0.0
- Cache Gradle ada: litertlm-android, coroutines, serialization-core, okhttp3, datastore, room-runtime (TANPA compiler)
- Cache Gradle TIDAK ada: Ktor, NanoHTTPD → unduhan baru saat build pertama

## 3. Struktur modul (single `:app`, gaya kzkt)

```
app/src/main/java/com/localgpt/app/
├── core/
│   ├── engine/
│   │   ├── LiteRtEngineManager.kt   # port LiteRtInferenceEngine.kt kzkt
│   │   │   # singleton + mutex; load/unload; GPU(OpenCL)->CPU(XNNPack) fallback
│   │   │   # BARU: generateResponseStream(prompt): Flow<String> (sendMessageAsync)
│   │   └── EngineParams.kt          # modelPath/temp/topK/maxTokens/backend
│   └── server/
│       ├── ChatServerService.kt     # foreground service hosting embedded Ktor
│       ├── OpenAiApi.kt             # GET /v1/models, POST /v1/chat/completions (stream+non)
│       ├── SseProtocol.kt           # Flow<token> -> chunk SSE {"delta":{"content"}} + [DONE]
│       └── PromptBuilder.kt         # messages[] -> prompt single-shot berpenanda role
├── data/
│   ├── SettingsRepository.kt        # DataStore: theme, litert_* params, server prefs
│   └── ChatRepository.kt            # JSON filesDir/chats/{id}.json + index
├── localai/
│   ├── LocalModelCatalog.kt         # preset .litertlm HF litert-community (decoupled dari manga)
│   ├── LocalModelManager.kt         # storage getExternalFilesDir("models"), scan, import SAF
│   ├── ModelDownloader.kt           # downloader + progress StateFlow
│   ├── DownloadService.kt           # foreground download notification
│   └── HuggingFaceResolver.kt       # resolve repo URL -> file .litertlm (+token auth)
├── util/
│   └── KLog.kt                      # ring-buffer 500 + persist disk + reload antar-sesi
└── ui/
    ├── theme/                       # port Theme.kt + Type.kt (MaterialKolor, pure-black, Inter)
    ├── chat/
    │   ├── ChatScreen.kt            # transcript LazyColumn bubble + composer + tombol stop
    │   ├── ChatViewModel.kt         # SharedFlow token -> coalescer 33ms -> list message
    │   └── MessageBubble.kt         # render via MarkdownText (port), alignment user/asisten
    ├── models/                      # port trio LocalAiManagementDialog/ModelCard/ParametersTab
    ├── server/
    │   └── ServerPanel.kt           # toggle start/stop, port, bind mode, alamat LAN, auth token
    ├── history/                     # list riwayat chat + swipe-delete + snackbar undo (pola HistoryScreen)
    └── component/                   # primitif generic: ChipsRow, StatusChip, ParameterSlider, LogSheet(unified)
```

## 4. Alur data

```
A) Chat UI : Composer -> ChatViewModel -┐
                                        +- [Mutex] LiteRtEngineManager -> Flow<token>
B) App lain: POST /v1/chat/completions -> Ktor(CIO) -> SseProtocol -+
             -> coalescer 33ms -> bubble UI / chunk SSE -> klien
```

Endpoint:
- `GET /v1/models` → daftar model aktif (format OpenAI)
- `POST /v1/chat/completions` → `stream:true` = SSE; `stream:false` = JSON tunggal
- Auth opsional: `Authorization: Bearer <token>` jika diisi di settings

## 5. Checklist porting dari kzkt (path sumber)

| Sumber (kzkt) | Tujuan (newpj) | Catatan adaptasi |
|---|---|---|
| `ui/theme/Theme.kt`, `Type.kt`, res/font Inter* | sama | ganti package; buang ColorSaver custom-font import dulu |
| `util/KLog.kt` | `util/KLog.kt` | ganti package saja |
| `core/localai/LiteRtInferenceEngine.kt` | `core/engine/LiteRtEngineManager.kt` | tambah jalur streaming Flow; buang log domain manga |
| `core/localai/LocalModelManager.kt`, `LocalAiModel.kt`, `LocalAiModelDownloader.kt`, `ModelDownloadService.kt`, `HuggingFaceModelResolver.kt` | `localai/*` | decouple dari SettingsRepository kzkt → interface/prefs sendiri |
| `ui/component/LocalAiManagementDialog.kt` + `LocalAiModelCard.kt` + `LocalAiParametersTab.kt` | `ui/models/*` | ~1.440 LOC; callback-driven, pangkas referensi manga |
| `ui/component/MarkdownText.kt` | `ui/component/MarkdownText.kt` | nambah code-fence block parsing (opsional) |
| pola log sheet (TranslationLog/AppLogs/LiteRtLogs — 3 duplikat) | SATU `LogSheet` unified | perbaikan atas tech-debt kzkt |
| Snackbar optimistic-delete+Undo (HistoryScreen) | history chat | salin idiom |
| `MainViewModel.post()` coalescer 33 ms | ChatViewModel | inti streaming mulus |
| `SseParser.kt` (referensi format) | tidak dipakai langsung | acuan balik: kzkt CustomProvider bisa jadi klien uji |

Skip total: pipeline manga, editor, reader, OCR/YOLO, onboarding.

## 6. Risiko & catatan teknis

- **Cleartext HTTP**: Android 9+ blok plaintext → butuh `networkSecurityConfig` allow cleartext (untuk mode LAN; 127.0.0.1 juga kena aturan ini di beberapa konfigurasi).
- **Klien app lain harus dukung custom base URL** (kzkt ✅ via CustomProvider/OpenAI-compat).
- **Auth default kosong** = siapa pun di LAN bisa pakai saat bind 0.0.0.0 → sarankan isi token.
- **Context window**: replay messages bisa meluap pada chat panjang → PromptBuilder memotong dari tengah (simpan system + pesan terbaru).
- **litertlm 0.16.0**: `conversation.sendMessageAsync(text): Flow<String>`; `Engine.setNativeMinLogSeverity`; backend `Backend.GPU()/CPU()/NPU(nativeLibraryDir)`; `cacheDir` mempercepat load ke-2.
- Model hanya `.litertlm` (bukan GGUF); vision hanya model tertentu → v1 text-only.
- ProGuard/R8: cek rules litertlm & Ktor saat release build.

## 7. Roadmap implementasi

- [x] Riset kzkt (provider arch + frontend) — selesai 2026-08-23
- [x] Rancang arsitektur — dokumen ini
- [x] Fase 1: scaffold gradle + shell app jalan (assembleDebug OK, APK 69 MB)
- [x] Fase 2: port theme + KLog + settings
- [x] Fase 3: port localai stack + engine manager streaming (`streamResponse(): Flow<String>`)
- [x] Fase 4: server /v1 (Ktor CIO 3.5.2 + SSE + ChatServerService foreground)
- [x] Fase 5: chat UI fungsional minimal (transcript, composer, streaming, persist JSON)
- [x] Fase 6: model manager UI minimal (ModelsSheet: preset/install/import/tuning) — versi polished menyusul
- [x] Fase 7: history sheet + auto-scroll cerdas + cancel native generation (`Conversation.cancelProcess`)
- [ ] Fase 8: uji end-to-end di device

## Status build pertama (2026-08-23)

`./gradlew assembleDebug` ✅ — `app/build/outputs/apk/debug/app-debug.apk` (69 MB,
`liblitertlm_jni.so` ter-bundle utk arm64-v8a/armeabi-v7a/x86/x86_64).
`./gradlew assembleRelease` ✅ — R8 minify+shrink jalan (49 MB), proguard rules litertlm/Ktor/Gson aman.
`./gradlew testDebugUnitTest` ✅ — 9/9 (PromptBuilderTest ×4, SseProtocolTest ×5).

Catatan perbaikan saat build pertama: tambah OkHttp (downloader), import `routing.post`
(bukan `request.post`), rename param ViewModel agar tak bentrok dgn properti privat
`AndroidViewModel.application`.

Deviasi kecil dari kzkt (disengaja): temperature TIDAK di-clamp min 0.30; sampler
memakai nilai settings apa adanya (chat butuh temp rendah untuk output stabil).

## Checklist QA manual Fase 8 (butuh device Android)

1. Install debug APK → buka → Models tab → download Qwen3-0.6B (585 MB, tercepat)
2. Set Active → chat "Halo, perkenalkan dirimu" → token harus muncul progresif (streaming)
3. Tekan Stop saat generate → generasi berhenti native, partial text tersimpan
4. Server sheet (ikon DNS) → Start Server → status "Running" + alamat 127.0.0.1:8080/v1
5. Uji curl dari app lain / adb:
   `adb shell curl -s http://127.0.0.1:8080/v1/models`
   `adb shell curl -N -X POST http://127.0.0.1:8080/v1/chat/completions -d '{"messages":[{"role":"user","content":"hi"}],"stream":true}'`
6. kzkt sebagai klien: Settings → Custom/OpenAI-compat provider → base URL
   `http://127.0.0.1:8080/v1`, API key kosong → translate satu halaman
7. LAN mode: aktifkan bind-all, uji dari laptop `curl http://<phone-ip>:8080/v1/models`
8. History: tutup app, buka lagi → Chats (ikon history) → load percakapan lama
9. GPU→CPU fallback: pilih backend GPU di device tanpa OpenCL → log menunjukkan fallback

## Known limitations v1

- Stop generation membatalkan request aktif; request server yang sedang jalan juga ikut
  mutex queue (klien kedua menunggu giliran) — by design
- Markdown renderer masih minimal (tanpa code-fence block); tingkatkan belakangan
- Belum ada i18n; string hardcoded English
- Model manager UI belum se-polish trio dialog kzkt (tanpa HF custom URL resolver UI)
