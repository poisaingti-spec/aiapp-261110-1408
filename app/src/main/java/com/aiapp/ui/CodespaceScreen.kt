package com.aiapp.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiapp.builder.GitHubClient
import com.aiapp.builder.GitHubLogin
import com.aiapp.data.ApkInstaller
import com.aiapp.llm.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Mobile workspace pane modes so VS Code Web is never squeezed into 3 tiny columns on a phone:
 * - `SPLIT`: Default responsive layout (or landscape multi-pane)
 * - `AGENT`: Full-screen Agent / Copilot Chat panel (100% width)
 * - `FILES`: Full-screen Explorer File Tree (100% width)
 * - `CODE`: Full-screen Code Editor (hides left/right sidebars so code has 100% width)
 * - `TERMINAL`: Full-screen / maximized Terminal panel
 */
enum class MobilePaneMode(val id: String, val label: String) {
    AGENT("agent", "🤖 Agent"),
    FILES("files", "📁 Files"),
    CODE("code", "📝 Code"),
    TERMINAL("terminal", "💻 Term"),
    SPLIT("split", "🖥 Split"),
}

/**
 * Retains the active Codespace / ChatGPT Codex WebView instance, its session state, and its native
 * file/APK download bridge (`AndroidCodespaceBridge`) across configuration changes, screen switches,
 * and app backgrounding.
 */
object CodespaceSessionManager {
    const val URL_GITHUB_CODESPACES = "https://github.com/codespaces"
    const val URL_GITHUB_NEW_CODESPACE = "https://github.com/codespaces/new"
    const val URL_GITHUB_NEW_REPO = "https://github.com/new"
    const val URL_CHATGPT_CODEX = "https://chatgpt.com/codex"
    const val URL_GITHUB_DEVICE = "https://github.com/login/device"

    /** Clean Chrome Android User-Agent without `; wv` so Google, GitHub, and OpenAI OAuth never block sign-in. */
    const val AUTH_SAFE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val savedState = Bundle()
    var retainedWebView: WebView? = null
    var lastLoadedUrl: String? = null
    var hasMainFrameError: Boolean = false
    private var bridgeInstalled = false

