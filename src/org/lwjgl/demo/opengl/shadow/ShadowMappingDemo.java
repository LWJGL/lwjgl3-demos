/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shadow;

import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.opengl.raytracing.Scene;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Simple demo to showcase shadow mapping with a custom shader doing perspective
 * divide and depth test (i.e. no sampler2DShadow).
 * 
 * @author Kai Burjack
 */
public class ShadowMappingDemo {

    private static Vector3f[] boxes = Scene.boxes2;
    private static Vector3f UP = new Vector3f(0.0f, 1.0f, 0.0f);

    static int shadowMapSize = 1024;
    static Vector3f lightPosition = new Vector3f(6.0f, 3.0f, 6.0f);
    static Vector3f lightLookAt = new Vector3f(0.0f, 1.0f, 0.0f);
    static Vector3f cameraPosition = new Vector3f(-3.0f, 6.0f, 6.0f);
    static Vector3f cameraLookAt = new Vector3f(0.0f, 0.0f, 0.0f);
    static float lightDistance = 10.0f;
    static float lightHeight = 4.0f;

    long window;
    int width = 1200;
    int height = 800;

    int vao;
    int vbo;
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
    int samplerLocation;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    Matrix4f light = new Matrix4f();
    Matrix4f camera = new Matrix4f();
    Matrix4f biasMatrix = new Matrix4f(
            0.5f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.5f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.5f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f
            );

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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Shadow Mapping Demo", NULL, NULL);
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
                if (width > 0 && height > 0 && (ShadowMappingDemo.this.width != width || ShadowMappingDemo.this.height != height)) {
                    ShadowMappingDemo.this.width = width;
                    ShadowMappingDemo.this.height = height;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Set some GL states */
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.2f, 0.3f, 0.4f, 1.0f);

        /* Create all needed GL resources */
        createVao();
        createShadowProgram();
        initShadowProgram();
        createNormalProgram();
        initNormalProgram();
        createDepthTexture();
        createFbo();
    }

    /**
     * Create the texture storing the depth values of the light-render.
     */
    void createDepthTexture() {
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, shadowMapSize, shadowMapSize, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the FBO to render the depth values of the light-render into the
     * depth texture.
     */
    void createFbo() {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Creates a VAO for the scene with some boxes.
     */
    void createVao() {
        vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
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

    static int createShader(String resource, int type) throws IOException {
        int shader = glCreateShader(type);

        ByteBuffer source = ioResourceToByteBuffer(resource, 8192);

        PointerBuffer strings = BufferUtils.createPointerBuffer(1);
        IntBuffer lengths = BufferUtils.createIntBuffer(1);

        strings.put(0, source);
        lengths.put(0, source.remaining());

        glShaderSource(shader, strings, lengths);
        glCompileShader(shader);
        int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
        String shaderLog = glGetShaderInfoLog(shader);
        if (shaderLog.trim().length() > 0) {
            System.err.println(shaderLog);
        }
        if (compiled == 0) {
            throw new AssertionError("Could not compile shader");
        }
        return shader;
    }

    void createShadowProgram() throws IOException {
        shadowProgram = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/shadowMapping-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/shadowMapping-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(shadowProgram, vshader);
        glAttachShader(shadowProgram, fshader);
        glBindAttribLocation(shadowProgram, 0, "position");
        glLinkProgram(shadowProgram);
        int linked = glGetProgrami(shadowProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(shadowProgram);
        if (programLog.trim().length() > 0) {
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
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/shadowMappingShade-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/shadowMappingShade-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(normalProgram, vshader);
        glAttachShader(normalProgram, fshader);
        glBindAttribLocation(normalProgram, 0, "position");
        glBindAttribLocation(normalProgram, 1, "normal");
        glLinkProgram(normalProgram);
        int linked = glGetProgrami(normalProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(normalProgram);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
    }

    void initNormalProgram() {
        glUseProgram(normalProgram);
        samplerLocation = glGetUniformLocation(normalProgram, "depthTexture");
        normalProgramBiasUniform = glGetUniformLocation(normalProgram, "biasMatrix");
        normalProgramVPUniform = glGetUniformLocation(normalProgram, "viewProjectionMatrix");
        normalProgramLVPUniform = glGetUniformLocation(normalProgram, "lightViewProjectionMatrix");
        normalProgramLightPosition = glGetUniformLocation(normalProgram, "lightPosition");
        normalProgramLightLookAt = glGetUniformLocation(normalProgram, "lightLookAt");
        glUniform1i(samplerLocation, 0);
        glUseProgram(0);
    }

    /**
     * Update the camera MVP matrix.
     */
    void update() {
        /* Update light */
        double alpha = System.currentTimeMillis() / 1000.0 * 0.5;
        float x = (float) Math.sin(alpha);
        float z = (float) Math.cos(alpha);
        lightPosition.set(lightDistance * x, 3 + (float) Math.sin(alpha), lightDistance * z);
        light.setPerspective((float) Math.toRadians(45.0f), 1.0f, 0.1f, 60.0f)
             .lookAt(lightPosition, lightLookAt, UP);

        /* Update camera */
        camera.setPerspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 30.0f)
                .lookAt(cameraPosition, cameraLookAt, UP);
    }

    /**
     * Render the shadow map into a depth texture.
     */
    void renderShadowMap() {
        glUseProgram(shadowProgram);

        /* Set MVP matrix of the "light camera" */
        glUniformMatrix4fv(shadowProgramVPUniform, false, light.get(matrixBuffer));

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, shadowMapSize, shadowMapSize);
        /* Only clear depth buffer, since we don't have a color draw buffer */
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        glUseProgram(0);
    }

    /**
     * Render the scene normally, with sampling the previously rendered depth
     * texture.
     */
    void renderNormal() {
        glUseProgram(normalProgram);

        /* Set MVP matrix of camera */
        glUniformMatrix4fv(normalProgramVPUniform, false, camera.get(matrixBuffer));
        /* Set MVP matrix that was used when doing the light-render */
        glUniformMatrix4fv(normalProgramLVPUniform, false, light.get(matrixBuffer));
        /* The bias-matrix used to convert to NDC coordinates */
        glUniformMatrix4fv(normalProgramBiasUniform, false, biasMatrix.get(matrixBuffer));
        /* Light position and lookat for normal lambertian computation */
        glUniform3f(normalProgramLightPosition, lightPosition.x, lightPosition.y, lightPosition.z);
        glUniform3f(normalProgramLightLookAt, lightLookAt.x, lightLookAt.y, lightLookAt.z);

        glViewport(0, 0, width, height);
        /* Must clear both color and depth, since we are re-rendering the scene */
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);

        glUseProgram(0);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

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
        new ShadowMappingDemo().run();
    }

}