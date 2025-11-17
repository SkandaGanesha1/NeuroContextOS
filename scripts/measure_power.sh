#!/usr/bin/env bash
set -euo pipefail

# measure_power.sh
# Captures power consumption metrics using adb + Perfetto/ODPM
# Exports CSV with per-component power draw (CPU, GPU, NPU, DRAM)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/metrics/power"

DEVICE_SERIAL="${DEVICE_SERIAL:-}"
DURATION="${DURATION:-30}"  # seconds
PACKAGE_NAME="${PACKAGE_NAME:-com.cortexn.aura}"

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
    
    if ! command -v adb &>/dev/null; then
        log_error "adb not found. Install Android SDK platform-tools."
    fi
    
    # Check device connection
    local device_count=$(adb devices | grep -v "List" | grep "device" | wc -l)
    if [[ $device_count -eq 0 ]]; then
        log_error "No Android device connected. Connect via USB and enable USB debugging."
    elif [[ $device_count -gt 1 ]] && [[ -z "$DEVICE_SERIAL" ]]; then
        log_error "Multiple devices connected. Set DEVICE_SERIAL environment variable."
    fi
    
    log_info "✓ Device connected: $(adb ${DEVICE_SERIAL:+-s $DEVICE_SERIAL} shell getprop ro.product.model)"
}

# Setup adb command prefix
ADB_CMD="adb ${DEVICE_SERIAL:+-s $DEVICE_SERIAL}"

# Start Perfetto trace
start_perfetto_trace() {
    log_info "Starting Perfetto power trace (${DURATION}s)..."
    
    local trace_config="/sdcard/power_trace_config.pbtx"
    local trace_output="/sdcard/power_trace.perfetto-trace"
    
    # Generate Perfetto config
    cat > /tmp/power_trace_config.pbtx <<'EOF'
buffers {
    size_kb: 65536
    fill_policy: RING_BUFFER
}

data_sources {
    config {
        name: "android.power"
        android_power_config {
            battery_poll_ms: 250
            battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
            battery_counters: BATTERY_COUNTER_CHARGE
            battery_counters: BATTERY_COUNTER_CURRENT
            collect_power_rails: true
        }
    }
}

data_sources {
    config {
        name: "linux.process_stats"
        process_stats_config {
            scan_all_processes_on_start: true
            proc_stats_poll_ms: 1000
        }
    }
}

duration_ms: DURATION_MS_PLACEHOLDER
EOF
    
    # Replace duration placeholder
    sed -i "s/DURATION_MS_PLACEHOLDER/$((DURATION * 1000))/" /tmp/power_trace_config.pbtx
    
    # Push config to device
    $ADB_CMD push /tmp/power_trace_config.pbtx "$trace_config"
    
    # Start trace
    $ADB_CMD shell "perfetto \
        --config $trace_config \
        --out $trace_output \
        --background" || log_warn "Perfetto not available, falling back to batterystats"
    
    log_info "✓ Perfetto trace started"
}

# Fallback: Use batterystats
capture_batterystats() {
    log_info "Capturing batterystats..."
    
    # Reset stats
    $ADB_CMD shell dumpsys batterystats --reset
    
    log_info "Running workload for ${DURATION}s..."
    
    # Launch app if specified
    if [[ -n "$PACKAGE_NAME" ]]; then
        $ADB_CMD shell am start -n "$PACKAGE_NAME/.MainActivity"
        sleep 2
    fi
    
    # Wait for duration
    sleep "$DURATION"
    
    # Dump stats
    local stats_file="$OUTPUT_DIR/batterystats_$(date +%Y%m%d_%H%M%S).txt"
    mkdir -p "$OUTPUT_DIR"
    
    $ADB_CMD shell dumpsys batterystats > "$stats_file"
    
    log_info "✓ Batterystats saved: $stats_file"
    
    # Parse power consumption
    parse_batterystats "$stats_file"
}

