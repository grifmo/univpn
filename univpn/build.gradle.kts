plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release builds get their version from the git tag: release.yml passes
// -PreleaseVersion=1.2.3 (or 1.2.3-beta.4). Local builds fall back to 0.0.0-dev.
//
// versionCode = MMmmpp·100 + NN, where NN is the pre-release number (1-98) or 99 for a
// final release, so 1.1.0-beta.1 (1010001) < 1.1.0-beta.2 (1010002) < 1.1.0 (1010099).
// Pre-release numbers must keep increasing within a version, whatever the label.
val releaseVersion: String? = findProperty("releaseVersion")?.toString()?.removePrefix("v")
val (appVersionName, appVersionCode) = if (releaseVersion == null) {
    "0.0.0-dev" to 1
} else {
    val m = Regex("""(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z]+\.(\d+))?""").matchEntire(releaseVersion)
        ?: error("releaseVersion '$releaseVersion' must look like 1.2.3 or 1.2.3-beta.4")
    val major = m.groupValues[1].toInt()
    val minor = m.groupValues[2].toInt()
    val patch = m.groupValues[3].toInt()
    val preNumber = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()
    require(minor < 100 && patch < 100 && (preNumber == null || preNumber in 1..98)) {
        "releaseVersion '$releaseVersion': minor/patch must be < 100 and pre-release number 1-98"
    }
    releaseVersion to (major * 1_000_000 + minor * 10_000 + patch * 100 + (preNumber ?: 99))
}

// Release signing comes from the environment (GitHub secrets in CI, never a file in the
// repo). Without RELEASE_KEYSTORE_PATH the release APK is built unsigned.
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

android {
    namespace = "com.univpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.univpn.app"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64", "x86")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API < 26 (MullvadConnector date parsing)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(17)
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                // PKCS12 keystores use one password for the store and the key
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs {
            pickFirsts += setOf("**/*.so")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // WireGuard
    implementation("com.wireguard.android:tunnel:1.0.20211029")

    // Embedded HTTP server for web-based config import
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Jetpack Security (EncryptedSharedPreferences for credential storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ViewModel + LiveData + Fragment/Activity KTX delegates
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // QR code generation (no camera permission needed)
    implementation("com.google.zxing:core:3.5.3")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

}
