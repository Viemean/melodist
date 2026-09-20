plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.melodist.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.melodist.tv"
        minSdk = 28
        targetSdk = 37
        versionCode = 139
        versionName = "1.3.9"

        buildConfigField("String", "GRADLE_VERSION", "\"${gradle.gradleVersion}\"")
        buildConfigField("String", "AGP_VERSION", "\"${libs.versions.agp.get()}\"")
        buildConfigField("String", "MEDIA3_VERSION", "\"${libs.versions.media3.get()}\"")
        buildConfigField("String", "COMPOSE_TV_VERSION", "\"${libs.versions.tv.material.get()}\"")
        buildConfigField("String", "COIL_VERSION", "\"${libs.versions.coil.get()}\"")
        buildConfigField("String", "OKHTTP_VERSION", "\"${libs.versions.okhttp.get()}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        resourceConfigurations += listOf("zh", "zh-rCN", "en")
    }

    val releaseKeystore = file("release.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("KEY_ALIAS")
    val keyPassword = System.getenv("KEY_PASSWORD")

    signingConfigs {
        if (releaseKeystore.exists() && !keystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = runCatching { signingConfigs.getByName("release") }.getOrNull()
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    packaging {
        resources {
            excludes +=
                listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module",
                    "/META-INF/INDEX.LIST",
                )
        }
    }

    val isReleaseLintEnabled = project.hasProperty("enableLint") || project.hasProperty("ci") || System.getenv("CI") == "true"
    lint {
        checkReleaseBuilds = isReleaseLintEnabled
        abortOnError = isReleaseLintEnabled
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-api"))
    implementation(project(":core-data"))
    implementation(project(":core-playback"))
    implementation(project(":core-connect"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose for TV
    implementation(libs.tv.foundation)
    implementation(libs.tv.material)
    implementation(libs.compose.material.icons.extended)

    // Media3 ExoPlayer & MediaSession
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Coil 3 图片异步加载
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Kotlinx Serialization JSON
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // LeakCanary 运行时内存泄漏自动检测
    debugImplementation(libs.leakcanary)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
