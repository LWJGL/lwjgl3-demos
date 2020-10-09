/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing.tutorial;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
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
     * The shader program handle of the compute shader.
     */
    private int computeProgram;
    /**
     * The shader program handle of a fullscreen quad shader.
     */
    private int quadProgram;
    /**
     *
     */
    private int filterProgram;
    private int colorMapUniform, normalMapUniform, posMapUniform;
    private int c_phiUniform, n_phiUniform, p_phiUniform, stepwidthUniform;
    private int kernelUniform, offsetUniform;

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
     * The location of the 'eye' uniform declared in the compute shader holding the
     * world-space eye position.
     */
    private int eyeUniform;
    /*
     * The location of the rayNN uniforms. These will be explained later.
     */
    private int ray00Uniform, ray10Uniform, ray01Uniform, ray11Uniform;
    private int timeUniform;
    private int blendFactorUniform;
    private int multipleImportanceSampledUniform;
    private int phongExponentUniform;
    private int specularFactorUniform;
    /**
     * The binding point in the compute shader of the framebuffer image (level 0 of
     * the {@link #pttex} texture).
     */
    private int framebufferImageBinding;
    private int normalbufferImageBinding;
    private int positionbufferImageBinding;
    /**
     * Value of the work group size in X dimension declared in the compute shader.
     */
    private int workGroupSizeX;
    /**
     * Value of the work group size in Y dimension declared in the compute shader.
     */
    private int workGroupSizeY;

    private float mouseX, mouseY;
    private boolean mouseDown;
    private int frameNumber;
    private boolean multipleImportanceSampled = true;
    private float phongExponent = 128.0f;
    private float specularFactor = 0.0f;
    private boolean filtered = true;
    private boolean singleSample = true;
    private boolean stopTime;
    private int filterIterations = 4;

    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
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
         * And set some OpenGL context attributes, such as that we are using OpenGL 4.3.
         * This is the minimum core version such so that can use compute shaders.
         */
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
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
                } else if (key == GLFW_KEY_KP_ADD && action == GLFW_RELEASE) {
                    specularFactor += 0.1f;
                    if (specularFactor > 1.0f)
                        specularFactor = 1.0f;
                    System.out.println("Specular factor = " + specularFactor);
                    frameNumber = 0;
                } else if (key == GLFW_KEY_KP_SUBTRACT && action == GLFW_RELEASE) {
                    specularFactor -= 0.1f;
                    if (specularFactor < 0.0f)
                        specularFactor = 0.0f;
                    System.out.println("Specular factor = " + specularFactor);
                    frameNumber = 0;
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
        createComputeProgram();
        initComputeProgram();
        createQuadProgram();
        initQuadProgram();
        createFilterProgram();
        initFilterProgram();
        createFilterFBO();

        glfwShowWindow(window);
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
     * Create the tracing compute shader program.
     */
    private void createComputeProgram() throws IOException {
        /*
         * Create our GLSL compute shader. It does not look any different to creating a
         * program with vertex/fragment shaders. The only thing that changes is the
         * shader type, now being GL_COMPUTE_SHADER.
         */
        int program = glCreateProgram();
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/random.glsl", GL_COMPUTE_SHADER);
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial5/raytracing.glsl", GL_COMPUTE_SHADER);
        glAttachShader(program, random);
        glAttachShader(program, cshader);
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
        n_phiUniform = glGetUniformLocation(filterProgram, "n_phi");
        p_phiUniform = glGetUniformLocation(filterProgram, "p_phi");
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
     * Initialize the compute shader. This just binds the program briefly to obtain
     * the uniform locations, the declared work group size values and the image
     * binding point of the framebuffer image.
     */
    private void initComputeProgram() {
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
        timeUniform = glGetUniformLocation(computeProgram, "time");
        blendFactorUniform = glGetUniformLocation(computeProgram, "blendFactor");
        multipleImportanceSampledUniform = glGetUniformLocation(computeProgram, "multipleImportanceSampled");
        phongExponentUniform = glGetUniformLocation(computeProgram, "phongExponent");
        specularFactorUniform = glGetUniformLocation(computeProgram, "specularFactor");

        /* Query the "image binding point" of the image uniforms */
        IntBuffer params = BufferUtils.createIntBuffer(1);
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);
        /*
         * Now we are also outputting the normal and the position of the primary ray hit
         * for the edge-avoiding À-Trous filter pass. For the sake of performance we
         * should really be using hybrid path tracing now, with using the rasterizer for
         * a position/normal pre-pass to avoid tracing the primary rays and using them
         * to output normal/position. But that'll come later.
         */
        loc = glGetUniformLocation(computeProgram, "normalbufferImage");
        glGetUniformiv(computeProgram, loc, params);
        normalbufferImageBinding = params.get(0);
        loc = glGetUniformLocation(computeProgram, "positionbufferImage");
        glGetUniformiv(computeProgram, loc, params);
        positionbufferImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer that the compute shader
     * will write/render to.
     */
    private void createFramebufferTexture() {
        pttex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, pttex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
        ftex0 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ftex0);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
        ftex1 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ftex1);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
        normalTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8_SNORM, width, height);
        positionTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, positionTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);
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
        glDeleteFramebuffers(filterFBO);
        glDeleteTextures(pttex);
        glDeleteTextures(ftex0);
        glDeleteTextures(ftex1);
        glDeleteTextures(normalTex);
        glDeleteTextures(positionTex);
        createFramebufferTexture();
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
     * Compute a new frame by tracing the scene using our compute shader. The
     * resulting pixels will be written to the framebuffer texture {@link #pttex}.
     * <p>
     * See the JavaDocs of this method in {@link Tutorial2} for a better
     * explanation.
     */
    private void trace(float time) {
        glUseProgram(computeProgram);

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
         * Submit the current time to the compute shader for temporal variance in the
         * generated random numbers.
         */
        glUniform1f(timeUniform, time);

        /*
         * We are going to average the last computed average and this frame's result, so
         * here we compute the blend factor between old frame and new frame. See the
         * class JavaDocs above for more information about that.
         */
        float blendFactor = frameNumber / (frameNumber + 1.0f);
        glUniform1f(blendFactorUniform, blendFactor);
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
         * given shader work item.
         */
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

        /*
         * Bind level 0 of framebuffer texture as writable and readable image in the
         * shader. This tells OpenGL that any writes to and reads from the image defined
         * in our shader is going to go to the first level of the texture 'tex'.
         */
        glBindImageTexture(framebufferImageBinding, pttex, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindImageTexture(normalbufferImageBinding, normalTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8_SNORM);
        glBindImageTexture(positionbufferImageBinding, positionTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);

        /*
         * Compute appropriate global work size dimensions.
         */
        int numGroupsX = (int) Math.ceil((double)width / workGroupSizeX);
        int numGroupsY = (int) Math.ceil((double)height / workGroupSizeY);

        /* Invoke the compute shader. */
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        /*
         * Synchronize all writes to the framebuffer image before we let OpenGL source
         * texels from it afterwards when rendering the final image with the full-screen
         * quad.
         */
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindImageTexture(normalbufferImageBinding, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8_SNORM);
        glBindImageTexture(positionbufferImageBinding, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
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
         * Use some good edge function weights.
         *
         * Play with these to get somewhat an understanding what they cause.
         */
        float c_phi0 = 3.3f;
        float n_phi0 = 1E-2f; // <- normals are VERY important
        float p_phi0 = 5.5f;
        /*
         * Now, perform 'n' iterations of the filter algorithm.
         */
        for (int i = 0; i < n; i++) {
            /*
             * Each iteration potentially needs different edge function weights.
             */
            glUniform1f(c_phiUniform, 1.0f / i * c_phi0);
            glUniform1f(n_phiUniform, 1.0f / (1 << i) * n_phi0);
            glUniform1f(p_phiUniform, 1.0f / (1 << i) * p_phi0);
            glUniform1i(stepwidthUniform, (1 << (i + 1)) - 1);
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
        float lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            float thisTime = System.nanoTime();
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
             * Call the compute shader to trace the scene and produce an image in our
             * framebuffer texture.
             */
            trace(!stopTime ? thisTime / 1E9f : 1.0f);
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