#!/usr/bin/env python3
"""
collect_metrics.py
Parses Perfetto traces and power logs to extract:
- p50/p99 latency
- Energy per inference (joules)
- Audio underruns
- Frame drops
"""

import argparse
import csv
import json
import logging
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
import pandas as pd

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class MetricsCollector:
    """Aggregates performance and power metrics from traces"""
    
    def __init__(self, input_dir: str, output_dir: str):
        self.input_dir = Path(input_dir)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        self.metrics = {
            'latency': {},
            'power': {},
            'audio': {},
            'system': {}
        }
    
    def parse_perfetto_trace(self, trace_path: Path) -> Dict:
        """Parse Perfetto trace using trace processor"""
        logger.info(f"Parsing Perfetto trace: {trace_path}")
        
        try:
            # Try using Perfetto Python API
            from perfetto.trace_processor import TraceProcessor
            
            tp = TraceProcessor(trace=str(trace_path))
            
            # Query inference latencies
            latency_query = """
            SELECT
                ts,
                dur / 1e6 as latency_ms,
                name
            FROM slice
            WHERE name LIKE '%inference%' OR name LIKE '%Inference%'
            ORDER BY ts
            """
            
            latencies = []
            for row in tp.query(latency_query):
                latencies.append({
                    'timestamp': row.ts,
                    'latency_ms': row.latency_ms,
                    'name': row.name
                })
            
            logger.info(f"Found {len(latencies)} inference events")
            
            # Calculate statistics
            if latencies:
                latency_values = [l['latency_ms'] for l in latencies]
                self.metrics['latency'] = {
                    'mean_ms': np.mean(latency_values),
                    'median_ms': np.median(latency_values),
                    'p50_ms': np.percentile(latency_values, 50),
                    'p95_ms': np.percentile(latency_values, 95),
                    'p99_ms': np.percentile(latency_values, 99),
                    'min_ms': np.min(latency_values),
                    'max_ms': np.max(latency_values),
                    'count': len(latency_values)
                }
            
            return self.metrics['latency']
            
        except ImportError:
            logger.warning("Perfetto trace_processor not available, using fallback parser")
            return self._fallback_parse_trace(trace_path)
        except Exception as e:
            logger.error(f"Perfetto parsing failed: {e}")
            return {}
    
    def _fallback_parse_trace(self, trace_path: Path) -> Dict:
        """Fallback: Parse trace as JSON"""
        logger.info("Using JSON fallback parser...")
        
        try:
            with open(trace_path, 'r') as f:
                trace_data = json.load(f)
            
            latencies = []
            
            # Extract slices from Chrome trace format
            if 'traceEvents' in trace_data:
                for event in trace_data['traceEvents']:
                    if event.get('ph') == 'X':  # Complete event
                        name = event.get('name', '')
                        if 'inference' in name.lower():
                            dur_us = event.get('dur', 0)
                            latencies.append(dur_us / 1000.0)  # Convert to ms
            
            if latencies:
                self.metrics['latency'] = {
                    'mean_ms': np.mean(latencies),
                    'p50_ms': np.percentile(latencies, 50),
                    'p99_ms': np.percentile(latencies, 99),
                    'count': len(latencies)
                }
                
                logger.info(f"Parsed {len(latencies)} latency samples")
            
            return self.metrics['latency']
            
        except Exception as e:
            logger.error(f"Fallback parsing failed: {e}")
            return {}
    
    def parse_power_csv(self, power_csv: Path) -> Dict:
        """Parse power consumption CSV"""
        logger.info(f"Parsing power CSV: {power_csv}")
        
        try:
            df = pd.read_csv(power_csv)
            
            if 'power_mah' in df.columns:
                total_power_mah = df['power_mah'].sum()
                
                # Calculate energy (assuming 3.7V battery typical)
                voltage_v = 3.7
                energy_wh = (total_power_mah / 1000.0) * voltage_v
                energy_j = energy_wh * 3600
                
                self.metrics['power'] = {
                    'total_energy_j': energy_j,
                    'total_power_mah': total_power_mah,
                    'voltage_v': voltage_v
                }
                
                # Calculate per-inference energy
                if self.metrics['latency'].get('count'):
                    inference_count = self.metrics['latency']['count']
                    self.metrics['power']['energy_per_inference_j'] = energy_j / inference_count
                
                logger.info(f"Total energy: {energy_j:.2f} J")
            
            return self.metrics['power']
            
        except Exception as e:
            logger.error(f"Power CSV parsing failed: {e}")
            return {}
    
    def parse_audio_underruns(self, logcat_file: Optional[Path] = None) -> Dict:
        """Parse audio underruns from logcat"""
        logger.info("Parsing audio underruns...")
        
        if not logcat_file or not logcat_file.exists():
            logger.warning("No logcat file provided, skipping audio analysis")
            return {}
        
        try:
            with open(logcat_file, 'r') as f:
                log_lines = f.readlines()
            
            underruns = 0
            buffer_overflows = 0
            
            for line in log_lines:
                if 'AudioTrack' in line and 'underrun' in line.lower():
                    underruns += 1
                if 'AudioTrack' in line and 'overflow' in line.lower():
                    buffer_overflows += 1
            
            self.metrics['audio'] = {
                'underruns': underruns,
                'buffer_overflows': buffer_overflows
            }
            
            logger.info(f"Audio underruns: {underruns}, overflows: {buffer_overflows}")
            
            return self.metrics['audio']
            
        except Exception as e:
            logger.error(f"Audio parsing failed: {e}")
            return {}
    
    def parse_system_metrics(self, batterystats_file: Optional[Path] = None) -> Dict:
        """Extract system-level metrics from batterystats"""
        logger.info("Parsing system metrics...")
        
        if not batterystats_file or not batterystats_file.exists():
            logger.warning("No batterystats file, skipping system metrics")
            return {}
        
        try:
            with open(batterystats_file, 'r') as f:
                content = f.read()
            
            # Extract CPU time
            cpu_match = re.search(r'Total cpu time:\s*([\d.]+)ms', content)
            if cpu_match:
                self.metrics['system']['total_cpu_time_ms'] = float(cpu_match.group(1))
            
            # Extract screen on time
            screen_match = re.search(r'Screen on:\s*([\d.]+)ms', content)
            if screen_match:
                self.metrics['system']['screen_on_time_ms'] = float(screen_match.group(1))
            
            # Extract wake locks
            wakelock_match = re.search(r'Total wake locks:\s*(\d+)', content)
            if wakelock_match:
                self.metrics['system']['wake_locks'] = int(wakelock_match.group(1))
            
            return self.metrics['system']
            
        except Exception as e:
            logger.error(f"System metrics parsing failed: {e}")
            return {}
    
    def export_metrics(self, output_format: str = 'json'):
        """Export collected metrics"""
        logger.info("Exporting metrics...")
        
        # JSON export
        if output_format in ['json', 'both']:
            json_path = self.output_dir / 'metrics_summary.json'
            with open(json_path, 'w') as f:
                json.dump(self.metrics, f, indent=2)
            logger.info(f"✓ JSON exported: {json_path}")
        
        # CSV export
        if output_format in ['csv', 'both']:
            csv_path = self.output_dir / 'metrics_summary.csv'
            
            rows = []
            for category, metrics in self.metrics.items():
                for key, value in metrics.items():
                    rows.append({
                        'category': category,
                        'metric': key,
                        'value': value
                    })
            
            df = pd.DataFrame(rows)
            df.to_csv(csv_path, index=False)
            logger.info(f"✓ CSV exported: {csv_path}")
        
        # Print summary
        self._print_summary()
    
    def _print_summary(self):
        """Print metrics summary to console"""
        logger.info("=" * 60)
        logger.info("METRICS SUMMARY")
        logger.info("=" * 60)
        
        if self.metrics['latency']:
            logger.info("\nLatency:")
            for key, value in self.metrics['latency'].items():
                logger.info(f"  {key}: {value:.2f}" if isinstance(value, float) else f"  {key}: {value}")
        
        if self.metrics['power']:
            logger.info("\nPower:")
            for key, value in self.metrics['power'].items():
                logger.info(f"  {key}: {value:.2f}" if isinstance(value, float) else f"  {key}: {value}")
        
        if self.metrics['audio']:
            logger.info("\nAudio:")
            for key, value in self.metrics['audio'].items():
                logger.info(f"  {key}: {value}")
        
        if self.metrics['system']:
            logger.info("\nSystem:")
            for key, value in self.metrics['system'].items():
                logger.info(f"  {key}: {value:.2f}" if isinstance(value, float) else f"  {key}: {value}")
        
        logger.info("=" * 60)
    
    def collect_all(
        self,
        perfetto_trace: Optional[Path] = None,
        power_csv: Optional[Path] = None,
        logcat_file: Optional[Path] = None,
        batterystats_file: Optional[Path] = None
    ):
        """Collect all available metrics"""
        logger.info("Collecting metrics from all sources...")
        
        if perfetto_trace and perfetto_trace.exists():
            self.parse_perfetto_trace(perfetto_trace)
        
        if power_csv and power_csv.exists():
            self.parse_power_csv(power_csv)
        
        if logcat_file and logcat_file.exists():
            self.parse_audio_underruns(logcat_file)
        
        if batterystats_file and batterystats_file.exists():
            self.parse_system_metrics(batterystats_file)
        
        self.export_metrics(output_format='both')


