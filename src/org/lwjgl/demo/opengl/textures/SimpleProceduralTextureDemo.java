/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.ARBVertexBufferObject.*;
import static org.lwjgl.system.MemoryUtil.*;

public class SimpleProceduralTextureDemo {

    long window;
    int width = 1024;
    int height = 768;
    int tex;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Closure debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));

        if (glfwInit() != GL_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);

        window = glfwCreateWindow(width, height, "Simple texture demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (SimpleProceduralTextureDemo.this.width != width || SimpleProceduralTextureDemo.this.height != height)) {
                    SimpleProceduralTextureDemo.this.width = width;
                    SimpleProceduralTextureDemo.this.height = height;
                }
            }
        });
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, GL_TRUE);
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Set state and create all needed GL resources */
        glEnable(GL_TEXTURE_2D);
        createTexture();
        createVbo();
    }

    private void createTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, this.tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 4, 4, 0, GL_RGB, GL_UNSIGNED_BYTE, (IntBuffer) null);
        glBindTexture(GL_TEXTURE_2D, tex);
        IntBuffer bb = BufferUtils.createIntBuffer(16);
        bb.put(new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
               }).rewind();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 4, GL_RGBA, GL_UNSIGNED_BYTE, bb);
    }

    private void createVbo() {
        int vbo = glGenBuffersARB();
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * (2 + 2) * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f).put(0.0f).put(0.0f);
        fv.put( 1.0f).put(-1.0f).put(1.0f).put(0.0f);
        fv.put( 1.0f).put( 1.0f).put(1.0f).put(1.0f);
        fv.put( 1.0f).put( 1.0f).put(1.0f).put(1.0f);
        fv.put(-1.0f).put( 1.0f).put(0.0f).put(1.0f);
        fv.put(-1.0f).put(-1.0f).put(0.0f).put(0.0f);
        glBufferDataARB(GL_ARRAY_BUFFER_ARB, bb, GL_STATIC_DRAW_ARB);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 4 * 4, 0L);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(2, GL_FLOAT, 4 * 4, 2 * 4);
    }

    private void render() {
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private void loop() {
        while (glfwWindowShouldClose(window) == GL_FALSE) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            render();
            glfwSwapBuffers(window);
        }
    }

    private void run() {
        try {
            init();
            loop();

            if (debugProc != null) {
                debugProc.release();
            }

            errCallback.release();
            keyCallback.release();
            fbCallback.release();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new SimpleProceduralTextureDemo().run();
    }

}
