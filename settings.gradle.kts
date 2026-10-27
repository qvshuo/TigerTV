pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TigerTV"
include(":core", ":nativeBridge", ":cli")
// Native-only builds do not require an Android SDK.
if (providers.gradleProperty("android").orNull == "true") {
    include(":androidApp")
    project(":androidApp").projectDir = file("AndroidTV/app")
}
