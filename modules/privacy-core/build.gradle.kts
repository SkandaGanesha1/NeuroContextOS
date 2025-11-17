plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

android {
    namespace = "com.cortexn.privacy"
    compileSdk = 34
    ndkVersion = "27.0.12077973"
    
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
        
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-DNDEBUG",
                    "-fvisibility=hidden"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSEAL_USE_INTEL_HEXL=OFF"
                )
            }
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        
        debug {
            isJniDebuggable = true
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlin.ExperimentalStdlibApi"
        )
    }
    
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.ktor:ktor-network:2.3.7")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Cryptography
    implementation("com.google.crypto.tink:tink-android:1.12.0")
    
    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
