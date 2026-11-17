plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hpu.timetable"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hpu.timetable"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // main 分支 false（隐藏开发者选项）；dev 分支改 true
        buildConfigField("boolean", "DEV_OPTIONS", "true")
    }

    buildTypes {
        release {
            // 复用调试密钥签名：这样正式包能直接覆盖已装的 debug 包，
            // 不会因为签名不同被迫卸载（卸载会丢登录态和课表缓存）。
            // 将来要上架/分发再换独立 keystore，但那样必须卸载重装。
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 这台机器的 lint 工具链跑不起来（lintVitalAnalyzeRelease 报
    // lintTool.versionKey / 配置缓存序列化错误），打正式包时把它关掉。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
