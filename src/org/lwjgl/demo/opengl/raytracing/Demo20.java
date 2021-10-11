/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBTextureFloat.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Raytracing demo.
 * <p/>
 * This is a port of the other raytracing demos to OpenGL 2.0, to better port it
 * further to OpenGL ES 2.0 / WebGL 1.0.
 * <p>
 * Since we do not have SSBO support here, we use a RGB/XYZ texture to store the
 * boxes which we then sample in the shader. We are also using a different
 * random function that does not rely on GLSL 3.30.
 *
 * @author Kai Burjack
 */
public class Demo20 {

    /**
     * The boxes for ray tracing.
     */
    private static Vector3f[] boxes = Scene.boxes;

    private long window;
    private int width = 300;
    private int height = 300;
    private boolean resetFramebuffer = true;

    private int tex;
    private int vbo;
    private int fbo;
    private int rayTracingProgram;
    private int quadProgram;

    private int boxesTexture;
    private int eyeUniform;
    private int ray00Uniform;
    private int ray10Uniform;
    private int ray01Uniform;
    private int ray11Uniform;
    private int timeUniform;
    private int blendFactorUniform;
    private int framebufferUniform;
    private int boxesUniform;
    private int numBoxesUniform;
    private int widthUniform;
    private int heightUniform;
    private int bounceCountUniform;

    private float mouseDownX;
    private float mouseX;
    private boolean mouseDown;

    private float currRotationAboutY = 0.0f;
    private float rotationAboutY = 0.8f;

