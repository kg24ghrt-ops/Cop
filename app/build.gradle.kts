plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pot.cil.hj"
    compileSdk = 35

    // 👇 REQUIRED: pin the exact NDK version so all developers & CI use the same one
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.pot.cil.hj"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // 👇 REQUIRED: only package ABIs we actually build (keep APK size small)
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

        // NDK configuration for C++ engine
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_static"
                )
                // Restrict build ABIs (must match ndk.abiFilters above)
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