def main():
    parser = argparse.ArgumentParser(
        description="Collect and aggregate performance metrics"
    )
    parser.add_argument(
        "--input-dir",
        required=True,
        help="Directory containing metric files"
    )
    parser.add_argument(
        "--output-dir",
        default="./metrics/aggregated",
        help="Output directory for processed metrics"
    )
    parser.add_argument(
        "--perfetto-trace",
        help="Path to Perfetto trace file"
    )
    parser.add_argument(
        "--power-csv",
        help="Path to power consumption CSV"
    )
    parser.add_argument(
        "--logcat",
        help="Path to logcat file for audio analysis"
    )
    parser.add_argument(
        "--batterystats",
        help="Path to batterystats dump"
    )
    
    args = parser.parse_args()
    
    collector = MetricsCollector(args.input_dir, args.output_dir)
    
    # Auto-discover files if not specified
    input_dir = Path(args.input_dir)
    
    perfetto_trace = args.perfetto_trace or next(input_dir.glob("*.perfetto-trace"), None)
    power_csv = args.power_csv or next(input_dir.glob("*power*.csv"), None)
    logcat_file = args.logcat or next(input_dir.glob("*logcat*.txt"), None)
    batterystats_file = args.batterystats or next(input_dir.glob("*batterystats*.txt"), None)
    
    collector.collect_all(
        perfetto_trace=Path(perfetto_trace) if perfetto_trace else None,
        power_csv=Path(power_csv) if power_csv else None,
        logcat_file=Path(logcat_file) if logcat_file else None,
        batterystats_file=Path(batterystats_file) if batterystats_file else None
    )


if __name__ == "__main__":
    main()
