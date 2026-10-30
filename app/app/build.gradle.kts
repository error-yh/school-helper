plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aggapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.schoolhelper.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildToolsVersion = "34.0.0"

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = null
        }
        debug {
            // 调试包独立包名，与 release 包共存（调试日志导出/全量请求日志仅 debug 包开启）
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // 校园助手独立产物名，避免与原版 APK 混淆。
    applicationVariants.all {
        val debugSuffix = if (buildType.name == "debug") "-debug" else ""
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "school-helper${debugSuffix}-v${versionName}.apk"
        }
    }
}

// Stage 1 has no production signing identity. Fail closed before any release packaging.
val verifyReleaseSigning by tasks.registering {
    doLast { throw GradleException("Release signing is not configured for School Helper. Debug signing fallback is prohibited.") }
}
// Keep release unit tests/lint runnable; stop every release APK/AAB packaging entry point.
tasks.matching { it.name in setOf("packageRelease", "assembleRelease", "bundleRelease", "packageReleaseBundle", "signReleaseBundle") }
    .configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(project(":feature:timetable"))
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(project(":platform:api"))
    implementation(project(":platform:pdd"))
    implementation(project(":platform:taobao"))
    implementation(project(":platform:jd"))
    implementation(project(":platform:sf"))
    implementation(project(":platform:ryll"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation("androidx.webkit:webkit:1.11.0") // WebViewAssetLoader：极验页需 https origin（file:// origin 为 null 会被 gt4.js 拒绝）
    implementation("io.coil-kt:coil-compose:2.6.0") // 验证题图片选项（收货地址截图等）异步加载
    implementation("androidx.browser:browser:1.8.0") // Chrome Custom Tabs：PDD 验证页反 WebView 渲染隐藏，必须用真 Chrome 管线显示
}

