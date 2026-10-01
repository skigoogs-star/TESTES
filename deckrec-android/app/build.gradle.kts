plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Picks the newest NDK or CMake already installed beside the SDK.
 *
 * Pinning an exact version means the build breaks the day the CI image is trimmed or bumped, and
 * asking Gradle to fetch a specific one needs network access to dl.google.com that this project's
 * sandbox does not have. Reading what is actually on disk avoids both.
 */
fun newestSdkComponent(directory: String, accept: (String) -> Boolean = { true }): String? {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
    return File(sdk, directory).listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?.filter(accept)
        // Compared numerically, so 29.0.1 beats 9.0.1 and 3.31.5 beats 3.9.0.
        ?.maxWithOrNull(compareBy { name ->
            name.split('.').map { part -> part.toIntOrNull() ?: 0 }
                .let { parts -> (parts + List(4) { 0 }).take(4) }
                .fold(0L) { acc, part -> acc * 10_000 + part }
        })
}

android {
    namespace = "com.deckrec"
    compileSdk = 35

    newestSdkComponent("ndk")?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "com.deckrec"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // The only architecture this app is used on. Building the other three triples native
            // build time and adds nothing: no 32-bit phone is going to host a 12-channel USB
            // capture stream, and x86 targets emulators, which cannot pass through a USB mixer.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Deliberately the newest 3.x rather than the newest overall: the runner image also
            // ships CMake 4.x, which the Android Gradle Plugin does not support, so "newest" would
            // have picked the one version guaranteed to fail.
            newestSdkComponent("cmake") { it.startsWith("3.") }?.let { version = it }
        }
    }

    signingConfigs {
        // Every build must be signed with the SAME key, or none of them can be installed over each
        // other. Left to itself Gradle invents a debug keystore in the build machine's home
        // directory, and CI hands out a fresh machine per run — so each APK was signed by a
        // different key and Android rejected every update with a bare "App not installed".
        //
        // These are Android's own well-known debug credentials (alias androiddebugkey, password
        // "android"), so the checked-in file is not a secret and grants nothing: it identifies
        // debug builds and nothing else. A store-distributed release would need a real key kept
        // outside the repository.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // The DSP package is pure Kotlin, so its regressions are checked on the JVM rather than a device.
    testImplementation("junit:junit:4.13.2")
}
