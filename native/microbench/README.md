# Cortex-N Microbenchmarks

Performance benchmarks for ARM NEON, I8MM, and SME2 optimizations.

## Overview

This directory contains microbenchmarks comparing matrix multiplication (GEMM) performance across different ARM SIMD instruction sets:

- **Scalar**: Baseline C++ implementation
- **NEON**: ARMv8-A 128-bit SIMD (4×float32 lanes)
- **I8MM**: ARMv8.6-A INT8 matrix multiply instructions
- **SME2**: ARMv9-A Scalable Matrix Extension 2

## Building

### Prerequisites

- Android NDK r27 or later
- CMake 3.22+
- Ninja build system

### Quick Start

