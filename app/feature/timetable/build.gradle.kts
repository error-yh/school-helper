plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val rapidOcrTrial = providers.gradleProperty("runRapidOcrTrial").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.lingion.sleepy"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    // Promote the pinned, device-tested upstream source without copying or forking it.
    sourceSets.getByName("main").java.srcDirs("src/rapidTrial/java/io", "src/rapidTrial/java/de")
    sourceSets.getByName("main").assets.srcDir("src/rapidTrial/assets")
    if (rapidOcrTrial) {
        sourceSets.getByName("androidTest").java.srcDir("src/rapidTrial/java/com")
    }
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("test").resources.srcDir("$rootDir/../docs/stage-3/import-examples/xlsx")
    sourceSets.getByName("androidTest").assets.srcDir("$rootDir/../docs/stage-3/import-examples/xlsx")
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
    if (providers.gradleProperty("runCalendarRoomJvm").map(String::toBoolean).getOrElse(false)) {
        sourceSets.getByName("test").java.srcDir("src/roomJvmTest/java")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val testHome = layout.buildDirectory.dir("robolectric-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("robolectricDependencyDir").orNull?.let { systemProperty("robolectric.dependency.dir", it) }
}

dependencies {
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    // Standard (non-OpenMP) variant; models and full notices are bundled in assets/ocr.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Apache-2.0; pinned source/notice: docs/stage-3/pdf-parser-api.md
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Reuse the host's pinned Activity Result integration for the system document picker.
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    api("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    if (providers.gradleProperty("runCalendarRoomJvm").map(String::toBoolean).getOrElse(false)) {
        testImplementation("org.robolectric:robolectric:4.17")
    }
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
