/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.IOException;
import java.lang.Math;
import java.nio.*;
import java.util.*;

import org.joml.*;
import org.lwjgl.demo.opengl.raytracing.CubeTrace.IBVHMortonTree.*;
import org.lwjgl.demo.opengl.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Tracing Minecraft-like axis-aligned cube worlds using a BVH tree with morton
 * code partitioning and bitstack traversal.
 * <p>
 * Axis-aligned cubes are really a natural fit for BVH trees, since they align
 * very well with the BVH subdivisions and a terrain with cubes at regular
 * positions can be subdivided optimally in log2(N) iterations.
 * 
 * @author Kai Burjack
 */
public class CubeTrace {
    /**
     * Bounding Volume Hierarchy for integer lattices using morton code
     * partitioning.
     *
     * @author Kai Burjack
     */
    public static class IBVHMortonTree {
        public static class Voxel implements Comparable<Voxel> {
            public int x, y, z;
            public long morton;
            public float r, g, b;

            public Voxel(int x, int y, int z, float r, float g, float b) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.r = r;
                this.g = g;
                this.b = b;
            }

            @Override
            public int compareTo(Voxel o) {
                return Long.compare(morton, o.morton);
            }
        }

        public static final int MAX_POINTS_IN_NODE = 8;
        public IBVHMortonTree parent;
        public IBVHMortonTree left;
        public IBVHMortonTree right;
        public List<Voxel> voxels;
        public int index;
        public int first, last = -1;
        public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        IBVHMortonTree(IBVHMortonTree parent) {
            this.parent = parent;
        }

        IBVHMortonTree(IBVHMortonTree parent, List<Voxel> voxels, int first, int last) {
            this.parent = parent;
            this.first = first;
            this.last = last;
            this.voxels = voxels;
            voxels.forEach(v -> {
                this.minX = this.minX < v.x ? this.minX : v.x;
                this.minY = this.minY < v.y ? this.minY : v.y;
                this.minZ = this.minZ < v.z ? this.minZ : v.z;
                this.maxX = this.maxX > v.x ? this.maxX : v.x;
                this.maxY = this.maxY > v.y ? this.maxY : v.y;
                this.maxZ = this.maxZ > v.z ? this.maxZ : v.z;
            });
        }

        private static long expandBits(long v) {
            v &= 0x00000000001fffffL;
            v = (v | v << 32L) & 0x001f00000000ffffL;
            v = (v | v << 16L) & 0x001f0000ff0000ffL;
            v = (v | v << 8L) & 0x010f00f00f00f00fL;
            v = (v | v << 4L) & 0x10c30c30c30c30c3L;
            v = (v | v << 2L) & 0x1249249249249249L;
            return v;
        }

        private static long morton3d(int x, int y, int z) {
            return (expandBits(y) << 2L) + (expandBits(z) << 1L) + expandBits(x);
        }

        private static int findSplit(List<Voxel> sortedMortonCodes, int first, int last) {
            long firstCode = sortedMortonCodes.get(first).morton;
            long lastCode = sortedMortonCodes.get(last).morton;
            if (firstCode == lastCode)
                return first + last >> 1;
            int commonPrefix = Long.numberOfLeadingZeros(firstCode ^ lastCode);
            int split = first;
            int step = last - first;
            do {
                step = step + 1 >> 1;
                int newSplit = split + step;
                if (newSplit < last) {
                    long splitCode = sortedMortonCodes.get(newSplit).morton;
                    int splitPrefix = Long.numberOfLeadingZeros(firstCode ^ splitCode);
                    if (splitPrefix > commonPrefix)
                        split = newSplit;
                }
            } while (step > 1);
            return split;
        }

        public static IBVHMortonTree build(List<Voxel> voxels) {
            voxels.forEach(v -> v.morton = morton3d(v.x, v.y, v.z));
            Collections.sort(voxels);
            return build(null, voxels, 0, voxels.size() - 1);
        }

