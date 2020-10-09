/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.ARBShaderAtomicCounters;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static java.lang.Math.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Same as {@link Demo} but it uses a pseudo-random number generator based on
 * {@link ARBShaderAtomicCounters}.
 * <p>
 * This should showcase the use of atomic counter buffers in GLSL shaders. In
 * detail, there is a new <code>random()</code> GLSL function which implicitly
 * takes an atomic counter and increments it everytime the function is called.
 * What was once the combination of screen coordinate parameterization and time
 * to achieve variance of the generated random value over each pixel is now a
 * single atomic counter.
 * <p>
 * In later demos (especially with correct "by the book" photon mapping), which
 * use rays originating from the light source we are going to need this
 * approach, since otherwise we cannot have different random reflection
 * directions, since the direction will always depend on constant "random"
 * values. This will be explained in later demos!
 *
 * @author Kai Burjack
 */
public class AtomicDemo {

    private long window;
    private int width = 1024;
    private int height = 768;
    private boolean resetFramebuffer = true;

    private int tex;
    private int vao;
    private int computeProgram;
    private int quadProgram;
    private int sampler;

    private int eyeUniform;
    private int ray00Uniform;
    private int ray10Uniform;
    private int ray01Uniform;
    private int ray11Uniform;
    private int blendFactorUniform;
    private int bounceCountUniform;
    private int framebufferImageBinding;
    private int atomicBuffer;

    private int workGroupSizeX;
    private int workGroupSizeY;

    private float mouseDownX;
    private float mouseX;
    private boolean mouseDown;

    private float currRotationAboutY = 0.0f;
    private float rotationAboutY = 0.8f;

    private int frameNumber;
    private int bounceCount = 1;

