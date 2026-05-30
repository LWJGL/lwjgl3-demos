/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shadow;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;

/**
 * Cascaded shadow maps using {@link Matrix4f#perspectiveFrustumSlice(float, float, Matrix4f) JOML's perspectiveFrustumSlice}
 * and {@link Matrix4f#orthoCrop(org.joml.Matrix4fc, Matrix4f) orthoCrop}.
 * <p>
 * The camera view frustum is split into {@link #NUM_CASCADES} depth slices. For each slice we build the
 * camera sub-frustum's view-projection, invert it (NDC cube -> world) and feed that to
 * {@code orthoCrop(lightView, dest)}, which returns an orthographic projection that tightly encloses the
 * slice as seen from the directional light. Each cascade's depth is rendered into one layer of a
 * {@code GL_TEXTURE_2D_ARRAY}, then sampled in the main pass with a {@code sampler2DArrayShadow} (3x3 PCF).
 *
 * @author Kai Burjack
 */
public class CascadedShadowMappingDemo {

    private static final int NUM_CASCADES = 4;
    private static final int SHADOW_SIZE = 2048;
    private static final float NEAR = 0.1f, FAR = 250.0f;
    private static final float SHADOW_NEAR = 0.1f, SHADOW_FAR = 130.0f;
    private static final float LAMBDA = 0.6f; // practical-split blend (1 = fully logarithmic)
    private static final Vector3f UP = new Vector3f(0, 1, 0);

    private long window;
    private int width = 1200, height = 800;

    private final Vector3f target = new Vector3f(0.0f, 2.0f, 0.0f);
    private float camYaw = 0.6f, camPitch = 0.5f, camDist = 42.0f;
    private double prevX, prevY;
    private boolean dragging;

    private final Vector3f lightDir = new Vector3f(-0.5f, -1.3f, -0.4f);
    private boolean animateLight;
    private boolean visualizeCascades;

    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f camViewProj = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f biasMatrix = new Matrix4f(
            0.5f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.5f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.5f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f);
    private final Matrix4f invFrustum = new Matrix4f();
    private final Matrix4f cropProj = new Matrix4f();
    private final Matrix4f[] lightViewProj = new Matrix4f[NUM_CASCADES];
    private final Matrix4f[] biasLightViewProj = new Matrix4f[NUM_CASCADES];
    private final float[] splitDist = new float[NUM_CASCADES + 1];
    private final float[] cascadeSplitFar = new float[NUM_CASCADES];
    private final Vector3f eye = new Vector3f();
    private final Vector3f tmp = new Vector3f();
    private final Vector3f sceneMin = new Vector3f();
    private final Vector3f sceneMax = new Vector3f();

    private int vao, sceneVertexCount;
    private int depthProgram, shadeProgram;
    private int fbo, depthArray;
    private int dpViewProj;
    private int spViewProj, spView, spLightViewProj, spSplits, spLightDir, spVisualize, spSampler;

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private GLCapabilities caps;
    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private GLFWScrollCallback sCallback;
    private Callback debugProc;

