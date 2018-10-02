/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.fbo;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.ARBFramebufferObject.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases multisampled FBO rendering.
 * 
 * @author Kai Burjack
 */
public class MultisampledFboDemo {

    long window;
    int width = 1024;
    int height = 768;
    boolean resetFramebuffer;
    boolean destroyed;
    Object lock = new Object();

    int colorRenderBuffer;
    int depthRenderBuffer;
    int fbo;
    int samples = 8;

    GLFWErrorCallback errorCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

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
            fbCallback.free();
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

        window = glfwCreateWindow(width, height, "Multisampled FBO", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (MultisampledFboDemo.this.width != width || MultisampledFboDemo.this.height != height)) {
                    MultisampledFboDemo.this.width = width;
                    MultisampledFboDemo.this.height = height;
                    MultisampledFboDemo.this.resetFramebuffer = true;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwShowWindow(window);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }

    void createFramebufferObject() {
        colorRenderBuffer = glGenRenderbuffers();
        depthRenderBuffer = glGenRenderbuffers();
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        glBindRenderbuffer(GL_RENDERBUFFER, colorRenderBuffer);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRenderBuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, depthRenderBuffer);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderBuffer);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void resizeFramebufferTexture() {
        glDeleteRenderbuffers(depthRenderBuffer);
        glDeleteRenderbuffers(colorRenderBuffer);
        glDeleteFramebuffers(fbo);
        createFramebufferObject();
    }

    void update() {
        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
    }

    void renderLoop() {
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        glClearColor(1.0f, 1.0f, 1.0f, 0.0f);
        glColor3f(0.1f, 0.1f, 0.1f);

        /* Query maximum sample count */
        samples = glGetInteger(GL_MAX_SAMPLES);
        System.err.println("Using " + samples + "x multisampling");

        /* Initially create the FBO with color texture and renderbuffer */
        createFramebufferObject();

        long lastTime = System.nanoTime();
        while (!destroyed) {
            /* Update the FBO if the window changed in size */
            update();

            /* Render to multisampled FBO */
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glViewport(0, 0, width, height);

                long thisTime = System.nanoTime();
                float elapsed = (lastTime - thisTime) / 1E9f;
                lastTime = thisTime;

                /* Simple orthographic project */
                float aspect = (float) width / height;
                glMatrixMode(GL_PROJECTION);
                glLoadIdentity();
                glOrtho(-1.0f * aspect, +1.0f * aspect, -1.0f, +1.0f, -1.0f, +1.0f);

                /* Rotate a bit and draw a quad */
                glMatrixMode(GL_MODELVIEW);
                glRotatef(elapsed, 0, 0, 1);
                glBegin(GL_QUADS);
                glVertex2f(-0.5f, -0.5f);
                glVertex2f(+0.5f, -0.5f);
                glVertex2f(+0.5f, +0.5f);
                glVertex2f(-0.5f, +0.5f);
                glEnd();
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            /* Blit to default framebuffer */
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

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
        new MultisampledFboDemo().run();
    }

}