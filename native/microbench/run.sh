#!/usr/bin/env bash
set -euo pipefail

# Microbenchmark runner script
# Compiles and runs GEMM benchmarks for different architectures

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"
BUILD_DIR="${PROJECT_ROOT}/build/microbench"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Check for Android NDK
if [[ -z "${ANDROID_NDK:-}" ]]; then
    log_error "ANDROID_NDK environment variable not set"
fi

# Parse arguments
TARGET="${1:-arm64}"
BUILD_TYPE="${2:-Release}"

log_info "Building microbenchmark for target: $TARGET ($BUILD_TYPE)"

# Create build directory
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Configure based on target
case "$TARGET" in
    arm64)
        log_info "Configuring for ARMv8-A with NEON"
        cmake "$PROJECT_ROOT/native/microbench" \
            --preset android-arm64-release \
            -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
        ;;
    
    armv9-sme2)
        log_info "Configuring for ARMv9-A with SME2"
        cmake "$PROJECT_ROOT/native/microbench" \
            --preset android-armv9-sme2 \
            -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
            -DENABLE_SME2=ON
        ;;
    
    armv7)
        log_info "Configuring for ARMv7-A with NEON"
        cmake "$PROJECT_ROOT/native/microbench" \
            --preset android-armv7-neon \
            -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
        ;;
    
    *)
        log_error "Unknown target: $TARGET (use: arm64, armv9-sme2, armv7)"
        ;;
esac

# Build
log_info "Building..."
cmake --build . --config "$BUILD_TYPE" -j "$(nproc)"

# Check if running on device or emulator
if command -v adb &>/dev/null && adb devices | grep -q "device$"; then
    log_info "Android device detected, deploying and running..."
    
    # Push binary to device
    BINARY="./sme2_vs_neon_gemm"
    DEVICE_PATH="/data/local/tmp/gemm_bench"
    
    adb push "$BINARY" "$DEVICE_PATH"
    adb shell chmod +x "$DEVICE_PATH"
    
    # Run benchmark
    log_info "Running benchmark on device..."
    adb shell "$DEVICE_PATH"
    
else
    log_warn "No Android device connected"
    log_info "To run on device:"
    log_info "  1. Connect device: adb connect <device>"
    log_info "  2. Run: adb shell /data/local/tmp/gemm_bench"
fi

log_info "Build complete: $BUILD_DIR"
