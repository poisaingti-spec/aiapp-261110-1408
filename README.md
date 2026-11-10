# AI App (v0.15.0)

Android AI assistant, Android Studio app builder, and GitHub Codespaces / ChatGPT Codex workspace (Kotlin + Jetpack Compose).

## What's New in v0.15.0

1. **Fixed ChatGPT Codex & GitHub Codespace Sign-In (`GitHubLogin.kt`, `GitHubClient.kt`, `CodespaceScreen.kt`, `AndroidManifest.xml`, `OpenAiProvider.kt`)**:
   - Added `aiapp://` OAuth redirect URI intent filter and `MainActivity.onNewIntent` token capture.
   - Added in-app GitHub Device Flow auto-copy + auto-fill inside `CodespaceScreen.kt`, resilient background token polling that survives network switches and HTTP 5xx, URL-encoded & JSON token parsing, and `repo workflow codespace read:user` scopes.
   - Enabled third-party cookies (`CookieManager.setAcceptThirdPartyCookies`), DOM storage, multi-window OAuth popup dialogs (`onCreateWindow`), and an OAuth-safe Chrome mobile User-Agent so Google/GitHub/OpenAI login flows never fail inside the WebView.
   - Enabled ChatGPT Codex models in `OpenAiProvider.kt` with automatic `/v1/responses` endpoint fallback.

2. **Fixed Codespace Reloading & "Failed to fetch" Error on Backgrounding (`CodespaceScreen.kt`, `Http.kt`, `Retry.kt`, `MainActivity.kt`)**:
   - Added `CodespaceSessionManager` to retain the active `WebView` instance and its `Bundle` state (`saveState` / `restoreState`) across screen switches, orientation changes, and backgrounding without calling `pauseTimers()` or tearing down active WebSockets.
   - Added `ConnectivityManager.NetworkCallback` and non-destructive JS keep-alive/reconnection (`online`/`focus` event dispatch and visibility-change protection) so returning from background never wipes the active Codespace or triggers "Failed to fetch".
   - Hardened `Http.kt` (HTTP/2 `pingInterval`, `ConnectionPool`) and `Retry.kt` (automatic retry on transient `IOException` / socket drops).

3. **Added Visible, Functional Back Button in `CodespaceScreen.kt`**:
   - Added a top `TopAppBar` with a dedicated **Back** button (`ArrowBack`) and **Exit** button that return immediately to the previous screen, a `◄ Page` button for in-WebView history, workspace switcher chips (`GitHub Codespaces`, `ChatGPT Codex`, `My Codespaces API`, `GitHub Sign-In`), and `BackHandler` system back navigation.

4. **Integrated Free, Download-and-Use Unrestricted Coding & Android App-Building Models (`OfflineProvider.kt`, `ChatScreen.kt`, `BuildScreen.kt`, `SettingsScreen.kt`)**:
   - Added top-tier ungated `.litertlm` coding and unrestricted models that download and run directly on the phone with **zero API keys and no website visits**:
     - **★ Qwen 2.5 Coder 3B Instruct** (`litert-community/Qwen2.5-Coder-3B-Instruct`, 3.4 GB)
     - **★ Gemma 4 E4B Abliterated (Unrestricted · Coding & Agent)** (`olekk/gemma-4-E4B-it-abliterated-litert-lm`, 3.7 GB)
     - **★ DeepSeek R1 Distill Qwen 7B (Max Reasoning & Code)** (`litert-community/DeepSeek-R1-Distill-Qwen-7B`, 4.2 GB)
     - **★ Gemma 4 E2B Uncensored-MAX (Unrestricted · 4 GB+ RAM)** (`PeppX/gemma-4-e2b-uncensored-litertlm`, 2.4 GB)
     - **★ Qwen 2.5 Coder 1.5B Uncensored** (`Shaurya2020/Qwen2.5-1.5B-Instruct-uncensored-litert`, 1.7 GB)
     - **Qwen 2.5 Coder 1.5B Int4 (Fastest Coding · Any Phone)** (`litert-community/Qwen2.5-Coder-1.5B-Instruct`, 1.1 GB)
     - **DeepSeek R1 Distill Qwen 1.5B** (`litert-community/DeepSeek-R1-Distill-Qwen-1.5B`, 1.8 GB)
   - Added one-tap **Download & Use** cards directly on the Chat and Build screens, plus automatic on-demand downloading when a prompt or build is started.

## Building the APK Without a PC

- **Option 1 (Inside your existing AI App on Android)**: Open the **Build** tab → tap **Upload Android Studio project (.zip) → APK** and pick this `.zip` file. It will compile on GitHub Actions and give you a direct installable APK link.
- **Option 2 (GitHub Actions)**: Push or upload these files to your GitHub repo (`main` branch). `.github/workflows/build-apk.yml` automatically builds `AIApp-N.apk` and publishes it under **Releases → latest**.
- **Option 3 (Android Studio)**: Open the unzipped folder in Android Studio and run **Build → Build APK(s)**.
