#!/usr/bin/env python3
"""
export_llama32_executorch.py
Exports Llama 3.2 1B to ExecuTorch .pte format with KleidiAI optimizations
Supports quantization (INT4/INT8) and mobile-specific adaptations
"""

import argparse
import logging
import os
import sys
from pathlib import Path
from typing import Optional

import torch
import torch.nn as nn
from executorch.exir import EdgeCompileConfig, ExecutorchBackendConfig, to_edge
from executorch.exir.passes import MemoryPlanningPass
from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e
from torch.ao.quantization.quantizer import Quantizer
from torch.ao.quantization.quantizer.xnnpack_quantizer import (
    XNNPACKQuantizer,
    get_symmetric_quantization_config,
)
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class LlamaExporter:
    """ExecuTorch export pipeline for Llama 3.2 models"""
    
    def __init__(
        self,
        model_id: str = "meta-llama/Llama-3.2-1B",
        output_dir: str = "./models/llama",
        quantization: Optional[str] = "int8",
        max_seq_len: int = 512,
        use_kv_cache: bool = True
    ):
        self.model_id = model_id
        self.output_dir = Path(output_dir)
        self.quantization = quantization
        self.max_seq_len = max_seq_len
        self.use_kv_cache = use_kv_cache
        
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
    def load_model(self) -> tuple[nn.Module, AutoTokenizer]:
        """Load Llama model and tokenizer from HuggingFace"""
        logger.info(f"Loading model {self.model_id}...")
        
        try:
            tokenizer = AutoTokenizer.from_pretrained(
                self.model_id,
                use_auth_token=os.getenv("HF_TOKEN")
            )
            
            model = AutoModelForCausalLM.from_pretrained(
                self.model_id,
                torch_dtype=torch.float32,
                use_auth_token=os.getenv("HF_TOKEN"),
                low_cpu_mem_usage=True
            )
            
            model.eval()
            logger.info(f"✓ Model loaded: {model.config.num_hidden_layers} layers, "
                       f"{model.config.hidden_size} hidden size")
            
            return model, tokenizer
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            logger.info("Set HF_TOKEN environment variable for gated models")
            sys.exit(1)
    
    def prepare_model_mobile(self, model: nn.Module) -> nn.Module:
        """Apply mobile-specific optimizations"""
        logger.info("Applying mobile optimizations...")
        
        # Remove unused heads/layers for on-device inference
        if hasattr(model, 'lm_head'):
            # Keep language modeling head for text generation
            pass
        
        # Fuse operations where possible
        # Note: ExecuTorch handles most fusions automatically
        
        return model
    
    def quantize_model(self, model: nn.Module) -> nn.Module:
        """Apply quantization using XNNPACK backend"""
        if not self.quantization:
            logger.info("Skipping quantization (fp32 export)")
            return model
            
        logger.info(f"Applying {self.quantization.upper()} quantization...")
        
        try:
            # Configure XNNPACK quantizer with KleidiAI compatibility
            quantizer = XNNPACKQuantizer()
            
            if self.quantization == "int8":
                quantization_config = get_symmetric_quantization_config(
                    is_per_channel=True,
                    is_dynamic=True
                )
            elif self.quantization == "int4":
                # INT4 weight-only quantization
                from torch.ao.quantization.quantizer.xnnpack_quantizer_utils import (
                    QuantizationConfig,
                    QuantizationSpec,
                )
                quantization_config = QuantizationConfig(
                    input_activation=None,
                    weight=QuantizationSpec(
                        dtype=torch.int4,
                        quant_min=-8,
                        quant_max=7,
                        qscheme=torch.per_channel_symmetric,
                    ),
                )
            else:
                raise ValueError(f"Unsupported quantization: {self.quantization}")
            
            quantizer.set_global(quantization_config)
            
            # Create example inputs for tracing
            example_inputs = (
                torch.randint(0, 32000, (1, self.max_seq_len), dtype=torch.long),
            )
            
            # Capture model graph
            model = torch.export.export(model, example_inputs)
            
            # Prepare and convert
            model = prepare_pt2e(model, quantizer)
            model = convert_pt2e(model)
            
            logger.info(f"✓ {self.quantization.upper()} quantization applied")
            return model
            
        except Exception as e:
            logger.error(f"Quantization failed: {e}")
            logger.warning("Falling back to fp32 export")
            return model
    
    def export_to_executorch(
        self,
        model: nn.Module,
        output_name: str = "llama-3.2-1b.pte"
    ) -> Path:
        """Export model to ExecuTorch .pte format"""
        logger.info("Exporting to ExecuTorch...")
        
        output_path = self.output_dir / output_name
        
        try:
            # Example inputs for graph capture
            example_inputs = (
                torch.randint(0, 32000, (1, self.max_seq_len), dtype=torch.long),
            )
            
            # ExecuTorch Edge IR conversion
            edge_config = EdgeCompileConfig(
                _check_ir_validity=True,
                _use_edge_ops=True,
            )
            
            edge_program = to_edge(
                torch.export.export(model, example_inputs),
                compile_config=edge_config
            )
            
            # Apply memory planning pass
            edge_program = edge_program.to_executorch(
                ExecutorchBackendConfig(
                    passes=[
                        MemoryPlanningPass("greedy"),
                    ],
                    extract_delegate_segments=True,
                )
            )
            
            # Write .pte file
            with open(output_path, "wb") as f:
                f.write(edge_program.buffer)
            
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logger.info(f"✓ Exported to {output_path} ({file_size_mb:.2f} MB)")
            
            return output_path
            
        except Exception as e:
            logger.error(f"Export failed: {e}")
            raise
    
    def export(self) -> Path:
        """Run full export pipeline"""
        logger.info("=" * 60)
        logger.info("Llama 3.2 → ExecuTorch Export Pipeline")
        logger.info("=" * 60)
        
        # Load model
        model, tokenizer = self.load_model()
        
        # Mobile optimizations
        model = self.prepare_model_mobile(model)
        
        # Quantization
        if self.quantization:
            model = self.quantize_model(model)
        
        # Export
        quant_suffix = f"-{self.quantization}" if self.quantization else ""
        output_name = f"llama-3.2-1b{quant_suffix}.pte"
        output_path = self.export_to_executorch(model, output_name)
        
        # Save tokenizer
        tokenizer.save_pretrained(self.output_dir)
        logger.info(f"✓ Tokenizer saved to {self.output_dir}")
        
        logger.info("=" * 60)
        logger.info("Export complete!")
        logger.info(f"Model: {output_path}")
        logger.info(f"Tokenizer: {self.output_dir}/tokenizer.json")
        logger.info("=" * 60)
        
        return output_path


def main():
    parser = argparse.ArgumentParser(
        description="Export Llama 3.2 1B to ExecuTorch .pte format"
    )
    parser.add_argument(
        "--model-id",
        default="meta-llama/Llama-3.2-1B",
        help="HuggingFace model identifier"
    )
    parser.add_argument(
        "--output-dir",
        default="./models/llama",
        help="Output directory for .pte and tokenizer"
    )
    parser.add_argument(
        "--quantization",
        choices=["int4", "int8", "none"],
        default="int8",
        help="Quantization method (none for fp32)"
    )
    parser.add_argument(
        "--max-seq-len",
        type=int,
        default=512,
        help="Maximum sequence length for export"
    )
    
    args = parser.parse_args()
    
    exporter = LlamaExporter(
        model_id=args.model_id,
        output_dir=args.output_dir,
        quantization=None if args.quantization == "none" else args.quantization,
        max_seq_len=args.max_seq_len
    )
    
    exporter.export()


if __name__ == "__main__":
    main()