    // Live download state for APK / files exported from the Codespace to phone storage
    var downloadingFileName by mutableStateOf<String?>(null)
    var downloadBytesReceived by mutableLongStateOf(0L)
    var downloadTotalBytes by mutableLongStateOf(-1L)
    var downloadedFile by mutableStateOf<File?>(null)
    var downloadedPublicPath by mutableStateOf<String?>(null)
    var downloadError by mutableStateOf<String?>(null)
    var downloadStatusHint by mutableStateOf<String?>(null)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeStream: FileOutputStream? = null
    private var activeTempFile: File? = null

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Returns true ONLY if [url] is an external OAuth popup that must stay in a popup dialog. */
    fun isOAuthPopupUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("accounts.google.com") ||
            lower.contains("appleid.apple.com") ||
            lower.contains("login.microsoftonline.com") ||
            lower.contains("auth0.com") ||
            lower.contains("auth.openai.com") ||
            lower.contains("github.com/login/oauth") ||
            lower.contains("github.com/sessions/")
    }

    fun attachDownloadBridgeIfNeeded(webView: WebView, appContext: Context) {
        if (bridgeInstalled) return
        bridgeInstalled = true
        webView.addJavascriptInterface(CodespaceDownloadBridge(appContext.applicationContext), "AndroidCodespaceBridge")
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType).ifBlank { "app-debug.apk" }
            when {
                url.startsWith("blob:") -> {
                    val safeName = guessedName.replace("'", "")
                    val safeUrl = url.replace("'", "")
                    val js = """
                        (function() {
                            if (window.__aiAppDownloadBlobUrl) {
                                window.__aiAppDownloadBlobUrl('$safeUrl', '$safeName');
                            }
                        })();
                    """.trimIndent()
                    webView.post { webView.evaluateJavascript(js, null) }
                }
                url.startsWith("http://") || url.startsWith("https://") -> {
                    downloadHttpUrlWithCookies(appContext.applicationContext, url, userAgent, guessedName)
                }
            }
        }
    }

    /** Downloads a direct HTTP/HTTPS URL using the WebView's authenticated session cookies and saves it to Downloads/AIApp. */
    fun downloadHttpUrlWithCookies(context: Context, url: String, userAgent: String?, fileName: String) {
        val safeName = sanitizeFileName(fileName)
        downloadingFileName = safeName
        downloadBytesReceived = 0L
        downloadTotalBytes = -1L
        downloadedFile = null
        downloadedPublicPath = null
        downloadError = null
        downloadStatusHint = null

        ioScope.launch {
            try {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: AUTH_SAFE_UA)
                    .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                    .build()
                val dir = File(context.cacheDir, "apks").apply { mkdirs() }
                val out = File(dir, safeName)
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Download failed (HTTP ${resp.code})")
                    val body = resp.body ?: throw IllegalStateException("Empty download response")
                    downloadTotalBytes = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(1 shl 16)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                total += n
                                downloadBytesReceived = total
                            }
                        }
                    }
                }
                val savedPath = saveToPhoneDownloads(context, out, safeName)
                downloadedFile = out
                downloadedPublicPath = savedPath
            } catch (e: Throwable) {
                downloadError = e.message ?: "Couldn't download file from Codespace."
            } finally {
                downloadingFileName = null
            }
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').substringBefore('?')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "app-debug.apk" }

    /** Saves a copy of [file] into the phone's public `Downloads/AIApp/` folder and returns its human-readable path. */
    fun saveToPhoneDownloads(context: Context, file: File, fileName: String): String {
        val mime = if (fileName.endsWith(".apk", ignoreCase = true)) {
            "application/vnd.android.package-archive"
        } else {
            "application/octet-stream"
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIApp")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    return@runCatching "Downloads/AIApp/$fileName"
                }
            }
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AIApp").apply { mkdirs() }
            val dest = File(fallbackDir, fileName)
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.getOrElse { file.absolutePath }
    }

    /**
     * JavaScript-to-Android bridge that captures files exported by VS Code Web's `window.showSaveFilePicker`
     * or `blob:` URLs in streaming 256 KB chunks and writes them directly to phone storage, AND allows JS
     * to report status or request native hardware `KeyEvent` dispatch (`dispatchNativeEnter`).
     */
    class CodespaceDownloadBridge(private val appContext: Context) {
        @JavascriptInterface
        fun reportStatus(status: String) {
            downloadStatusHint = status.takeIf { it.isNotBlank() }
        }

        @JavascriptInterface
        fun startDownload(fileName: String, expectedBytes: Long) {
            runCatching { activeStream?.close() }
            val safeName = sanitizeFileName(fileName)
            val dir = File(appContext.cacheDir, "apks").apply { mkdirs() }
            val outFile = File(dir, safeName)
            activeTempFile = outFile
            activeStream = FileOutputStream(outFile)
            downloadingFileName = safeName
            downloadBytesReceived = 0L
            downloadTotalBytes = expectedBytes
            downloadedFile = null
            downloadedPublicPath = null
            downloadError = null
            downloadStatusHint = null
        }

        @JavascriptInterface
        fun appendBase64Chunk(base64Chunk: String) {
            val fos = activeStream ?: return
            val bytes = Base64.getDecoder().decode(base64Chunk)
            fos.write(bytes)
            downloadBytesReceived += bytes.size
        }

        @JavascriptInterface
        fun finishDownload() {
            val fos = activeStream
            val file = activeTempFile
            activeStream = null
            activeTempFile = null
            runCatching {
                fos?.flush()
                fos?.close()
            }
            if (file != null && file.exists() && file.length() > 0) {
                val savedPath = saveToPhoneDownloads(appContext, file, file.name)
                downloadedFile = file
                downloadedPublicPath = savedPath
            } else {
                downloadError = "The downloaded file was empty. Tap 📁 Files, expand app → build → outputs → apk → debug, and tap app-debug.apk."
            }
            downloadingFileName = null
            downloadStatusHint = null
        }

        @JavascriptInterface
        fun failDownload(reason: String) {
            runCatching { activeStream?.close() }
            activeStream = null
            activeTempFile = null
            downloadingFileName = null
            downloadStatusHint = null
            downloadError = reason.ifBlank { "Download from Codespace failed." }
        }

        /** Called from JS when the user presses Enter or taps the Agent Send arrow so Android dispatches a trusted hardware KEYCODE_ENTER. */
        @JavascriptInterface
        fun dispatchNativeEnter() {
            val wv = retainedWebView ?: return
            wv.post {
                sendHardwareKeyToWebView(wv, KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    /** Sends a genuine Android hardware `KeyEvent` (`ACTION_DOWN` + `ACTION_UP`) to [webView] so Monaco/xterm sees `isTrusted: true`. */
    fun sendHardwareKeyToWebView(webView: WebView, keyCode: Int, metaState: Int = 0) {
        runCatching {
            webView.requestFocus()
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            val up = KeyEvent(now, now + 15, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
    }

    /**
     * Injects:
     * 1. Mobile Single-Pane Workspace Controller (`window.__aiAppSetPaneMode(mode)`):
     *    - Eliminates the unusable 3-column squeeze on phone screens by letting the user view
     *      **🤖 Agent Chat**, **📁 Files**, **📝 Code Editor**, or **💻 Terminal** at 100% full width!
     * 2. Smart Auto-Expanding APK Finder & Downloader (`window.__aiAppTriggerApkDownload()`):
     *    - When the user clicks the top **`📥 APK`** button, even if `app/build/outputs/apk/debug/app-debug.apk`
     *      is buried inside collapsed folders below or the Explorer sidebar is closed, it automatically
     *      opens the Explorer, expands `app` → `build` → `outputs` → `apk` → `debug` step by step,
     *      right-clicks `app-debug.apk`, and clicks `Download...` to stream the `.apk` straight into
     *      `Downloads/AIApp/` on the phone!
     * 3. VS Code Agent Chat Enter Key, Blue `↑` Send Button (`-monaco-gesturetap`), and Dock Arrow Keys.
     */
    fun injectKeepAliveAndMobileUI(
        webView: WebView,
        userCodeToAutoFill: String? = null,
        paneMode: MobilePaneMode = MobilePaneMode.SPLIT,
        uiZoomPercent: Int = 100,
    ) {
        val codeEscaped = userCodeToAutoFill?.replace("'", "")?.trim().orEmpty()
        val paneModeId = paneMode.id
        val zoomScale = (uiZoomPercent.coerceIn(65, 150) / 100.0)
        val js = """
            (function() {
                try {
                    window.dispatchEvent(new Event('online'));
                    window.dispatchEvent(new Event('focus'));

                    if (!window.__aiAppKeepAliveInstalled) {
                        window.__aiAppKeepAliveInstalled = true;
                        document.addEventListener('visibilitychange', function(e) {
                            if (document.visibilityState === 'hidden') {
                                e.stopImmediatePropagation();
                            }
                        }, true);
                    }

                    // --- 0. Prevent github.com links ("New codespace", "New repository", etc.) from opening trapped popups ---
                    if (window.location.hostname.indexOf('github.com') >= 0) {
                        var blankLinks = document.querySelectorAll('a[target="_blank"]');
                        for (var k = 0; k < blankLinks.length; k++) {
                            var h = blankLinks[k].href || '';
                            if (!h || h.indexOf('github.com') >= 0 || h.indexOf('.github.dev') >= 0 || h.indexOf('/') === 0) {
                                blankLinks[k].removeAttribute('target');
                            }
                        }
                    }

                    // --- 1. VS Code Agent Chat Enter Key, Blue Arrow Send Button & Hardware Key Helper ---
                    if (!window.__aiAppAgentEnterInstalled) {
                        window.__aiAppAgentEnterInstalled = true;

                        document.addEventListener('focusin', function(ev) {
                            var t = ev.target;
                            if (t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT' || t.isContentEditable)) {
                                window.__aiAppLastFocusedInput = t;
                                if (t.closest && t.closest('.interactive-input-part, .interactive-session, .chat-widget, .chat-input-container')) {
                                    window.__aiAppLastChatTextarea = t;
                                }
                            }
                        }, true);

                        window.__aiAppMakeKeyEvent = function(type, key, code, keyCode, ctrl, shift, alt) {
                            var ev = new KeyboardEvent(type, {
                                key: key,
                                code: code,
                                keyCode: keyCode,
                                which: keyCode,
                                ctrlKey: !!ctrl,
                                shiftKey: !!shift,
                                altKey: !!alt,
                                bubbles: true,
                                cancelable: true,
                                composed: true
                            });
                            try {
                                Object.defineProperty(ev, 'keyCode', { get: function() { return keyCode; } });
                                Object.defineProperty(ev, 'which', { get: function() { return keyCode; } });
                            } catch (err) {}
                            return ev;
                        };

                        window.__aiAppFindChatSendButton = function(scopeEl) {
                            var root = scopeEl || document;
                            var selectors = [
                                '.interactive-input-part .monaco-action-bar .action-item a.action-label.codicon-arrow-up',
                                '.interactive-input-part .monaco-action-bar .action-item a.action-label.codicon-send',
                                '.interactive-input-part .monaco-action-bar .action-item a.action-label[aria-label*="Send"]',
                                '.interactive-input-part .monaco-action-bar .action-item a.action-label[title*="Send"]',
                                '.interactive-input-part .monaco-action-bar .action-item a.action-label[aria-label*="Submit"]',
                                '.interactive-input-part .interactive-execute-toolbar .action-item:last-child a.action-label',
                                '.interactive-input-part .monaco-action-bar .action-item:last-child a.action-label',
                                '.chat-input-toolbars .monaco-action-bar .action-item:last-child a.action-label'
                            ];
                            for (var i = 0; i < selectors.length; i++) {
                                var btn = root.querySelector(selectors[i]);
                                if (btn && btn.offsetParent !== null) return btn;
                            }
                            return null;
                        };

                        var lastSubmitTime = 0;
                        window.__aiAppSubmitAgentChat = function() {
                            var now = Date.now();
                            if (now - lastSubmitTime < 220) return;
                            lastSubmitTime = now;

                            try {
                                var chatTa = window.__aiAppLastChatTextarea ||
                                    document.querySelector('.interactive-input-part textarea.inputarea, .interactive-session .interactive-input-part textarea, .chat-widget textarea.inputarea');
                                if (chatTa) {
                                    chatTa.focus();
                                    chatTa.dispatchEvent(window.__aiAppMakeKeyEvent('keydown', 'Enter', 'Enter', 13, false, false, false));
                                    chatTa.dispatchEvent(window.__aiAppMakeKeyEvent('keypress', 'Enter', 'Enter', 13, false, false, false));
                                    chatTa.dispatchEvent(window.__aiAppMakeKeyEvent('keyup', 'Enter', 'Enter', 13, false, false, false));
                                }
                                if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.dispatchNativeEnter) {
                                    window.AndroidCodespaceBridge.dispatchNativeEnter();
                                }
                                setTimeout(function() {
                                    try {
                                        var part = (chatTa && chatTa.closest) ? chatTa.closest('.interactive-input-part, .interactive-session, .chat-widget') : null;
                                        var hasText = true;
                                        if (part) {
                                            var lines = part.querySelector('.view-lines');
                                            if (lines && (lines.innerText || '').trim().length === 0) hasText = false;
                                        }
                                        if (hasText) {
                                            var btn = window.__aiAppFindChatSendButton(part) || window.__aiAppFindChatSendButton(document);
                                            if (btn) {
                                                var li = btn.closest('.action-item') || btn.parentElement;
                                                var rect = btn.getBoundingClientRect();
                                                var cx = rect.left + rect.width / 2;
                                                var cy = rect.top + rect.height / 2;
                                                var mOpts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0, buttons: 1 };
                                                btn.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                                if (li) li.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                                btn.dispatchEvent(new PointerEvent('pointerdown', mOpts));
                                                btn.dispatchEvent(new MouseEvent('mousedown', mOpts));
                                                btn.dispatchEvent(new PointerEvent('pointerup', mOpts));
                                                btn.dispatchEvent(new MouseEvent('mouseup', mOpts));
                                                btn.click();
                                                if (li) {
                                                    li.dispatchEvent(new MouseEvent('mousedown', mOpts));
                                                    li.dispatchEvent(new MouseEvent('mouseup', mOpts));
                                                    li.click();
                                                }
                                            }
                                        }
                                    } catch (e2) {}
                                }, 80);
                            } catch (e) {}
                        };

                        document.addEventListener('beforeinput', function(ev) {
                            try {
                                var t = ev.target;
                                if (!t || !t.closest) return;
                                var inChat = t.closest('.interactive-input-part, .chat-input-container');
                                if (!inChat) return;
                                var it = ev.inputType || '';
                                var d = ev.data || '';
                                if (it === 'insertLineBreak' || it === 'insertParagraph' ||
                                    (it === 'insertText' && d === '\n') ||
                                    (it === 'insertCompositionText' && d.length > 0 && d.charCodeAt(d.length - 1) === 10)) {
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    window.__aiAppSubmitAgentChat();
                                }
                            } catch (e) {}
                        }, true);

                        document.addEventListener('input', function(ev) {
                            try {
                                var t = ev.target;
                                if (!t || !t.closest) return;
                                var inChat = t.closest('.interactive-input-part, .chat-input-container');
                                if (!inChat) return;
                                if (typeof t.value === 'string' && t.value.endsWith('\n') && !ev.shiftKey) {
                                    window.__aiAppSubmitAgentChat();
                                }
                            } catch (e) {}
                        }, true);

                        function handleSendButtonTouch(ev) {
                            try {
                                var target = ev.target;
                                if (!target || !target.closest) return;
                                var inInputPart = target.closest('.interactive-input-part, .chat-input-toolbars');
                                if (!inInputPart) return;
                                var actionItem = target.closest('.action-item, a.action-label, .monaco-button');
                                if (!actionItem) return;
                                var label = ((actionItem.getAttribute('aria-label') || '') + ' ' +
                                             (actionItem.getAttribute('title') || '') + ' ' +
                                             (actionItem.className || '')).toLowerCase();
                                var isLastAction = false;
                                var bar = actionItem.closest('.monaco-action-bar');
                                if (bar && bar.lastElementChild && (bar.lastElementChild === actionItem || bar.lastElementChild.contains(actionItem))) {
                                    var txt = (actionItem.innerText || '').trim().toLowerCase();
                                    if (txt === '' && label.indexOf('attach') < 0 && label.indexOf('model') < 0 && label.indexOf('mode') < 0 && label.indexOf('voice') < 0) {
                                        isLastAction = true;
                                    }
                                }
                                if (label.indexOf('send') >= 0 || label.indexOf('submit') >= 0 ||
                                    label.indexOf('codicon-arrow-up') >= 0 || label.indexOf('codicon-send') >= 0 || isLastAction) {
                                    window.__aiAppSubmitAgentChat();
                                }
                            } catch (e) {}
                        }
                        document.addEventListener('touchstart', handleSendButtonTouch, { capture: true, passive: true });
                        document.addEventListener('pointerdown', handleSendButtonTouch, true);

                        setInterval(function() {
                            try {
                                var btns = document.querySelectorAll('.monaco-dialog-box a.monaco-button, .workspace-trust-editor a.monaco-button');
                                for (var i = 0; i < btns.length; i++) {
                                    var bt = (btns[i].innerText || '').trim().toLowerCase();
                                    if (bt === 'trust' || bt === 'yes, i trust the authors' || bt === 'trust folder and continue') {
                                        btns[i].click();
                                    }
                                }
                            } catch (e) {}
                        }, 1500);
                    }

                    // --- A. Native Android File & APK Download Bridge + Auto-Expanding Tree Downloader ---
                    if (!window.__aiAppDownloadHooksInstalled && window.AndroidCodespaceBridge) {
                        window.__aiAppDownloadHooksInstalled = true;

                        window.__aiAppSendUint8ToBridge = async function(uint8) {
                            var chunkSize = 196608;
                            for (var offset = 0; offset < uint8.byteLength; offset += chunkSize) {
                                var slice = uint8.subarray(offset, Math.min(offset + chunkSize, uint8.byteLength));
                                var binary = '';
                                var subStep = 8192;
                                for (var i = 0; i < slice.length; i += subStep) {
                                    binary += String.fromCharCode.apply(null, slice.subarray(i, Math.min(i + subStep, slice.length)));
                                }
                                window.AndroidCodespaceBridge.appendBase64Chunk(btoa(binary));
                            }
                        };

                        window.__aiAppDownloadBlobUrl = async function(blobUrl, fileName) {
                            try {
                                var resp = await fetch(blobUrl);
                                var blob = await resp.blob();
                                window.AndroidCodespaceBridge.startDownload(fileName || 'app-debug.apk', blob.size || -1);
                                var buf = new Uint8Array(await blob.arrayBuffer());
                                await window.__aiAppSendUint8ToBridge(buf);
                                window.AndroidCodespaceBridge.finishDownload();
                            } catch (err) {
                                window.AndroidCodespaceBridge.failDownload('Blob download error: ' + err);
                            }
                        };

                        window.showSaveFilePicker = async function(options) {
                            var suggested = (options && options.suggestedName) ? options.suggestedName : 'app-debug.apk';
                            window.AndroidCodespaceBridge.startDownload(suggested, -1);
                            return {
                                kind: 'file',
                                name: suggested,
                                createWritable: async function() {
                                    return {
                                        write: async function(data) {
                                            var buf;
                                            if (data instanceof Blob) {
                                                buf = new Uint8Array(await data.arrayBuffer());
                                            } else if (data instanceof ArrayBuffer) {
                                                buf = new Uint8Array(data);
                                            } else if (ArrayBuffer.isView(data)) {
                                                buf = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                                            } else if (data && typeof data === 'object' && data.data) {
                                                var d = data.data;
                                                buf = (d instanceof Blob) ? new Uint8Array(await d.arrayBuffer())
                                                    : (d instanceof ArrayBuffer) ? new Uint8Array(d)
                                                    : ArrayBuffer.isView(d) ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength)
                                                    : new TextEncoder().encode(String(d));
                                            } else {
                                                buf = new TextEncoder().encode(String(data || ''));
                                            }
                                            await window.__aiAppSendUint8ToBridge(buf);
                                        },
                                        close: async function() {
                                            window.AndroidCodespaceBridge.finishDownload();
                                        },
                                        abort: async function(err) {
                                            window.AndroidCodespaceBridge.failDownload(String(err || 'Aborted'));
                                        }
                                    };
                                }
                            };
                        };

                        document.addEventListener('click', function(ev) {
                            var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
                            if (!a) return;
                            var href = a.getAttribute('href') || a.href || '';
                            var dl = a.getAttribute('download');
                            if (href.indexOf('blob:') === 0) {
                                ev.preventDefault();
                                ev.stopPropagation();
                                window.__aiAppDownloadBlobUrl(href, dl || 'app-debug.apk');
                            }
                        }, true);

                        // Helper: right-click a found .apk row in Explorer and click "Download..."
                        window.__aiAppContextDownloadRow = function(apkRow) {
                            var rect = apkRow.getBoundingClientRect();
                            var cx = rect.left + Math.min(40, rect.width / 2);
                            var cy = rect.top + (rect.height / 2);
                            apkRow.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
                            apkRow.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
                            apkRow.click();
                            apkRow.dispatchEvent(new MouseEvent('contextmenu', {
                                bubbles: true,
                                cancelable: true,
                                view: window,
                                button: 2,
                                buttons: 2,
                                clientX: cx,
                                clientY: cy
                            }));
                            var attempts = 0;
                            var timer = setInterval(function() {
                                attempts++;
                                var items = Array.from(document.querySelectorAll('.context-view .action-item, .monaco-menu .action-item, .monaco-action-bar .action-item'));
                                var dlItem = items.find(function(el) {
                                    var label = (el.innerText || el.getAttribute('aria-label') || '').trim().toLowerCase();
                                    return label === 'download...' || label === 'download';
                                });
                                if (dlItem) {
                                    clearInterval(timer);
                                    var link = dlItem.querySelector('a.action-label') || dlItem;
                                    link.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                    link.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                                    link.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
                                    link.click();
                                } else if (attempts >= 18) {
                                    clearInterval(timer);
                                }
                            }, 60);
                        };

                        // Smart Auto-Expanding APK Finder & Downloader:
                        // Even if the user clicks the top "📥 APK" button while the Explorer tree is closed or collapsed,
                        // this automatically opens Explorer, expands app -> build -> outputs -> apk -> debug, finds the .apk row,
                        // and triggers VS Code's Download action!
                        window.__aiAppTriggerApkDownload = async function() {
                            try {
                                function sleep(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }
                                function findApkRow() {
                                    var rows = Array.from(document.querySelectorAll('.explorer-folders-view .monaco-list-row, .monaco-list-row'));
                                    return rows.find(function(r) {
                                        var txt = (r.innerText || '').trim().toLowerCase();
                                        return txt.endsWith('.apk');
                                    });
                                }

                                // 1. Check if an .apk row is already visible in the DOM right now
                                var existingRow = findApkRow();
                                if (existingRow) {
                                    if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.reportStatus) {
                                        window.AndroidCodespaceBridge.reportStatus('Found ' + (existingRow.innerText || 'APK').trim() + ' — starting download to phone…');
                                    }
                                    window.__aiAppContextDownloadRow(existingRow);
                                    return;
                                }

                                // 2. Ensure the Explorer sidebar is open so .explorer-folders-view is rendered
                                var explorerView = document.querySelector('.explorer-folders-view');
                                if (!explorerView || explorerView.offsetParent === null) {
                                    if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.reportStatus) {
                                        window.AndroidCodespaceBridge.reportStatus('Opening Explorer & locating app/build/outputs/apk/debug/app-debug.apk…');
                                    }
                                    var explorerIcon = document.querySelector('.part.activitybar a.action-label[aria-label*="Explorer"], .part.activitybar .codicon-files');
                                    if (explorerIcon) {
                                        explorerIcon.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                        explorerIcon.click();
                                    } else {
                                        var target = document.querySelector('.monaco-workbench') || document.body;
                                        target.dispatchEvent(window.__aiAppMakeKeyEvent('keydown', 'E', 'KeyE', 69, true, true, false));
                                        target.dispatchEvent(window.__aiAppMakeKeyEvent('keyup', 'E', 'KeyE', 69, true, true, false));
                                    }
                                    await sleep(350);
                                }

                                existingRow = findApkRow();
                                if (existingRow) {
                                    window.__aiAppContextDownloadRow(existingRow);
                                    return;
                                }

                                // 3. Step-by-step expand folder path: app -> build -> outputs -> apk -> debug (or release)
                                var pathFolders = ['app', 'build', 'outputs', 'apk', 'debug', 'release'];
                                for (var fIdx = 0; fIdx < pathFolders.length; fIdx++) {
                                    var folderName = pathFolders[fIdx];
                                    var rows = Array.from(document.querySelectorAll('.explorer-folders-view .monaco-list-row'));
                                    var folderRow = rows.find(function(r) {
                                        var txt = (r.innerText || '').trim().toLowerCase();
                                        // Match exact folder name or compact path like "apk/debug" or "outputs/apk/debug"
                                        return txt === folderName || txt.endsWith('/' + folderName) || txt.indexOf(folderName + '/') === 0;
                                    });
                                    if (folderRow) {
                                        var twistie = folderRow.querySelector('.monaco-tl-twistie');
                                        var isCollapsed = (twistie && twistie.classList.contains('collapsed')) ||
                                            folderRow.getAttribute('aria-expanded') === 'false';
                                        if (isCollapsed) {
                                            var rect = folderRow.getBoundingClientRect();
                                            var cx = rect.left + 20;
                                            var cy = rect.top + rect.height / 2;
                                            folderRow.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
                                            folderRow.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
                                            folderRow.click();
                                            await sleep(220);
                                        }
                                    }
                                    existingRow = findApkRow();
                                    if (existingRow) {
                                        if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.reportStatus) {
                                            window.AndroidCodespaceBridge.reportStatus('Found ' + (existingRow.innerText || 'APK').trim() + ' — downloading to Downloads/AIApp…');
                                        }
                                        window.__aiAppContextDownloadRow(existingRow);
                                        return;
                                    }
                                }

                                // 4. If still not found, notify user clearly
                                if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.failDownload) {
                                    window.AndroidCodespaceBridge.failDownload(
                                        'No built .apk found in Explorer yet. Build the APK first (e.g. "./gradlew assembleDebug" in 💻 Term), or tap 📁 Files and tap your .apk file directly!'
                                    );
                                }
                            } catch (e) {
                                if (window.AndroidCodespaceBridge && window.AndroidCodespaceBridge.failDownload) {
                                    window.AndroidCodespaceBridge.failDownload('Download error: ' + e);
                                }
                            }
                        };

                        setInterval(function() {
                            try {
                                var activeTab = document.querySelector('.tabs-container .tab.active');
                                var isApkTab = activeTab && (activeTab.innerText || '').toLowerCase().indexOf('.apk') >= 0;
                                var openAnywayBtn = Array.from(document.querySelectorAll('.editor-container a.monaco-button, .editor-container button'))
                                    .find(function(b) { return (b.innerText || '').trim().toLowerCase() === 'open anyway'; });
                                if (openAnywayBtn && (isApkTab || document.title.toLowerCase().indexOf('.apk') >= 0)) {
                                    var parent = openAnywayBtn.parentElement;
                                    if (parent && !parent.querySelector('#__aiapp_dl_apk_btn')) {
                                        var btn = document.createElement('button');
                                        btn.id = '__aiapp_dl_apk_btn';
                                        btn.innerText = '📥 Download APK to Phone Storage';
                                        btn.style.cssText = 'display:block;width:100%;max-width:280px;margin:10px auto;padding:12px 16px;background:#C96442;color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:bold;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,0.3);';
                                        btn.onclick = function(e) {
                                            e.preventDefault();
                                            e.stopPropagation();
                                            window.__aiAppTriggerApkDownload();
                                        };
                                        parent.insertBefore(btn, openAnywayBtn);
                                    }
                                }
                            } catch (e) {}
                        }, 800);
                    }

                    // --- B. Mobile Single-Pane Workspace Mode CSS & Controller ---
                    var vp = document.querySelector('meta[name="viewport"]');
                    if (!vp) {
                        vp = document.createElement('meta');
                        vp.name = 'viewport';
                        document.head.appendChild(vp);
                    }
                    vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes, viewport-fit=cover';

                    window.__aiAppApplyPaneMode = function(mode) {
                        var isVsCodeWeb = !!document.querySelector('.monaco-workbench') || window.location.hostname.indexOf('.github.dev') >= 0;
                        var styleId = '__aiapp_mobile_codespace_css';
                        var styleEl = document.getElementById(styleId);
                        if (!isVsCodeWeb) {
                            if (styleEl) styleEl.remove();
                            return;
                        }
                        if (!styleEl) {
                            styleEl = document.createElement('style');
                            styleEl.id = styleId;
                            document.head.appendChild(styleEl);
                        }

                        var baseCss = `
                            .monaco-workbench .monaco-scrollable-element,
                            .xterm .xterm-viewport,
                            .monaco-list {
                                -webkit-overflow-scrolling: touch !important;
                                touch-action: pan-x pan-y !important;
                            }
                            .interactive-input-part .monaco-action-bar .action-item a.action-label {
                                min-width: 34px !important;
                                min-height: 34px !important;
                                display: inline-flex !important;
                                align-items: center !important;
                                justify-content: center !important;
                            }
                            .monaco-workbench .part.activitybar {
                                width: 38px !important;
                            }
                            .monaco-workbench .part.statusbar {
                                overflow-x: auto !important;
                                white-space: nowrap !important;
                            }
                        `;

                        var modeCss = '';
                        if (mode === 'agent') {
                            // Full-width Agent Chat: expand .part.auxiliarybar across the entire screen!
                            modeCss = `
                                .monaco-workbench .part.auxiliarybar {
                                    position: fixed !important;
                                    top: 30px !important;
                                    left: 0 !important;
                                    right: 0 !important;
                                    bottom: 24px !important;
                                    width: 100vw !important;
                                    max-width: 100vw !important;
                                    height: calc(100vh - 54px) !important;
                                    z-index: 300 !important;
                                }
                                .monaco-workbench .part.auxiliarybar .interactive-session,
                                .monaco-workbench .part.auxiliarybar .interactive-input-part,
                                .monaco-workbench .part.auxiliarybar .monaco-editor {
                                    width: 100% !important;
                                    max-width: 100% !important;
                                }
                                .monaco-workbench .part.sidebar {
                                    display: none !important;
                                }
                            `;
                        } else if (mode === 'files') {
                            // Full-width Explorer File Tree: expand .part.sidebar across the screen!
                            modeCss = `
                                .monaco-workbench .part.sidebar {
                                    position: fixed !important;
                                    top: 30px !important;
                                    left: 38px !important;
                                    right: 0 !important;
                                    bottom: 24px !important;
                                    width: calc(100vw - 38px) !important;
                                    max-width: calc(100vw - 38px) !important;
                                    height: calc(100vh - 54px) !important;
                                    z-index: 300 !important;
                                }
                                .monaco-workbench .part.auxiliarybar {
                                    display: none !important;
                                }
                            `;
                        } else if (mode === 'code') {
                            // Full-width Code Editor: hide left and right sidebars so Editor gets 100% width!
                            modeCss = `
                                .monaco-workbench .part.sidebar,
                                .monaco-workbench .part.auxiliarybar {
                                    display: none !important;
                                    width: 0 !important;
                                }
                                .monaco-workbench .part.editor {
                                    width: calc(100vw - 38px) !important;
                                    max-width: calc(100vw - 38px) !important;
                                }
                            `;
                        } else if (mode === 'terminal') {
                            // Full-width Terminal panel!
                            modeCss = `
                                .monaco-workbench .part.panel {
                                    position: fixed !important;
                                    top: 30px !important;
                                    left: 0 !important;
                                    right: 0 !important;
                                    bottom: 24px !important;
                                    width: 100vw !important;
                                    max-width: 100vw !important;
                                    height: calc(100vh - 54px) !important;
                                    z-index: 300 !important;
                                }
                                .monaco-workbench .part.sidebar,
                                .monaco-workbench .part.auxiliarybar {
                                    display: none !important;
                                }
                            `;
                        } else {
                            // Responsive Split mode
                            modeCss = `
                                @media (max-width: 900px) {
                                    .monaco-workbench .part.sidebar {
                                        max-width: 78vw !important;
                                        z-index: 100 !important;
                                        box-shadow: 4px 0 16px rgba(0,0,0,0.45) !important;
                                    }
                                }
                            `;
                        }

                        styleEl.textContent = baseCss + modeCss;
                        setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 60);
                    };

                    window.__aiAppApplyPaneMode('$paneModeId');

                    var isVsCodeWebNow = !!document.querySelector('.monaco-workbench') || window.location.hostname.indexOf('.github.dev') >= 0;
                    if (document.body) {
                        if (isVsCodeWebNow && Math.abs($zoomScale - 1.0) > 0.01) {
                            document.body.style.zoom = '$zoomScale';
                        } else {
                            document.body.style.zoom = '';
                        }
                    }

                    if (!window.__aiAppMobileTreeHooked) {
                        window.__aiAppMobileTreeHooked = true;
                        document.addEventListener('click', function(ev) {
                            try {
                                var row = ev.target && ev.target.closest ? ev.target.closest('.explorer-folders-view .monaco-list-row') : null;
                                if (!row || row.classList.contains('collapsible')) return;
                                var label = (row.innerText || '').trim().toLowerCase();
                                if (label.endsWith('.apk')) {
                                    setTimeout(function() {
                                        if (window.__aiAppContextDownloadRow) window.__aiAppContextDownloadRow(row);
                                    }, 120);
                                    return;
                                }
                            } catch (e) {}
                        }, false);
                    }

                    window.__aiAppRunVsCodeAction = function(action) {
                        try {
                            var target = (document.activeElement && document.activeElement !== document.body)
                                ? document.activeElement
                                : (window.__aiAppLastFocusedInput ||
                                   document.querySelector('.interactive-input-part textarea.inputarea, .monaco-editor textarea.inputarea, .xterm-helper-textarea, input, textarea') ||
                                   document.querySelector('.monaco-workbench') ||
                                   document.body);
                            if (target && target.focus) target.focus();

                            function sendKey(key, code, keyCode, ctrl, shift, alt) {
                                var kd = window.__aiAppMakeKeyEvent('keydown', key, code, keyCode, ctrl, shift, alt);
                                var ku = window.__aiAppMakeKeyEvent('keyup', key, code, keyCode, ctrl, shift, alt);
                                target.dispatchEvent(kd);
                                target.dispatchEvent(ku);
                            }

                            if (action === 'enter') {
                                var inChat = target && target.closest && target.closest('.interactive-input-part, .interactive-session, .chat-widget');
                                var visibleChat = document.querySelector('.interactive-input-part textarea.inputarea');
                                if (inChat || visibleChat) {
                                    window.__aiAppSubmitAgentChat();
                                } else {
                                    sendKey('Enter', 'Enter', 13, false, false, false);
                                }
                            } else if (action === 'open_agent_chat') {
                                var aux = document.querySelector('.monaco-workbench .part.auxiliarybar');
                                if (!aux || aux.offsetWidth < 20) {
                                    var chatBtn = document.querySelector('.command-center-chat, a.action-label[aria-label*="Chat"], .codicon-chat-sparkle, .codicon-comment-discussion');
                                    if (chatBtn) {
                                        chatBtn.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                        chatBtn.click();
                                    } else {
                                        sendKey('I', 'KeyI', 73, true, false, true);
                                    }
                                }
                                setTimeout(function() {
                                    var ta = document.querySelector('.interactive-input-part textarea.inputarea');
                                    if (ta) ta.focus();
                                }, 180);
                            } else if (action === 'open_explorer') {
                                var sb = document.querySelector('.monaco-workbench .part.sidebar');
                                if (!sb || sb.offsetWidth < 20) {
                                    var expBtn = document.querySelector('.part.activitybar a.action-label[aria-label*="Explorer"], .part.activitybar .codicon-files');
                                    if (expBtn) {
                                        expBtn.dispatchEvent(new Event('-monaco-gesturetap', { bubbles: true, cancelable: true }));
                                        expBtn.click();
                                    } else {
                                        sendKey('E', 'KeyE', 69, true, true, false);
                                    }
                                }
                            } else if (action === 'open_terminal') {
                                var pnl = document.querySelector('.monaco-workbench .part.panel');
                                if (!pnl || pnl.offsetHeight < 20) {
                                    sendKey('`', 'Backquote', 192, true, false, false);
                                }
                                setTimeout(function() {
                                    var xta = document.querySelector('.xterm-helper-textarea');
                                    if (xta) xta.focus();
                                }, 180);
                            } else if (action === 'command_palette') {
                                sendKey('F1', 'F1', 112, false, false, false);
                                sendKey('P', 'KeyP', 80, true, true, false);
                            } else if (action === 'quick_open') {
                                sendKey('p', 'KeyP', 80, true, false, false);
                            } else if (action === 'sidebar') {
                                sendKey('b', 'KeyB', 66, true, false, false);
                            } else if (action === 'terminal') {
                                sendKey('`', 'Backquote', 192, true, false, false);
                            } else if (action === 'find') {
                                sendKey('f', 'KeyF', 70, true, false, false);
                            } else if (action === 'save') {
                                sendKey('s', 'KeyS', 83, true, false, false);
                            } else if (action === 'git') {
                                sendKey('G', 'KeyG', 71, true, true, false);
                            } else if (action === 'undo') {
                                document.execCommand('undo');
                                sendKey('z', 'KeyZ', 90, true, false, false);
                            } else if (action === 'redo') {
                                document.execCommand('redo');
                                sendKey('y', 'KeyY', 89, true, false, false);
                            } else if (action === 'escape') {
                                sendKey('Escape', 'Escape', 27, false, false, false);
                            } else if (action === 'tab') {
                                sendKey('Tab', 'Tab', 9, false, false, false);
                            } else if (action === 'arrow_left') {
                                sendKey('ArrowLeft', 'ArrowLeft', 37, false, false, false);
                            } else if (action === 'arrow_up') {
                                sendKey('ArrowUp', 'ArrowUp', 38, false, false, false);
                            } else if (action === 'arrow_right') {
                                sendKey('ArrowRight', 'ArrowRight', 39, false, false, false);
                            } else if (action === 'arrow_down') {
                                sendKey('ArrowDown', 'ArrowDown', 40, false, false, false);
                            }
                        } catch (e) {}
                    };

                    window.__aiAppInsertText = function(txt, withCtrl, withAlt) {
                        try {
                            var el = (document.activeElement && document.activeElement !== document.body)
                                ? document.activeElement
                                : (window.__aiAppLastFocusedInput ||
                                   document.querySelector('.interactive-input-part textarea.inputarea, .monaco-editor textarea, .xterm-helper-textarea, input, textarea') ||
                                   document.body);
                            if (el && el.focus) el.focus();
                            if (withCtrl || withAlt) {
                                var upper = txt.toUpperCase();
                                var code = upper.charCodeAt(0);
                                el.dispatchEvent(window.__aiAppMakeKeyEvent('keydown', txt, 'Key' + upper, code, !!withCtrl, false, !!withAlt));
                                el.dispatchEvent(window.__aiAppMakeKeyEvent('keyup', txt, 'Key' + upper, code, !!withCtrl, false, !!withAlt));
                                return;
                            }
                            if (!document.execCommand('insertText', false, txt)) {
                                if (el && typeof el.value === 'string') {
                                    var start = el.selectionStart || el.value.length;
                                    var end = el.selectionEnd || el.value.length;
                                    el.value = el.value.substring(0, start) + txt + el.value.substring(end);
                                    el.selectionStart = el.selectionEnd = start + txt.length;
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                }
                            }
                        } catch (e) {}
                    };

                    var code = '$codeEscaped';
                    if (code && window.location.hostname === 'github.com' && window.location.pathname.indexOf('/login/device') === 0) {
                        var singleInput = document.querySelector('input#user-code, input[name="user_code"]');
                        if (singleInput && !singleInput.value) {
                            singleInput.value = code;
                            singleInput.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        var chars = code.replace(/[^A-Za-z0-9]/g, '');
                        for (var i = 0; i < chars.length; i++) {
                            var box = document.querySelector('input#user-code-' + i);
                            if (box && !box.value) {
                                box.value = chars[i];
                                box.dispatchEvent(new Event('input', { bubbles: true }));
                            }
                        }
                    }

                    var buttons = document.querySelectorAll('a.monaco-button, button');
                    for (var b = 0; b < buttons.length; b++) {
                        var t = (buttons[b].innerText || '').trim().toLowerCase();
                        if (t === 'reconnect' || t === 'try again' || t === 'retry') {
                            buttons[b].click();
                            break;
                        }
                    }
                } catch (err) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /**
     * Switches the mobile single-pane layout inside VS Code Web (`AGENT`, `FILES`, `CODE`, `TERMINAL`, `SPLIT`)
     * and triggers the corresponding VS Code panel so it opens at 100% full width on the phone.
     */
    fun switchPaneMode(webView: WebView, mode: MobilePaneMode) {
        runCatching {
            webView.evaluateJavascript("if (window.__aiAppApplyPaneMode) window.__aiAppApplyPaneMode('${mode.id}');", null)
            when (mode) {
                MobilePaneMode.AGENT -> triggerVsCodeAction(webView, "open_agent_chat")
                MobilePaneMode.FILES -> triggerVsCodeAction(webView, "open_explorer")
                MobilePaneMode.TERMINAL -> triggerVsCodeAction(webView, "open_terminal")
                else -> Unit
            }
        }
    }

    fun triggerVsCodeAction(webView: WebView, action: String) {
        runCatching {
            webView.evaluateJavascript("if (window.__aiAppRunVsCodeAction) window.__aiAppRunVsCodeAction('$action');", null)
        }
        when (action) {
            "enter" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_ENTER)
            "arrow_left" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_DPAD_LEFT)
            "arrow_up" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_DPAD_UP)
            "arrow_right" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_DPAD_RIGHT)
            "arrow_down" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_DPAD_DOWN)
            "escape" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_ESCAPE)
            "tab" -> sendHardwareKeyToWebView(webView, KeyEvent.KEYCODE_TAB)
        }
    }

    fun triggerApkDownloadFromCodespace(webView: WebView) {
        downloadError = null
        downloadStatusHint = "Locating built .apk in Codespace…"
        runCatching {
            webView.evaluateJavascript("if (window.__aiAppTriggerApkDownload) window.__aiAppTriggerApkDownload();", null)
        }
    }

    fun insertTextOrShortcut(webView: WebView, text: String, ctrl: Boolean = false, alt: Boolean = false) {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        runCatching {
            webView.evaluateJavascript("if (window.__aiAppInsertText) window.__aiAppInsertText('$escaped', $ctrl, $alt);", null)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodespaceScreen(
    vm: ChatViewModel,
    initialUrl: String = CodespaceSessionManager.URL_GITHUB_CODESPACES,
    requestedUrl: String? = null,
    onConsumeRequestedUrl: () -> Unit = {},
    isVisible: Boolean = true,
    onBack: () -> Unit,
    onSwitchToChat: () -> Unit = onBack,
    onSwitchToBuild: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var currentUrl by rememberSaveable { mutableStateOf(CodespaceSessionManager.lastLoadedUrl ?: initialUrl) }
    var pageTitle by rememberSaveable { mutableStateOf("Codespace") }
    var loadingProgress by remember { mutableStateOf<Float?>(null) }
    var canGoBackInWeb by remember { mutableStateOf(false) }
    var networkError by remember { mutableStateOf<String?>(null) }
    var showCodespacePicker by rememberSaveable { mutableStateOf(false) }
    var showCreateRepoDialog by rememberSaveable { mutableStateOf(false) }
    var showMoreTools by rememberSaveable { mutableStateOf(false) }
    var codespaces by remember { mutableStateOf<List<GitHubClient.Codespace>>(emptyList()) }
    var repos by remember { mutableStateOf<List<GitHubClient.Repo>>(emptyList()) }
    var loadingList by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    var paneMode by rememberSaveable { mutableStateOf(MobilePaneMode.SPLIT) }
    var showMobileKeyBar by rememberSaveable { mutableStateOf(true) }
    var landscapeLocked by rememberSaveable { mutableStateOf(false) }
    var uiZoomPercent by rememberSaveable { mutableIntStateOf(100) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }

    var popupMessage by remember { mutableStateOf<Message?>(null) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    val webView = remember {
        CodespaceSessionManager.retainedWebView?.also { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
        } ?: WebView(context.applicationContext).also { created ->
            CodespaceSessionManager.retainedWebView = created
        }.also { wv ->
            CodespaceSessionManager.attachDownloadBridgeIfNeeded(wv, context.applicationContext)
        }
    }

    fun applyMobileSettings() {
        CodespaceSessionManager.injectKeepAliveAndMobileUI(
            webView = webView,
            userCodeToAutoFill = vm.githubCode?.userCode,
            paneMode = paneMode,
            uiZoomPercent = uiZoomPercent,
        )
    }

    fun navigateTo(url: String) {
        networkError = null
        CodespaceSessionManager.hasMainFrameError = false
        currentUrl = url
        CodespaceSessionManager.lastLoadedUrl = url
        webView.loadUrl(url)
    }

    fun toggleOrientation() {
        landscapeLocked = !landscapeLocked
        activity?.requestedOrientation = if (landscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun showSoftKeyboard() {
        runCatching {
            webView.requestFocus()
            webView.evaluateJavascript(
                "(function(){ var el = window.__aiAppLastFocusedInput || document.querySelector('.interactive-input-part textarea.inputarea, .monaco-editor textarea, .xterm-helper-textarea, input, textarea'); if(el) el.focus(); })();",
                null,
            )
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun installDownloadedApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.packageName),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            CodespaceSessionManager.downloadError = "Allow \"Install unknown apps\" for AI App, come back, and tap Install APK."
            return
        }
        runCatching { ApkInstaller.install(context, file) }.onFailure {
            CodespaceSessionManager.downloadError = it.message
        }
    }

    fun refreshCodespaceList() {
        val token = vm.githubToken()
        if (token.isBlank()) {
            listError = "Sign in with GitHub first to list or create Repositories & Codespaces via API."
            return
        }
        loadingList = true
        listError = null
        scope.launch {
            try {
                val gh = GitHubClient(token)
                codespaces = gh.listCodespaces()
                repos = gh.listRepos()
            } catch (e: Throwable) {
                listError = e.message ?: "Couldn't load Codespaces."
            } finally {
                loadingList = false
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (CodespaceSessionManager.lastLoadedUrl == null && webView.url.isNullOrBlank() && initialUrl.isNotBlank()) {
            navigateTo(initialUrl)
        }
    }

    LaunchedEffect(requestedUrl) {
        val target = requestedUrl?.trim().orEmpty()
        if (target.isNotEmpty()) {
            if (target != CodespaceSessionManager.lastLoadedUrl) {
                navigateTo(target)
            }
            onConsumeRequestedUrl()
        }
    }

    LaunchedEffect(paneMode, uiZoomPercent) {
        applyMobileSettings()
    }

    DisposableEffect(lifecycleOwner, webView) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                webView.post {
                    CodespaceSessionManager.flushCookies()
                    if (CodespaceSessionManager.hasMainFrameError || networkError != null) {
                        networkError = null
                        CodespaceSessionManager.hasMainFrameError = false
                        webView.reload()
                    } else {
                        applyMobileSettings()
                    }
                }
            }
        }
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(req, netCallback)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    runCatching { webView.saveState(CodespaceSessionManager.savedState) }
                    CodespaceSessionManager.flushCookies()
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching {
                        webView.onResume()
                        webView.resumeTimers()
                    }
                    CodespaceSessionManager.flushCookies()
                    if (CodespaceSessionManager.hasMainFrameError) {
                        CodespaceSessionManager.hasMainFrameError = false
                        networkError = null
                        webView.reload()
                    } else {
                        applyMobileSettings()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { cm?.unregisterNetworkCallback(netCallback) }
            runCatching { webView.saveState(CodespaceSessionManager.savedState) }
            CodespaceSessionManager.flushCookies()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = isVisible) {
        when {
            showCreateRepoDialog -> showCreateRepoDialog = false
            popupMessage != null -> popupMessage = null
            showCodespacePicker -> showCodespacePicker = false
            paneMode != MobilePaneMode.SPLIT -> {
                paneMode = MobilePaneMode.SPLIT
                CodespaceSessionManager.switchPaneMode(webView, MobilePaneMode.SPLIT)
            }
            // Return directly to the app (Chat / Build) without navigating the WebView away from the active Codespace!
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            // Safe-Area Top Header + Mobile Single-Pane Switcher (never touches the phone status bar / camera cutout!)
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(top = 6.dp, bottom = 4.dp),
                ) {
                    // Row 1: Back, Title, Prominent "📥 Download APK" Button, "+ Repo", Tools & Reload
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to app (no reload)")
                        }
                        OutlinedButton(
                            onClick = onSwitchToChat,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("💬 Chat", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onSwitchToBuild,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("🔨 Build", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { CodespaceSessionManager.triggerApkDownloadFromCodespace(webView) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("📥 APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showCreateRepoDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("➕ Repo", fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = {
                                networkError = null
                                CodespaceSessionManager.hasMainFrameError = false
                                webView.reload()
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                    }

                    // Row 2: Mobile Single-Pane Mode Switcher (Full-Width Agent | Files | Code | Term | Split + More)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MobilePaneMode.entries.forEach { mode ->
                            val selected = paneMode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable {
                                    paneMode = mode
                                    CodespaceSessionManager.switchPaneMode(webView, mode)
                                },
                            ) {
                                Text(
                                    mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showMoreTools) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { showMoreTools = !showMoreTools },
                        ) {
                            Text(
                                if (showMoreTools) "⚙️ Tools ▴" else "⚙️ Tools ▾",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    // Optional Expandable Tools Row (My Codespaces, New Codespace, Codex, Rotate, Zoom, Commands)
                    if (showMoreTools) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (canGoBackInWeb) {
                                IdeQuickButton("◀ Web Back") { if (webView.canGoBack()) webView.goBack() }
                            }
                            IdeQuickButton("My Codespaces") {
                                showCodespacePicker = !showCodespacePicker
                                if (showCodespacePicker) refreshCodespaceList()
                            }
                            IdeQuickButton("➕ New Codespace") { navigateTo(CodespaceSessionManager.URL_GITHUB_NEW_CODESPACE) }
                            IdeQuickButton("GitHub Home") { navigateTo(CodespaceSessionManager.URL_GITHUB_CODESPACES) }
                            IdeQuickButton("ChatGPT Codex") { navigateTo(CodespaceSessionManager.URL_CHATGPT_CODEX) }
                            IdeQuickButton(if (landscapeLocked) "📱 Portrait" else "🔄 Landscape") { toggleOrientation() }
                            IdeQuickButton("🔍 Zoom: $uiZoomPercent%") {
                                uiZoomPercent = when (uiZoomPercent) {
                                    100 -> 85
                                    85 -> 115
                                    115 -> 130
                                    else -> 100
                                }
                            }
                            IdeQuickButton("⚡ Commands (F1)") { CodespaceSessionManager.triggerVsCodeAction(webView, "command_palette") }
                            IdeQuickButton("🔍 Open File") { CodespaceSessionManager.triggerVsCodeAction(webView, "quick_open") }
                            IdeQuickButton("💾 Save File") { CodespaceSessionManager.triggerVsCodeAction(webView, "save") }
                            IdeQuickButton(if (showMobileKeyBar) "Hide Key Bar" else "Show Key Bar") {
                                showMobileKeyBar = !showMobileKeyBar
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (showMobileKeyBar) {
                MobileIdeBottomDock(
                    ctrlArmed = ctrlArmed,
                    altArmed = altArmed,
                    onToggleCtrl = { ctrlArmed = !ctrlArmed },
                    onToggleAlt = { altArmed = !altArmed },
                    onAction = { act ->
                        ctrlArmed = false
                        altArmed = false
                        CodespaceSessionManager.triggerVsCodeAction(webView, act)
                    },
                    onKey = { keyText ->
                        CodespaceSessionManager.insertTextOrShortcut(webView, keyText, ctrl = ctrlArmed, alt = altArmed)
                        ctrlArmed = false
                        altArmed = false
                    },
                    onShowKeyboard = ::showSoftKeyboard,
                    onHideDock = { showMobileKeyBar = false },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Live APK / File Download Progress, Status Hint & Install Card
            val dlName = CodespaceSessionManager.downloadingFileName
            val dlFile = CodespaceSessionManager.downloadedFile
            val dlErr = CodespaceSessionManager.downloadError
            val dlHint = CodespaceSessionManager.downloadStatusHint
            if (dlName != null || dlFile != null || dlErr != null || dlHint != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (dlHint != null && dlName == null && dlFile == null && dlErr == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Text(dlHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { CodespaceSessionManager.downloadStatusHint = null }) { Text("✕") }
                            }
                        }
                        if (dlName != null) {
                            val gotMb = "%.1f MB".format(CodespaceSessionManager.downloadBytesReceived / 1048576.0)
                            val total = CodespaceSessionManager.downloadTotalBytes
                            val pct = if (total > 0) (CodespaceSessionManager.downloadBytesReceived.toFloat() / total).coerceIn(0f, 1f) else -1f
                            UploadBar("Downloading $dlName to phone ($gotMb)…", pct)
                        }
                        if (dlFile != null) {
                            val sizeMb = "%.1f MB".format(dlFile.length() / 1048576.0)
                            Text(
                                "✓ Saved ${dlFile.name} ($sizeMb) to ${CodespaceSessionManager.downloadedPublicPath ?: "Downloads/AIApp"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (dlFile.name.endsWith(".apk", ignoreCase = true)) {
                                    Button(onClick = { installDownloadedApk(dlFile) }) {
                                        Text("📲 Install APK Now")
                                    }
                                }
                                OutlinedButton(onClick = {
                                    CodespaceSessionManager.downloadedFile = null
                                    CodespaceSessionManager.downloadedPublicPath = null
                                }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                        if (dlErr != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dlErr,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { CodespaceSessionManager.downloadError = null }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            // Active GitHub Device Code helper banner
            vm.githubCode?.let { code ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "GitHub Code: ${code.userCode}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Approve below — the app captures your token automatically.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(code.userCode))
                            applyMobileSettings()
                        }) {
                            Text("Paste Code")
                        }
                    }
                }
            }

            if (showCodespacePicker) {
                CodespaceApiPanel(
                    vm = vm,
                    codespaces = codespaces,
                    repos = repos,
                    loading = loadingList,
                    error = listError,
                    onRefresh = ::refreshCodespaceList,
                    onOpenCreateRepo = {
                        showCodespacePicker = false
                        showCreateRepoDialog = true
                    },
                    onOpenUrl = { url ->
                        showCodespacePicker = false
                        navigateTo(url)
                    },
                    onCreateForRepo = { repo ->
                        scope.launch {
                            loadingList = true
                            listError = null
                            try {
                                val cs = GitHubClient(vm.githubToken()).createCodespace(repo.owner, repo.name, repo.branch)
                                showCodespacePicker = false
                                navigateTo(cs.webUrl)
                            } catch (e: Throwable) {
                                listError = e.message ?: "Couldn't create Codespace."
                            } finally {
                                loadingList = false
                            }
                        }
                    },
                )
            }

            loadingProgress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }

            networkError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            networkError = null
                            CodespaceSessionManager.hasMainFrameError = false
                            applyMobileSettings()
                            webView.reload()
                        }) {
                            Text("Reconnect")
                        }
                    }
                }
            }

            // Main persistent WebView
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        webView.apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                cacheMode = WebSettings.LOAD_DEFAULT
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = false
                                textZoom = 100
                                mediaPlaybackRequiresUserGesture = false
                                userAgentString = CodespaceSessionManager.AUTH_SAFE_UA
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url ?: return false
                                    val scheme = uri.scheme?.lowercase().orEmpty()
                                    val full = uri.toString()

                                    if (scheme == "aiapp") {
                                        GitHubLogin.extractTokenFromUri(uri)?.let { token ->
                                            vm.saveGithubToken(token)
                                        }
                                        CodespaceSessionManager.flushCookies()
                                        return true
                                    }

                                    if (scheme == "blob") {
                                        val safeUrl = full.replace("'", "")
                                        view.evaluateJavascript(
                                            "if (window.__aiAppDownloadBlobUrl) window.__aiAppDownloadBlobUrl('$safeUrl', 'app-debug.apk');",
                                            null,
                                        )
                                        return true
                                    }

                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }

                                    if (scheme == "vscode" || scheme == "vscode-insiders" || scheme == "github-codespaces") {
                                        return true
                                    }
                                    if (scheme == "intent") {
                                        runCatching {
                                            val parsed = Intent.parseUri(full, Intent.URI_INTENT_SCHEME)
                                            val fallback = parsed.getStringExtra("browser_fallback_url")
                                            if (!fallback.isNullOrBlank()) {
                                                view.loadUrl(fallback)
                                            }
                                        }
                                        return true
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        CodespaceSessionManager.lastLoadedUrl = url
                                    }
                                    canGoBackInWeb = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        CodespaceSessionManager.lastLoadedUrl = url
                                    }
                                    canGoBackInWeb = view.canGoBack()
                                    loadingProgress = null
                                    CodespaceSessionManager.flushCookies()
                                    CodespaceSessionManager.injectKeepAliveAndMobileUI(
                                        webView = view,
                                        userCodeToAutoFill = vm.githubCode?.userCode,
                                        paneMode = paneMode,
                                        uiZoomPercent = uiZoomPercent,
                                    )
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) {
                                        CodespaceSessionManager.hasMainFrameError = true
                                        networkError = "Connection interrupted (${error.description}). Tap Reconnect or wait for network."
                                    } else {
                                        CodespaceSessionManager.injectKeepAliveAndMobileUI(
                                            webView = view,
                                            userCodeToAutoFill = vm.githubCode?.userCode,
                                            paneMode = paneMode,
                                            uiZoomPercent = uiZoomPercent,
                                        )
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loadingProgress = if (newProgress in 1..99) newProgress / 100f else null
                                    if (newProgress >= 60) {
                                        CodespaceSessionManager.injectKeepAliveAndMobileUI(
                                            webView = view,
                                            userCodeToAutoFill = vm.githubCode?.userCode,
                                            paneMode = paneMode,
                                            uiZoomPercent = uiZoomPercent,
                                        )
                                    }
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }

                                override fun onShowFileChooser(
                                    webView: WebView,
                                    filePathCallback: ValueCallback<Array<Uri>>,
                                    fileChooserParams: FileChooserParams,
                                ): Boolean {
                                    fileChooserCallback?.onReceiveValue(null)
                                    fileChooserCallback = filePathCallback
                                    runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure {
                                        fileChooserCallback?.onReceiveValue(null)
                                        fileChooserCallback = null
                                    }
                                    return true
                                }

                                override fun onCreateWindow(
                                    view: WebView,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    val hitUrl = view.hitTestResult?.extra
                                    if (!hitUrl.isNullOrBlank() && !CodespaceSessionManager.isOAuthPopupUrl(hitUrl)) {
                                        navigateTo(hitUrl)
                                        return false
                                    }
                                    if (resultMsg != null) {
                                        popupMessage = resultMsg
                                        return true
                                    }
                                    return false
                                }

                                override fun onCloseWindow(window: WebView?) {
                                    popupMessage = null
                                }
                            }

                            if (url.isNullOrBlank()) {
                                if (!CodespaceSessionManager.savedState.isEmpty) {
                                    restoreState(CodespaceSessionManager.savedState)
                                }
                                if (url.isNullOrBlank()) {
                                    loadUrl(currentUrl)
                                }
                            }
                        }
                    },
                    update = { view ->
                        canGoBackInWeb = view.canGoBack()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showCreateRepoDialog) {
        CreateGitHubRepoDialog(
            vm = vm,
            onDismiss = { showCreateRepoDialog = false },
            onOpenUrlInWebView = { targetUrl ->
                showCreateRepoDialog = false
                navigateTo(targetUrl)
            },
        )
    }

    popupMessage?.let { msg ->
        OAuthPopupDialog(
            resultMessage = msg,
            onDismiss = {
                popupMessage = null
                CodespaceSessionManager.flushCookies()
                applyMobileSettings()
            },
            onNavigateInMain = { targetUrl ->
                popupMessage = null
                navigateTo(targetUrl)
            },
            onTokenCaptured = { token ->
                vm.saveGithubToken(token)
                popupMessage = null
            },
        )
    }
}

/**
 * 1-Tap "Create New GitHub Repository" Dialog:
 * Lets the user create a new GitHub repository (Public or Private) and either open it on GitHub
 * or immediately launch a new GitHub Codespace for it — OR open `https://github.com/new` directly in the WebView.
 */
@Composable
private fun CreateGitHubRepoDialog(
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenUrlInWebView: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var repoName by rememberSaveable { mutableStateOf("") }
    var repoDesc by rememberSaveable { mutableStateOf("") }
    var isPrivate by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!creating) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "➕ Create New GitHub Repository",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss, enabled = !creating, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                if (vm.githubToken().isBlank()) {
                    Text(
                        "You can either create the repository on github.com/new in the WebView, or sign in with GitHub for 1-tap API creation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onOpenUrlInWebView(CodespaceSessionManager.URL_GITHUB_NEW_REPO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("🌐 Open github.com/new in WebView")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.signInGithub { link -> onOpenUrlInWebView(link) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("🔑 Sign in with GitHub (1-Tap Device Flow)")
                    }
                    return@Column
                }

                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Repository name (e.g. my-android-app)") },
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repoDesc,
                    onValueChange = { repoDesc = it },
                    label = { Text("Description (optional)") },
                    maxLines = 2,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (isPrivate) "🔒 Private repository" else "🌐 Public repository (Free Actions)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it }, enabled = !creating)
                }

                errorMsg?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text("Creating repository on GitHub…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Button(
                    onClick = {
                        if (repoName.isBlank()) {
                            errorMsg = "Enter a repository name first."
                            return@Button
                        }
                        creating = true
                        errorMsg = null
                        scope.launch {
                            try {
                                val gh = GitHubClient(vm.githubToken())
                                val created = gh.createRepo(repoName.trim(), repoDesc.trim(), isPrivate = isPrivate)
                                val cs = runCatching { gh.createCodespace(created.owner, created.name, created.branch) }.getOrNull()
                                onOpenUrlInWebView(cs?.webUrl ?: created.url)
                            } catch (e: Throwable) {
                                errorMsg = e.message ?: "Could not create repository."
                            } finally {
                                creating = false
                            }
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("🚀 Create Repo & Launch Codespace")
                }

                OutlinedButton(
                    onClick = {
                        if (repoName.isBlank()) {
                            errorMsg = "Enter a repository name first."
                            return@OutlinedButton
                        }
                        creating = true
                        errorMsg = null
                        scope.launch {
                            try {
                                val gh = GitHubClient(vm.githubToken())
                                val created = gh.createRepo(repoName.trim(), repoDesc.trim(), isPrivate = isPrivate)
                                onOpenUrlInWebView(created.url)
                            } catch (e: Throwable) {
                                errorMsg = e.message ?: "Could not create repository."
                            } finally {
                                creating = false
                            }
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("✅ Create Repo Only & Open on GitHub")
                }

                TextButton(
                    onClick = { onOpenUrlInWebView(CodespaceSessionManager.URL_GITHUB_NEW_REPO) },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("🌐 Or open github.com/new web page")
                }
            }
        }
    }
}

@Composable
private fun IdeQuickButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MobileIdeBottomDock(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onAction: (String) -> Unit,
    onKey: (String) -> Unit,
    onShowKeyboard: () -> Unit,
    onHideDock: () -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockKey("⌨️", highlight = true, onClick = onShowKeyboard)
            DockKey("⏎ Enter", highlight = true, onClick = { onAction("enter") })
            DockKey("Esc", onClick = { onAction("escape") })
            DockKey("Tab", onClick = { onAction("tab") })
            DockKey("Ctrl", highlight = ctrlArmed, onClick = onToggleCtrl)
            DockKey("Alt", highlight = altArmed, onClick = onToggleAlt)
            DockKey("←", onClick = { onAction("arrow_left") })
            DockKey("↓", onClick = { onAction("arrow_down") })
            DockKey("↑", onClick = { onAction("arrow_up") })
            DockKey("→", onClick = { onAction("arrow_right") })
            DockKey("↶", onClick = { onAction("undo") })
            DockKey("↷", onClick = { onAction("redo") })
            listOf("{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "\"", "'", "=", "/", "\\", "|", "&", "$", "_", "-").forEach { sym ->
                DockKey(sym, onClick = { onKey(sym) })
            }
            DockKey("✕", onClick = onHideDock)
        }
    }
}

@Composable
private fun DockKey(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthPopupDialog(
    resultMessage: Message,
    onDismiss: () -> Unit,
    onNavigateInMain: (String) -> Unit,
    onTokenCaptured: (String) -> Unit,
) {
    var popupTitle by remember { mutableStateOf("Sign in") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        popupTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close window")
                    }
                }
                HorizontalDivider()
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                userAgentString = CodespaceSessionManager.AUTH_SAFE_UA
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val u = request.url ?: return false
                                    val full = u.toString()
                                    if (u.scheme?.lowercase() == "aiapp") {
                                        GitHubLogin.extractTokenFromUri(u)?.let(onTokenCaptured)
                                        onDismiss()
                                        return true
                                    }
                                    if (!CodespaceSessionManager.isOAuthPopupUrl(full) &&
                                        (full.contains("github.com/") || full.contains(".github.dev"))
                                    ) {
                                        onNavigateInMain(full)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank() &&
                                        !CodespaceSessionManager.isOAuthPopupUrl(url) &&
                                        (url.contains("github.com/new") || url.contains("github.com/codespaces") || url.contains(".github.dev"))
                                    ) {
                                        onNavigateInMain(url)
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    CodespaceSessionManager.flushCookies()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!title.isNullOrBlank()) popupTitle = title
                                }

                                override fun onCloseWindow(window: WebView?) {
                                    CodespaceSessionManager.flushCookies()
                                    onDismiss()
                                }
                            }
                            val transport = resultMessage.obj as? WebView.WebViewTransport
                            transport?.webView = this
                            resultMessage.sendToTarget()
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun CodespaceApiPanel(
    vm: ChatViewModel,
    codespaces: List<GitHubClient.Codespace>,
    repos: List<GitHubClient.Repo>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenCreateRepo: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCreateForRepo: (GitHubClient.Repo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 310.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your GitHub Repos & Codespaces",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onOpenCreateRepo) { Text("➕ New Repo") }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            }
            if (vm.githubToken().isBlank()) {
                Text(
                    "Sign in with GitHub to create repositories or launch your Codespaces directly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.signInGithub { link -> onOpenUrl(link) }
                    }) {
                        Text("Sign in with GitHub")
                    }
                    OutlinedButton(onClick = { onOpenUrl(CodespaceSessionManager.URL_GITHUB_NEW_REPO) }) {
                        Text("Open github.com/new")
                    }
                }
                return@Column
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (codespaces.isNotEmpty()) {
                    items(codespaces, key = { it.name }) { cs ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            if (cs.state != "Available") GitHubClient(vm.githubToken()).startCodespace(cs.name)
                                        }
                                        onOpenUrl(cs.webUrl)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cs.displayName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${cs.repoFullName} (${cs.branch}) · ${cs.state}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onOpenUrl(cs.webUrl) }) {
                                Text("Open")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (repos.isNotEmpty()) {
                    item {
                        Text(
                            "Create new Codespace from your repositories:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(repos.take(15), key = { "${it.owner}/${it.name}" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${r.owner}/${r.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onOpenUrl(r.url) }) {
                                Text("Repo")
                            }
                            TextButton(onClick = { onCreateForRepo(r) }, enabled = !loading) {
                                Text("+ Codespace")
                            }
                        }
                    }
                }
            }
        }
    }
}
