/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.intro;

import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.MemoryStack.*;

/**
 * In Intro3 we learnt how to allocate short-lived memory using the MemoryStack class.
 * <p>
 * There was one thing missing, though, which is necessary when working with manual memory management, including the
 * MemoryStack, which is: Ensuring that the stackPop() call happens eventually. This may not be the case when the code
 * between stackPush() and stackPop() throws an exception.
 * <p>
 * To take care of possible exceptions, we will therefore wrap the code in a try-with-resources statement to ensure that
 * stackPop() will get called eventually.
 *
 * @author Kai Burjack
 */
public class Intro4 {

    public static void main(String[] args) {
        glfwInit();
        long window = createWindow();

        /*
         * Wrap the code that is using the MemoryStack in a Java 7 try-with-resources statement. The nice thing here is
         * that the MemoryStack class itself implements AutoCloseable, so it is applicable to being the resource in the
         * try-with-resources statement.
         *
         * What is also new here is that a call to stackPush() actually returns something, namely an instance of the
         * MemoryStack class. This instance represents the thread-local MemoryStack instance, which would otherwise be
         * accessed whenever we call stackPush(), stackPop() or one of the static stackMalloc* methods.
         *
         * So, the code below calls the static method stackPush() on the class MemoryStack, which returns the
         * MemoryStack instance of the current thread. At the end of the try-with-resources statement, a call to
         * MemoryStack.pop() will be done automatically to undo the allocation.
         */
        try (MemoryStack stack = stackPush()) {
            /*
             * The following code is identical to Intro3.
             */
            FloatBuffer buffer = stackMallocFloat(3 * 2);
            buffer.put(-0.5f).put(-0.5f);
            buffer.put(+0.5f).put(-0.5f);
            buffer.put(+0.0f).put(+0.5f);
            buffer.flip();
            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);

            /*
             * Notice that we do not need a stackPop() here! It will be done automatically at the end of the
             * try-with-resources statement, even in the event of an exception, which was the sole purpose of doing it
             * this way, in the first place.
             */
        }

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
        long window = glfwCreateWindow(800, 600, "Intro4", NULL, NULL);
        glfwMakeContextCurrent(window);
        createCapabilities();
        return window;
    }

}
