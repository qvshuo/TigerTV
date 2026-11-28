plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

private object KotlinSource {
    fun stringLiteral(text: String) = buildString {
        append('"')
        for (char in text) when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> if (char.isISOControl()) append("\\u${char.code.toString(16).padStart(4, '0')}") else append(char)
        }
        append('"')
    }
}

val generateContractFixtures = tasks.register("generateContractFixtures") {
    val source = project.file("../docs/fixtures")
    val output = layout.buildDirectory.file("generated/fixtures/ContractFixtures.kt")
    inputs.dir(source)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package art.anjing.tigertv.core\ninternal val contractFixtures = mapOf(\n" + source.listFiles()!!.sortedBy { it.name }.joinToString(",\n") { KotlinSource.stringLiteral(it.name) + " to " + KotlinSource.stringLiteral(it.readText()) } + "\n)\n")
        }
    }
}
val generateSharedConfig = tasks.register("generateSharedConfig") {
    val files = mapOf(
        "hlsHeuristicsJson" to "hls-heuristics.json",
        "danmakuSelectionJson" to "danmaku-selection.json",
        "networkTimeoutsJson" to "network-timeouts.json",
        "doubanCategoriesJson" to "douban-categories.json",
        "productDefaultsJson" to "product-defaults.json",
    ).mapValues { project.file("../shared/${it.value}") }
    val output = layout.buildDirectory.file("generated/shared/SharedConfig.kt")
    inputs.files(files.values)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package art.anjing.tigertv.core\n" + files.entries.joinToString("\n", postfix = "\n") { (name, file) ->
                "internal const val $name = ${KotlinSource.stringLiteral(file.readText().trim())}"
            })
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
        commonMain { kotlin.srcDir(generateSharedConfig.map { layout.buildDirectory.dir("generated/shared").get() }) }
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
