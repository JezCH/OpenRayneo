plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openrayneo.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.openrayneo.android"
        manifestPlaceholders["appLabel"] = "OpenRayneo"
        minSdk = 33
        targetSdk = 36
        // CI builds count up with the workflow run so every APK installs over the previous one; local builds stay at 37.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 1000 + it } ?: 37
        // CI passes the next minor version per build (see android-apk.yml); local builds keep the base name.
        versionName = System.getenv("OPENRAYNEO_VERSION_NAME") ?: "1.2.0-beta.4"
        buildConfigField("String", "GIT_COMMIT", "\"" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local") + "\"")
        ndk { abiFilters += providers.gradleProperty("openrayneo.abis").orElse("arm64-v8a").get().split(',') }
    }
    val distributionSigningPath = System.getenv("OPENRAYNEO_SIGNING_KEYSTORE")
    val fixedDistributionSigning = if (distributionSigningPath.isNullOrBlank()) null else {
        signingConfigs.create("fixedDistribution") {
            storeFile = file(distributionSigningPath)
            require(storeFile?.isFile == true) { "Fixed distribution keystore does not exist" }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    check(System.getenv("OPENRAYNEO_FIXED_SIGNING") != "true" || fixedDistributionSigning != null) {
        "Distribution requires the verified explicit keystore path"
    }
    buildTypes {
        getByName("release") {
            // Never let a distribution build fall back to a generated debug keystore.
            signingConfig = fixedDistributionSigning ?: signingConfigs.getByName("debug")
            isDebuggable = false
        }
        create("validation") {
            // Side-by-side device testing without uninstalling the user's separately signed trial build.
            initWith(getByName("release"))
            applicationIdSuffix = ".validation"
            versionNameSuffix = "-validation"
            manifestPlaceholders["appLabel"] = "OpenRayneo (검증용)"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").assets.srcDir("../../ThirdParty/SherpaOnnx")
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/mozilla/jni"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/mozilla/assets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundled-speech-assets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundled-translation-assets"))
}

val prepareVoiceModels by tasks.registering(Exec::class) {
    commandLine("sh", rootProject.file("../scripts/prepare-android-voice.sh"))
    outputs.files(file("libs/sherpa-onnx-2.0.1.aar"), file("src/main/assets/voice-models/campplus-zh-16k.onnx"),
        file("src/main/assets/voice-models/silero-vad.onnx"), file("src/main/assets/voice-models/gtcrn-simple.onnx"),
        file("src/main/assets/voice-models/segmentation.int8.onnx"))
}
tasks.named("preBuild") { dependsOn(prepareVoiceModels) }

// All four existing speech models are shipped in every installation APK, not fetched by the phone.
val prepareBundledSpeech by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/prepare-android-bundled-speech.py")
    val output = layout.buildDirectory.dir("generated/bundled-speech-assets")
    inputs.file(script)
    inputs.file(rootProject.file("../ThirdParty/SherpaOnnx/LICENSE"))
    outputs.dir(output)
    commandLine("python3", script.absolutePath, "prepare", output.get().asFile.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareBundledSpeech)
}

// Package the existing pinned Mozilla model catalog at build time.
val prepareBundledTranslation by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/prepare-android-bundled-translation.py")
    val catalog = file("src/main/assets/mozilla-media-models.json")
    val license = rootProject.file("../ThirdParty/MozillaTranslation/LICENSE")
    val output = layout.buildDirectory.dir("generated/bundled-translation-assets")
    inputs.files(script, catalog, license)
    outputs.dir(output)
    commandLine("python3", script.absolutePath, "prepare", output.get().asFile.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareBundledTranslation)
}

// Build exact upstream native source; translation weights are included in the APK.
val mozillaAbis = providers.gradleProperty("openrayneo.abis").orElse("arm64-v8a")
val prepareMozillaNative by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/prepare-android-mozilla.py")
    val output = layout.buildDirectory.dir("generated/mozilla")
    inputs.file(script)
    inputs.property("abis", mozillaAbis)
    outputs.dir(output)
    commandLine("python3", script.absolutePath, "--cache", rootProject.file("../.build/android-mozilla").absolutePath,
        "--output", output.get().asFile.absolutePath, "--abis", mozillaAbis.get())
}
tasks.named("preBuild") { dependsOn(prepareMozillaNative) }

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
