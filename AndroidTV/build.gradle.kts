plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp) apply false
}

android {
    namespace = "art.anjing.tigertv"
    compileSdk = 37

    defaultConfig {
        applicationId = "art.anjing.tigertv"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("androidVersionCode").orElse("12").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("3.3.0").get()

        // Include both 32-bit (TV) and 64-bit (modern devices/emulators) ABIs.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            keyAlias = "androiddebugkey"
        }
        create("release") {
            initWith(getByName("debug"))
            // Local builds share Interlude's key; CI restores the same key from secrets.
            val ciStoreFile = System.getenv("CI_RELEASE_STORE_FILE")?.takeIf { it.isNotEmpty() }
            if (ciStoreFile != null) {
                storeFile = file(ciStoreFile)
                storePassword = System.getenv("CI_RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("CI_RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("CI_RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    lint {
        // This leanback-only app targets ARM TVs, including 32-bit Sony devices, not ChromeOS.
        disable += "ChromeOsAbiSupport"
    }

    packaging {
        // AndroidX ships this prebuilt library stripped; no local NDK processing is needed.
        jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(dependencies.project(mapOf("path" to ":core")))
    testImplementation("io.ktor:ktor-client-mock:3.6.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Compose / TV
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.tv:tv-material:1.1.0")

    // Media3 ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Image loading (search result covers)
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
}

// Android unit tests need the same JDK as core, independently of the IDE/Gradle daemon JDK.
val testJavaToolchains = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>()
tasks.withType<Test>().configureEach {
    javaLauncher.set(testJavaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // MockK instruments bootstrap classes, for which class-data sharing is unavailable.
    jvmArgs("-Xshare:off")
}
