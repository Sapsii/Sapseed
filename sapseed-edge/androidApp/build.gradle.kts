import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun readDotEnv(file: File): Map<String, String> = if (!file.isFile) emptyMap() else file.readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("\"") }

val dotEnv = readDotEnv(rootProject.file(".env"))
fun localSetting(name: String): String = providers.environmentVariable(name).orNull ?: dotEnv[name].orEmpty()
fun quotedBuildConfig(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    alias(libs.plugins.androidApplication)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":edge"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
check(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "Android release signing requires all ANDROID_KEYSTORE_* and ANDROID_KEY_* variables"
}

android {
    namespace = "app.sapsii.sapseed"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.sapsii.sapseed"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.environmentVariable("SAPSEED_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("SAPSEED_VERSION_NAME").orElse("1.0").get()
        buildConfigField("String", "SAPSEED_API_URL", quotedBuildConfig(localSetting("SAPSEED_API_URL")))
        buildConfigField("String", "SAPSEED_DEVICE_AUTHORIZATION", quotedBuildConfig(localSetting("SAPSEED_DEVICE_AUTHORIZATION")))

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = checkNotNull(releaseKeystorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
