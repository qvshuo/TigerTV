plugins { kotlin("multiplatform") }
val generateVersion = tasks.register("generateVersion") {
    val output = layout.buildDirectory.file("generated/version/Version.kt")
    val release = project.version.toString()
    inputs.property("version", release)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package art.anjing.tigertv.cli\ninternal const val VERSION = \"$release\"\n")
        }
    }
}
kotlin {
    macosArm64 {
        binaries.executable {
            baseName = "tigertv"
            entryPoint = "art.anjing.tigertv.cli.main"
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir(generateVersion.map { layout.buildDirectory.dir("generated/version").get() }) }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
