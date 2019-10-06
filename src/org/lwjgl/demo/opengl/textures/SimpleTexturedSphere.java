/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.textures;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.lang.Math;
import java.nio.*;

import org.joml.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Renders a simple textured sphere using OpenGL 4.0 Core Profile.
 * 
 * @author Kai Burjack
 */
public class SimpleTexturedSphere {

    static final int rings = 80;
    static final int sectors = 80;

    long window;
    int width = 1024;
    int height = 768;

    int vao;
    int sphereProgram;
    int sphereProgram_inputPosition;
    int sphereProgram_inputTextureCoords;
    int sphereProgram_matrixUniform;

    Matrix4f m = new Matrix4f();
    FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
    float time;

    GLCapabilities caps;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbsCallback;
    Callback debugProc;

    void init() throws IOException {
        glfwInit();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Simple textured sphere", NULL, NULL);
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetFramebufferSizeCallback(window, fbsCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                SimpleTexturedSphere.this.width = width;
                SimpleTexturedSphere.this.height = height;
            }
        });
        glfwMakeContextCurrent(window);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        createTexture();
        createQuadProgram();
        createSphere();
        glClearColor(0.02f, 0.03f, 0.04f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glfwShowWindow(window);
    }

    void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/texturedSphere.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/texturedSphere.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        glUseProgram(program);
        int texLocation = glGetUniformLocation(program, "tex");
        glUniform1i(texLocation, 0);
        sphereProgram_matrixUniform = glGetUniformLocation(program, "matrix");
        sphereProgram_inputPosition = glGetAttribLocation(program, "position");
        sphereProgram_inputTextureCoords = glGetAttribLocation(program, "texCoords");
        glUseProgram(0);
        this.sphereProgram = program;
    }

    void createSphere() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        /* Generate vertex buffer */
        float PI = (float) Math.PI;
        float R = 1f / (rings - 1);
        float S = 1f / (sectors - 1);
        FloatBuffer fb = MemoryUtil.memAllocFloat(rings * sectors * (3 + 2));
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < sectors; s++) {
                float x = (float) (Math.cos(2 * PI * s * S) * Math.sin(PI * r * R));
                float y = (float) Math.sin(-PI / 2 + PI * r * R);
                float z = (float) (Math.sin(2 * PI * s * S) * Math.sin(PI * r * R));
                fb.put(x).put(y).put(z);
                fb.put(1.0f - s * S).put(1.0f - r * R);
            }
        }
        fb.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        MemoryUtil.memFree(fb);
        glVertexAttribPointer(sphereProgram_inputPosition, 3, GL_FLOAT, false, (3 + 2) * 4, 0L);
        glEnableVertexAttribArray(sphereProgram_inputPosition);
        glVertexAttribPointer(sphereProgram_inputTextureCoords, 2, GL_FLOAT, false, (3 + 2) * 4, 3 * 4L);
        glEnableVertexAttribArray(sphereProgram_inputTextureCoords);
        /* Generate index/element buffer */
        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer ib = MemoryUtil.memAllocInt((rings - 1) * (sectors - 1) * 6);
        for (int r = 0; r < rings - 1; r++) {
            for (int s = 0; s < sectors - 1; s++) {
                ib.put(r * sectors + s).put((r + 1) * sectors + s).put((r + 1) * sectors + s + 1);
                ib.put((r + 1) * sectors + s + 1).put(r * sectors + s + 1).put(r * sectors + s);
            }
        }
        ib.flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        MemoryUtil.memFree(ib);
        glBindVertexArray(0);
    }

    static void createTexture() throws IOException {
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer width = frame.mallocInt(1);
            IntBuffer height = frame.mallocInt(1);
            IntBuffer components = frame.mallocInt(1);
            ByteBuffer data = stbi_load_from_memory(
                    ioResourceToByteBuffer("org/lwjgl/demo/opengl/textures/earth.jpg", 1024), width, height, components,
                    4);
            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width.get(), height.get(), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            stbi_image_free(data);
        }
    }

    void update(float elapsedTime) {
        time += elapsedTime;
        m.setPerspective((float) Math.toRadians(45), (float) width / height, 0.1f, 10.0f)
         .lookAt(0, 1, 3,
                 0, 0, 0,
                 0, 1, 0)
         .rotateY(time * 0.2f);
    }

    void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(sphereProgram);
        glUniformMatrix4fv(sphereProgram_matrixUniform, false, m.get(matrixBuffer));
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, (rings - 1) * (sectors - 1) * 6, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            long thisTime = System.nanoTime();
            float elapsedTime = (thisTime - lastTime) * 1E-9f;
            lastTime = thisTime;
            update(elapsedTime);
            render();
            glfwSwapBuffers(window);
        }
    }

    void run() throws IOException {
        init();
        loop();
        glfwDestroyWindow(window);
        glfwTerminate();
        /* Free memory resources */
        if (debugProc != null)
            debugProc.free();
        keyCallback.free();
        fbsCallback.free();
        GL.setCapabilities(null);
        MemoryUtil.memFree(matrixBuffer);
    }

    public static void main(String[] args) throws IOException {
        new SimpleTexturedSphere().run();
    }

}
