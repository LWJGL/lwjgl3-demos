/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing.tutorial;

import org.lwjgl.*;
import org.lwjgl.assimp.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.sampling.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This demo implements hybrid rasterization and path tracing together with
 * temporal anti-aliasing using screen-space sample reprojection as well as
 * spatial edge-avoiding à-trous filtering.
 * <p>
 * Temporal anti-aliasing with reprojection means that texels from an image
 * traced at frame `t-1` will be reused and reprojected onto their new position
 * at time `t` and blended together with the traced result at frame `t`. This
 * allows to effectively increase the sample count even when the camera moves.
 * Previously, when the camera moved, the weight factor was reset so that no
 * previous frame was blended together with the current frame.
 * <p>
 * In addition to temporal anti-aliasing, this demo also uses hybrid
 * rasterization + path tracing in order to accelerate the first bounce by
 * rasterizing the first eye-to-surface ray using rasterization and to produce a
 * robust G-buffer with scene depth and normal information, which will be used
 * by the temporal anti-aliasing step.
 * <p>
 * Also, this demo does not use a GLSL compute shader for the path tracing but
 * combines rasterization with path tracing in a fragment shader. Apart from
 * OpenGL 3.3 users being able to run this demo, this saves memory bandwidth
 * because the reprojection step can be done in a single shader invocation
 * instead of the necessary velocity and color information being written to a
 * render target which will then subsequently be read again by a compute shader.
 * In order to save fragment shader invocations in the rasterize and path trace
 * fragment shader, a previous depth-only pass is performed so that the fragment
 * shader is only executed for visible surfaces.
 * 
 * @author Kai Burjack
 */
public class Tutorial8 {

    private static class Box {
        Vector3f min;
        Vector3f max;
    }

    /**
     * Camera near Z.
     * 
     * TODO: Adjust based on scene depth bounds.
     */
    private static final float MIN_Z = 0.1f;
    /**
     * Camera far Z.
     * 
     * TODO: Adjust based on scene depth bounds.
     */
    private static final float MAX_Z = 50.0f;

