# Android NDK Toolchain File
# Provides cross-compilation support for Android targets

# Validation
if(NOT DEFINED ANDROID_NDK)
    if(DEFINED ENV{ANDROID_NDK})
        set(ANDROID_NDK $ENV{ANDROID_NDK})
    elseif(DEFINED ENV{ANDROID_NDK_HOME})
        set(ANDROID_NDK $ENV{ANDROID_NDK_HOME})
    else()
        message(FATAL_ERROR "ANDROID_NDK not defined. Set ANDROID_NDK environment variable.")
    endif()
endif()

if(NOT EXISTS ${ANDROID_NDK})
    message(FATAL_ERROR "ANDROID_NDK path does not exist: ${ANDROID_NDK}")
endif()

message(STATUS "Using Android NDK: ${ANDROID_NDK}")

# System configuration
set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION ${ANDROID_PLATFORM})

# Default values
if(NOT DEFINED ANDROID_PLATFORM)
    set(ANDROID_PLATFORM "android-26")
endif()

if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a")
endif()

if(NOT DEFINED ANDROID_STL)
    set(ANDROID_STL "c++_shared")
endif()

message(STATUS "Android Platform: ${ANDROID_PLATFORM}")
message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "Android STL: ${ANDROID_STL}")

# Use the official NDK toolchain file
set(CMAKE_ANDROID_NDK ${ANDROID_NDK})
set(CMAKE_ANDROID_ARCH_ABI ${ANDROID_ABI})
set(CMAKE_ANDROID_STL_TYPE ${ANDROID_STL})

# Extract platform level from ANDROID_PLATFORM (e.g., "android-26" -> 26)
string(REGEX MATCH "[0-9]+" ANDROID_PLATFORM_LEVEL ${ANDROID_PLATFORM})
set(CMAKE_SYSTEM_VERSION ${ANDROID_PLATFORM_LEVEL})

# Include official NDK toolchain
include(${ANDROID_NDK}/build/cmake/android.toolchain.cmake)

# ARM-specific optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8-A optimizations
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
    
    # Check for optional features
    if(ENABLE_CRYPTO_EXT)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} +crypto")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} +crypto")
        add_definitions(-DHAVE_CRYPTO_EXT)
    endif()
    
    if(ENABLE_DOTPROD)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} +dotprod")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} +dotprod")
        add_definitions(-DHAVE_DOTPROD)
    endif()
    
    if(ENABLE_I8MM)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} +i8mm")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} +i8mm")
        add_definitions(-DHAVE_I8MM)
    endif()
    
    if(ENABLE_SME2)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv9-a+sme2")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv9-a+sme2")
        add_definitions(-DHAVE_SME2)
        message(STATUS "SME2 support enabled")
    endif()
    
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # ARMv7-A with NEON
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
    add_definitions(-DHAVE_NEON)
endif()

# Compiler optimizations
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -ffast-math -fvisibility=hidden")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -ffast-math -fvisibility=hidden")
    
    # Link-time optimization
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -flto=thin")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -flto=thin")
        set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -flto=thin")
    endif()
endif()

# Debug settings
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -g -O0 -DDEBUG")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -O0 -DDEBUG")
endif()

# Common flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -Werror=return-type")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Werror=return-type")

# Android-specific definitions
add_definitions(-DANDROID)
add_definitions(-D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL})

# Find Android libraries
find_library(ANDROID_LOG_LIB log)
find_library(ANDROID_LIB android)

message(STATUS "Android toolchain configured successfully")
