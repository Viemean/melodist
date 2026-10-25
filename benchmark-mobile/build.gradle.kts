plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "org.melodist.mobile.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "channel"
    productFlavors {
        create("standard") {
            dimension = "channel"
            buildConfigField("String", "TARGET_PACKAGE", "\"org.melodist.mobile\"")
        }
        create("originOs") {
            dimension = "channel"
            buildConfigField("String", "TARGET_PACKAGE", "\"com.tencent.qqmusic\"")
        }
    }

    targetProjectPath = ":app-mobile"
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
