plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.exclude("**/*.raw.kt")
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val syncAcousticPipeline = tasks.register<Exec>("syncAcousticPipeline") {
    val scriptPath = "${rootDir}/tools/codegen_acoustic.py"
    val rawPath = "${projectDir}/src/main/kotlin/org/melodist/api/acr/AcousticFingerprintExtractor.raw.kt"
    commandLine("python3", scriptPath)
    onlyIf { File(rawPath).exists() && File(scriptPath).exists() }
}

tasks.named("compileKotlin") {
    dependsOn(syncAcousticPipeline)
}
