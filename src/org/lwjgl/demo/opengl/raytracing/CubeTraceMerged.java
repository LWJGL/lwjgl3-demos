/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.opengl.raytracing;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.NVDrawTexture.*;
import static org.lwjgl.opengl.NVFillRectangle.*;
import static org.lwjgl.system.MemoryUtil.*;
import java.io.*;
import java.lang.Math;
import java.nio.*;
import java.util.*;
import org.joml.*;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.KDTreei.Voxel;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Stackless kd-tree ray tracing with optimized/merged cubes.
 * 
 * @author Kai Burjack
 */
public class CubeTraceMerged {
    private long window;
    private int width = 1920;
    private int height = 1080;
    private boolean resetFramebuffer;
    private int cbWidth = 2;
    private int cbPixel;
    private byte[] samplePattern = samplePattern();
    private int scale = 1;
    private int levelWidth = 256 / scale;
    private int levelDepth = 256 / scale;
    private int levelHeight = 32 / scale;
    private int pttex;
    private int vao;
    private int tex;
    private int finalGatherProgram;
    private int quadProgram;
    private int sampler;
    private int camUniform;
    private KDTreei.Node<KDTreei.Voxel> cameraNode;
    private int startNodeIdxUniform;
    private int prevVPUniform;
    private int texUniform;
    private int offUniform, cbwidthUniform, scaleUniform;
    private int nodesSsbo;
    private int nodeGeomsSsbo;
    private int leafNodesSsbo;
    private int voxelsSsbo;
    private int finalGatherTimeUniform;
    private int finalGatherWorkGroupSizeX;
    private int finalGatherWorkGroupSizeY;
    private float mouseX, mouseY;
    private boolean leftMouseDown;
    private boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private Matrix4d projMatrix = new Matrix4d();
    private Matrix4d viewMatrix = new Matrix4d();
    private Matrix4d viewProjMatrix = new Matrix4d();
    private Matrix4d prevViewProjMatrix = new Matrix4d();
    private Matrix4d invViewProjMatrix = new Matrix4d();
    private Vector3d tmpVector = new Vector3d(), tmpVector2 = new Vector3d();
    private Vector3d cameraPosition = new Vector3d(levelWidth * 0.3f * scale, levelHeight * 0.4f * scale,
            levelDepth * 0.5f * scale);
    private Vector3d cameraLookDir = new Vector3d(0, -0.1f, 1).normalize();
    private Quaterniond quaternion = new Quaterniond();
    private KDTreei<KDTreei.Voxel> root;
    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private Callback debugProc;
    private GLCapabilities caps;
    private boolean GL_NV_draw_texture, GL_NV_fill_rectangle;
    private int frame = 0;
    private int[] viewport = { 0, 0, width, height };
    private List<KDTreei.Voxel> voxels;

