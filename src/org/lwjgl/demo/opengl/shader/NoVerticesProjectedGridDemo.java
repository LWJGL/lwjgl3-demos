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
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Renders a projected grid without using any vertex source but fully computing the vertex positions in the vertex shader.
 * <p>
 * This showcases JOML's implementation of <a href="http://fileadmin.cs.lth.se/graphics/theses/projects/projgrid/projgrid-lq.pdf">Projected Grid</a>.
 * <p>
 * This demo does not take care or show how to obtain a "pleasant" projector matrix. Consult section 2.4.1 in the referenced paper for more
 * guidance on how to obtain a projector matrix.
 * So, to keep things simple this demo instead just uses the camera's view-projection matrix as the projector matrix.
 * 
 * @author Kai Burjack
 */
public class NoVerticesProjectedGridDemo {

    long window;
    int width = 1024;
    int height = 768;

    int program;
    int transformUniform;
    int intersectionsUniform;
    int timeUniform;
    int sizeUniform;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    Matrix4f view = new Matrix4f();
    Matrix4f proj = new Matrix4f();
    Matrix4f viewproj = new Matrix4f();
    int sizeX = 128;
    int sizeY = 128;

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

        window = glfwCreateWindow(width, height, "No vertices projected grid shader demo", NULL, NULL);
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
        intersectionsUniform = glGetUniformLocation(program, "intersections");
        timeUniform = glGetUniformLocation(program, "time");
        sizeUniform = glGetUniformLocation(program, "size");
        glUseProgram(0);
    }

    Matrix4f projector = new Matrix4f();
    Matrix4f invViewProj = new Matrix4f();
    Matrix4f range = new Matrix4f();
    Vector4f p0 = new Vector4f();
    Vector4f p1 = new Vector4f();
    Vector4f isect = new Vector4f();
    float alpha = 0.0f;
    long lastTime = System.nanoTime();
    final float MAX_HEIGHT = 0.2f;
    void render() {
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(this.program);

        long thisTime = System.nanoTime();
        float delta = (thisTime - lastTime) / 1E9f;
        alpha += delta * 2.0f;
        lastTime = thisTime;

        // Build camera view-projection matrix
        Matrix4f r = viewproj
                .setPerspective((float) Math.toRadians(45.0f), (float)width/height, 0.1f, 100.0f)
                .lookAt(0, 4, 20, 0, 0, 0, 0, 1, 0)
                .invert(invViewProj) // <- invert it
                .projectedGridRange(viewproj, -MAX_HEIGHT, MAX_HEIGHT, range); // <- build range matrix
        if (r == null) {
            // grid not visible. We don't render anything!
            return;
        }
        invViewProj.mul(range, projector); // <- build final projector matrix
        // compute the intersections with the y=0 plane at the grid corners in homogeneous space
        for (int i = 0; i < 4; i++) {
            float x = (i & 1);
            float y = (i >>> 1) & 1;
            projector.transform(p0.set(x, y, -1, 1));
            projector.transform(p1.set(x, y, +1, 1));
            float t = -p0.y / (p1.y - p0.y);
            isect.set(p1).sub(p0).mul(t).add(p0);
            glUniform4f(intersectionsUniform+i, isect.x, isect.y, isect.z, isect.w);
        }
        // upload matrices to the shader
        glUniformMatrix4fv(transformUniform, false, viewproj.get(matrixBuffer));
        glUniform1f(timeUniform, alpha);
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
        new NoVerticesProjectedGridDemo().run();
    }

}