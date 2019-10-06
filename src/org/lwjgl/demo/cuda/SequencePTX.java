/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.cuda;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.cuda.CU40.*;
import java.nio.*;
import org.lwjgl.*;
import org.lwjgl.system.*;

/**
 * A small and simple example of using PTX code to write a sequence of
 * consecutive numbers into a buffer via a device kernel function.
 * 
 * @author Kai Burjack
 */
public class SequencePTX {
    private static void check(int err) {
        if (err != 0)
            throw new AssertionError("Error \n code: " + err);
    }

    private static void run(int N, MemoryStack s) {
        // Allocate some buffers up-front
        IntBuffer count = s.mallocInt(1);
        IntBuffer dev = s.mallocInt(1);
        PointerBuffer ctx = s.mallocPointer(1);
        PointerBuffer module = s.mallocPointer(1);
        PointerBuffer function = s.mallocPointer(1);
        PointerBuffer deviceMem = s.mallocPointer(1);
        IntBuffer hostMem = memAllocInt(N);
        // Initialize/load the CUDA device driver for this process
        check(cuInit(0));
        // Check if we actually have at least one CUDA-capable device
        check(cuDeviceGetCount(count));
        if (count.get(0) == 0)
            throw new AssertionError("No CUDA-capable device found");
        // Obtain handle to first device
        check(cuDeviceGet(dev, 0));
        // and create a CUDA context on that device, which will also be made
        // current in the calling thread (much like OpenGL's context)
        check(cuCtxCreate(ctx, 0, dev.get(0)));
        // Create the PTX source string of the module
        String ptx =
// Minimum PTX version 2.3 for .address_size
".version 2.3\n" + 
// We make no use of actual shader model capabilities/functions, so target the lowest possible
".target sm_11\n" +
// We want 64-bit addresses
".address_size 64\n" +
// Fill a buffer sequentially from 0..len
".visible .entry seq (.param .u32 len, .param .u64 .ptr.global.align 8 buffer) {\n" +
// Create a predicate register to let only threads participate which fall into
// the range of the buffer
"   .reg .pred      %p;\n" +
// Create uint32_t registers for thread index computation and for the `len` parameter
"   .reg .u32       %blockid, %blockdim, %thrid, %idx, %len;\n" +
// Create two uint64_t registers to hold the buffer address and a access offset
"   .reg .u64       %addr, %off;\n" +
// Read the special registers `ntid`, `ctaid` and `tid.x` to compute the global thread
// index of the thread used to access the correct buffer item
// This is: idx = ctaid.x * ntid.x + tid
"    mov.u32        %blockid, %ctaid.x;\n" +
"    mov.u32        %blockdim, %ntid.x;\n" +
"    mov.u32        %thrid, %tid.x;\n" +
"    mad.lo.u32     %idx, %blockid, %blockdim, %thrid;\n" +
// Read the function parameter `len` into a register
"    ld.param.u32   %len, [len];\n" +
// Set the predicate `p` to `idx >= len` (to later inactivate threads with higher idx)
"    setp.ge.u32    %p, %idx, %len;\n" +
// Perform a conditional/predicated return when predicate/condition is met
// This will mark all threads as inactive (for all further instructions) for which 
// the predicate/condition is false
"@%p ret;\n" +
// Otherwise read the buffer address parameter into register
"    ld.param.u64   %addr, [buffer];\n" +
// Convert the idx to uint64_t and store in off (this will be the buffer address offset)
"    cvt.u64.u32    %off, %idx;\n" +
// Compute the final address via: addr = off * 4 + addr
"    mad.lo.u64     %addr, %off, 4U, %addr;\n" +
// Store the thread id at the respective buffer address
"    st.global.u32 [%addr], %idx;\n" +
"}";
        // Load the PTX module
        check(cuModuleLoadData(module, s.ASCII(ptx)));
        // Obtain handle to the `seq` function of the module
        check(cuModuleGetFunction(function, module.get(0), "seq"));
        // Allocate memory on the device to hold the resulting number sequence
        check(cuMemAlloc(deviceMem, Integer.BYTES * N));
        // Compute a possible grid configuration for the kernel
        // We just use a constant 512 threads per block (maximum for Compute Capability 1.x)
        int numThreadsPerBlock = 512;
        // Compute the necessary number of blocks to cover `N`
        int numBlocks = Math.max(1, (int) Math.ceil((double) N / numThreadsPerBlock));
        // Execute the kernel function
        check(cuLaunchKernel(function.get(0),
            numBlocks, 1, 1, // <- size of the grid in number of blocks
            numThreadsPerBlock, 1, 1, // <- number of threads per block
            0, // <- number of shared memory bytes
            NULL, // <- stream to use (0/NULL = default stream)
            s.pointers( // <- kernel parameters are always pointers to the actual data
                memAddress(s.ints(N)), // <- number of items in buffer
                memAddress(s.longs(deviceMem.get(0))) // <- the buffer base address
            ), null));
        // Synchronize to catch any possible async errors from cuLaunchKernel
        check(cuCtxSynchronize());
        // Read-back device memory to host
        check(cuMemcpyDtoH(hostMem, deviceMem.get(0)));
        // and check whether the numbers are correct
        for (int i = 0; i < N; i++)
            if (hostMem.get(i) != i)
                throw new AssertionError();
        // Clean-up
        memFree(hostMem);
        check(cuMemFree(deviceMem.get(0)));
        check(cuCtxDestroy(ctx.get(0)));
    }

    public static void main(String[] args) {
        try (MemoryStack frame = MemoryStack.stackPush()) {
            // Run with an arbitrary number of N
            System.out.println("Start...");
            run(765432, frame);
            System.out.println("Done!");
        }
    }
}
