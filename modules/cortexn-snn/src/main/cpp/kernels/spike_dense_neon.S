/* 
 * Hand-optimized AArch64 assembly for spike dense forward
 * Uses NEON SIMD with fused multiply-add
 */

.text
.align 4
.global spike_dense_neon_asm
.type spike_dense_neon_asm, %function

/*
 * void spike_dense_neon_asm(
 *     const float* spikes,     // x0
 *     const float* weights,    // x1
 *     const float* bias,       // x2
 *     float* output,           // x3
 *     int input_size,          // w4
 *     int output_size          // w5
 * )
 */
spike_dense_neon_asm:
    // Save callee-saved registers
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    
    // Initialize output index
    mov x6, #0
    
output_loop:
    cmp x6, x5
    b.ge output_done
    
    // Load bias
    ldr s0, [x2, x6, lsl #2]
    dup v1.4s, v0.s[0]
    
    // Calculate weight row address: weights + output_idx * input_size
    mul x7, x6, x4
    add x8, x1, x7, lsl #2
    
    // Initialize input index
    mov x9, #0
    
    // Process 4 elements at a time
inner_loop:
    add x10, x9, #4
    cmp x10, x4
    b.gt inner_remainder
    
    // Load 4 spikes
    add x11, x0, x9, lsl #2
    ld1 {v2.4s}, [x11]
    
    // Load 4 weights
    add x12, x8, x9, lsl #2
    ld1 {v3.4s}, [x12]
    
    // Fused multiply-accumulate: v1 += v2 * v3
    fmla v1.4s, v2.4s, v3.4s
    
    mov x9, x10
    b inner_loop
    
inner_remainder:
    // Horizontal sum of v1
    faddp v4.4s, v1.4s, v1.4s
    faddp v4.4s, v4.4s, v4.4s
    
    // Handle remaining elements (scalar)
remainder_loop:
    cmp x9, x4
    b.ge remainder_done
    
    add x11, x0, x9, lsl #2
    ldr s2, [x11]
    
    add x12, x8, x9, lsl #2
    ldr s3, [x12]
    
    fmadd s4, s2, s3, s4
    
    add x9, x9, #1
    b remainder_loop
    
remainder_done:
    // Store result
    add x13, x3, x6, lsl #2
    str s4, [x13]
    
    add x6, x6, #1
    b output_loop
    
output_done:
    // Restore registers and return
    ldp x29, x30, [sp], #16
    ret

.size spike_dense_neon_asm, .-spike_dense_neon_asm
