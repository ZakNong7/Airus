plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.zaknong.airus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaknong.airus"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export untuk debug
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"] = "true"
            }
        }

        // CMake: konfigurasi native build
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O2",              // optimasi performa
                    "-ffast-math",      // DSP math optimization
                    "-Wall"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON"   // NEON SIMD untuk DSP
                )
            }
        }

        ndk {
            // Target ABI — semua arsitektur modern
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        ndkVersion = "28.2.13676358"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isJniDebuggable = true  // penting! untuk debug native code
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
        viewBinding = true   // untuk binding layout tanpa findViewById
    }

    packaging {
        jniLibs {
            // Include native library
            keepDebugSymbols += setOf("**/*.so")
        }
    }
}

dependencies {
    // === AndroidX Core ===
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)

    // === Navigation Component ===
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // === Room Database ===
    implementation(libs.room.runtime)
    kapt(libs.room.compiler)
    implementation(libs.room.rxjava3)          // async queries

    // === Lifecycle (ViewModel + LiveData) ===
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.service)

    // === Media Session ===
    implementation("androidx.media:media:1.7.0")

    // === Metadata Tag Reader ===
    // JAudioTagger — baca tag FLAC, MP3, OPUS, M4A
    implementation("net.jthink:jaudiotagger:2.2.5")

    // === Glide — Album Art ===
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    // === Palette — Ambil warna dari album art ===
    implementation(libs.androidx.palette)

    // === Preference ===
    implementation(libs.androidx.preference)

    // === Testing ===
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
