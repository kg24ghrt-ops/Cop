plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pot.cil.hj"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pot.cil.hj"
        minSdk = 29 // Upgraded to Android 10 baseline for modern NDK & Hardware Buffer zero-copy support
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // NDK configuration for C++ Engine
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_static"
                    // -DANDROID_ARM_NEON=TRUE removed to prevent '-mfpu=neon' errors on arm64-v8a
                )
                // Restrict ABIs to modern 64-bit & common 32-bit targets
                abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            }
        }
    }

    // Link CMake build file for native C++ compilation
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    // Pass Release build flag down to CMake for -O3 and -flto Clang optimizations
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        debug {
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Debug")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
