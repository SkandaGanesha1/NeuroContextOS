#!/usr/bin/env bash
set -euo pipefail

# run_armie_sme2.sh
# Validates SME2 (Scalable Matrix Extension 2) kernels using Arm Instruction Emulator (armie)
# Emulates SME2 instructions on non-SME2 hardware for testing and validation

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_ROOT/build"
OUTPUT_DIR="$PROJECT_ROOT/metrics/armie"

ARMIE_PATH="${ARMIE_PATH:-/opt/arm/armie}"
TEST_BINARY="${TEST_BINARY:-$BUILD_DIR/tests/sme2_kernel_test}"
ARMIE_CONFIG="${ARMIE_CONFIG:-sme2-default}"

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
    
    # Check if armie is installed
    if [[ ! -d "$ARMIE_PATH" ]]; then
        log_error "Arm Instruction Emulator not found at $ARMIE_PATH"
        log_info "Download from: https://developer.arm.com/Tools%20and%20Software/Arm%20Instruction%20Emulator"
    fi
    
    # Check for armie binary
    if [[ ! -x "$ARMIE_PATH/bin/armie" ]]; then
        log_error "armie binary not executable: $ARMIE_PATH/bin/armie"
    fi
    
    # Check test binary
    if [[ ! -f "$TEST_BINARY" ]]; then
        log_error "Test binary not found: $TEST_BINARY"
        log_info "Build the project first: ./scripts/build_android.sh"
    fi
    
    log_info "✓ Prerequisites satisfied"
}

# Display armie version
show_armie_info() {
    log_info "Arm Instruction Emulator information:"
    "$ARMIE_PATH/bin/armie" --version
    echo ""
    
    log_info "Supported architectures:"
    "$ARMIE_PATH/bin/armie" --list-archs
    echo ""
}

# Setup armie environment
setup_armie_environment() {
    log_info "Setting up armie environment..."
    
    export PATH="$ARMIE_PATH/bin:$PATH"
    export LD_LIBRARY_PATH="$ARMIE_PATH/lib:${LD_LIBRARY_PATH:-}"
    
    # Create output directory
    mkdir -p "$OUTPUT_DIR"
    
    log_info "✓ Environment configured"
}

# Run SME2 kernel test under armie
run_sme2_test() {
    local test_name="$1"
    local arch="${2:-armv9-a+sme2}"
    
    log_info "Running test: $test_name on $arch"
    
    local output_file="$OUTPUT_DIR/${test_name}_$(date +%Y%m%d_%H%M%S).log"
    local stats_file="$OUTPUT_DIR/${test_name}_stats.json"
    
    # Run with armie
    "$ARMIE_PATH/bin/armie" \
        --march="$arch" \
        --msve-vector-bits=256 \
        --plugin=libarmie_emulate.so \
        --plugin-option="model=scalar-plus-sve" \
        --plugin-option="emulate-sve=on" \
        --plugin-option="emulate-sme=on" \
        --plugin-option="emulate-sme2=on" \
        --stats="$stats_file" \
        -- "$TEST_BINARY" "$test_name" 2>&1 | tee "$output_file"
    
    local exit_code=${PIPESTATUS[0]}
    
    if [[ $exit_code -eq 0 ]]; then
        log_info "✓ Test passed: $test_name"
    else
        log_error "✗ Test failed: $test_name (exit code: $exit_code)"
    fi
    
    # Parse statistics
    parse_armie_stats "$stats_file" "$test_name"
}

# Parse armie statistics
parse_armie_stats() {
    local stats_file="$1"
    local test_name="$2"
    
    if [[ ! -f "$stats_file" ]]; then
        log_warn "Stats file not found: $stats_file"
        return
    fi
    
    log_info "Statistics for $test_name:"
    
    # Extract key metrics using jq if available
    if command -v jq &>/dev/null; then
        local total_insn=$(jq -r '.instructions.total // "N/A"' "$stats_file")
        local sme2_insn=$(jq -r '.instructions.sme2 // "N/A"' "$stats_file")
        local cycles=$(jq -r '.cycles.total // "N/A"' "$stats_file")
        
        echo "  Total instructions: $total_insn"
        echo "  SME2 instructions: $sme2_insn"
        echo "  Total cycles: $cycles"
        
        if [[ "$total_insn" != "N/A" && "$cycles" != "N/A" ]]; then
            local ipc=$(echo "scale=2; $total_insn / $cycles" | bc)
            echo "  IPC: $ipc"
        fi
    else
        # Fallback: cat the JSON
        cat "$stats_file"
    fi
    
    echo ""
}