    private IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
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
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 4.3 or higher. The Demo33 version works on OpenGL 3.3 or higher.");
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
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Raytracing Demo (compute shader + atomic counter)", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Press keypad '+' or 'page up' to increase the number of bounces.");
        System.out.println("Press keypad '-' or 'page down' to decrease the number of bounces.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE) {
                    return;
                }

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_KP_ADD || key == GLFW_KEY_PAGE_UP) {
                    int newBounceCount = Math.min(4, AtomicDemo.this.bounceCount + 1);
                    if (newBounceCount != AtomicDemo.this.bounceCount) {
                        AtomicDemo.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + AtomicDemo.this.bounceCount);
                        AtomicDemo.this.frameNumber = 0;
                    }
                } else if (key == GLFW_KEY_KP_SUBTRACT || key == GLFW_KEY_PAGE_DOWN) {
                    int newBounceCount = Math.max(1, AtomicDemo.this.bounceCount - 1);
                    if (newBounceCount != AtomicDemo.this.bounceCount) {
                        AtomicDemo.this.bounceCount = newBounceCount;
                        System.out.println("Ray bounce count is now: " + AtomicDemo.this.bounceCount);
                        AtomicDemo.this.frameNumber = 0;
                    }
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (AtomicDemo.this.width != width || AtomicDemo.this.height != height)) {
                    AtomicDemo.this.width = width;
                    AtomicDemo.this.height = height;
                    AtomicDemo.this.resetFramebuffer = true;
                    AtomicDemo.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                AtomicDemo.this.mouseX = (float) x;
                if (mouseDown) {
                    AtomicDemo.this.frameNumber = 0;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    AtomicDemo.this.mouseDownX = AtomicDemo.this.mouseX;
                    AtomicDemo.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    AtomicDemo.this.mouseDown = false;
                    AtomicDemo.this.rotationAboutY = AtomicDemo.this.currRotationAboutY;
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
        quadFullScreenVao();
        createComputeProgram();
        initComputeProgram();
        createQuadProgram();
        initQuadProgram();
        createAtomicBuffer();

        glfwShowWindow(window);
    }

    /**
     * Create a VAO with a full-screen quad VBO.
     */
    private void quadFullScreenVao() {
        this.vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
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
        glBindVertexArray(0);
    }

    /**
     * Create a shader object from the given classpath resource.
     *
     * @param resource
     *            the class path
     * @param type
     *            the shader type
     *
     * @return the shader object id
     *
     * @throws IOException
     */
    static int createShader(String resource, int type) throws IOException {
        return createShader(resource, type, null);
    }

    /**
     * Create a shader object from the given classpath resource.
     *
     * @param resource
     *            the class path
     * @param type
     *            the shader type
     * @param version
     *            the GLSL version to prepend to the shader source, or null
     *
     * @return the shader object id
     *
     * @throws IOException
     */
    static int createShader(String resource, int type, String version) throws IOException {
        int shader = glCreateShader(type);

        ByteBuffer source = ioResourceToByteBuffer(resource, 8192);

        if (version == null) {
            PointerBuffer strings = BufferUtils.createPointerBuffer(1);
            IntBuffer lengths = BufferUtils.createIntBuffer(1);

            strings.put(0, source);
            lengths.put(0, source.remaining());

            glShaderSource(shader, strings, lengths);
        } else {
            PointerBuffer strings = BufferUtils.createPointerBuffer(2);
            IntBuffer lengths = BufferUtils.createIntBuffer(2);

            ByteBuffer preamble = memUTF8("#version " + version + "\n", false);

            strings.put(0, preamble);
            lengths.put(0, preamble.remaining());

            strings.put(1, source);
            lengths.put(1, source.remaining());

            glShaderSource(shader, strings, lengths);
        }

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

    private void createAtomicBuffer() {
        atomicBuffer = glGenBuffers();
        glBindBuffer(GL_ATOMIC_COUNTER_BUFFER, atomicBuffer);
        glBufferData(GL_ATOMIC_COUNTER_BUFFER, 4, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ATOMIC_COUNTER_BUFFER, 0);
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = AtomicDemo.createShader("org/lwjgl/demo/opengl/raytracing/quad.vs", GL_VERTEX_SHADER, "330");
        int fshader = AtomicDemo.createShader("org/lwjgl/demo/opengl/raytracing/quad.fs", GL_FRAGMENT_SHADER, "330");
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
     *
     * @throws IOException
     */
    private void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = createShader("org/lwjgl/demo/opengl/raytracing/raytracingAtomic.glslcs", GL_COMPUTE_SHADER);
        int randomCommon = createShader("org/lwjgl/demo/opengl/raytracing/randomCommon.glsl", GL_COMPUTE_SHADER, "330");
        int random = createShader("org/lwjgl/demo/opengl/raytracing/randomAtomic.glsl", GL_COMPUTE_SHADER);
        glAttachShader(program, cshader);
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
        this.computeProgram = program;
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
     * Initialize the compute shader.
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
        blendFactorUniform = glGetUniformLocation(computeProgram, "blendFactor");
        bounceCountUniform = glGetUniformLocation(computeProgram, "bounceCount");

        /* Query the "image binding point" of the image uniform */
        IntBuffer params = BufferUtils.createIntBuffer(1);
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer.
     */
    private void createFramebufferTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
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

    /**
     * Recreate the framebuffer when the window size changes.
     */
    private void resizeFramebufferTexture() {
        glDeleteTextures(tex);
        createFramebufferTexture();
    }

    /**
     * Compute one frame by tracing the scene using our compute shader and
     * presenting that image on the screen.
     */
    private void trace() {
        glUseProgram(computeProgram);

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

        /* Bind atomic counter buffer */
        glBindBufferBase(GL_ATOMIC_COUNTER_BUFFER, 0, atomicBuffer);
        /*
         * Initialize the atomic counter with a seed (current time) to avoid
         * uint32 wrap-around patterns when 2^32 is divisible by the framebuffer
         * pixel count.
         */
        intBuffer.put(0, (int) System.nanoTime());
        glBufferSubData(GL_ATOMIC_COUNTER_BUFFER, 0, intBuffer);

        /*
         * We are going to average multiple successive frames, so here we
         * compute the blend factor between old frame and new frame.
         * = 0.0 - use only the new frame
         * > 0.0 - blend between old frame and new frame
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

        /* Bind level 0 of framebuffer texture as writable image in the shader. */
        glBindImageTexture(framebufferImageBinding, tex, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);

        /*
         * Compute appropriate global work size dimensions.
         */
        int numGroupsX = (int) Math.ceil((double)width / workGroupSizeX);
        int numGroupsY = (int) Math.ceil((double)height / workGroupSizeY);

        /* Invoke the compute shader. */
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        /*
         * Synchronize all writes to the framebuffer image before we let OpenGL
         * source texels from it afterwards when rendering the final image with
         * the full-screen quad.
         */
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindBufferBase(GL_ATOMIC_COUNTER_BUFFER, 0, 0);
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
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
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
        new AtomicDemo().run();
    }

}