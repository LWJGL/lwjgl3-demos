/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Raytracing demo.
 * <p/>
 * This is a port to OpenGL 3.3, using the old-style GPGPU with full-screen quad and vertex/fragment shaders
 * to do general purpose computing in shaders. We also need 3.3 as opposed to 3.0, because of certain GLSL
 * functions only available there.
 * <p/>
 * The port is meant for people that cannot use OpenGL 4.3, as it is the case for all Apple products right now.
 *
 * @author Kai Burjack
 */
public class Demo33 {

    private long window;
    private int width  = 1024;
    private int height = 768;
    private boolean resetFramebuffer = true;

    private int tex;
    private int vao;
    private int fbo;
    private int rayTracingProgram;
    private int quadProgram;
    private int sampler;

    private int eyeUniform;
    private int ray00Uniform;
    private int ray10Uniform;
    private int ray01Uniform;
    private int ray11Uniform;
    private int timeUniform;
    private int blendFactorUniform;
    private int framebufferUniform;
    private int widthUniform;
    private int heightUniform;
    private int bounceCountUniform;

    private float   mouseDownX;
    private float   mouseX;
    private boolean mouseDown;

    private float currRotationAboutY = 0.0f;
    private float rotationAboutY     = 0.8f;

    private long firstTime;
    private int  frameNumber;
    private int bounceCount = 1;

    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4x3f viewMatrix = new Matrix4x3f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraPosition = new Vector3f();
    private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);

    GLFWErrorCallback           errCallback;
    GLFWKeyCallback             keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback       cpCallback;
    GLFWMouseButtonCallback     mbCallback;

    Callback debugProc;

    static {
        /*
         * Tell LWJGL that we only want 3.3 functionality.
         */
        System.setProperty("org.lwjgl.opengl.maxVersion", "3.3");
    }

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if ( error == GLFW_VERSION_UNAVAILABLE )
                    System.err.println("This demo requires OpenGL 3.3 or higher.");
                delegate.invoke(error, description);
            }

            @Override
            public void free() {
                delegate.free();
            }
        });

        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Raytracing Demo (fragment shader)", NULL, NULL);
        if ( window == NULL ) {
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
                    int newBounceCount = Math.min(4, Demo33.this.bounceCount + 1);
                    if (newBounceCount != Demo33.this.bounceCount) {
                        Demo33.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + Demo33.this.bounceCount);
                        Demo33.this.frameNumber = 0;
                    }
                } else if ( key == GLFW_KEY_KP_SUBTRACT || key == GLFW_KEY_PAGE_DOWN ) {
                    int newBounceCount = Math.max(1, Demo33.this.bounceCount - 1);
                    if (newBounceCount != Demo33.this.bounceCount) {
                        Demo33.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + Demo33.this.bounceCount);
                        Demo33.this.frameNumber = 0;
                    }
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if ( width > 0 && height > 0 &&
                     (Demo33.this.width != width || Demo33.this.height != height) ) {
                    Demo33.this.width = width;
                    Demo33.this.height = height;
                    Demo33.this.resetFramebuffer = true;
                    Demo33.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                Demo33.this.mouseX = (float)x;
                if ( mouseDown ) {
                    Demo33.this.frameNumber = 0;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if ( action == GLFW_PRESS ) {
                    Demo33.this.mouseDownX = Demo33.this.mouseX;
                    Demo33.this.mouseDown = true;
                } else if ( action == GLFW_RELEASE ) {
                    Demo33.this.mouseDown = false;
                    Demo33.this.rotationAboutY = Demo33.this.currRotationAboutY;
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

        /* Create all needed GL resources */
        createFramebufferTexture();
        createSampler();
        createFrameBufferObject();
        this.vao = glGenVertexArrays();
        createRayTracingProgram();
        initRayTracingProgram();
        createQuadProgram();
        initQuadProgram();

        glfwShowWindow(window);
        firstTime = System.nanoTime();
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad.vs", GL_VERTEX_SHADER, "330");
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad.fs", GL_FRAGMENT_SHADER, "330");
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if ( linked == 0 ) {
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
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad.vs", GL_VERTEX_SHADER, "330");
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/raytracing.fs", GL_FRAGMENT_SHADER);
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/random.glsl", GL_FRAGMENT_SHADER);
        int randomCommon = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/randomCommon.glsl", GL_FRAGMENT_SHADER, "330");
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, random);
        glAttachShader(program, randomCommon);
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if ( linked == 0 ) {
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
        widthUniform = glGetUniformLocation(rayTracingProgram, "width");
        heightUniform = glGetUniformLocation(rayTracingProgram, "height");
        bounceCountUniform = glGetUniformLocation(rayTracingProgram, "bounceCount");
        glUniform1i(framebufferUniform, 0);
        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer.
     */
    private void createFramebufferTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the sampler to sample the framebuffer texture within the shader.
     */
    private void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(tex);
        glDeleteFramebuffers(fbo);

        createFramebufferTexture();
        createFrameBufferObject();
    }

    /**
     * Create the frame buffer object that our ray tracing shader uses to render
     * into the framebuffer texture.
     */
    private int createFrameBufferObject() {
        this.fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.tex, 0);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if ( fboStatus != GL_FRAMEBUFFER_COMPLETE ) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return fbo;
    }

    /**
     * Compute one frame by tracing the scene using our ray tracing shader and
     * presenting that image on the screen.
     */
    private void trace() {
        glUseProgram(rayTracingProgram);

        if ( mouseDown ) {
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

        /* We are going to average multiple successive frames, so
         * here we compute the blend factor between old frame and new frame.
         *   0.0 - use only the new frame
         * > 0.0 - blend between old frame and new frame */
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
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindVertexArray(vao);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(0, this.sampler);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
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
        glBindVertexArray(vao);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(0, this.sampler);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void loop() {
        while ( !glfwWindowShouldClose(window) ) {
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

            if ( debugProc != null )
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
        new Demo33().run();
    }

}