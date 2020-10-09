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

import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * In this part we create a path tracer using Monte Carlo integration/simulation
 * to estimate the amount of light reaching any visible surface point in the
 * scene.
 * <p>
 * That means, we now don't just shoot primary rays from the eye/camera into the
 * scene to check for simple intersections with scene geometry and then simply
 * rendering those intersections as some grayscale color. No, this time we want
 * to actually simulate the light as it bounces around in our scene.
 * <p>
 * For that, we more or less use the same scene from before but with an open
 * ceiling from which light can reach the inner areas of our "room". Starting
 * with the current eye/camera position, we shoot rays into the scene (just like
 * before), but unlike before when a ray hits a box we generate a reflected ray
 * and continue following that "path" of up to three bounces/rays until either
 * one of those rays escapes the room through the open ceiling into the light,
 * or not. In the last case, the contribution of that ray's light will be zero.
 * <p>
 * Like mentioned we use Monte Carlo integration to estimate the amount of light
 * traveling off a visible surface point towards the eye/camera. That means we
 * need to generate many rays and average their light contribution for each
 * pixel in our framebuffer. We could implement all of this in a single compute
 * shader invocation by using a pre-defined number of samples to average over,
 * say 1000. But we do not know in advance whether that number of samples
 * suffices to generate a good estimation of the actual light transport in the
 * scene and whether we complete the shader invocation before the graphics card
 * driver assumes the kernel invocation to hang and reset the driver.
 * <p>
 * Because of those reasons we decide to map a single iteration of the Monte
 * Carlo integration to a single shader invocation. But now we need to average
 * the framebuffer contents of the individual invocation results somehow,
 * because for any number of iterations we need the arithmetic mean/average over
 * the last N results. There is an easy way to achieve this via linearly
 * interpolating between the (N-1) average and the current iteration result N.
 * We just need to find a blending weight `a` such that:
 * 
 * <pre>
 * S(1) = O(0)/2 + O(1)/2
 * S(2) = O(0)/3 + O(1)/3 + O(2)/3
 * S(3) = O(0)/4 + O(1)/4 + O(2)/4 + O(3)/4
 * </pre>
 * 
 * can be formulated recursively via:
 * 
 * <pre>
 * S(i) = S(i - 1) * a + O(i) * (1 - a)
 * </pre>
 * 
 * In order to achieve this, we use <code>a = i/(i-1)</code>
 * 
 * @author Kai Burjack
 */
public class Tutorial2 {

    /**
     * The GLFW window handle.
     */
    private long window;
    private int width = 1200;
    private int height = 800;
    /**
     * Whether we need to recreate our ray tracer framebuffer.
     */
    private boolean resetFramebuffer = true;

    /**
     * The OpenGL texture acting as our framebuffer for the ray tracer.
     */
    private int tex;
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
    /**
     * The binding point in the compute shader of the framebuffer image (level 0 of
     * the {@link #tex} texture).
     */
    private int framebufferImageBinding;
    /**
     * Value of the work group size in X dimension declared in the compute shader.
     */
    private int workGroupSizeX;
    /**
     * Value of the work group size in Y dimension declared in the compute shader.
     */
    private int workGroupSizeY;

    private float mouseDownX;
    private float mouseX;
    private boolean mouseDown;
    private float currRotationAboutY;
    private float rotationAboutY;

