/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.glfw;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.nio.*;

/**
 * Showcases how you can use multithreading in a GLFW application in order to
 * separate the (blocking) winproc handling from the render loop.
 * 
 * @author Kai Burjack
 */
public class Multithreaded {

    GLFWErrorCallback errorCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fsCallback;
    Callback debugProc;

    long window;
    int width = 300;
    int height = 300;
    Object lock = new Object();
    boolean destroyed;

    void run() {
        try {
            init();
            winProcLoop();

            synchronized (lock) {
                destroyed = true;
                glfwDestroyWindow(window);
            }
            if (debugProc != null)
                debugProc.free();
            keyCallback.free();
            fsCallback.free();
        } finally {
            glfwTerminate();
            errorCallback.free();
        }
    }

    void init() {
        glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Hello World!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetFramebufferSizeCallback(window, fsCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        glfwShowWindow(window);
    }

    void renderLoop() {
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        glClearColor(0.3f, 0.5f, 0.7f, 0.0f);

        long lastTime = System.nanoTime();
        while (!destroyed) {
            glClear(GL_COLOR_BUFFER_BIT);
            glViewport(0, 0, width, height);

            long thisTime = System.nanoTime();
            float elapsed = (lastTime - thisTime) / 1E9f;
            lastTime = thisTime;

            float aspect = (float) width / height;
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glOrtho(-1.0f * aspect, +1.0f * aspect, -1.0f, +1.0f, -1.0f, +1.0f);

            glMatrixMode(GL_MODELVIEW);
            glRotatef(elapsed * 10.0f, 0, 0, 1);
            glBegin(GL_QUADS);
            glVertex2f(-0.5f, -0.5f);
            glVertex2f(+0.5f, -0.5f);
            glVertex2f(+0.5f, +0.5f);
            glVertex2f(-0.5f, +0.5f);
            glEnd();

            synchronized (lock) {
                if (!destroyed) {
                    glfwSwapBuffers(window);
                }
            }
        }
    }

    void winProcLoop() {
        /*
         * Start new thread to have the OpenGL context current in and which does
         * the rendering.
         */
        new Thread(new Runnable() {
            public void run() {
                renderLoop();
            }
        }).start();

        while (!glfwWindowShouldClose(window)) {
            glfwWaitEvents();
        }
    }

    public static void main(String[] args) {
        new Multithreaded().run();
    }

}