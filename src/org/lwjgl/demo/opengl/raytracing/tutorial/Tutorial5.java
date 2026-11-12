/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing.tutorial;

import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This part adds the
 * <a href="https://jo.dreggn.org/home/2010_atrous.pdf">Edge-Avoiding À-Trous
 * Wavelet Transform for fast Global Illumination Filtering</a> for filtering
 * the path-traced result to reduce noise at the expense of blurring the result.
 * <p>
 * The algorithm uses edge-stop functions based on the color, normal and
 * position of each sample and these edge-stop functions can be tweaked by how
 * much they should care for a change in color, normal and position. See the
 * {@link #filter(int)} method.
 *
 * @author Kai Burjack
 */
public class Tutorial5 {

    /**
     * The GLFW window handle.
     */
    private long window;
    private int width = 512;
    private int height = 512;
    /**
     * Whether we need to recreate our ray tracer framebuffer.
     */
    private boolean resetFramebuffer = true;

    /**
     * The OpenGL texture acting as our framebuffer for the ray tracer.
     */
    private int pttex;
    /**
     * Ping-pong textures for the filter.
     */
    private int ftex0, ftex1;
    private int normalTex;
    private int positionTex;
    /**
     * A VAO simply holding a VBO for rendering a simple quad.
     */
    private int vao;
    /**
     * The shader program handle of the path tracing shader.
     */
    private int traceProgram;
    /**
     * The shader program handle of a fullscreen quad shader.
     */
    private int quadProgram;
    /**
     *
     */
    private int filterProgram;
    private int colorMapUniform, normalMapUniform, posMapUniform;
    private int c_phiUniform, v_phiUniform, n_phiUniform, p_phiUniform, d_phiUniform, stepwidthUniform;
    private int kernelUniform, offsetUniform;

    /**
     * The FBO for the path tracer with the three render targets {@link #pttex},
     * {@link #normalTex} and {@link #positionTex}.
     */
    private int traceFBO;
    /**
     * The FBO for the À-Trous wavelet filter. We need to ping-pong between two
     * textures.
     */
    private int filterFBO;

    /**
     * A sampler object to sample the framebuffer texture when finally presenting it
     * on the screen.
     */
    private int sampler;

    /**
     * The location of the 'eye' uniform declared in the path tracing shader holding
     * the world-space eye position.
     */
    private int eyeUniform;
    /*
     * The location of the rayNN uniforms. These will be explained later.
     */
    private int ray00Uniform, ray10Uniform, ray01Uniform, ray11Uniform;
    private int timeUniform;
    private int multipleImportanceSampledUniform;
    private int phongExponentUniform;
    private int specularFactorUniform;

    private float mouseX, mouseY;
    private boolean mouseDown;
    private int frameNumber;
    private boolean multipleImportanceSampled = true;
    private float phongExponent = 128.0f;
    private float specularFactor = 0.0f;
    private boolean filtered = true;
    private boolean singleSample = true;
    private boolean stopTime;
    private int filterIterations = 5;

    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4x3f viewMatrix = new Matrix4x3f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraPosition = new Vector3f(-4.0f, 3.0f, 3.0f);
    private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);

    /*
     * All the GLFW callbacks we use to detect certain events, such as keyboard and
     * mouse events or window resize events.
     */
    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWCharCallback charCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;

    /*
     * LWJGL's OpenGL debug callback object, which will get notified by OpenGL about
     * certain events, such as OpenGL errors, warnings or merely information.
     */
    private Callback debugProc;

    /**
     * Do everything necessary once at the start of the application.
     */
    private void init() throws IOException {
        /*
         * Set a GLFW error callback to be notified about any error messages GLFW
         * generates.
         */
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        /*
         * Initialize GLFW itself.
         */
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        /*
         * And set some OpenGL context attributes, such as that we are using OpenGL 3.3.
         * Since the path tracer is implemented in a fragment shader now, we do not
         * need compute shaders (and therefore OpenGL 4.3) anymore.
         */
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // <- make the window visible explicitly later
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        /*
         * Now, create the window.
         */
        window = glfwCreateWindow(width, height, "Path Tracing Tutorial 5", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        System.out.println("Press WSAD, LCTRL, SPACE to move around in the scene.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Press 'C' to toggle between uniform and multiple importance sampling.");
        System.out.println("Press 'T' to toggle stop-time (samples will not depend on time anymore).");
        System.out.println("Press 'F' to toggle filtering.");
        System.out.println("Press 'X' to toggle using 1 sample per pixel (i.e. samples do not accumulate).");
        System.out.println("Press +/- to increase/decrease the specular factor.");
        System.out.println("Press arrowkey up/down to increase/decrease the filter iterations [1..5]");
        System.out.println("Press PAGEUP/PAGEDOWN to increase/decrease the Phong power.");
        System.out.println("Move the mouse to look around.");

        /* And set some GLFW callbacks to get notified about events. */
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                if (key == GLFW_KEY_C && action == GLFW_RELEASE) {
                    multipleImportanceSampled = !multipleImportanceSampled;
                    frameNumber = 0;
                    if (multipleImportanceSampled)
                        System.out.println("Using multiple importance sampling");
                    else
                        System.out.println("Using uniform sampling");
                } else if (key == GLFW_KEY_PAGE_UP && action == GLFW_RELEASE) {
                    phongExponent *= 1.2f;
                    System.out.println("Phong exponent = " + phongExponent);
                    frameNumber = 0;
                } else if (key == GLFW_KEY_PAGE_DOWN && action == GLFW_RELEASE) {
                    phongExponent /= 1.2f;
                    if (phongExponent < 1.0f)
                        phongExponent = 1.0f;
                    System.out.println("Phong exponent = " + phongExponent);
                    frameNumber = 0;
                } else if (key == GLFW_KEY_UP && action == GLFW_RELEASE) {
                    filterIterations = Math.min(5, filterIterations + 1);
                    System.out.println("Filter iterations = " + filterIterations);
                    frameNumber = 0;
                } else if (key == GLFW_KEY_DOWN && action == GLFW_RELEASE) {
                    filterIterations = Math.max(1, filterIterations - 1);
                    System.out.println("Filter iterations = " + filterIterations);
                    frameNumber = 0;
                } else if (key == GLFW_KEY_F && action == GLFW_RELEASE) {
                    filtered = !filtered;
                    if (filtered)
                        System.out.println("Filter enabled");
                    else
                        System.out.println("Filter disabled");
                    frameNumber = 0;
                } else if (key == GLFW_KEY_X && action == GLFW_RELEASE) {
                    singleSample = !singleSample;
                    if (singleSample)
                        System.out.println("Using only 1 sample per pixel");
                    else
                        System.out.println("Accumulating samples");
                    frameNumber = 0;
                } else if (key == GLFW_KEY_T && action == GLFW_RELEASE) {
                    stopTime = !stopTime;
                    if (stopTime)
                        System.out.println("Time is stopped");
                    else
                        System.out.println("Time continues");
                    frameNumber = 0;
                }
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        });

        /*
         * For changing the specular factor we do not use the key callback above. Its
         * key codes name the physical keys by what they are on a US keyboard, but
         * where the '+' and '-' keys are depends on the keyboard layout, and not
         * every keyboard has a numpad. So, we let GLFW tell us which characters got
         * typed instead. ('=' is the '+' key without Shift on a US keyboard.)
         */
        glfwSetCharCallback(window, charCallback = new GLFWCharCallback() {
            public void invoke(long window, int codepoint) {
                if (codepoint == '+' || codepoint == '=')
                    changeSpecularFactor(0.1f);
                else if (codepoint == '-')
                    changeSpecularFactor(-0.1f);
            }
        });

        /*
         * We need to get notified when the GLFW window framebuffer size changed (i.e.
         * by resizing the window), in order to recreate our own ray tracer framebuffer
         * texture.
         */
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (Tutorial5.this.width != width || Tutorial5.this.height != height)) {
                    Tutorial5.this.width = width;
                    Tutorial5.this.height = height;
                    Tutorial5.this.resetFramebuffer = true;
                    /*
                     * Reset the frame counter. Any change in framebuffer size will reset the
                     * current accumulated result.
                     */
                    Tutorial5.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - Tutorial5.this.mouseX;
                    float deltaY = (float) y - Tutorial5.this.mouseY;
                    Tutorial5.this.viewMatrix.rotateLocalY(deltaX * 0.01f);
                    Tutorial5.this.viewMatrix.rotateLocalX(deltaY * 0.01f);
                    /*
                     * Reset the frame counter. Any change in camera position will reset the current
                     * accumulated result.
                     */
                    Tutorial5.this.frameNumber = 0;
                }
                Tutorial5.this.mouseX = (float) x;
                Tutorial5.this.mouseY = (float) y;
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    Tutorial5.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    Tutorial5.this.mouseDown = false;
                }
            }
        });

        /*
         * Center the created GLFW window on the screen.
         */
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);

        /*
         * Account for HiDPI screens where window size != framebuffer pixel size.
         */
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);

        /* Create all needed GL resources */
        createFramebufferTexture();
        createSampler();
        this.vao = glGenVertexArrays();
        createTraceProgram();
        initTraceProgram();
        createQuadProgram();
        initQuadProgram();
        createFilterProgram();
        initFilterProgram();
        createTraceFBO();
        createFilterFBO();

        glfwShowWindow(window);
    }

    /**
     * Change the specular factor by the given amount and keep it within [0, 1].
     *
     * @param amount
     *            the amount to add to the specular factor
     */
    private void changeSpecularFactor(float amount) {
        /* Round to two decimal places in order not to accumulate rounding errors. */
        specularFactor = Math.round((specularFactor + amount) * 100.0f) / 100.0f;
        specularFactor = Math.max(0.0f, Math.min(1.0f, specularFactor));
        System.out.println("Specular factor = " + specularFactor);
        frameNumber = 0;
    }

    /**
     * Create the full-scren quad shader.
     */
    private void createQuadProgram() throws IOException {
        /*
         * Create program and shader objects for our full-screen quad rendering.
         */
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/quad.fs.glsl", GL_FRAGMENT_SHADER);
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
    }

    /**
     * Create the path tracing shader program.
     */
    private void createTraceProgram() throws IOException {
        /*
         * Create our GLSL path tracing program. It consists of the vertex shader
         * generating a full-screen triangle, which we also use for presenting the
         * final image, and the fragment shader, which traces one path for every
         * fragment/pixel generated by rasterizing that triangle.
         */
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/quad.vs.glsl", GL_VERTEX_SHADER);
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/random.glsl", GL_FRAGMENT_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/raytracing.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, random);
        glAttachShader(program, fshader);
        /*
         * The fragment shader writes to three render targets. See createTraceFBO().
         */
        glBindFragDataLocation(program, 0, "color_out");
        glBindFragDataLocation(program, 1, "normal_out");
        glBindFragDataLocation(program, 2, "position_out");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.traceProgram = program;
    }

    /**
     * Create the filter program.
     */
    private void createFilterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/atrous.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/atrous.fs.glsl", GL_FRAGMENT_SHADER);
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
        this.filterProgram = program;
    }

    /**
     * Initialize the À-Trous wavelet filter program.
     */
    private void initFilterProgram() {
        glUseProgram(filterProgram);
        colorMapUniform = glGetUniformLocation(filterProgram, "colorMap");
        glUniform1i(colorMapUniform, 0);
        normalMapUniform = glGetUniformLocation(filterProgram, "normalMap");
        glUniform1i(normalMapUniform, 1);
        posMapUniform = glGetUniformLocation(filterProgram, "posMap");
        glUniform1i(posMapUniform, 2);
        c_phiUniform = glGetUniformLocation(filterProgram, "c_phi");
        v_phiUniform = glGetUniformLocation(filterProgram, "v_phi");
        n_phiUniform = glGetUniformLocation(filterProgram, "n_phi");
        p_phiUniform = glGetUniformLocation(filterProgram, "p_phi");
        d_phiUniform = glGetUniformLocation(filterProgram, "d_phi");
        stepwidthUniform = glGetUniformLocation(filterProgram, "stepwidth");
        kernelUniform = glGetUniformLocation(filterProgram, "kernel");
        offsetUniform = glGetUniformLocation(filterProgram, "offset");
        for (int i = 0, y = -2; y <= 2; y++)
            for (int x = -2; x <= 2; x++, i++)
                glUniform2i(offsetUniform + i, x, y);
        /* B3^T x B3 */
        glUniform1fv(kernelUniform, new float[] { 1.0f / 256.0f, 1.0f / 64.0f, 3.0f / 128.0f, 1.0f / 64.0f,
                1.0f / 256.0f, 1.0f / 64.0f, 1.0f / 16.0f, 3.0f / 32.0f, 1.0f / 16.0f, 1.0f / 64.0f, 3.0f / 128.0f,
                3.0f / 32.0f, 9.0f / 64.0f, 3.0f / 32.0f, 3.0f / 128.0f, 1.0f / 64.0f, 1.0f / 16.0f, 3.0f / 32.0f,
                1.0f / 16.0f, 1.0f / 64.0f, 1.0f / 256.0f, 1.0f / 64.0f, 3.0f / 128.0f, 1.0f / 64.0f, 1.0f / 256.0f });
        glUseProgram(0);
    }

    /**
     * Create the FBO used to run the À-Trous filter iterations.
     */
    private void createFilterFBO() {
        filterFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, filterFBO);
        /*
         * The filter will ping-pong between two writable textures for each iteration.
         * We use glDrawBuffers() to decide which one to write to in each iteration.
         */
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ftex0, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, ftex1, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("Framebuffer is not complete: " + status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Initialize the full-screen-quad program. This just binds the program briefly
     * to obtain the uniform locations.
     */
    private void initQuadProgram() {
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    /**
     * Initialize the path tracing shader program. This just binds the program
     * briefly to obtain the uniform locations.
     */
    private void initTraceProgram() {
        glUseProgram(traceProgram);
        eyeUniform = glGetUniformLocation(traceProgram, "eye");
        ray00Uniform = glGetUniformLocation(traceProgram, "ray00");
        ray10Uniform = glGetUniformLocation(traceProgram, "ray10");
        ray01Uniform = glGetUniformLocation(traceProgram, "ray01");
        ray11Uniform = glGetUniformLocation(traceProgram, "ray11");
        timeUniform = glGetUniformLocation(traceProgram, "time");
        multipleImportanceSampledUniform = glGetUniformLocation(traceProgram, "multipleImportanceSampled");
        phongExponentUniform = glGetUniformLocation(traceProgram, "phongExponent");
        specularFactorUniform = glGetUniformLocation(traceProgram, "specularFactor");
        glUseProgram(0);
    }

    /**
     * Create the textures that will serve as the render targets that the path
     * tracing shader and the filter shader will write/render to.
     */
    private void createFramebufferTexture() {
        pttex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, pttex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, NULL);
        ftex0 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ftex0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, NULL);
        ftex1 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ftex1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, NULL);
        /*
         * A signed normalized format would be enough for the normal, but those are
         * not color-renderable in OpenGL 3.3. So we use a floating-point format.
         */
        normalTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, NULL);
        /*
         * The filter computes distances of just a few centimeters from the positions.
         * That needs more precision than a 16-bit floating-point format can give us.
         */
        positionTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, positionTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, NULL);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the FBO which the path tracing shader renders into.
     */
    private void createTraceFBO() {
        traceFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, traceFBO);
        /*
         * In addition to the color we are also outputting the normal and the position
         * of the primary ray hit for the edge-avoiding À-Trous filter pass. For the
         * sake of performance we should really be using hybrid path tracing now, with
         * using the rasterizer for a position/normal pre-pass to avoid tracing the
         * primary rays and using them to output normal/position. But that'll come
         * later.
         */
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pttex, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, normalTex, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, positionTex, 0);
        /*
         * The i-th fragment shader output will be written to the i-th draw buffer.
         */
        glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2 });
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("Framebuffer is not complete: " + status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Create the sampler to sample the framebuffer texture within the fullscreen
     * quad shader. We use NEAREST filtering since one texel on the framebuffer
     * texture corresponds exactly to one pixel on the GLFW window framebuffer.
     */
    private void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    /**
     * Recreate the framebuffer when the window size changes.
     */
    private void resizeFramebufferTexture() {
        glDeleteFramebuffers(traceFBO);
        glDeleteFramebuffers(filterFBO);
        glDeleteTextures(pttex);
        glDeleteTextures(ftex0);
        glDeleteTextures(ftex1);
        glDeleteTextures(normalTex);
        glDeleteTextures(positionTex);
        createFramebufferTexture();
        createTraceFBO();
        createFilterFBO();
    }

    /**
     * Update the camera position based on pressed keys to move around.
     *
     * @param dt
     *            the elapsed time since the last frame in seconds
     */
    private void update(float dt) {
        float factor = 1.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 3.0f;
        if (keydown[GLFW_KEY_W]) {
            viewMatrix.translateLocal(0, 0, factor * dt);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_S]) {
            viewMatrix.translateLocal(0, 0, -factor * dt);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_A]) {
            viewMatrix.translateLocal(factor * dt, 0, 0);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_D]) {
            viewMatrix.translateLocal(-factor * dt, 0, 0);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_Q]) {
            viewMatrix.rotateLocalZ(-factor * dt);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_E]) {
            viewMatrix.rotateLocalZ(factor * dt);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            viewMatrix.translateLocal(0, factor * dt, 0);
            frameNumber = 0;
        }
        if (keydown[GLFW_KEY_SPACE]) {
            viewMatrix.translateLocal(0, -factor * dt, 0);
            frameNumber = 0;
        }
    }

    /**
     * Compute a new frame by tracing the scene using our path tracing shader. The
     * resulting pixels will be written to the framebuffer texture {@link #pttex}.
     * <p>
     * See the JavaDocs of this method in {@link Tutorial2} for a better
     * explanation.
     */
    private void trace(float time) {
        glUseProgram(traceProgram);

        /*
         * If the framebuffer size has changed, because the GLFW window was resized, we
         * need to reset the camera's projection matrix and recreate our framebuffer
         * texture.
         */
        if (resetFramebuffer) {
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 1f, 2f);
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }

        /*
         * Submit the current time to the shader for temporal variance in the generated
         * random numbers.
         */
        glUniform1f(timeUniform, time);

        /*
         * We are going to average the last computed average and this frame's result, so
         * here we compute the blend factor between old frame and new frame. See the
         * class JavaDocs above for more information about that.
         */
        float blendFactor = frameNumber / (frameNumber + 1.0f);
        /*
         * Set whether we want to use multiple importance sampling.
         */
        glUniform1i(multipleImportanceSampledUniform, multipleImportanceSampled ? 1 : 0);
        /*
         * Set the phong power/exponent which can be configured via PAGEDOWN/PAGEUP
         * keys.
         */
        glUniform1f(phongExponentUniform, phongExponent);
        /*
         * Set the specular factor which can be configured via +/- keys.
         */
        glUniform1f(specularFactorUniform, specularFactor);

        /*
         * Invert the view-projection matrix to unproject NDC-space coordinates to
         * world-space vectors. See next few statements.
         */
        projMatrix.invertPerspectiveView(viewMatrix, invViewProjMatrix);
        /*
         * Compute and set the view frustum corner rays in the shader for the shader to
         * compute the direction from the eye through a framebuffer's pixel center for a
         * given fragment.
         */
        viewMatrix.origin(cameraPosition);
        glUniform3f(eyeUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray00Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray01Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray10Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray11Uniform, tmpVector.x, tmpVector.y, tmpVector.z);

        /*
         * Render to the three render targets (color, normal and position) of the path
         * tracer.
         */
        glBindFramebuffer(GL_FRAMEBUFFER, traceFBO);
        /*
         * A fragment shader cannot read from the texture it is currently rendering
         * to, like the compute shader of the previous parts did with its image. So, in
         * order to average the new frame with the previous frames, we let OpenGL blend
         * the color output of the fragment shader with the current content of the
         * color render target via:
         *   finalColor = blendFactor * oldColor + (1 - blendFactor) * newColor
         * We only enable blending for the first draw buffer (the color), because the
         * normal and the position should simply be overwritten. And we do not blend
         * at all when there is no previous frame to blend with. In that case the
         * content of the color render target can be anything.
         */
        if (blendFactor > 0.0f) {
            glEnablei(GL_BLEND, 0);
            glBlendColor(0.0f, 0.0f, 0.0f, blendFactor);
            glBlendFunc(GL_ONE_MINUS_CONSTANT_ALPHA, GL_CONSTANT_ALPHA);
        }
        /*
         * Draw the full-screen triangle to execute the path tracing fragment shader
         * for every pixel.
         */
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        /* Reset state. */
        glBindVertexArray(0);
        glDisablei(GL_BLEND, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);

        /*
         * Increment the frame counter to compute a correct average in the next
         * iteration.
         */
        if (!singleSample)
            frameNumber++;
    }

    /**
     * Perform 'n' iterations of the À-Trous edge-avoiding filter. The first
     * iteration will read from the path-traced texture and write/render to another
     * texture via a FBO.
     *
     * @param n
     * @return
     */
    private int filter(int n) {
        /*
         * We will play FBO ping-pong between two textures. One is being read from and
         * one is being rendered to.
         */
        int read = pttex, write = ftex0;
        glUseProgram(filterProgram);
        /*
         * Set the constant input textures for the filter: normal and position
         */
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, normalTex);
        glBindSampler(1, this.sampler);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, positionTex);
        glBindSampler(2, this.sampler);
        glBindFramebuffer(GL_FRAMEBUFFER, filterFBO);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, this.sampler);
        /*
         * Use some good edge-stopping function weights. Each one is the squared
         * "standard deviation" of the bell curve
         *   weight = exp(-difference² / phi)
         * which the filter uses to compute the weight of a sample from its difference
         * in color, normal and position to the pixel being filtered. So, the smaller
         * a value, the more such a difference reduces the weight of a sample.
         *
         * Play with these to get somewhat an understanding what they cause.
         *
         * The normal and the position are free of noise, so we can be strict with
         * those. For the position we use two values (see the shader): A sample should
         * not be farther away than about a meter, and it should not be more than about
         * three centimeters off the plane of the surface.
         */
        float n_phi = 1E-2f; // <- normals are VERY important
        float p_phi = 1.0f;
        float d_phi = 1E-3f;
        /*
         * The color is different, because the color is what contains the noise we
         * want to get rid of. In the path-traced image the color difference between
         * two neighboring pixels is mostly noise and tells us next to nothing about
         * actual edges in the illumination, such as the boundary of a shadow. So we
         * have to start with a big value. With it, the first iteration mostly only
         * cares for normals and positions.
         *
         * The more frames got averaged in the path-traced image, the less noise it
         * contains. Averaging N frames divides the variance of the noise by N, and so
         * we do the same with our (squared) value. This way, the filter blurs less
         * and less while the path-traced image converges, and the filtered image
         * converges with it instead of staying blurry forever.
         */
        float c_phi0 = 10.0f / Math.max(1, frameNumber);
        /*
         * That value is the same for all pixels. But the noise is not. Bright surfaces
         * are noisier than dark ones, and with specular surfaces there are single
         * very bright pixels (fireflies) from light paths, which the path tracer only
         * finds very rarely. So the shader also estimates the variance of the
         * path-traced image around every pixel and adds it, multiplied with the
         * following factor, to the value above. This way, the filter tolerates
         * bigger color differences where the image is noisier.
         */
        float v_phi0 = 2.0f;
        glUniform1f(n_phiUniform, n_phi);
        glUniform1f(p_phiUniform, p_phi);
        glUniform1f(d_phiUniform, d_phi);
        /*
         * Now, perform 'n' iterations of the filter algorithm.
         */
        for (int i = 0; i < n; i++) {
            /*
             * Every iteration averages over an area with twice the diameter as the
             * previous iteration and thereby about halves the noise remaining in the
             * image. So we can (and have to) also halve the standard deviation of the
             * color edge-stopping function with every iteration, like the paper does.
             * This means dividing our squared value by four. Without this, the later
             * iterations with their big stepwidth would blur all the edges in the
             * illumination again, which the earlier iterations preserved.
             * The same holds for the influence of the variance.
             */
            glUniform1f(c_phiUniform, c_phi0 / (1 << (2 * i)));
            glUniform1f(v_phiUniform, v_phi0 / (1 << (2 * i)));
            /*
             * This is the "à-trous" (with holes) part of the algorithm: We always use
             * the same 5x5 kernel, but the distance between two of its samples doubles
             * with every iteration. So iteration 'i' leaves 2^i - 1 pixels wide holes
             * between its samples. These holes do no harm, because the previous
             * iterations already averaged the pixels in the holes into the samples.
             */
            glUniform1i(stepwidthUniform, 1 << i);
            /*
             * Read from the current 'read' texture.
             */
            glBindTexture(GL_TEXTURE_2D, read);
            /*
             * Set which FBO color attachment to write to.
             */
            glDrawBuffers(write == ftex0 ? GL_COLOR_ATTACHMENT0 : GL_COLOR_ATTACHMENT1);
            /*
             * Draw the full-screen quad.
             */
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (i == 0) {
                /*
                 * We don't want to ping-pong between ftex0 and the path-tracer result anymore,
                 * but between ftex0 and ftex1.
                 */
                read = ftex1;
            }
            /*
             * Swap 'read' and 'write'.
             */
            int tmp = read;
            read = write;
            write = tmp;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindVertexArray(0);
        glUseProgram(0);
        /*
         * Return the texture that was last written/rendered to and which should now be
         * read from. We set this to 'read' for a potential next loop iteration above.
         */
        return read;
    }

    /**
     * Present the final image in the given OpenGL texture on the default
     * framebuffer of the GLFW window.
     *
     * @param tex
     *            the texture to present on the screen / default GLFW window
     *            framebuffer. This depends on whether we want filtering and how
     *            many filter passes we used
     */
    private void present(int tex) {
        /*
         * Draw the rendered image on the screen using a textured full-screen quad.
         */
        glUseProgram(quadProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(0, this.sampler);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void loop() {
        /*
         * Our render loop is really simple...
         */
        /*
         * Mind that System.nanoTime() can return very big values, which a float cannot
         * hold precisely enough. So we only convert time differences to float.
         * Otherwise, when rendering faster than that precision, consecutive frames
         * would get the same time and with it the same random numbers and therefore
         * the same noise, and averaging those frames would not reduce any noise.
         */
        long startTime = System.nanoTime();
        long lastTime = startTime;
        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            /*
             * ...we just poll for GLFW window events (as usual).
             */
            glfwPollEvents();
            /*
             * Tell OpenGL about any possibly modified viewport size.
             */
            glViewport(0, 0, width, height);
            /*
             * Update the camera
             */
            update(dt);
            /*
             * Call the path tracing shader to trace the scene and produce an image in
             * our framebuffer texture.
             */
            trace(!stopTime ? (thisTime - startTime) / 1E9f : 1.0f);
            int read = pttex;
            if (filtered) {
                /*
                 * Filter the traced image via the edge-avoiding À-Trous filter and return the
                 * texture to read from when presenting the image on the screen. Since the
                 * filter algorithm ping-pongs between two textures we need to know which was
                 * the last one written to.
                 */
                read = filter(filterIterations);
            }
            /*
             * Finally we blit/render the framebuffer texture to the default window
             * framebuffer of the GLFW window.
             */
            present(read);
            /*
             * Tell the GLFW window to swap buffers so that our rendered framebuffer texture
             * becomes visible.
             */
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
            charCallback.free();
            fbCallback.free();
            cpCallback.free();
            mbCallback.free();
            glfwDestroyWindow(window);
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) throws Exception {
        new Tutorial5().run();
    }

}