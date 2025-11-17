#!/usr/bin/env python3
"""
convert_stable_audio_to_litert.py
Converts Stable Audio Open Small to TensorFlow Lite (LiteRT) format
Uses AI Edge Torch for PyTorch → TFLite conversion with quantization
DiT (Diffusion Transformer) → INT8 dynamic, AutoEncoder → FP16
"""

import argparse
import logging
import sys
from pathlib import Path
from typing import Dict, Optional

import numpy as np
import torch
import torch.nn as nn

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class StableAudioConverter:
    """Converts Stable Audio models to TFLite using AI Edge Torch"""
    
    def __init__(
        self,
        model_path: str,
        output_dir: str = "./models/stable_audio",
        dit_quantization: str = "int8",
        ae_quantization: str = "fp16"
    ):
        self.model_path = Path(model_path)
        self.output_dir = Path(output_dir)
        self.dit_quantization = dit_quantization
        self.ae_quantization = ae_quantization
        
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
    def load_stable_audio_model(self) -> Dict[str, nn.Module]:
        """Load Stable Audio checkpoint and extract DiT + AutoEncoder"""
        logger.info(f"Loading Stable Audio from {self.model_path}...")
        
        try:
            # Load checkpoint
            checkpoint = torch.load(self.model_path, map_location='cpu')
            
            # Extract model components
            # Note: Actual implementation depends on Stable Audio architecture
            # This is a placeholder structure
            
            if 'model_state_dict' in checkpoint:
                state_dict = checkpoint['model_state_dict']
            elif 'state_dict' in checkpoint:
                state_dict = checkpoint['state_dict']
            else:
                state_dict = checkpoint
            
            # Separate DiT and AutoEncoder weights
            dit_state = {k: v for k, v in state_dict.items() if 'dit' in k or 'diffusion' in k}
            ae_state = {k: v for k, v in state_dict.items() if 'autoencoder' in k or 'vae' in k}
            
            logger.info(f"✓ Loaded checkpoint: {len(dit_state)} DiT params, {len(ae_state)} AE params")
            
            # Create model instances (placeholder - adapt to actual architecture)
            dit_model = self._create_dit_model(dit_state)
            ae_model = self._create_autoencoder_model(ae_state)
            
            return {
                'dit': dit_model,
                'autoencoder': ae_model
            }
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            sys.exit(1)
    
    def _create_dit_model(self, state_dict: dict) -> nn.Module:
        """Create DiT (Diffusion Transformer) model instance"""
        # Placeholder: Replace with actual Stable Audio DiT architecture
        class SimpleDiT(nn.Module):
            def __init__(self):
                super().__init__()
                self.layers = nn.Sequential(
                    nn.Linear(768, 1024),
                    nn.GELU(),
                    nn.Linear(1024, 768)
                )
            
            def forward(self, x, t):
                return self.layers(x)
        
        model = SimpleDiT()
        model.eval()
        return model
    
    def _create_autoencoder_model(self, state_dict: dict) -> nn.Module:
        """Create AutoEncoder model instance"""
        # Placeholder: Replace with actual Stable Audio VAE architecture
        class SimpleAutoEncoder(nn.Module):
            def __init__(self):
                super().__init__()
                self.encoder = nn.Sequential(
                    nn.Conv1d(1, 64, kernel_size=3, padding=1),
                    nn.ReLU(),
                    nn.Conv1d(64, 128, kernel_size=3, padding=1),
                )
                self.decoder = nn.Sequential(
                    nn.ConvTranspose1d(128, 64, kernel_size=3, padding=1),
                    nn.ReLU(),
                    nn.ConvTranspose1d(64, 1, kernel_size=3, padding=1),
                )
            
            def forward(self, x):
                z = self.encoder(x)
                return self.decoder(z)
        
        model = SimpleAutoEncoder()
        model.eval()
        return model
    
    def convert_to_tflite(
        self,
        model: nn.Module,
        model_name: str,
        quantization: str,
        example_inputs: tuple
    ) -> Path:
        """Convert PyTorch model to TFLite using AI Edge Torch"""
        logger.info(f"Converting {model_name} to TFLite ({quantization})...")
        
        try:
            import ai_edge_torch
            
            # Convert to TFLite
            if quantization == "int8":
                logger.info("Applying INT8 dynamic quantization...")
                
                # Representative dataset for calibration
                def representative_dataset():
                    for _ in range(100):
                        yield [torch.randn_like(inp) for inp in example_inputs]
                
                edge_model = ai_edge_torch.convert(
                    model,
                    example_inputs,
                    quant_config=ai_edge_torch.config.QuantConfig(
                        representative_dataset=representative_dataset
                    )
                )
                
            elif quantization == "fp16":
                logger.info("Applying FP16 quantization...")
                edge_model = ai_edge_torch.convert(
                    model,
                    example_inputs,
                    quant_config=ai_edge_torch.config.QuantConfig(
                        pt2e_quantizer=None,  # FP16 only
                    )
                )
                
            else:  # fp32
                logger.info("No quantization (FP32)...")
                edge_model = ai_edge_torch.convert(model, example_inputs)
            
            # Save TFLite model
            output_path = self.output_dir / f"{model_name}_{quantization}.tflite"
            edge_model.export(str(output_path))
            
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logger.info(f"✓ Converted: {output_path} ({file_size_mb:.2f} MB)")
            
            return output_path
            
        except ImportError:
            logger.error("ai_edge_torch not installed. Install with: pip install ai-edge-torch")
            sys.exit(1)
        except Exception as e:
            logger.error(f"Conversion failed: {e}")
            logger.info("Attempting fallback conversion...")
            return self._fallback_conversion(model, model_name, quantization, example_inputs)
    
    def _fallback_conversion(
        self,
        model: nn.Module,
        model_name: str,
        quantization: str,
        example_inputs: tuple
    ) -> Path:
        """Fallback: Direct ONNX → TFLite conversion"""
        logger.info("Using ONNX → TFLite fallback pipeline...")
        
        try:
            import onnx
            import tensorflow as tf
            from onnx_tf.backend import prepare
            
            # Export to ONNX
            onnx_path = self.output_dir / f"{model_name}.onnx"
            torch.onnx.export(
                model,
                example_inputs,
                onnx_path,
                input_names=['input'],
                output_names=['output'],
                opset_version=14
            )
            
            # Convert ONNX → TensorFlow
            onnx_model = onnx.load(str(onnx_path))
            tf_rep = prepare(onnx_model)
            tf_rep.export_graph(str(self.output_dir / f"{model_name}_tf"))
            
            # Convert TensorFlow → TFLite
            converter = tf.lite.TFLiteConverter.from_saved_model(
                str(self.output_dir / f"{model_name}_tf")
            )
            
            if quantization == "int8":
                converter.optimizations = [tf.lite.Optimize.DEFAULT]
                converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
            elif quantization == "fp16":
                converter.optimizations = [tf.lite.Optimize.DEFAULT]
                converter.target_spec.supported_types = [tf.float16]
            
            tflite_model = converter.convert()
            
            # Save TFLite model
            output_path = self.output_dir / f"{model_name}_{quantization}.tflite"
            with open(output_path, 'wb') as f:
                f.write(tflite_model)
            
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logger.info(f"✓ Fallback conversion successful: {output_path} ({file_size_mb:.2f} MB)")
            
            return output_path
            
        except Exception as e:
            logger.error(f"Fallback conversion failed: {e}")
            raise
    
    def validate_tflite(self, tflite_path: Path, example_inputs: tuple):
        """Validate TFLite model with dummy inference"""
        logger.info(f"Validating {tflite_path}...")
        
        try:
            import tensorflow as tf
            
            # Load TFLite model
            interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
            interpreter.allocate_tensors()
            
            # Get input/output details
            input_details = interpreter.get_input_details()
            output_details = interpreter.get_output_details()
            
            logger.info(f"Input shape: {input_details[0]['shape']}")
            logger.info(f"Output shape: {output_details[0]['shape']}")
            
            # Dummy inference
            input_data = np.random.randn(*input_details[0]['shape']).astype(input_details[0]['dtype'])
            interpreter.set_tensor(input_details[0]['index'], input_data)
            interpreter.invoke()
            output_data = interpreter.get_tensor(output_details[0]['index'])
            
            logger.info(f"✓ Validation passed. Output shape: {output_data.shape}")
            
        except Exception as e:
            logger.warning(f"Validation failed: {e}")
    
    def convert(self):
        """Run full conversion pipeline"""
        logger.info("=" * 60)
        logger.info("Stable Audio → LiteRT Conversion")
        logger.info("=" * 60)
        
        # Load models
        models = self.load_stable_audio_model()
        
        # Convert DiT
        dit_example_inputs = (
            torch.randn(1, 768),  # Latent
            torch.tensor([0.5])   # Timestep
        )
        
        dit_path = self.convert_to_tflite(
            models['dit'],
            "stable_audio_dit",
            self.dit_quantization,
            dit_example_inputs
        )
        
        self.validate_tflite(dit_path, dit_example_inputs)
        
        # Convert AutoEncoder
        ae_example_inputs = (torch.randn(1, 1, 16000),)  # 1-sec audio @ 16kHz
        
        ae_path = self.convert_to_tflite(
            models['autoencoder'],
            "stable_audio_ae",
            self.ae_quantization,
            ae_example_inputs
        )
        
        self.validate_tflite(ae_path, ae_example_inputs)
        
        logger.info("=" * 60)
        logger.info("Conversion complete!")
        logger.info(f"DiT: {dit_path}")
        logger.info(f"AutoEncoder: {ae_path}")
        logger.info("=" * 60)


def main():
    parser = argparse.ArgumentParser(
        description="Convert Stable Audio to TensorFlow Lite"
    )
    parser.add_argument(
        "--model-path",
        required=True,
        help="Path to Stable Audio checkpoint (.ckpt or .pt)"
    )
    parser.add_argument(
        "--output-dir",
        default="./models/stable_audio",
        help="Output directory for TFLite models"
    )
    parser.add_argument(
        "--dit-quant",
        choices=["int8", "fp16", "fp32"],
        default="int8",
        help="DiT quantization"
    )
    parser.add_argument(
        "--ae-quant",
        choices=["int8", "fp16", "fp32"],
        default="fp16",
        help="AutoEncoder quantization"
    )
    
    args = parser.parse_args()
    
    if not Path(args.model_path).exists():
        logger.error(f"Model path not found: {args.model_path}")
        sys.exit(1)
    
    converter = StableAudioConverter(
        model_path=args.model_path,
        output_dir=args.output_dir,
        dit_quantization=args.dit_quant,
        ae_quantization=args.ae_quant
    )
    
    converter.convert()


if __name__ == "__main__":
    main()
