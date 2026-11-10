package com.aiapp.builder

/**
 * A known-good Gradle project that every generated app starts from. The model only writes code and
 * resources under app/src/main, so small models (including offline Gemma) don't have to get the
 * build setup right.
 */
object ProjectTemplate {

    /** Files the app writes; the model is not allowed to replace these. */
    fun files(appName: String, packageName: String, extraDeps: List<String>): Map<String, String> = mapOf(
        "settings.gradle.kts" to """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                    maven("https://jitpack.io")
                }
            }
            rootProject.name = "${appName.replace("\"", "")}"
            include(":app")
        """.trimIndent(),
        "build.gradle.kts" to """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.2.20" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
                id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
            }
        """.trimIndent(),
        "gradle.properties" to """
            org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """.trimIndent(),
        "app/build.gradle.kts" to """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
                id("org.jetbrains.kotlin.plugin.serialization")
            }

            android {
                namespace = "$packageName"
                compileSdk = 35
                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }
                signingConfigs {
                    // Created once by the workflow and kept in the repo, so every build installs over the last one.
                    getByName("debug") {
                        if (file("signing.keystore").exists()) {
                            storeFile = file("signing.keystore")
                            storePassword = "android"
                            keyAlias = "app"
                            keyPassword = "android"
                        }
                    }
                }
                buildTypes { release { isMinifyEnabled = false } }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildFeatures { compose = true }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }

            dependencies {
                val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
                implementation(composeBom)
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.ui:ui-tooling-preview")
                implementation("androidx.compose.foundation:foundation")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.compose.material:material-icons-extended")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
                implementation("androidx.navigation:navigation-compose:2.8.5")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            ${extraDeps.joinToString("\n") { "    implementation(\"$it\")" }}
            }
        """.trimIndent(),
        ".gitignore" to "build/\n.gradle/\nlocal.properties\n*.iml\n",
        ".github/workflows/build.yml" to """
            name: Build APK
            on:
              push:
              workflow_dispatch:
            permissions:
              contents: write
            jobs:
              build:
                runs-on: ubuntu-latest
                timeout-minutes: 30
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: "17"
                  - uses: gradle/actions/setup-gradle@v4
                    with:
                      gradle-version: "8.14.3"
                  - name: Signing key (created once, then reused)
                    run: |
                      if [ ! -f app/signing.keystore ]; then
                        keytool -genkeypair -keystore app/signing.keystore -storepass android -keypass android -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AI App"
                        git config user.name "AI App"
                        git config user.email "ai-app@users.noreply.github.com"
                        git add app/signing.keystore
                        git commit -m "Add signing key"
                        git push origin HEAD:${'$'}{{ github.ref_name }} || echo "Couldn't save the key; this build still works."
                      fi
                  - name: Build debug APK
                    run: |
                      set -o pipefail
                      gradle assembleDebug --stacktrace 2>&1 | tee build.log
                  - name: Report errors
                    if: failure()
                    run: |
                      grep -E "^e: |error:|What went wrong|Could not resolve|FAILURE" -A2 build.log | head -60 | while IFS= read -r l; do
                        echo "::error::${'$'}{l//%/%25}"
                      done
                  - name: Rename APK
                    run: cp app/build/outputs/apk/debug/app-debug.apk app-${'$'}{{ github.run_number }}.apk
                  - uses: softprops/action-gh-release@v2
                    with:
                      tag_name: build-${'$'}{{ github.run_number }}
                      name: Build ${'$'}{{ github.run_number }}
                      files: app-*.apk
                      make_latest: true
        """.trimIndent(),
    )

