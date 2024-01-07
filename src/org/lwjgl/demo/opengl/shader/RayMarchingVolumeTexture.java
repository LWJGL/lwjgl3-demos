/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static java.lang.ClassLoader.getSystemResourceAsStream;
import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.util.MagicaVoxelLoader;
import org.lwjgl.glfw.*;
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
    private static class Volume {
        int x, y, z;
        int w, h, d;
        int tw, th, td;
        int tex;
        byte[] field;
    }

    private long window;
    private int width = 1024;
    private int height = 768;

    private int program;
    private int mvpUniform, invModelUniform, camPositionUniform, sizeUniform;

    private GLFWErrorCallback errCallback;
    private Callback debugProc;

    private int vao;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f().setLookAt(100, 20, 100, 100, 0, 0, 0, 1, 0);
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f mvpMatrix = new Matrix4f();
    private final Matrix4f invModelMatrix = new Matrix4f();
    private final List<Volume> volumes = new ArrayList<>();
    private float mouseX, mouseY;
    private boolean mouseDown;
    private final boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];

    private void update(float dt) {
        float factor = 20.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor *= 3.0f;
        if (keydown[GLFW_KEY_W]) {
            viewMatrix.translateLocal(0, 0, factor * dt);
        }
        if (keydown[GLFW_KEY_S]) {
            viewMatrix.translateLocal(0, 0, -factor * dt);
        }
        if (keydown[GLFW_KEY_A]) {
            viewMatrix.translateLocal(factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_D]) {
            viewMatrix.translateLocal(-factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_Q]) {
            viewMatrix.rotateLocalZ(-dt);
        }
        if (keydown[GLFW_KEY_E]) {
            viewMatrix.rotateLocalZ(dt);
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            viewMatrix.translateLocal(0, factor * dt, 0);
        }
        if (keydown[GLFW_KEY_SPACE]) {
            viewMatrix.translateLocal(0, -factor * dt, 0);
        }
    }

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
        glfwSetKeyCallback(window, (long window, int key, int scancode, int action, int mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
            if (key >= 0)
                if (action == GLFW_PRESS)
                    keydown[key] = true;
                else if (action == GLFW_RELEASE)
                    keydown[key] = false;
        });
        glfwSetCursorPosCallback(window, (long window, double x, double y) -> {
            if (mouseDown) {
                float deltaX = (float) x - RayMarchingVolumeTexture.this.mouseX;
                float deltaY = (float) y - RayMarchingVolumeTexture.this.mouseY;
                RayMarchingVolumeTexture.this.viewMatrix.rotateLocalY(deltaX * 0.01f);
                RayMarchingVolumeTexture.this.viewMatrix.rotateLocalX(deltaY * 0.01f);
            }
            RayMarchingVolumeTexture.this.mouseX = (float) x;
            RayMarchingVolumeTexture.this.mouseY = (float) y;
        });
        glfwSetMouseButtonCallback(window, (long window, int button, int action, int mods) -> {
            if (action == GLFW_PRESS) {
                RayMarchingVolumeTexture.this.mouseDown = true;
            } else if (action == GLFW_RELEASE) {
                RayMarchingVolumeTexture.this.mouseDown = false;
            }
        });
        glfwSetFramebufferSizeCallback(window, (long window, int width, int height) -> {
            if (width > 0 && height > 0 && (RayMarchingVolumeTexture.this.width != width || RayMarchingVolumeTexture.this.height != height)) {
                RayMarchingVolumeTexture.this.width = width;
                RayMarchingVolumeTexture.this.height = height;
                glViewport(0, 0, width, height);
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
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        glClearColor(0.1f, 0.15f, 0.23f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_FRONT);
        createProgram();
        volumes.add(create3dVolume("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house5.vox", 0, 0, 0));
        volumes.add(create3dVolume("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house6.vox", 140, 0, 0));
        createBoxVao();
        glfwShowWindow(window);
    }
    private void createBoxVao() {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
        float s = 0.5f;
        glBufferData(GL_ARRAY_BUFFER, new float[] {
            +s, +s, -s, -s, +s, -s, +s, -s, -s, -s, -s, -s,
            +s, +s, +s, -s, +s, +s, -s, -s, +s, +s, -s, +s,
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
    private static int idx(int x, int y, int z, int width, int height) {
        return x + width * (y + z * height);
    }
    private static int nextPowerOfTwo(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        return (v | v >> 16) + 1;
    }
    private Volume create3dVolume(String resource, int x, int y, int z) throws IOException {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, texture);
        Volume v = new Volume();
        v.x = x;
        v.y = y;
        v.z = z;
        try (InputStream is = Objects.requireNonNull(getSystemResourceAsStream(resource))) {
            BufferedInputStream bis = new BufferedInputStream(is);
            new MagicaVoxelLoader().read(bis, new MagicaVoxelLoader.Callback() {
                public void voxel(int x, int y, int z, byte c) {
                    v.field[idx(x, z, v.td - 1 - y, v.tw, v.th)] = c;
                }
                public void size(int x, int y, int z) {
                    v.tw = nextPowerOfTwo(x);
                    v.th = nextPowerOfTwo(z);
                    v.td = nextPowerOfTwo(y);
                    v.field = new byte[v.tw * v.th * v.td];
                    v.w = x;
                    v.h = z;
                    v.d = y;
                }
                public void paletteMaterial(int i, MagicaVoxelLoader.Material mat) {
                }
            });
        }
        ByteBuffer bb = BufferUtils.createByteBuffer(v.field.length);
        bb.put(v.field);
        bb.flip();
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, v.tw, v.th, v.td, 0, GL_RED, GL_UNSIGNED_BYTE, bb);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenerateMipmap(GL_TEXTURE_3D);
        v.tex = texture;
        return v;
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
        mvpUniform = glGetUniformLocation(program, "mvp");
        invModelUniform = glGetUniformLocation(program, "invModel");
        camPositionUniform = glGetUniformLocation(program, "camPosition");
        sizeUniform = glGetUniformLocation(program, "size");
        int texUniform = glGetUniformLocation(program, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }
    private void renderVolume(Volume v) {
        glBindTexture(GL_TEXTURE_3D, v.tex);
        modelMatrix.translation(v.x, v.y, v.z).scale(v.w, v.h, v.d);
        projMatrix.mul(viewMatrix, mvpMatrix).mul(modelMatrix);
        glUniformMatrix4fv(mvpUniform, false, mvpMatrix.get(matrixBuffer));
        modelMatrix.invert(invModelMatrix);
        glUniformMatrix4fv(invModelUniform, false, invModelMatrix.get(matrixBuffer));
        glUniform3f(sizeUniform, v.w, v.h, v.d);
        glDrawElements(GL_TRIANGLE_STRIP, 14, GL_UNSIGNED_SHORT, 0L);
    }
    private void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            update(dt);
            lastTime = thisTime;
            glfwPollEvents();
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.1f, 1000.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glUseProgram(this.program);
            Vector3f camPos = viewMatrix.originAffine(new Vector3f());
            glUniform3f(camPositionUniform, camPos.x, camPos.y, camPos.z);
            glBindVertexArray(vao);
            for (Volume volume : volumes)
                renderVolume(volume);
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
