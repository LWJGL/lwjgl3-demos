/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.io.IOException;
import java.nio.*;
import org.joml.Matrix3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Shows how to use immediate mode with a simple shader.
 * 
 * @author Kai Burjack
 */
public class ImmediateModeDemo {

    long window;
    int width = 1024;
    int height = 768;

    int program;
    int transformUniform;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(9);
    Matrix3f transform = new Matrix3f();

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 2.0 or higher.");
                delegate.invoke(error, description);
            }

            @Override
            public void free() {
                delegate.free();
            }
        });

        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Immediate mode shader demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (ImmediateModeDemo.this.width != width || ImmediateModeDemo.this.height != height)) {
                    ImmediateModeDemo.this.width = width;
                    ImmediateModeDemo.this.height = height;
                }
            }
        });

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

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Create all needed GL resources
        createProgram();
        // and set some GL state
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
    }

    void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/simple.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/simple.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.program = program;
        glUseProgram(program);
        transformUniform = glGetUniformLocation(program, "transform");
        glUseProgram(0);
    }

    float angle = 0.0f;
    long lastTime = System.nanoTime();
    void render() {
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(this.program);

        // Compute rotation angle
        long thisTime = System.nanoTime();
        float delta = (thisTime - lastTime) / 1E9f;
        angle += delta;
        lastTime = thisTime;

        // Build some transformation matrix
        float invAspect = (float) height / width;
        transform.scaling(invAspect, 1, 1) // correct the aspect ratio with some scaling
                 .rotateZ(angle * (float) Math.toRadians(45)) // rotate 45 degrees per second
                 .scale(0.5f); // make everything a bit smaller
        // and upload it to the shader
        glUniformMatrix3fv(transformUniform, false, transform.get(matrixBuffer));

        // draw some quad
        glBegin(GL_QUADS);
        glVertex2f(-1, -1);
        glVertex2f(+1, -1);
        glVertex2f(+1, +1);
        glVertex2f(-1, +1);
        glEnd();

        glUseProgram(0);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            render();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null) {
                debugProc.free();
            }

            errCallback.free();
            keyCallback.free();
            fbCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new ImmediateModeDemo().run();
    }

}