    private long firstTime;
    private int frameNumber;

    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private Vector3f cameraPosition = new Vector3f();
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
        window = glfwCreateWindow(width, height, "Path Tracing Tutorial 2", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        /* And set some GLFW callbacks to get notified about events. */

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;
                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        });

        /*
         * We need to get notified when the GLFW window framebuffer size changed (i.e.
         * by resizing the window), in order to recreate our own ray tracer framebuffer
         * texture.
         */
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (Tutorial2.this.width != width || Tutorial2.this.height != height)) {
                    Tutorial2.this.width = width;
                    Tutorial2.this.height = height;
                    Tutorial2.this.resetFramebuffer = true;
                    /*
                     * Reset the frame counter. Any change in framebuffer size will reset the
                     * current accumulated result.
                     */
                    Tutorial2.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                Tutorial2.this.mouseX = (float) x;
                if (mouseDown) {
                    /*
                     * Reset the frame counter. Any change in camera position will reset the current
                     * accumulated result.
                     */
                    Tutorial2.this.frameNumber = 0;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    Tutorial2.this.mouseDownX = Tutorial2.this.mouseX;
                    Tutorial2.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    Tutorial2.this.mouseDown = false;
                    Tutorial2.this.rotationAboutY = Tutorial2.this.currRotationAboutY;
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

        /* Create all needed GL resources */
        createFramebufferTexture();
        createSampler();
        this.vao = glGenVertexArrays();
        createComputeProgram();
        initComputeProgram();
        createQuadProgram();
        initQuadProgram();

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
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial1/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial1/quad.fs.glsl", GL_FRAGMENT_SHADER);
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
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial2/random.glsl", GL_COMPUTE_SHADER);
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial2/raytracing.glsl", GL_COMPUTE_SHADER);
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

        /* Query the "image binding point" of the image uniform */
        IntBuffer params = BufferUtils.createIntBuffer(1);
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer that the compute shader
     * will write/render to.
     */
    private void createFramebufferTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        /*
         * glTexStorage2D only allocates space for the texture, but does not initialize
         * it with any values. This is fine, because we use the texture as output
         * texture in the compute shader and read from it only after we've written to
         * it.
         */
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
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
        glDeleteTextures(tex);
        createFramebufferTexture();
    }

    /**
     * Compute a new frame by tracing the scene using our compute shader. The
     * resulting pixels will be written to the framebuffer texture {@link #tex}.
     * <p>
     * This method differs from the one in {@link Tutorial1} in that we do not just
     * trace a single frame but take the average of a potentially unlimited number
     * of frames. We need to do this for the first time, since our path tracer has
     * become a Monte Carlo integrator to evaluate the rendering equation for simple
     * diffuse surfaces.
     * <p>
     * Essentially when we want to evaluate the rendering equation we need to
     * evaluate many integrals integrating over surface's hemispheres and summing up
     * all the irradiance reaching the surface from every possible direction
     * combined with the BRDF of the surfaces.
     * <p>
     * Monte Carlo integration/simulation is a way to numerically approximate those
     * integrals via a stochastic process. This essentially means we take many
     * numbers of "samples" of possible directions in a surface's hemisphere and
     * check whether light comes along this direction. Then we average over all
     * those sample results.
     * <p>
     * Like described in the class JavaDocs this method will compute the average of
     * the last frames average and the light transport computed during this
     * invocation.
     */
    private void trace() {
        glUseProgram(computeProgram);

        if (mouseDown) {
            /*
             * If mouse is down, compute the camera rotation based on mouse cursor location.
             */
            currRotationAboutY = rotationAboutY + (mouseX - mouseDownX) * 0.01f;
        } else {
            currRotationAboutY = rotationAboutY;
        }

        cameraPosition.set((float) sin(-currRotationAboutY) * 4.0f,
                (float) cos(-currRotationAboutY) * (float) cos(-currRotationAboutY) * 8.0f + 2.0f,
                (float) cos(-currRotationAboutY) * 3.0f);
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);

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
         * Obtain the current "time" and submit it to the compute shader for temporal
         * variance in the generated random numbers.
         */
        long thisTime = System.nanoTime();
        float elapsedSeconds = (thisTime - firstTime) / 1E9f;
        glUniform1f(timeUniform, elapsedSeconds);

        /*
         * We are going to average the last computed average and this frame's result, so
         * here we compute the blend factor between old frame and new frame. See the
         * class JavaDocs above for more information about that.
         */
        float blendFactor = frameNumber / (frameNumber + 1.0f);
        glUniform1f(blendFactorUniform, blendFactor);

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
        glBindImageTexture(framebufferImageBinding, tex, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);

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
        glUseProgram(0);

        /*
         * Increment the frame counter to compute a correct average in the next
         * iteration.
         */
        frameNumber++;
    }

    /**
     * Present the final image on the default framebuffer of the GLFW window.
     */
    private void present() {
        /*
         * Draw the rendered image on the screen using a textured full-screen quad.
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
        /*
         * Our render loop is really simple...
         */
        while (!glfwWindowShouldClose(window)) {
            /*
             * ...we just poll for GLFW window events (as usual).
             */
            glfwPollEvents();
            /*
             * Tell OpenGL about any possibly modified viewport size.
             */
            glViewport(0, 0, width, height);
            /*
             * Call the compute shader to trace the scene and produce an image in our
             * framebuffer texture.
             */
            trace();
            /*
             * Finally we blit/render the framebuffer texture to the default window
             * framebuffer of the GLFW window.
             */
            present();
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
        new Tutorial2().run();
    }

}