# Parse batterystats output
parse_batterystats() {
    local stats_file="$1"
    local csv_file="${stats_file%.txt}.csv"
    
    log_info "Parsing batterystats to CSV..."
    
    # Extract power usage per UID
    grep -A 1000 "Estimated power use" "$stats_file" | \
    awk '
    BEGIN {
        print "component,power_mah"
    }
    /Uid [0-9]+:/ {
        gsub(/[^0-9.]/, "", $NF)
        component = $2
        power = $NF
        if (power != "") print component "," power
    }
    /Screen:/ || /Wifi:/ || /Bluetooth:/ || /Idle:/ || /Cell standby:/ {
        gsub(/[^0-9.]/, "", $NF)
        print $1 "," $NF
    }
    ' > "$csv_file"
    
    log_info "✓ CSV saved: $csv_file"
    
    # Summary
    local total_power=$(awk -F',' 'NR>1 {sum+=$2} END {print sum}' "$csv_file")
    log_info "Total power consumption: ${total_power} mAh"
}

# Parse Perfetto trace (if available)
parse_perfetto_trace() {
    log_info "Pulling Perfetto trace..."
    
    local trace_output="/sdcard/power_trace.perfetto-trace"
    local local_trace="$OUTPUT_DIR/power_trace_$(date +%Y%m%d_%H%M%S).perfetto-trace"
    
    $ADB_CMD pull "$trace_output" "$local_trace" || {
        log_warn "Failed to pull Perfetto trace, using batterystats instead"
        capture_batterystats
        return
    }
    
    log_info "✓ Trace saved: $local_trace"
    
    # Parse trace using Python (requires perfetto Python package)
    if command -v python3 &>/dev/null; then
        python3 "$SCRIPT_DIR/utils/parse_perfetto_power.py" "$local_trace" "$OUTPUT_DIR" || {
            log_warn "Perfetto parsing failed, falling back to batterystats"
            capture_batterystats
        }
    else
        log_warn "Python not available for Perfetto parsing"
        capture_batterystats
    fi
}

# Capture ODPM (On-Device Power Monitor) metrics if available
capture_odpm() {
    log_info "Checking for ODPM support..."
    
    if $ADB_CMD shell "ls /sys/class/power_supply/odpm_battery" &>/dev/null; then
        log_info "ODPM available, capturing rail-level power..."
        
        local odpm_file="$OUTPUT_DIR/odpm_$(date +%Y%m%d_%H%M%S).csv"
        
        echo "timestamp,rail,voltage_uv,current_ua,power_uw" > "$odpm_file"
        
        for i in $(seq 1 $DURATION); do
            local timestamp=$(date +%s)
            
            # Read power rails
            $ADB_CMD shell "cat /sys/class/power_supply/odpm_*/uevent" | \
            awk -v ts="$timestamp" '
            /POWER_SUPPLY_NAME=/ {name=$0; gsub(/.*=/, "", name)}
            /POWER_SUPPLY_VOLTAGE_NOW=/ {voltage=$0; gsub(/.*=/, "", voltage)}
            /POWER_SUPPLY_CURRENT_NOW=/ {current=$0; gsub(/.*=/, "", current)}
            END {
                if (name && voltage && current) {
                    power = (voltage * current) / 1000000
                    print ts "," name "," voltage "," current "," power
                }
            }' >> "$odpm_file"
            
            sleep 1
        done
        
        log_info "✓ ODPM data saved: $odpm_file"
    else
        log_warn "ODPM not available on this device"
    fi
}

# Main execution
main() {
    log_info "=" * 60
    log_info "Power Measurement Tool"
    log_info "=" * 60
    
    check_prerequisites
    
    mkdir -p "$OUTPUT_DIR"
    
    # Try Perfetto first
    if $ADB_CMD shell "which perfetto" &>/dev/null; then
        start_perfetto_trace
        
        log_info "Workload running for ${DURATION}s..."
        
        # Launch app if specified
        if [[ -n "$PACKAGE_NAME" ]]; then
            $ADB_CMD shell am start -n "$PACKAGE_NAME/.MainActivity"
        fi
        
        # Wait for trace to complete
        sleep "$DURATION"
        
        parse_perfetto_trace
    else
        # Fallback to batterystats
        capture_batterystats
    fi
    
    # Try ODPM if available
    capture_odpm
    
    log_info "=" * 60
    log_info "✓ Power measurement complete!"
    log_info "Output directory: $OUTPUT_DIR"
    log_info "=" * 60
}

main "$@"
