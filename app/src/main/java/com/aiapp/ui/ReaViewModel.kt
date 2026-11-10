package com.aiapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.aiapp.builder.ReaEngine
import com.aiapp.data.ApkInstaller
import java.io.File

class ReaViewModel : ViewModel() {

    /** Launches REA inspection on the given APK file and returns the summary. */
    fun inspectApk(apkFile: File, onResult: (ReaEngine.ReaApkSummary) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val summary = ReaEngine.inspectApk(apkFile)
                onResult(summary)
            } catch (e: Exception) {
                onError("Failed to inspect APK: ${e.message}")
            }
        }
    }

    /** Extracts reusable resources from an APK for bundling into a new app. */
    fun extractResources(apkFile: File, onResult: (ReaEngine.ExtractedResourcesBundle) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val bundle = ReaEngine.extractReusableResources(apkFile)
                onResult(bundle)
            } catch (e: Exception) {
                onError("Failed to extract resources: ${e.message}")
            }
        }
    }

    /** Installs an APK from a URL. */
    fun installApk(url: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                ApkInstaller.install(url)
                onSuccess()
            } catch (e: Exception) {
                onError("Install failed: ${e.message}")
            }
        }
    }
}
