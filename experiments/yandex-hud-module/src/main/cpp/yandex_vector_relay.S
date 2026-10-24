/* AArch64 exact-build observer relay and synthetic ABI verification. */
.text
.p2align 4
.hidden denza_vector_relay_begin
.global denza_vector_relay_begin
denza_vector_relay_begin:
  sub sp, sp, #0x310
  stp x0, x1, [sp, #0x0]
  stp x2, x3, [sp, #0x10]
  stp x4, x5, [sp, #0x20]
  stp x6, x7, [sp, #0x30]
  stp x8, x9, [sp, #0x40]
  stp x10, x11, [sp, #0x50]
  stp x12, x13, [sp, #0x60]
  stp x14, x15, [sp, #0x70]
  stp x16, x17, [sp, #0x80]
  stp x18, x19, [sp, #0x90]
  stp x20, x21, [sp, #0xa0]
  stp x22, x23, [sp, #0xb0]
  stp x24, x25, [sp, #0xc0]
  stp x26, x27, [sp, #0xd0]
  stp x28, x29, [sp, #0xe0]
  str x30, [sp, #0xf0]
  mrs x9, nzcv
  str x9, [sp, #0xf8]
  mrs x9, fpcr
  str x9, [sp, #0x100]
  mrs x9, fpsr
  str x9, [sp, #0x108]
  stp q0, q1, [sp, #0x110]
  stp q2, q3, [sp, #0x130]
  stp q4, q5, [sp, #0x150]
  stp q6, q7, [sp, #0x170]
  stp q8, q9, [sp, #0x190]
  stp q10, q11, [sp, #0x1b0]
  stp q12, q13, [sp, #0x1d0]
  stp q14, q15, [sp, #0x1f0]
  stp q16, q17, [sp, #0x210]
  stp q18, q19, [sp, #0x230]
  stp q20, q21, [sp, #0x250]
  stp q22, q23, [sp, #0x270]
  stp q24, q25, [sp, #0x290]
  stp q26, q27, [sp, #0x2b0]
  stp q28, q29, [sp, #0x2d0]
  stp q30, q31, [sp, #0x2f0]
  mov x0, sp
  ldr x1, denza_vector_relay_event
  add x2, sp, #0x310
  mrs x3, tpidr_el0
  ldr x16, denza_vector_relay_callback
  blr x16
  ldp q0, q1, [sp, #0x110]
  ldp q2, q3, [sp, #0x130]
  ldp q4, q5, [sp, #0x150]
  ldp q6, q7, [sp, #0x170]
  ldp q8, q9, [sp, #0x190]
  ldp q10, q11, [sp, #0x1b0]
  ldp q12, q13, [sp, #0x1d0]
  ldp q14, q15, [sp, #0x1f0]
  ldp q16, q17, [sp, #0x210]
  ldp q18, q19, [sp, #0x230]
  ldp q20, q21, [sp, #0x250]
  ldp q22, q23, [sp, #0x270]
  ldp q24, q25, [sp, #0x290]
  ldp q26, q27, [sp, #0x2b0]
  ldp q28, q29, [sp, #0x2d0]
  ldp q30, q31, [sp, #0x2f0]
  ldr x9, [sp, #0xf8]
  msr nzcv, x9
  ldr x9, [sp, #0x100]
  msr fpcr, x9
  ldr x9, [sp, #0x108]
  msr fpsr, x9
  ldp x0, x1, [sp, #0x0]
  ldp x2, x3, [sp, #0x10]
  ldp x4, x5, [sp, #0x20]
  ldp x6, x7, [sp, #0x30]
  ldp x8, x9, [sp, #0x40]
  ldp x10, x11, [sp, #0x50]
  ldp x12, x13, [sp, #0x60]
  ldp x14, x15, [sp, #0x70]
  ldp x16, x17, [sp, #0x80]
  ldp x18, x19, [sp, #0x90]
  ldp x20, x21, [sp, #0xa0]
  ldp x22, x23, [sp, #0xb0]
  ldp x24, x25, [sp, #0xc0]
  ldp x26, x27, [sp, #0xd0]
  ldp x28, x29, [sp, #0xe0]
  ldr x30, [sp, #0xf0]
  add sp, sp, #0x310
.hidden denza_vector_relay_original
.global denza_vector_relay_original
denza_vector_relay_original:
  nop /* One verified PC-independent SUB, preindexed STP, or RET. */
.hidden denza_vector_relay_branch
.global denza_vector_relay_branch
denza_vector_relay_branch:
  b denza_vector_relay_branch /* Replaced with direct branch to original entry+4. */
.p2align 3
.hidden denza_vector_relay_callback
.global denza_vector_relay_callback
denza_vector_relay_callback:
  .quad 0
.hidden denza_vector_relay_event
.global denza_vector_relay_event
denza_vector_relay_event:
  .quad 0
.hidden denza_vector_relay_end
.global denza_vector_relay_end
denza_vector_relay_end:
.p2align 4
.hidden denza_vector_flush_cache
.global denza_vector_flush_cache
.type denza_vector_flush_cache, %function
denza_vector_flush_cache:
  mrs x2, ctr_el0
  ubfx x3, x2, #16, #4
  mov x4, #4
  lsl x3, x4, x3
  sub x5, x3, #1
  bic x4, x0, x5
1:
  dc cvau, x4
  add x4, x4, x3
  cmp x4, x1
  b.lo 1b
  dsb ish
  and x3, x2, #0xf
  mov x4, #4
  lsl x3, x4, x3
  sub x5, x3, #1
  bic x4, x0, x5
2:
  ic ivau, x4
  add x4, x4, x3
  cmp x4, x1
  b.lo 2b
  dsb ish
  isb
  ret
.size denza_vector_flush_cache, .-denza_vector_flush_cache
.p2align 4
.hidden denza_vector_probe_invoke
.global denza_vector_probe_invoke
.type denza_vector_probe_invoke, %function
denza_vector_probe_invoke:
  sub sp, sp, #0x110
  stp x19, x20, [sp, #0x0]
  stp x21, x22, [sp, #0x10]
  stp x23, x24, [sp, #0x20]
  stp x25, x26, [sp, #0x30]
  stp x27, x28, [sp, #0x40]
  stp x29, x30, [sp, #0x50]
  stp q8, q9, [sp, #0x60]
  stp q10, q11, [sp, #0x80]
  stp q12, q13, [sp, #0xa0]
  stp q14, q15, [sp, #0xc0]
  mrs x9, nzcv
  str x9, [sp, #0xe0]
  mrs x9, fpcr
  str x9, [sp, #0xe8]
  mrs x9, fpsr
  str x9, [sp, #0xf0]
  str x0, [sp, #0xf8]
  str x1, [sp, #0x100]
  str x18, [sp, #0x108]
  mov x9, sp
  str x9, [x1, #0x318]
  adr x9, .Lprobe_return
  str x9, [x1, #0x320]
  movi v0.16b, #0
  movi v1.16b, #1
  movi v2.16b, #2
  movi v3.16b, #3
  movi v4.16b, #4
  movi v5.16b, #5
  movi v6.16b, #6
  movi v7.16b, #7
  movi v8.16b, #8
  movi v9.16b, #9
  movi v10.16b, #10
  movi v11.16b, #11
  movi v12.16b, #12
  movi v13.16b, #13
  movi v14.16b, #14
  movi v15.16b, #15
  movi v16.16b, #16
  movi v17.16b, #17
  movi v18.16b, #18
  movi v19.16b, #19
  movi v20.16b, #20
  movi v21.16b, #21
  movi v22.16b, #22
  movi v23.16b, #23
  movi v24.16b, #24
  movi v25.16b, #25
  movi v26.16b, #26
  movi v27.16b, #27
  movi v28.16b, #28
  movi v29.16b, #29
  movi v30.16b, #30
  movi v31.16b, #31
  mov x17, #0xa0000000
  msr nzcv, x17
  mov x17, #0x01000000
  msr fpcr, x17
  mov x17, #0x08000000
  msr fpsr, x17
  mov x1, #0x101
  mov x2, #0x102
  mov x3, #0x103
  mov x4, #0x104
  mov x5, #0x105
  mov x6, #0x106
  mov x7, #0x107
  mov x8, #0x108
  mov x9, #0x109
  mov x10, #0x10a
  mov x11, #0x10b
  mov x12, #0x10c
  mov x13, #0x10d
  mov x14, #0x10e
  mov x15, #0x10f
  mov x16, #0x110
  mov x17, #0x111
  mov x18, #0x112
  mov x19, #0x113
  mov x20, #0x114
  mov x21, #0x115
  mov x22, #0x116
  mov x23, #0x117
  mov x24, #0x118
  mov x25, #0x119
  mov x26, #0x11a
  mov x27, #0x11b
  mov x28, #0x11c
  mov x29, #0x11d
  ldr x0, [sp, #0x100]
  ldr x16, [sp, #0xf8]
  adr x30, .Lprobe_return
  br x16
.Lprobe_return:
  ldr x9, [sp, #0xe0]
  msr nzcv, x9
  ldr x9, [sp, #0xe8]
  msr fpcr, x9
  ldr x9, [sp, #0xf0]
  msr fpsr, x9
  ldp q8, q9, [sp, #0x60]
  ldp q10, q11, [sp, #0x80]
  ldp q12, q13, [sp, #0xa0]
  ldp q14, q15, [sp, #0xc0]
  ldp x19, x20, [sp, #0x0]
  ldp x21, x22, [sp, #0x10]
  ldp x23, x24, [sp, #0x20]
  ldp x25, x26, [sp, #0x30]
  ldp x27, x28, [sp, #0x40]
  ldp x29, x30, [sp, #0x50]
  ldr x18, [sp, #0x108]
  add sp, sp, #0x110
  ret
.size denza_vector_probe_invoke, .-denza_vector_probe_invoke
.p2align 4
.hidden denza_vector_probe_target
.global denza_vector_probe_target
.type denza_vector_probe_target, %function
denza_vector_probe_target:
  sub sp, sp, #0x50
  stp x0, x1, [x0, #0x0]
  stp x2, x3, [x0, #0x10]
  stp x4, x5, [x0, #0x20]
  stp x6, x7, [x0, #0x30]
  stp x8, x9, [x0, #0x40]
  stp x10, x11, [x0, #0x50]
  stp x12, x13, [x0, #0x60]
  stp x14, x15, [x0, #0x70]
  stp x16, x17, [x0, #0x80]
  stp x18, x19, [x0, #0x90]
  stp x20, x21, [x0, #0xa0]
  stp x22, x23, [x0, #0xb0]
  stp x24, x25, [x0, #0xc0]
  stp x26, x27, [x0, #0xd0]
  stp x28, x29, [x0, #0xe0]
  str x30, [x0, #0xf0]
  mrs x9, nzcv
  str x9, [x0, #0xf8]
  mrs x9, fpcr
  str x9, [x0, #0x100]
  mrs x9, fpsr
  str x9, [x0, #0x108]
  stp q0, q1, [x0, #0x110]
  stp q2, q3, [x0, #0x130]
  stp q4, q5, [x0, #0x150]
  stp q6, q7, [x0, #0x170]
  stp q8, q9, [x0, #0x190]
  stp q10, q11, [x0, #0x1b0]
  stp q12, q13, [x0, #0x1d0]
  stp q14, q15, [x0, #0x1f0]
  stp q16, q17, [x0, #0x210]
  stp q18, q19, [x0, #0x230]
  stp q20, q21, [x0, #0x250]
  stp q22, q23, [x0, #0x270]
  stp q24, q25, [x0, #0x290]
  stp q26, q27, [x0, #0x2b0]
  stp q28, q29, [x0, #0x2d0]
  stp q30, q31, [x0, #0x2f0]
  add x9, sp, #0x50
  str x9, [x0, #0x310]
  add sp, sp, #0x50
  ret
.size denza_vector_probe_target, .-denza_vector_probe_target
.p2align 4
.hidden denza_vector_probe_wrapper
.global denza_vector_probe_wrapper
.type denza_vector_probe_wrapper, %function
denza_vector_probe_wrapper:
  sub sp, sp, #0x60
  stp x0, x1, [x0, #0x0]
  stp x2, x3, [x0, #0x10]
  stp x4, x5, [x0, #0x20]
  stp x6, x7, [x0, #0x30]
  stp x8, x9, [x0, #0x40]
  stp x10, x11, [x0, #0x50]
  stp x12, x13, [x0, #0x60]
  stp x14, x15, [x0, #0x70]
  stp x16, x17, [x0, #0x80]
  stp x18, x19, [x0, #0x90]
  stp x20, x21, [x0, #0xa0]
  stp x22, x23, [x0, #0xb0]
  stp x24, x25, [x0, #0xc0]
  stp x26, x27, [x0, #0xd0]
  stp x28, x29, [x0, #0xe0]
  str x30, [x0, #0xf0]
  mrs x9, nzcv
  str x9, [x0, #0xf8]
  mrs x9, fpcr
  str x9, [x0, #0x100]
  mrs x9, fpsr
  str x9, [x0, #0x108]
  stp q0, q1, [x0, #0x110]
  stp q2, q3, [x0, #0x130]
  stp q4, q5, [x0, #0x150]
  stp q6, q7, [x0, #0x170]
  stp q8, q9, [x0, #0x190]
  stp q10, q11, [x0, #0x1b0]
  stp q12, q13, [x0, #0x1d0]
  stp q14, q15, [x0, #0x1f0]
  stp q16, q17, [x0, #0x210]
  stp q18, q19, [x0, #0x230]
  stp q20, q21, [x0, #0x250]
  stp q22, q23, [x0, #0x270]
  stp q24, q25, [x0, #0x290]
  stp q26, q27, [x0, #0x2b0]
  stp q28, q29, [x0, #0x2d0]
  stp q30, q31, [x0, #0x2f0]
  add x9, sp, #0x60
  str x9, [x0, #0x310]
  add sp, sp, #0x60
  ret
.size denza_vector_probe_wrapper, .-denza_vector_probe_wrapper
.p2align 4
.hidden denza_vector_probe_layer
.global denza_vector_probe_layer
.type denza_vector_probe_layer, %function
denza_vector_probe_layer:
  stp x29, x30, [sp, #-0x60]!
  stp x0, x1, [x0, #0x0]
  stp x2, x3, [x0, #0x10]
  stp x4, x5, [x0, #0x20]
  stp x6, x7, [x0, #0x30]
  stp x8, x9, [x0, #0x40]
  stp x10, x11, [x0, #0x50]
  stp x12, x13, [x0, #0x60]
  stp x14, x15, [x0, #0x70]
  stp x16, x17, [x0, #0x80]
  stp x18, x19, [x0, #0x90]
  stp x20, x21, [x0, #0xa0]
  stp x22, x23, [x0, #0xb0]
  stp x24, x25, [x0, #0xc0]
  stp x26, x27, [x0, #0xd0]
  stp x28, x29, [x0, #0xe0]
  str x30, [x0, #0xf0]
  mrs x9, nzcv
  str x9, [x0, #0xf8]
  mrs x9, fpcr
  str x9, [x0, #0x100]
  mrs x9, fpsr
  str x9, [x0, #0x108]
  stp q0, q1, [x0, #0x110]
  stp q2, q3, [x0, #0x130]
  stp q4, q5, [x0, #0x150]
  stp q6, q7, [x0, #0x170]
  stp q8, q9, [x0, #0x190]
  stp q10, q11, [x0, #0x1b0]
  stp q12, q13, [x0, #0x1d0]
  stp q14, q15, [x0, #0x1f0]
  stp q16, q17, [x0, #0x210]
  stp q18, q19, [x0, #0x230]
  stp q20, q21, [x0, #0x250]
  stp q22, q23, [x0, #0x270]
  stp q24, q25, [x0, #0x290]
  stp q26, q27, [x0, #0x2b0]
  stp q28, q29, [x0, #0x2d0]
  stp q30, q31, [x0, #0x2f0]
  add x9, sp, #0x60
  str x9, [x0, #0x310]
  add sp, sp, #0x60
  ret
.size denza_vector_probe_layer, .-denza_vector_probe_layer
.p2align 4
.hidden denza_vector_probe_invoke_ret
.global denza_vector_probe_invoke_ret
.type denza_vector_probe_invoke_ret, %function
denza_vector_probe_invoke_ret:
  sub sp, sp, #0x110
  stp x19, x20, [sp, #0x0]
  stp x21, x22, [sp, #0x10]
  stp x23, x24, [sp, #0x20]
  stp x25, x26, [sp, #0x30]
  stp x27, x28, [sp, #0x40]
  stp x29, x30, [sp, #0x50]
  stp q8, q9, [sp, #0x60]
  stp q10, q11, [sp, #0x80]
  stp q12, q13, [sp, #0xa0]
  stp q14, q15, [sp, #0xc0]
  mrs x9, nzcv
  str x9, [sp, #0xe0]
  mrs x9, fpcr
  str x9, [sp, #0xe8]
  mrs x9, fpsr
  str x9, [sp, #0xf0]
  str x0, [sp, #0xf8]
  str x1, [sp, #0x100]
  str x18, [sp, #0x108]
  mov x9, sp
  str x9, [x1, #0x318]
  adr x9, .Lprobe_ret_return
  str x9, [x1, #0x320]
  movi v0.16b, #0
  movi v1.16b, #1
  movi v2.16b, #2
  movi v3.16b, #3
  movi v4.16b, #4
  movi v5.16b, #5
  movi v6.16b, #6
  movi v7.16b, #7
  movi v8.16b, #8
  movi v9.16b, #9
  movi v10.16b, #10
  movi v11.16b, #11
  movi v12.16b, #12
  movi v13.16b, #13
  movi v14.16b, #14
  movi v15.16b, #15
  movi v16.16b, #16
  movi v17.16b, #17
  movi v18.16b, #18
  movi v19.16b, #19
  movi v20.16b, #20
  movi v21.16b, #21
  movi v22.16b, #22
  movi v23.16b, #23
  movi v24.16b, #24
  movi v25.16b, #25
  movi v26.16b, #26
  movi v27.16b, #27
  movi v28.16b, #28
  movi v29.16b, #29
  movi v30.16b, #30
  movi v31.16b, #31
  mov x17, #0xa0000000
  msr nzcv, x17
  mov x17, #0x01000000
  msr fpcr, x17
  mov x17, #0x08000000
  msr fpsr, x17
  mov x1, #0x101
  mov x2, #0x102
  mov x3, #0x103
  mov x4, #0x104
  mov x5, #0x105
  mov x6, #0x106
  mov x7, #0x107
  mov x8, #0x108
  mov x9, #0x109
  mov x10, #0x10a
  mov x11, #0x10b
  mov x12, #0x10c
  mov x13, #0x10d
  mov x14, #0x10e
  mov x15, #0x10f
  mov x16, #0x110
  mov x17, #0x111
  mov x18, #0x112
  mov x19, #0x113
  mov x20, #0x114
  mov x21, #0x115
  mov x22, #0x116
  mov x23, #0x117
  mov x24, #0x118
  mov x25, #0x119
  mov x26, #0x11a
  mov x27, #0x11b
  mov x28, #0x11c
  mov x29, #0x11d
  ldr x0, [sp, #0x100]
  ldr x16, [sp, #0xf8]
  adr x30, .Lprobe_ret_return
  br x16
.Lprobe_ret_return:
  stp x0, x1, [x0, #0x0]
  stp x2, x3, [x0, #0x10]
  stp x4, x5, [x0, #0x20]
  stp x6, x7, [x0, #0x30]
  stp x8, x9, [x0, #0x40]
  stp x10, x11, [x0, #0x50]
  stp x12, x13, [x0, #0x60]
  stp x14, x15, [x0, #0x70]
  stp x16, x17, [x0, #0x80]
  stp x18, x19, [x0, #0x90]
  stp x20, x21, [x0, #0xa0]
  stp x22, x23, [x0, #0xb0]
  stp x24, x25, [x0, #0xc0]
  stp x26, x27, [x0, #0xd0]
  stp x28, x29, [x0, #0xe0]
  str x30, [x0, #0xf0]
  mrs x9, nzcv
  str x9, [x0, #0xf8]
  mrs x9, fpcr
  str x9, [x0, #0x100]
  mrs x9, fpsr
  str x9, [x0, #0x108]
  stp q0, q1, [x0, #0x110]
  stp q2, q3, [x0, #0x130]
  stp q4, q5, [x0, #0x150]
  stp q6, q7, [x0, #0x170]
  stp q8, q9, [x0, #0x190]
  stp q10, q11, [x0, #0x1b0]
  stp q12, q13, [x0, #0x1d0]
  stp q14, q15, [x0, #0x1f0]
  stp q16, q17, [x0, #0x210]
  stp q18, q19, [x0, #0x230]
  stp q20, q21, [x0, #0x250]
  stp q22, q23, [x0, #0x270]
  stp q24, q25, [x0, #0x290]
  stp q26, q27, [x0, #0x2b0]
  stp q28, q29, [x0, #0x2d0]
  stp q30, q31, [x0, #0x2f0]
  mov x9, sp
  str x9, [x0, #0x310]
  ldr x9, [sp, #0xe0]
  msr nzcv, x9
  ldr x9, [sp, #0xe8]
  msr fpcr, x9
  ldr x9, [sp, #0xf0]
  msr fpsr, x9
  ldp q8, q9, [sp, #0x60]
  ldp q10, q11, [sp, #0x80]
  ldp q12, q13, [sp, #0xa0]
  ldp q14, q15, [sp, #0xc0]
  ldp x19, x20, [sp, #0x0]
  ldp x21, x22, [sp, #0x10]
  ldp x23, x24, [sp, #0x20]
  ldp x25, x26, [sp, #0x30]
  ldp x27, x28, [sp, #0x40]
  ldp x29, x30, [sp, #0x50]
  ldr x18, [sp, #0x108]
  add sp, sp, #0x110
  ret
.size denza_vector_probe_invoke_ret, .-denza_vector_probe_invoke_ret

.p2align 4
.hidden denza_vector_probe_ret
.global denza_vector_probe_ret
.type denza_vector_probe_ret, %function
denza_vector_probe_ret:
  ret
.size denza_vector_probe_ret, .-denza_vector_probe_ret
.p2align 4
.hidden denza_vector_probe_callback
.global denza_vector_probe_callback
.type denza_vector_probe_callback, %function
denza_vector_probe_callback:
  mov x0, xzr
  mov x1, xzr
  mov x2, xzr
  mov x3, xzr
  mov x4, xzr
  mov x5, xzr
  mov x6, xzr
  mov x7, xzr
  mov x8, xzr
  mov x9, xzr
  mov x10, xzr
  mov x11, xzr
  mov x12, xzr
  mov x13, xzr
  mov x14, xzr
  mov x15, xzr
  mov x16, xzr
  mov x17, xzr
  mov x18, xzr
  mov x19, xzr
  mov x20, xzr
  mov x21, xzr
  mov x22, xzr
  mov x23, xzr
  mov x24, xzr
  mov x25, xzr
  mov x26, xzr
  mov x27, xzr
  mov x28, xzr
  mov x29, xzr
  movi v0.16b, #0
  movi v1.16b, #0
  movi v2.16b, #0
  movi v3.16b, #0
  movi v4.16b, #0
  movi v5.16b, #0
  movi v6.16b, #0
  movi v7.16b, #0
  movi v8.16b, #0
  movi v9.16b, #0
  movi v10.16b, #0
  movi v11.16b, #0
  movi v12.16b, #0
  movi v13.16b, #0
  movi v14.16b, #0
  movi v15.16b, #0
  movi v16.16b, #0
  movi v17.16b, #0
  movi v18.16b, #0
  movi v19.16b, #0
  movi v20.16b, #0
  movi v21.16b, #0
  movi v22.16b, #0
  movi v23.16b, #0
  movi v24.16b, #0
  movi v25.16b, #0
  movi v26.16b, #0
  movi v27.16b, #0
  movi v28.16b, #0
  movi v29.16b, #0
  movi v30.16b, #0
  movi v31.16b, #0
  msr nzcv, xzr
  msr fpcr, xzr
  msr fpsr, xzr
  ret
.size denza_vector_probe_callback, .-denza_vector_probe_callback
.section .note.GNU-stack,"",%progbits
