/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.KDTree.*;
import org.lwjgl.demo.util.Std430Writer.*;
import org.lwjgl.demo.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.opengl.NVDrawTexture;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static java.lang.Math.*;
import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Like {@link HybridDemoSsboTriangles} but uses the <a href=
 * "https://graphics.cg.uni-saarland.de/fileadmin/cguds/papers/2007/popov_07_GPURT/Popov_et_al._-_Stackless_KD-Tree_Traversal_for_High_Performance_GPU_Ray_Tracing.pdf"
 * >Stackless KD-Tree Traversal</a> algorithm for binary space partitioning to avoid testing all triangles of the scene
 * for each ray.
 * <p>
 * Normally, traversing a kd-tree requires recursion (or a stack), but those are not available in GLSL. So
 * "Stackless kd-tree traversal" works by connecting adjacent nodes of the tree. The connections/links are called
 * "ropes" in that paper.
 * <p>
 * The kd-tree as well as the rope-building is implemented in {@link KDTree}.
 * 
 * @author Kai Burjack
 */
public class DemoSsboTrianglesStacklessKdTree {
    long window;
    int width = 1024;
    int height = 768;
    boolean resetFramebuffer = true;

    int raytraceTexture;
    int vao;
    int computeProgram;
    int quadProgram;
    int nodesSsbo;
    int trianglesSsbo;
    int sampler;

    int eyeUniform;
    int ray00Uniform;
    int ray10Uniform;
    int ray01Uniform;
    int ray11Uniform;
    int debugUniform;
    int sceneMinUniform;
    int sceneMaxUniform;
    int nodesSsboBinding;
    int trianglesSsboBinding;
    int framebufferImageBinding;

    int workGroupSizeX;
    int workGroupSizeY;

    Mesh mesh;
    float mouseDownX;
    float mouseX;
    boolean mouseDown;
    boolean debug;

    float currRotationAboutY = 0.0f;
    float rotationAboutY = (float) Math.toRadians(-45);

