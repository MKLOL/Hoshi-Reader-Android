plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val rustProjectDir = file("src/main/rust/hoshiepub")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin").get().asFile
val rustDebugJniLibsDir = layout.buildDirectory.dir("jniLibs/debug").get().asFile
val rustReleaseJniLibsDir = layout.buildDirectory.dir("jniLibs/release").get().asFile
val cargo = System.getenv("HOME") + "/.cargo/bin/cargo"
val androidNdkHome = System.getenv("ANDROID_NDK_HOME") ?: "/opt/homebrew/share/android-ndk"
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()
// Short git commit of the build, surfaced in-app (Settings) so a build can be identified at a
// glance — e.g. to confirm a new APK actually installed over an old one.
val gitSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=10", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")
if (providers.gradleProperty("releaseVersionCode").isPresent && releaseVersionCode == null) {
    throw GradleException("releaseVersionCode must be an integer.")
}
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val isReleaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val isReleaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() } &&
    releaseKeystorePath?.let { file(it).isFile } == true

if (isReleaseSigningRequested && !isReleaseSigningConfigured) {
    throw GradleException(
        "Release signing requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD, and the keystore file must exist."
    )
}

val hostLibExtension = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "dylib"
    System.getProperty("os.name").lowercase().contains("win") -> "dll"
    else -> "so"
}

android {
    namespace = "moe.antimony.hoshi"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "moe.antimony.hoshi"
        minSdk = 28
        targetSdk = 36
        versionCode = 908
        versionName = "0.9.8"
        releaseVersionCode?.let { versionCode = it }
        releaseVersionName?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        externalNativeBuild {
            cmake {
                targets += "hoshidicts_jni"
                targets += "hoshi_llama_jni"
            }
        }
    }

    if (isReleaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Sui Manga Reader Debug"
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appLabel"] = "Sui Manga Reader"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No official release keystore was provided. Rather than emit an
                // uninstallable unsigned APK, produce an installable *fork* build: carry
                // the `.debug` applicationId suffix and sign with the universal Android
                // debug keystore. It is still a real release build (minified, resource-
                // shrunk, not debuggable), but shares the package name and signing key of
                // a debug build of this fork, so it installs as an in-place update over
                // one — keeping the user's library, reading progress and dictionaries.
                applicationIdSuffix = ".debug"
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "DirectSystemCurrentTimeMillisUsage"
        // The fork's EPUB strip collapses two distinct import code paths into the same
        // user-visible message (e.g. "Failed to import manga." for both single-file and
        // multi-file flows). The AnkiDroid plugin's DuplicateCrowdInStrings check is meant
        // for translator-confusion prevention; here the duplication is intentional.
        disable += "DuplicateCrowdInStrings"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].java.directories.add(uniffiOutDir.absolutePath)
    sourceSets["debug"].jniLibs.directories.add(rustDebugJniLibsDir.absolutePath)
    sourceSets["release"].jniLibs.directories.add(rustReleaseJniLibsDir.absolutePath)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.ankidroid.api)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    testImplementation(libs.junit)
    testRuntimeOnly("net.java.dev.jna:jna:${libs.versions.jna.get()}@jar")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("org.nanohttpd:nanohttpd:2.3.1")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRustHost by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    outputs.file(rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension"))

    commandLine(cargo, "build", "--lib")
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    dependsOn(buildRustHost)
    workingDir = rustProjectDir

    val hostLibPath = rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension")

    inputs.file(hostLibPath)
    inputs.file(rustProjectDir.resolve("uniffi.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.lock"))
    outputs.dir(uniffiOutDir)

    commandLine(
        cargo,
        "run",
        "--features",
        "bindgen",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        hostLibPath.absolutePath,
        "--config",
        rustProjectDir.resolve("uniffi.toml").absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        uniffiOutDir.absolutePath,
        "--no-format",
    )
}

val buildRustAndroidDebug by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    outputs.files(
        rustDebugJniLibsDir.resolve("arm64-v8a/libhoshiepub.so"),
        rustDebugJniLibsDir.resolve("x86_64/libhoshiepub.so"),
    )

    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        rustDebugJniLibsDir.absolutePath,
        "build",
        "--lib",
    )
}

val buildRustAndroidRelease by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    outputs.file(rustReleaseJniLibsDir.resolve("arm64-v8a/libhoshiepub.so"))

    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-o",
        rustReleaseJniLibsDir.absolutePath,
        "build",
        "--lib",
        "--release",
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiKotlin)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiKotlin)
    source(uniffiOutDir)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("jna.library.path", rustProjectDir.resolve("target/debug").absolutePath)
}

afterEvaluate {
    tasks.named("preDebugBuild") {
        dependsOn(buildRustAndroidDebug)
    }
    tasks.named("preReleaseBuild") {
        dependsOn(buildRustAndroidRelease)
    }
}
