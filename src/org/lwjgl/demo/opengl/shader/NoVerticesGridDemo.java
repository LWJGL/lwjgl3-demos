/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.io.IOException;
import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Renders a grid without using any vertex source but fully computing the vertex positions in the vertex shader.
 * 
 * @author Kai Burjack
 */
public class NoVerticesGridDemo {

    long window;
    int width = 1024;
    int height = 768;

    int program;
    int transformUniform;
    int sizeUniform;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    Matrix4f transform = new Matrix4f();
    int sizeX = 10;
    int sizeY = 10;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 3.0 or higher.");
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "No vertices grid shader demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (NoVerticesGridDemo.this.width != width || NoVerticesGridDemo.this.height != height)) {
                    NoVerticesGridDemo.this.width = width;
                    NoVerticesGridDemo.this.height = height;
                }
            }
        });

        System.out.println("Press 'arrow right' to increase the grid size in X");
        System.out.println("Press 'arrow left' to decrease the grid size in X");
        System.out.println("Press 'arrow up' to increase the grid size in Y");
        System.out.println("Press 'arrow down' to decrease the grid size in Y");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true);
                }
                if (key == GLFW_KEY_LEFT && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    sizeX = Math.max(1, sizeX - 1);
                } else if (key == GLFW_KEY_RIGHT && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    sizeX++;
                } else if (key == GLFW_KEY_DOWN && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    sizeY = Math.max(1, sizeY - 1);
                } else if (key == GLFW_KEY_UP && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    sizeY++;
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
        glfwSwapInterval(0);
        glfwShowWindow(window);
        caps = createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Create all needed GL resources
        createProgram();
        // and set some GL state
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
    }

    void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/noverticesgrid.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/noverticesgrid.fs", GL_FRAGMENT_SHADER);
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
        sizeUniform = glGetUniformLocation(program, "size");
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
        transform.setPerspective((float) Math.toRadians(45.0f), (float)width/height, 0.1f, 100.0f)
                 .lookAt(0, 1, 2, 0, 0, 0, 0, 1, 0)
                 .rotateY(angle * (float) Math.toRadians(30)); // 30 degrees per second
        // and upload it to the shader
        glUniformMatrix4fv(transformUniform, false, transform.get(matrixBuffer));
        glUniform2i(sizeUniform, sizeX, sizeY);

        glDrawArrays(GL_TRIANGLES, 0, 6 * sizeX * sizeY);

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
        new NoVerticesGridDemo().run();
    }

}