    /**
     * The scene as a set of axis-aligned boxes.
     */
    private List<Box> boxes;
    /**
     * The GLFW window handle.
     */
    private long window;
    /**
     * (Initial) window width.
     */
    private int windowWidth = 1920;
    /**
     * (Initial) window height.
     */
    private int windowHeight = 1080;
    /**
     * The factor to scale the rasterized and path traced framebuffer down
     * compared to the window/viewport size. 1 = fullsize, 2 = half-size, etc.
     */
    private int fbSizeScale = 2;
    /**
     * Will be set to <code>true</code> whenever the framebuffer dimensions
     * changed and textures/renderbuffers need to be recreated.
     */
    private boolean rebuildFramebuffer;
    /**
     * VAO holding a full-screen VBO. This is to present the FBO-rendered final
     * color texture to the window.
     */
    private int quadVao;
    /**
     * Uniform Buffer Object to hold our scene as a list of axis-aligned boxes.
     */
    private int ubo;
    /**
     * The index of the "Boxes" UBO in the rasterize-and-trace fragment shader.
     */
    private int boxesUboIndex;
    /**
     * The shader program used to rasterize and trace the scene.
     */
    private int rasterAndTraceProgram;
    /**
     * The shader program to do the depth-only pass.
     */
    private int rasterDepthProgram;
    /**
     * The shader program for doing edge-avoiding À-Trous filtering.
     */
    private int filterProgram;
    private int filter_c_phiUniform, filter_n_phiUniform, filter_p_phiUniform,
            filterStepwidthUniform;
    private int filterKernelUniform, filterOffsetUniform;
    /**
     * Ping-pong textures for the À-Trous filter. When doing multiple filter
     * passes, one is the read buffer and the other the render target.
     */
    private int filterTextures[] = new int[2];
    /**
     * Handle to the viewMatrix uniform of the depth-only shader.
     */
    private int depthViewMatrixUniform;
    /**
     * Handle to the projMatrix uniform of the depth-only shader.
     */
    private int depthProjMatrixUniform;
    /**
     * Handle to the cameraPosition uniform in the rasterize-and-trace shader.
     */
    private int cameraPositionUniform;
    /**
     * Handle to the viewMatrix uniform in the rasterize-and-trace shader.
     */
    private int viewMatrixUniform;
    /**
     * Handle to the projectionMatrix uniform in the rasterize-and-trace shader.
     */
    private int projectionMatrixUniform;
    /**
     * Texture handles of the color render targets of the "current" and
     * "previous" frame in the rasterize-and-trace shader. Those will be indexed
     * via the {@link #curr} and {@link #prev} indices for temporal filtering.
     */
    private int[] colorTextures = new int[2];
    /**
     * Texture handles for the view depth render targets of the "current" and
     * "previous" frame in the rasterize-and-trace shader. Those will be indexed
     * via the {@link #curr} and {@link #prev} indices for temporal filtering.
     */
    private int[] viewDepthTextures = new int[2];
    /**
     * Texture handle for the normal render target in the rasterize-and-trace
     * shader. Will be used/read in the edge-avoiding À-Trous filter.
     */
    private int normalTexture;
    /**
     * The FBO used to render to all render targets defined above.
     */
    private int fbo;
    /**
     * Handle to a renderbuffer for the depth buffer.
     */
    private int depthBuffer;
    /**
     * VAO holding the VBO of the scene for rasterization.
     */
    private int vaoScene;
    /**
     * Shader program for the full-screen quad to present the final image to the
     * window.
     */
    private int quadProgram;
    /**
     * Sampler objects to read from the various textures.
     */
    private int linearSampler, nearestSampler;
    /**
     * Handle to the "time" uniform in the rasterize-and-trace shader. This is
     * used as a seed for the pseudo-random number generator.
     */
    private int timeUniform;
    /**
     * Number of boxes uniform in the rasterize-and-trace shader.
     */
    private int numBoxesUniform;
    /**
     * Last mouse X and Y coordinates to compute the relative mouse delta
     * motion.
     */
    private float mouseX, mouseY;
    /**
     * Whether the left mouse button is down.
     */
    private boolean mouseDown;
    /**
     * Key-down states of all keyboard keys.
     */
    private final boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    /**
     * The projection matrix.
     */
    private final Matrix4f projMatrix = new Matrix4f();
    /**
     * The view matrices of the current and previous frame.
     */
    private final Matrix4f[] viewMatrix = { new Matrix4f(), new Matrix4f() };
    /**
     * {@link FloatBuffer} to store a {@link Matrix4f} for upload as a shader
     * uniform.
     */
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    /**
     * These two values will alternate between 0 and 1 to indicate which of the
     * various render targets are the "current" and the "previous" ones.
     */
    private int curr = 0, prev = 1;
    /**
     * Vector for temporary calculations.
     */
    private final Vector3f tmpVector3f = new Vector3f();
    /**
     * Height of the eye of the player.
     */
    private static final float EYE_HEIGHT = 1.0f;
    /**
     * The current camera position.
     */
    private final Vector3f cameraPosition = new Vector3f(-5.0f, EYE_HEIGHT,
            3.0f);
    /**
     * Rotation angles of the camera.
     */
    private float alpha, beta;
    /**
     * Draw buffers for the FBO to alternate rendering between the current and
     * previous render targets. The first draw buffer is the color output, the
     * second the view depth and the third the normal.
     */
    private final IntBuffer[] renderBuffers = {
            BufferUtils.createIntBuffer(3).put(0, GL_COLOR_ATTACHMENT0)
                    .put(1, GL_COLOR_ATTACHMENT1).put(2, GL_COLOR_ATTACHMENT4),
            BufferUtils.createIntBuffer(3).put(0, GL_COLOR_ATTACHMENT2)
                    .put(1, GL_COLOR_ATTACHMENT3)
                    .put(2, GL_COLOR_ATTACHMENT4) };

