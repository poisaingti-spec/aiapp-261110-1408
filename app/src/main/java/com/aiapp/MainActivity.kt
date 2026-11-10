package com.aiapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.BuildScreen
import com.aiapp.ui.BuildViewModel
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.CodespaceScreen
import com.aiapp.ui.CodespaceSessionManager
import com.aiapp.ui.CreateScreen
import com.aiapp.ui.CreateViewModel
import com.aiapp.ui.HistoryScreen
import com.aiapp.ui.ReaScreen
import com.aiapp.ui.ReaViewModel
import com.aiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.aiapp.ui.ImageLoader.clear()
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!com.aiapp.builder.BuildRunner.running && !chatVm.streaming) {
                com.aiapp.llm.Providers.offlineIfLoaded?.release()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatVm.handleOAuthIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatVm.handleOAuthIntent(intent)
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel<ChatViewModel>()
                val buildVm: BuildViewModel = viewModel<BuildViewModel>()
                val createVm: CreateViewModel = viewModel<CreateViewModel>()
                val reaVm: ReaViewModel = viewModel<ReaViewModel>()

                var screen by rememberSaveable { mutableStateOf("chat") }
                var previousScreen by rememberSaveable { mutableStateOf("chat") }
                var hasOpenedCodespace by rememberSaveable { mutableStateOf(false) }
                var requestedCodespaceUrl by rememberSaveable { mutableStateOf<String?>(null) }

                /**
                 * Switches to the persistent Codespace view without reloading!
                 * - When [targetUrl] is null (e.g., tapping "Codespace" in Chat, REA, or Build), it resumes
                 *   the exact live WebView state in 0ms without touching `lastLoadedUrl`.
                 * - Only navigates to a new URL when [targetUrl] is explicitly provided.
                 */
                fun navigateToCodespace(targetUrl: String? = null) {
                    if (screen != "codespace") previousScreen = screen
                    hasOpenedCodespace = true
                    if (!targetUrl.isNullOrBlank()) {
                        requestedCodespaceUrl = targetUrl
                    }
                    screen = "codespace"
                }

                BackHandler(enabled = screen != "chat" && screen != "codespace") {
                    screen = "chat"
                }

                Box(Modifier.fillMaxSize()) {
                    // Active App Screen Layer (Chat, Build, REA, Create, History, Settings)
                    Box(
                        modifier = if (screen == "codespace") {
                            Modifier.size(1.dp).alpha(0f).zIndex(-1f)
                        } else {
                            Modifier.fillMaxSize().zIndex(1f)
                        },
                    ) {
                        when (screen) {
                            "history" -> HistoryScreen(vm, onBack = { screen = "chat" })
                            "create" -> CreateScreen(createVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                            "rea" -> ReaScreen(
                                chatVm = vm,
                                reaVm = reaVm,
                                onBack = { screen = "chat" },
                                onOpenBuild = { screen = "build" },
                                onOpenCodespace = { url -> navigateToCodespace(url) },
                                onOpenSettings = { screen = "settings" },
                            )
                            "build" -> BuildScreen(
                                vm,
                                buildVm,
                                onBack = { screen = "chat" },
                                onOpenSettings = { screen = "settings" },
                                onOpenCodespace = { url -> navigateToCodespace(url) },
                            )
                            "settings" -> SettingsScreen(
                                vm,
                                onBack = { screen = "chat" },
                                onOpenCodespace = { url -> navigateToCodespace(url) },
                            )
                            else -> ChatScreen(
                                vm,
                                onOpenSettings = { screen = "settings" },
                                onOpenBuild = { screen = "build" },
                                onOpenRea = { screen = "rea" },
                                onOpenCodespace = { navigateToCodespace(null) },
                                onOpenHistory = { screen = "history" },
                                onOpenCreate = { screen = "create" },
                            )
                        }
                    }

                    // Persistent Zero-Reload Codespace Layer
                    if (hasOpenedCodespace || screen == "codespace") {
                        Box(
                            modifier = if (screen == "codespace") {
                                Modifier.fillMaxSize().zIndex(10f)
                            } else {
                                Modifier.size(1.dp).alpha(0f).zIndex(-1f)
                            },
                        ) {
                            CodespaceScreen(
                                vm = vm,
                                initialUrl = CodespaceSessionManager.URL_GITHUB_CODESPACES,
                                requestedUrl = requestedCodespaceUrl,
                                onConsumeRequestedUrl = { requestedCodespaceUrl = null },
                                isVisible = screen == "codespace",
                                onBack = {
                                    screen = if (previousScreen != "codespace") previousScreen else "chat"
                                },
                                onSwitchToChat = { screen = "chat" },
                                onSwitchToBuild = { screen = "build" },
                            )
                        }
                    }
                }
            }
        }
    }
}