        private static IBVHMortonTree build(IBVHMortonTree parent, List<Voxel> mortonSortedTriangles, int first,
                int last) {
            if (first > last - MAX_POINTS_IN_NODE)
                return new IBVHMortonTree(parent, mortonSortedTriangles.subList(first, last + 1), first, last);
            int split = findSplit(mortonSortedTriangles, first, last);
            IBVHMortonTree tree = new IBVHMortonTree(parent);
            tree.left = build(tree, mortonSortedTriangles, first, split);
            tree.right = build(tree, mortonSortedTriangles, split + 1, last);
            tree.minX = java.lang.Math.min(tree.left.minX, tree.right.minX);
            tree.minY = java.lang.Math.min(tree.left.minY, tree.right.minY);
            tree.minZ = java.lang.Math.min(tree.left.minZ, tree.right.minZ);
            tree.maxX = java.lang.Math.max(tree.left.maxX, tree.right.maxX);
            tree.maxY = java.lang.Math.max(tree.left.maxY, tree.right.maxY);
            tree.maxZ = java.lang.Math.max(tree.left.maxZ, tree.right.maxZ);
            return tree;
        }
    }

    private long window;
    private int width = 1920;
    private int height = 1080;
    private boolean resetFramebuffer;
    private int cbWidth = 3;
    private byte[] samplePattern = samplePattern();
    private int cbPixel;
    private int levelWidth = 2048;
    private int levelDepth = 1024;
    private int levelHeight = 64;
    private int pttex;
    private int vao;
    private int computeProgram;
    private int quadProgram;
    private int sampler;
    private int eyeUniform;
    private int offUniform, cbwidthUniform;
    private int ray00Uniform, ray10Uniform, ray01Uniform, ray11Uniform;
    private int framebufferImageBinding;
    private int nodesSsbo;
    private int nodesSsboBinding;
    private int voxelsSsbo;
    private int voxelsSsboBinding;
    private int workGroupSizeX;
    private int workGroupSizeY;
    private float mouseX, mouseY;
    private boolean mouseDown;
    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4d projMatrix = new Matrix4d();
    private Matrix4d viewMatrix = new Matrix4d();
    private Matrix4d invViewProjMatrix = new Matrix4d();
    private Vector3d tmpVector = new Vector3d();
    private Vector3d cameraPosition = new Vector3d(levelWidth * 0.9f, levelHeight * 1.4f, levelDepth * 0.5f);
    private Vector3d cameraLookAt = new Vector3d(levelWidth * 0.5f, levelHeight * 0.5f, levelDepth * 0.5f);
    private Vector3d cameraUp = new Vector3d(0.0f, 1.0f, 0.0f);
    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private Callback debugProc;
    private long lastTime = System.nanoTime();
    private int frame = 0;
    private float avgTime = 0.0f;
    private boolean hasShortsInShader;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Tracing axis-aligned cubes with BVH", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        System.out.println("Press WSAD, LCTRL, SPACE to move around in the scene.");
        System.out.println("Press Q/E to roll left/right.");
        System.out.println("Hold down left shift to move faster.");
        System.out.println("Move the mouse to look around.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (CubeTrace.this.width != width || CubeTrace.this.height != height)) {
                    CubeTrace.this.width = width;
                    CubeTrace.this.height = height;
                    CubeTrace.this.resetFramebuffer = true;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - CubeTrace.this.mouseX;
                    float deltaY = (float) y - CubeTrace.this.mouseY;
                    CubeTrace.this.viewMatrix.rotateLocalY(deltaX * 0.002f);
                    CubeTrace.this.viewMatrix.rotateLocalX(deltaY * 0.002f);
                    CubeTrace.this.viewMatrix.normalize3x3();
                }
                CubeTrace.this.mouseX = (float) x;
                CubeTrace.this.mouseY = (float) y;
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS)
                    CubeTrace.this.mouseDown = true;
                else if (action == GLFW_RELEASE)
                    CubeTrace.this.mouseDown = false;
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
        GLCapabilities caps = GL.createCapabilities();
        hasShortsInShader = caps.GL_NV_gpu_shader5 || caps.GL_AMD_gpu_shader_int16;
        // debugProc = GLUtil.setupDebugMessageCallback();
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);
        System.out.println("Building terrain...");
        createFramebufferTextures();
        createSampler();
        quadFullScreenVao();
        createComputeProgram();
        initComputeProgram();
        createQuadProgram();
        initQuadProgram();
        createSceneSSBOs(buildTerrainVoxels());
        glfwShowWindow(window);
    }

    private byte[] samplePattern() {
        switch (cbWidth) {
        case 1:
            return new byte[] { 0, 0 };
        case 2:
        /* Use custom sample patterns for 2x2 up to 4x4 pixel groups */
            return new byte[] { 0, 0, 1, 1, 1, 0, 0, 1 };
        case 3:
            return new byte[] { 0, 0, 1, 2, 2, 1, 0, 1, 2, 0, 1, 1, 2, 2, 0, 2, 1, 0 };
        case 4:
            return new byte[] { 0, 0, 2, 1, 1, 3, 0, 2, 2, 0, 3, 2, 2, 3, 1, 1, 3, 0, 0, 1, 2, 2, 0, 3, 3, 1, 1, 0, 3,
                    3, 1, 2 };
        default:
            throw new UnsupportedOperationException("No pattern for " + cbWidth + "x" + cbWidth + " sampling");
        }
    }

    private List<Voxel> buildTerrainVoxels() {
        int width = levelWidth, height = levelHeight, depth = levelDepth;
        float xzScale = 0.01353f, yScale = 0.0322f;
        byte[] field = new byte[width * depth * height];
        Thread[] threads = new Thread[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < threads.length; i++) {
            final int ti = i;
            threads[i] = new Thread(() -> {
                int depthSlice = depth / threads.length;
                for (int z = ti * depthSlice; z < (ti + 1) * depthSlice; z++)
                    for (int y = 0; y < height; y++)
                        for (int x = 0; x < width; x++) {
                            float v = SimplexNoise.noise(x * xzScale, z * xzScale, y * yScale);
                            if (y == 0 || v > 0.15f)
                                field[x + y * width + z * width * height] = ~0;
                        }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
            }
        }
        /* Remove voxels that have neighbors at all sides */
        int removed = 0;
        for (int z = 1; z < depth - 1; z++)
            for (int y = 1; y < height - 1; y++)
                for (int x = 1; x < width - 1; x++) {
                    int idx = x + y * width + z * width * height;
                    int left = (x - 1) + y * width + z * width * height;
                    int right = (x + 1) + y * width + z * width * height;
                    int up = x + (y + 1) * width + z * width * height;
                    int down = x + (y - 1) * width + z * width * height;
                    int front = x + y * width + (z - 1) * width * height;
                    int back = x + y * width + (z + 1) * width * height;
                    if ((field[idx] & 1) == 1 && (field[left] & 1) == 1 && (field[right] & 1) == 1
                            && (field[up] & 1) == 1 && (field[down] & 1) == 1 && (field[front] & 1) == 1
                            && (field[back] & 1) == 1) {
                        field[idx] = 3; // <- remember that this once was a filled voxel!
                        removed++;
                    }
                }
        List<Voxel> voxels = new ArrayList<>();
        for (int z = 0; z < depth; z++)
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int idx = x + y * width + z * width * height;
                    float r = 0.9f * (SimplexNoise.noise(x * 0.012f, y * 0.04f, z * 0.012f) * 0.5f + 0.5f);
                    float g = r;
                    float b = g;
                    if (field[idx] == ~0)
                        voxels.add(new Voxel(x, y, z, r, g, b));
                }
        System.out.println("Removed voxels: " + removed);
        System.out.println("Retained voxels: " + voxels.size());
        return voxels;
    }

    private static List<IBVHMortonTree> allocate(IBVHMortonTree node) {
        List<IBVHMortonTree> linearNodes = new ArrayList<>();
        Queue<IBVHMortonTree> nodes = new ArrayDeque<>();
        int index = 0;
        nodes.add(node);
        while (!nodes.isEmpty()) {
            IBVHMortonTree n = nodes.poll();
            linearNodes.add(n);
            n.index = index++;
            if (n.left != null) {
                nodes.add(n.left);
                nodes.add(n.right);
            }
        }
        return linearNodes;
    }

    private void createSceneSSBOs(List<Voxel> voxels) {
        System.out.println("Building BVH...");
        IBVHMortonTree root = IBVHMortonTree.build(voxels);
        System.out.println("Writing BVH to buffers...");
        DynamicByteBuffer voxelsBuffer = new DynamicByteBuffer(voxels.size() * 4 * 4);
        if (hasShortsInShader) {
            voxels.forEach(v -> {
                int r = (int) (v.r * 32.0f);
                int g = (int) (v.g * 32.0f);
                int b = (int) (v.b * 32.0f);
                voxelsBuffer.putShort(v.x).putShort(v.y).putShort(v.z)
                        .putShort((r & 0x1F) | ((g & 0x1F) << 5) | ((b & 0x1F) << 10));
            });
        } else {
            voxels.forEach(v -> {
                int r = (int) (v.r * 1024.0f);
                int g = (int) (v.g * 1024.0f);
                int b = (int) (v.b * 1024.0f);
                voxelsBuffer.putInt(v.x).putInt(v.y).putInt(v.z)
                        .putInt((r & 0x3FF) | ((g & 0x3FF) << 10) | ((b & 0x3FF) << 20));
            });
        }
        voxelsBuffer.flip();
        System.out.println("Voxels SSBO size: " + voxelsBuffer.remaining() / 1024 / 1024 + " MB");
        DynamicByteBuffer nodesBuffer = new DynamicByteBuffer(8192);
        bhvToBuffers(root, nodesBuffer);
        nodesBuffer.flip();
        System.out.println("BVH SSBO size: " + nodesBuffer.remaining() / 1024 / 1024 + " MB");
        this.nodesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, nodesSsbo);
        glBufferData(GL_ARRAY_BUFFER, nodesBuffer.bb, GL_STATIC_DRAW);
        nodesBuffer.free();
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        this.voxelsSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, voxelsSsbo);
        glBufferData(GL_ARRAY_BUFFER, voxelsBuffer.bb, GL_STATIC_DRAW);
        voxelsBuffer.free();
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void bhvToBuffers(IBVHMortonTree root, DynamicByteBuffer nodesBuffer) {
        if (hasShortsInShader) {
            for (IBVHMortonTree n : allocate(root)) {
                nodesBuffer.putShort(n.minX).putShort(n.minY).putShort(n.minZ).putByte(n.last - n.first + 1).putByte(0);
                nodesBuffer.putShort(n.maxX + 1).putShort(n.maxY + 1).putShort(n.maxZ + 1).putShort(0);
                nodesBuffer.putInt(n.left != null ? n.left.index : -1).putInt(n.right != null ? n.right.index : -1);
                nodesBuffer.putInt(n.parent != null ? n.parent.index : -1).putInt(n.first);
            }
        } else {
            for (IBVHMortonTree n : allocate(root)) {
                nodesBuffer.putFloat(n.minX).putFloat(n.minY).putFloat(n.minZ)
                        .putInt(n.left != null ? n.left.index : -1);
                nodesBuffer.putFloat(n.maxX + 1).putFloat(n.maxY + 1).putFloat(n.maxZ + 1)
                        .putInt(n.right != null ? n.right.index : -1);
                nodesBuffer.putInt(n.parent != null ? n.parent.index : -1).putInt(n.first).putInt(n.last - n.first + 1)
                        .putInt(-1);
            }
        }
    }

    private void quadFullScreenVao() {
        this.vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = MemoryUtil.memAlloc(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put(1.0f).put(-1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(-1.0f).put(1.0f);
        fv.put(-1.0f).put(-1.0f);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        MemoryUtil.memFree(bb);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/cubetrace/quad.vs.glsl",
                GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/cubetrace/quad.fs.glsl",
                GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertex");
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.quadProgram = program;
    }

    private void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = DemoUtils.createShader(
                "org/lwjgl/demo/opengl/raytracing/cubetrace/raytracing" + (hasShortsInShader ? "_16t" : "") + ".glsl",
                GL_COMPUTE_SHADER);
        glAttachShader(program, cshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.computeProgram = program;
    }

    private void initQuadProgram() {
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    private void initComputeProgram() {
        glUseProgram(computeProgram);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer workGroupSize = frame.mallocInt(3);
            glGetProgramiv(computeProgram, GL_COMPUTE_WORK_GROUP_SIZE, workGroupSize);
            workGroupSizeX = workGroupSize.get(0);
            workGroupSizeY = workGroupSize.get(1);
            cbwidthUniform = glGetUniformLocation(computeProgram, "cbwidth");
            offUniform = glGetUniformLocation(computeProgram, "off");
            eyeUniform = glGetUniformLocation(computeProgram, "eye");
            ray00Uniform = glGetUniformLocation(computeProgram, "ray00");
            ray10Uniform = glGetUniformLocation(computeProgram, "ray10");
            ray01Uniform = glGetUniformLocation(computeProgram, "ray01");
            ray11Uniform = glGetUniformLocation(computeProgram, "ray11");
            IntBuffer props = frame.mallocInt(1);
            IntBuffer params = frame.mallocInt(1);
            props.put(0, GL_BUFFER_BINDING);
            int nodesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Nodes");
            glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, nodesResourceIndex, props, null, params);
            nodesSsboBinding = params.get(0);
            int voxelsResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Voxels");
            glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, voxelsResourceIndex, props, null, params);
            voxelsSsboBinding = params.get(0);
            int loc = glGetUniformLocation(computeProgram, "framebufferImage");
            glGetUniformiv(computeProgram, loc, params);
            framebufferImageBinding = params.get(0);
        }
        glUseProgram(0);
    }

    private void createFramebufferTextures() {
        pttex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, pttex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height);
    }

    private void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(pttex);
        createFramebufferTextures();
    }

    private void update(float dt) {
        float factor = 20.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 50.0f;
        if (keydown[GLFW_KEY_W])
            viewMatrix.translateLocal(0, 0, factor * dt);
        if (keydown[GLFW_KEY_S])
            viewMatrix.translateLocal(0, 0, -factor * dt);
        if (keydown[GLFW_KEY_A])
            viewMatrix.translateLocal(factor * dt, 0, 0);
        if (keydown[GLFW_KEY_D])
            viewMatrix.translateLocal(-factor * dt, 0, 0);
        if (keydown[GLFW_KEY_Q])
            viewMatrix.rotateLocalZ(factor * 0.02f * -dt);
        if (keydown[GLFW_KEY_E])
            viewMatrix.rotateLocalZ(factor * 0.02f * dt);
        if (keydown[GLFW_KEY_LEFT_CONTROL])
            viewMatrix.translateLocal(0, factor * dt, 0);
        if (keydown[GLFW_KEY_SPACE])
            viewMatrix.translateLocal(0, -factor * dt, 0);
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
    }

    private void trace() {
        glUseProgram(computeProgram);
        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
        projMatrix.invertPerspectiveView(viewMatrix, invViewProjMatrix);
        viewMatrix.originAffine(cameraPosition);
        glUniform2i(cbwidthUniform, cbWidth, cbWidth);
        glUniform2i(offUniform, samplePattern[cbPixel << 1], samplePattern[(cbPixel << 1) + 1]);
        cbPixel = (cbPixel + 1) % (cbWidth * cbWidth);
        glUniform3f(eyeUniform, (float) cameraPosition.x, (float) cameraPosition.y, (float) cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray00Uniform, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray01Uniform, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, -1, 0)).sub(cameraPosition);
        glUniform3f(ray10Uniform, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, 1, 0)).sub(cameraPosition);
        glUniform3f(ray11Uniform, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        glBindImageTexture(framebufferImageBinding, pttex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA32F);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, nodesSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, voxelsSsboBinding, voxelsSsbo);
        int numGroupsX = (int) Math.ceil((double)width / workGroupSizeX);
        int numGroupsY = (int) Math.ceil((double)height / workGroupSizeY);
        int q0 = ARBOcclusionQuery.glGenQueriesARB();
        int q1 = ARBOcclusionQuery.glGenQueriesARB();
        ARBTimerQuery.glQueryCounter(q0, ARBTimerQuery.GL_TIMESTAMP);
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        ARBTimerQuery.glQueryCounter(q1, ARBTimerQuery.GL_TIMESTAMP);
        while (ARBOcclusionQuery.glGetQueryObjectiARB(q0, ARBOcclusionQuery.GL_QUERY_RESULT_AVAILABLE_ARB) == 0
                || ARBOcclusionQuery.glGetQueryObjectiARB(q1, ARBOcclusionQuery.GL_QUERY_RESULT_AVAILABLE_ARB) == 0)
            Thread.yield();
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
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, nodesSsboBinding, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, voxelsSsboBinding, 0);
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA32F);
        glUseProgram(0);
    }

    private void present() {
        glUseProgram(quadProgram);
        glBindVertexArray(vao);
        glBindTexture(GL_TEXTURE_2D, pttex);
        glBindSampler(0, this.sampler);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void loop() {
        float lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            float thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            glfwPollEvents();
            glViewport(0, 0, width, height);
            update(dt);
            trace();
            present();
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
        new CubeTrace().run();
    }

}