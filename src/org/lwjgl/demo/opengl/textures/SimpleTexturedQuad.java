/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Renders a simple textured quad using OpenGL 3.0.
 * 
 * @author Kai Burjack
 */
public class SimpleTexturedQuad {

    long window;
    int width = 1024;
    int height = 768;

    int vao;
    int quadProgram;
    int quadProgram_inputPosition;
    int quadProgram_inputTextureCoords;

    GLCapabilities caps;
    GLFWKeyCallback keyCallback;
    Callback debugProc;

    void init() throws IOException {
        glfwInit();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        window = glfwCreateWindow(width, height, "Simple textured quad", NULL, NULL);
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwMakeContextCurrent(window);
        glfwShowWindow(window);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        createTexture();
        createQuadProgram();
        createFullScreenQuad();
    }

    void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/texturedQuad.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/texturedQuad.fs", GL_FRAGMENT_SHADER);
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
        quadProgram_inputPosition = glGetAttribLocation(program, "position");
        quadProgram_inputTextureCoords = glGetAttribLocation(program, "texCoords");
        glUseProgram(0);
        this.quadProgram = program;
    }

    void createFullScreenQuad() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int positionVbo = glGenBuffers();
        FloatBuffer fb = BufferUtils.createFloatBuffer(2 * 6);
        fb.put(-1.0f).put(-1.0f);
        fb.put(1.0f).put(-1.0f);
        fb.put(1.0f).put(1.0f);
        fb.put(1.0f).put(1.0f);
        fb.put(-1.0f).put(1.0f);
        fb.put(-1.0f).put(-1.0f);
        fb.flip();
        glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(quadProgram_inputPosition, 2, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(quadProgram_inputPosition);
        int texCoordsVbo = glGenBuffers();
        fb = BufferUtils.createFloatBuffer(2 * 6);
        fb.put(0.0f).put(1.0f);
        fb.put(1.0f).put(1.0f);
        fb.put(1.0f).put(0.0f);
        fb.put(1.0f).put(0.0f);
        fb.put(0.0f).put(0.0f);
        fb.put(0.0f).put(1.0f);
        fb.flip();
        glBindBuffer(GL_ARRAY_BUFFER, texCoordsVbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(quadProgram_inputTextureCoords, 2, GL_FLOAT, true, 0, 0L);
        glEnableVertexAttribArray(quadProgram_inputTextureCoords);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    static void createTexture() throws IOException {
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        IntBuffer components = BufferUtils.createIntBuffer(1);
        ByteBuffer data = stbi_load_from_memory(ioResourceToByteBuffer("org/lwjgl/demo/opengl/textures/environment.jpg", 1024), width, height, components, 4);
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width.get(), height.get(), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        stbi_image_free(data);
    }

    void render() {
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(quadProgram);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
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

    void run() throws IOException {
        init();
        loop();
        if (debugProc != null)
            debugProc.free();
        keyCallback.free();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public static void main(String[] args) throws IOException {
        new SimpleTexturedQuad().run();
    }

}
