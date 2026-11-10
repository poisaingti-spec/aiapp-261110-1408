package com.aiapp.llm

/**
 * Puts the most advanced models at the top of the picker and marks the best one with a star.
 * The lists still come live from each provider, so new releases show up without an app update;
 * this only decides the order.
 */
object ModelRanking {

    /** Family weight per provider: a higher number means a stronger model line. */
    private val families: Map<ProviderId, List<Pair<String, Int>>> = mapOf(
        ProviderId.CLAUDE to listOf(
            "mythos" to 60, "fable" to 50, "opus" to 40, "sonnet" to 30, "haiku" to 20,
        ),
        ProviderId.GEMINI to listOf(
            "deep-think" to 60, "ultra" to 55, "pro" to 50, "flash-lite" to 20, "flash" to 30, "nano" to 10,
        ),
        ProviderId.OPENAI to listOf(
            "-pro" to 55, "codex" to 50, "sol" to 45, "nano" to 10, "mini" to 20, "luna" to 30,
        ),
        // Biggest open-weight coding & unrestricted model lines first.
        ProviderId.OPEN to listOf(
            "qwen3-coder" to 68, "coder" to 65, "kimi" to 60, "deepseek" to 58, "glm" to 50, "qwen" to 48,
            "dolphin" to 46, "hermes" to 44, "minimax" to 40, "gpt-oss" to 35, "llama" to 30, "mistral" to 25, "gemma" to 20,
        ),
    )

    private val versionRegex = Regex("(\\d+(?:[.-]\\d{1,2}(?!\\d))?)")

    /** Version number in the id, e.g. "claude-opus-5-5" → 5.5, "gemini-3.8-flash" → 3.8, "gpt-5.6" → 5.6. */
    private fun version(id: String): Double {
        val m = versionRegex.findAll(id).firstOrNull { match ->
            // Skip date stamps like 20251001 or 2026-08-01.
            match.value.takeWhile { it.isDigit() }.length < 4
        } ?: return 0.0
        return m.value.replace('-', '.').toDoubleOrNull() ?: 0.0
    }

    private fun familyScore(provider: ProviderId, id: String): Int {
        val lower = id.lowercase()
        return families[provider].orEmpty().firstOrNull { lower.contains(it.first) }?.second
            ?: if (provider == ProviderId.OPENAI) 40 else 0
    }

    private fun isPreviewOrDated(id: String): Boolean {
        val lower = id.lowercase()
        return lower.contains("preview") || lower.contains("exp") || Regex("\\d{8}|\\d{4}-\\d{2}-\\d{2}").containsMatchIn(lower)
    }

    /**
     * Claude and Gemini: strongest family first (Fable above Opus above Sonnet; Pro above Flash), newest
     * version within each family. ChatGPT: newest version first, then Pro/Codex above standard above mini.
     * Stable aliases sort before dated or preview snapshots.
     */
    fun rank(provider: ProviderId, models: List<ModelInfo>): List<ModelInfo> {
        if (models.isEmpty()) return models
        val byFamily = compareByDescending<ModelInfo> { familyScore(provider, it.id) }
            .thenByDescending { version(it.id) }
        val byVersion = compareByDescending<ModelInfo> { version(it.id) }
            .thenByDescending { familyScore(provider, it.id) }
        val primary = if (provider == ProviderId.OPENAI) byVersion else byFamily
        val sorted = models.sortedWith(
            primary.thenBy { isPreviewOrDated(it.id) }
                .thenBy { it.id.endsWith(":free") }
                .thenBy { it.id },
        )
        val best = sorted.first()
        return listOf(best.copy(name = "★ ${best.name}")) + sorted.drop(1)
    }
}
