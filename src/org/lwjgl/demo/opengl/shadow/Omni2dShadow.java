/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.shadow;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.lang.Math;
import java.nio.*;

import org.joml.*;
import org.lwjgl.*;
import org.lwjgl.demo.opengl.raytracing.*;
import org.lwjgl.demo.opengl.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Omnidirectional 2D shadows using "1D shadow mapping".
 * 
 * @author Kai Burjack
 */
public class Omni2dShadow {

    private static Vector3f[] boxes = Scene.boxes3;

    static Vector3f UP = new Vector3f(0, 0, -1);
    static int shadowMapWidth = 512;
    static Vector3f lightPosition = new Vector3f(0.0f, 0.5f, 0.0f);
    static Vector3f cameraPosition = new Vector3f(-1.0f, 18.0f, 0.0f);
    static Vector3f cameraLookAt = new Vector3f(0.0f, 0.0f, 0.0f);
    static float near = 0.1f;
    static float far = 18.0f;

    long window;
    int width = 1200;
    int height = 800;
    float time = 0.0f;

    int vao, fullScreenVao;
    int shadowProgram;
    int shadowProgramVPUniform;
    int normalProgram;
    int normalProgramBiasUniform;
    int normalProgramVPUniform;
    int normalProgramLVPUniform;
    int normalProgramLightPosition;
    int normalProgramLightLookAt;
    int fbo;
    int depthTexture;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    Matrix4f light = new Matrix4f();
    Matrix4f camera = new Matrix4f();
    Matrix4f biasMatrix = new Matrix4f().scaling(0.5f).translate(1, 1, 1);

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 3.2 or higher.");
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
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Omnidirectional 2D Shadow Mapping Demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (Omni2dShadow.this.width != width || Omni2dShadow.this.height != height)) {
                    Omni2dShadow.this.width = width;
                    Omni2dShadow.this.height = height;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Set some GL states */
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.2f, 0.3f, 0.4f, 1.0f);

        /* Create all needed GL resources */
        createDepthTexture();
        createFbo();
        createVbo();
        createShadowProgram();
        initShadowProgram();
        createNormalProgram();
        initNormalProgram();
    }

    /**
     * Create the texture storing the depth values of the light-render.
     */
    void createDepthTexture() {
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_1D_ARRAY, depthTexture);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_1D_ARRAY, 0, GL_DEPTH24_STENCIL8, shadowMapWidth, 4, 0, GL_DEPTH_COMPONENT,
                GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_1D_ARRAY, 0);
    }

    /**
     * Create the FBO to render the depth values of the light-render into the depth texture.
     */
    void createFbo() {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindTexture(GL_TEXTURE_1D_ARRAY, depthTexture);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glFramebufferTexture(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depthTexture, 0);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindTexture(GL_TEXTURE_1D_ARRAY, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Creates a VBO for the scene with some boxes.
     */
    void createVbo() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(boxes.length * 4 * (3 + 3) * 6 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        for (int i = 0; i < boxes.length; i += 2) {
            DemoUtils.triangulateBox(boxes[i], boxes[i + 1], fv);
        }
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 4 * (3 + 3), 4 * 3);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    void createShadowProgram() throws IOException {
        shadowProgram = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-vs.glsl", GL_VERTEX_SHADER);
        int gshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-gs.glsl", GL_GEOMETRY_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(shadowProgram, vshader);
        glAttachShader(shadowProgram, gshader);
        glAttachShader(shadowProgram, fshader);
        glBindAttribLocation(shadowProgram, 0, "position");
        glLinkProgram(shadowProgram);
        int linked = glGetProgrami(shadowProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(shadowProgram);
        if (programLog != null && programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
    }

    void initShadowProgram() {
        glUseProgram(shadowProgram);
        shadowProgramVPUniform = glGetUniformLocation(shadowProgram, "viewProjectionMatrix");
        glUseProgram(0);
    }

    void createNormalProgram() throws IOException {
        normalProgram = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadowShade-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadowShade-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(normalProgram, vshader);
        glAttachShader(normalProgram, fshader);
        glBindAttribLocation(normalProgram, 0, "position");
        glBindAttribLocation(normalProgram, 1, "normal");
        glBindFragDataLocation(normalProgram, 0, "color");
        glLinkProgram(normalProgram);
        int linked = glGetProgrami(normalProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(normalProgram);
        if (programLog != null && programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
    }

    void initNormalProgram() {
        glUseProgram(normalProgram);
        int depthMapsUniform = glGetUniformLocation(normalProgram, "depthMaps");
        normalProgramBiasUniform = glGetUniformLocation(normalProgram, "biasMatrix");
        normalProgramVPUniform = glGetUniformLocation(normalProgram, "viewProjectionMatrix");
        normalProgramLVPUniform = glGetUniformLocation(normalProgram, "lightViewProjectionMatrices");
        normalProgramLightPosition = glGetUniformLocation(normalProgram, "lightPosition");
        glUniform1i(depthMapsUniform, 0);
        glUseProgram(0);
    }

    void update() {
        lightPosition.set((float) Math.sin(time), 0.5f, (float) Math.cos(time));
        camera.setPerspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 30.0f).lookAt(cameraPosition,
                cameraLookAt, UP);
    }

    Matrix4f frustum(Matrix4f m) {
        float yDist = 1E-4f;
        return m.setFrustum(-near, near, -yDist, yDist, near, far);
    }

    void loadLightViewProjection(int uniform) {
        glUniformMatrix4fv(uniform + 0, false, frustum(light).rotateY(0)
                .translate(-lightPosition.x, -lightPosition.y, -lightPosition.z).get(matrixBuffer));
        glUniformMatrix4fv(uniform + 1, false, frustum(light).rotateY((float) Math.PI * 0.5f)
                .translate(-lightPosition.x, -lightPosition.y, -lightPosition.z).get(matrixBuffer));
        glUniformMatrix4fv(uniform + 2, false, frustum(light).rotateY((float) Math.PI)
                .translate(-lightPosition.x, -lightPosition.y, -lightPosition.z).get(matrixBuffer));
        glUniformMatrix4fv(uniform + 3, false, frustum(light).rotateY((float) Math.PI * 1.5f)
                .translate(-lightPosition.x, -lightPosition.y, -lightPosition.z).get(matrixBuffer));
    }

    /**
     * Render the shadow map into a depth texture.
     */
    void renderShadowMap() {
        glUseProgram(shadowProgram);
        /* Set VP matrix of the "light cameras" */
        loadLightViewProjection(shadowProgramVPUniform);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, shadowMapWidth, 1);
        /* Only clear depth buffer, since we don't have a color draw buffer */
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
    }

    /**
     * Render the scene normally, with sampling the previously rendered depth texture.
     */
    void renderNormal() {
        glUseProgram(normalProgram);
        /* Set MVP matrix of camera */
        glUniformMatrix4fv(normalProgramVPUniform, false, camera.get(matrixBuffer));
        /* Set MVP matrix that was used when doing the light-render */
        loadLightViewProjection(normalProgramLVPUniform);
        /* The bias-matrix used to convert to NDC coordinates */
        glUniformMatrix4fv(normalProgramBiasUniform, false, biasMatrix.get(matrixBuffer));
        /* Light position and lookat for normal lambertian computation */
        glUniform3f(normalProgramLightPosition, lightPosition.x, lightPosition.y, lightPosition.z);
        glViewport(0, 0, width, height);
        /* Must clear both color and depth, since we are re-rendering the scene */
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glBindTexture(GL_TEXTURE_1D_ARRAY, depthTexture);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_1D_ARRAY, 0);
        glUseProgram(0);
    }

    void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            long thisTime = System.nanoTime();
            float diff = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            time += diff;
            update();
            renderShadowMap();
            renderNormal();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null)
                debugProc.free();

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
        new Omni2dShadow().run();
    }

}