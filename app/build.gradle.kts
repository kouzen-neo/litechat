plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.localgpt.app"
    // compileSdk 37: required by the Compose 1.12.0-beta01 / material3
    // 1.5.0-alpha25 / material-kolor 5.0.0 artifacts' AAR metadata (AGP 9
    // enforces it). Google has not published the android-37 platform
    // publicly, so CI provides android-37 as android-36's APIs (see the
    // android-37 shim in .github/workflows/build-apk.yml). The app itself
    // uses no API 37+ calls; targetSdk stays 36.
    compileSdk = 37

    // -PabiFilter=arm64-v8a -> single-ABI APK; without flag -> all ABIs + universal
    val abiFilter =
        (project.findProperty("abiFilter") as String?)?.takeIf {
            it.isNotBlank() && it != "all"
        }

    defaultConfig {
        applicationId = "com.localgpt.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            if (abiFilter == null) {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (abiFilter != null) {
                include(abiFilter)
                isUniversalApk = false
            } else {
                include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                isUniversalApk = true
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            // Passwords are intentionally NOT hardcoded: read from environment first,
            // then gradle.properties / local.properties (never commit those files).
            val ksStorePw = System.getenv("LITECHAT_KEYSTORE_STORE_PASSWORD")
                ?: (project.findProperty("litechat.keystore.storePassword") as String?)
            val ksKeyPw = System.getenv("LITECHAT_KEYSTORE_KEY_PASSWORD")
                ?: (project.findProperty("litechat.keystore.keyPassword") as String?)
            if (ksStorePw.isNullOrEmpty() || ksKeyPw.isNullOrEmpty()) {
                logger.warn(
                    "Keystore passwords not set (LITECHAT_KEYSTORE_STORE_PASSWORD / " +
                        "LITECHAT_KEYSTORE_KEY_PASSWORD env or litechat.keystore.* gradle properties). " +
                        "Release signing will fail until they are provided."
                )
            }
            storePassword = ksStorePw
            keyAlias = System.getenv("LITECHAT_KEYSTORE_KEY_ALIAS")
                ?: (project.findProperty("litechat.keystore.keyAlias") as String?)
                ?: "localgpt"
            keyPassword = ksKeyPw
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP 9.0 built-in Kotlin
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/NOTICE.md",
                    "META-INF/LICENSE.md",
                    "META-INF/DEPENDENCIES",
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                )
        }
    }
}

dependencies {
    // Compose BOM (androidx) — material3 pinned like kzkt (M3 Expressive line)
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3:1.5.0-alpha25")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Theme: MaterialKolor (seed-color dynamic theming)
    implementation("com.materialkolor:material-kolor:5.0.0")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.8.7")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // WorkManager (pengingat terjadwal)
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // EncryptedSharedPreferences (sensitive tokens: API keys, HF token, server auth)
    implementation("androidx.security:security-crypto:1.0.0")

    // JSON + HTTP client (model downloads from HuggingFace)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Embedded OpenAI-compatible HTTP server
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")

    // Google AI Edge LiteRT-LM (on-device LLM runtime)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
}