    private void init() throws IOException {
        for (int i = 0; i < NUM_CASCADES; i++) {
            lightViewProj[i] = new Matrix4f();
            biasLightViewProj[i] = new Matrix4f();
        }

        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Cascaded Shadow Mapping Demo", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        printControls();

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;
                switch (key) {
                    case GLFW_KEY_ESCAPE:
                        glfwSetWindowShouldClose(window, true);
                        break;
                    case GLFW_KEY_C:
                        visualizeCascades = !visualizeCascades;
                        System.out.println("Cascade visualization: " + visualizeCascades);
                        break;
                    case GLFW_KEY_L:
                        animateLight = !animateLight;
                        System.out.println("Light animation: " + animateLight);
                        break;
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0 && (CascadedShadowMappingDemo.this.width != w || CascadedShadowMappingDemo.this.height != h)) {
                    CascadedShadowMappingDemo.this.width = w;
                    CascadedShadowMappingDemo.this.height = h;
                }
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    dragging = action == GLFW_PRESS;
                    try (MemoryStack s = stackPush()) {
                        java.nio.DoubleBuffer px = s.mallocDouble(1), py = s.mallocDouble(1);
                        glfwGetCursorPos(window, px, py);
                        prevX = px.get(0);
                        prevY = py.get(0);
                    }
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            public void invoke(long window, double x, double y) {
                if (dragging) {
                    camYaw -= (float) (x - prevX) * 0.001f;
                    camPitch -= (float) (y - prevY) * 0.001f;
                    camPitch = Math.max(0.05f, Math.min(1.5f, camPitch));
                }
                prevX = x;
                prevY = y;
            }
        });
        glfwSetScrollCallback(window, sCallback = new GLFWScrollCallback() {
            public void invoke(long window, double xoffset, double yoffset) {
                camDist *= yoffset > 0 ? 0.9f : 1.0f / 0.9f;
                camDist = Math.max(5.0f, Math.min(150.0f, camDist));
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        try (MemoryStack frame = stackPush()) {
            IntBuffer fb = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(fb), memAddress(fb) + 4);
            width = fb.get(0);
            height = fb.get(1);
        }

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        createSceneVao();
        createPrograms();
        createShadowTextureAndFbo();

        glfwShowWindow(window);
    }

    private void printControls() {
        System.out.println("Cascaded Shadow Mapping Demo (" + NUM_CASCADES + " cascades, orthoCrop, 3x3 PCF)");
        System.out.println("  LMB drag: orbit camera");
        System.out.println("  scroll:   zoom");
        System.out.println("  C:        toggle cascade visualization");
        System.out.println("  L:        toggle light animation");
        System.out.println("  ESC:      quit");
    }

    private void createSceneVao() {
        List<Vector3f> mins = new ArrayList<>();
        List<Vector3f> maxs = new ArrayList<>();
        // Ground
        mins.add(new Vector3f(-60, -1, -60));
        maxs.add(new Vector3f(60, 0, 60));
        // Boxes
        Random rnd = new Random(1234L);
        for (int i = 0; i < 48; i++) {
            float cx = (rnd.nextFloat() * 2 - 1) * 45;
            float cz = (rnd.nextFloat() * 2 - 1) * 45;
            float hw = 0.8f + rnd.nextFloat() * 2.2f;
            float hd = 0.8f + rnd.nextFloat() * 2.2f;
            float h = 1.5f + rnd.nextFloat() * 6.5f;
            mins.add(new Vector3f(cx - hw, 0, cz - hd));
            maxs.add(new Vector3f(cx + hw, h, cz + hd));
        }
        int boxes = mins.size();
        sceneVertexCount = boxes * 6 * 6;

        sceneMin.set(mins.get(0));
        sceneMax.set(maxs.get(0));
        for (int i = 1; i < boxes; i++) {
            sceneMin.min(mins.get(i));
            sceneMax.max(maxs.get(i));
        }

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(boxes * 6 * 6 * (3 + 3) * 4);
        FloatBuffer fv = bb.asFloatBuffer();
        for (int i = 0; i < boxes; i++)
            DemoUtils.triangulateBox(mins.get(i), maxs.get(i), fv);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 4 * (3 + 3), 4 * 3);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private int createProgram(String vs, String fs) throws IOException {
        int p = glCreateProgram();
        int v = DemoUtils.createShader(vs, GL_VERTEX_SHADER);
        int f = DemoUtils.createShader(fs, GL_FRAGMENT_SHADER);
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0)
            throw new AssertionError("Could not link program: " + glGetProgramInfoLog(p));
        return p;
    }

    private void createPrograms() throws IOException {
        String dir = "org/lwjgl/demo/opengl/shadow/";
        depthProgram = createProgram(dir + "cascadedShadowMapping-vs.glsl", dir + "cascadedShadowMapping-fs.glsl");
        dpViewProj = glGetUniformLocation(depthProgram, "viewProjectionMatrix");

        shadeProgram = createProgram(dir + "cascadedShadowMappingShade-vs.glsl", dir + "cascadedShadowMappingShade-fs.glsl");
        spViewProj = glGetUniformLocation(shadeProgram, "viewProjectionMatrix");
        spView = glGetUniformLocation(shadeProgram, "viewMatrix");
        spLightViewProj = glGetUniformLocation(shadeProgram, "lightViewProjection");
        spSplits = glGetUniformLocation(shadeProgram, "cascadeSplits");
        spLightDir = glGetUniformLocation(shadeProgram, "lightDir");
        spVisualize = glGetUniformLocation(shadeProgram, "visualizeCascades");
        spSampler = glGetUniformLocation(shadeProgram, "shadowMap");
        glUseProgram(shadeProgram);
        glUniform1i(spSampler, 0);
        glUseProgram(0);
    }

    private void createShadowTextureAndFbo() {
        depthArray = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, depthArray);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F, SHADOW_SIZE, SHADOW_SIZE, NUM_CASCADES, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depthArray, 0, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("Incomplete shadow FBO: " + status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void update(float time) {
        if (animateLight) {
            float a = time * 0.3f;
            lightDir.set((float) Math.sin(a) * 0.6f, -1.3f, (float) Math.cos(a) * 0.6f);
        }

        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float cy = (float) Math.cos(camYaw), sy = (float) Math.sin(camYaw);
        eye.set(target.x + camDist * cp * sy, target.y + camDist * sp, target.z + camDist * cp * cy);
        float fovy = (float) Math.toRadians(45.0);
        float aspect = (float) width / height;
        projMatrix.setPerspective(fovy, aspect, NEAR, FAR);
        viewMatrix.setLookAt(eye, target, UP);
        camViewProj.set(projMatrix).mul(viewMatrix);

        tmp.set(lightDir).normalize();
        lightView.setLookAt(target.x, target.y, target.z,
                target.x + tmp.x, target.y + tmp.y, target.z + tmp.z, 0, 1, 0);

        for (int i = 0; i <= NUM_CASCADES; i++) {
            float p = (float) i / NUM_CASCADES;
            float logd = SHADOW_NEAR * (float) Math.pow(SHADOW_FAR / SHADOW_NEAR, p);
            float uni = SHADOW_NEAR + (SHADOW_FAR - SHADOW_NEAR) * p;
            splitDist[i] = LAMBDA * logd + (1.0f - LAMBDA) * uni;
        }
        splitDist[0] = SHADOW_NEAR;
        splitDist[NUM_CASCADES] = SHADOW_FAR;

        for (int i = 0; i < NUM_CASCADES; i++) {
            projMatrix
                .perspectiveFrustumSlice(splitDist[i], splitDist[i + 1], invFrustum)
                .mul(viewMatrix)
                .invert()
                .orthoCrop(lightView, cropProj)
                .mul(lightView, lightViewProj[i]);
            biasMatrix.mul(lightViewProj[i], biasLightViewProj[i]);
            cascadeSplitFar[i] = splitDist[i + 1];
        }
    }

    private void renderShadowMaps() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE);
        glEnable(GL_DEPTH_CLAMP);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.0f, 4.0f);
        glUseProgram(depthProgram);
        glBindVertexArray(vao);
        for (int i = 0; i < NUM_CASCADES; i++) {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depthArray, 0, i);
            glClear(GL_DEPTH_BUFFER_BIT);
            glUniformMatrix4fv(dpViewProj, false, lightViewProj[i].get(matrixBuffer));
            glDrawArrays(GL_TRIANGLES, 0, sceneVertexCount);
        }
        glBindVertexArray(0);
        glUseProgram(0);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_DEPTH_CLAMP);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void renderScene() {
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(shadeProgram);
        glUniformMatrix4fv(spViewProj, false, camViewProj.get(matrixBuffer));
        glUniformMatrix4fv(spView, false, viewMatrix.get(matrixBuffer));
        try (MemoryStack s = stackPush()) {
            FloatBuffer mats = s.mallocFloat(16 * NUM_CASCADES);
            for (int i = 0; i < NUM_CASCADES; i++)
                biasLightViewProj[i].get(16 * i, mats);
            glUniformMatrix4fv(spLightViewProj, false, mats);
            FloatBuffer sd = s.mallocFloat(NUM_CASCADES);
            for (int i = 0; i < NUM_CASCADES; i++)
                sd.put(i, cascadeSplitFar[i]);
            glUniform1fv(spSplits, sd);
        }
        tmp.set(lightDir).negate().normalize();
        glUniform3f(spLightDir, tmp.x, tmp.y, tmp.z);
        glUniform1i(spVisualize, visualizeCascades ? 1 : 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, depthArray);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, sceneVertexCount);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glUseProgram(0);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            update((float) glfwGetTime());
            renderShadowMaps();
            renderScene();
            glfwSwapBuffers(window);
        }
    }

    private void run() {
        try {
            init();
            loop();
            if (debugProc != null)
                debugProc.free();
            errCallback.free();
            keyCallback.free();
            fbCallback.free();
            cpCallback.free();
            mbCallback.free();
            sCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new CascadedShadowMappingDemo().run();
    }
}
