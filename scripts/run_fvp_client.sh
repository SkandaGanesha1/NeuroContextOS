#!/usr/bin/env bash
set -euo pipefail

# run_fvp_client.sh
# Boots Fixed Virtual Platform (FVP) and runs SME2 test binaries
# Supports both Base Platform and Morello FVPs for ARMv9-A validation

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_ROOT/build"
OUTPUT_DIR="$PROJECT_ROOT/metrics/fvp"

FVP_PATH="${FVP_PATH:-/opt/arm/fvp}"
FVP_MODEL="${FVP_MODEL:-FVP_Base_RevC-2xAEMvA}"
TEST_BINARY="${TEST_BINARY:-$BUILD_DIR/tests/sme2_kernel_test}"
KERNEL_IMAGE="${KERNEL_IMAGE:-$PROJECT_ROOT/fvp/Image}"
DTB_FILE="${DTB_FILE:-$PROJECT_ROOT/fvp/fvp-base-revc.dtb}"
ROOTFS_IMAGE="${ROOTFS_IMAGE:-$PROJECT_ROOT/fvp/rootfs.ext4}"

# FVP configuration
FVP_CPU_COUNT=4
FVP_MEMORY_SIZE=8192  # MB
FVP_SME_ENABLE=1
FVP_SVE_ENABLE=1
FVP_VECTOR_LENGTH=256

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check FVP installation
    if [[ ! -d "$FVP_PATH" ]]; then
        log_error "FVP not found at $FVP_PATH"
        log_info "Download Arm Fixed Virtual Platforms from:"
        log_info "https://developer.arm.com/Tools%20and%20Software/Fixed%20Virtual%20Platforms"
        exit 1
    fi
    
    # Check FVP model
    local fvp_binary="$FVP_PATH/models/Linux64_GCC-9.3/$FVP_MODEL/$FVP_MODEL"
    if [[ ! -x "$fvp_binary" ]]; then
        log_error "FVP model not found: $fvp_binary"
        log_info "Available models:"
        ls -1 "$FVP_PATH/models/Linux64_GCC-9.3/" || true
        exit 1
    fi
    
    # Check kernel image
    if [[ ! -f "$KERNEL_IMAGE" ]]; then
        log_warn "Kernel image not found: $KERNEL_IMAGE"
        log_info "You may need to build an ARM64 Linux kernel with SME2 support"
    fi
    
    # Check test binary
    if [[ ! -f "$TEST_BINARY" ]]; then
        log_warn "Test binary not found: $TEST_BINARY"
        log_info "Build the project first: ./scripts/build_android.sh"
    fi
    
    log_info "✓ Prerequisites checked"
}

# Display FVP information
show_fvp_info() {
    log_info "FVP Model Information:"
    
    local fvp_binary="$FVP_PATH/models/Linux64_GCC-9.3/$FVP_MODEL/$FVP_MODEL"
    
    "$fvp_binary" --version 2>&1 | head -n 5
    
    log_info "Configuration:"
    echo "  Model: $FVP_MODEL"
    echo "  CPUs: $FVP_CPU_COUNT"
    echo "  Memory: ${FVP_MEMORY_SIZE}MB"
    echo "  SME2: $([ $FVP_SME_ENABLE -eq 1 ] && echo 'Enabled' || echo 'Disabled')"
    echo "  Vector Length: ${FVP_VECTOR_LENGTH} bits"
    echo ""
}

# Create FVP configuration file
create_fvp_config() {
    log_info "Creating FVP configuration..."
    
    local config_file="$OUTPUT_DIR/fvp_config.txt"
    mkdir -p "$OUTPUT_DIR"
    
    cat > "$config_file" <<EOF
# FVP Configuration for SME2 Testing
# Generated: $(date)

# CPU Configuration
cluster0.NUM_CORES=$FVP_CPU_COUNT
cluster0.cpu0.CONFIG64=1
cluster0.cpu0.enable_sme=$FVP_SME_ENABLE
cluster0.cpu0.enable_sve=$FVP_SVE_ENABLE
cluster0.cpu0.vfp-enable_at_reset=1
cluster0.cpu0.sme-vector-length=$FVP_VECTOR_LENGTH
cluster0.cpu0.sve-vector-length=$FVP_VECTOR_LENGTH

# Memory Configuration
bp.dram_size=$FVP_MEMORY_SIZE

# Peripheral Configuration
bp.vis.disable_visualisation=1
bp.terminal_0.start_telnet=1
bp.terminal_0.start_port=5000
bp.terminal_1.start_telnet=1
bp.terminal_1.start_port=5001

# Security Configuration
cluster0.has_el3=1
cluster0.has_el2=1

# Performance Monitoring
pctl.startup=0.0.0.0

# Cache Configuration
cluster0.cpu0.l1_dcache-size=0x8000
cluster0.cpu0.l1_icache-size=0x8000
cluster0.l2_cache-size=0x80000
EOF
    
    log_info "✓ Configuration saved: $config_file"
    echo "$config_file"
}

