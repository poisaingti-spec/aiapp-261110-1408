package com.aiapp.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.Project
import com.aiapp.builder.ProjectTemplate
import com.aiapp.builder.TimelineItem
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.llm.alternativeModel
import com.aiapp.llm.resilient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages the Build tab:
 * - Supports both **Direct Conversational Q&A** (answering specific questions like "what more can we add? reply"
 *   or explaining code without starting a build) AND **Explicit APK Building** (writing code & compiling on GitHub Actions
 *   only when the user directs it to build).
 */
class BuildViewModel(private val app: Application) : AndroidViewModel(app) {
    init {
        BuildRunner.load(app)
    }

    val timeline: List<TimelineItem> get() = BuildRunner.timeline
    val running: Boolean get() = BuildRunner.running
    val project: Project? get() = BuildRunner.project

    var answering by mutableStateOf(false)
        private set
    var answeringText by mutableStateOf("")
        private set
    var answeringThinking by mutableStateOf("")
        private set

    private var answerJob: Job? = null

    private val questionPattern = Regex(
        "(\\?\\s*$|^\\s*(what|how|why|which|where|who|when|can\\s+you|can\\s+we|could\\s+we|should\\s+we|tell\\s+me|explain|describe|list|suggest|review|reply|answer|is\\s+there|are\\s+there|do\\s+you)\\b|\\b(reply|explain|tell\\s+me|what\\s+more\\s+can\\s+we\\s+add)\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val explicitBuildCommandPattern = Regex(
        "\\b(build\\s+(it|this|now|apk|app|the\\s+app|the\\s+apk)|compile\\s+(it|this|now|apk|to\\s+apk)|start\\s+build|make\\s+the\\s+apk|implement\\s+and\\s+build|apply\\s+and\\s+build)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns true when [input] is a question, brainstorm request, or conversational message
     * (like `"what more can we add? reply"`) rather than an explicit instruction to start building an APK.
     */
    fun isQuestionIntent(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        if (explicitBuildCommandPattern.containsMatchIn(trimmed)) return false
        if (questionPattern.containsMatchIn(trimmed)) return true
        return false
    }

    /**
     * Answers a user question directly inside the Build tab timeline (with full context of the open project)
     * WITHOUT modifying files or triggering a GitHub Actions APK build.
     */
    fun askQuestion(
        question: String,
        provider: ProviderId,
        model: String,
        apiKey: String,
        effort: Effort,
        attachments: List<Attachment> = emptyList(),
    ) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || answering || running) return
        BuildRunner.timeline += TimelineItem.User(trimmed)
        BuildRunner.save(app)

        answering = true
        answeringText = ""
        answeringThinking = ""

        val proj = project
        val contextPrompt = buildString {
            if (proj != null) {
                appendLine("Current open Android project in Build Tab: \"${proj.appName}\" (package `${proj.packageName}`).")
                if (proj.description.isNotBlank()) {
                    appendLine("Original project description: ${proj.description}")
                }
                if (proj.files.isNotEmpty()) {
                    appendLine("Files in project (${proj.files.size} total): ${proj.files.keys.sorted().joinToString(", ")}")
                    var budget = if (provider == ProviderId.OFFLINE) 12_000 else 32_000
                    for ((path, content) in proj.files) {
                        if (budget <= 0) break
                        val snippet = content.take(budget.coerceAtMost(4_000))
                        budget -= snippet.length
                        appendLine("\n--- $path ---\n$snippet")
                    }
                }
                appendLine("\n---")
            }
            appendLine("User question / message: $trimmed")
            appendLine("\nAnswer the user's question directly and clearly in Markdown. Do NOT start a build or output `=== FILE:` blocks unless the user explicitly clicks Build.")
        }

        // Include recent Q&A turns from timeline for conversational continuity
        val historyMessages = mutableListOf<ChatMessage>()
        BuildRunner.timeline.dropLast(1).takeLast(6).forEach { item ->
            when (item) {
                is TimelineItem.User -> historyMessages += ChatMessage(Role.USER, item.text)
                is TimelineItem.Reply -> historyMessages += ChatMessage(Role.ASSISTANT, item.text)
                is TimelineItem.Result -> historyMessages += ChatMessage(Role.ASSISTANT, item.message)
                else -> Unit
            }
        }
        historyMessages += ChatMessage(Role.USER, contextPrompt, attachments)

        answerJob = viewModelScope.launch {
            val textBuilder = StringBuilder()
            val thinkBuilder = StringBuilder()
            try {
                val chosenEffort = if (effort == Effort.AUTO) Effort.HIGH else effort
                resilient(
                    attempt = { mId ->
                        Providers.get(provider).stream(
                            apiKey = apiKey,
                            model = mId,
                            messages = historyMessages,
                            effort = chosenEffort,
                            system = ProjectTemplate.qaSystemPrompt,
                        )
                    },
                    model = model,
                    fallback = { alternativeModel(provider, apiKey, model) },
                    onRetry = { statusMsg -> answeringThinking = statusMsg },
                ).collect { delta ->
                    if (delta.startsWith(THOUGHT)) {
                        thinkBuilder.append(delta.substring(1))
                        answeringThinking = thinkBuilder.toString()
                    } else {
                        textBuilder.append(delta)
                        answeringText = textBuilder.toString()
                    }
                }
                val finalAnswer = textBuilder.toString().trim().ifBlank {
                    "I'm ready to help with your project! Ask me anything about the code or tap **🔨 Build** when you want me to implement changes and compile the APK."
                }
                BuildRunner.timeline += TimelineItem.Reply(
                    text = finalAnswer,
                    thinking = thinkBuilder.toString().trim(),
                )
                BuildRunner.saveNow(app)
            } catch (e: CancellationException) {
                val partial = textBuilder.toString().trim()
                if (partial.isNotBlank()) {
                    BuildRunner.timeline += TimelineItem.Reply(partial, thinkBuilder.toString().trim())
                    BuildRunner.saveNow(app)
                }
                throw e
            } catch (e: Throwable) {
                BuildRunner.timeline += TimelineItem.Reply(
                    text = "⚠️ Couldn't complete reply: ${e.message ?: "Network error"}. Try again or switch model in Chat.",
                )
                BuildRunner.saveNow(app)
            } finally {
                answering = false
                answeringText = ""
                answeringThinking = ""
            }
        }
    }

    fun stopAnswering() {
        answerJob?.cancel()
    }

    fun start(request: BuildRequest) = BuildService.start(app, request)

    fun stop() {
        answerJob?.cancel()
        BuildService.stop(app)
    }

    fun newProject() = BuildRunner.newProject(app)

    fun projects() = BuildRunner.list(app)

    fun openProject(id: String) = BuildRunner.open(app, id)

    fun deleteProject(id: String) = BuildRunner.delete(app, id)
}
