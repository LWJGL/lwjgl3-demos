/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import static java.lang.ClassLoader.getSystemResourceAsStream;
import static org.lwjgl.demo.util.FacePacker.pack;
import static org.lwjgl.demo.util.GreedyMeshing.Face.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.demo.util.KDTreei.build;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GLUtil.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.nio.*;
import java.util.*;

import org.joml.*;
import org.joml.Math;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.FacePacker.PackResult;
import org.lwjgl.demo.util.GreedyMeshing.Face;
import org.lwjgl.demo.util.KDTreei.Voxel;
import org.lwjgl.demo.util.MagicaVoxelLoader.Material;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.*;

/**
 * Loads a MagicaVoxel file and builds a lightmap with ray traced ambient occlusion
 * at runtime. The scene is then rasterized normally with the lightmap sampled with linear filtering.
 * 
 * @author Kai Burjack
 */
public class VoxelLightmapping {
    private static final int NOT_USED = 0;

    private long window;
    private int width = 1600;
    private int height = 940;
    private final Matrix4f pMat = new Matrix4f();
    private final Matrix4f vMat = new Matrix4f().lookAt(-40, 60, 140, 90, 0, 40, 0, 1, 0);
    private final Matrix4f mvpMat = new Matrix4f();
    private final Material[] materials = new Material[512];
    private Callback debugProc;

    /* OpenGL resources for kd-tree */
    private int nodesBufferObject, nodesTexture;
    private int voxelsBufferObject, voxelsTexture;
    private int leafNodesBufferObject, leafNodesTexture;
    private int materialsBufferObject, materialsTexture;

    /* Resources for rasterizing the faces of the scene */
    private int rasterProgram;
    private int rasterProgramMvpUniform, rasterProgramLightmapSizeUniform;
    private int vao;
    private int positionsBufferObject;
    private int normalsBufferObject;
    private int lightmapCoordsBufferObject;
    private int indexBufferObject;

    /* Resources for building the lightmap */
    private int lightmapProgram;
    private int lightmapProgramTimeUniform, lightmapProgramLightmapSizeUniform;
    private int fbo;
    private int lightmapTexWidth, lightmapTexHeight;
    private int lightmapTexture;
    private int blendIndexTexture;

    /* Misc */
    private int indexCount;
    private final boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private boolean mouseDown;
    private int mouseX, mouseY;

    private void onCursorPos(long window, double x, double y) {
        if (mouseDown) {
            float deltaX = (float) x - mouseX;
            float deltaY = (float) y - mouseY;
            vMat.rotateLocalY(deltaX * 0.01f);
            vMat.rotateLocalX(deltaY * 0.01f);
        }
        mouseX = (int) x;
        mouseY = (int) y;
    }

