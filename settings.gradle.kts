pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "cortexn-aura-sapient"

// include(":modules:aura-runtime")
include(":modules:cortexn-snn")
include(":modules:audiogen-lite")
include(":modules:privacy-core")
include(":modules:agents-vision-yolo")
// include(":modules:agents-asr-whisper")
include(":modules:agents-predictor")
include(":apps:android")