# Prepare root filesystem with test binary
prepare_rootfs() {
    log_info "Preparing root filesystem..."
    
    if [[ ! -f "$ROOTFS_IMAGE" ]]; then
        log_warn "Root filesystem not found, creating minimal rootfs..."
        
        # Create minimal ext4 filesystem
        local temp_dir=$(mktemp -d)
        local rootfs_size=512  # MB
        
        dd if=/dev/zero of="$ROOTFS_IMAGE" bs=1M count=$rootfs_size
        mkfs.ext4 -F "$ROOTFS_IMAGE"
        
        # Mount and populate
        sudo mount -o loop "$ROOTFS_IMAGE" "$temp_dir"
        sudo mkdir -p "$temp_dir"/{bin,sbin,lib,usr,dev,proc,sys,tmp,root}
        
        # Copy test binary
        if [[ -f "$TEST_BINARY" ]]; then
            sudo cp "$TEST_BINARY" "$temp_dir/root/"
        fi
        
        sudo umount "$temp_dir"
        rmdir "$temp_dir"
        
        log_info "✓ Root filesystem created: $ROOTFS_IMAGE"
    else
        log_info "Using existing root filesystem: $ROOTFS_IMAGE"
    fi
}

# Launch FVP
launch_fvp() {
    log_info "Launching FVP..."
    
    local fvp_binary="$FVP_PATH/models/Linux64_GCC-9.3/$FVP_MODEL/$FVP_MODEL"
    local config_file=$(create_fvp_config)
    local log_file="$OUTPUT_DIR/fvp_$(date +%Y%m%d_%H%M%S).log"
    
    # FVP command line arguments
    local fvp_args=(
        -C bp.secure_memory=0
        -C cache_state_modelled=0
        -C bp.pl011_uart0.out_file="$log_file"
        -C bp.pl011_uart0.unbuffered_output=1
        -C bp.pl011_uart0.shutdown_on_eot=1
        --config-file "$config_file"
    )
    
    # Add kernel and dtb if available
    if [[ -f "$KERNEL_IMAGE" ]]; then
        fvp_args+=(
            -C cluster0.cpu0.RVBAR=0x04020000
            --data cluster0.cpu0="$KERNEL_IMAGE"@0x80080000
        )
    fi
    
    if [[ -f "$DTB_FILE" ]]; then
        fvp_args+=(
            --data cluster0.cpu0="$DTB_FILE"@0x83000000
        )
    fi
    
    if [[ -f "$ROOTFS_IMAGE" ]]; then
        fvp_args+=(
            -C bp.virtioblockdevice.image_path="$ROOTFS_IMAGE"
        )
    fi
    
    log_info "Starting FVP with configuration:"
    printf '%s\n' "${fvp_args[@]}" | sed 's/^/  /'
    
    # Launch FVP (run in background with timeout)
    timeout 300s "$fvp_binary" "${fvp_args[@]}" &
    local fvp_pid=$!
    
    log_info "FVP launched (PID: $fvp_pid)"
    log_info "Console output: $log_file"
    log_info "Telnet: localhost:5000"
    
    # Wait for boot or timeout
    local boot_timeout=120
    local elapsed=0
    
    log_info "Waiting for boot (timeout: ${boot_timeout}s)..."
    
    while [[ $elapsed -lt $boot_timeout ]]; do
        if grep -q "login:" "$log_file" 2>/dev/null; then
            log_info "✓ System booted successfully"
            break
        fi
        
        if ! kill -0 $fvp_pid 2>/dev/null; then
            log_error "FVP process terminated unexpectedly"
        fi
        
        sleep 2
        ((elapsed += 2))
    done
    
    if [[ $elapsed -ge $boot_timeout ]]; then
        log_warn "Boot timeout reached, continuing anyway..."
    fi
    
    # Return FVP PID
    echo $fvp_pid
}

# Execute test on FVP via telnet
execute_test_on_fvp() {
    local fvp_pid="$1"
    local test_command="${2:-/root/sme2_kernel_test}"
    
    log_info "Executing test on FVP..."
    
    # Connect via telnet and run test
    (
        sleep 2
        echo "root"  # Login
        sleep 1
        echo "$test_command"
        sleep 5
        echo "poweroff"  # Shutdown FVP
    ) | telnet localhost 5000 &>/dev/null &
    
    # Wait for FVP to complete
    local timeout=180
    local elapsed=0
    
    while kill -0 $fvp_pid 2>/dev/null && [[ $elapsed -lt $timeout ]]; do
        sleep 2
        ((elapsed += 2))
    done
    
    if kill -0 $fvp_pid 2>/dev/null; then
        log_warn "Test timeout, terminating FVP..."
        kill $fvp_pid 2>/dev/null || true
    fi
    
    log_info "✓ Test execution complete"
}

# Parse FVP output
parse_fvp_output() {
    local log_file="$1"
    
    log_info "Parsing FVP output..."
    
    if [[ ! -f "$log_file" ]]; then
        log_warn "Log file not found: $log_file"
        return
    fi
    
    log_info "Test Results:"
    grep -A 10 "SME2 Test" "$log_file" || log_warn "No test results found"
    
    # Extract performance metrics
    log_info "Performance Metrics:"
    grep -E "(cycles|instructions|time)" "$log_file" || log_warn "No metrics found"
}

# Main execution
main() {
    log_info "=" * 60
    log_info "FVP SME2 Test Runner"
    log_info "=" * 60
    
    check_prerequisites
    show_fvp_info
    prepare_rootfs
    
    # Launch FVP
    local fvp_pid=$(launch_fvp)
    
    # Execute test
    execute_test_on_fvp "$fvp_pid" "${TEST_COMMAND:-/root/sme2_kernel_test}"
    
    # Parse results
    local latest_log=$(ls -t "$OUTPUT_DIR"/fvp_*.log | head -n1)
    parse_fvp_output "$latest_log"
    
    log_info "=" * 60
    log_info "✓ FVP test complete!"
    log_info "Output directory: $OUTPUT_DIR"
    log_info "=" * 60
}

main "$@"
