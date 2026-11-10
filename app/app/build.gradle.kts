import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val rawArmOnly = project.findProperty("schoolHelperArmOnly")?.toString()?.trim()
val schoolHelperArmOnly: Boolean = when {
    rawArmOnly.isNullOrEmpty() -> false
    rawArmOnly.equals("true", ignoreCase = true) -> true
    rawArmOnly.equals("false", ignoreCase = true) -> false
    else -> throw GradleException("Invalid property value for schoolHelperArmOnly: '$rawArmOnly'. Must be 'true' or 'false'.")
}

val releaseKeystore = providers.environmentVariable("SCHOOL_HELPER_RELEASE_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("SCHOOL_HELPER_RELEASE_PASSWORD").orNull
val releaseAlias = "school-helper-release"
val releaseCertificateSha256 = "4D8836EBB9A7964FF103DFFC3AA84D2D2A7800DC94AB0577DCAEE1CA469BE0AD"

android {
    namespace = "com.aggapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.schoolhelper.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (schoolHelperArmOnly) {
            ndk {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }
    }
    buildToolsVersion = "34.0.0"

    val localReleaseSigning = if (!releaseKeystore.isNullOrBlank() && !releasePassword.isNullOrEmpty()) {
        signingConfigs.create("localRelease") {
            storeFile = file(releaseKeystore)
            storeType = "PKCS12"
            storePassword = releasePassword
            keyAlias = releaseAlias
            keyPassword = releasePassword
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = localReleaseSigning
        }
        debug {
            // 调试包独立包名，与 release 包共存（调试日志导出/全量请求日志仅 debug 包开启）
            applicationIdSuffix = if (providers.gradleProperty("schoolHelperMigrationSafTarget").orNull == "true") {
                ".migrationtest.target"
            } else if (providers.gradleProperty("schoolHelperMigrationSandbox").orNull == "true") {
                ".migrationtest"
            } else {
                ".debug"
            }
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

val verifyReleaseSigning by tasks.registering {
    doLast {
        if (releaseKeystore.isNullOrBlank() || releasePassword.isNullOrEmpty()) {
            throw GradleException("School Helper release signing requires a local keystore and password.")
        }
        val password = releasePassword.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            file(releaseKeystore).inputStream().use { store.load(it, password) }
            val certificate = store.getCertificate(releaseAlias)
            if (!store.isKeyEntry(releaseAlias) || certificate == null) {
                throw GradleException("School Helper release key alias is missing.")
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02X".format(it) }
            if (fingerprint != releaseCertificateSha256) {
                throw GradleException("School Helper release certificate does not match the registered identity.")
            }
        } catch (e: GradleException) {
            throw e
        } catch (_: Exception) {
            throw GradleException("Cannot open the School Helper release keystore. Check the local password and file.")
        } finally {
            password.fill('\u0000')
        }
    }
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