    {
        cameraPosition.set(2.033E+2f, 4.511E+0f, 1.232E+2f);
        cameraLookDir.set(-1.504E-1f, 4.513E-2f, 9.876E-1f);
    }

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
        long monitor = glfwGetMonitors().get(0);
        GLFWVidMode vidmode = glfwGetVideoMode(monitor);
        window = glfwCreateWindow(width, height, "Stackless KD-Tree with merged cubes", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
                if (action == GLFW_PRESS && key == GLFW_KEY_R) {
                    frame = 0;
                } else if (action == GLFW_PRESS && key == GLFW_KEY_C) {
                    System.out.println(cameraPosition);
                    System.out.println(cameraLookDir);
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (CubeTraceMerged.this.width != width
                        || CubeTraceMerged.this.height != height)) {
                    CubeTraceMerged.this.width = width;
                    CubeTraceMerged.this.height = height;
                    CubeTraceMerged.this.resetFramebuffer = true;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            public void invoke(long window, double x, double y) {
                if (leftMouseDown) {
                    float deltaX = (float) x - CubeTraceMerged.this.mouseX;
                    float deltaY = (float) y - CubeTraceMerged.this.mouseY;
                    cameraLookDir
                            .rotate(quaternion.identity().rotateAxis(-deltaY * 0.002f, viewMatrix.positiveX(tmpVector))
                                    .rotateAxis(-deltaX * 0.002f, viewMatrix.positiveY(tmpVector)));
                }
                CubeTraceMerged.this.mouseX = (float) x;
                CubeTraceMerged.this.mouseY = (float) y;
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_1)
                    leftMouseDown = true;
                else if (action == GLFW_RELEASE && button == GLFW_MOUSE_BUTTON_1)
                    leftMouseDown = false;
            }
        });
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
        GL_NV_draw_texture = caps.GL_NV_draw_texture;
        GL_NV_fill_rectangle = caps.GL_NV_fill_rectangle;
        debugProc = GLUtil.setupDebugMessageCallback();
        createFramebufferTextures();
        createFinalGatherProgram();
        createSampler();
        if (!GL_NV_draw_texture) {
            createFullScreenVao();
            createQuadProgram();
            initQuadProgram();
        }
        System.out.println("Building terrain...");
        voxels = buildTerrainVoxels();
        createSceneSSBOs(voxels);
        glfwShowWindow(window);
    }

    private void createSampler() {
        sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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

    private static int idx(int x, int y, int z, int width, int depth) {
        return (x+1) + (width+2) * ((z+1) + (y+1) * (depth+2));
    }

    private List<KDTreei.Voxel> buildTerrainVoxels() {
        int width = levelWidth, height = levelHeight, depth = levelDepth;
        float xzScale = 0.02343f * scale, yScale = 0.0212f * scale;
        byte[] field = new byte[(width+2) * (depth+2) * (height+2)];
        boolean[] culled = new boolean[field.length];
        int numVoxels = 0;
        for (int z = 0; z < depth; z++)
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    float v = SimplexNoise.noise(x * xzScale, z * xzScale, y * yScale);
                    if (y == 0 || v > 0.4f) {
                        field[idx(x, y, z, width, depth)] = 1;
                        numVoxels++;
                    }
                }
        System.out.println("Num voxels: " + numVoxels);
        /* Remove voxels that have neighbors at all sides */
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = idx(x, y, z, width, depth);
                    int left = idx(x - 1, y, z, width, depth);
                    int right = idx(x + 1, y, z, width, depth);
                    int up = idx(x, y + 1, z, width, depth);
                    int down = idx(x, y - 1, z, width, depth);
                    int front = idx(x, y, z - 1, width, depth);
                    int back = idx(x, y, z + 1, width, depth);
                    if (field[idx] == 1 && (x == 0 || field[left] == 1)
                            && (x == width - 1 || field[right] == 1) && (y == height - 1 || field[up] == 1)
                            && (y == 0 || field[down] == 1) && (z == 0 || field[front] == 1)
                            && (z == depth - 1 || field[back] == 1)) {
                        culled[idx] = true;
                    }
                }
            }
        }
        /* Merge voxels */
        List<KDTreei.Voxel> voxels = new ArrayList<>();
        GreedyVoxels gv = new GreedyVoxels(0, height - 1, width, depth, (x, y, z, w, h, d, v) -> {
            voxels.add(new Voxel(x, y, z, w-1, h-1, d-1, v));
        });
        gv.merge(field, culled);
        System.out.println("Num voxels after culling: " + voxels.size());
        return voxels;
    }

    private static List<KDTreei.Node<KDTreei.Voxel>> allocate(KDTreei.Node<KDTreei.Voxel> node) {
        List<KDTreei.Node<KDTreei.Voxel>> linearNodes = new ArrayList<>();
        LinkedList<KDTreei.Node<KDTreei.Voxel>> nodes = new LinkedList<>();
        int index = 0, leafIndex = 0;
        nodes.add(node);
        while (!nodes.isEmpty()) {
            KDTreei.Node<KDTreei.Voxel> n = nodes.removeFirst();
            linearNodes.add(n);
            n.index = index++;
            if (n.left != null) {
                nodes.addFirst(n.right);
                nodes.addFirst(n.left);
            } else {
                n.leafIndex = leafIndex++;
                n.boundables.forEach(v -> {
                    v.nindex = n.index;
                });
            }
        }
        return linearNodes;
    }

    private void createSceneSSBOs(List<KDTreei.Voxel> voxels) {
        System.out.println("Building kd tree...");
        for (int i = 0; i < 1; i++) {
            long time1 = System.nanoTime();
            root = KDTreei.build(voxels, 14);
            long time2 = System.nanoTime();
            System.out.println("KD tree build took: " + (time2 - time1) * 1E-6f + " ms.");
        }
        System.out.println("Writing kd and voxels to buffers...");
        DynamicByteBuffer voxelsBuffer = new DynamicByteBuffer();
        DynamicByteBuffer nodesBuffer = new DynamicByteBuffer();
        DynamicByteBuffer nodeGeomsBuffer = new DynamicByteBuffer();
        DynamicByteBuffer leafNodesBuffer = new DynamicByteBuffer();
        long time1 = System.nanoTime();
        kdTreeToBuffers(root, 0, 0, nodesBuffer, nodeGeomsBuffer, leafNodesBuffer, voxelsBuffer);
        long time2 = System.nanoTime();
        System.out.println("kd tree and voxels to buffers took: " + (time2 - time1) * 1E-6f + " ms.");
        System.out.println("Voxels SSBO size: " + String.format("%.2f", voxelsBuffer.pos / 1024.0 / 1024.0) + " MB");
        System.out.println("Nodes SSBO size: " + String.format("%.2f", nodesBuffer.pos / 1024.0 / 1024.0) + " MB");
        System.out.println(
                "Nodes Geometry SSBO size: " + String.format("%.2f", nodeGeomsBuffer.pos / 1024.0 / 1024.0) + " MB");
        System.out.println(
                "Leaf Nodes SSBO size: " + String.format("%.2f", leafNodesBuffer.pos / 1024.0 / 1024.0) + " MB");
        nodesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, nodesSsbo);
        nglBufferData(GL_ARRAY_BUFFER, nodesBuffer.pos, nodesBuffer.addr, GL_STATIC_DRAW);
        nodesBuffer.free();
        nodeGeomsSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, nodeGeomsSsbo);
        nglBufferData(GL_ARRAY_BUFFER, nodeGeomsBuffer.pos, nodeGeomsBuffer.addr, GL_STATIC_DRAW);
        nodeGeomsBuffer.free();
        leafNodesSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, leafNodesSsbo);
        nglBufferData(GL_ARRAY_BUFFER, leafNodesBuffer.pos, leafNodesBuffer.addr, GL_STATIC_DRAW);
        leafNodesBuffer.free();
        voxelsSsbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, voxelsSsbo);
        nglBufferData(GL_ARRAY_BUFFER, voxelsBuffer.pos, voxelsBuffer.addr, GL_STATIC_DRAW);
        voxelsBuffer.free();
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void kdTreeToBuffers(KDTreei<KDTreei.Voxel> root, int nodeIndexOffset, int voxelIndexOffset,
            DynamicByteBuffer nodesBuffer, DynamicByteBuffer nodeGeomsBuffer, DynamicByteBuffer leafNodesBuffer,
            DynamicByteBuffer voxelsBuffer) {
        int first = 0;
        List<KDTreei.Node<KDTreei.Voxel>> nodes = allocate(root.root);
        System.out.println("Num nodes in kd-tree: " + nodes.size());
        for (KDTreei.Node<KDTreei.Voxel> n : nodes) {
            int numVoxels = 0;
            if (n.left == null) {
                numVoxels = n.boundables.size();
                n.boundables.forEach(v -> {
                    voxelsBuffer.putByte(v.x).putByte(v.y).putByte(v.z).putByte(v.paletteIndex);
                    voxelsBuffer.putByte(v.ex).putByte(v.ey).putByte(v.ez).putByte(0);
                });
                leafNodesBuffer.putInt(first).putInt(numVoxels);
                for (int i = 0; i < 6; i++)
                    leafNodesBuffer.putInt(n.ropes[i] != null ? n.ropes[i].index : -1);
            }
            nodeGeomsBuffer
                    .putByte(n.bb.minX)
                    .putByte(n.bb.minY)
                    .putByte(n.bb.minZ)
                    .putByte(0);
            nodeGeomsBuffer
                    .putByte(n.bb.maxX - 1)
                    .putByte(n.bb.maxY - 1)
                    .putByte(n.bb.maxZ - 1)
                    .putByte(0);
            nodesBuffer.putInt(n.right != null ? n.right.index + nodeIndexOffset : n.leafIndex);
            nodesBuffer.putInt(n.splitAxis == -1 ? -1 : n.splitAxis << 30 | n.splitPos);
            first += numVoxels;
        }
        System.out.println("Num voxels in kd-tree: " + first);
    }

    private void createFullScreenVao() {
        this.vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = MemoryUtil.memAlloc(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put(1.0f).put(-1.0f);
        fv.put(1.0f).put(1.0f);
        if (!GL_NV_fill_rectangle) {
            fv.put(1.0f).put(1.0f);
            fv.put(-1.0f).put(1.0f);
            fv.put(-1.0f).put(-1.0f);
        }
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        MemoryUtil.memFree(bb);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/cubetracemerged/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/cubetracemerged/quad.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog != null && programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.quadProgram = program;
    }

    private void initQuadProgram() {
        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    private void createFinalGatherProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = createShader("org/lwjgl/demo/opengl/raytracing/cubetracemerged/compute.glsl", GL_COMPUTE_SHADER);
        glAttachShader(program, cshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog != null && programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.finalGatherProgram = program;
        glUseProgram(finalGatherProgram);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer workGroupSize = frame.mallocInt(3);
            glGetProgramiv(finalGatherProgram, GL_COMPUTE_WORK_GROUP_SIZE, workGroupSize);
            finalGatherWorkGroupSizeX = workGroupSize.get(0);
            finalGatherWorkGroupSizeY = workGroupSize.get(1);
        }
        cbwidthUniform = glGetUniformLocation(finalGatherProgram, "cbwidth");
        scaleUniform = glGetUniformLocation(finalGatherProgram, "scale");
        texUniform = glGetUniformLocation(finalGatherProgram, "tex");
        glUniform1i(texUniform, 0);
        offUniform = glGetUniformLocation(finalGatherProgram, "off");
        camUniform = glGetUniformLocation(finalGatherProgram, "cam");
        startNodeIdxUniform = glGetUniformLocation(finalGatherProgram, "startNodeIdx");
        prevVPUniform = glGetUniformLocation(finalGatherProgram, "prevVP");
        finalGatherTimeUniform = glGetUniformLocation(finalGatherProgram, "time");
        glUniform1i(scaleUniform, scale);
        glUseProgram(0);
    }

    private void createFramebufferTextures() {
        pttex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, pttex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    }

    private void resizeFramebufferTexture() {
        glDeleteTextures(pttex);
        createFramebufferTextures();
    }

    private void update(float dt) {
        if (resetFramebuffer) {
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
        viewport[2] = width;
        viewport[3] = height;
        float factor = 10.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 20.0f;
        if (keydown[GLFW_KEY_W])
            cameraPosition.sub(viewMatrix.positiveZ(tmpVector).mul(dt * factor));
        if (keydown[GLFW_KEY_S])
            cameraPosition.add(viewMatrix.positiveZ(tmpVector).mul(dt * factor));
        if (keydown[GLFW_KEY_A])
            cameraPosition.sub(viewMatrix.positiveX(tmpVector).mul(dt * factor));
        if (keydown[GLFW_KEY_D])
            cameraPosition.add(viewMatrix.positiveX(tmpVector).mul(dt * factor));
        if (keydown[GLFW_KEY_LEFT_CONTROL])
            cameraPosition.sub(tmpVector.set(0, 1, 0).mul(dt * factor));
        if (keydown[GLFW_KEY_SPACE])
            cameraPosition.add(tmpVector.set(0, 1, 0).mul(dt * factor));
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 1f, 1000.0f);
        viewMatrix.setLookAt(cameraPosition, tmpVector.set(cameraPosition).add(cameraLookDir), tmpVector2.set(0, 1, 0));
        viewProjMatrix.set(projMatrix).mul(viewMatrix).invert(invViewProjMatrix);
        /* Determine camera node */
        cameraNode = root.findNode(cameraPosition);
    }

    private void trace(float elapsedSeconds) {
        glUseProgram(finalGatherProgram);
        viewMatrix.originAffine(cameraPosition);
        glUniform1ui(startNodeIdxUniform, cameraNode.index);
        glUniform1f(finalGatherTimeUniform, elapsedSeconds);
        glUniform2i(cbwidthUniform, cbWidth, cbWidth);
        glUniform2i(offUniform, samplePattern[cbPixel << 1], samplePattern[(cbPixel << 1) + 1]);
        cbPixel = (cbPixel + 1) % (cbWidth * cbWidth);
        glUniform3f(camUniform, (float) cameraPosition.x, (float) cameraPosition.y, (float) cameraPosition.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, -1, -1)).sub(cameraPosition);
        glUniform3f(camUniform + 1, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(-1, 1, -1)).sub(cameraPosition);
        glUniform3f(camUniform + 2, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, -1, -1)).sub(cameraPosition);
        glUniform3f(camUniform + 3, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        invViewProjMatrix.transformProject(tmpVector.set(1, 1, -1)).sub(cameraPosition);
        glUniform3f(camUniform + 4, (float) tmpVector.x, (float) tmpVector.y, (float) tmpVector.z);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            glUniformMatrix4fv(prevVPUniform, false, prevViewProjMatrix.get(frame.mallocFloat(16)));
        }
        glBindImageTexture(0, pttex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, nodesSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeGeomsSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, leafNodesSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, voxelsSsbo);
        glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
        int worksizeX = (int) Math.ceil((double) width / cbWidth / finalGatherWorkGroupSizeX);
        int worksizeY = (int) Math.ceil((double) height / cbWidth / finalGatherWorkGroupSizeY);
        glDispatchCompute(worksizeX, worksizeY, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, 0);
        glBindImageTexture(0, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glUseProgram(0);
    }

    private void present() {
        if (GL_NV_draw_texture) {
            glDrawTextureNV(pttex, sampler, 0, 0, width, height, 0, 0, 0, 1, 1);
        } else {
            glUseProgram(quadProgram);
            glBindVertexArray(vao);
            glBindTexture(GL_TEXTURE_2D, pttex);
            if (GL_NV_fill_rectangle) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL_RECTANGLE_NV);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            } else {
                glDrawArrays(GL_TRIANGLES, 0, 6);
            }
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    private int averageOver = 400;

    private void loop() {
        long lastTime = System.nanoTime();
        long lastAvg = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) * 1E-9f;
            if ((frame % averageOver) == 0) {
                String text = String.format("%.3f", (thisTime - lastAvg) * 1E-6f / averageOver) + " ms.";
                glfwSetWindowTitle(window, text);
                System.out.println(text);
                lastAvg = thisTime;
            }
            lastTime = thisTime;
            update((float) dt);
            glViewport(0, 0, width, height);
            trace(thisTime * 1E-9f);
            present();
            glfwSwapBuffers(window);
            frame++;
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
        new CubeTraceMerged().run();
    }

}