/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.demo.util.WavefrontMeshLoader.MeshObject;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.opengl.NVDrawTexture;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.*;
import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Like {@link HybridDemoSsbo} but uses a triangle mesh instead of boxes.
 * <p>
 * We want to get to a real ray tracer soon, and therefore we want to be able to
 * trace triangle meshes.
 * <p>
 * This demo uses a simple non-hierarchical AABB spatial acceleration structure,
 * to first test a ray against the AABB of all triangles of a single object in
 * the mesh before testing all triangles of the object.
 * 
 * @author Kai Burjack
 */
public class HybridDemoSsboTriangles {
    private long window;
    private int width = 1200;
    private int height = 800;
    private boolean resetFramebuffer = true;

    private int raytraceTexture;
    private int vao;
    private int computeProgram;
    private int quadProgram;
    private int rasterProgram;
    private int fbo;
    private int depthRenderBuffer;
    private int vaoScene;
    private int positionTexture;
    private int normalTexture;
    private int trianglesSsbo;
    private int objectsSsbo;
    private int sampler;

    private int timeUniform;
    private int blendFactorUniform;
    private int lightRadiusUniform;
    private int trianglesSsboBinding;
    private int objectsSsboBinding;
    private int framebufferImageBinding;
    private int worldPositionImageBinding;
    private int worldNormalImageBinding;

    private int workGroupSizeX;
    private int workGroupSizeY;

    private int viewMatrixUniform;
    private int projectionMatrixUniform;

    private Mesh mesh;
    private float mouseDownX;
    private float mouseX;
    private boolean mouseDown;

    private float currRotationAboutY = 0.0f;
    private float rotationAboutY = (float) Math.toRadians(-45);

    private long firstTime;
    private int frameNumber;
    private int lightRadius = 4;

