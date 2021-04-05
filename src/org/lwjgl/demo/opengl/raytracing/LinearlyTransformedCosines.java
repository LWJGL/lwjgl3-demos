/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This demo is an implementation of the paper
 * <a href="https://eheitzresearch.wordpress.com/415-2/">Real-Time
 * Polygonal-Light Shading with Linearly Transformed Cosines</a> from Eric
 * Heitz, Jonathan Dupuy, Stephen Hill and David Neubelt to analytically compute
 * the direct contribution of the rectangular light without shadows.
 * 
 * @author Kai Burjack
 */
public class LinearlyTransformedCosines {
    private long window;
    private int width = 800;
    private int height = 600;
    private boolean resetFramebuffer = true;
    private int tex;
    private int ltcMatTexture, ltcMagTexture;
    private int vao;
    private int computeProgram;
    private int quadProgram;
    private int sampler;
    private int eyeUniform;
    private int ray00Uniform, ray10Uniform, ray01Uniform, ray11Uniform;
    private int roughnessUniform;
    private int framebufferImageBinding;
    private int workGroupSizeX, workGroupSizeY;
    private float mouseX, mouseY;
    private boolean mouseDown;
    private float roughness = 0.5f;
    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraPosition = new Vector3f(-4.0f, 3.0f, -4.0f);
    private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);

    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private Callback debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Linearly Transformed Cosines with Multiple Lights", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        System.out.println("Press WSAD, LCTRL, SPACE to move around in the scene.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Press PAGEUP/PAGEDOWN to increase/decrease the roughness.");
        System.out.println("Move the mouse to look around.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                if (key == GLFW_KEY_PAGE_UP && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    roughness += 0.01f;
                    if (roughness > 1.0f)
                        roughness = 1.0f;
                    System.out.println("Roughness = " + roughness);
                } else if (key == GLFW_KEY_PAGE_DOWN && (action == GLFW_RELEASE || action == GLFW_REPEAT)) {
                    roughness -= 0.01f;
                    if (roughness < 0.0f)
                        roughness = 0.0f;
                    System.out.println("Roughness = " + roughness);
                }
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (LinearlyTransformedCosines.this.width != width || LinearlyTransformedCosines.this.height != height)) {
                    LinearlyTransformedCosines.this.width = width;
                    LinearlyTransformedCosines.this.height = height;
                    LinearlyTransformedCosines.this.resetFramebuffer = true;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - LinearlyTransformedCosines.this.mouseX;
                    float deltaY = (float) y - LinearlyTransformedCosines.this.mouseY;
                    LinearlyTransformedCosines.this.viewMatrix.rotateLocalY(deltaX * 0.01f);
                    LinearlyTransformedCosines.this.viewMatrix.rotateLocalX(deltaY * 0.01f);
                }
                LinearlyTransformedCosines.this.mouseX = (float) x;
                LinearlyTransformedCosines.this.mouseY = (float) y;
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    LinearlyTransformedCosines.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    LinearlyTransformedCosines.this.mouseDown = false;
                }
            }
        });
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);
        createFramebufferTexture();
        createLtcMatTexture();
        createSampler();
        this.vao = glGenVertexArrays();
        createComputeProgram();
        createQuadProgram();

        glfwShowWindow(window);
    }

    private void createLtcMatTexture() throws IOException {
        try (MemoryStack frame = MemoryStack.stackPush()) {
            ltcMatTexture = glGenTextures();
            int size = 16;
            glBindTexture(GL_TEXTURE_2D, ltcMatTexture);
            ByteBuffer data = ioResourceToByteBuffer("org/lwjgl/demo/opengl/raytracing/linearlytransformedcosines/ltc1.data",
                    64 * 1024);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, size, size);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_FLOAT, data);
            ltcMagTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, ltcMagTexture);
            data = ioResourceToByteBuffer("org/lwjgl/demo/opengl/raytracing/linearlytransformedcosines/ltc2.data",
                    64 * 1024);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, size, size);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_FLOAT, data);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/linearlytransformedcosines/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/linearlytransformedcosines/quad.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.quadProgram = program;
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    private void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int raytracing = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/linearlytransformedcosines/raytracing.glsl", GL_COMPUTE_SHADER);
        glAttachShader(program, raytracing);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.computeProgram = program;
        glUseProgram(computeProgram);
        IntBuffer workGroupSize = BufferUtils.createIntBuffer(3);
        glGetProgramiv(computeProgram, GL_COMPUTE_WORK_GROUP_SIZE, workGroupSize);
        workGroupSizeX = workGroupSize.get(0);
        workGroupSizeY = workGroupSize.get(1);
        eyeUniform = glGetUniformLocation(computeProgram, "eye");
        ray00Uniform = glGetUniformLocation(computeProgram, "ray00");
        ray10Uniform = glGetUniformLocation(computeProgram, "ray10");
        ray01Uniform = glGetUniformLocation(computeProgram, "ray01");
        ray11Uniform = glGetUniformLocation(computeProgram, "ray11");
        roughnessUniform = glGetUniformLocation(computeProgram, "roughness");
        IntBuffer params = BufferUtils.createIntBuffer(1);
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);
        glUseProgram(0);
    }

    private void createFramebufferTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(tex);
        createFramebufferTexture();
    }

    private void update(float dt) {
        float factor = 1.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 3.0f;
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
            viewMatrix.rotateLocalZ(-factor * dt);
        }
        if (keydown[GLFW_KEY_E]) {
            viewMatrix.rotateLocalZ(factor * dt);
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            viewMatrix.translateLocal(0, factor * dt, 0);
        }
        if (keydown[GLFW_KEY_SPACE]) {
            viewMatrix.translateLocal(0, -factor * dt, 0);
        }
        viewMatrix.withLookAtUp(0, 1, 0);
    }

    private void trace() {
        glUseProgram(computeProgram);
        if (resetFramebuffer) {
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 1f, 2f);
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
        glUniform1f(roughnessUniform, roughness);
        projMatrix.invertPerspectiveView(viewMatrix, invViewProjMatrix);
        viewMatrix.originAffine(cameraPosition);
        glUniform3f(eyeUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray00Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray01Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray10Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray11Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, ltcMatTexture);
        glBindSampler(0, sampler);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, ltcMagTexture);
        glBindSampler(1, sampler);
        glBindImageTexture(framebufferImageBinding, tex, 0, false, 0, GL_READ_WRITE, GL_RGBA8);
        int numGroupsX = (int) Math.ceil((double) width / workGroupSizeX);
        int numGroupsY = (int) Math.ceil((double) height / workGroupSizeY);
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA8);
        glUseProgram(0);
    }

    private void present() {
        glUseProgram(quadProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(0, this.sampler);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void loop() {
        float lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            float thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            glfwPollEvents();
            glViewport(0, 0, width, height);
            update(dt);
            trace();
            present();
            glfwSwapBuffers(window);
        }
    }

    private void run() throws Exception {
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
            glfwDestroyWindow(window);
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) throws Exception {
        new LinearlyTransformedCosines().run();
    }
}
