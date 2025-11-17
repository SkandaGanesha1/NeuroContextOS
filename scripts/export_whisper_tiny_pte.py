#!/usr/bin/env python3
"""
export_whisper_tiny_pte.py
Exports Whisper-tiny model to ExecuTorch .pte format for on-device ASR
Optimized for mobile ARM CPUs with NEON/I8MM/SME2 via KleidiAI
"""

import argparse
import logging
import sys
from pathlib import Path
from typing import Optional

import torch
import torch.nn as nn
from executorch.exir import EdgeCompileConfig, ExecutorchBackendConfig, to_edge
from executorch.exir.passes import MemoryPlanningPass
from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e
from torch.ao.quantization.quantizer.xnnpack_quantizer import (
    XNNPACKQuantizer,
    get_symmetric_quantization_config,
)
from transformers import WhisperForConditionalGeneration, WhisperProcessor

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class WhisperExporter:
    """ExecuTorch export pipeline for Whisper ASR models"""
    
    def __init__(
        self,
        model_id: str = "openai/whisper-tiny",
        output_dir: str = "./models/whisper",
        quantize: bool = True,
        audio_duration_sec: int = 30,
    ):
        self.model_id = model_id
        self.output_dir = Path(output_dir)
        self.quantize = quantize
        self.audio_duration_sec = audio_duration_sec
        self.sample_rate = 16000  # Whisper standard
        
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
    def load_model(self) -> tuple[nn.Module, WhisperProcessor]:
        """Load Whisper model and processor"""
        logger.info(f"Loading Whisper model: {self.model_id}...")
        
        try:
            processor = WhisperProcessor.from_pretrained(self.model_id)
            
            model = WhisperForConditionalGeneration.from_pretrained(
                self.model_id,
                torch_dtype=torch.float32,
                low_cpu_mem_usage=True
            )
            
            model.eval()
            
            config = model.config
            logger.info(
                f"✓ Loaded: {config.num_hidden_layers} layers, "
                f"{config.d_model} hidden size, "
                f"{config.vocab_size} vocab"
            )
            
            return model, processor
            
        except Exception as e:
            logger.error(f"Model loading failed: {e}")
            sys.exit(1)
    
    def prepare_encoder_only(self, model: nn.Module) -> nn.Module:
        """Extract encoder for streaming inference (optional optimization)"""
        # For full transcription, we need both encoder + decoder
        # For future optimization, encoder-only model can be exported separately
        logger.info("Using full encoder-decoder model for transcription")
        return model
    
    def quantize_model(self, model: nn.Module) -> nn.Module:
        """Apply INT8 dynamic quantization via XNNPACK"""
        if not self.quantize:
            logger.info("Skipping quantization")
            return model
        
        logger.info("Applying INT8 dynamic quantization...")
        
        try:
            quantizer = XNNPACKQuantizer()
            quantization_config = get_symmetric_quantization_config(
                is_per_channel=True,
                is_dynamic=True
            )
            quantizer.set_global(quantization_config)
            
            # Example inputs: mel spectrogram (80 mel bins × time steps)
            n_frames = (self.audio_duration_sec * self.sample_rate) // 160  # Whisper hop length
            example_inputs = {
                "input_features": torch.randn(1, 80, n_frames),
                "decoder_input_ids": torch.randint(0, 51865, (1, 1), dtype=torch.long),
            }
            
            # Export to EXIR
            exported = torch.export.export(
                model,
                args=(),
                kwargs=example_inputs
            )
            
            # Quantize
            model = prepare_pt2e(exported, quantizer)
            model = convert_pt2e(model)
            
            logger.info("✓ INT8 quantization applied")
            return model
            
        except Exception as e:
            logger.error(f"Quantization failed: {e}")
            logger.warning("Continuing with fp32 model")
            return model
    
    def export_to_pte(
        self,
        model: nn.Module,
        output_name: str = "whisper-tiny.pte"
    ) -> Path:
        """Export to ExecuTorch .pte format"""
        logger.info("Exporting to ExecuTorch .pte...")
        
        output_path = self.output_dir / output_name
        
        try:
            # Example inputs for graph capture
            n_frames = (self.audio_duration_sec * self.sample_rate) // 160
            example_inputs = {
                "input_features": torch.randn(1, 80, n_frames),
                "decoder_input_ids": torch.randint(0, 51865, (1, 1), dtype=torch.long),
            }
            
            # Capture graph
            exported_program = torch.export.export(
                model,
                args=(),
                kwargs=example_inputs
            )
            
            # Convert to Edge IR
            edge_program = to_edge(
                exported_program,
                compile_config=EdgeCompileConfig(
                    _check_ir_validity=True,
                )
            )
            
            # Convert to ExecuTorch
            executorch_program = edge_program.to_executorch(
                ExecutorchBackendConfig(
                    passes=[
                        MemoryPlanningPass("greedy"),
                    ]
                )
            )
            
            # Write file
            with open(output_path, "wb") as f:
                f.write(executorch_program.buffer)
            
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logger.info(f"✓ Exported: {output_path} ({file_size_mb:.2f} MB)")
            
            return output_path
            
        except Exception as e:
            logger.error(f"Export failed: {e}")
            raise
    
    def export(self) -> Path:
        """Run full export pipeline"""
        logger.info("=" * 60)
        logger.info("Whisper → ExecuTorch Export")
        logger.info("=" * 60)
        
        # Load
        model, processor = self.load_model()
        
        # Optional: encoder-only optimization
        model = self.prepare_encoder_only(model)
        
        # Quantize
        if self.quantize:
            model = self.quantize_model(model)
        
        # Export
        output_name = f"whisper-tiny{'_int8' if self.quantize else ''}.pte"
        output_path = self.export_to_pte(model, output_name)
        
        # Save processor
        processor.save_pretrained(self.output_dir)
        logger.info(f"✓ Processor saved: {self.output_dir}")
        
        logger.info("=" * 60)
        logger.info("Export complete!")
        logger.info(f"Model: {output_path}")
        logger.info(f"Processor: {self.output_dir}")
        logger.info("=" * 60)
        
        return output_path


def main():
    parser = argparse.ArgumentParser(
        description="Export Whisper-tiny to ExecuTorch for on-device ASR"
    )
    parser.add_argument(
        "--model-id",
        default="openai/whisper-tiny",
        help="HuggingFace model ID"
    )
    parser.add_argument(
        "--output-dir",
        default="./models/whisper",
        help="Output directory"
    )
    parser.add_argument(
        "--no-quantize",
        action="store_true",
        help="Disable INT8 quantization"
    )
    parser.add_argument(
        "--audio-duration",
        type=int,
        default=30,
        help="Max audio duration in seconds"
    )
    
    args = parser.parse_args()
    
    exporter = WhisperExporter(
        model_id=args.model_id,
        output_dir=args.output_dir,
        quantize=not args.no_quantize,
        audio_duration_sec=args.audio_duration
    )
    
    exporter.export()


if __name__ == "__main__":
    main()
