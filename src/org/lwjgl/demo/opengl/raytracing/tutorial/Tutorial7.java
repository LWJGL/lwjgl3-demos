/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing.tutorial;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.demo.util.DynamicByteBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.ZipInputStream;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.demo.util.Std430Writer.*;

/**
 * This demo shows stackless kd-tree traversal, as presented in the 2007
 * <a href=
 * "http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.93.8709&rep=rep1&type=pdf">Stackless
 * KD-Tree Traversal for High Performance GPU Ray Tracing</a> paper together
 * with hybrid rasterization.
 * <p>
 * The former is a nice way to avoiding unnecessary ray-triangle intersections
 * using a kd-tree as the spatial acceleration structure. In addition, the
 * traversal is made stackless by introducing "ropes" (i.e. pointers to neighbor
 * nodes). See the class {@link KDTreeForTutorial7} for the actual kd-tree
 * implementation plus the "ropes" extension.
 * <p>
 * The latter is a way to accelerate shooting the primary rays and intersecting
 * them with scene geometry by simply rendering/rasterizing the scene with
 * OpenGL in the normal way. In the fragment shader we will write the
 * world-space normal and the view distance of the fragment into a texture,
 * which is then read by the path tracer compute shader. From there on the path
 * tracer will again use the default path tracing algorithm for shooting the
 * secondary rays.
 * 
 * @author Kai Burjack
 */
public class Tutorial7 {

    /**
     * A triangle described by three vertices with its positions and normals.
     */
    private static class Triangle implements KDTreeForTutorial7.Boundable {
        public Vector3f v0, v1, v2;
        public Vector3f n0, n1, n2;
        public KDTreeForTutorial7.Box bounds;

        public KDTreeForTutorial7.Box getBounds() {
            if (bounds != null)
                return bounds;
            bounds = new KDTreeForTutorial7.Box();
            bounds.min = new Vector3f(v0).min(v1).min(v2);
            bounds.max = new Vector3f(v0).max(v1).max(v2);
            return bounds;
        }
    }

    /**
     * Reflects the "Node" structure of a kd-tree node as used in the
     * raytracing.glsl shader.
     * <p>
     * We use the {@link Std430Writer} to layout our nodes in memory as the shader
     * expects it.
     */
    public static class GPUNode {
        public Vector3f min, max;
        public int dim;
        public float plane;
        public @Member(length = 6) int[] ropes;
        public int left, right;
        public int firstTri, numTris;
    }

    /**
     * Describes the imported Assimp scene and builds OpenGL buffer objects for
     * vertex, normal and elements/indices.
     */
    private static class Model {
        private List<Mesh> meshes;

        private Model(AIScene scene) {
            int meshCount = scene.mNumMeshes();
            PointerBuffer meshesBuffer = scene.mMeshes();
            meshes = new ArrayList<>();
            for (int i = 0; i < meshCount; ++i)
                meshes.add(new Mesh(AIMesh.create(meshesBuffer.get(i))));
        }

        private static class Mesh {
            private int vao;
            private int vertexArrayBuffer;
            private FloatBuffer verticesFB;
            private int normalArrayBuffer;
            private FloatBuffer normalsFB;
            private int elementArrayBuffer;
            private IntBuffer indicesIB;
            private int elementCount;

