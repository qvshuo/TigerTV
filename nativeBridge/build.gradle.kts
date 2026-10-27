plugins { kotlin("multiplatform") }
kotlin {
    macosArm64 { binaries.framework { baseName = "TigerTVCore"; isStatic = true } }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