    fun defaultManifest(appName: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <application
                android:label="${appName.replace("\"", "")}"
                android:supportsRtl="true"
                android:theme="@android:style/Theme.Material.Light.NoActionBar">
                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    /** Workflow added to uploaded projects; uses the project's own Gradle wrapper when it has one. */
    const val IMPORT_WORKFLOW_FILE = "ai-app-build.yml"

    val importWorkflow = """
        name: AI App build
        on:
          push:
          workflow_dispatch:
        permissions:
          contents: write
        jobs:
          build:
            runs-on: ubuntu-latest
            timeout-minutes: 45
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: "17"
              - uses: gradle/actions/setup-gradle@v4
                with:
                  gradle-version: "8.14.3"
              - name: Build debug APK
                run: |
                  set -o pipefail
                  if [ -f gradlew ]; then
                    chmod +x gradlew
                    ./gradlew assembleDebug --stacktrace 2>&1 | tee build.log
                  else
                    gradle assembleDebug --stacktrace 2>&1 | tee build.log
                  fi
              - name: Report errors
                if: failure()
                run: |
                  grep -E "^e: |error:|What went wrong|Could not resolve|FAILURE" -A2 build.log | head -60 | while IFS= read -r l; do
                    echo "::error::${'$'}{l//%/%25}"
                  done
              - name: Collect APK
                run: |
                  APK=${'$'}(find . -path "*/build/outputs/apk/*" -name "*.apk" | head -n 1)
                  if [ -z "${'$'}APK" ]; then echo "No APK was produced"; exit 1; fi
                  cp "${'$'}APK" app-${'$'}{{ github.run_number }}.apk
              - uses: softprops/action-gh-release@v2
                with:
                  tag_name: build-${'$'}{{ github.run_number }}
                  name: Build ${'$'}{{ github.run_number }}
                  files: app-*.apk
                  make_latest: true
    """.trimIndent()

    /** System prompt used when the user asks a question or discusses the project in the Build tab (without building). */
    val qaSystemPrompt = """
        You are an expert Android Studio architect and conversational co-pilot inside the Build tab of AI App.
        The user is asking a question, brainstorming features, or discussing their Android project — they have NOT asked you to trigger a build yet.
        
        Rules:
        - Answer the user's question directly, clearly, and helpfully using clean Markdown (bullet points, short explanations, or code snippets if requested).
        - Do NOT output `=== FILE: ... ===` blocks when answering a question, because `=== FILE:` triggers file overwrites and a full APK build.
        - At the end of your reply, let the user know they can tap "🔨 Build / Apply Changes" (or say "build this") whenever they want you to implement and compile any of the discussed features into an APK.
    """.trimIndent()

    val importedSystemPrompt = """
        You are an expert Android developer working on an existing Android Studio project (Gradle, Kotlin or Java).
        If the user is asking a question (e.g., "what more can we add?", "how does this work?", "reply") rather than directing you to modify code and build, answer their question directly in plain Markdown WITHOUT emitting any `=== FILE: ... ===` blocks.
        When the user directs you to make code changes or fix build errors, output ONLY changed or new files in this format:

        === FILE: path/from/project/root ===
        (full file content)
        === END FILE ===

        Rules:
        - Paths are relative to the project root (where settings.gradle is), e.g. app/src/main/java/... or app/build.gradle.kts.
        - Write every file you send in full. No placeholders, no "..." and no TODOs.
        - To add a library, edit the module's build.gradle(.kts) dependencies (and gradle/libs.versions.toml if the project uses it).
        - To delete a file, send it with the single line: DELETE
        - Don't touch .github/ or the Gradle wrapper jar.
    """.trimIndent()

    /** Lets the model look things up before writing code. */
    val webInstructions = "\n\n" + """

        Before writing files you may look things up online. Reply with ONLY <search>query</search> to
        search the web, or ONLY <fetch>https://url</fetch> to read a page (docs, READMEs, release
        notes). Use this when you're unsure how a library or API works. Then write the files.
    """.trimIndent()

    /** Rules given to the model. Kept short so small offline models can follow them. */
    fun systemPrompt(packageName: String): String = """
        You are an expert Android developer. You write complete, working Android apps in Kotlin with Jetpack Compose and Material 3.

        The Gradle setup already exists. It includes: Compose (BOM 2024.12.01), material3, material-icons-extended, activity-compose, lifecycle-viewmodel-compose, navigation-compose, kotlinx-coroutines, kotlinx-serialization-json, okhttp. Kotlin 2.2, compileSdk 35, minSdk 26.

        If you need another library from Maven Central, Google Maven or JitPack, add a line before the files:
        === DEPENDENCY: group:artifact:version ===
        (You may write "latest" for the version and the build tool will look up the newest release.)

        If you need to download an image, font, sound or other asset into the app, add a line:
        === DOWNLOAD: https://... -> app/src/main/res/drawable/name.png ===
        (Allowed folders: app/src/main/res/drawable, res/raw, res/font, and app/src/main/assets. Resource file names must be lowercase_with_underscores.)

        Output every file you write in this exact format:

        === FILE: app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt ===
        package $packageName
        ...full file content...
        === END FILE ===

        Rules:
        - Package name is $packageName. Put all Kotlin files under app/src/main/java/${packageName.replace('.', '/')}/.
        - MainActivity must be in package $packageName and extend ComponentActivity.
        - Write complete files. Never use placeholders, "..." or TODOs.
        - Only write files under app/src/main/ (code, res/, assets/, AndroidManifest.xml). Do not write build.gradle or settings.gradle.
        - If you write AndroidManifest.xml, include INTERNET permission and the LAUNCHER activity for .MainActivity.
    """.trimIndent()
}