    /*
     * GLFW callbacks.
     */
    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private Callback debugProc;
    private GLCapabilities caps;

    private void init() throws IOException {
        glfwSetErrorCallback(
                errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        /*
         * The tracing is implemented in the rasterizer fragment shader, so we
         * don't need compute shaders and SSBOs and can get away with OpenGL
         * 3.3.
         */
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(windowWidth, windowHeight,
                "Hybrid Path Tracing with Spatiotemporal Filtering", NULL,
                NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        System.out.println("Press WSAD to move around in the scene.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Move the mouse to look around.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action,
                    int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        });
        glfwSetFramebufferSizeCallback(window,
                fbCallback = new GLFWFramebufferSizeCallback() {
                    @Override
                    public void invoke(long window, int width, int height) {
                        if (width > 0 && height > 0 && (windowWidth != width
                                || windowHeight != height)) {
                            windowWidth = width;
                            windowHeight = height;
                            rebuildFramebuffer = true;
                        }
                    }
                });
        glfwSetCursorPosCallback(window,
                cpCallback = new GLFWCursorPosCallback() {
                    @Override
                    public void invoke(long window, double x, double y) {
                        if (mouseDown) {
                            float deltaX = (float) x - mouseX;
                            float deltaY = (float) y - mouseY;
                            alpha += deltaY * 5E-3f;
                            alpha = Math.max(-(float) Math.PI / 2, alpha);
                            alpha = Math.min((float) Math.PI / 2, alpha);
                            beta += deltaX * 5E-3f;
                        }
                        mouseX = (float) x;
                        mouseY = (float) y;
                    }
                });
        glfwSetMouseButtonCallback(window,
                mbCallback = new GLFWMouseButtonCallback() {
                    @Override
                    public void invoke(long window, int button, int action,
                            int mods) {
                        if (action == GLFW_PRESS
                                && button == GLFW.GLFW_MOUSE_BUTTON_1) {
                            mouseDown = true;
                        } else if (action == GLFW_RELEASE
                                && button == GLFW.GLFW_MOUSE_BUTTON_1) {
                            mouseDown = false;
                        }
                    }
                });
        /*
         * Position the window in the center of the screen.
         */
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2,
                (vidmode.height() - windowHeight) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        /*
         * Account for HiDPI displays by querying the actual framebuffer
         * dimensions.
         */
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize),
                    memAddress(framebufferSize) + 4);
            windowWidth = framebufferSize.get(0);
            windowHeight = framebufferSize.get(1);
        }
        /* Setup initial camera settings */
        projMatrix.setPerspective((float) Math.toRadians(60.0f),
                (float) windowWidth / windowHeight, MIN_Z, MAX_Z);
        for (int i = 0; i < 2; i++) {
            viewMatrix[i].setLookAt(cameraPosition,
                    new Vector3f(0.0f, EYE_HEIGHT, 0.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f));
        }
        /* Initialize resources */
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        importSceneAsBoxes();
        createSceneVao();
        this.quadVao = glGenVertexArrays();
        createDepthProgram();
        initDepthProgram();
        createSceneUBO();
        createSamplers();
        createFramebufferTextures();
        createFramebufferObject();
        createRasterAndTraceProgram();
        initRasterAndTraceProgram();
        createFilterProgram();
        initFilterProgram();
        if (!caps.GL_NV_draw_texture) {
            createQuadProgram();
            initQuadProgram();
        }
        /* Set some global OpenGL state */
        glEnable(GL_CULL_FACE);
        glfwShowWindow(window);
    }

    /**
     * Import the level/scene from a Wavefront OBJ file via Assimp. Each mesh in
     * the model will be considered an AABB.
     */
    private void importSceneAsBoxes() throws IOException {
        ByteBuffer bb = ioResourceToByteBuffer(
                "org/lwjgl/demo/opengl/raytracing/tutorial8/cubes.obj", 8192);
        AIScene scene = Assimp.aiImportFileFromMemory(bb, 0, "obj");
        int meshCount = scene.mNumMeshes();
        PointerBuffer meshesBuffer = scene.mMeshes();
        boxes = new ArrayList<>();
        System.out.println("Loaded level with " + meshCount + " boxes");
        /*
         * Read all meshes, compute their min,max corners and build a AABB from
         * it.
         */
        for (int i = 0; i < meshCount; i++) {
            AIMesh mesh = AIMesh.create(meshesBuffer.get(i));
            int verticesCount = mesh.mNumVertices();
            AIVector3D.Buffer vertices = mesh.mVertices();
            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE,
                    Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE,
                    -Float.MAX_VALUE);
            for (int v = 0; v < verticesCount; v++) {
                AIVector3D vec = vertices.get(v);
                Vector3f v3 = new Vector3f(vec.x(), vec.y(), vec.z());
                min.min(v3);
                max.max(v3);
            }
            Box box = new Box();
            box.min = min;
            box.max = max;
            boxes.add(box);
        }
        Assimp.aiReleaseImport(scene);
    }

    /**
     * Create a VAO/VBO of the boxes in the scene for the rasterizer. Each
     * vertex will have position and normal information.
     */
    private void createSceneVao() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils
                .createByteBuffer(boxes.size() * 2 * 4 * (3 + 3) * 6 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            triangulateBox(box.min, box.max, fv);
        }
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 4 * (3 + 3), 4 * 3);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        this.vaoScene = vao;
    }

    /**
     * Create the depth-only shader program. This will be used to render a depth
     * pre-pass in order for the later rasterize-and-trace shader to only
     * compute shading for actually visible surfaces.
     */
    private void createDepthProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader(
                "org/lwjgl/demo/opengl/raytracing/tutorial8/rasterDepth.vs",
                GL_VERTEX_SHADER);
        int fshader = createShader(
                "org/lwjgl/demo/opengl/raytracing/tutorial8/rasterDepth.fs",
                GL_FRAGMENT_SHADER);
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
        this.rasterDepthProgram = program;
    }

    private void initDepthProgram() {
        glUseProgram(rasterDepthProgram);
        depthViewMatrixUniform = glGetUniformLocation(rasterDepthProgram,
                "viewMatrix");
        depthProjMatrixUniform = glGetUniformLocation(rasterDepthProgram,
                "projMatrix");
        glUseProgram(0);
    }

    /**
     * Build a UBO from the AABBs of the scene for the path tracer to traverse.
     * In this implementation we will not use any spatial acceleration
     * structure, such as a BVH.
     */
    private void createSceneUBO() {
        this.ubo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, ubo);
        ByteBuffer bb = BufferUtils
                .createByteBuffer(4 * (4 + 4) * boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            bb.putFloat(box.min.x).putFloat(box.min.y).putFloat(box.min.z)
                    .putFloat(0.0f);
            bb.putFloat(box.max.x).putFloat(box.max.y).putFloat(box.max.z)
                    .putFloat(0.0f);
        }
        bb.flip();
        glBufferData(GL_UNIFORM_BUFFER, bb, GL_STATIC_DRAW);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    private void createSamplers() {
        this.nearestSampler = glGenSamplers();
        this.linearSampler = glGenSamplers();
        glSamplerParameteri(this.linearSampler, GL_TEXTURE_MIN_FILTER,
                GL_LINEAR);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_MIN_FILTER,
                GL_NEAREST);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_WRAP_S,
                GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_WRAP_T,
                GL_CLAMP_TO_EDGE);
    }

    /**
     * Create all render targets/textures.
     */
    private void createFramebufferTextures() {
        glGenTextures(this.colorTextures);
        glGenTextures(this.viewDepthTextures);
        glGenTextures(this.filterTextures);
        /*
         * Create textures for alternating current/previous render targets.
         */
        for (int i = 0; i < 2; i++) {
            glBindTexture(GL_TEXTURE_2D, colorTextures[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F,
                    windowWidth / fbSizeScale, windowHeight / fbSizeScale, 0,
                    GL_RGBA, GL_FLOAT, 0L);
            glBindTexture(GL_TEXTURE_2D, viewDepthTextures[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, windowWidth / fbSizeScale,
                    windowHeight / fbSizeScale, 0, GL_RED, GL_FLOAT, 0L);
            glBindTexture(GL_TEXTURE_2D, filterTextures[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F,
                    windowWidth / fbSizeScale, windowHeight / fbSizeScale, 0,
                    GL_RGBA, GL_FLOAT, 0L);
        }
        this.normalTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8_SNORM,
                windowWidth / fbSizeScale, windowHeight / fbSizeScale, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, 0L);
    }

    /**
     * Create the FBO to render to various render targets in the depth-only,
     * rasterize-and-trace as well as filter programs.
     * <p>
     * The respective render targets will be selected via glDrawBuffers for each
     * render pass.
     */
    private void createFramebufferObject() {
        this.fbo = glGenFramebuffers();
        this.depthBuffer = glGenRenderbuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24,
                windowWidth / fbSizeScale, windowHeight / fbSizeScale);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, colorTextures[0], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
                GL_TEXTURE_2D, viewDepthTextures[0], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2,
                GL_TEXTURE_2D, colorTextures[1], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3,
                GL_TEXTURE_2D, viewDepthTextures[1], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT4,
                GL_TEXTURE_2D, normalTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT5,
                GL_TEXTURE_2D, filterTextures[0], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT6,
                GL_TEXTURE_2D, filterTextures[1], 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_RENDERBUFFER, depthBuffer);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Create the rasterize-and-trace shader program. After the depth-only pass,
     * this will rasterize the visible surfaces of the scene and shade them via
     * tracing 1 sample-per-pixel through the scene (the AABBs in the UBO).
     */
    private void createRasterAndTraceProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/raster.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/rasterAndTrace.fs", GL_FRAGMENT_SHADER);
        int fshader2 = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/random.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, fshader2);
        glBindAttribLocation(program, 0, "vertexPosition");
        glBindAttribLocation(program, 1, "vertexNormal");
        glBindAttribLocation(program, 2, "vertexIsLight");
        glBindFragDataLocation(program, 0, "color_out");
        glBindFragDataLocation(program, 1, "viewDepth_out");
        glBindFragDataLocation(program, 2, "normal_out");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.rasterAndTraceProgram = program;
    }

    private void initRasterAndTraceProgram() {
        glUseProgram(rasterAndTraceProgram);
        cameraPositionUniform = glGetUniformLocation(rasterAndTraceProgram,
                "cameraPosition");
        viewMatrixUniform = glGetUniformLocation(rasterAndTraceProgram,
                "viewMatrix");
        projectionMatrixUniform = glGetUniformLocation(rasterAndTraceProgram,
                "projectionMatrix");
        timeUniform = glGetUniformLocation(rasterAndTraceProgram, "time");
        numBoxesUniform = glGetUniformLocation(rasterAndTraceProgram,
                "numBoxes");
        int prevColorTexUniform = glGetUniformLocation(rasterAndTraceProgram,
                "prevColorTex");
        glUniform1i(prevColorTexUniform, 0);
        int prevViewDepthTexUniform = glGetUniformLocation(
                rasterAndTraceProgram, "prevViewDepthTex");
        glUniform1i(prevViewDepthTexUniform, 1);
        boxesUboIndex = glGetUniformBlockIndex(rasterAndTraceProgram, "Boxes");
        glUseProgram(0);
    }

    /**
     * Create the edge-avoiding À-Trous filter shader program. This will
     * effectively be a low-pass filter (blur) to reduce high-variance noise in
     * the path traced image, avoiding blurring over normal or depth
     * discontinuities (i.e. edges).
     */
    private void createFilterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/atrous.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/atrous.fs.glsl", GL_FRAGMENT_SHADER);
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

    private void initFilterProgram() {
        glUseProgram(filterProgram);
        glUniform1i(glGetUniformLocation(filterProgram, "colorMap"), 0);
        glUniform1i(glGetUniformLocation(filterProgram, "normalMap"), 1);
        glUniform1i(glGetUniformLocation(filterProgram, "depthMap"), 2);
        filter_c_phiUniform = glGetUniformLocation(filterProgram, "c_phi");
        filter_n_phiUniform = glGetUniformLocation(filterProgram, "n_phi");
        filter_p_phiUniform = glGetUniformLocation(filterProgram, "p_phi");
        filterStepwidthUniform = glGetUniformLocation(filterProgram,
                "stepwidth");
        filterKernelUniform = glGetUniformLocation(filterProgram, "kernel");
        filterOffsetUniform = glGetUniformLocation(filterProgram, "offset");
        for (int i = 0, y = -1; y <= 1; y++)
            for (int x = -1; x <= 1; x++, i++)
                glUniform2i(filterOffsetUniform + i, x, y);
        /*
         * Generate a simple 3x3 Gaussian kernel for the filter weights.
         */
        float[] weights = new float[9];
        Convolution.gaussianKernel(3, 3, 2.0f, weights);
        glUniform1fv(filterKernelUniform, weights);
        glUseProgram(0);
    }

    /**
     * Create the full-screen quad shader program.
     */
    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8/quad.fs.glsl", GL_FRAGMENT_SHADER);
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

    private void initQuadProgram() {
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    /**
     * Recreate render targets and FBO when the framebuffer size changed.
     */
    private void resizeFramebuffer() {
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthBuffer);
        glDeleteTextures(colorTextures);
        glDeleteTextures(viewDepthTextures);
        glDeleteTextures(filterTextures);
        glDeleteTextures(normalTexture);
        createFramebufferTextures();
        createFramebufferObject();
    }

    /**
     * Called once per frame to process possible framebuffer resized and update
     * camera positions.
     * 
     * @param dt the time since the last frame
     */
    private void update(float dt) {
        if (rebuildFramebuffer) {
            resizeFramebuffer();
            projMatrix.setPerspective((float) Math.toRadians(60.0f),
                    (float) windowWidth / windowHeight, MIN_Z, MAX_Z);
            rebuildFramebuffer = false;
        }
        float factor = 1.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 3.0f;
        if (keydown[GLFW_KEY_W]) {
            cameraPosition.sub(
                    viewMatrix[curr].positiveZ(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_S]) {
            cameraPosition.add(
                    viewMatrix[curr].positiveZ(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_A]) {
            cameraPosition.sub(
                    viewMatrix[curr].positiveX(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_D]) {
            cameraPosition.add(
                    viewMatrix[curr].positiveX(tmpVector3f).mul(dt * factor));
        }
        cameraPosition.y = EYE_HEIGHT;
        viewMatrix[curr].rotationX(alpha).rotateY(beta).translate(
                -cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
    }

    /**
     * Render a full-screen quad to present the final color output to the
     * screen.
     * 
     * @param read handle to the texture object to present
     */
    private void present(int read) {
        glViewport(0, 0, windowWidth, windowHeight);
        if (!caps.GL_NV_draw_texture) {
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
            glUseProgram(quadProgram);
            glBindVertexArray(quadVao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, read);
            glBindSampler(0, this.linearSampler);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else {
            NVDrawTexture.glDrawTextureNV(read, linearSampler, 0, 0,
                    windowWidth, windowHeight, 0, 0, 0, 1, 1);
        }
    }

    /**
     * Render a depth-only pass.
     */
    private void rasterDepthOnly() {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glColorMask(false, false, false, false);
        glUseProgram(rasterDepthProgram);
        glUniformMatrix4fv(depthViewMatrixUniform, false,
                viewMatrix[curr].get(matrixBuffer));
        glUniformMatrix4fv(depthProjMatrixUniform, false,
                projMatrix.get(matrixBuffer));
        glViewport(0, 0, windowWidth / fbSizeScale, windowHeight / fbSizeScale);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(vaoScene);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.size());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Render the scene with the rasterize-and-trace shader.
     * 
     * @param elapsedSeconds the total number of seconds since application start
     */
    private void rasterAndTrace(float elapsedSeconds) {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);
        glColorMask(true, true, true, true);
        glUseProgram(rasterAndTraceProgram);
        glUniform3f(cameraPositionUniform, cameraPosition.x, cameraPosition.y,
                cameraPosition.z);
        glUniformMatrix4fv(viewMatrixUniform, false,
                viewMatrix[curr].get(matrixBuffer));
        glUniformMatrix4fv(viewMatrixUniform + 1, false,
                viewMatrix[prev].get(matrixBuffer));
        glUniformMatrix4fv(projectionMatrixUniform, false,
                projMatrix.get(matrixBuffer));
        glBindSampler(0, linearSampler);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, colorTextures[prev]);
        glBindSampler(1, linearSampler);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, viewDepthTextures[prev]);
        glUniform1f(timeUniform, elapsedSeconds);
        glUniform1i(numBoxesUniform, boxes.size());
        glBindBufferRange(GL_UNIFORM_BUFFER, boxesUboIndex, ubo, 0L,
                boxes.size() * (4 + 4) * 4);
        glViewport(0, 0, windowWidth / fbSizeScale, windowHeight / fbSizeScale);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffers(renderBuffers[curr]);
        glClearBufferfv(GL_COLOR, 0, new float[] { 0.98f, 0.99f, 1.0f, 0.0f });
        glClearBufferfv(GL_COLOR, 1,
                new float[] { Float.NaN, 0.0f, 0.0f, 0.0f });
        glClearBufferfv(GL_COLOR, 2, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
        glBindVertexArray(vaoScene);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.size());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Execute 'n' iterations of the edge-avoiding À-Trous filter program. This
     * will alternate rendering (ping-pong) between two textures and the last
     * write texture will be the return value of this method in order for
     * {@link #present(int)} to draw that on the screen.
     * 
     * @param  n the number of filter iterations
     * @return   the write texture of the last iteration
     */
    private int filter(int n) {
        int read = colorTextures[curr], write = filterTextures[0];
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glUseProgram(filterProgram);
        glBindVertexArray(quadVao);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glBindSampler(1, this.nearestSampler);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, viewDepthTextures[curr]);
        glBindSampler(2, this.nearestSampler);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, this.nearestSampler);
        float c_phi0 = 20.4f;
        float n_phi0 = 1E-2f;
        float p_phi0 = 1E-1f;
        for (int i = 0; i < n; i++) {
            glUniform1f(filter_c_phiUniform, 1.0f / i * c_phi0);
            glUniform1f(filter_n_phiUniform, 1.0f / i * n_phi0);
            glUniform1f(filter_p_phiUniform, 1.0f / i * p_phi0);
            glUniform1i(filterStepwidthUniform, i * 2 + 1);
            glBindTexture(GL_TEXTURE_2D, read);
            glDrawBuffers(write == filterTextures[0] ? GL_COLOR_ATTACHMENT5
                    : GL_COLOR_ATTACHMENT6);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (i == 0) {
                read = filterTextures[1];
            }
            int tmp = read;
            read = write;
            write = tmp;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return read;
    }

    /**
     * Swap between current and previous matrices and render target indices.
     */
    private void swapFrames() {
        viewMatrix[prev].set(viewMatrix[curr]);
        curr = 1 - curr;
        prev = 1 - prev;
    }

    private void loop() {
        float lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            float thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            glfwPollEvents();
            update(dt);
            rasterDepthOnly();
            rasterAndTrace(thisTime * 1E-9f);
            int read = filter(3);
            present(read);
            swapFrames();
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
        new Tutorial8().run();
    }

}