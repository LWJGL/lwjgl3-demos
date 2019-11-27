/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.intro;

import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.MemoryStack.*;

/**
 * In Intro2 we learnt how to allocate an off-heap memory buffer using MemoryUtil. This was done by first calling one of
 * the memAlloc*() methods which return a Java NIO Buffer instance representing the allocated memory region. Once we
 * were done with the buffer, we called the memFree() method to deallocate/free the off-heap memory represented by the
 * Java NIO Buffer.
 * <p>
 * This manual memory management is necessary when a buffer needs to live for an extended amount of time in our
 * application, meaning that the time between allocation and deallocation spans beyond one method.
 * <p>
 * In most scenarios however, the memory will be very short-living. One example was the allocation of the memory to fill
 * the VBO in Intro2. Memory was allocated, filled with data, given to OpenGL and then freed again.
 * <p>
 * LWJGL 3 provides a better way to handle such situations, which is by using the MemoryStack class. This class allows
 * to retrieve a small chunk of memory from a pre-allocated thread-local memory region of a fixed size. By default the
 * maximum size allocatable from the MemoryStack is 8 kilobytes.
 * <p>
 * By the way: It is called a stack because allocations/deallocations must be issued in LIFO order, in that allocations
 * cannot be freed randomly bust must be freed in the reverse allocation order. This allows to avoid any heap allocation
 * and compaction strategies.
 * <p>
 * Also note that the pre-allocated memory of the MemoryStack is per thread. That means, every thread will get its own
 * memory region and MemoryStack instances should not be shared among different threads.
 *
 * @author Kai Burjack
 */
public class Intro3 {

    /**
     * This sample is the same as Intro2, but uses the MemoryStack to allocate the short-living buffer to fill the VBO.
     */
    public static void main(String[] args) {
        glfwInit();
        long window = createWindow();

        /*
         * Ask the MemoryStack to create a new "stack frame".
         *
         * This is basically for house-keeping of the current stack allocation position/pointer. Everytime we allocate
         * memory using the MemoryStack (see below), the stack pointer advances (towards address zero). In order to
         * "deallocate" memory that was previously allocated by the MemoryStack, all that needs to be done is to reset
         * the stack position/pointer to the previous position. This will be done by a call to stackPop().
         *
         * So, first we need to save the current stack position/pointer using stackPush().
         */
        stackPush();

        /*
         * No we reserve some space on the stack and return it as a new Java NIO Buffer using the MemoryStack's
         * stackMallocFloat() method. The MemoryStack provides other stackMalloc* methods as well, which return other
         * typed Java NIO Buffer views.
         */
        FloatBuffer buffer = stackMallocFloat(3 * 2);

        /*
         * The following few lines of code from Intro2 are identical. This also means that code _using_ a
         * stack-allocated memory buffer does not differ from code using buffers allocated with other allocation
         * strategies.
         */
        buffer.put(-0.5f).put(-0.5f);
        buffer.put(+0.5f).put(-0.5f);
        buffer.put(+0.0f).put(+0.5f);
        buffer.flip();
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);

        /*
         * Now, that we don't need the allocated buffer anymore, we have to pop the "stack frame". This will reset the
         * state of the MemoryStack (i.e. the allocation position/pointer) to where it was before we called stackPush()
         * above.
         */
        stackPop();

        /*
         * The following code is just like in Intro2.
         */
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 0, 0L);
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glfwSwapBuffers(window);
        }
        glfwTerminate();
        System.out.println("Fin.");
    }

    private static long createWindow() {
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        long window = glfwCreateWindow(800, 600, "Intro3", NULL, NULL);
        glfwMakeContextCurrent(window);
        createCapabilities();
        return window;
    }

}
