plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tme.vivowidget"
    compileSdk = 37
    compileSdkMinor = 2

    val releaseKeystore = file("../app-tv/release.jks")
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

    defaultConfig {
        applicationId = "com.tme.vivowidget"
        minSdk = 26
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
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
}
