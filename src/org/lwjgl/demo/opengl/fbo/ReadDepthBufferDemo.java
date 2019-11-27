/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.fbo;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.raytracing.Scene;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases simple reconstruction of the world position from the depth buffer.
 * <p>
 * It uses a depth attachment texture to render depth-only to the FBO.
 * Afterwards, the view-space or world-space coordinates are reconstructed
 * via the depth values from the depth texture.
 * <p>
 * In order to do this, first the inverse of either the view-projection matrix
 * is computed (for world-space reconstruction) or the inverse of the projection
 * matrix (for view-space reconstruction). This matrix is uploaded to a shader.
 * The fragment shader reads the depth values from the depth buffer and 
 * transforms those values by the computed matrix.
 * 
 * @author Kai Burjack
 */
public class ReadDepthBufferDemo {

    /**
     * The scene as (min, max) axis-aligned boxes.
     */
    private static Vector3f[] boxes = Scene.boxes;

    private long window;
    private int width = 1024;
    private int height = 768;
    private boolean resetFramebuffer = true;

    private int depthTexture;
    private int fullScreenQuadVbo;
    private int fullScreenQuadProgram;
    private int depthOnlyProgram;
    private int fbo;
    private int vboScene;

    private int viewProjMatrixUniform;
    private int inverseMatrixUniform;

    private Matrix4f camera = new Matrix4f();
    private Matrix4f invCamera = new Matrix4f();
    private float mouseDownX;
    private float mouseX;
    private boolean mouseDown;
    private boolean reconstructViewSpace;

    private float currRotationAboutY = 0.0f;
    private float rotationAboutY = 0.0f;

    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWMouseButtonCallback mbCallback;
    Callback debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 2.0 or higher.");
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Sample depth buffer", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Press key 'V' to toggle between view-space and world-space reconstruction.");

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_V) {
                    reconstructViewSpace = !reconstructViewSpace;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                ReadDepthBufferDemo.this.mouseX = (float) x;
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (ReadDepthBufferDemo.this.width != width || ReadDepthBufferDemo.this.height != height)) {
                    ReadDepthBufferDemo.this.width = width;
                    ReadDepthBufferDemo.this.height = height;
                    ReadDepthBufferDemo.this.resetFramebuffer = true;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    ReadDepthBufferDemo.this.mouseDownX = ReadDepthBufferDemo.this.mouseX;
                    ReadDepthBufferDemo.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    ReadDepthBufferDemo.this.mouseDown = false;
                    ReadDepthBufferDemo.this.rotationAboutY = ReadDepthBufferDemo.this.currRotationAboutY;
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

        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_EXT_framebuffer_object) {
            throw new AssertionError("This demo requires the EXT_framebuffer_object extension");
        }
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        createDepthTexture();
        createFramebufferObject();
        createFullScreenVbo();
        createSceneVbo();
        createDepthOnlyProgram();
        createFullScreenQuadProgram();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void createFullScreenVbo() {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put(1.0f).put(-1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(-1.0f).put(1.0f);
        fv.put(-1.0f).put(-1.0f);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        fullScreenQuadVbo = vbo;
    }

    private void createSceneVbo() {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(boxes.length / 2 * 4 * (3 + 3) * 6 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        for (int i = 0; i < boxes.length; i += 2) {
            DemoUtils.triangulateBox(boxes[i], boxes[i + 1], fv);
        }
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        this.vboScene = vbo;
    }

    private void createFramebufferObject() {
        this.fbo = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glDrawBuffer(GL_NONE); // we are not rendering to color buffers!
        glReadBuffer(GL_NONE); // we are also not reading from color buffers!
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_TEXTURE_2D, depthTexture, 0);
        int fboStatus = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }

    private void createFullScreenQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/quadDepth.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/quadDepth.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertex");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.fullScreenQuadProgram = program;
        glUseProgram(fullScreenQuadProgram);
        int texUniform = glGetUniformLocation(fullScreenQuadProgram, "tex");
        inverseMatrixUniform = glGetUniformLocation(fullScreenQuadProgram, "inverseMatrix");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    private void createDepthOnlyProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/rasterDepth.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/rasterDepth.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertexPosition");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.depthOnlyProgram = program;
        glUseProgram(depthOnlyProgram);
        viewProjMatrixUniform = glGetUniformLocation(depthOnlyProgram, "viewProjMatrix");
        glUseProgram(0);
    }

    private void createDepthTexture() {
        this.depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, width, height, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(depthTexture);
        glDeleteFramebuffersEXT(fbo);

        createDepthTexture();
        createFramebufferObject();
    }

    private void update() {
        if (mouseDown) {
            /*
             * If mouse is down, compute the camera rotation based on mouse
             * cursor location.
             */
            currRotationAboutY = rotationAboutY + (mouseX - mouseDownX) * 0.01f;
        } else {
            currRotationAboutY = rotationAboutY;
        }

        /* Rotate camera about Y axis. */
        tmpVector.set((float) sin(-currRotationAboutY) * 3.0f, 2.0f, (float) cos(-currRotationAboutY) * 3.0f);
        camera.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
        if (reconstructViewSpace) {
            camera.invertPerspective(invCamera);
        }
        camera.lookAt(tmpVector, cameraLookAt, cameraUp);
        if (!reconstructViewSpace) {
            camera.invert(invCamera);
        }

        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
    }

    private void renderDepthOnly() {
        glEnable(GL_DEPTH_TEST);
        glUseProgram(depthOnlyProgram);

        /* Update matrices in shader */
        glUniformMatrix4fv(viewProjMatrixUniform, false, camera.get(matrixBuffer));

        /* Rasterize the boxes into the FBO */
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindBuffer(GL_ARRAY_BUFFER, vboScene);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length / 2);
        glDisableVertexAttribArray(0);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        glUseProgram(0);
    }

    private void present() {
        glDisable(GL_DEPTH_TEST);
        glUseProgram(fullScreenQuadProgram);

        /* Set the inverse(proj * view) matrix in the shader */
        glUniformMatrix4fv(inverseMatrixUniform, false, invCamera.get(matrixBuffer));

        glBindBuffer(GL_ARRAY_BUFFER, fullScreenQuadVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisableVertexAttribArray(0);
        glUseProgram(0);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            renderDepthOnly();
            present();

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
            cpCallback.free();
            fbCallback.free();
            mbCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new ReadDepthBufferDemo().run();
    }

}