import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The Android target needs an installed Android SDK to configure. Detect it so the
// rest of the project (core, desktop, and the JVM preview of this app) builds on
// machines without one. Pass -PenableAndroid=false to force it off even when an
// SDK exists (used by CI to run the same build everywhere). See README
// "Building the mobile app".
val androidSdkDir: String? = run {
    val localProps = rootProject.file("local.properties")
    val fromLocalProps = if (localProps.exists()) {
        Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else null
    (fromLocalProps ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.takeIf { it.isNotBlank() }
}
val androidEnabled = androidSdkDir != null && findProperty("enableAndroid")?.toString() != "false"

if (androidEnabled) {
    apply(plugin = libs.plugins.android.application.get().pluginId)
}

kotlin {
    jvm("preview") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    if (System.getProperty("os.name").startsWith("Mac")) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "MobileApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val previewMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.15.0")
            }
        }
    }
}

if (androidEnabled) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
        namespace = "com.tward.watcher.mobile"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        defaultConfig {
            applicationId = "com.tward.watcher.mobile"
            minSdk = libs.versions.androidMinSdk.get().toInt()
            targetSdk = libs.versions.androidTargetSdk.get().toInt()
            versionCode = 1
            versionName = project.version.toString()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.tward.watcher.mobile.PreviewMainKt"
    }
}
