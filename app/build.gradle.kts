import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名：根目录放 keystore.properties（已 gitignore）就用正式密钥；
// 没有这个文件（比如别人克隆仓库）自动退回 debug 签名，保证谁都能编译。
val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore.properties")
val hasReleaseKey = keystoreFile.exists()
if (hasReleaseKey) {
    keystoreFile.inputStream().use { keystoreProps.load(it) }
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
        // 版本号约定：
        // - versionName 用三段式 主.次.修：修 bug 升修，加功能升次，大改升主（2.1.0 → 2.1.1 → 2.2.0 → 3.0.0）
        // - versionCode 每次对外发布（含预发行）+1，永不复用；正式版晚于其预发行发布，号自然更高
        // - 预发行后缀递增：2.2.0-dev.1 → 2.2.0-beta.1 → 2.2.0-rc.1 → 2.2.0 正式
        versionCode = 4
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // main 分支 false（隐藏开发者选项）；dev 分支改 true
        buildConfigField("boolean", "DEV_OPTIONS", "false")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 有正式密钥用正式签名；没有则退回 debug 签名（开源克隆者可直接编译）。
            // 注意：正式签名与 debug 签名互不兼容，换签名必须卸载重装，会丢登录态和缓存。
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
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
