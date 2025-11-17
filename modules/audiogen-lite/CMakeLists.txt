cmake_minimum_required(VERSION 3.22.1)
project(audiogen_lite)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Optimization flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Werror")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -DNDEBUG")

# ARM-specific optimizations
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
    message(STATUS "ARM64 optimizations enabled")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp")
    message(STATUS "ARM32 NEON optimizations enabled")
endif()

# Enable OpenMP for parallel processing
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
    message(STATUS "OpenMP enabled")
endif()

# Source files
set(AUDIOGEN_SOURCES
    src/main/cpp/audiogen.cpp
    src/main/cpp/ring_buffer.cpp
    src/main/cpp/jni_bridge.cpp
)

# Include directories
include_directories(
    src/main/cpp/include
    ${ANDROID_NDK}/sources/android/cpufeatures
)

# Build shared library
add_library(audiogen_lite SHARED ${AUDIOGEN_SOURCES})

# Link TensorFlow Lite
add_library(tensorflowlite SHARED IMPORTED)
set_target_properties(tensorflowlite PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libtensorflowlite_jni.so
)

# Link libraries
target_link_libraries(audiogen_lite
    android
    log
    OpenSLES
    tensorflowlite
    ${OpenMP_CXX_LIBRARIES}
)

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET audiogen_lite POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:audiogen_lite>
    )
endif()
