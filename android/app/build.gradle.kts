/**
 * Short commit the APK was built from.
 *
 * Every build so far has been "the one I sent you last" — with several APKs
 * a day and an ephemeral signing key forcing a reinstall each time, neither
 * the log nor the screen could say which one was actually running, and that
 * ambiguity has already cost a diagnosis. Falls back to "dev" outside a git
 * checkout so a source-tarball build still works.
 */
val gitSha: String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    p.inputStream.bufferedReader().readText().trim().ifEmpty { "dev" }
        .also { p.waitFor() }
} catch (_: Exception) {
    "dev"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mqvpn.app"
    compileSdk = 37

    // AGP embeds a Google-signed "dependency metadata" block in the APK
    // signing block by default. It is not reproducible and F-Droid rejects
    // it (its APK scanner flags an extra signing block), so keep it out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "org.mqvpn.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "0.15.0"
        // arm64-v8a only: must match sdk-native's abiFilters. Adding ABIs here
        // without updating sdk-native produces APKs that crash with
        // UnsatisfiedLinkError on those ABIs (no .so packaged).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    // Release signing config reads from env vars set by CI. When unset
    // (local `assembleRelease` without env), Gradle leaves the variant
    // unsigned and the build produces an unsigned APK that zipalign can
    // sign later — fine for local inspection.
    val ksPath = System.getenv("MQVPN_KEYSTORE_PATH")
    val ksPass = System.getenv("MQVPN_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("MQVPN_KEY_ALIAS")
    val keyPass = System.getenv("MQVPN_KEY_PASSWORD")
    val haveSigning = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() && !keyPass.isNullOrBlank()
    if (haveSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (haveSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":sdk-core"))

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // QR code generation (config export)
    implementation("com.google.zxing:core:3.5.3")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
