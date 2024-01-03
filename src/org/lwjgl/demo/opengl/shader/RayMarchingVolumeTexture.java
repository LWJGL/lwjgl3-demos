/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.io.IOException;
import java.nio.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Ray marching a 3D texture using "A Fast Voxel Traversal Algorithm for Ray Tracing" by John Amanatides, Andrew Woo
 * in the fragment shader.
 * We render a box and for each generated fragment we compute the ray from the camera through the fragment and
 * march along the ray until we hit a voxel/texel in the 3D texture with a value > 0.0.
 *
 * @author Kai Burjack
 */
public class RayMarchingVolumeTexture {
    private long window;
    private int width = 1024;
    private int height = 768;

    private int program;
    private int projectionUniform, viewUniform, camPositionUniform;

    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private Callback debugProc;

    private int vao, tex;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final Vector3f camPos = new Vector3f(1.5f, 1.1f, 3.0f);
    private final Matrix4f projMatrix = new Matrix4f().setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
    private final Matrix4f viewMatrix = new Matrix4f();

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Raymarching Demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (RayMarchingVolumeTexture.this.width != width || RayMarchingVolumeTexture.this.height != height)) {
                    RayMarchingVolumeTexture.this.width = width;
                    RayMarchingVolumeTexture.this.height = height;
                    glViewport(0, 0, width, height);
                    projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
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
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        glClearColor(0.1f, 0.15f, 0.23f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        createProgram();
        create3dTexture();
        createBoxVao();
    }
    private void createBoxVao() {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
        glBufferData(GL_ARRAY_BUFFER, new float[] {
            +1, +1, -1, -1, +1, -1, +1, -1, -1, -1, -1, -1,
            +1, +1, +1, -1, +1, +1, -1, -1, +1, +1, -1, +1,
        }, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, glGenBuffers());
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, new short[] {
            3, 2, 6, 7, 4, 2, 0, 3, 1, 6, 5, 4, 1, 0
        }, GL_STATIC_DRAW);
        glBindVertexArray(0);
        this.vao = vao;
    }
    private void create3dTexture() {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, texture);
        int s = 16;
        ByteBuffer bb = BufferUtils.createByteBuffer(s * s * s);
        for (int z = 0; z < s; z++) {
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    Vector3f p = new Vector3f(x - s / 2, y - s / 2, z - s / 2);
                    if (p.lengthSquared() < s/2.5*s/2.5) {
                        bb.put((byte) 255);
                    } else {
                        bb.put((byte) 0);
                    }
                }
            }
        }
        bb.flip();
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, s, s, s, 0, GL_RED, GL_UNSIGNED_BYTE, bb);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.tex = texture;
    }
    private void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/raymarching.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/raymarching.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (!programLog.trim().isEmpty()) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.program = program;
        glUseProgram(program);
        projectionUniform = glGetUniformLocation(program, "projection");
        viewUniform = glGetUniformLocation(program, "view");
        camPositionUniform = glGetUniformLocation(program, "camPosition");
        int texUniform = glGetUniformLocation(program, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }
    private void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            glfwPollEvents();
            camPos.rotateY(dt*0.3f);
            viewMatrix.setLookAt(camPos.x, camPos.y, camPos.z, 0, -0.3f, 0, 0, 1, 0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glUseProgram(this.program);
            glUniformMatrix4fv(projectionUniform, false, projMatrix.get(matrixBuffer));
            glUniformMatrix4fv(viewUniform, false, viewMatrix.get(matrixBuffer));
            glUniform3f(camPositionUniform, camPos.x, camPos.y, camPos.z);
            glBindVertexArray(vao);
            glBindTexture(GL_TEXTURE_3D, tex);
            glDrawElements(GL_TRIANGLE_STRIP, 14, GL_UNSIGNED_SHORT, 0L);
            glUseProgram(0);
            glfwSwapBuffers(window);
        }
    }
    private void run() {
        try {
            init();
            loop();
            if (debugProc != null) {
                debugProc.free();
            }
            errCallback.free();
            fbCallback.free();
            keyCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new RayMarchingVolumeTexture().run();
    }
}
