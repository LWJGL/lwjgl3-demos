/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.intro;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL15.*;

/**
 * After we learnt that LWJGL 3 provides the functions exported by a native library as static Java methods, in this
 * second part of the introductory series we will look at how to communicate data between our Java application and the
 * native library. One example of such data could be a simple array of vectors to upload to OpenGL in order to draw a
 * simple triangle.
 * <p>
 * It is important to know how Java can communicate data with a native library, such as OpenGL. There are generally two
 * different <em>kinds</em> of memory which we can allocate.
 * <ul>
 * <li>memory that lives on the garbage collected Java heap managed by the JVM. This is called "on-heap" or "heap"
 * memory, because with "heap" here we mean the memory region managed by the JVM
 * <li>memory that lives in unmanaged memory the JVM does not know about and will not garbage collect. This is also
 * called "off-heap" memory
 * </ul>
 * The former memory is used for all normal Java object and array allocations. The advantage is that the Java programmer
 * does not have to care about freeing/deallocating that memory once it is not used anymore. The JVM will know this and
 * will reclaim the memory using its built-in garbage collector. And the JVM can employ more sophisticated mechanisms,
 * such as escape analysis, to even completely avoid allocating memory for objects.
 * <p>
 * The downside is that we cannot transfer this kind of memory to a native library because:
 * <ul>
 * <li>native libraries use the C memory model which basically consists of a large virtual region of memory adressable
 * using byte addresses
 * <li>we cannot get the virtual memory byte address of a Java object (actually, we _can_ but it is not part of this
 * introduction)
 * <li>even if we could get the address of a Java object, we have no standards-compliant reliable way of knowing the
 * layout of the memory
 * <li>and worse, the JVM can move memory around freely, so any virtual memory address we obtain of a Java-managed
 * object can potentially change
 * </ul>
 * Because of these limitations, we leave that kind of memory to the JVM for allocating normal Java objects and arrays,
 * and concentrate on the other kind of memory, "off-heap" memory.
 * <p>
 * Using off-heap memory we <em>can</em> get the physical virtual memory address of the allocated memory, which will
 * also not change throughout the lifetime of the process. Therefore, we can communicate this memory address to native
 * libraries. Those native libraries can then read from or write to the memory.
 * 
 * @author Kai Burjack
 */
public class Intro2 {

    /**
     * In this example, we will use OpenGL to draw a single triangle on a window.
     * <p>
     * As mentioned above, we have to use off-heap memory for this in order to communicate the virtual memory address to
     * OpenGL, which in turn will read the data we provided at that address.
     * <p>
     * The example here will upload the position vectors of a simple triangle to an OpenGL Vertex Buffer Object.
     */
    public static void main(String[] args) {
        /*
         * We know this already from the first part. We call glfwInit() to initialize GLFW.
         */
        glfwInit();

        /*
         * Create a visible window using GLFW. This is not the focus of this part of the introduction, but we have to do
         * it anyway in order to see anything on the screen.
         */
        long window = createWindow();

        /*
         * Create the data buffer to hold a single triangle. This is the focus of this introduction part.
         * 
         * LWJGL 3 provides various ways to allocate an off-heap memory buffer. One way is via the MemoryUtil class,
         * which is statically imported above. This class provides methods starting with 'memAlloc'. Those methods
         * allocate off-heap memory and return a Java NIO Buffer object, which is just a Java-accessible view on the
         * allocated memory. This means that using a Java NIO Buffer we can read and manipulate the memory using Java
         * API calls.
         * 
         * It is important to note that using the 'memAlloc' methods in MemoryUtil, we have to take care of
         * deallocating/freeing the buffer ourselves. While it _is_ true that the Java NIO Buffer instance itself will
         * be garbage-collected, the memory it points to/represents will not.
         */
        FloatBuffer buffer = memAllocFloat(3 * 2);

        /*
         * Fill the buffer with the relative put() methods. Each call to put() below will result in the provided float
         * value being written to the current read/write position, and then the position will advance further one float
         * (i.e. 4 bytes).
         */
        buffer.put(-0.5f).put(-0.5f);
        buffer.put(+0.5f).put(-0.5f);
        buffer.put(+0.0f).put(+0.5f);

        /*
         * The following is important, because we used "relative" put methods of the Java NIO FloatBuffer. See the
         * JavaDocs of that class for further information.
         * 
         * What flip() does is to set the read/write limit of the buffer to the current read/write position and reset
         * the position to zero.
         * 
         * Now, a well-meant advice: You should really consult the JavaDoc of the Java NIO Buffer API. Understanding how
         * the Java NIO buffers behave is really essential and important for everything we do with them.
         */
        buffer.flip();

        /*
         * Create the OpenGL Vertex Buffer Object. Don't focus on the next two calls...
         */
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        /*
         * ...but instead focus on the following call. This is where we actually send the data buffer to OpenGL.
         * 
         * Whenever we can communicate data to a native library via some API function of that library, LWJGL 3 provides
         * at least one method overload taking a Java NIO Buffer (or any typed view of it) as parameter.
         * 
         * In this case, it is the glBufferData() call we will use. This method provides multiple overloads depending on
         * that typed Java NIO Buffer we use. In our case we used the FloatBuffer, because floats is what we use in this
         * example and using a FloatBuffer was the easiest to fill the buffer with floats above.
         * 
         * The next important thing now is how LWJGL 3 actually handles the buffer and how it will call the underlying
         * native function. Usually, a native function will take two arguments, the starting address of the memory and
         * the length of the memory in some unit (such as number of bytes).
         * 
         * Since we use the Java NIO Buffer here, which has a built-in read/write position and a limit, LWJGL 3 respects
         * both the position and the limit, reads both as part of the call and forwards this information to the
         * underlying native function.
         * 
         * Another important thing to note is that LWJGL 3 will NEVER modify the Buffer position as part of the call.
         * So, the observable state of the Java NIO Buffer will still be the same after the call returns.
         */
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        /*
         * After the above call, the state of the provided buffer will still be the same. No change in position, limit,
         * capacity or mark.
         */

        /*
         * Depending on the semantics of the underlying native library, we can now either free the buffer or have to
         * keep it around. When using OpenGL's glBufferData() we now can free the buffer, because OpenGL read everything
         * from it.
         * 
         * When allocating memory with MemoryUtil.memAlloc*() we have to use the MemoryUtil.memFree() method to
         * deallocate the underlying off-heap memory represented by the given buffer.
         */
        memFree(buffer);

        /*
         * The next code is not important. It just exists to prove that everything works and we see a white triangle on
         * the window.
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
        /*
         * In order to see anything, we create a new window using GLFW's glfwCreateWindow().
         */
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        long window = glfwCreateWindow(800, 600, "Intro2", NULL, NULL);

        /*
         * Tell GLFW to make the OpenGL context current so that we can make OpenGL calls.
         */
        glfwMakeContextCurrent(window);

        /*
         * Tell LWJGL 3 that an OpenGL context is current in this thread. This will result in LWJGL 3 querying function
         * pointers for various OpenGL functions.
         */
        createCapabilities();

        /*
         * Return the handle to the created window.
         */
        return window;
    }

}
