cmake_minimum_required(VERSION 3.22.1)
project(cortexn_snn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Detect ARM architecture features
include(CheckCXXSourceCompiles)

# Check for NEON support
check_cxx_source_compiles("
    #include <arm_neon.h>
    int main() { float32x4_t v = vdupq_n_f32(1.0f); return 0; }
" HAVE_NEON)

# Check for I8MM (Int8 Matrix Multiply)
check_cxx_source_compiles("
    #include <arm_neon.h>
    int main() { 
        int32x4_t v = vdupq_n_s32(0);
        return 0; 
    }
" HAVE_I8MM)

# Compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Werror")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -DNDEBUG")

# ARM-specific optimizations
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
    
    # Enable NEON
    if(HAVE_NEON)
        message(STATUS "NEON support detected")
        add_definitions(-DHAVE_NEON)
    endif()
    
    # Enable I8MM if available
    if(HAVE_I8MM)
        message(STATUS "I8MM support detected")
        add_definitions(-DHAVE_I8MM)
    endif()
    
    # Enable SME2 for ARMv9-A (experimental)
    if(ENABLE_SME2)
        message(STATUS "SME2 support enabled (requires ARMv9-A)")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv9-a+sme2")
        add_definitions(-DHAVE_SME2)
    endif()
endif()

# Source files
set(CORTEXN_SNN_SOURCES
    src/main/cpp/cortexn_snn.cpp
    src/main/cpp/lif_layer.cpp
    src/main/cpp/spike_encoder.cpp
    src/main/cpp/kernels/spike_dense_neon.cpp
)

# Add assembly sources for NEON
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
    list(APPEND CORTEXN_SNN_SOURCES
        src/main/cpp/kernels/spike_dense_neon.S
    )
endif()

# Add SME2 sources if enabled
if(ENABLE_SME2)
    list(APPEND CORTEXN_SNN_SOURCES
        src/main/cpp/kernels/spike_dense_sme2.cpp
        src/main/cpp/kernels/sme2_bench.cpp
    )
endif()

# Include directories
include_directories(
    src/main/cpp/include
    src/main/cpp/utils
)

# Build shared library
add_library(cortexn_snn SHARED ${CORTEXN_SNN_SOURCES})

# Link libraries
target_link_libraries(cortexn_snn
    android
    log
    m
)

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET cortexn_snn POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:cortexn_snn>
    )
endif()
