package com.aiapp.builder

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Android Native Port of `github.com/morluto/rea` (Reverse Engineer Anything).
 *
 * Ports `morluto/rea`'s four Android static analysis & reconstruction tool families directly to
 * Kotlin on Android (zero desktop JDK, Node.js, or external binaries required):
 * 1. `inspect_apk_signature` (`docs/apk-signature.md`):
 *    - Computes SHA-256 artifact digest, inspects the ZIP tail for the APK Signing Block
 *      (`APK Sig Block 42` — v2 `0x7109871a`, v3 `0xf05368c0`, v3.1 `0x1b93ad61`), and parses
 *      `META-INF/*.RSA|*.DSA|*.EC` X.509 signer certificates.
 * 2. `inspect_android_package` (`docs/android-analysis.md`):
 *    - Full Binary XML (`AXML`) tree decoder (`decodeBinaryXml`) that reconstructs human-readable
 *      `AndroidManifest.xml` (package, versionCode, versionName, minSdk, targetSdk, permissions,
 *      activities, launcher intent-filters, services, receivers, providers).
 *    - Multi-DEX (`classes*.dex`) bytecode table parser (`string_ids`, `type_ids`, `proto_ids`,
 *      `field_ids`, `method_ids`, `class_defs`) that extracts domain classes, method signatures
 *      (`ClassName.method(params): ReturnType`), and field declarations (`ClassName.field: Type`).
 * 3. `apktool_resource_analysis` (`docs/apktool-resource-analysis.md`):
 *    - Decodes binary XML screen layouts (`res/layout/*.xml`) into readable XML view trees,
 *      extracts UI string literals from `resources.arsc` / DEX pools, and inventories drawables,
 *      raw media, and `assets/`.
 * 4. `project_android_application_graph` & `extractReusableResources`:
 *    - Maps `lib/<abi>/*.so` JNI libraries and extracts reusable raster drawables (`.png`, `.jpg`,
 *      `.webp`), fonts (`.ttf`, `.otf`), raw media (`res/raw/*`), and `assets/**` from the uploaded
 *      APK so the rebuilt app can bundle and use the original app's real resources under a new name.
 */
object ReaEngine {

    const val REA_SYSTEM_PROMPT = """
[REA — REVERSE ENGINEER ANYTHING (github.com/morluto/rea) ANDROID RECONSTRUCTION PROTOCOL]:
You are an elite Android reverse-engineering and clean-room reconstruction agent powered by the REA (Reverse Engineer Anything) framework (`github.com/morluto/rea`).

Your objective is to take the structured REA Evidence extracted from the uploaded APK (or target website/app description):
- Decoded `AndroidManifest.xml` (activities, services, receivers, permissions, SDK targets)
- Decompiled `classes*.dex` class hierarchy, method signatures (`Class.method(params): ReturnType`), and field types
- Decoded `res/layout/*.xml` UI view trees and extracted UI strings
- Discovered network endpoints, API routes, and data structures
- Extracted reusable APK resources (`R.drawable.*`, `R.raw.*`, `R.font.*`, and `assets/*`)

And build a COMPLETE, WORKING Android app in Kotlin + Jetpack Compose (Material 3) that:
1. Uses the user's requested **New App Name** and **New Package Name** so it installs cleanly as an independent app.
2. Replicates every screen, workflow, navigation path, calculation, and feature of the original app faithfully.
3. Applies every **Custom Modification** requested by the user (e.g., UI redesign, offline mode, new features, ad/tracker removal, dark theme, custom branding).
4. Uses the extracted `R.drawable.*`, `R.font.*`, `R.raw.*`, and `assets/*` resources from the original APK wherever applicable.
5. Uses `<search>query</search>` or `<fetch>https://...</fetch>` whenever internet research is needed to verify APIs, protocols, or domain logic.
6. Writes every file in full without placeholders, `...`, or TODOs.
"""

    data class ApkSignatureReport(
        val sha256Digest: String,
        val v1Present: Boolean,
        val v2Present: Boolean,
        val v3Present: Boolean,
        val v31Present: Boolean,
        val signerSubject: String,
        val signerIssuer: String,
        val certSha256: String,
    ) {
        val schemesSummary: String
            get() = buildList {
                if (v1Present) add("v1 (JAR)")
                if (v2Present) add("v2 (APK Sig Block)")
                if (v3Present) add("v3 (Key Rotation)")
                if (v31Present) add("v3.1")
                if (isEmpty()) add("Unsigned / Unknown")
            }.joinToString(" + ")
    }

    data class ExtractedResourcesBundle(
        /** Map of relative project path (`app/src/main/res/drawable/...` or `app/src/main/assets/...`) to raw bytes. */
        val files: Map<String, ByteArray>,
        /** Prompt note listing every extracted `R.drawable.*`, `R.font.*`, `R.raw.*`, and `assets/*` reference. */
        val promptSummary: String,
    )

