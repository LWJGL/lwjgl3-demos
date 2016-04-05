/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.shader;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.IOException;
import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;

/**
 * Renders a proejcted grid without using any vertex source but fully computing the vertex positions in the vertex shader.
 * <p>
 * This showcases JOML's implementation of <a href="http://fileadmin.cs.lth.se/graphics/theses/projects/projgrid/projgrid-lq.pdf">Projected Grid</a>.
 * 
 * @author Kai Burjack
 */
public class NoVerticesProjectedGridDemo {

    long window;
    int width = 1024;
    int height = 768;

    int program;
    int transformUniform;
    int projectorUniform;
    int sizeUniform;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Closure debugProc;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    Matrix4f view = new Matrix4f();
    Matrix4f proj = new Matrix4f();
    Matrix4f viewproj = new Matrix4f();
    int sizeX = 40;
    int sizeY = 40;

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
                super.free();
            }
        });

        if (glfwInit() != GL_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
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
                if (width > 0 && height > 0 && (NoVerticesProjectedGridDemo.this.width != width || NoVerticesProjectedGridDemo.this.height != height)) {
                    NoVerticesProjectedGridDemo.this.width = width;
                    NoVerticesProjectedGridDemo.this.height = height;
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
                    glfwSetWindowShouldClose(window, GL_TRUE);
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
        int vshader = createShader("org/lwjgl/demo/opengl/shader/noverticesprojectedgrid.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/noverticesprojectedgrid.fs", GL_FRAGMENT_SHADER);
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
        projectorUniform = glGetUniformLocation(program, "projector");
        sizeUniform = glGetUniformLocation(program, "size");
        glUseProgram(0);
    }

    Matrix4f projector = new Matrix4f();
    Matrix4f invViewProj = new Matrix4f();
    Matrix4f range = new Matrix4f();
    void render() {
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(this.program);

        // Build camera view-projection matrix
        viewproj.setPerspective((float) Math.toRadians(45.0f), (float)width/height, 0.1f, 100.0f)
                .lookAt(0, 1, 8, 0, 0, 0, 0, 1, 0)
                .invert(invViewProj) // <- invert it
                .projectedGridRange(viewproj, 0, 0, range); // <- build range matrix
        invViewProj.mul(range, projector); // <- build final projector matrix
        // and upload it to the shader
        glUniformMatrix4fv(transformUniform, false, viewproj.get(matrixBuffer));
        glUniformMatrix4fv(projectorUniform, false, projector.get(matrixBuffer));
        glUniform2i(sizeUniform, sizeX, sizeY);

        glDrawArrays(GL_TRIANGLES, 0, 6 * sizeX * sizeY);

        glUseProgram(0);
    }

    void loop() {
        while (glfwWindowShouldClose(window) == GLFW_FALSE) {
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
        new NoVerticesProjectedGridDemo().run();
    }

}