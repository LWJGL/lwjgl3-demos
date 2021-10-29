/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static java.lang.Math.*;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.joml.Random;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Computes 3 mip levels of a texture using only a single compute shader dispatch
 * and GL_KHR_shader_subgroup.
 * Then uses shared memory for mips 4 and 5.
 * 
 * @author Kai Burjack
 */
public class DownsamplingDemo {

    private static long window;
    private static int width = 1024;
    private static int height = 768;
    private static boolean resetTexture;

    private static int nullVao;
    private static int computeProgram;
    private static int quadProgram;
    private static int texture;
    private static int levelUniform;
    private static int level;

    private static Callback debugProc;

    private static void createNullVao() {
        nullVao = glGenVertexArrays();
    }

    private static void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/shader/downsampling/quad.vs.glsl",
                GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/shader/downsampling/quad.fs.glsl",
                GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        glDetachShader(program, vshader);
        glDetachShader(program, fshader);
        glDeleteShader(vshader);
        glDeleteShader(fshader);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        int texUniform = glGetUniformLocation(program, "tex");
        levelUniform = glGetUniformLocation(program, "level");
        glUseProgram(program);
        glUniform1i(texUniform, 0);
        glUseProgram(0);
        quadProgram = program;
    }

    private static void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/shader/downsampling/downsample.cs.glsl",
                GL_COMPUTE_SHADER);
        glAttachShader(program, cshader);
        glLinkProgram(program);
        glDetachShader(program, cshader);
        glDeleteShader(cshader);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        computeProgram = program;
    }

    private static final Random rnd = new Random(1L);
    private static byte v(int i) {
        return (byte) (rnd.nextFloat() * 255);
    }

    private static void createTextures() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 6);
        glTexStorage2D(GL_TEXTURE_2D, 6, GL_RGBA16F, width, height);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        // fill the first level of the texture with some pattern
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels.put(v(x)).put(v(y)).put(v(x)).put((byte) 255);
        pixels.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        MemoryUtil.memFree(pixels);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static void downsample() {
        glUseProgram(computeProgram);

        // read mip level 0
        glBindTexture(GL_TEXTURE_2D, texture);
        // write mip levels 1-5
        glBindImageTexture(0, texture, 1, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(1, texture, 2, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(2, texture, 3, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(3, texture, 4, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(4, texture, 5, false, 0, GL_WRITE_ONLY, GL_RGBA16F);

        int texelsPerWorkItem = 2;
        int numGroupsX = (int) ceil((double) width / texelsPerWorkItem / 16);
        int numGroupsY = (int) ceil((double) height / texelsPerWorkItem / 16);

        glDispatchCompute(numGroupsX, numGroupsY, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindImageTexture(0, 0, 1, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(1, 0, 2, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(2, 0, 3, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(3, 0, 3, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(4, 0, 3, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glUseProgram(0);
    }

    private static void present() {
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport(0, 0, width, height);
        glUseProgram(quadProgram);
        glUniform1i(levelUniform, level);
        glBindVertexArray(nullVao);
        glBindTexture(GL_TEXTURE_2D, texture);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private static void init() throws IOException {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        System.out.println("Press arrow up/down to increase/decrease the viewed mip level");
        window = glfwCreateWindow(width, height, "Downsampling Demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }
        glfwSetKeyCallback(window, (wnd, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                glfwSetWindowShouldClose(window, true);
            else if (key == GLFW_KEY_UP && action == GLFW_RELEASE)
                level = min(5, level + 1);
            else if (key == GLFW_KEY_DOWN && action == GLFW_RELEASE)
                level = max(0, level - 1);
        });
        glfwSetFramebufferSizeCallback(window, (wnd, w, h) -> {
            if (w > 0 && h > 0 && (width != w || height != h)) {
                width = w;
                height = h;
                resetTexture = true;
            }
        });

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        glfwMakeContextCurrent(window);

        GLCapabilities caps = GL.createCapabilities();
        // Check required extensions
        if (!caps.GL_KHR_shader_subgroup)
            throw new AssertionError("GL_KHR_shader_subgroup is required but not supported");

        debugProc = GLUtil.setupDebugMessageCallback();

        createTextures();
        createNullVao();
        createComputeProgram();
        createQuadProgram();

        glfwShowWindow(window);
    }

    private static void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            if (resetTexture) {
                glDeleteTextures(texture);
                createTextures();
                resetTexture = false;
            }
            downsample();
            present();
            glfwSwapBuffers(window);
        }
    }

    private static void destroy() {
        if (debugProc != null)
            debugProc.free();
        glfwDestroyWindow(window);
        glfwFreeCallbacks(window);
        glfwTerminate();
    }

    public static void main(String[] args) throws IOException {
        init();
        loop();
        destroy();
    }

}