            /**
             * Build everything from the given {@link AIMesh}.
             *
             * @param mesh
             *            the Assimp {@link AIMesh} object
             */
            private Mesh(AIMesh mesh) {
                vao = glGenVertexArrays();
                glBindVertexArray(vao);
                vertexArrayBuffer = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, vertexArrayBuffer);
                AIVector3D.Buffer vertices = mesh.mVertices();
                int verticesBytes = 4 * 3 * vertices.remaining();
                verticesFB = BufferUtils.createByteBuffer(verticesBytes).asFloatBuffer();
                memCopy(vertices.address(), memAddress(verticesFB), verticesBytes);
                nglBufferData(GL_ARRAY_BUFFER, verticesBytes, vertices.address(), GL_STATIC_DRAW);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
                normalArrayBuffer = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, normalArrayBuffer);
                AIVector3D.Buffer normals = mesh.mNormals();
                int normalsBytes = 4 * 3 * normals.remaining();
                normalsFB = BufferUtils.createByteBuffer(normalsBytes).asFloatBuffer();
                memCopy(normals.address(), memAddress(normalsFB), normalsBytes);
                nglBufferData(GL_ARRAY_BUFFER, normalsBytes, normals.address(), GL_STATIC_DRAW);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, 3, GL_FLOAT, true, 0, 0L);
                int faceCount = mesh.mNumFaces();
                elementCount = faceCount * 3;
                indicesIB = BufferUtils.createIntBuffer(elementCount);
                AIFace.Buffer facesBuffer = mesh.mFaces();
                for (int i = 0; i < faceCount; ++i) {
                    AIFace face = facesBuffer.get(i);
                    if (face.mNumIndices() != 3)
                        throw new IllegalStateException("AIFace.mNumIndices() != 3");
                    indicesIB.put(face.mIndices());
                }
                indicesIB.flip();
                elementArrayBuffer = glGenBuffers();
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesIB, GL_STATIC_DRAW);
                glBindVertexArray(0);
            }
        }
    }

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
     * The rasterized normal and view distance texture.
     */
    private int normalAndViewDistTex;
    /**
     * A VAO simply holding a VBO for rendering a simple quad.
     */
    private int vao;
    /**
     * The Framebuffer Object we use to render/rasterize the scene into.
     */
    private int rasterFBO;
    /**
     * The Renderbuffer Object to store depth information.
     */
    private int depthRBO;
    /**
     * The shader program handle of the compute shader.
     */
    private int computeProgram;
    /**
     * The shader program handle of a fullscreen quad shader.
     */
    private int quadProgram;
    /**
     * The shader program to rasterize the mesh (normal + view distance) with.
     */
    private int rasterProgram;
    private int viewMatrixUniform;
    private int projMatrixUniform;
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
     * the {@link #pttex} texture).
     */
    private int framebufferImageBinding;
    private int normalAndViewDistImageBinding;
    private int nodesSsbo;
    private int nodesSsboBinding;
    private int trianglesSsbo;
    private int trianglesSsboBinding;
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

    private Model model;
    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private Vector3f tmpVector = new Vector3f();
    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private Vector3f cameraPosition = new Vector3f(1.587E+1f, 9.643E-1f, -2.432E-1f);
    private Vector3f cameraLookAt = new Vector3f(0, 3, 0);
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
        window = glfwCreateWindow(width, height, "Path Tracing Tutorial 7", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        System.out.println("Press WSAD, LCTRL, SPACE to move around in the scene.");
        System.out.println("Press Q/E to roll left/right.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Move the mouse to look around.");

        /* And set some GLFW callbacks to get notified about events. */
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
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
                if (width > 0 && height > 0 && (Tutorial7.this.width != width || Tutorial7.this.height != height)) {
                    Tutorial7.this.width = width;
                    Tutorial7.this.height = height;
                    Tutorial7.this.resetFramebuffer = true;
                    frameNumber = 0;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - Tutorial7.this.mouseX;
                    float deltaY = (float) y - Tutorial7.this.mouseY;
                    Tutorial7.this.viewMatrix.rotateLocalY(deltaX * 0.01f);
                    Tutorial7.this.viewMatrix.rotateLocalX(deltaY * 0.01f);
                    frameNumber = 0;
                }
                Tutorial7.this.mouseX = (float) x;
                Tutorial7.this.mouseY = (float) y;
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    Tutorial7.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    Tutorial7.this.mouseDown = false;
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

        /* Load OBJ model */
        byte[] bytes = readSingleFileZip("org/lwjgl/demo/opengl/raytracing/tutorial7/sponza.obj.zip");
        ByteBuffer bb = BufferUtils.createByteBuffer(bytes.length);
        bb.put(bytes).flip();
        AIScene scene = Assimp.aiImportFileFromMemory(bb, 0, "obj");
        model = new Model(scene);
        /* And create KD-tree and triangles SSBOs */
        createSceneSSBOs();

        /* Create all needed GL resources */
        createFramebufferTextures();
        createRasterProgram();
        initRasterProgram();
        createSampler();
        this.vao = glGenVertexArrays();
        createComputeProgram();
        initComputeProgram();
        createQuadProgram();
        initQuadProgram();
        createRasterFBO();

        glfwShowWindow(window);
    }

    private static byte[] readSingleFileZip(String zipResource) throws IOException {
        ZipInputStream zipStream = new ZipInputStream(
                Tutorial7.class.getClassLoader().getResourceAsStream(zipResource));
        zipStream.getNextEntry();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read = 0;
        while ((read = zipStream.read(buffer)) > 0) {
            baos.write(buffer, 0, read);
        }
        zipStream.close();
        return baos.toByteArray();
    }

    private static void allocate(KDTreeForTutorial7.Node node, Map<KDTreeForTutorial7.Node, Integer> indexes) {
        Queue<KDTreeForTutorial7.Node> nodes = new LinkedList<KDTreeForTutorial7.Node>();
        nodes.add(node);
        while (!nodes.isEmpty()) {
            KDTreeForTutorial7.Node n = nodes.poll();
            if (n == null)
                continue;
            int index = indexes.size();
            indexes.put(n, Integer.valueOf(index));
            nodes.add(n.left);
            nodes.add(n.right);
        }
    }

    /**
     * Convert the Assimp-imported scene into the Shader Storage Buffer Objects
     * needed for stackless kd-tree traversable in the compute shader.
     */
    private void createSceneSSBOs() {
        KDTreeForTutorial7 kdtree = new KDTreeForTutorial7();
        List<KDTreeForTutorial7.Boundable> triangles = new ArrayList<KDTreeForTutorial7.Boundable>();
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Model.Mesh mesh : model.meshes) {
            int trianglesCount = mesh.elementCount / 3;
            for (int i = 0; i < trianglesCount; i++) {
                Triangle t = new Triangle();
                int i0 = mesh.indicesIB.get(i * 3 + 0);
                int i1 = mesh.indicesIB.get(i * 3 + 1);
                int i2 = mesh.indicesIB.get(i * 3 + 2);
                float v0x = mesh.verticesFB.get(i0 * 3 + 0);
                float v0y = mesh.verticesFB.get(i0 * 3 + 1);
                float v0z = mesh.verticesFB.get(i0 * 3 + 2);
                float v1x = mesh.verticesFB.get(i1 * 3 + 0);
                float v1y = mesh.verticesFB.get(i1 * 3 + 1);
                float v1z = mesh.verticesFB.get(i1 * 3 + 2);
                float v2x = mesh.verticesFB.get(i2 * 3 + 0);
                float v2y = mesh.verticesFB.get(i2 * 3 + 1);
                float v2z = mesh.verticesFB.get(i2 * 3 + 2);
                float n0x = mesh.normalsFB.get(i0 * 3 + 0);
                float n0y = mesh.normalsFB.get(i0 * 3 + 1);
                float n0z = mesh.normalsFB.get(i0 * 3 + 2);
                float n1x = mesh.normalsFB.get(i1 * 3 + 0);
                float n1y = mesh.normalsFB.get(i1 * 3 + 1);
                float n1z = mesh.normalsFB.get(i1 * 3 + 2);
                float n2x = mesh.normalsFB.get(i2 * 3 + 0);
                float n2y = mesh.normalsFB.get(i2 * 3 + 1);
                float n2z = mesh.normalsFB.get(i2 * 3 + 2);
                t.v0 = new Vector3f(v0x, v0y, v0z);
                t.v1 = new Vector3f(v1x, v1y, v1z);
                t.v2 = new Vector3f(v2x, v2y, v2z);
                t.n0 = new Vector3f(n0x, n0y, n0z);
                t.n1 = new Vector3f(n1x, n1y, n1z);
                t.n2 = new Vector3f(n2x, n2y, n2z);
                triangles.add(t);
                min.min(t.v0).min(t.v1).min(t.v2);
                max.max(t.v0).max(t.v1).max(t.v2);
            }
        }
        kdtree.buildTree(triangles, new KDTreeForTutorial7.Box(min, max));
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
     * Fill the given nodes and triangles buffers from the given kd-tree.
     * <p>
     * These buffers will then be uploaded to a Shader Storage Buffer Object to be
     * used by the path tracer.
     *
     * @param tree
     *            the kd-tree to generate the nodes and triangles buffers from
     * @param nodesBuffer
     *            will be populated with the nodes of the stackless kd-tree
     *            structure used by the compute shader
     * @param trianglesBuffer
     *            will be populated with the vertices of all triangles in the scene
     */
    private static void kdTreeToBuffers(KDTreeForTutorial7 tree, DynamicByteBuffer nodesBuffer,
            DynamicByteBuffer trianglesBuffer) {
        Map<KDTreeForTutorial7.Node, Integer> indexes = new LinkedHashMap<KDTreeForTutorial7.Node, Integer>();
        // Allocate indexes for each of the nodes
        allocate(tree.mRootNode, indexes);
        int triangleIndex = 0;
        List<GPUNode> gpuNodes = new ArrayList<GPUNode>();
        // Iterate over each node in insertion order and write to the buffers
        for (Map.Entry<KDTreeForTutorial7.Node, Integer> e : indexes.entrySet()) {
            KDTreeForTutorial7.Node n = e.getKey();
            GPUNode gn = new GPUNode();
            gn.min = n.boundingBox.min;
            gn.max = n.boundingBox.max;
            gn.dim = n.splitAxis;
            gn.plane = n.splitPlane;
            gn.ropes = new int[6];
            /* Write ropes */
            for (int i = 0; i < 6; i++) {
                KDTreeForTutorial7.Node r = n.ropes[i];
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
                    Triangle t = (Triangle) n.triangles.get(i);
                    trianglesBuffer.putFloat(t.v0.x).putFloat(t.v0.y).putFloat(t.v0.z).putFloat(1.0f);
                    trianglesBuffer.putFloat(t.v1.x).putFloat(t.v1.y).putFloat(t.v1.z).putFloat(1.0f);
                    trianglesBuffer.putFloat(t.v2.x).putFloat(t.v2.y).putFloat(t.v2.z).putFloat(1.0f);
                    trianglesBuffer.putFloat(t.n0.x).putFloat(t.n0.y).putFloat(t.n0.z).putFloat(0.0f);
                    trianglesBuffer.putFloat(t.n1.x).putFloat(t.n1.y).putFloat(t.n1.z).putFloat(0.0f);
                    trianglesBuffer.putFloat(t.n2.x).putFloat(t.n2.y).putFloat(t.n2.z).putFloat(0.0f);
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
        write(gpuNodes, GPUNode.class, nodesBuffer);
    }

    /**
     * Create the raster shader used to render world normal and view distance into a
     * texture.
     */
    private void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/raster.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/raster.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertex");
        glBindAttribLocation(program, 1, "normal");
        glBindFragDataLocation(program, 0, "outNormalAndDist");
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
     * Initialize the program to rasterize the scene.
     */
    private void initRasterProgram() {
        glUseProgram(rasterProgram);
        projMatrixUniform = glGetUniformLocation(rasterProgram, "projMatrix");
        viewMatrixUniform = glGetUniformLocation(rasterProgram, "viewMatrix");
        glUseProgram(0);
    }

    /**
     * Create the full-scren quad shader.
     */
    private void createQuadProgram() throws IOException {
        /*
         * Create program and shader objects for our full-screen quad rendering.
         */
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/quad.fs.glsl", GL_FRAGMENT_SHADER);
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
        int geometry = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/geometry.glsl", GL_COMPUTE_SHADER);
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/random.glsl", GL_COMPUTE_SHADER);
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/tutorial7/raytracing.glsl", GL_COMPUTE_SHADER);
        glAttachShader(program, geometry);
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
        blendFactorUniform = glGetUniformLocation(computeProgram, "blendFactor");
        timeUniform = glGetUniformLocation(computeProgram, "time");

        /* Query the SSBO binding points */
        IntBuffer props = BufferUtils.createIntBuffer(1);
        IntBuffer params = BufferUtils.createIntBuffer(1);
        props.put(0, GL_BUFFER_BINDING);
        int nodesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Nodes");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, nodesResourceIndex, props, null, params);
        nodesSsboBinding = params.get(0);
        int trianglesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Triangles");
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, trianglesResourceIndex, props, null, params);
        trianglesSsboBinding = params.get(0);

        /* Query the image binding points */
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);
        loc = glGetUniformLocation(computeProgram, "normalAndViewDistImage");
        glGetUniformiv(computeProgram, loc, params);
        normalAndViewDistImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the textures that will serve as our framebuffer that the compute
     * shader will write/render to.
     */
    private void createFramebufferTextures() {
        pttex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, pttex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
        normalAndViewDistTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalAndViewDistTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
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
     * Create the FBO used to rasterize the scene into. This will be used to quickly
     * generate the intersection of the primary rays with scene geometry.
     */
    private void createRasterFBO() {
        rasterFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, rasterFBO);
        depthRBO = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRBO);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, normalAndViewDistTex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("Framebuffer is not complete: " + status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Recreate the framebuffer when the window size changes.
     */
    private void resizeFramebufferTexture() {
        glDeleteFramebuffers(rasterFBO);
        glDeleteRenderbuffers(depthRBO);
        glDeleteTextures(pttex);
        glDeleteTextures(normalAndViewDistTex);
        createFramebufferTextures();
        createRasterFBO();
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

        /* Update matrices */
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
    }

    /**
     * Rasterize the scene into a FBO writing the world normal and the view distance
     * into the {@link #normalAndViewDistTex} texture attached to the FBO.
     */
    private void rasterize() {
        glUseProgram(rasterProgram);
        glUniformMatrix4fv(viewMatrixUniform, false, viewMatrix.get(matrixBuffer));
        glUniformMatrix4fv(projMatrixUniform, false, projMatrix.get(matrixBuffer));
        glBindFramebuffer(GL_FRAMEBUFFER, rasterFBO);
        glEnable(GL_DEPTH_TEST);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        for (Model.Mesh mesh : model.meshes) {
            glBindVertexArray(mesh.vao);
            glDrawElements(GL_TRIANGLES, mesh.elementCount, GL_UNSIGNED_INT, 0L);
        }
        glBindVertexArray(0);
        glDisable(GL_DEPTH_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
    }

    /* For measuring rendering performance */
    private long lastTime = System.nanoTime();
    private int frame = 0;
    private float avgTime = 0.0f;

    /**
     * Compute a new frame by tracing the scene using our compute shader. The
     * resulting pixels will be written to the framebuffer texture {@link #pttex}.
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
         * Bind level 0 of the path tracer framebuffer texture as writable and readable
         * image in the shader. This tells OpenGL that any writes to and reads from the
         * image defined in our shader is going to go to the first level of the texture
         * 'tex'.
         */
        glBindImageTexture(framebufferImageBinding, pttex, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        /*
         * Also bind the image we rasterized the normal and view distance into as
         * read-only.
         */
        glBindImageTexture(normalAndViewDistImageBinding, normalAndViewDistTex, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
        /* Bind the SSBO containing our kd tree nodes */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, nodesSsbo);
        /* Bind the SSBO containing our triangles */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, trianglesSsbo);

        /*
         * Compute appropriate global work size dimensions.
         */
        int numGroupsX = (int) Math.ceil((double)width / workGroupSizeX);
        int numGroupsY = (int) Math.ceil((double)height / workGroupSizeY);

        /*
         * Prepare query objects to retrieve the GPU timestamp, which we will use to
         * measure the time it takes the compute shader to execute.
         */
        int q0 = ARBOcclusionQuery.glGenQueriesARB();
        int q1 = ARBOcclusionQuery.glGenQueriesARB();
        ARBTimerQuery.glQueryCounter(q0, ARBTimerQuery.GL_TIMESTAMP);
        /* Execute the shader. */
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        /* Measure the time. */
        ARBTimerQuery.glQueryCounter(q1, ARBTimerQuery.GL_TIMESTAMP);
        while (ARBOcclusionQuery.glGetQueryObjectiARB(q0, ARBOcclusionQuery.GL_QUERY_RESULT_AVAILABLE_ARB) == 0
                || ARBOcclusionQuery.glGetQueryObjectiARB(q1, ARBOcclusionQuery.GL_QUERY_RESULT_AVAILABLE_ARB) == 0) {
            /* Wait until results are available. */
        }
        long time1 = ARBTimerQuery.glGetQueryObjectui64(q0, ARBOcclusionQuery.GL_QUERY_RESULT_ARB);
        long time2 = ARBTimerQuery.glGetQueryObjectui64(q1, ARBOcclusionQuery.GL_QUERY_RESULT_ARB);
        float factor = (float) frame / (frame + 1);
        avgTime = (1.0f - factor) * avgTime + factor * (time2 - time1);
        frame++;
        if (System.nanoTime() - lastTime >= 1E9) {
            System.err.println(avgTime * 1E-6 + " ms.");
            lastTime = System.nanoTime();
            frame = 0;
        }
        ARBOcclusionQuery.glDeleteQueriesARB(q0);
        ARBOcclusionQuery.glDeleteQueriesARB(q1);
        /*
         * Synchronize all writes to the framebuffer image before we let OpenGL source
         * texels from it afterwards when rendering the final image with the full-screen
         * quad.
         */
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, 0);
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindImageTexture(normalAndViewDistImageBinding, 0, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
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
        glBindTexture(GL_TEXTURE_2D, pttex);
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
             * First, rasterize the scene to quickly obtain normal and view distance
             * information.
             */
            rasterize();
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
        new Tutorial7().run();
    }

}