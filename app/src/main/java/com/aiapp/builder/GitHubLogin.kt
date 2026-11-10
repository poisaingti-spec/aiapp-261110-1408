package com.aiapp.builder

import android.net.Uri
import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * "Sign in with GitHub" supporting both GitHub's Device Flow (with in-app WebView or browser
 * approval) and OAuth redirect URI capture (`aiapp://oauth/github` or `aiapp://callback`),
 * including scopes for repositories, GitHub Actions workflows, and GitHub Codespaces.
 */
object GitHubLogin {
    /**
     * Client ID of the AI App OAuth app (public, not a secret). Created at
     * github.com/settings/developers with "Enable Device Flow" ticked. Can be overridden in Settings.
     */
    const val DEFAULT_CLIENT_ID = "Ov23li27PSK4KPEitrSN"

    /** Redirect URI registered in AndroidManifest.xml for OAuth callbacks. */
    const val REDIRECT_URI = "aiapp://oauth/github"

    /** Full scopes for Repo creation, Actions workflows, and GitHub Codespaces. */
    const val SCOPES = "repo workflow codespace read:user"
    const val FALLBACK_SCOPES = "public_repo workflow"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val interval: Int,
        val expiresIn: Int,
    )

    private suspend fun postForm(url: String, form: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AI-App-Android/1.0")
            .post(body)
            .build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty().trim()
            if (!r.isSuccessful && text.isEmpty()) {
                throw ProviderException(r.code, "GitHub sign-in failed (HTTP ${r.code}).")
            }
            // GitHub usually returns JSON when Accept: application/json is set, but can return
            // URL-encoded form parameters (e.g. access_token=...&scope=...) through some proxies.
            if (text.startsWith("{")) {
                val parsed = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (parsed != null) return@use parsed
            }
            if (text.contains("=")) {
                val map = text.split('&').mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }.toMap()
                if (map.isNotEmpty()) {
                    return@use buildJsonObject { map.forEach { (k, v) -> put(k, v) } }
                }
            }
            if (!r.isSuccessful) throw ProviderException(r.code, "GitHub sign-in failed (HTTP ${r.code}).")
            throw ProviderException(500, "Unexpected response from GitHub during sign-in.")
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Starts GitHub Device Flow; falls back to basic scopes if the OAuth app doesn't enable codespace scope. */
    suspend fun start(clientId: String): DeviceCode {
        val cleanId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        var lastError: Throwable? = null
        for (scopeToTry in listOf(SCOPES, FALLBACK_SCOPES)) {
            for (attempt in 0 until 4) {
                try {
                    val res = postForm(
                        "https://github.com/login/device/code",
                        mapOf("client_id" to cleanId, "scope" to scopeToTry),
                    )
                    val err = res.str("error")
                    if (err != null) {
                        if (scopeToTry == SCOPES && (err.contains("scope", true) || err == "invalid_scope")) {
                            break // retry with FALLBACK_SCOPES
                        }
                        throw ProviderException(
                            400,
                            when (err) {
                                "device_flow_disabled" -> "Turn on \"Enable Device Flow\" in your GitHub OAuth app settings, or sign in via the Codespace screen."
                                else -> "GitHub: ${res.str("error_description") ?: err}"
                            },
                        )
                    }
                    val deviceCode = res.str("device_code")
                        ?: throw ProviderException(500, "GitHub didn't return a device code.")
                    val userCode = res.str("user_code").orEmpty()
                    val verificationUri = res.str("verification_uri") ?: "https://github.com/login/device"
                    val completeUri = res.str("verification_uri_complete")
                    return DeviceCode(
                        deviceCode = deviceCode,
                        userCode = userCode,
                        verificationUri = verificationUri,
                        verificationUriComplete = completeUri,
                        interval = (res["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
                        expiresIn = (res["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
                    )
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < 3) delay(2000L * (attempt + 1))
                } catch (e: ProviderException) {
                    lastError = e
                    if (e.code >= 500 && attempt < 3) {
                        delay(2000L * (attempt + 1))
                    } else {
                        throw e
                    }
                }
            }
        }
        throw (lastError ?: IOException("No internet connection. Check your connection and try again."))
    }

    /**
     * Waits until you approve the code on github.com (either in the in-app WebView or external browser),
     * surviving app backgrounding and brief network switches, then returns the access token.
     */
    suspend fun waitForToken(clientId: String, code: DeviceCode): String = withContext(Dispatchers.IO) {
        val cleanId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        var interval = code.interval.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            // Keep waiting through brief network drops or HTTP 5xx while the app is in the background.
            val r = try {
                withContext(NonCancellable) {
                    postForm(
                        "https://github.com/login/oauth/access_token",
                        mapOf(
                            "client_id" to cleanId,
                            "device_code" to code.deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        ),
                    )
                }
            } catch (e: IOException) {
                continue
            } catch (e: ProviderException) {
                if (e.code >= 500 || e.code == 408 || e.code == 429) continue
                throw e
            }
            r.str("access_token")?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            when (r.str("error")) {
                "authorization_pending", null -> Unit
                "slow_down" -> interval = (r["interval"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(interval + 5) ?: (interval + 5)
                "expired_token" -> throw ProviderException(408, "The sign-in code expired. Tap Sign in with GitHub again.")
                "access_denied" -> throw ProviderException(403, "Sign-in was cancelled on GitHub.")
                else -> throw ProviderException(400, "GitHub: ${r.str("error_description") ?: r.str("error")}")
            }
        }
        throw ProviderException(408, "The sign-in code expired. Tap Sign in with GitHub again.")
    }

    /** Builds a direct OAuth web authorization URL for in-app WebView or browser flows. */
    fun authorizeUrl(clientId: String = DEFAULT_CLIENT_ID, state: String = "aiapp_auth"): String {
        val cleanId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        return "https://github.com/login/oauth/authorize" +
            "?client_id=" + URLEncoder.encode(cleanId, "UTF-8") +
            "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&scope=" + URLEncoder.encode(SCOPES, "UTF-8") +
            "&state=" + URLEncoder.encode(state, "UTF-8")
    }

    /** Exchanges an OAuth authorization `code` for an `access_token` if a client secret is provided. */
    suspend fun exchangeCodeForToken(clientId: String, clientSecret: String, code: String): String {
        val r = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId.trim().ifEmpty { DEFAULT_CLIENT_ID },
                "client_secret" to clientSecret.trim(),
                "code" to code.trim(),
                "redirect_uri" to REDIRECT_URI,
            ),
        )
        r.str("access_token")?.takeIf { it.isNotBlank() }?.let { return it }
        throw ProviderException(400, "GitHub OAuth: ${r.str("error_description") ?: r.str("error") ?: "Failed to exchange code"}")
    }

    /**
     * Extracts an access token or authorization parameter from an OAuth redirect URI
     * (e.g. `aiapp://oauth/github?access_token=...` or `#access_token=...`).
     */
    fun extractTokenFromUri(uri: Uri?): String? {
        if (uri == null) return null
        uri.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { return it }
        val fragment = uri.fragment.orEmpty()
        if (fragment.isNotBlank()) {
            fragment.split('&').forEach { part ->
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && (kv[0] == "access_token" || kv[0] == "token") && kv[1].isNotBlank()) {
                    return URLDecoder.decode(kv[1], "UTF-8")
                }
            }
        }
        return null
    }
}
