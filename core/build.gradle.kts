plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
val generateContractFixtures = tasks.register("generateContractFixtures") {
    val source = project.file("../docs/fixtures")
    val output = layout.buildDirectory.file("generated/fixtures/ContractFixtures.kt")
    inputs.dir(source)
    outputs.file(output)
    doLast {
        fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("$", "\\$") + "\""
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package art.anjing.tigertv.core\ninternal val contractFixtures = mapOf(\n" + source.listFiles()!!.sortedBy { it.name }.joinToString(",\n") { literal(it.name) + " to " + literal(it.readText()) } + "\n)\n")
        }
    }
}
val generatePlaybackRules = tasks.register("generatePlaybackRules") {
    val heuristics = project.file("../shared/hls-heuristics.json")
    val danmakuSelection = project.file("../shared/danmaku-selection.json")
    val networkTimeouts = project.file("../shared/network-timeouts.json")
    val output = layout.buildDirectory.file("generated/playback/PlaybackRules.kt")
    inputs.files(heuristics, danmakuSelection, networkTimeouts)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package art.anjing.tigertv.core\n" +
                "internal const val hlsHeuristicsJson = \"\"\"" + heuristics.readText().trim() + "\"\"\"\n" +
                "internal const val danmakuSelectionJson = \"\"\"" + danmakuSelection.readText().trim() + "\"\"\"\n" +
                "internal const val networkTimeoutsJson = \"\"\"" + networkTimeouts.readText().trim() + "\"\"\"\n")
        }
    }
}
if (providers.gradleProperty("android").orNull == "true") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core"))
    apply(plugin = "com.android.kotlin.multiplatform.library")
}
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()
    jvmToolchain(21)
    if (providers.gradleProperty("android").orNull == "true") {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "art.anjing.tigertv.core"
            compileSdk = 37
            minSdk = 34
            withHostTest {}
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonTest { kotlin.srcDir(generateContractFixtures.map { layout.buildDirectory.dir("generated/fixtures").get() }) }
        commonMain { kotlin.srcDir(generatePlaybackRules.map { layout.buildDirectory.dir("generated/playback").get() }) }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("io.ktor:ktor-client-core:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.1")
        }
        val jvmAndAndroidMain = create("jvmAndAndroidMain") {
            dependsOn(commonMain.get())
            dependencies { implementation("io.ktor:ktor-client-okhttp:3.6.1") }
        }
        jvmMain { dependsOn(jvmAndAndroidMain) }
        linuxMain.dependencies { implementation("io.ktor:ktor-client-curl:3.6.1") }
        macosArm64Main.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.1") }
        if (providers.gradleProperty("android").orNull == "true") {
            getByName("androidMain").dependsOn(jvmAndAndroidMain)
        }
    }
}

// Android lint reads generated common-test fixtures as source inputs.
tasks.matching { it.name == "lintAnalyzeAndroidHostTest" || it.name == "generateAndroidHostTestLintModel" }.configureEach {
    dependsOn(generateContractFixtures)
}
