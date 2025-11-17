#!/usr/bin/env bash
set -euo pipefail

# fetch_models.sh
# Downloads all required models with hash verification
# Supports Git LFS, HuggingFace Hub, and direct URLs

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MODELS_DIR="$PROJECT_ROOT/models"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Model registry with URLs and SHA256 hashes
declare -A MODELS=(
    # Llama 3.2 1B (ExecuTorch)
    ["llama-3.2-1b-int8.pte"]="https://example.com/models/llama-3.2-1b-int8.pte|abc123def456..."
    
    # Whisper-tiny (ExecuTorch)
    ["whisper-tiny-int8.pte"]="https://example.com/models/whisper-tiny-int8.pte|def789ghi012..."
    
    # YOLOv8-nano (ONNX)
    ["yolov8-tiny-opt.onnx"]="https://github.com/ultralytics/assets/releases/download/v8.0.0/yolov8n.onnx|ghi345jkl678..."
    
    # SNN Gesture weights
    ["lif_gesture_weights.bin"]="https://example.com/models/lif_gesture_weights.bin|jkl901mno234..."
    
    # Stable Audio (TFLite)
    ["stable_audio_dit_int8.tflite"]="https://example.com/models/stable_audio_dit_int8.tflite|mno567pqr890..."
    ["stable_audio_ae_fp16.tflite"]="https://example.com/models/stable_audio_ae_fp16.tflite|pqr123stu456..."
)

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    if ! command -v curl &>/dev/null; then
        log_error "curl not found. Install via package manager."
    fi
    
    if ! command -v sha256sum &>/dev/null && ! command -v shasum &>/dev/null; then
        log_error "sha256sum/shasum not found for hash verification"
    fi
    
    # Check Git LFS
    if command -v git &>/dev/null && git lfs version &>/dev/null; then
        log_info "Git LFS available"
    else
        log_warn "Git LFS not available. Large files may not download correctly."
    fi
    
    log_info "✓ Prerequisites OK"
}

# Verify file hash
verify_hash() {
    local file="$1"
    local expected_hash="$2"
    
    if [[ -z "$expected_hash" ]] || [[ "$expected_hash" == "SKIP" ]]; then
        log_warn "Hash verification skipped for $file"
        return 0
    fi
    
    local actual_hash
    if command -v sha256sum &>/dev/null; then
        actual_hash=$(sha256sum "$file" | awk '{print $1}')
    else
        actual_hash=$(shasum -a 256 "$file" | awk '{print $1}')
    fi
    
    if [[ "$actual_hash" == "$expected_hash" ]]; then
        log_info "✓ Hash verified: $(basename "$file")"
        return 0
    else
        log_error "Hash mismatch for $file\nExpected: $expected_hash\nActual: $actual_hash"
        return 1
    fi
}

# Download single model
download_model() {
    local model_name="$1"
    local url_hash="$2"
    
    local url="${url_hash%|*}"
    local expected_hash="${url_hash#*|}"
    
    local output_path="$MODELS_DIR/$model_name"
    
    # Create subdirectories if needed
    mkdir -p "$(dirname "$output_path")"
    
    # Skip if already exists and hash matches
    if [[ -f "$output_path" ]]; then
        if verify_hash "$output_path" "$expected_hash" 2>/dev/null; then
            log_info "✓ Already exists: $model_name"
            return 0
        else
            log_warn "Hash mismatch, re-downloading: $model_name"
            rm -f "$output_path"
        fi
    fi
    
    log_info "Downloading: $model_name"
    log_info "  from: $url"
    
    # Download with progress bar
    if curl -fL --progress-bar -o "$output_path" "$url"; then
        log_info "✓ Downloaded: $model_name"
        
        # Verify hash
        verify_hash "$output_path" "$expected_hash"
    else
        log_error "Download failed: $model_name"
    fi
}

# Download from HuggingFace Hub
download_from_hf() {
    local repo_id="$1"
    local filename="$2"
    local output_path="$3"
    
    log_info "Downloading from HuggingFace: $repo_id/$filename"
    
    if ! command -v huggingface-cli &>/dev/null; then
        log_warn "huggingface-cli not found. Installing..."
        pip3 install --quiet huggingface_hub
    fi
    
    huggingface-cli download \
        "$repo_id" \
        "$filename" \
        --local-dir "$(dirname "$output_path")" \
        --local-dir-use-symlinks False
    
    log_info "✓ Downloaded from HuggingFace: $filename"
}

# Pull Git LFS files
pull_git_lfs() {
    log_info "Pulling Git LFS files..."
    
    cd "$PROJECT_ROOT"
    
    if git lfs version &>/dev/null; then
        git lfs pull
        log_info "✓ Git LFS files synchronized"
    else
        log_warn "Git LFS not available, skipping LFS pull"
    fi
}

# Main download loop
download_all_models() {
    log_info "Downloading models to $MODELS_DIR"
    
    mkdir -p "$MODELS_DIR"
    
    local total=${#MODELS[@]}
    local current=0
    
    for model_name in "${!MODELS[@]}"; do
        ((current++))
        log_info "[$current/$total] Processing: $model_name"
        
        download_model "$model_name" "${MODELS[@]}"
    done
    
    log_info "✓ All models downloaded"
}

# Generate model inventory
generate_inventory() {
    log_info "Generating model inventory..."
    
    local inventory_file="$MODELS_DIR/INVENTORY.md"
    
    cat > "$inventory_file" <<EOF
# Model Inventory

Generated: $(date)

## Downloaded Models

| Model | Size | Hash (SHA256) |
|-------|------|---------------|
EOF
    
    find "$MODELS_DIR" -type f \( -name "*.pte" -o -name "*.onnx" -o -name "*.tflite" -o -name "*.bin" \) | while read -r file; do
        local name=$(basename "$file")
        local size=$(du -h "$file" | awk '{print $1}')
        local hash=$(sha256sum "$file" 2>/dev/null | awk '{print substr($1,1,16)"..."}' || echo "N/A")
        
        echo "| $name | $size | $hash |" >> "$inventory_file"
    done
    
    log_info "✓ Inventory saved: $inventory_file"
}

# Main execution
main() {
    log_info "=" * 60
    log_info "Model Fetcher"
    log_info "=" * 60
    
    check_prerequisites
    pull_git_lfs
    download_all_models
    generate_inventory
    
    log_info "=" * 60
    log_info "✓ Model fetching complete!"
    log_info "Models directory: $MODELS_DIR"
    log_info "=" * 60
}

main "$@"
