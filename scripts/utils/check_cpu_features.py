#!/usr/bin/env python3
"""
check_cpu_features.py
Detects ARM CPU features (NEON, I8MM, SVE, SME2) on Android devices
Validates instruction set support for optimal kernel selection
"""

import argparse
import logging
import platform
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class ARMFeatureDetector:
    """Detects ARM CPU features and capabilities"""
    
    def __init__(self, adb_serial: Optional[str] = None):
        self.adb_serial = adb_serial
        self.features = {}
        self.cpu_info = {}
        
    def _run_adb(self, command: str) -> str:
        """Execute adb command"""
        cmd = ["adb"]
        if self.adb_serial:
            cmd.extend(["-s", self.adb_serial])
        cmd.extend(["shell", command])
        
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=10
            )
            return result.stdout.strip()
        except subprocess.TimeoutExpired:
            logger.error(f"ADB command timed out: {command}")
            return ""
        except Exception as e:
            logger.error(f"ADB command failed: {e}")
            return ""
    
    def detect_on_android(self) -> Dict:
        """Detect CPU features on Android device via adb"""
        logger.info("Detecting CPU features on Android device...")
        
        # Read /proc/cpuinfo
        cpuinfo = self._run_adb("cat /proc/cpuinfo")
        
        if not cpuinfo:
            logger.error("Failed to read /proc/cpuinfo")
            return {}
        
        self._parse_cpuinfo(cpuinfo)
        
        # Read CPU feature flags
        features_line = self._run_adb("cat /proc/cpuinfo | grep Features")
        if features_line:
            self._parse_features(features_line)
        
        # Check for specific CPU capabilities
        self._check_advanced_features()
        
        # Get CPU frequency info
        self._get_cpu_frequencies()
        
        return {
            'cpu_info': self.cpu_info,
            'features': self.features
        }
    
    def detect_on_linux(self) -> Dict:
        """Detect CPU features on Linux host"""
        logger.info("Detecting CPU features on Linux host...")
        
        try:
            with open('/proc/cpuinfo', 'r') as f:
                cpuinfo = f.read()
            
            self._parse_cpuinfo(cpuinfo)
            
            # Parse features
            for line in cpuinfo.split('\n'):
                if line.startswith('Features'):
                    self._parse_features(line)
                    break
            
            return {
                'cpu_info': self.cpu_info,
                'features': self.features
            }
            
        except FileNotFoundError:
            logger.warning("/proc/cpuinfo not found (non-Linux system?)")
            return {}
    
    def _parse_cpuinfo(self, cpuinfo: str):
        """Parse /proc/cpuinfo output"""
        lines = cpuinfo.split('\n')
        
        # Extract CPU model
        for line in lines:
            if 'CPU implementer' in line:
                self.cpu_info['implementer'] = line.split(':')[1].strip()
            elif 'CPU architecture' in line:
                self.cpu_info['architecture'] = line.split(':')[1].strip()
            elif 'CPU variant' in line:
                self.cpu_info['variant'] = line.split(':')[1].strip()
            elif 'CPU part' in line:
                self.cpu_info['part'] = line.split(':')[1].strip()
            elif 'Hardware' in line:
                self.cpu_info['hardware'] = line.split(':')[1].strip()
            elif 'Processor' in line and 'processor' not in self.cpu_info:
                self.cpu_info['processor'] = line.split(':')[1].strip()
        
        # Identify CPU vendor and model
        self._identify_cpu()
    
    def _parse_features(self, features_line: str):
        """Parse CPU feature flags"""
        # Extract features after ':'
        if ':' in features_line:
            features_str = features_line.split(':', 1)[1].strip()
            features_list = features_str.split()
            
            # Check for key features
            self.features['neon'] = 'asimd' in features_list or 'neon' in features_list
            self.features['fp16'] = 'fphp' in features_list or 'asimdhp' in features_list
            self.features['dotprod'] = 'asimddp' in features_list
            self.features['i8mm'] = 'i8mm' in features_list
            self.features['bf16'] = 'bf16' in features_list
            self.features['sve'] = 'sve' in features_list
            self.features['sve2'] = 'sve2' in features_list
            self.features['sme'] = 'sme' in features_list
            self.features['sme2'] = 'sme2' in features_list
            
            # Additional features
            self.features['aes'] = 'aes' in features_list
            self.features['sha1'] = 'sha1' in features_list
            self.features['sha2'] = 'sha2' in features_list
            self.features['crc32'] = 'crc32' in features_list
            
            self.features['raw_features'] = features_list
    
    def _identify_cpu(self):
        """Identify CPU vendor and model from implementer/part codes"""
        implementer = self.cpu_info.get('implementer', '')
        part = self.cpu_info.get('part', '')
        
        # ARM CPU database
        arm_cpus = {
            '0xd03': 'Cortex-A53',
            '0xd04': 'Cortex-A35',
            '0xd05': 'Cortex-A55',
            '0xd07': 'Cortex-A57',
            '0xd08': 'Cortex-A72',
            '0xd09': 'Cortex-A73',
            '0xd0a': 'Cortex-A75',
            '0xd0b': 'Cortex-A76',
            '0xd0c': 'Neoverse-N1',
            '0xd0d': 'Cortex-A77',
            '0xd0e': 'Cortex-A76AE',
            '0xd40': 'Neoverse-V1',
            '0xd41': 'Cortex-A78',
            '0xd44': 'Cortex-X1',
            '0xd46': 'Cortex-A510',
            '0xd47': 'Cortex-A710',
            '0xd48': 'Cortex-X2',
            '0xd49': 'Neoverse-N2',
            '0xd4a': 'Neoverse-E1',
            '0xd4b': 'Cortex-A78AE',
            '0xd4c': 'Cortex-X1C',
            '0xd4d': 'Cortex-A715',
            '0xd4e': 'Cortex-X3',
            '0xd4f': 'Neoverse-V2',
            '0xd80': 'Cortex-A520',
            '0xd81': 'Cortex-A720',
            '0xd82': 'Cortex-X4',
        }
        
        if implementer == '0x41':  # ARM
            self.cpu_info['vendor'] = 'ARM'
            self.cpu_info['model'] = arm_cpus.get(part, f'Unknown ({part})')
        elif implementer == '0x51':  # Qualcomm
            self.cpu_info['vendor'] = 'Qualcomm'
        elif implementer == '0x53':  # Samsung
            self.cpu_info['vendor'] = 'Samsung'
        else:
            self.cpu_info['vendor'] = f'Unknown ({implementer})'
    
    def _check_advanced_features(self):
        """Check for advanced features via getauxval or direct queries"""
        # Check for SVE vector length
        if self.features.get('sve'):
            sve_vl = self._run_adb("getprop ro.vendor.sve.vl")
            if sve_vl:
                self.features['sve_vector_length'] = int(sve_vl)
        
        # Check for SME support
        if self.features.get('sme') or self.features.get('sme2'):
            # SME typically implies ARMv9-A
            self.cpu_info['architecture_family'] = 'ARMv9-A'
    
    def _get_cpu_frequencies(self):
        """Get CPU frequency information"""
        # Get current frequencies for all CPUs
        frequencies = []
        
        cpu_idx = 0
        while True:
            freq_path = f"/sys/devices/system/cpu/cpu{cpu_idx}/cpufreq/scaling_cur_freq"
            freq = self._run_adb(f"cat {freq_path} 2>/dev/null")
            
            if not freq:
                break
            
            try:
                freq_khz = int(freq)
                frequencies.append(freq_khz // 1000)  # Convert to MHz
            except ValueError:
                pass
            
            cpu_idx += 1
        
        if frequencies:
            self.cpu_info['cpu_frequencies_mhz'] = frequencies
    
    def get_recommended_kernels(self) -> List[str]:
        """Recommend optimal kernel variants based on detected features"""
        kernels = []
        
        if self.features.get('sme2'):
            kernels.append('sme2')
        
        if self.features.get('i8mm'):
            kernels.append('i8mm')
        
        if self.features.get('dotprod'):
            kernels.append('dotprod')
        
        if self.features.get('fp16'):
            kernels.append('fp16')
        
        if self.features.get('neon'):
            kernels.append('neon')
        
        return kernels if kernels else ['generic']
    
    def print_report(self):
        """Print detailed feature report"""
        logger.info("=" * 60)
        logger.info("ARM CPU Feature Report")
        logger.info("=" * 60)
        
        logger.info("\nCPU Information:")
        for key, value in self.cpu_info.items():
            logger.info(f"  {key}: {value}")
        
        logger.info("\nFeature Support:")
        feature_names = {
            'neon': 'NEON (ASIMD)',
            'fp16': 'FP16',
            'dotprod': 'Dot Product',
            'i8mm': 'Int8 Matrix Multiply',
            'bf16': 'BFloat16',
            'sve': 'SVE',
            'sve2': 'SVE2',
            'sme': 'SME',
            'sme2': 'SME2',
            'aes': 'AES',
            'sha1': 'SHA1',
            'sha2': 'SHA2',
            'crc32': 'CRC32',
        }
        
        for key, name in feature_names.items():
            status = "✓" if self.features.get(key) else "✗"
            logger.info(f"  {status} {name}")
        
        logger.info("\nRecommended Kernels:")
        kernels = self.get_recommended_kernels()
        for kernel in kernels:
            logger.info(f"  - {kernel}")
        
        logger.info("=" * 60)


def main():
    parser = argparse.ArgumentParser(
        description="Detect ARM CPU features for optimal kernel selection"
    )
    parser.add_argument(
        "--platform",
        choices=['android', 'linux', 'auto'],
        default='auto',
        help="Target platform"
    )
    parser.add_argument(
        "--device",
        help="ADB device serial (for Android)"
    )
    parser.add_argument(
        "--json",
        action='store_true',
        help="Output as JSON"
    )
    
    args = parser.parse_args()
    
    detector = ARMFeatureDetector(adb_serial=args.device)
    
    # Auto-detect platform
    if args.platform == 'auto':
        if platform.system() == 'Linux' and platform.machine() in ['aarch64', 'arm64']:
            args.platform = 'linux'
        else:
            args.platform = 'android'
    
    # Detect features
    if args.platform == 'android':
        result = detector.detect_on_android()
    else:
        result = detector.detect_on_linux()
    
    # Output
    if args.json:
        import json
        print(json.dumps(result, indent=2))
    else:
        detector.print_report()


if __name__ == "__main__":
    main()
