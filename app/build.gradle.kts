// 📁 app/build.gradle.kts (Module: app)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pot.cil.hj"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pot.cil.hj"
        minSdk = 24 // OpenGL ES 3.0/3.2 support; we use 3.0
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK / CMake configuration
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Optionally set abiFilters here; they can also be set in the ndk block
                // abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        // Optional: specify which ABIs to build
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    // Point to the CMakeLists.txt file
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Source sets: Kotlin sources are automatically included;
    // native sources are under src/main/cpp/ (CMake handles them)
    sourceSets {
        getByName("main") {
            // Optionally, you can explicitly add more native source directories
            // jniLibs.srcDirs("src/main/libs")
        }
    }
}

dependencies {
    // Jetpack Compose core
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Material and Ink libraries
    implementation("com.google.android.material:material:1.12.0")
    val ink_version = "1.1.0-alpha05"
    implementation("androidx.ink:ink-authoring:$ink_version")
    implementation("androidx.ink:ink-brush:$ink_version")
    implementation("androidx.ink:ink-geometry:$ink_version")
    implementation("androidx.ink:ink-nativeloader:$ink_version")
    implementation("androidx.ink:ink-rendering:$ink_version")
    implementation("androidx.ink:ink-storage:$ink_version")
    implementation("androidx.ink:ink-strokes:$ink_version")

    // Lifecycle & Activity Compose
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}