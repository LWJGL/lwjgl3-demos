/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.cuda;

import static org.lwjgl.cuda.CU40.*;
import static org.lwjgl.cuda.CUGL.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.nio.*;

import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * CUDA/OpenGL interop example.
 * <p>
 * A device kernel function is used to fill an OpenGL texture with a red color
 * gradient and GLFW is used to display that texture in a window.
 * 
 * @author Kai Burjack
 */
public class OpenGLExample {
    private static void check(int err) {
        if (err != 0)
            throw new AssertionError("Error code: " + err);
    }

    private static void run(MemoryStack s) {
        // Create a super simple OpenGL context and a texture
        glfwInit();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        long window = glfwCreateWindow(512, 512, "Hello CUDA!", NULL, NULL);
        GLFWKeyCallback keyCallback;
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwMakeContextCurrent(window);
        createCapabilities();
        Callback debugProc = GLUtil.setupDebugMessageCallback();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glEnable(GL_TEXTURE_2D);

        // Allocate some buffers up-front
        IntBuffer count = s.mallocInt(1);
        IntBuffer dev = s.mallocInt(1);
        PointerBuffer ctx = s.mallocPointer(1);
        PointerBuffer resource = s.mallocPointer(1);
        PointerBuffer array = s.mallocPointer(1);
        PointerBuffer module = s.mallocPointer(1);
        PointerBuffer surfref = s.mallocPointer(1);
        PointerBuffer function = s.mallocPointer(1);

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
// Minimum PTX version 1.5 to be able to use .surfref and sust
".version 1.5\n" + 
// We make no use of actual shader model capabilities/functions, so target the lowest possible
".target sm_11\n" +
// Add a global reference to a surface which we will write to
".global .surfref surface;\n" +
// Function to write color to a surface
".visible .entry fillcolor () {\n" +
// Allocate some registers to compute the thread (x, y) coordinates
"   .reg .u32       %blockid, %blockdim, %thrid, %xidx, %yidx, %w;\n" +
// Allocate float registers for floating-point calculations
"   .reg .f32       %fwidth, %xpos;\n" +
// Allocate a u8 register to hold the red color channel value to write
"   .reg .u8        %red;\n" +
// Compute the x coordinate of this thread for writing to the surface
// xidx = ctaid.x * ntid.x + tid.x
"    mov.u32        %blockid, %ctaid.x;\n" +
"    mov.u32        %blockdim, %ntid.x;\n" +
"    mov.u32        %thrid, %tid.x;\n" +
"    mad.lo.u32     %xidx, %blockid, %blockdim, %thrid;\n" +
// Compute the y coordinate of this thread for writing to the surface
// yidx = ctaid.y * ntid.y + tid.y
"    mov.u32        %blockid, %ctaid.y;\n" +
"    mov.u32        %blockdim, %ntid.y;\n" +
"    mov.u32        %thrid, %tid.y;\n" +
"    mad.lo.u32     %yidx, %blockid, %blockdim, %thrid;\n" +
// Compute color based on interpolated x coordinate (from 0 to 255)
// Convert the x coordinate to a float
"    cvt.rn.f32.u32 %xpos, %xidx;\n" +
// Obtain the width of the surface
"    suq.width.b32  %w, [surface];\n" +
// Convert the width to a float
"    cvt.rn.f32.u32 %fwidth, %w;\n" +
// Compute the reciprocal (1.0f/fwidth)
"    rcp.approx.f32 %fwidth, %fwidth;\n" +
// Multiply 1/fwidth to the x coordinate
"    mul.f32        %xpos, %xpos, %fwidth;\n" +
// Multiply by 255.0f to get to the range [0, 255)
"    mul.f32        %xpos, %xpos, 0F437f0000;\n" + // 255.0f
// Convert to u8 for storing via sust
"    cvt.rni.u8.f32 %red, %xpos;\n" +
// Write color to surface
// Pay close attention to the documentation of the sust instruction!
// "The lowest dimension coordinate represents a byte offset into the surface and is not scaled."
// So we have to multiply xidx by 4 in order to get the actual texel byte offset:
"    shl.b32        %xidx, %xidx, 2U;\n" +
"    sust.b.2d.v4.b8.trap [surface, {%xidx, %yidx}], {%red, 0, 0, 255};\n" +
"}";
        // Register the OpenGL texture as a CUDA resource
        check(cuGraphicsGLRegisterImage(resource, tex, GL_TEXTURE_2D,
            // Flag to tell that CUDA will overwrite the image
            CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD |
            // Flag to tell that this resource is used via a surface reference
            CU_GRAPHICS_REGISTER_FLAGS_SURFACE_LDST));
        // Map the resource to be used by further CUDA graphics functions
        // Without this, cuGraphicsSubResourceGetMappedArray() will not work
        check(cuGraphicsMapResources(resource, NULL));
        // Get the first image of the OpenGL texture as a CUDA array
        check(cuGraphicsSubResourceGetMappedArray(array, resource.get(0), 0, 0));
        // Unmap the resource
        check(cuGraphicsUnmapResources(resource, NULL));
        // Load the PTX module
        check(cuModuleLoadData(module, s.ASCII(ptx)));
        // Obtain handle to the `surface` surface reference of the module
        check(cuModuleGetSurfRef(surfref, module.get(0), "surface"));
        // Assign the array to the surface reference used by the kernel function
        check(cuSurfRefSetArray(surfref.get(0), array.get(0), 0));
        // Obtain handle to the `fillcolor` function of the module
        check(cuModuleGetFunction(function, module.get(0), "fillcolor"));
        // Execute the kernel function
        check(cuLaunchKernel(function.get(0),
            64, 64, 1, // <- 64x64x1 blocks
            8, 8, 1,   // <- 8x8x1 threads per block
            0,         // <- no shared memory
            0,         // <- use default stream
            null,      // <- no function parameters
            null));    // <- no extra parameters
        // Synchronize to catch any possible async errors from cuLaunchKernel
        check(cuCtxSynchronize());
        // Clean-up CUDA resources
        check(cuCtxDestroy(ctx.get(0)));

        // Show window and render the texture
        glfwShowWindow(window);
        while (!glfwWindowShouldClose(window)) {
            glBegin(GL_QUADS);
            glTexCoord2f(0, 0); glVertex2f(-1, -1);
            glTexCoord2f(1, 0); glVertex2f(+1, -1);
            glTexCoord2f(1, 1); glVertex2f(+1, +1);
            glTexCoord2f(0, 1); glVertex2f(-1, +1);
            glEnd();
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
        if (debugProc != null)
            debugProc.free();
        keyCallback.free();
        GL.setCapabilities(null);
    }

    public static void main(String[] args) {
        try (MemoryStack frame = MemoryStack.stackPush()) {
            run(frame);
        }
    }
}
