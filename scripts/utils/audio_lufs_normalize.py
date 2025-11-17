#!/usr/bin/env python3
"""
audio_lufs_normalize.py
Normalizes audio files to target LUFS (Loudness Units relative to Full Scale)
Ensures consistent loudness across generated audio samples
"""

import argparse
import logging
import sys
from pathlib import Path
from typing import Optional, Tuple

import numpy as np
import soundfile as sf

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class LUFSNormalizer:
    """Audio normalization using LUFS standard"""
    
    def __init__(self, target_lufs: float = -16.0, max_true_peak: float = -1.0):
        self.target_lufs = target_lufs
        self.max_true_peak = max_true_peak
        
        try:
            import pyloudnorm as pyln
            self.meter = pyln.Meter(48000)  # Will be updated per file
            self.pyln = pyln
        except ImportError:
            logger.error("pyloudnorm not installed. Install with: pip install pyloudnorm")
            sys.exit(1)
    
    def load_audio(self, input_path: Path) -> Tuple[np.ndarray, int]:
        """Load audio file"""
        logger.info(f"Loading: {input_path}")
        
        try:
            audio, sr = sf.read(str(input_path), always_2d=False)
            
            # Convert to mono if stereo
            if audio.ndim == 2:
                logger.info("Converting stereo to mono...")
                audio = audio.mean(axis=1)
            
            logger.info(f"Loaded: {len(audio)} samples @ {sr} Hz")
            return audio, sr
            
        except Exception as e:
            logger.error(f"Failed to load audio: {e}")
            raise
    
    def measure_loudness(self, audio: np.ndarray, sr: int) -> float:
        """Measure integrated LUFS"""
        self.meter = self.pyln.Meter(sr)
        loudness = self.meter.integrated_loudness(audio)
        logger.info(f"Measured loudness: {loudness:.2f} LUFS")
        return loudness
    
    def normalize_loudness(
        self,
        audio: np.ndarray,
        sr: int,
        current_lufs: Optional[float] = None
    ) -> np.ndarray:
        """Normalize audio to target LUFS"""
        if current_lufs is None:
            current_lufs = self.measure_loudness(audio, sr)
        
        # Calculate gain
        gain_db = self.target_lufs - current_lufs
        logger.info(f"Applying gain: {gain_db:+.2f} dB")
        
        # Apply gain
        normalized = self.pyln.normalize.loudness(audio, current_lufs, self.target_lufs)
        
        # Check true peak
        true_peak = self._calculate_true_peak(normalized)
        logger.info(f"True peak: {true_peak:.2f} dBFS")
        
        # Apply limiter if needed
        if true_peak > self.max_true_peak:
            logger.warning(f"True peak exceeds {self.max_true_peak} dBFS, applying limiter...")
            normalized = self._apply_limiter(normalized, self.max_true_peak)
        
        return normalized
    
    def _calculate_true_peak(self, audio: np.ndarray) -> float:
        """Calculate true peak in dBFS"""
        # Oversample for true peak detection
        from scipy import signal
        
        # 4x oversampling
        oversampled = signal.resample(audio, len(audio) * 4)
        peak = np.abs(oversampled).max()
        
        if peak == 0:
            return -np.inf
        
        peak_db = 20 * np.log10(peak)
        return peak_db
    
    def _apply_limiter(self, audio: np.ndarray, threshold_db: float) -> np.ndarray:
        """Apply soft limiter to prevent clipping"""
        threshold_linear = 10 ** (threshold_db / 20)
        
        # Soft knee limiter
        limited = np.where(
            np.abs(audio) > threshold_linear,
            np.sign(audio) * (threshold_linear + (np.abs(audio) - threshold_linear) * 0.1),
            audio
        )
        
        return limited
    
    def process_file(
        self,
        input_path: Path,
        output_path: Optional[Path] = None,
        overwrite: bool = False
    ) -> Path:
        """Process single audio file"""
        if output_path is None:
            output_path = input_path.parent / f"{input_path.stem}_normalized{input_path.suffix}"
        
        if output_path.exists() and not overwrite:
            logger.warning(f"Output exists: {output_path}. Use --overwrite to replace.")
            return output_path
        
        # Load audio
        audio, sr = self.load_audio(input_path)
        
        # Normalize
        normalized = self.normalize_loudness(audio, sr)
        
        # Save
        logger.info(f"Saving: {output_path}")
        sf.write(str(output_path), normalized, sr, subtype='PCM_16')
        
        # Verify
        final_lufs = self.measure_loudness(normalized, sr)
        logger.info(f"Final loudness: {final_lufs:.2f} LUFS (target: {self.target_lufs:.2f})")
        
        return output_path
    
    def process_directory(
        self,
        input_dir: Path,
        output_dir: Optional[Path] = None,
        pattern: str = "*.wav"
    ):
        """Process all audio files in directory"""
        if output_dir is None:
            output_dir = input_dir / "normalized"
        
        output_dir.mkdir(parents=True, exist_ok=True)
        
        audio_files = list(input_dir.glob(pattern))
        logger.info(f"Found {len(audio_files)} audio files")
        
        for i, input_path in enumerate(audio_files, 1):
            logger.info(f"[{i}/{len(audio_files)}] Processing: {input_path.name}")
            
            output_path = output_dir / input_path.name
            
            try:
                self.process_file(input_path, output_path, overwrite=True)
            except Exception as e:
                logger.error(f"Failed to process {input_path.name}: {e}")
                continue
        
        logger.info("✓ Batch processing complete")


def main():
    parser = argparse.ArgumentParser(
        description="Normalize audio files to target LUFS"
    )
    parser.add_argument(
        "input",
        type=Path,
        help="Input audio file or directory"
    )
    parser.add_argument(
        "-o", "--output",
        type=Path,
        help="Output file or directory"
    )
    parser.add_argument(
        "--target-lufs",
        type=float,
        default=-16.0,
        help="Target LUFS (default: -16.0)"
    )
    parser.add_argument(
        "--max-peak",
        type=float,
        default=-1.0,
        help="Maximum true peak in dBFS (default: -1.0)"
    )
    parser.add_argument(
        "--pattern",
        default="*.wav",
        help="File pattern for batch processing (default: *.wav)"
    )
    parser.add_argument(
        "--overwrite",
        action='store_true',
        help="Overwrite existing output files"
    )
    
    args = parser.parse_args()
    
    if not args.input.exists():
        logger.error(f"Input not found: {args.input}")
        sys.exit(1)
    
    normalizer = LUFSNormalizer(
        target_lufs=args.target_lufs,
        max_true_peak=args.max_peak
    )
    
    logger.info("=" * 60)
    logger.info("LUFS Audio Normalizer")
    logger.info(f"Target: {args.target_lufs} LUFS, Max Peak: {args.max_peak} dBFS")
    logger.info("=" * 60)
    
    if args.input.is_file():
        # Process single file
        normalizer.process_file(args.input, args.output, args.overwrite)
    else:
        # Process directory
        normalizer.process_directory(args.input, args.output, args.pattern)
    
    logger.info("=" * 60)
    logger.info("✓ Normalization complete!")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
