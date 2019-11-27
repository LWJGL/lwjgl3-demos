/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.io.IOException;
import java.nio.*;
import org.joml.Matrix4f;
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
 * Renders a cubic B-spline without using any vertex source but fully computing the vertex positions in the vertex shader.
 * <p>
 * This demo implements cubic B-spline evaluation in the vertex shader and stores the control points in a Uniform Buffer Object.
 * 
 * @author Kai Burjack
 */
public class NoVerticesBSplineDemo {

    long window;
    int width = 1024;
    int height = 768;

    int program;
    int transformUniform;
    int lodUniform;
    int numPointsUniform;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    Matrix4f transform = new Matrix4f();
    int lod = 10;
    static final int numPoints = 50;

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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "No vertices cubic B-splines shader demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (NoVerticesBSplineDemo.this.width != width || NoVerticesBSplineDemo.this.height != height)) {
                    NoVerticesBSplineDemo.this.width = width;
                    NoVerticesBSplineDemo.this.height = height;
                }
            }
        });

        System.out.println("Press 'arrow up' to increase the lod");
        System.out.println("Press 'arrow down' to decrease the lod");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_UP && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    lod++;
                    System.out.println("Increased LOD to " + lod);
                } else if (key == GLFW_KEY_DOWN && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    lod = Math.max(1, lod - 1);
                    System.out.println("Decreased LOD to " + lod);
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
        glBindVertexArray(glGenVertexArrays());
        createProgram();
        createUbo();
        // and set some GL state
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
    }

    void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/noverticesbspline.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/noverticesbspline.fs", GL_FRAGMENT_SHADER);
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
        lodUniform = glGetUniformLocation(program, "lod");
        numPointsUniform = glGetUniformLocation(program, "numPoints");
        glUseProgram(0);
    }

    static void createUbo() {
        int ubo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, ubo);
        int pointsPerCircle = 5;
        ByteBuffer bb = BufferUtils.createByteBuffer(numPoints * 4 * 4);
        FloatBuffer fb = bb.asFloatBuffer();
        for (int i = 0; i < numPoints; i++) {
            float scale = 1.0f - (float)i/numPoints;
            float t = (float)i / pointsPerCircle;
            float ang = 2.0f * (float)Math.PI * t;
            float x = (float) Math.cos(ang) * scale;
            float y = i / 10.0f;
            float z = (float) Math.sin(ang) * scale;
            fb.put(x).put(y).put(z).put(1.0f);
        }
        glBufferData(GL_UNIFORM_BUFFER, bb, GL_STATIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
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
                 .lookAt(0, 0, 6,
                         0, 2, 0,
                         0, 1, 0)
                 .rotateY(angle * (float) Math.toRadians(180)); // 180 degrees per second
        // and upload it to the shader
        glUniformMatrix4fv(transformUniform, false, transform.get(matrixBuffer));
        glUniform1i(lodUniform, lod);
        glUniform1i(numPointsUniform, numPoints);

        glDrawArrays(GL_LINE_STRIP, 0, lod * (numPoints+1) + 1);

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
        new NoVerticesBSplineDemo().run();
    }

}