    private float cameraRadius = 4.0f;
    private float cameraHeight = 2.0f;
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projMatrix = new Matrix4f();
    private Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 0.0f);
    private Vector3f cameraLookAt = new Vector3f(-0.2f, 0.25f, -0.2f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWMouseButtonCallback mbCallback;

    GLCapabilities caps;
    Callback debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 4.3 or higher.");
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

        window = glfwCreateWindow(width, height, "Raytracing Demo (triangle mesh)", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Hold down any mouse button and drag to rotate.");
        System.out.println("Press arrow down/up to decrease/increase the light's radius.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action == GLFW_PRESS)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_DOWN) {
                    int newRadius = lightRadius - 1;
                    if (newRadius >= 0) {
                        lightRadius = newRadius;
                        frameNumber = 0;
                        System.out.println("Light radius: " + lightRadius * 0.1f);
                    }
                } else if (key == GLFW_KEY_UP) {
                    int newRadius = lightRadius + 1;
                    if (newRadius <= 30) {
                        lightRadius = newRadius;
                        frameNumber = 0;
                        System.out.println("Light radius: " + lightRadius * 0.1f);
                    }
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (HybridDemoSsboTriangles.this.width != width || HybridDemoSsboTriangles.this.height != height)) {
                    HybridDemoSsboTriangles.this.width = width;
                    HybridDemoSsboTriangles.this.height = height;
                    HybridDemoSsboTriangles.this.resetFramebuffer = true;
                    HybridDemoSsboTriangles.this.frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                HybridDemoSsboTriangles.this.mouseX = (float) x;
                if (mouseDown) {
                    HybridDemoSsboTriangles.this.frameNumber = 0;
                }
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    HybridDemoSsboTriangles.this.mouseDownX = HybridDemoSsboTriangles.this.mouseX;
                    HybridDemoSsboTriangles.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    HybridDemoSsboTriangles.this.mouseDown = false;
                    HybridDemoSsboTriangles.this.rotationAboutY = HybridDemoSsboTriangles.this.currRotationAboutY;
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

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Load OBJ model */
        WavefrontMeshLoader loader = new WavefrontMeshLoader();
        mesh = loader.loadMesh("org/lwjgl/demo/opengl/models/lwjgl3.obj.zip");

        /* Create all needed GL resources */
        createRaytracingTexture();
        createSampler();
        createRasterizerTextures();
        createRasterFrameBufferObject();
        createSceneSSBO();
        createSceneVao();
        createRasterProgram();
        initRasterProgram();
        createComputeProgram();
        initComputeProgram();
        if (!caps.GL_NV_draw_texture) {
            this.vao = glGenVertexArrays();
            createQuadProgram();
            initQuadProgram();
        }

        glEnable(GL_CULL_FACE);

        glfwShowWindow(window);
        firstTime = System.nanoTime();
    }

    /**
     * Java-pendant of the GLSL struct 'object' in the compute shader 'hybridSsboTriangle.glsl'.
     */
    public static class GPUObject {
        public Vector3f min;
        public Vector3f max;
        public int first;
        public int count;
    }

    /**
     * Create two SSBOs:
     * <ul>
     * <li>one to hold all our triangles of the mesh
     * <li>another to hold the objects of the mesh with their AABBs and triangle
     * indexes
     * </ul>
     */
    private void createSceneSSBO() {
        this.trianglesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, trianglesSsbo);
        ByteBuffer ssboData = BufferUtils.createByteBuffer(4 * (4 /* + 4 */) * mesh.numVertices);
        FloatBuffer fv = ssboData.asFloatBuffer();
        for (int i = 0; i < mesh.numVertices; i++) {
            float x = mesh.positions.get(3 * i + 0);
            float y = mesh.positions.get(3 * i + 1);
            float z = mesh.positions.get(3 * i + 2);
            fv.put(x).put(y).put(z).put(0.0f);
            /* We do not take normals into account, currently! */
            // float nx = mesh.normals.get(3 * i + 0);
            // float ny = mesh.normals.get(3 * i + 1);
            // float nz = mesh.normals.get(3 * i + 2);
            // fv.put(nx).put(ny).put(nz).put(0.0f);
        }
        glBufferData(GL_ARRAY_BUFFER, ssboData, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        this.objectsSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, objectsSsbo);
        DynamicByteBuffer objectsBuffer = new DynamicByteBuffer();
        List<GPUObject> objects = new ArrayList<GPUObject>();
        for (MeshObject o : mesh.objects) {
            GPUObject obj = new GPUObject();
            obj.min = o.min;
            obj.max = o.max;
            obj.first = o.first;
            obj.count = o.count;
            objects.add(obj);
        }
        Std430Writer.write(objects, GPUObject.class, objectsBuffer);
        nglBufferData(GL_ARRAY_BUFFER, objectsBuffer.pos, objectsBuffer.addr, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Creates a VAO for the scene.
     */
    private void createSceneVao() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        long bufferSize = 4 * (3 + 3) * mesh.numVertices;
        long normalsOffset = 4L * 3 * mesh.numVertices;
        glBufferData(GL_ARRAY_BUFFER, bufferSize, GL_STATIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, mesh.positions);
        glBufferSubData(GL_ARRAY_BUFFER, normalsOffset, mesh.normals);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, normalsOffset);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        this.vaoScene = vao;
    }

    /**
     * Create the frame buffer object that our rasterizer uses to render the
     * world-space position and normal into the textures.
     */
    private void createRasterFrameBufferObject() {
        this.fbo = glGenFramebuffers();
        this.depthRenderBuffer = glGenRenderbuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        IntBuffer renderBuffers = BufferUtils.createIntBuffer(2).put(GL_COLOR_ATTACHMENT0).put(GL_COLOR_ATTACHMENT1);
        renderBuffers.flip();
        glDrawBuffers(renderBuffers);
        glBindRenderbuffer(GL_RENDERBUFFER, depthRenderBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT, width, height);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, positionTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, normalTexture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderBuffer);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/quad.vs", GL_VERTEX_SHADER, "330");
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/quad.fs", GL_FRAGMENT_SHADER, "330");
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
     * Create the raster shader.
     *
     * @throws IOException
     */
    private void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/raster.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/raster.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertexPosition");
        glBindAttribLocation(program, 1, "vertexNormal");
        glBindFragDataLocation(program, 0, "worldPosition_out");
        glBindFragDataLocation(program, 1, "worldNormal_out");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.rasterProgram = program;
    }

    /**
     * Create the tracing compute shader program.
     *
     * @throws IOException
     */
    private void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = createShader("org/lwjgl/demo/opengl/raytracing/hybridSsboTriangle.glsl", GL_COMPUTE_SHADER);
        int random = createShader("org/lwjgl/demo/opengl/raytracing/random.glsl", GL_COMPUTE_SHADER);
        int randomCommon = createShader("org/lwjgl/demo/opengl/raytracing/randomCommon.glsl", GL_COMPUTE_SHADER, "330");
        glAttachShader(program, cshader);
        glAttachShader(program, random);
        glAttachShader(program, randomCommon);
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
     * Initialize the raster program.
     */
    private void initRasterProgram() {
        glUseProgram(rasterProgram);
        viewMatrixUniform = glGetUniformLocation(rasterProgram, "viewMatrix");
        projectionMatrixUniform = glGetUniformLocation(rasterProgram, "projectionMatrix");
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
        timeUniform = glGetUniformLocation(computeProgram, "time");
        blendFactorUniform = glGetUniformLocation(computeProgram, "blendFactor");
        lightRadiusUniform = glGetUniformLocation(computeProgram, "lightRadius");

        IntBuffer props = BufferUtils.createIntBuffer(1);
        IntBuffer params = BufferUtils.createIntBuffer(1);
        props.put(0, GL_BUFFER_BINDING);

        int objectsResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Objects");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, objectsResourceIndex, props, null, params);
        objectsSsboBinding = params.get(0);
        int trianglesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Triangles");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, trianglesResourceIndex, props, null, params);
        trianglesSsboBinding = params.get(0);

        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);
        loc = glGetUniformLocation(computeProgram, "worldPositionImage");
        glGetUniformiv(computeProgram, loc, params);
        worldPositionImageBinding = params.get(0);
        loc = glGetUniformLocation(computeProgram, "worldNormalImage");
        glGetUniformiv(computeProgram, loc, params);
        worldNormalImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer for the ray tracer.
     */
    private void createRaytracingTexture() {
        this.raytraceTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, raytraceTexture);
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
     * Create the textures that the rasterizer renders into.
     */
    private void createRasterizerTextures() {
        this.positionTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, positionTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);

        this.normalTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Resize the framebuffer textures for both rasterization and ray tracing.
     */
    private void resizeFramebufferTexture() {
        glDeleteTextures(raytraceTexture);
        glDeleteTextures(positionTexture);
        glDeleteTextures(normalTexture);
        glDeleteRenderbuffers(depthRenderBuffer);
        glDeleteFramebuffers(fbo);

        createRaytracingTexture();
        createRasterizerTextures();
        createRasterFrameBufferObject();
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
        cameraPosition.set((float) sin(-currRotationAboutY) * cameraRadius, cameraHeight, (float) cos(-currRotationAboutY) * cameraRadius);
        projMatrix.setPerspective((float) Math.toRadians(30.0f), (float) width / height, 0.01f, 100.0f);
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);

        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
    }

    /**
     * Rasterize the scene and write depth and position data into framebuffer
     * textures.
     */
    private void raster() {
        glEnable(GL_DEPTH_TEST);
        glUseProgram(rasterProgram);

        /* Update matrices in shader */
        glUniformMatrix4fv(viewMatrixUniform, false, viewMatrix.get(matrixBuffer));
        glUniformMatrix4fv(projectionMatrixUniform, false, projMatrix.get(matrixBuffer));

        /* Rasterize the boxes into the FBO */
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        // clear alpha=0.0 to indicate later to the compute shader
        // that there was no rasterized geometry.
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(vaoScene);
        glDrawArrays(GL_TRIANGLES, 0, mesh.numVertices);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
    }

    /**
     * Compute one frame by tracing the scene using our compute shader.
     */
    private void trace() {
        glUseProgram(computeProgram);

        long thisTime = System.nanoTime();
        float elapsedSeconds = (thisTime - firstTime) / 1E9f;
        glUniform1f(timeUniform, elapsedSeconds);

        /*
         * We are going to average multiple successive frames, so here we
         * compute the blend factor between old frame and new frame.
         * = 0.0 - use only the new frame
         * > 0.0 - blend between old frame and new frame
         */
        float blendFactor = frameNumber / (frameNumber + 1.0f);
        glUniform1f(blendFactorUniform, blendFactor);

        glUniform1f(lightRadiusUniform, lightRadius * 0.1f);

        /* Bind level 0 of framebuffer texture as writable image in the shader. */
        glBindImageTexture(framebufferImageBinding, raytraceTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        /* Bind level 1 and 2 to our rasterized images */
        glBindImageTexture(worldPositionImageBinding, positionTexture, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
        glBindImageTexture(worldNormalImageBinding, normalTexture, 0, false, 0, GL_READ_ONLY, GL_RGBA16F);

        /* Bind the SSBO containing our objects */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, objectsSsboBinding, objectsSsbo);
        /* Bind the SSBO containing our triangles */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, trianglesSsbo);

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
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, objectsSsboBinding, 0);
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindImageTexture(worldPositionImageBinding, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
        glBindImageTexture(worldNormalImageBinding, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA16F);
        glUseProgram(0);

        frameNumber++;
    }

    /**
     * Present the final image on the screen/viewport.
     */
    private void present() {
        glDisable(GL_DEPTH_TEST);
        if (caps.GL_NV_draw_texture) {
            /*
             * Use some fancy NV extension to draw a screen-aligned textured
             * quad without needing a VAO/VBO or a shader.
             */
            NVDrawTexture.glDrawTextureNV(raytraceTexture, sampler, 0.0f, 0.0f, width, height, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            /*
             * Draw a full-screen quad using the VAO and shader.
             */
            glUseProgram(quadProgram);
            glBindVertexArray(vao);
            glBindTexture(GL_TEXTURE_2D, raytraceTexture);
            glBindSampler(0, this.sampler);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            raster();
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
        new HybridDemoSsboTriangles().run();
    }

}