    float cameraRadius = 4.0f;
    float cameraHeight = 2.0f;
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix4f invViewProjMatrix = new Matrix4f();
    Vector3f tmpVector = new Vector3f();
    Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 0.0f);
    Vector3f cameraLookAt = new Vector3f(-0.2f, 0.25f, -0.2f);
    Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    Box sceneBounds;

    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWMouseButtonCallback mbCallback;

    GLCapabilities caps;
    Callback debugProc;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

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

        window = glfwCreateWindow(width, height, "Raytracing Demo (triangle mesh, stackless kd-tree)", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Hold down any mouse button and drag to rotate.");
        System.out.println("Press 'D' to toggle debug view.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action == GLFW_PRESS)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_D) {
                    debug = !debug;
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0
                        && height > 0
                        && (DemoSsboTrianglesStacklessKdTree.this.width != width || DemoSsboTrianglesStacklessKdTree.this.height != height)) {
                    DemoSsboTrianglesStacklessKdTree.this.width = width;
                    DemoSsboTrianglesStacklessKdTree.this.height = height;
                    DemoSsboTrianglesStacklessKdTree.this.resetFramebuffer = true;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                DemoSsboTrianglesStacklessKdTree.this.mouseX = (float) x;
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    DemoSsboTrianglesStacklessKdTree.this.mouseDownX = DemoSsboTrianglesStacklessKdTree.this.mouseX;
                    DemoSsboTrianglesStacklessKdTree.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    DemoSsboTrianglesStacklessKdTree.this.mouseDown = false;
                    DemoSsboTrianglesStacklessKdTree.this.rotationAboutY = DemoSsboTrianglesStacklessKdTree.this.currRotationAboutY;
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
        createSceneSSBO();
        createComputeProgram();
        initComputeProgram();
        if (!caps.GL_NV_draw_texture) {
            this.vao = glGenVertexArrays();
            createQuadProgram();
        }

        glfwShowWindow(window);
    }

    /**
     * Java-pendant of the GLSL struct 'node' in the compute shader 'ssboTriangleStacklessKdTree.glsl'.
     */
    public static class GPUNode {
        public Vector3f min;
        public Vector3f max;
        public int dim;
        public float plane;
        public @Member(length = 6) int[] ropes;
        public int left;
        public int right;
        public int firstTri;
        public int numTris;
    }

    static void kdTreeToBuffers(KDTree tree, DynamicByteBuffer nodesBuffer, DynamicByteBuffer trianglesBuffer) {
        Map<Node, Integer> indexes = new LinkedHashMap<Node, Integer>();
        // Allocate indexes for each of the nodes
        allocate(tree.mRootNode, indexes);
        int triangleIndex = 0;
        List<GPUNode> gpuNodes = new ArrayList<GPUNode>();
        // Iterate over each node in insertion order and write to the buffers
        for (Map.Entry<Node, Integer> e : indexes.entrySet()) {
            Node n = e.getKey();
            GPUNode gn = new GPUNode();
            gn.min = n.boundingBox.min;
            gn.max = n.boundingBox.max;
            gn.dim = n.splitAxis.dim;
            gn.plane = n.splitPlane;
            gn.ropes = new int[6];
            /* Write ropes */
            for (int i = 0; i < 6; i++) {
                Node r = n.ropes[i];
                if (r != null) {
                    gn.ropes[i] = indexes.get(r).intValue();
                } else {
                    gn.ropes[i] = -1; // no neighbor
                }
            }
            if (n.isLeafNode()) {
                gn.left = -1; // no left child
                gn.right = -1; // no right child
                gn.firstTri = triangleIndex;
                gn.numTris = n.triangles.size();
                triangleIndex += n.triangles.size();
                /* Write triangles to buffer */
                for (int i = 0; i < n.triangles.size(); i++) {
                    Triangle t = n.triangles.get(i);
                    trianglesBuffer.putFloat(t.v0.x).putFloat(t.v0.y).putFloat(t.v0.z).putFloat(1.0f);
                    trianglesBuffer.putFloat(t.v1.x).putFloat(t.v1.y).putFloat(t.v1.z).putFloat(1.0f);
                    trianglesBuffer.putFloat(t.v2.x).putFloat(t.v2.y).putFloat(t.v2.z).putFloat(1.0f);
                }
            } else {
                gn.left = indexes.get(n.left).intValue();
                gn.right = indexes.get(n.right).intValue();
                gn.firstTri = 0; // no triangles
                gn.numTris = 0; // no triangles
            }
            gpuNodes.add(gn);
        }
        // Write GPUNode list to ByteBuffer in std430 layout
        Std430Writer.write(gpuNodes, GPUNode.class, nodesBuffer);
    }

    static void allocate(Node node, Map<Node, Integer> indexes) {
        Queue<Node> nodes = new LinkedList<Node>();
        nodes.add(node);
        while (!nodes.isEmpty()) {
            Node n = nodes.poll();
            if (n == null)
                continue;
            int index = indexes.size();
            indexes.put(n, Integer.valueOf(index));
            nodes.add(n.left);
            nodes.add(n.right);
        }
    }

    /**
     * Build the kd-tree of the scene and create two SSBOs:
     * <ul>
     * <li>one for the nodes of the kd-tree
     * <li>and another one to hold all the triangles stored in the leaf nodes of the kd-tree
     * </ul>
     */
    void createSceneSSBO() {
        /* Build Kd-tree */
        KDTree kdtree = new KDTree();
        List<Triangle> triangles = new ArrayList<Triangle>();
        int trianglesCount = mesh.positions.remaining() / 3 / 3;
        sceneBounds = new Box();
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        sceneBounds.min = min;
        sceneBounds.max = max;
        for (int i = 0; i < trianglesCount; i++) {
            Triangle t = new Triangle();
            t.v0 = new Vector3f(mesh.positions.get(i * 3 * 3 + 0), mesh.positions.get(i * 3 * 3 + 1),
                    mesh.positions.get(i * 3 * 3 + 2));
            t.v1 = new Vector3f(mesh.positions.get(i * 3 * 3 + 3), mesh.positions.get(i * 3 * 3 + 4),
                    mesh.positions.get(i * 3 * 3 + 5));
            t.v2 = new Vector3f(mesh.positions.get(i * 3 * 3 + 6), mesh.positions.get(i * 3 * 3 + 7),
                    mesh.positions.get(i * 3 * 3 + 8));
            triangles.add(t);
            min.min(t.v0).min(t.v1).min(t.v2);
            max.max(t.v0).max(t.v1).max(t.v2);
        }
        kdtree.buildTree(triangles, sceneBounds);
        DynamicByteBuffer nodesBuffer = new DynamicByteBuffer();
        DynamicByteBuffer trianglesBuffer = new DynamicByteBuffer();
        kdTreeToBuffers(kdtree, nodesBuffer, trianglesBuffer);

        this.nodesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, nodesSsbo);
        nglBufferData(GL_ARRAY_BUFFER, nodesBuffer.pos, nodesBuffer.addr, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        this.trianglesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, trianglesSsbo);
        nglBufferData(GL_ARRAY_BUFFER, trianglesBuffer.pos, trianglesBuffer.addr, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    void createQuadProgram() throws IOException {
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
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    /**
     * Create the tracing compute shader program.
     */
    void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = createShader("org/lwjgl/demo/opengl/raytracing/ssboTriangleStacklessKdTree.glsl",
                GL_COMPUTE_SHADER);
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
     * Initialize the compute shader.
     */
    void initComputeProgram() {
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
        debugUniform = glGetUniformLocation(computeProgram, "debug");
        sceneMinUniform = glGetUniformLocation(computeProgram, "sceneMin");
        sceneMaxUniform = glGetUniformLocation(computeProgram, "sceneMax");

        IntBuffer props = BufferUtils.createIntBuffer(1);
        IntBuffer params = BufferUtils.createIntBuffer(1);
        props.put(0, GL_BUFFER_BINDING);

        int nodesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Nodes");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, nodesResourceIndex, props, null, params);
        nodesSsboBinding = params.get(0);
        int trianglesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Triangles");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, trianglesResourceIndex, props, null, params);
        trianglesSsboBinding = params.get(0);

        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer for the ray tracer.
     */
    void createRaytracingTexture() {
        this.raytraceTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, raytraceTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the sampler to sample the framebuffer texture within the shader.
     */
    void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    /**
     * Resize the framebuffer textures for both rasterization and ray tracing.
     */
    void resizeFramebufferTexture() {
        glDeleteTextures(raytraceTexture);
        createRaytracingTexture();
    }

    void update() {
        if (mouseDown) {
            /*
             * If mouse is down, compute the camera rotation based on mouse cursor location.
             */
            currRotationAboutY = rotationAboutY + (mouseX - mouseDownX) * 0.01f;
        } else {
            currRotationAboutY = rotationAboutY;
        }

        /* Rotate camera about Y axis. */
        cameraPosition.set((float) sin(-currRotationAboutY) * cameraRadius, cameraHeight,
                (float) cos(-currRotationAboutY) * cameraRadius);
        projMatrix.setPerspective((float) Math.toRadians(30.0f), (float) width / height, 0.01f, 100.0f);
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);
        projMatrix.invertPerspectiveView(viewMatrix, invViewProjMatrix);

        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
    }

    /**
     * Compute one frame by tracing the scene using our compute shader.
     */
    void trace() {
        glUseProgram(computeProgram);

        /* Set viewing frustum corner rays in shader */
        glUniform3f(eyeUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, 0)).sub(cameraPosition).normalize();
        glUniform3f(ray00Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, 1, 0)).sub(cameraPosition).normalize();
        glUniform3f(ray01Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, -1, 0)).sub(cameraPosition).normalize();
        glUniform3f(ray10Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, 1, 0)).sub(cameraPosition).normalize();
        glUniform3f(ray11Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        glUniform3f(sceneMinUniform, sceneBounds.min.x, sceneBounds.min.y, sceneBounds.min.z);
        glUniform3f(sceneMaxUniform, sceneBounds.max.x, sceneBounds.max.y, sceneBounds.max.z);
        glUniform1i(debugUniform, debug ? 1 : 0);

        /* Bind level 0 of framebuffer texture as writable image in the shader. */
        glBindImageTexture(framebufferImageBinding, raytraceTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        /* Bind the SSBO containing our kd tree nodes */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, nodesSsbo);
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
         * Synchronize all writes to the framebuffer image before we let OpenGL source texels from it afterwards when
         * rendering the final image with the full-screen quad.
         */
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, 0);
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glUseProgram(0);
    }

    /**
     * Present the final image on the screen/viewport.
     */
    void present() {
        if (caps.GL_NV_draw_texture) {
            /*
             * Use some fancy NV extension to draw a screen-aligned textured quad without needing a VAO/VBO or a shader.
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

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            trace();
            present();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null)
                debugProc.free();

            errCallback.free();
            keyCallback.free();
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
        new DemoSsboTrianglesStacklessKdTree().run();
    }

}