    data class ReaApkSummary(
        val fileName: String,
        val fileSizeMb: Double,
        val packageName: String,
        val appLabel: String,
        val versionName: String,
        val versionCode: String,
        val minSdk: String,
        val targetSdk: String,
        val signatureReport: ApkSignatureReport,
        val decodedManifestXml: String,
        val permissions: List<String>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>,
        val dexFileCount: Int,
        val classesCount: Int,
        val methodsCount: Int,
        val fieldsCount: Int,
        val appClasses: List<String>,
        val methodSignatures: List<String>,
        val fieldSignatures: List<String>,
        val nativeLibraries: List<String>,
        val networkUrls: List<String>,
        val stringConstants: List<String>,
        val layouts: List<String>,
        val decodedLayouts: Map<String, String>,
        val assets: List<String>,
        val drawablesCount: Int,
        val reusableResourcePaths: List<String>,
        val fullMarkdownSpec: String,
    )

    /**
     * Runs the full `morluto/rea` static inspection pipeline (`inspect_apk_signature`,
     * `inspect_android_package`, `apktool_resource_analysis`, and `project_android_application_graph`)
     * on [apkFile] directly on the Android device.
     */
    fun inspectApk(apkFile: File): ReaApkSummary {
        val sigReport = inspectApkSignature(apkFile)

        var pkgName = "com.example.app"
        var appLabel = apkFile.nameWithoutExtension
        var versionName = "1.0"
        var versionCode = "1"
        var minSdk = "26"
        var targetSdk = "35"
        var decodedManifest = ""

        val permissions = linkedSetOf<String>()
        val activities = linkedSetOf<String>()
        val services = linkedSetOf<String>()
        val receivers = linkedSetOf<String>()
        val providers = linkedSetOf<String>()

        val allClasses = linkedSetOf<String>()
        val allMethods = linkedSetOf<String>()
        val allFields = linkedSetOf<String>()
        val networkUrls = linkedSetOf<String>()
        val stringConstants = linkedSetOf<String>()
        val nativeLibs = linkedSetOf<String>()

        val layouts = mutableListOf<String>()
        val decodedLayouts = linkedMapOf<String, String>()
        val assets = mutableListOf<String>()
        val reusablePaths = mutableListOf<String>()
        var drawablesCount = 0
        var dexFileCount = 0
        var totalDexClasses = 0
        var totalDexMethods = 0
        var totalDexFields = 0

        ZipFile(apkFile).use { zip ->
            // 1. Decode AndroidManifest.xml (Binary AXML -> full XML + structured attributes)
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val parsedManifest = decodeBinaryXml(manifestBytes)
                decodedManifest = parsedManifest.xmlText
                if (parsedManifest.packageName.isNotBlank()) pkgName = parsedManifest.packageName
                if (parsedManifest.appLabel.isNotBlank() && !parsedManifest.appLabel.startsWith("@")) {
                    appLabel = parsedManifest.appLabel
                }
                if (parsedManifest.versionName.isNotBlank()) versionName = parsedManifest.versionName
                if (parsedManifest.versionCode.isNotBlank()) versionCode = parsedManifest.versionCode
                if (parsedManifest.minSdk.isNotBlank()) minSdk = parsedManifest.minSdk
                if (parsedManifest.targetSdk.isNotBlank()) targetSdk = parsedManifest.targetSdk

                permissions.addAll(parsedManifest.permissions)
                activities.addAll(parsedManifest.activities)
                services.addAll(parsedManifest.services)
                receivers.addAll(parsedManifest.receivers)
                providers.addAll(parsedManifest.providers)

                // Also scan string pool for any additional components
                for (s in parsedManifest.stringPool) {
                    when {
                        s.startsWith("android.permission.") || (s.contains(".permission.") && !s.contains(" ")) -> permissions.add(s)
                        (s.endsWith("Activity") || s.contains("Activity$")) && !s.contains(" ") && !s.startsWith("android.") -> activities.add(s)
                        s.endsWith("Service") && !s.contains(" ") && !s.startsWith("android.") -> services.add(s)
                        s.endsWith("Receiver") && !s.contains(" ") && !s.startsWith("android.") -> receivers.add(s)
                    }
                }
            }

            // 2. Parse all classes*.dex files (classes, method signatures, field declarations, strings, URLs)
            val dexEntries = zip.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
            dexFileCount = dexEntries.size

            val pkgPrefix = pkgName.split('.').take(2).joinToString(".")
            for (dexEntry in dexEntries) {
                val dexBytes = runCatching { zip.getInputStream(dexEntry).use { it.readBytes() } }.getOrNull() ?: continue
                val dexResult = parseDexFull(dexBytes, pkgPrefix)
                totalDexClasses += dexResult.classCount
                totalDexMethods += dexResult.methodCount
                totalDexFields += dexResult.fieldCount
                allClasses.addAll(dexResult.classes)
                allMethods.addAll(dexResult.methodSignatures)
                allFields.addAll(dexResult.fieldSignatures)
                networkUrls.addAll(dexResult.urls)
                stringConstants.addAll(dexResult.uiStrings)
            }

            // 3. Inspect layouts, drawables, native libraries, and assets
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val n = entry.name
                when {
                    n.startsWith("res/layout") && n.endsWith(".xml") -> {
                        val shortName = n.substringAfterLast('/')
                        if (shortName !in layouts) layouts.add(shortName)
                        if (decodedLayouts.size < 12 && !shortName.startsWith("abc_") && !shortName.startsWith("m3_") &&
                            !shortName.startsWith("design_") && !shortName.startsWith(" mtrl_") && !shortName.startsWith("notification_")
                        ) {
                            runCatching {
                                val raw = zip.getInputStream(entry).use { it.readBytes() }
                                val xml = decodeBinaryXml(raw).xmlText
                                if (xml.isNotBlank()) decodedLayouts[shortName] = xml.take(2_500)
                            }
                        }
                    }
                    n.startsWith("res/drawable") || n.startsWith("res/mipmap") -> {
                        drawablesCount++
                        if ((n.endsWith(".png", true) || n.endsWith(".jpg", true) || n.endsWith(".webp", true)) &&
                            !n.endsWith(".9.png", true) && !n.substringAfterLast('/').startsWith("abc_") &&
                            !n.substringAfterLast('/').startsWith("notification_")
                        ) {
                            reusablePaths.add(n)
                        }
                    }
                    n.startsWith("res/raw/") || n.startsWith("res/font/") -> {
                        reusablePaths.add(n)
                    }
                    n.startsWith("assets/") -> {
                        assets.add(n.removePrefix("assets/"))
                        reusablePaths.add(n)
                    }
                    n.startsWith("lib/") && n.endsWith(".so") -> {
                        nativeLibs.add(n.removePrefix("lib/"))
                    }
                }
            }
        }

        // Filter out standard AndroidX / Kotlin / Google library classes so the app's own classes stand out
        val appClasses = allClasses.filter { c ->
            !c.startsWith("android.") && !c.startsWith("androidx.") &&
                !c.startsWith("kotlin.") && !c.startsWith("kotlinx.") &&
                !c.startsWith("java.") && !c.startsWith("javax.") &&
                !c.startsWith("com.google.android.material.") &&
                !c.startsWith("com.google.common.") &&
                !c.startsWith("org.jetbrains.") &&
                !c.startsWith("okhttp3.") && !c.startsWith("okio.") &&
                !c.contains("$$") && !c.endsWith("BuildConfig") && !c.endsWith(".R") && !c.contains(".R$")
        }.sorted()

        if (appLabel == apkFile.nameWithoutExtension && pkgName != "com.example.app") {
            appLabel = pkgName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        val sizeMb = apkFile.length() / (1024.0 * 1024.0)

        val fullSpec = buildString {
            appendLine("# 🔍 REA (Reverse Engineer Anything) Evidence Report: ${apkFile.name}")
            appendLine("Generated by AI App Native REA Engine (`github.com/morluto/rea` Android Port).")
            appendLine()
            appendLine("## 1. Package & APK Signature Evidence (`inspect_apk_signature` & `inspect_android_package`)")
            appendLine("- **Original File**: `${apkFile.name}` (${"%.2f".format(sizeMb)} MB)")
            appendLine("- **SHA-256 Artifact Digest**: `${sigReport.sha256Digest}`")
            appendLine("- **Signing Schemes**: ${sigReport.schemesSummary}")
            if (sigReport.signerSubject.isNotBlank()) {
                appendLine("- **Signer Certificate**: `${sigReport.signerSubject}` (Cert SHA-256: `${sigReport.certSha256.take(32)}…`)")
            }
            appendLine("- **Original Package ID**: `$pkgName`")
            appendLine("- **Detected App Label**: `$appLabel`")
            appendLine("- **Version**: `$versionName` (versionCode `$versionCode`) | **SDK**: `minSdk=$minSdk`, `targetSdk=$targetSdk`")
            appendLine("- **Bytecode Totals**: $dexFileCount DEX file(s), $totalDexClasses classes, $totalDexMethods methods, $totalDexFields fields")
            appendLine("- **Resources**: ${layouts.size} XML layouts (${decodedLayouts.size} decoded), $drawablesCount drawables, ${assets.size} bundled assets, ${nativeLibs.size} native `.so` libraries")
            appendLine()

            if (permissions.isNotEmpty()) {
                appendLine("## 2. Declared Permissions (${permissions.size})")
                permissions.sorted().forEach { appendLine("- `$it`") }
                appendLine()
            }

            if (activities.isNotEmpty() || services.isNotEmpty() || receivers.isNotEmpty() || providers.isNotEmpty()) {
                appendLine("## 3. Android Components (`AndroidManifest.xml`)")
                activities.forEach { appendLine("- **Activity**: `$it`") }
                services.forEach { appendLine("- **Service**: `$it`") }
                receivers.forEach { appendLine("- **Receiver**: `$it`") }
                providers.forEach { appendLine("- **Provider**: `$it`") }
                appendLine()
            }

            if (decodedManifest.isNotBlank()) {
                appendLine("## 4. Decoded `AndroidManifest.xml`")
                appendLine("```xml")
                appendLine(decodedManifest.take(4_500))
                appendLine("```")
                appendLine()
            }

            if (appClasses.isNotEmpty()) {
                appendLine("## 5. Decompiled App Domain Classes (${appClasses.size} found)")
                appClasses.take(65).forEach { appendLine("- `$it`") }
                if (appClasses.size > 65) appendLine("*(+ ${appClasses.size - 65} additional classes)*")
                appendLine()
            }

            if (allMethods.isNotEmpty()) {
                appendLine("## 6. Decompiled Method Signatures (`inspect_android_method` — ${allMethods.size} sampled)")
                allMethods.take(70).forEach { appendLine("- `$it`") }
                appendLine()
            }

            if (allFields.isNotEmpty()) {
                appendLine("## 7. Decompiled Field Declarations (${allFields.size} sampled)")
                allFields.take(45).forEach { appendLine("- `$it`") }
                appendLine()
            }

            if (decodedLayouts.isNotEmpty()) {
                appendLine("## 8. Decoded Screen Layouts (`apktool_resource_analysis`)")
                decodedLayouts.entries.take(6).forEach { (name, xml) ->
                    appendLine("### Layout: `res/layout/$name`")
                    appendLine("```xml")
                    appendLine(xml.take(1_500))
                    appendLine("```")
                }
                appendLine()
            } else if (layouts.isNotEmpty()) {
                appendLine("## 8. Screen Layout Files (${layouts.size})")
                layouts.take(30).forEach { appendLine("- `res/layout/$it`") }
                appendLine()
            }

            if (networkUrls.isNotEmpty()) {
                appendLine("## 9. Discovered Network Endpoints & Deep Links (${networkUrls.size})")
                networkUrls.take(30).forEach { appendLine("- `$it`") }
                appendLine()
            }

            if (stringConstants.isNotEmpty()) {
                appendLine("## 10. Extracted UI Strings & Domain Constants")
                stringConstants.take(50).forEach { appendLine("- \"${it.replace("\n", " ").take(100)}\"") }
                appendLine()
            }

            if (nativeLibs.isNotEmpty()) {
                appendLine("## 11. Native JNI Libraries (`project_android_application_graph`)")
                nativeLibs.take(20).forEach { appendLine("- `lib/$it`") }
                appendLine()
            }

            if (assets.isNotEmpty()) {
                appendLine("## 12. Bundled Assets (`assets/`)")
                assets.take(25).forEach { appendLine("- `assets/$it`") }
                appendLine()
            }
        }

        return ReaApkSummary(
            fileName = apkFile.name,
            fileSizeMb = sizeMb,
            packageName = pkgName,
            appLabel = appLabel,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            signatureReport = sigReport,
            decodedManifestXml = decodedManifest,
            permissions = permissions.toList(),
            activities = activities.toList(),
            services = services.toList(),
            receivers = receivers.toList(),
            providers = providers.toList(),
            dexFileCount = dexFileCount,
            classesCount = totalDexClasses,
            methodsCount = totalDexMethods,
            fieldsCount = totalDexFields,
            appClasses = appClasses,
            methodSignatures = allMethods.toList(),
            fieldSignatures = allFields.toList(),
            nativeLibraries = nativeLibs.toList(),
            networkUrls = networkUrls.toList(),
            stringConstants = stringConstants.toList(),
            layouts = layouts,
            decodedLayouts = decodedLayouts,
            assets = assets,
            drawablesCount = drawablesCount,
            reusableResourcePaths = reusablePaths,
            fullMarkdownSpec = fullSpec,
        )
    }

    /**
     * Extracts reusable non-compiled resources (raster drawables `.png`/`.jpg`/`.webp`,
     * fonts `.ttf`/`.otf`, raw media `res/raw/*`, and `assets/*`) from [apkFile] so they can be
     * bundled directly into the rebuilt Android Studio project.
     */
    fun extractReusableResources(apkFile: File, maxTotalBytes: Long = 18L * 1024 * 1024): ExtractedResourcesBundle {
        val outFiles = linkedMapOf<String, ByteArray>()
        val notes = mutableListOf<String>()
        var usedBytes = 0L
        val seenResourceNames = mutableSetOf<String>()

        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory }
                // Prioritize higher-density drawables (xxhdpi / xhdpi) and assets
                val sorted = entries.sortedBy { e ->
                    when {
                        e.name.contains("xxhdpi") -> 0
                        e.name.contains("xhdpi") -> 1
                        e.name.contains("hdpi") -> 2
                        e.name.startsWith("res/font/") || e.name.startsWith("res/raw/") -> 3
                        e.name.startsWith("assets/") -> 4
                        else -> 5
                    }
                }

                for (entry in sorted) {
                    val n = entry.name
                    if (n.contains("..")) continue
                    val ext = n.substringAfterLast('.', "").lowercase()
                    val baseRaw = n.substringAfterLast('/').substringBeforeLast('.')

                    when {
                        (n.startsWith("res/drawable") || n.startsWith("res/mipmap")) &&
                            ext in listOf("png", "jpg", "jpeg", "webp") &&
                            !n.endsWith(".9.png", true) &&
                            !baseRaw.startsWith("abc_") &&
                            !baseRaw.startsWith("notification_") &&
                            !baseRaw.startsWith("common_google_") &&
                            !baseRaw.startsWith("googleg_") -> {
                            val cleanBase = sanitizeResName(baseRaw)
                            if (cleanBase in seenResourceNames || outFiles.size >= 45) continue
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (bytes.isEmpty() || bytes.size > 1_500_000 || usedBytes + bytes.size > maxTotalBytes) continue
                            seenResourceNames.add(cleanBase)
                            val targetPath = "app/src/main/res/drawable/$cleanBase.$ext"
                            outFiles[targetPath] = bytes
                            usedBytes += bytes.size
                            notes.add("`R.drawable.$cleanBase` (from `$n`)")
                        }
                        n.startsWith("res/font/") && ext in listOf("ttf", "otf") -> {
                            val cleanBase = sanitizeResName(baseRaw)
                            val targetPath = "app/src/main/res/font/$cleanBase.$ext"
                            if (targetPath in outFiles) continue
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (bytes.isEmpty() || bytes.size > 3_000_000 || usedBytes + bytes.size > maxTotalBytes) continue
                            outFiles[targetPath] = bytes
                            usedBytes += bytes.size
                            notes.add("`R.font.$cleanBase` (from `$n`)")
                        }
                        n.startsWith("res/raw/") && ext in listOf("mp3", "ogg", "wav", "m4a", "json", "txt", "mp4") -> {
                            val cleanBase = sanitizeResName(baseRaw)
                            val targetPath = "app/src/main/res/raw/$cleanBase.$ext"
                            if (targetPath in outFiles) continue
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (bytes.isEmpty() || bytes.size > 4_000_000 || usedBytes + bytes.size > maxTotalBytes) continue
                            outFiles[targetPath] = bytes
                            usedBytes += bytes.size
                            notes.add("`R.raw.$cleanBase` (from `$n`)")
                        }
                        n.startsWith("assets/") && !n.startsWith("assets/dexopt/") && !n.endsWith(".so") -> {
                            val relAsset = n.removePrefix("assets/")
                            val safeAsset = relAsset.split('/').joinToString("/") { part ->
                                part.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            }
                            val targetPath = "app/src/main/assets/$safeAsset"
                            if (targetPath in outFiles || outFiles.size >= 60) continue
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (bytes.isEmpty() || bytes.size > 3_000_000 || usedBytes + bytes.size > maxTotalBytes) continue
                            outFiles[targetPath] = bytes
                            usedBytes += bytes.size
                            notes.add("`assets/$safeAsset`")
                        }
                    }
                }
            }
        }

        val summary = if (notes.isEmpty()) {
            ""
        } else {
            "\n\n### Extracted Original APK Resources Bundled Into This Project (${outFiles.size} files):\n" +
                "These resources from the original APK are ALREADY copied into the project and ready to use in your code:\n" +
                notes.take(40).joinToString("\n") { "- $it" }
        }
        return ExtractedResourcesBundle(outFiles, summary)
    }

    private fun sanitizeResName(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        return when {
            cleaned.isEmpty() -> "res_item"
            !cleaned.first().isLetter() -> "ic_$cleaned"
            cleaned == "ic_launcher" || cleaned == "ic_launcher_round" -> "orig_$cleaned"
            else -> cleaned.take(45)
        }
    }

    /**
     * Port of `morluto/rea`'s `inspect_apk_signature` (`docs/apk-signature.md`):
     * Computes SHA-256 digest, checks APK Signing Block (v2/v3/v3.1) in the ZIP tail, and parses X.509 certificates.
     */
    fun inspectApkSignature(apkFile: File): ApkSignatureReport {
        val sha256 = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("unavailable")

        var v1 = false
        var v2 = false
        var v3 = false
        var v31 = false
        var subject = ""
        var issuer = ""
        var certHash = ""

        // 1. Check v1 META-INF/*.RSA|*.DSA|*.EC
        runCatching {
            ZipFile(apkFile).use { zip ->
                val certEntry = zip.entries().toList().firstOrNull { e ->
                    val up = e.name.uppercase()
                    up.startsWith("META-INF/") && (up.endsWith(".RSA") || up.endsWith(".DSA") || up.endsWith(".EC"))
                }
                if (certEntry != null) {
                    v1 = true
                    val certBytes = zip.getInputStream(certEntry).use { it.readBytes() }
                    val cf = CertificateFactory.getInstance("X.509")
                    val certs = cf.generateCertificates(ByteArrayInputStream(certBytes))
                    val x509 = certs.firstOrNull() as? X509Certificate
                    if (x509 != null) {
                        subject = x509.subjectX500Principal?.name.orEmpty()
                        issuer = x509.issuerX500Principal?.name.orEmpty()
                        certHash = MessageDigest.getInstance("SHA-256")
                            .digest(x509.encoded)
                            .joinToString("") { "%02x".format(it) }
                    }
                }
            }
        }

        // 2. Check ZIP tail for APK Signing Block ("APK Sig Block 42") and scheme IDs
        runCatching {
            val len = apkFile.length()
            val tailSize = minOf(len, 131_072L).toInt()
            val tail = ByteArray(tailSize)
            java.io.RandomAccessFile(apkFile, "r").use { raf ->
                raf.seek(len - tailSize)
                raf.readFully(tail)
            }
            val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
            val magicIdx = indexOfSubArray(tail, magic)
            if (magicIdx >= 24) {
                val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                // Scan backward/forward for scheme IDs in the signing block
                for (pos in 0..(magicIdx - 4)) {
                    val id = buf.getInt(pos)
                    when (id) {
                        0x7109871a -> v2 = true
                        -0xfac9740 -> v3 = true // 0xf05368c0 as signed 32-bit int
                        0x1b93ad61 -> v31 = true
                    }
                }
            }
        }

        return ApkSignatureReport(
            sha256Digest = sha256,
            v1Present = v1,
            v2Present = v2,
            v3Present = v3,
            v31Present = v31,
            signerSubject = subject,
            signerIssuer = issuer,
            certSha256 = certHash,
        )
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private data class DecodedAxml(
        val xmlText: String,
        val stringPool: List<String>,
        val packageName: String,
        val appLabel: String,
        val versionName: String,
        val versionCode: String,
        val minSdk: String,
        val targetSdk: String,
        val permissions: List<String>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>,
    )

    /**
     * Full Binary XML (`AXML`) parser that decodes both the string pool AND the element tree
     * (`START_TAG` `0x0102`, `END_TAG` `0x0103`) into readable XML text and extracts manifest metadata.
     */
    private fun decodeBinaryXml(bytes: ByteArray): DecodedAxml {
        if (bytes.size < 32) {
            return DecodedAxml("", emptyList(), "", "", "", "", "", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fileType = buf.getShort(0).toInt() and 0xFFFF
        if (fileType != 0x0003) {
            return DecodedAxml("", emptyList(), "", "", "", "", "", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val strings = mutableListOf<String>()
        val sb = StringBuilder()
        var pkg = ""
        var label = ""
        var verName = ""
        var verCode = ""
        var minSdk = ""
        var targetSdk = ""
        val perms = mutableListOf<String>()
        val acts = mutableListOf<String>()
        val svcs = mutableListOf<String>()
        val rcvs = mutableListOf<String>()
        val prvs = mutableListOf<String>()

        var offset = 8
        var indent = 0

        fun getStr(idx: Int): String = if (idx in strings.indices) strings[idx] else ""

        while (offset + 8 <= bytes.size) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 8 || offset + chunkSize > bytes.size) break

            when (chunkType) {
                0x0001 -> {
                    // RES_STRING_POOL_TYPE
                    if (offset + 28 <= bytes.size) {
                        val stringCount = buf.getInt(offset + 8)
                        val flags = buf.getInt(offset + 16)
                        val stringsStart = buf.getInt(offset + 20)
                        val isUtf8 = (flags and (1 shl 8)) != 0
                        val offsetsBase = offset + headerSize
                        val dataBase = offset + stringsStart
                        for (i in 0 until minOf(stringCount, 4000)) {
                            val offPos = offsetsBase + i * 4
                            if (offPos + 4 > bytes.size) break
                            val strOff = buf.getInt(offPos)
                            val strPos = dataBase + strOff
                            if (strPos < 0 || strPos >= bytes.size) {
                                strings.add("")
                                continue
                            }
                            if (isUtf8) {
                                var p = strPos
                                if (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p += 2 else p += 1
                                if (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p += 2 else p += 1
                                var end = p
                                while (end < bytes.size && bytes[end] != 0.toByte()) end++
                                strings.add(if (end <= bytes.size && end >= p) String(bytes, p, end - p, Charsets.UTF_8) else "")
                            } else {
                                if (strPos + 2 <= bytes.size) {
                                    val charLen = buf.getShort(strPos).toInt() and 0xFFFF
                                    val p = strPos + 2
                                    val byteLen = charLen * 2
                                    strings.add(if (p + byteLen <= bytes.size) String(bytes, p, byteLen, Charsets.UTF_16LE) else "")
                                } else {
                                    strings.add("")
                                }
                            }
                        }
                    }
                }
                0x0102 -> {
                    // RES_XML_START_ELEMENT_TYPE
                    if (offset + 36 <= bytes.size) {
                        val nameIdx = buf.getInt(offset + 20)
                        val attrStart = buf.getShort(offset + 24).toInt() and 0xFFFF
                        val attrSize = buf.getShort(offset + 26).toInt() and 0xFFFF
                        val attrCount = buf.getShort(offset + 28).toInt() and 0xFFFF
                        val elemName = getStr(nameIdx).ifBlank { "element" }

                        val attrs = mutableListOf<Pair<String, String>>()
                        var attrPos = offset + 16 + attrStart
                        val step = if (attrSize >= 20) attrSize else 20
                        for (a in 0 until minOf(attrCount, 60)) {
                            if (attrPos + 20 > offset + chunkSize || attrPos + 20 > bytes.size) break
                            val aNameIdx = buf.getInt(attrPos + 4)
                            val aRawIdx = buf.getInt(attrPos + 8)
                            val aType = buf.get(attrPos + 15).toInt() and 0xFF
                            val aData = buf.getInt(attrPos + 16)
                            val attrName = getStr(aNameIdx).ifBlank { "attr_$a" }
                            val attrVal = when {
                                aRawIdx >= 0 && aRawIdx in strings.indices -> strings[aRawIdx]
                                aType == 0x03 && aData in strings.indices -> strings[aData]
                                aType == 0x12 -> if (aData != 0) "true" else "false"
                                aType == 0x10 -> aData.toString()
                                aType == 0x11 -> "0x${Integer.toHexString(aData)}"
                                aType == 0x01 -> "@0x${Integer.toHexString(aData)}"
                                else -> aData.toString()
                            }
                            attrs.add(attrName to attrVal)
                            attrPos += step
                        }

                        // Capture structured manifest fields
                        val attrMap = attrs.toMap()
                        when (elemName) {
                            "manifest" -> {
                                attrMap["package"]?.let { pkg = it }
                                attrMap["versionName"]?.let { verName = it }
                                attrMap["versionCode"]?.let { verCode = it }
                            }
                            "uses-sdk" -> {
                                attrMap["minSdkVersion"]?.let { minSdk = it }
                                attrMap["targetSdkVersion"]?.let { targetSdk = it }
                            }
                            "uses-permission", "uses-permission-sdk-23" -> {
                                attrMap["name"]?.let { perms.add(it) }
                            }
                            "application" -> {
                                attrMap["label"]?.let { label = it }
                            }
                            "activity", "activity-alias" -> {
                                attrMap["name"]?.let { acts.add(resolveComponentName(pkg, it)) }
                            }
                            "service" -> {
                                attrMap["name"]?.let { svcs.add(resolveComponentName(pkg, it)) }
                            }
                            "receiver" -> {
                                attrMap["name"]?.let { rcvs.add(resolveComponentName(pkg, it)) }
                            }
                            "provider" -> {
                                attrMap["name"]?.let { prvs.add(resolveComponentName(pkg, it)) }
                            }
                        }

                        if (sb.length < 16_000) {
                            val pad = "  ".repeat(indent.coerceIn(0, 12))
                            val attrStr = if (attrs.isEmpty()) "" else " " + attrs.joinToString(" ") { (k, v) -> "$k=\"$v\"" }
                            sb.appendLine("$pad<$elemName$attrStr>")
                        }
                        indent++
                    }
                }
                0x0103 -> {
                    // RES_XML_END_ELEMENT_TYPE
                    indent = (indent - 1).coerceAtLeast(0)
                    if (offset + 24 <= bytes.size && sb.length < 16_000) {
                        val nameIdx = buf.getInt(offset + 20)
                        val elemName = getStr(nameIdx).ifBlank { "element" }
                        val pad = "  ".repeat(indent.coerceIn(0, 12))
                        sb.appendLine("$pad</$elemName>")
                    }
                }
            }
            offset += chunkSize
        }

        return DecodedAxml(
            xmlText = sb.toString().trim(),
            stringPool = strings,
            packageName = pkg,
            appLabel = label,
            versionName = verName,
            versionCode = verCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = perms,
            activities = acts,
            services = svcs,
            receivers = rcvs,
            providers = prvs,
        )
    }

    private fun resolveComponentName(pkg: String, raw: String): String = when {
        raw.startsWith(".") && pkg.isNotBlank() -> "$pkg$raw"
        !raw.contains(".") && pkg.isNotBlank() -> "$pkg.$raw"
        else -> raw
    }

    private data class DexFullResult(
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val classes: List<String>,
        val methodSignatures: List<String>,
        val fieldSignatures: List<String>,
        val urls: List<String>,
        val uiStrings: List<String>,
    )

    /**
     * Parses Dalvik Executable (`classes*.dex`) tables:
     * - `string_ids` (offset 56)
     * - `type_ids` (offset 64)
     * - `proto_ids` (offset 72)
     * - `field_ids` (offset 80)
     * - `method_ids` (offset 88)
     * - `class_defs` (offset 96)
     */
    private fun parseDexFull(bytes: ByteArray, appPkgPrefix: String): DexFullResult {
        if (bytes.size < 112) return DexFullResult(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val magic = String(bytes, 0, minOf(bytes.size, 8), Charsets.US_ASCII)
        if (!magic.startsWith("dex\n")) return DexFullResult(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(56)
        val stringIdsOff = buf.getInt(60)
        val typeIdsSize = buf.getInt(64)
        val typeIdsOff = buf.getInt(68)
        val protoIdsSize = buf.getInt(72)
        val protoIdsOff = buf.getInt(76)
        val fieldIdsSize = buf.getInt(80)
        val fieldIdsOff = buf.getInt(84)
        val methodIdsSize = buf.getInt(88)
        val methodIdsOff = buf.getInt(92)
        val classDefsSize = buf.getInt(96)
        val classDefsOff = buf.getInt(100)

        fun readStringAtIdx(idx: Int): String {
            if (idx < 0 || idx >= stringIdsSize) return ""
            val offPos = stringIdsOff + idx * 4
            if (offPos + 4 > bytes.size) return ""
            val off = buf.getInt(offPos)
            if (off <= 0 || off >= bytes.size) return ""
            var p = off
            while (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p++
            p++
            var end = p
            while (end < bytes.size && bytes[end] != 0.toByte() && end - p < 300) end++
            return if (end <= bytes.size && end >= p) String(bytes, p, end - p, Charsets.UTF_8) else ""
        }

        fun formatDescriptor(desc: String): String = when {
            desc.isEmpty() -> "void"
            desc == "V" -> "Unit"
            desc == "Z" -> "Boolean"
            desc == "B" -> "Byte"
            desc == "S" -> "Short"
            desc == "C" -> "Char"
            desc == "I" -> "Int"
            desc == "J" -> "Long"
            desc == "F" -> "Float"
            desc == "D" -> "Double"
            desc.startsWith("[") -> "Array<" + formatDescriptor(desc.drop(1)) + ">"
            desc.startsWith("L") && desc.endsWith(";") ->
                desc.substring(1, desc.length - 1).replace('/', '.').substringAfterLast('.')
            else -> desc
        }

        fun readTypeAtIdx(typeIdx: Int): String {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return ""
            val pos = typeIdsOff + typeIdx * 4
            if (pos + 4 > bytes.size) return ""
            val strIdx = buf.getInt(pos)
            return readStringAtIdx(strIdx)
        }

        // 1. Sample strings & URLs
        val urls = linkedSetOf<String>()
        val uiStrings = linkedSetOf<String>()
        val stringStep = maxOf(1, stringIdsSize / 4000)
        var sIdx = 0
        while (sIdx < stringIdsSize) {
            val s = readStringAtIdx(sIdx)
            if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ws://") || s.startsWith("wss://")) {
                if (!s.contains("schemas.android.com") && !s.contains("www.w3.org") && !s.contains("apache.org")) {
                    urls.add(s.take(140))
                }
            } else if (s.length in 4..90 && s.any { it == ' ' } && s.first().isUpperCase() && !s.startsWith("L") && !s.contains("Exception")) {
                if (uiStrings.size < 80) uiStrings.add(s)
            }
            sIdx += stringStep
        }

        // 2. Extract class definitions
        val classes = mutableListOf<String>()
        for (i in 0 until minOf(classDefsSize, 1200)) {
            val defPos = classDefsOff + i * 32
            if (defPos + 4 > bytes.size) break
            val classTypeIdx = buf.getInt(defPos)
            val rawDesc = readTypeAtIdx(classTypeIdx)
            if (rawDesc.startsWith("L") && rawDesc.endsWith(";")) {
                classes.add(rawDesc.substring(1, rawDesc.length - 1).replace('/', '.'))
            }
        }

        val isInterestingClass: (String) -> Boolean = { fqcn ->
            (appPkgPrefix.isNotBlank() && fqcn.startsWith(appPkgPrefix)) ||
                (!fqcn.startsWith("android.") && !fqcn.startsWith("androidx.") &&
                    !fqcn.startsWith("kotlin.") && !fqcn.startsWith("kotlinx.") &&
                    !fqcn.startsWith("java.") && !fqcn.startsWith("javax.") &&
                    !fqcn.startsWith("com.google.") && !fqcn.startsWith("okhttp3.") &&
                    !fqcn.startsWith("okio.") && !fqcn.startsWith("org.jetbrains.") &&
                    !fqcn.contains("$$"))
        }

        // 3. Extract method signatures from method_ids
        val methodSigs = mutableListOf<String>()
        val methodStep = maxOf(1, methodIdsSize / 3000)
        var mIdx = 0
        while (mIdx < methodIdsSize && methodSigs.size < 120) {
            val pos = methodIdsOff + mIdx * 8
            if (pos + 8 > bytes.size) break
            val classIdx = buf.getShort(pos).toInt() and 0xFFFF
            val protoIdx = buf.getShort(pos + 2).toInt() and 0xFFFF
            val nameIdx = buf.getInt(pos + 4)

            val rawClass = readTypeAtIdx(classIdx)
            if (rawClass.startsWith("L") && rawClass.endsWith(";")) {
                val fqcn = rawClass.substring(1, rawClass.length - 1).replace('/', '.')
                if (isInterestingClass(fqcn)) {
                    val mName = readStringAtIdx(nameIdx)
                    if (mName.isNotBlank() && mName != "<init>" && mName != "<clinit>" && !mName.contains("$")) {
                        var retType = "Any"
                        if (protoIdx in 0 until protoIdsSize) {
                            val pPos = protoIdsOff + protoIdx * 12
                            if (pPos + 8 <= bytes.size) {
                                val retTypeIdx = buf.getInt(pPos + 4)
                                retType = formatDescriptor(readTypeAtIdx(retTypeIdx))
                            }
                        }
                        val shortClass = fqcn.substringAfterLast('.')
                        methodSigs.add("$shortClass.$mName(): $retType")
                    }
                }
            }
            mIdx += methodStep
        }

        // 4. Extract field signatures from field_ids
        val fieldSigs = mutableListOf<String>()
        val fieldStep = maxOf(1, fieldIdsSize / 2000)
        var fIdx = 0
        while (fIdx < fieldIdsSize && fieldSigs.size < 80) {
            val pos = fieldIdsOff + fIdx * 8
            if (pos + 8 > bytes.size) break
            val classIdx = buf.getShort(pos).toInt() and 0xFFFF
            val typeIdx = buf.getShort(pos + 2).toInt() and 0xFFFF
            val nameIdx = buf.getInt(pos + 4)

            val rawClass = readTypeAtIdx(classIdx)
            if (rawClass.startsWith("L") && rawClass.endsWith(";")) {
                val fqcn = rawClass.substring(1, rawClass.length - 1).replace('/', '.')
                if (isInterestingClass(fqcn)) {
                    val fName = readStringAtIdx(nameIdx)
                    if (fName.isNotBlank() && !fName.contains("$") && fName != "Companion" && fName != "INSTANCE") {
                        val fType = formatDescriptor(readTypeAtIdx(typeIdx))
                        val shortClass = fqcn.substringAfterLast('.')
                        fieldSigs.add("$shortClass.$fName: $fType")
                    }
                }
            }
            fIdx += fieldStep
        }

        return DexFullResult(
            classCount = classDefsSize,
            methodCount = methodIdsSize,
            fieldCount = fieldIdsSize,
            classes = classes,
            methodSignatures = methodSigs,
            fieldSignatures = fieldSigs,
            urls = urls.toList(),
            uiStrings = uiStrings.toList(),
        )
    }
}
