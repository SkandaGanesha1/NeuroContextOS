plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cortexn.cortexn"
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
                    "-ffast-math",
                    "-O3",
                    "-DNDEBUG",
                    "-fvisibility=hidden"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DENABLE_SME2=ON"
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
    }
    
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.9")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
