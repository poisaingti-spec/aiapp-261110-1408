package com.aiapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aiapp.llm.ProviderId

/** API keys, encrypted with a key held in the Android Keystore. Never leaves the phone. */
class ApiKeys(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "api_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(provider: ProviderId): String = prefs.getString(provider.name, "").orEmpty()

    fun set(provider: ProviderId, key: String) {
        prefs.edit().putString(provider.name, key.trim()).apply()
    }

    /** Other secrets, such as the GitHub token Build Mode uses. */
    fun getExtra(name: String): String = prefs.getString("extra_$name", "").orEmpty()

    fun setExtra(name: String, value: String) {
        prefs.edit().putString("extra_$name", value.trim()).apply()
    }
}
