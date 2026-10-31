plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp) apply false
}
allprojects { version = providers.gradleProperty("releaseVersion").orElse("4.0.0").get() }
