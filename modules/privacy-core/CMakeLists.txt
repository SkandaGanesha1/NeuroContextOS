cmake_minimum_required(VERSION 3.22.1)
project(privacy_core)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Optimization flags

set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")


# ARM-specific optimizations
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+crypto")
    message(STATUS "ARM64 crypto extensions enabled")
endif()

# Microsoft SEAL (Homomorphic Encryption)
# Note: SEAL requires building from source or prebuilt binaries
set(SEAL_DIR ${CMAKE_SOURCE_DIR}/third_party/SEAL)

if(EXISTS ${SEAL_DIR})
    message(STATUS "SEAL found at ${SEAL_DIR}")
    include_directories(${SEAL_DIR}/native/src)
    add_definitions(-DHAVE_SEAL)
    
    # Link prebuilt SEAL library
    add_library(seal SHARED IMPORTED)
    set_target_properties(seal PROPERTIES
        IMPORTED_LOCATION ${SEAL_DIR}/lib/${ANDROID_ABI}/libseal.so
    )
else()
    message(WARNING "SEAL not found, homomorphic encryption will be disabled")
endif()

# Source files
set(PRIVACY_SOURCES
    src/main/cpp/seal_jni.cpp
)

# Include directories
include_directories(
    src/main/cpp/include
)

# Build shared library
add_library(privacy_core SHARED ${PRIVACY_SOURCES})

target_compile_options(privacy_core PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)


# Link libraries
target_link_libraries(privacy_core
    android
    log
)

# Link SEAL if available
if(EXISTS ${SEAL_DIR})
    target_link_libraries(privacy_core seal)
endif()

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET privacy_core POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:privacy_core>
    )
endif()
