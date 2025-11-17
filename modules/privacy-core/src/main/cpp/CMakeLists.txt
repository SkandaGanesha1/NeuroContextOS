cmake_minimum_required(VERSION 3.22.1)
project(privacy_core_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Werror -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math")
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0 -DDEBUG")

# ARM-specific optimizations
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    # Enable ARM crypto extensions for AES, SHA
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+crypto")
    message(STATUS "ARM64 crypto extensions enabled")
    add_definitions(-DARM_CRYPTO_AVAILABLE)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
    message(STATUS "ARMv7 NEON enabled")
endif()

# Platform definitions
add_definitions(-DANDROID)

# ============================================================================
# Microsoft SEAL (Homomorphic Encryption) - OPTIONAL
# ============================================================================

# SEAL installation paths
set(SEAL_ROOT_DIR "${CMAKE_SOURCE_DIR}/../../../third_party/SEAL" CACHE PATH "Path to SEAL installation")
set(SEAL_INCLUDE_DIR "${SEAL_ROOT_DIR}/native/src" CACHE PATH "SEAL include directory")
set(SEAL_LIB_DIR "${SEAL_ROOT_DIR}/lib/${ANDROID_ABI}" CACHE PATH "SEAL library directory")

# Check if SEAL is available
if(EXISTS "${SEAL_INCLUDE_DIR}/seal/seal.h")
    message(STATUS "Microsoft SEAL found at ${SEAL_ROOT_DIR}")
    message(STATUS "  Include: ${SEAL_INCLUDE_DIR}")
    message(STATUS "  Library: ${SEAL_LIB_DIR}")
    
    set(HAVE_SEAL ON)
    add_definitions(-DHAVE_SEAL)
    
    # Import prebuilt SEAL library
    add_library(seal SHARED IMPORTED)
    set_target_properties(seal PROPERTIES
        IMPORTED_LOCATION "${SEAL_LIB_DIR}/libseal.so"
        INTERFACE_INCLUDE_DIRECTORIES "${SEAL_INCLUDE_DIR}"
    )
    
    # SEAL requires these dependencies
    # Note: SEAL typically needs Intel HEXL for performance, but we disable it for ARM
    add_definitions(-DSEAL_USE_INTEL_HEXL=OFF)
    
else()
    message(WARNING "Microsoft SEAL not found at ${SEAL_ROOT_DIR}")
    message(WARNING "Homomorphic encryption will be disabled")
    message(WARNING "To enable: Place SEAL library in third_party/SEAL/")
    set(HAVE_SEAL OFF)
endif()

# ============================================================================
# Include directories
# ============================================================================

set(INCLUDE_DIRS
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${ANDROID_NDK}/sources/android/support/include
)

if(HAVE_SEAL)
    list(APPEND INCLUDE_DIRS ${SEAL_INCLUDE_DIR})
endif()

include_directories(${INCLUDE_DIRS})

# ============================================================================
# Source files
# ============================================================================

set(PRIVACY_SOURCES
    seal_jni.cpp
)

# Optional: Add more C++ sources here
# set(PRIVACY_SOURCES ${PRIVACY_SOURCES}
#     crypto_utils.cpp
#     secure_random.cpp
# )

# ============================================================================
# Build shared library
# ============================================================================

add_library(privacy_core SHARED ${PRIVACY_SOURCES})

# Link system libraries
target_link_libraries(privacy_core
    android       # Android native APIs
    log           # Android logging
    m             # Math library
)

# Link SEAL if available
if(HAVE_SEAL)
    target_link_libraries(privacy_core seal)
    message(STATUS "Linking with SEAL library")
endif()

# ============================================================================
# Compiler-specific settings
# ============================================================================

# Enable link-time optimization in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -flto=thin")
        message(STATUS "Link-time optimization enabled (ThinLTO)")
    endif()
endif()

# Position-independent code
set_target_properties(privacy_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

# ============================================================================
# Post-build steps
# ============================================================================

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET privacy_core POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:privacy_core>
        COMMENT "Stripping symbols from release build"
    )
    message(STATUS "Symbol stripping enabled for release builds")
endif()

# Copy SEAL library to output directory (if available)
if(HAVE_SEAL AND EXISTS "${SEAL_LIB_DIR}/libseal.so")
    add_custom_command(TARGET privacy_core POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${SEAL_LIB_DIR}/libseal.so"
            $<TARGET_FILE_DIR:privacy_core>
        COMMENT "Copying SEAL library to output directory"
    )
endif()

# ============================================================================
# Installation (optional)
# ============================================================================

# Install library to jniLibs directory
install(TARGETS privacy_core
    LIBRARY DESTINATION ${CMAKE_SOURCE_DIR}/../../../jniLibs/${ANDROID_ABI}
)

if(HAVE_SEAL)
    install(FILES "${SEAL_LIB_DIR}/libseal.so"
        DESTINATION ${CMAKE_SOURCE_DIR}/../../../jniLibs/${ANDROID_ABI}
    )
endif()

# ============================================================================
# Debug information
# ============================================================================

message(STATUS "==================================================")
message(STATUS "Privacy Core Native Build Configuration")
message(STATUS "==================================================")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID}")
message(STATUS "SEAL support: ${HAVE_SEAL}")
message(STATUS "Output directory: $<TARGET_FILE_DIR:privacy_core>")
message(STATUS "==================================================")
