plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
    id("com.android.application") version "9.2.1" apply false
}
allprojects { version = providers.gradleProperty("releaseVersion").orElse("3.3.0").get() }
