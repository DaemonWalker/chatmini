plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chatmini.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chatmini.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Build variants:
    //   arm64Debug / arm64Release     -> real ARM64 device (arm64 libs + arm64 mihomo)
    //   pcSimuDebug / pcSimuRelease   -> PC emulator with ARM translation
    //                                    (arm64 libs + amd64 mihomo to avoid SIGILL)
    //   x86_64Debug / x86_64Release   -> pure x86_64 device/emulator
    //                                    (x86_64 libs + amd64 mihomo)
    flavorDimensions += "target"
    productFlavors {
        create("arm64") {
            dimension = "target"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("pcSimu") {
            dimension = "target"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("x86_64") {
            dimension = "target"
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
    }

    // mihomo binaries are shipped as native libraries (libmihomo.so) under
    // src/main/jniLibs/<abi>/. Android extracts them into nativeLibraryDir,
    // where SELinux allows execution on Android 10+.

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// mihomo binaries are packaged as native libraries (libmihomo.so) under
// src/main/jniLibs/<abi>/. Android's native library extraction places them in
// nativeLibraryDir, where execution is permitted on Android 10+.

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // GeckoView - Firefox browser engine
    add("arm64Implementation", "org.mozilla.geckoview:geckoview-arm64-v8a:130.0.20240913135723")
    add("pcSimuImplementation", "org.mozilla.geckoview:geckoview-arm64-v8a:130.0.20240913135723")
    add("x86_64Implementation", "org.mozilla.geckoview:geckoview-x86_64:130.0.20240913135723")

    // Network & parsing
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
