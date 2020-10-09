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
 * This time we are going to add a small spherical light source to our scene.
 * With that we'll see that uniform hemisphere sampling is not quite up to the
 * task and produces very strong noise/variance, since the generated sample
 * directions will very likely miss the light source.
 * <p>
 * To combat this, we will use "Multiple Importance" sampling introduced by Eric
 * Veach's 1998 PhD thesis "Robust Monte Carlo Methods for Light Transport
 * Simulation" (see chapter 9). The idea is to sample not based on the BRDF but
 * around the direction towards the light source. This has the potential of
 * significantly reducing the variance in the Monte Carlo estimate, especially
 * in our simple "room with a table" scene where we will place a small spherical
 * light, since that light is visible from all locations but the ones under the
 * table.
 * 
 * @author Kai Burjack
 */
public class Tutorial4 {

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
    private int multipleImportanceSampledUniform;
    private int phongExponentUniform;
    private int specularFactorUniform;
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

    private float mouseX, mouseY;
    private boolean mouseDown;
    private int frameNumber;
    private boolean multipleImportanceSampled;
    private float phongExponent = 128.0f;
    private float specularFactor = 0.0f;

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
        window = glfwCreateWindow(width, height, "Path Tracing Tutorial 4", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        System.out.println("Press WSAD, LCTRL, SPACE to move around in the scene.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Press 'C' to toggle between uniform and multiple importance sampling.");
        System.out.println("Press +/- to increase/decrease the specular factor.");
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
                if (width > 0 && height > 0 && (Tutorial4.this.width != width || Tutorial4.this.height != height)) {
                    Tutorial4.this.width = width;
                    Tutorial4.this.height = height;
                    Tutorial4.this.resetFramebuffer = true;
                    /*
                     * Reset the frame counter. Any change in framebuffer size will reset the
                     * current accumulated result.
                     */
                    Tutorial4.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - Tutorial4.this.mouseX;
                    float deltaY = (float) y - Tutorial4.this.mouseY;
                    Tutorial4.this.viewMatrix.rotateLocalY(deltaX * 0.01f);
                    Tutorial4.this.viewMatrix.rotateLocalX(deltaY * 0.01f);
                    /*
                     * Reset the frame counter. Any change in camera position will reset the current
                     * accumulated result.
                     */
                    Tutorial4.this.frameNumber = 0;
                }
                Tutorial4.this.mouseX = (float) x;
                Tutorial4.this.mouseY = (float) y;
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    Tutorial4.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    Tutorial4.this.mouseDown = false;
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
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial4/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial4/quad.fs.glsl", GL_FRAGMENT_SHADER);
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
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial4/random.glsl", GL_COMPUTE_SHADER);
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial4/raytracing.glsl", GL_COMPUTE_SHADER);
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
        multipleImportanceSampledUniform = glGetUniformLocation(computeProgram, "multipleImportanceSampled");
        phongExponentUniform = glGetUniformLocation(computeProgram, "phongExponent");
        specularFactorUniform = glGetUniformLocation(computeProgram, "specularFactor");

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
     * resulting pixels will be written to the framebuffer texture {@link #tex}.
     * <p>
     * See the JavaDocs of this method in {@link Tutorial2} for a better
     * explanation.
     */
    private void trace(float elapsedSeconds) {
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
        glUniform1f(timeUniform, elapsedSeconds);

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
            trace(thisTime / 1E9f);
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
        new Tutorial4().run();
    }

}