    private long firstTime;
    private int frameNumber;
    private int bounceCount = 1;

    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4x3f viewMatrix = new Matrix4x3f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraPosition = new Vector3f();
    private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);

    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWMouseButtonCallback mbCallback;

    Callback debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if ( error == GLFW_VERSION_UNAVAILABLE )
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

        window = glfwCreateWindow(width, height, "Raytracing Demo (fragment shader)", 0L, 0L);
        if (window == 0L) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Press keypad '+' or 'page up' to increase the number of bounces.");
        System.out.println("Press keypad '-' or 'page down' to decrease the number of bounces.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if ( action != GLFW_RELEASE ) {
                    return;
                }

                if ( key == GLFW_KEY_ESCAPE ) {
                    glfwSetWindowShouldClose(window, true);
                } else if ( key == GLFW_KEY_KP_ADD || key == GLFW_KEY_PAGE_UP ) {
                    int newBounceCount = Math.min(4, Demo20.this.bounceCount + 1);
                    if ( newBounceCount != Demo20.this.bounceCount ) {
                        Demo20.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + Demo20.this.bounceCount);
                        Demo20.this.frameNumber = 0;
                    }
                } else if ( key == GLFW_KEY_KP_SUBTRACT || key == GLFW_KEY_PAGE_DOWN ) {
                    int newBounceCount = Math.max(1, Demo20.this.bounceCount - 1);
                    if ( newBounceCount != Demo20.this.bounceCount ) {
                        Demo20.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + Demo20.this.bounceCount);
                        Demo20.this.frameNumber = 0;
                    }
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if ( width > 0 && height > 0 && (Demo20.this.width != width || Demo20.this.height != height) ) {
                    Demo20.this.width = width;
                    Demo20.this.height = height;
                    Demo20.this.resetFramebuffer = true;
                    Demo20.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                Demo20.this.mouseX = (float)x;
                if ( mouseDown ) {
                    Demo20.this.frameNumber = 0;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if ( action == GLFW_PRESS ) {
                    Demo20.this.mouseDownX = Demo20.this.mouseX;
                    Demo20.this.mouseDown = true;
                } else if ( action == GLFW_RELEASE ) {
                    Demo20.this.mouseDown = false;
                    Demo20.this.rotationAboutY = Demo20.this.currRotationAboutY;
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

        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_EXT_framebuffer_object) {
            throw new AssertionError("This demo requires the EXT_framebuffer_object extensions");
        }
        if (!caps.GL_ARB_texture_float) {
            throw new AssertionError("This demo requires the ARB_texture_float extensions");
        }

        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        createFramebufferTexture();
        createFrameBufferObject();
        quadFullScreenVbo();
        createBoxesTexture();
        createRayTracingProgram();
        initRayTracingProgram();
        createQuadProgram();
        initQuadProgram();

        glfwShowWindow(window);
        firstTime = System.nanoTime();
    }

    /**
     * Create a full-screen quad VBO.
     */
    private void quadFullScreenVbo() {
        this.vbo = glGenBuffers();
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
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad110.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad110.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
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
    }

    /**
     * Create the ray tracing shader program.
     *
     * @return that program id
     *
     * @throws IOException
     */
    private void createRayTracingProgram() throws IOException {
        String version = GL.getCapabilities().OpenGL30 ? "130" : null;

        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad110.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/raytracing110.fs", GL_FRAGMENT_SHADER);
        int randomCommon = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/randomCommon.glsl", GL_FRAGMENT_SHADER, version);
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/random20.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, randomCommon);
        glAttachShader(program, random);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.rayTracingProgram = program;
    }

    /**
     * Initialize the full-screen-quad program.
     */
    private void initQuadProgram() {
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    /**
     * Initialize the ray tracing shader.
     */
    private void initRayTracingProgram() {
        glUseProgram(rayTracingProgram);
        eyeUniform = glGetUniformLocation(rayTracingProgram, "eye");
        ray00Uniform = glGetUniformLocation(rayTracingProgram, "ray00");
        ray10Uniform = glGetUniformLocation(rayTracingProgram, "ray10");
        ray01Uniform = glGetUniformLocation(rayTracingProgram, "ray01");
        ray11Uniform = glGetUniformLocation(rayTracingProgram, "ray11");
        timeUniform = glGetUniformLocation(rayTracingProgram, "time");
        blendFactorUniform = glGetUniformLocation(rayTracingProgram, "blendFactor");
        framebufferUniform = glGetUniformLocation(rayTracingProgram, "framebuffer");
        boxesUniform = glGetUniformLocation(rayTracingProgram, "boxes");
        numBoxesUniform = glGetUniformLocation(rayTracingProgram, "numBoxes");
        widthUniform = glGetUniformLocation(rayTracingProgram, "width");
        heightUniform = glGetUniformLocation(rayTracingProgram, "height");
        bounceCountUniform = glGetUniformLocation(rayTracingProgram, "bounceCount");
        glUniform1i(framebufferUniform, 0);
        glUniform1i(boxesUniform, 1);
        glUniform1i(numBoxesUniform, Demo20.boxes.length / 2);
        glUseProgram(0);
    }

    /**
     * Create the RGB/XYZ texture holding our boxes.
     */
    private void createBoxesTexture() {
        this.boxesTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, boxesTexture);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * 4 * Demo20.boxes.length);
        FloatBuffer fb = bb.asFloatBuffer();
        for (int i = 0; i < Demo20.boxes.length; i += 2) {
            Vector3f min = Demo20.boxes[i];
            Vector3f max = Demo20.boxes[i + 1];
            fb.put(min.x).put(min.y).put(min.z).put(0.0f);
            fb.put(max.x).put(max.y).put(max.z).put(0.0f);
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F_ARB, Demo20.boxes.length, 1, 0, GL_RGBA, GL_FLOAT, bb);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the texture that will serve as our framebuffer.
     */
    private void createFramebufferTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F_ARB, width, height, 0, GL_RGBA, GL_FLOAT,
                (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(tex);
        glDeleteFramebuffersEXT(fbo);

        createFramebufferTexture();
        createFrameBufferObject();
    }

    /**
     * Create the frame buffer object that our ray tracing shader uses to render
     * into the framebuffer texture.
     */
    private int createFrameBufferObject() {
        this.fbo = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, this.tex, 0);
        int fboStatus = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        return fbo;
    }

    /**
     * Compute one frame by tracing the scene using our ray tracing shader and
     * presenting that image on the screen.
     */
    private void trace() {
        glUseProgram(rayTracingProgram);

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
        cameraPosition.set((float) sin(-currRotationAboutY) * 3.0f, 2.0f, (float) cos(-currRotationAboutY) * 3.0f);
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);

        if (resetFramebuffer) {
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 1f, 2f);
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
        projMatrix.invertPerspectiveView(viewMatrix, invViewProjMatrix);

        long thisTime = System.nanoTime();
        float elapsedSeconds = (thisTime - firstTime) / 1E9f;
        glUniform1f(timeUniform, elapsedSeconds);

        /*
         * We are going to average multiple successive frames, so here we
         * compute the blend factor between old frame and new frame. 0.0 - use
         * only the new frame > 0.0 - blend between old frame and new frame
         */
        float blendFactor = frameNumber / (frameNumber + 1.0f);
        glUniform1f(blendFactorUniform, blendFactor);
        glUniform1i(bounceCountUniform, bounceCount);

        /* Set viewing frustum corner rays in shader */
        glUniform3f(eyeUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray00Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1,  1, 0)).sub(cameraPosition);
        glUniform3f(ray01Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set( 1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray10Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set( 1,  1, 0)).sub(cameraPosition);
        glUniform3f(ray11Uniform, tmpVector.x, tmpVector.y, tmpVector.z);

        glUniform1f(widthUniform, width);
        glUniform1f(heightUniform, height);

        /*
         * Draw full-screen quad to generate frame with our tracing shader
         * program.
         */
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, boxesTexture);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        glUseProgram(0);

        frameNumber++;
    }

    /**
     * Present the final image on the screen/viewport.
     */
    private void present() {
        /*
         * Draw the rendered image on the screen using textured full-screen
         * quad.
         */
        glUseProgram(quadProgram);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glUseProgram(0);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            trace();
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
            fbCallback.free();
            cpCallback.free();
            mbCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new Demo20().run();
    }

}