# Run comprehensive SME2 validation suite
run_validation_suite() {
    log_info "Running SME2 validation suite..."
    
    local tests=(
        "matmul_int8_sme2"
        "matmul_fp16_sme2"
        "gemm_sme2"
        "conv2d_sme2"
        "depthwise_conv_sme2"
        "attention_sme2"
    )
    
    local passed=0
    local failed=0
    
    for test in "${tests[@]}"; do
        log_info "[$((passed + failed + 1))/${#tests[@]}] Testing: $test"
        
        if run_sme2_test "$test" "armv9-a+sme2"; then
            ((passed++))
        else
            ((failed++))
        fi
        
        echo ""
    done
    
    log_info "=" * 60
    log_info "Validation Results:"
    log_info "  Passed: $passed / ${#tests[@]}"
    log_info "  Failed: $failed / ${#tests[@]}"
    log_info "=" * 60
    
    return $failed
}

# Compare SME2 vs NEON performance
compare_sme2_neon() {
    log_info "Comparing SME2 vs NEON performance..."
    
    local test="matmul_benchmark"
    
    # Run with SME2
    log_info "Running with SME2..."
    run_sme2_test "${test}_sme2" "armv9-a+sme2"
    
    # Run with NEON only (baseline)
    log_info "Running with NEON baseline..."
    run_sme2_test "${test}_neon" "armv8.2-a+fp16"
    
    # Compare results
    local sme2_stats="$OUTPUT_DIR/${test}_sme2_stats.json"
    local neon_stats="$OUTPUT_DIR/${test}_neon_stats.json"
    
    if [[ -f "$sme2_stats" && -f "$neon_stats" ]] && command -v jq &>/dev/null; then
        local sme2_cycles=$(jq -r '.cycles.total' "$sme2_stats")
        local neon_cycles=$(jq -r '.cycles.total' "$neon_stats")
        
        if [[ "$sme2_cycles" != "null" && "$neon_cycles" != "null" ]]; then
            local speedup=$(echo "scale=2; $neon_cycles / $sme2_cycles" | bc)
            log_info "SME2 speedup: ${speedup}x over NEON"
        fi
    fi
}

# Generate validation report
generate_report() {
    log_info "Generating validation report..."
    
    local report_file="$OUTPUT_DIR/sme2_validation_report.md"
    
    cat > "$report_file" <<EOF
# SME2 Validation Report

**Generated:** $(date)
**Platform:** Arm Instruction Emulator (armie)
**Architecture:** ARMv9-A + SME2

## Test Configuration

- **Vector Length:** 256 bits
- **SME Tile Size:** 16x16 (FP16), 32x32 (INT8)
- **Test Binary:** $TEST_BINARY

## Test Results

EOF
    
    # Append test results
    for log_file in "$OUTPUT_DIR"/*.log; do
        if [[ -f "$log_file" ]]; then
            local test_name=$(basename "$log_file" .log)
            echo "### $test_name" >> "$report_file"
            echo '```
            tail -n 20 "$log_file" >> "$report_file"
            echo '```' >> "$report_file"
            echo "" >> "$report_file"
        fi
    done
    
    cat >> "$report_file" <<EOF

## Instruction Mix

$(if command -v jq &>/dev/null; then
    for stats_file in "$OUTPUT_DIR"/*_stats.json; do
        if [[ -f "$stats_file" ]]; then
            echo "**$(basename "$stats_file" _stats.json):**"
            jq -r '.instructions | to_entries | .[] | "  - \(.key): \(.value)"' "$stats_file" 2>/dev/null || echo "  N/A"
        fi
    done
else
    echo "jq not available for detailed analysis"
fi)

## Performance Metrics

- **Instruction Throughput:** See individual test logs
- **Cycle Counts:** See stats JSON files in \`$OUTPUT_DIR\`

## Notes

- All tests executed under emulation; actual hardware performance will vary
- SME2 emulation includes cycle-accurate modeling
- For production benchmarks, use real ARMv9-A hardware with SME2 support

---

*Report generated by run_armie_sme2.sh*
EOF
    
    log_info "✓ Report saved: $report_file"
}

# Main execution
main() {
    log_info "=" * 60
    log_info "SME2 Validation with Arm Instruction Emulator"
    log_info "=" * 60
    
    check_prerequisites
    show_armie_info
    setup_armie_environment
    
    # Run validation suite
    run_validation_suite
    
    # Optional: Performance comparison
    if [[ "${COMPARE_NEON:-0}" == "1" ]]; then
        compare_sme2_neon
    fi
    
    # Generate report
    generate_report
    
    log_info "=" * 60
    log_info "✓ SME2 validation complete!"
    log_info "Output directory: $OUTPUT_DIR"
    log_info "=" * 60
}

main "$@"