    private void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE)
            glfwSetWindowShouldClose(window, true);
        if (key >= 0)
            keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
    }

    private void onFramebufferSize(long window, int w, int h) {
        if (w <= 0 && h <= 0)
            return;
        width = w;
        height = h;
    }

    private void onMouseButton(long window, int button, int action, int mods) {
        mouseDown = action == GLFW_PRESS;
    }

    private void registerWindowCallbacks() {
        glfwSetFramebufferSizeCallback(window, this::onFramebufferSize);
        glfwSetKeyCallback(window, this::onKey);
        glfwSetCursorPosCallback(window, this::onCursorPos);
        glfwSetMouseButtonCallback(window, this::onMouseButton);
    }

    private void createWindow() {
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_SAMPLES, 4);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Hello lightmapped voxels", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
    }

    private void centerWindowOnScreen() {
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
    }

    private void queryFramebufferSizeForHiDPI() {
        try (MemoryStack frame = stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }

    private void installDebugCallback() {
        debugProc = setupDebugMessageCallback();
    }

    private static void configureGlobalGlState() {
        // for accumulating sampled light onto lightmap texels baded on blendFactor
        glBlendFunc(GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA);
    }

    private void init() throws IOException {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        printInfo();
        createWindow();
        registerWindowCallbacks();
        centerWindowOnScreen();
        queryFramebufferSizeForHiDPI();

        glfwMakeContextCurrent(window);
        createCapabilities();
        glfwSwapInterval(0);
        installDebugCallback();

        configureGlobalGlState();
        createRasterProgram();
        VoxelField voxelField = buildVoxelField();
        ArrayList<Face> faces = buildFaces(voxelField);
        PackResult packResult = uvPackFaces(faces);
        System.out.println(packResult);
        createLightmapTextures(packResult.w, packResult.h);
        ArrayList<Voxel> voxels = buildVoxels(voxelField);
        createMaterialsTexture();
        createSceneTBOs(voxels);
        createSceneVbos(faces);
        createLightmapProgram();
        createFrameBufferObject();

        glfwShowWindow(window);
    }

    private static void printInfo() {
        System.out.println("Use WASD + Ctrl/Space to move/strafe");
        System.out.println("Use Shift to move/strafe faster");
        System.out.println("Use left mouse button + mouse move to rotate");
    }

    private static int createShader(String resource, int type) throws IOException {
        int shader = glCreateShader(type);
        ByteBuffer source = ioResourceToByteBuffer(resource, 8192);
        try (MemoryStack stack = stackPush()) {
            glShaderSource(shader, stack.pointers(source), stack.ints(source.remaining()));
        }
        glCompileShader(shader);
        int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
        String log = glGetShaderInfoLog(shader);
        if (log.trim().length() > 0)
            System.err.println(log);
        if (compiled == 0)
            throw new AssertionError("Could not compile shader: " + resource);
        return shader;
    }

    private void createLightmapTextures(int w, int h) {
        lightmapTexWidth = w;
        lightmapTexHeight = h;
        createLightmapTexture(w, h);
        createBlendIndexTexture(w, h);
    }

    private void createBlendIndexTexture(int w, int h) {
        blendIndexTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, blendIndexTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, w, h, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void createLightmapTexture(int w, int h) {
        lightmapTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, lightmapTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void createFrameBufferObject() {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, lightmapTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, blendIndexTexture, 0);
        glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 });
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void createLightmapProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping/lightmap.vs.glsl", GL_VERTEX_SHADER);
        int random = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping/random.glsl", GL_FRAGMENT_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping/lightmap.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, random);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glBindFragDataLocation(program, 1, "blendIndex_out");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "nodes"), 0);
        glUniform1i(glGetUniformLocation(program, "leafnodes"), 1);
        glUniform1i(glGetUniformLocation(program, "voxels"), 2);
        glUniform1i(glGetUniformLocation(program, "materials"), 3);
        glUniform1i(glGetUniformLocation(program, "blendIndices"), 4);
        lightmapProgramTimeUniform = glGetUniformLocation(program, "time");
        lightmapProgramLightmapSizeUniform = glGetUniformLocation(program, "lightmapSize");
        glUseProgram(0);
        lightmapProgram = program;
    }

    private void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping/raster.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping/raster.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "lightmap"), 0);
        glUniform1i(glGetUniformLocation(program, "materials"), 1);
        rasterProgramMvpUniform = glGetUniformLocation(program, "mvp");
        rasterProgramLightmapSizeUniform = glGetUniformLocation(program, "lightmapSize");
        glUseProgram(0);
        rasterProgram = program;
    }

    private static boolean isPositiveSide(int side) {
        return (side & 1) != 0;
    }

    private static short materialAndOffset(byte m, int x, int y, int z) {
        return (short) ((m & 0xFF) | ((x + 1) << 8) | ((y + 1) << 10) | ((z + 1) << 12));
    }

    public void triangulate(ArrayList<Face> faces, ShortBuffer positions, ByteBuffer normals,
            ShortBuffer lightmapCoords, IntBuffer indices) {
        for (int i = 0; i < faces.size(); i++) {
            Face f = faces.get(i);
            switch (f.s) {
            case SIDE_NX:
            case SIDE_PX:
                generatePositionsAndNormalsX(f, positions, normals);
                break;
            case SIDE_NY:
            case SIDE_PY:
                generatePositionsAndNormalsY(f, positions, normals);
                break;
            case SIDE_NZ:
            case SIDE_PZ:
                generatePositionsAndNormalsZ(f, positions, normals);
                break;
            }
            generateTexCoords(f, lightmapCoords);
            generateIndices(f, i, indices);
        }
    }

    private static void generateIndices(Face f, int i, IntBuffer indices) {
        if (isPositiveSide(f.s)) {
            indices.put(i << 2).put((i << 2) + 1).put((i << 2) + 2);
            indices.put((i << 2) + 2).put((i << 2) + 3).put(i << 2);
        } else {
            indices.put(i << 2).put((i << 2) + 3).put((i << 2) + 2);
            indices.put((i << 2) + 2).put((i << 2) + 1).put(i << 2);
        }
    }

    private void generateTexCoords(Face f, ShortBuffer lightmapCoords) {
        lightmapCoords
                .put((short) f.tx).put((short) f.ty).put((short) 0).put((short) 0)
                .put((short) (f.tx + f.w())).put((short) f.ty).put((short) 1).put((short) 0)
                .put((short) (f.tx + f.w())).put((short) (f.ty + f.h())).put((short) 1).put((short) 1)
                .put((short) f.tx).put((short) (f.ty + f.h())).put((short) 0).put((short) 1);
    }

    private static void generatePositionsAndNormalsZ(Face f, ShortBuffer positions, ByteBuffer normals) {
        positions.put(f.u0).put(f.v0).put(f.p).put(materialAndOffset(f.v, -1, -1, 0));
        positions.put(f.u1).put(f.v0).put(f.p).put(materialAndOffset(f.v, +1, -1, 0));
        positions.put(f.u1).put(f.v1).put(f.p).put(materialAndOffset(f.v, +1, +1, 0));
        positions.put(f.u0).put(f.v1).put(f.p).put(materialAndOffset(f.v, -1, +1, 0));
        normals.put((byte) 0).put((byte) 0).put((byte) (127 * ((f.s << 1) - 9)));
        normals.put((byte) 0).put((byte) 0).put((byte) (127 * ((f.s << 1) - 9)));
        normals.put((byte) 0).put((byte) 0).put((byte) (127 * ((f.s << 1) - 9)));
        normals.put((byte) 0).put((byte) 0).put((byte) (127 * ((f.s << 1) - 9)));
    }

    private static void generatePositionsAndNormalsY(Face f, ShortBuffer positions, ByteBuffer normals) {
        positions.put(f.v0).put(f.p).put(f.u0).put(materialAndOffset(f.v, -1, 0, -1));
        positions.put(f.v0).put(f.p).put(f.u1).put(materialAndOffset(f.v, -1, 0, +1));
        positions.put(f.v1).put(f.p).put(f.u1).put(materialAndOffset(f.v, +1, 0, +1));
        positions.put(f.v1).put(f.p).put(f.u0).put(materialAndOffset(f.v, +1, 0, -1));
        normals.put((byte) 0).put((byte) (127 * ((f.s << 1) - 5))).put((byte) 0);
        normals.put((byte) 0).put((byte) (127 * ((f.s << 1) - 5))).put((byte) 0);
        normals.put((byte) 0).put((byte) (127 * ((f.s << 1) - 5))).put((byte) 0);
        normals.put((byte) 0).put((byte) (127 * ((f.s << 1) - 5))).put((byte) 0);
    }

    private static void generatePositionsAndNormalsX(Face f, ShortBuffer positions, ByteBuffer normals) {
        positions.put(f.p).put(f.u0).put(f.v0).put(materialAndOffset(f.v, 0, -1, -1));
        positions.put(f.p).put(f.u1).put(f.v0).put(materialAndOffset(f.v, 0, +1, -1));
        positions.put(f.p).put(f.u1).put(f.v1).put(materialAndOffset(f.v, 0, +1, +1));
        positions.put(f.p).put(f.u0).put(f.v1).put(materialAndOffset(f.v, 0, -1, +1));
        normals.put((byte) (127 * ((f.s << 1) - 1))).put((byte) 0).put((byte) 0);
        normals.put((byte) (127 * ((f.s << 1) - 1))).put((byte) 0).put((byte) 0);
        normals.put((byte) (127 * ((f.s << 1) - 1))).put((byte) 0).put((byte) 0);
        normals.put((byte) (127 * ((f.s << 1) - 1))).put((byte) 0).put((byte) 0);
    }

    private void createSceneVbos(ArrayList<Face> faces) {
        indexCount = faces.size() * 6;
        ShortBuffer positions = memAllocShort(4 * Short.BYTES * faces.size() * 4);
        ByteBuffer normals = memAlloc(3 * Byte.BYTES * faces.size() * 4);
        ShortBuffer lightmapCoords = memAllocShort(4 * Short.BYTES * faces.size() * 4);
        IntBuffer indices = memAllocInt(Integer.BYTES * indexCount);
        triangulate(faces, positions, normals, lightmapCoords, indices);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        setupPositions(positions);
        setupNormals(normals);
        setupLightmapCoords(lightmapCoords);
        setupIndices(indices);
        glBindVertexArray(0);
    }

    private void setupIndices(IntBuffer indices) {
        indices.flip();
        indexBufferObject = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBufferObject);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        memFree(indices);
    }

    private void setupLightmapCoords(ShortBuffer lightmapCoords) {
        lightmapCoords.flip();
        lightmapCoordsBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, lightmapCoordsBufferObject);
        glBufferData(GL_ARRAY_BUFFER, lightmapCoords, GL_STATIC_DRAW);
        memFree(lightmapCoords);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_SHORT, false, 0, 0L);
    }

    private void setupNormals(ByteBuffer normals) {
        normals.flip();
        normalsBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, normalsBufferObject);
        glBufferData(GL_ARRAY_BUFFER, normals, GL_STATIC_DRAW);
        memFree(normals);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_BYTE, true, 0, 0L);
    }

    private void setupPositions(ShortBuffer positions) {
        positions.flip();
        positionsBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, positionsBufferObject);
        glBufferData(GL_ARRAY_BUFFER, positions, GL_STATIC_DRAW);
        memFree(positions);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 4, GL_UNSIGNED_SHORT, false, 0, 0L);
    }

    private void createSceneTBOs(ArrayList<Voxel> voxels) {
        System.out.println("Building kd-tree...");
        KDTreei<Voxel> root = build(voxels, 14);
        System.out.println("Serializing kd-tree to buffers...");
        DynamicByteBuffer voxelsBuffer = new DynamicByteBuffer();
        DynamicByteBuffer nodesBuffer = new DynamicByteBuffer();
        DynamicByteBuffer leafNodesBuffer = new DynamicByteBuffer();
        kdTreeToBuffers(root, 0, nodesBuffer, leafNodesBuffer, voxelsBuffer);
        createVoxelsTexture(voxelsBuffer);
        createNodesTexture(nodesBuffer);
        createLeafNodesTexture(leafNodesBuffer);
    }

    private void createMaterialsTexture() {
        ByteBuffer materialsBuffer = memAlloc(Integer.BYTES * materials.length);
        for (Material mat : materials)
            materialsBuffer.putInt(mat == null ? 0 : mat.color);
        materialsBuffer.flip();
        materialsBufferObject = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, materialsBufferObject);
        glBufferData(GL_TEXTURE_BUFFER, materialsBuffer, GL_STATIC_DRAW);
        memFree(materialsBuffer);
        materialsTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA8, materialsBufferObject);
    }

    private void createLeafNodesTexture(DynamicByteBuffer leafNodesBuffer) {
        System.out.println("Leaf nodes buffer: " + leafNodesBuffer.pos / 1024 + " KB");
        leafNodesBufferObject = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, leafNodesBufferObject);
        nglBufferData(GL_TEXTURE_BUFFER, leafNodesBuffer.pos, leafNodesBuffer.addr, GL_STATIC_DRAW);
        leafNodesBuffer.free();
        leafNodesTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, leafNodesTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, leafNodesBufferObject);
    }

    private void createNodesTexture(DynamicByteBuffer nodesBuffer) {
        System.out.println("Nodes buffer: " + nodesBuffer.pos / 1024 + " KB");
        nodesBufferObject = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, nodesBufferObject);
        nglBufferData(GL_TEXTURE_BUFFER, nodesBuffer.pos, nodesBuffer.addr, GL_STATIC_DRAW);
        nodesBuffer.free();
        nodesTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, nodesTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, nodesBufferObject);
    }

    private void createVoxelsTexture(DynamicByteBuffer voxelsBuffer) {
        System.out.println("Voxels buffer: " + voxelsBuffer.pos / 1024 + " KB");
        voxelsBufferObject = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, voxelsBufferObject);
        nglBufferData(GL_TEXTURE_BUFFER, voxelsBuffer.pos, voxelsBuffer.addr, GL_STATIC_DRAW);
        voxelsBuffer.free();
        voxelsTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, voxelsTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RG32UI, voxelsBufferObject);
    }

    private static ArrayList<KDTreei.Node<Voxel>> allocate(KDTreei.Node<Voxel> node) {
        ArrayList<KDTreei.Node<Voxel>> linearNodes = new ArrayList<>();
        LinkedList<KDTreei.Node<Voxel>> nodes = new LinkedList<>();
        int index = 0, leafIndex = 0;
        nodes.add(node);
        while (!nodes.isEmpty()) {
            KDTreei.Node<Voxel> n = nodes.removeFirst();
            linearNodes.add(n);
            n.index = index++;
            if (n.left != null) {
                nodes.addFirst(n.right);
                nodes.addFirst(n.left);
            } else {
                n.leafIndex = leafIndex++;
                n.boundables.forEach(v -> v.nindex = n.index);
            }
        }
        return linearNodes;
    }

    private void kdTreeToBuffers(KDTreei<Voxel> root, int nodeIndexOffset,
                                 DynamicByteBuffer nodesBuffer, DynamicByteBuffer leafNodesBuffer,
                                 DynamicByteBuffer voxelsBuffer) {
        int first = 0;
        ArrayList<KDTreei.Node<Voxel>> nodes = allocate(root.root);
        System.out.println("Num nodes in kd-tree: " + nodes.size());
        for (int j = 0; j < nodes.size(); j++) {
            KDTreei.Node<Voxel> n = nodes.get(j);
            int numVoxels = 0;
            if (n.left == null) {
                numVoxels = n.boundables.size();
                for (int i = 0; i < numVoxels; i++) {
                    Voxel v = n.boundables.get(i);
                    // RG32UI
                    voxelsBuffer.putByte(v.x).putByte(v.y).putByte(v.z).putByte(v.paletteIndex);
                    voxelsBuffer.putByte(v.ex).putByte(v.ey).putByte(v.ez).putByte(NOT_USED);
                }
                // RGBA32UI
                leafNodesBuffer.putShort(first).putShort(numVoxels);
                for (int i = 0; i < 6; i++)
                    leafNodesBuffer.putShort(n.ropes[i] != null ? n.ropes[i].index : -1);
                first += numVoxels;
            }
            // RGBA32UI
            nodesBuffer.putByte(n.bb.minX).putByte(n.bb.minY).putByte(n.bb.minZ).putByte(NOT_USED);
            nodesBuffer.putByte(n.bb.maxX - 1).putByte(n.bb.maxY - 1).putByte(n.bb.maxZ - 1).putByte(NOT_USED);
            nodesBuffer.putShort(n.right != null ? n.right.index + nodeIndexOffset : n.leafIndex);
            nodesBuffer.putShort(n.splitAxis == -1 ? -1 : n.splitAxis << (Short.SIZE - 2) | n.splitPos);
            nodesBuffer.putInt(NOT_USED);
        }
        System.out.println("Num voxels in kd-tree: " + first);
    }

    private void handleKeyboardInput(float dt) {
        float factor = 10.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 40.0f;
        if (keydown[GLFW_KEY_W]) {
            vMat.translateLocal(0, 0, factor * dt);
        }
        if (keydown[GLFW_KEY_S]) {
            vMat.translateLocal(0, 0, -factor * dt);
        }
        if (keydown[GLFW_KEY_A]) {
            vMat.translateLocal(factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_D]) {
            vMat.translateLocal(-factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_Q]) {
            vMat.rotateLocalZ(-factor * dt);
        }
        if (keydown[GLFW_KEY_E]) {
            vMat.rotateLocalZ(factor * dt);
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            vMat.translateLocal(0, factor * dt, 0);
        }
        if (keydown[GLFW_KEY_SPACE]) {
            vMat.translateLocal(0, -factor * dt, 0);
        }
    }

    private void update(float dt) {
        handleKeyboardInput(dt);
        vMat.withLookAtUp(0, 1, 0);
        pMat.setPerspective(Math.toRadians(50.0f), (float) width / height, 0.1f, 1200.0f);
        pMat.mulPerspectiveAffine(vMat, mvpMat);
    }

    private void raster() {
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glUseProgram(rasterProgram);
        glClearColor(0.42f, 0.53f, 0.69f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        try (MemoryStack stack = stackPush()) {
            glUniformMatrix4fv(rasterProgramMvpUniform, false, mvpMat.get(stack.mallocFloat(16)));
        }
        glUniform2i(rasterProgramLightmapSizeUniform, lightmapTexWidth, lightmapTexHeight);
        glViewport(0, 0, width, height);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, lightmapTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void trace() {
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glUseProgram(lightmapProgram);
        glUniform1f(lightmapProgramTimeUniform, (float) System.nanoTime() / 1E6f);
        glUniform2i(lightmapProgramLightmapSizeUniform, lightmapTexWidth, lightmapTexHeight);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_BUFFER, nodesTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_BUFFER, leafNodesTexture);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_BUFFER, voxelsTexture);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, blendIndexTexture);
        glViewport(0, 0, lightmapTexWidth, lightmapTexHeight);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
        glUseProgram(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static int idx(int x, int y, int z, int width, int depth) {
        return (x + 1) + (width + 2) * ((z + 1) + (y + 1) * (depth + 2));
    }

    private static class VoxelField {
        int w, h, d;
        byte[] field;
    }

    private VoxelField buildVoxelField() throws IOException {
        System.out.println("Building voxel field...");
        Vector3i dims = new Vector3i();
        InputStream is = getSystemResourceAsStream("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house5.vox");
        BufferedInputStream bis = new BufferedInputStream(is);
        byte[] field = new byte[(256 + 2) * (256 + 2) * (256 + 2)];
        new MagicaVoxelLoader().read(bis, new MagicaVoxelLoader.Callback() {
            public void voxel(int x, int y, int z, byte c) {
                y = dims.z - y - 1;
                field[idx(x, z, y, dims.x, dims.z)] = c;
            }

            public void size(int x, int y, int z) {
                dims.x = x;
                dims.y = z;
                dims.z = y;
            }

            public void paletteMaterial(int i, Material mat) {
                materials[i] = mat;
            }
        });
        System.out.println("Voxel dimensions: " + dims);
        VoxelField res = new VoxelField();
        res.w = dims.x;
        res.h = dims.y;
        res.d = dims.z;
        res.field = field;
        return res;
    }

    private ArrayList<Face> buildFaces(VoxelField voxelField) {
        System.out.println("Building faces...");
        /* Greedy-meshing */
        GreedyMeshing gm = new GreedyMeshing(voxelField.w, voxelField.h, voxelField.d);
        ArrayList<Face> faces = new ArrayList<>();
        gm.mesh(voxelField.field, faces);
        System.out.println("Num faces: " + faces.size());
        return faces;
    }

    private ArrayList<Voxel> buildVoxels(VoxelField field) {
        /* Cull voxels */
        System.out.println("Building voxels...");
        int numVoxels = 0, numRetainedVoxels = 0;
        boolean[] culled = new boolean[(field.w + 2) * (field.h + 2) * (field.d + 2)];
        for (int y = 0; y < field.h; y++) {
            for (int z = 0; z < field.d; z++) {
                for (int x = 0; x < field.w; x++) {
                    int idx = idx(x, y, z, field.w, field.d);
                    byte c = field.field[idx];
                    if (c == 0)
                        continue;
                    numVoxels++;
                    boolean left = (field.field[idx(x - 1, y, z, field.w, field.d)]) != 0;
                    boolean right = (field.field[idx(x + 1, y, z, field.w, field.d)]) != 0;
                    boolean down = (field.field[idx(x, y - 1, z, field.w, field.d)]) != 0;
                    boolean up = (field.field[idx(x, y + 1, z, field.w, field.d)]) != 0;
                    boolean back = (field.field[idx(x, y, z - 1, field.w, field.d)]) != 0;
                    boolean front = (field.field[idx(x, y, z + 1, field.w, field.d)]) != 0;
                    if (left && right && down && up && back && front) {
                        culled[idx] = true;
                    } else {
                        numRetainedVoxels++;
                    }
                }
            }
        }
        System.out.println("Num voxels: " + numVoxels);
        System.out.println("Num voxels after culling: " + numRetainedVoxels);
        /* Greedy voxeling */
        ArrayList<Voxel> voxels = new ArrayList<>();
        GreedyVoxels gv = new GreedyVoxels(field.w, field.h, field.d, (x, y, z, w, h, d, v) ->
                voxels.add(new Voxel(x, y, z, w - 1, h - 1, d - 1, v)));
        gv.setSingleOpaque(true);
        gv.merge(field.field, culled);
        System.out.println("Num voxels after merge: " + voxels.size());
        return voxels;
    }

    private PackResult uvPackFaces(ArrayList<Face> faces) {
        System.out.println("UV-pack faces...");
        return pack(faces);
    }

    private void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            glfwPollEvents();
            update(dt);
            trace();
            raster();
            glfwSwapBuffers(window);
        }
    }

    private void run() throws IOException {
        init();
        loop();
        if (debugProc != null)
            debugProc.free();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
    }

    public static void main(String[] args) throws IOException {
        new VoxelLightmapping().run();
    }
}
