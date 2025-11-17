#!/usr/bin/env bash
set -euo pipefail

# convert_yolov8_tiny_onnx.sh
# Downloads YOLOv8-tiny, exports to ONNX with optimized opset for mobile inference
# Applies graph optimization and validation for ONNX Runtime Mobile + XNNPACK EP

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/models/yolo"

MODEL_NAME="yolov8n.pt"  # YOLOv8 nano (tiny variant)
ONNX_OUTPUT="yolov8-tiny.onnx"
ONNX_OPTIMIZED="yolov8-tiny-opt.onnx"
OPSET_VERSION=14
IMG_SIZE=640

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
    
    if ! command -v python3 &>/dev/null; then
        log_error "Python 3 not found. Install python3."
    fi
    
    # Check if ultralytics is installed
    if ! python3 -c "import ultralytics" 2>/dev/null; then
        log_warn "Installing ultralytics (YOLOv8)..."
        pip3 install ultralytics onnx onnxruntime onnx-simplifier
    fi
    
    if ! python3 -c "import onnx" 2>/dev/null; then
        pip3 install onnx onnxruntime onnx-simplifier
    fi
    
    log_info "✓ Prerequisites satisfied"
}

# Download YOLOv8 model
download_model() {
    log_info "Downloading YOLOv8-nano model..."
    
    mkdir -p "$OUTPUT_DIR"
    
    # Download from Ultralytics (automatic via CLI)
    python3 -c "
from ultralytics import YOLO
import os

model = YOLO('yolov8n.pt')  # Auto-downloads if not present
print(f'Model downloaded: {model.ckpt_path}')
" || log_error "Model download failed"
    
    log_info "✓ Model ready"
}

# Export to ONNX
export_to_onnx() {
    log_info "Exporting YOLOv8-nano to ONNX (opset $OPSET_VERSION)..."
    
    python3 << EOF
from ultralytics import YOLO
import onnx
from onnxsim import simplify

# Load model
model = YOLO('yolov8n.pt')

# Export to ONNX
output_path = model.export(
    format='onnx',
    imgsz=$IMG_SIZE,
    opset=$OPSET_VERSION,
    simplify=False,  # We'll use onnx-simplifier separately
    dynamic=False,    # Static shapes for mobile
)

print(f"Exported to: {output_path}")
EOF
    
    # Move to output directory
    mv yolov8n.onnx "$OUTPUT_DIR/$ONNX_OUTPUT" 2>/dev/null || true
    
    log_info "✓ ONNX export complete: $OUTPUT_DIR/$ONNX_OUTPUT"
}

# Optimize ONNX graph
optimize_onnx() {
    log_info "Optimizing ONNX graph for mobile inference..."
    
    python3 << EOF
import onnx
from onnxsim import simplify
import os

input_path = "$OUTPUT_DIR/$ONNX_OUTPUT"
output_path = "$OUTPUT_DIR/$ONNX_OPTIMIZED"

# Load model
model = onnx.load(input_path)

# Simplify graph (fold constants, remove unused nodes)
model_simplified, check = simplify(
    model,
    check_n=3,
    perform_optimization=True,
    skip_fuse_bn=False,
)

if check:
    print("✓ Graph optimization successful")
    onnx.save(model_simplified, output_path)
    
    # Stats
    orig_size = os.path.getsize(input_path) / (1024 * 1024)
    opt_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Original: {orig_size:.2f} MB → Optimized: {opt_size:.2f} MB")
else:
    print("✗ Optimization validation failed, keeping original")
    import shutil
    shutil.copy(input_path, output_path)
EOF
    
    log_info "✓ Optimization complete: $OUTPUT_DIR/$ONNX_OPTIMIZED"
}

# Validate ONNX model
validate_onnx() {
    log_info "Validating ONNX model..."
    
    python3 << EOF
import onnx
import onnxruntime as ort
import numpy as np

model_path = "$OUTPUT_DIR/$ONNX_OPTIMIZED"

# Check ONNX validity
model = onnx.load(model_path)
onnx.checker.check_model(model)
print("✓ ONNX model is valid")

# Test inference with ONNX Runtime
session = ort.InferenceSession(
    model_path,
    providers=['CPUExecutionProvider']
)

# Get input shape
input_name = session.get_inputs()[0].name
input_shape = session.get_inputs()[0].shape
print(f"Input: {input_name}, Shape: {input_shape}")

# Dummy inference
dummy_input = np.random.randn(*input_shape).astype(np.float32)
outputs = session.run(None, {input_name: dummy_input})
print(f"✓ Inference test passed, output shapes: {[o.shape for o in outputs]}")
EOF
    
    log_info "✓ Validation complete"
}

# Generate model metadata
generate_metadata() {
    log_info "Generating model metadata..."
    
    cat > "$OUTPUT_DIR/README.md" <<EOF
# YOLOv8-tiny ONNX Model

## Model Details
- **Architecture**: YOLOv8 Nano (smallest variant)
- **Input Shape**: 1×3×${IMG_SIZE}×${IMG_SIZE} (NCHW)
- **Output**: Detection boxes, scores, classes
- **Opset Version**: $OPSET_VERSION
- **Optimizations**: Graph simplification, constant folding

## Inference
\`\`\`python
import onnxruntime as ort
import numpy as np
from PIL import Image

# Load model
session = ort.InferenceSession(
    'yolov8-tiny-opt.onnx',
    providers=['CPUExecutionProvider']
)

# Preprocess image
img = Image.open('input.jpg').resize(($IMG_SIZE, $IMG_SIZE))
input_data = np.array(img).transpose(2, 0, 1)[np.newaxis, :] / 255.0

# Run inference
outputs = session.run(None, {'images': input_data.astype(np.float32)})
\`\`\`

## Mobile Deployment
- **ONNX Runtime Mobile**: Use XNNPACK execution provider for ARM NEON/I8MM
- **Quantization**: INT8 quantization recommended for production (see convert script)
- **License**: AGPL-3.0 (Ultralytics YOLOv8)

Generated: $(date)
EOF
    
    log_info "✓ Metadata saved: $OUTPUT_DIR/README.md"
}

# Main execution
main() {
    log_info "=" * 60
    log_info "YOLOv8-tiny → ONNX Conversion"
    log_info "=" * 60
    
    check_prerequisites
    download_model
    export_to_onnx
    optimize_onnx
    validate_onnx
    generate_metadata
    
    log_info "=" * 60
    log_info "Conversion complete!"
    log_info "Model: $OUTPUT_DIR/$ONNX_OPTIMIZED"
    log_info "=" * 60
}

main "$@"
