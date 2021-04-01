/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import static java.lang.ClassLoader.getSystemResourceAsStream;
import static org.lwjgl.demo.util.FacePacker.pack;
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
import org.lwjgl.demo.util.MagicaVoxelLoader.Material;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.*;

/**
 * Enhances {@link VoxelLightmapping} with raytraced reflections with using the greedy meshed faces/rectangles
 * as scene representation for kd-tree tracing instead of axis-aligned boxes.
 * <p>
 * This allows to compute a UV coordinate for the hit point on a face and lookup the lightmap when following
 * view-dependent rays.
 * 
 * @author Kai Burjack
 */
public class VoxelLightmapping2 {
    private static final int NOT_USED = 0;
    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 5;
    private static final int PRIMITIVE_RESTART_INDEX = 0xFFFF;

    private long window;
    private int width = 1600;
    private int height = 940;
    private final Matrix4f pMat = new Matrix4f();
    private final Matrix4x3f vMat = new Matrix4x3f().lookAt(-40, 60, 140, 90, 0, 40, 0, 1, 0);
    private final Matrix4f mvpMat = new Matrix4f();
    private final Matrix4f ivpMat = new Matrix4f();
    private final Vector3f camPos = new Vector3f();
    private final Material[] materials = new Material[512];
    private Callback debugProc;

    /* OpenGL resources for kd-tree */
    private int nodesBufferObject, nodesTexture;
    private int facesBufferObject, facesTexture;
    private int leafNodesBufferObject, leafNodesTexture;
    private int materialsBufferObject, materialsTexture;

    /* Resources for rasterizing the faces of the scene */
    private int rasterProgram;
    private int rasterProgramMvpUniform, rasterProgramLightmapSizeUniform;
    private int rasterProgramCamPosUniform;
    private int vao;
    private int faceCount;

    /* Resources for building the lightmap */
    private int lightmapProgram;
    private int lightmapProgramTimeUniform, lightmapProgramLightmapSizeUniform;
    private int fbo;
    private int lightmapTexWidth, lightmapTexHeight;
    private int lightmapTexture;
    private int blendIndexTexture;

    /* Misc */
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
        handleSpecialKeys(key, action);
    }

    private void handleSpecialKeys(int key, int action) {
        if (action == GLFW_PRESS && key == GLFW_KEY_R) {
            resetBlendIndexTexture();
        }
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
        window = glfwCreateWindow(width, height, "Hello reflective lightmapped faces", NULL, NULL);
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
        glEnable(GL_PRIMITIVE_RESTART);
        glPrimitiveRestartIndex(PRIMITIVE_RESTART_INDEX);
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
        faceCount = faces.size();
        createLightmapTextures(packResult.w, packResult.h);
        createMaterialsTexture();
        createSceneTBOs(faces);
        createSceneVbos(faces);
        createLightmapProgram();
        createFrameBufferObject();

        glfwShowWindow(window);
    }

    private static void printInfo() {
        System.out.println("Use WASD + Ctrl/Space to move/strafe");
        System.out.println("Use Shift to move/strafe faster");
        System.out.println("Use left mouse button + mouse move to rotate");
        System.out.println("Press R to reset the lightmap");
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

    private void resetBlendIndexTexture() {
        ByteBuffer bb = memCalloc(lightmapTexWidth * lightmapTexHeight * Float.BYTES);
        glBindTexture(GL_TEXTURE_2D, blendIndexTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, lightmapTexWidth, lightmapTexHeight, GL_RED, GL_FLOAT, bb);
        glBindTexture(GL_TEXTURE_2D, 0);
        memFree(bb);
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
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/lightmap.vs.glsl", GL_VERTEX_SHADER);
        int random = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/random.glsl", GL_FRAGMENT_SHADER);
        int trace = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/trace.glsl", GL_FRAGMENT_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/lightmap.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, random);
        glAttachShader(program, trace);
        glAttachShader(program, fshader);
        glBindFragDataLocation(program, 0, "color");
        glBindFragDataLocation(program, 1, "blendIndex_out");
        glLinkProgram(program);
        glDeleteShader(vshader);
        glDeleteShader(random);
        glDeleteShader(trace);
        glDeleteShader(fshader);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "nodes"), 0);
        glUniform1i(glGetUniformLocation(program, "leafnodes"), 1);
        glUniform1i(glGetUniformLocation(program, "faces"), 2);
        glUniform1i(glGetUniformLocation(program, "blendIndices"), 3);
        lightmapProgramTimeUniform = glGetUniformLocation(program, "time");
        lightmapProgramLightmapSizeUniform = glGetUniformLocation(program, "lightmapSize");
        glUseProgram(0);
        lightmapProgram = program;
    }

    private void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/raster.vs.glsl", GL_VERTEX_SHADER);
        int trace = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/trace.glsl", GL_FRAGMENT_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/voxellightmapping2/raster.fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, trace);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        glDeleteShader(vshader);
        glDeleteShader(trace);
        glDeleteShader(fshader);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "lightmap"), 0);
        glUniform1i(glGetUniformLocation(program, "materials"), 1);
        glUniform1i(glGetUniformLocation(program, "nodes"), 2);
        glUniform1i(glGetUniformLocation(program, "leafnodes"), 3);
        glUniform1i(glGetUniformLocation(program, "faces"), 4);
        rasterProgramMvpUniform = glGetUniformLocation(program, "mvp");
        rasterProgramCamPosUniform = glGetUniformLocation(program, "camPos");
        rasterProgramLightmapSizeUniform = glGetUniformLocation(program, "lightmapSize");
        glUseProgram(0);
        rasterProgram = program;
    }

    private static boolean isPositiveSide(int side) {
        return (side & 1) != 0;
    }

    public void triangulate(List<Face> faces, ByteBuffer positionsAndTypes,
                            ByteBuffer sidesAndOffsets, ShortBuffer lightmapCoords, ShortBuffer indices) {
        if (faces.size() << 2 > PRIMITIVE_RESTART_INDEX)
            throw new AssertionError();
        for (int i = 0; i < faces.size(); i++) {
            Face f = faces.get(i);
            switch (f.s >>> 1) {
            case 0:
                generatePositionsAndTypesX(f, positionsAndTypes);
                break;
            case 1:
                generatePositionsAndTypesY(f, positionsAndTypes);
                break;
            case 2:
                generatePositionsAndTypesZ(f, positionsAndTypes);
                break;
            }
            int n00 = aoFactor(f.v >>>  8 & 7), n10 = aoFactor(f.v >>> 11 & 7);
            int n01 = aoFactor(f.v >>> 14 & 7), n11 = aoFactor(f.v >>> 17 & 7);
            generateSidesAndOffsets(f, n00, n10, n01, n11, sidesAndOffsets);
            generateTexCoords(f, lightmapCoords);
            generateIndices(f, i, indices);
        }
    }

    private static void generateIndices(Face f, int i, ShortBuffer indices) {
        if (isPositiveSide(f.s))
            indices.put((short) ((i << 2) + 1)).put((short) ((i << 2) + 3)).put((short) ((i << 2) + 0))
                   .put((short) ((i << 2) + 2)).put((short) PRIMITIVE_RESTART_INDEX);
        else
            indices.put((short) ((i << 2) + 2)).put((short) ((i << 2) + 3)).put((short) ((i << 2) + 0))
                   .put((short) ((i << 2) + 1)).put((short) PRIMITIVE_RESTART_INDEX);
    }

    private void generateTexCoords(Face f, ShortBuffer lightmapCoords) {
        lightmapCoords
                .put((short) f.tx).put((short) f.ty)
                .put((short) (f.tx + f.w())).put((short) f.ty)
                .put((short) f.tx).put((short) (f.ty + f.h()))
                .put((short) (f.tx + f.w())).put((short) (f.ty + f.h()));
    }

    private static byte aoFactor(int n) {
        boolean b1 = (n & 1) != 0, b2 = (n & 2) != 0, b4 = (n & 4) != 0;
        int f = b1 && b4 ? 0 : (3 - Integer.bitCount(n));
        int x = b1 && !b2 && !b4 ? 1 : b4 || b2 && !b1 ? -1 : 0,
            y = b4 && !b2 && !b1 ? 1 : b1 || b2 && !b4 ? -1 : 0;
        return (byte) ((x + 1) | (y + 1) << 2 | f << 4);
    }

    private static void generateSidesAndOffsets(Face f, int ao00, int ao10, int ao01, int ao11, ByteBuffer sidesAndOffsets) {
        sidesAndOffsets.put(f.s).put((byte) ao00);
        sidesAndOffsets.put(f.s).put((byte) ao10);
        sidesAndOffsets.put(f.s).put((byte) ao01);
        sidesAndOffsets.put(f.s).put((byte) ao11);
    }

    private static void generatePositionsAndTypesZ(Face f, ByteBuffer positions) {
        positions.put((byte) f.u0).put((byte) f.v0).put((byte) f.p).put((byte) f.v);
        positions.put((byte) f.u1).put((byte) f.v0).put((byte) f.p).put((byte) f.v);
        positions.put((byte) f.u0).put((byte) f.v1).put((byte) f.p).put((byte) f.v);
        positions.put((byte) f.u1).put((byte) f.v1).put((byte) f.p).put((byte) f.v);
    }

    private static void generatePositionsAndTypesY(Face f, ByteBuffer positions) {
        positions.put((byte) f.v0).put((byte) f.p).put((byte) f.u0).put((byte) f.v);
        positions.put((byte) f.v0).put((byte) f.p).put((byte) f.u1).put((byte) f.v);
        positions.put((byte) f.v1).put((byte) f.p).put((byte) f.u0).put((byte) f.v);
        positions.put((byte) f.v1).put((byte) f.p).put((byte) f.u1).put((byte) f.v);
    }

    private static void generatePositionsAndTypesX(Face f, ByteBuffer positions) {
        positions.put((byte) f.p).put((byte) f.u0).put((byte) f.v0).put((byte) f.v);
        positions.put((byte) f.p).put((byte) f.u1).put((byte) f.v0).put((byte) f.v);
        positions.put((byte) f.p).put((byte) f.u0).put((byte) f.v1).put((byte) f.v);
        positions.put((byte) f.p).put((byte) f.u1).put((byte) f.v1).put((byte) f.v);
    }

    private void createSceneVbos(ArrayList<Face> faces) {
        ByteBuffer positionsAndTypes = memAlloc(4 * faces.size() * VERTICES_PER_FACE);
        ByteBuffer sidesAndOffsets = memAlloc(2 * faces.size() * VERTICES_PER_FACE);
        ShortBuffer lightmapCoords = memAllocShort(2 * faces.size() * VERTICES_PER_FACE);
        ShortBuffer indices = memAllocShort(faces.size() * INDICES_PER_FACE);
        triangulate(faces, positionsAndTypes, sidesAndOffsets, lightmapCoords, indices);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int positionsAndTypesBufferObject = setupPositionsAndTypes(positionsAndTypes);
        int sidesAndOffsetsBufferObject = setupSidesAndOffsets(sidesAndOffsets);
        int lightmapCoordsBufferObject = setupLightmapCoords(lightmapCoords);
        int indicesBufferObject = setupIndices(indices);
        memFree(positionsAndTypes);
        memFree(sidesAndOffsets);
        memFree(lightmapCoords);
        memFree(indices);
        glBindVertexArray(0);
        glDeleteBuffers(new int[] {positionsAndTypesBufferObject, sidesAndOffsetsBufferObject, lightmapCoordsBufferObject, indicesBufferObject});
    }

    private int setupIndices(ShortBuffer indices) {
        indices.flip();
        int indicesBufferObject = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indicesBufferObject);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        return indicesBufferObject;
    }

    private int setupLightmapCoords(ShortBuffer lightmapCoords) {
        lightmapCoords.flip();
        int lightmapCoordsBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, lightmapCoordsBufferObject);
        glBufferData(GL_ARRAY_BUFFER, lightmapCoords, GL_STATIC_DRAW);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_UNSIGNED_SHORT, false, 0, 0L);
        return lightmapCoordsBufferObject;
    }

    private int setupSidesAndOffsets(ByteBuffer sidesAndOffsets) {
        sidesAndOffsets.flip();
        int sidesAndOffsetsBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, sidesAndOffsetsBufferObject);
        glBufferData(GL_ARRAY_BUFFER, sidesAndOffsets, GL_STATIC_DRAW);
        glEnableVertexAttribArray(1);
        glVertexAttribIPointer(1, 2, GL_UNSIGNED_BYTE, 0, 0L);
        return sidesAndOffsetsBufferObject;
    }

    private int setupPositionsAndTypes(ByteBuffer positionsAndTypes) {
        positionsAndTypes.flip();
        int positionsAndTypesBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, positionsAndTypesBufferObject);
        glBufferData(GL_ARRAY_BUFFER, positionsAndTypes, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 4, GL_UNSIGNED_BYTE, false, 0, 0L);
        return positionsAndTypesBufferObject;
    }

    private void createSceneTBOs(ArrayList<Face> faces) {
        System.out.println("Building kd-tree...");
        KDTreei<Face> root = build(faces, 14);
        System.out.println("Serializing kd-tree to buffers...");
        DynamicByteBuffer facesBuffer = new DynamicByteBuffer();
        DynamicByteBuffer nodesBuffer = new DynamicByteBuffer();
        DynamicByteBuffer leafNodesBuffer = new DynamicByteBuffer();
        kdTreeToBuffers(root, nodesBuffer, leafNodesBuffer, facesBuffer);
        createFacesTexture(facesBuffer);
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

    private void createFacesTexture(DynamicByteBuffer facesBuffer) {
        System.out.println("Faces buffer: " + facesBuffer.pos / 1024 + " KB");
        facesBufferObject = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, facesBufferObject);
        nglBufferData(GL_TEXTURE_BUFFER, facesBuffer.pos, facesBuffer.addr, GL_STATIC_DRAW);
        facesBuffer.free();
        facesTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, facesTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, facesBufferObject);
    }

    private static ArrayList<KDTreei.Node<Face>> allocate(KDTreei.Node<Face> node) {
        ArrayList<KDTreei.Node<Face>> linearNodes = new ArrayList<>();
        LinkedList<KDTreei.Node<Face>> nodes = new LinkedList<>();
        int index = 0, leafIndex = 0;
        nodes.add(node);
        while (!nodes.isEmpty()) {
            KDTreei.Node<Face> n = nodes.removeFirst();
            linearNodes.add(n);
            n.index = index++;
            if (n.left != null) {
                nodes.addFirst(n.right);
                nodes.addFirst(n.left);
            } else {
                n.leafIndex = leafIndex++;
            }
        }
        return linearNodes;
    }

    private void kdTreeToBuffers(KDTreei<Face> root,
                                 DynamicByteBuffer nodesBuffer, DynamicByteBuffer leafNodesBuffer,
                                 DynamicByteBuffer facesBuffer) {
        int first = 0;
        ArrayList<KDTreei.Node<Face>> nodes = allocate(root.root);
        System.out.println("Num nodes in kd-tree: " + nodes.size());
        for (KDTreei.Node<Face> n : nodes) {
            int numFaces = 0;
            if (n.left == null) {
                numFaces = n.boundables.size();
                for (int i = 0; i < numFaces; i++) {
                    Face f = n.boundables.get(i);
                    // RGBA32UI
                    int s = f.s >>> 1;
                    facesBuffer.putInt(f.p << (s << 3) | f.u0 << ((s + 1 << 3) % 24) | f.v0 << ((s + 2 << 3) % 24) | f.v << 24);
                    facesBuffer.putInt(f.u1 - f.u0 << (s + 1 << 3) % 24 | f.u1 - f.u0 << 24);
                    facesBuffer.putInt(f.v1 - f.v0 << (s + 2 << 3) % 24 | f.v1 - f.v0 << 24);
                    facesBuffer.putShort(f.tx).putShort(f.ty);
                }
                // RGBA32UI
                leafNodesBuffer.putShort(first).putShort(numFaces);
                for (int i = 0; i < 6; i++)
                    leafNodesBuffer.putShort(n.ropes[i] != null ? n.ropes[i].index : -1);
                first += numFaces;
            }
            // RGBA32UI
            nodesBuffer.putByte(n.bb.minX).putByte(n.bb.minY).putByte(n.bb.minZ).putByte(NOT_USED);
            nodesBuffer.putByte(n.bb.maxX - 1).putByte(n.bb.maxY - 1).putByte(n.bb.maxZ - 1).putByte(NOT_USED);
            nodesBuffer.putShort(n.right != null ? n.right.index : n.leafIndex);
            nodesBuffer.putShort(n.splitAxis == -1 ? -1 : n.splitAxis << (Short.SIZE - 2) | n.splitPos);
            nodesBuffer.putInt(NOT_USED);
        }
        System.out.println("Num faces in kd-tree: " + first);
    }

    private void handleKeyboardInput(float dt) {
        float factor = 10.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 40.0f;
        if (keydown[GLFW_KEY_W])
            vMat.translateLocal(0, 0, factor * dt);
        if (keydown[GLFW_KEY_S])
            vMat.translateLocal(0, 0, -factor * dt);
        if (keydown[GLFW_KEY_A])
            vMat.translateLocal(factor * dt, 0, 0);
        if (keydown[GLFW_KEY_D])
            vMat.translateLocal(-factor * dt, 0, 0);
        if (keydown[GLFW_KEY_Q])
            vMat.rotateLocalZ(-factor * dt);
        if (keydown[GLFW_KEY_E])
            vMat.rotateLocalZ(factor * dt);
        if (keydown[GLFW_KEY_LEFT_CONTROL])
            vMat.translateLocal(0, factor * dt, 0);
        if (keydown[GLFW_KEY_SPACE])
            vMat.translateLocal(0, -factor * dt, 0);
    }

    private void update(float dt) {
        handleKeyboardInput(dt);
        vMat.withLookAtUp(0, 1, 0);
        pMat.setPerspective(Math.toRadians(50.0f), (float) width / height, 0.1f, 1200.0f);
        pMat.mulPerspectiveAffine(vMat, mvpMat).invert(ivpMat);
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
            glUniform3fv(rasterProgramCamPosUniform, vMat.origin(camPos).get(stack.mallocFloat(3)));
        }
        glUniform2i(rasterProgramLightmapSizeUniform, lightmapTexWidth, lightmapTexHeight);
        glViewport(0, 0, width, height);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, lightmapTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_BUFFER, nodesTexture);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_BUFFER, leafNodesTexture);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_BUFFER, facesTexture);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLE_STRIP, faceCount * INDICES_PER_FACE, GL_UNSIGNED_SHORT, 0L);
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
        glBindTexture(GL_TEXTURE_BUFFER, facesTexture);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, blendIndexTexture);
        glViewport(0, 0, lightmapTexWidth, lightmapTexHeight);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLE_STRIP, faceCount * INDICES_PER_FACE, GL_UNSIGNED_SHORT, 0L);
        glBindVertexArray(0);
        glUseProgram(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static int idx(int x, int y, int z, int width, int depth) {
        return (x + 1) + (width + 2) * ((z + 1) + (y + 1) * (depth + 2));
    }

    private static class VoxelField {
        int ny, py, w, d;
        byte[] field;
    }

    private VoxelField buildVoxelField() throws IOException {
        System.out.println("Building voxel field...");
        Vector3i dims = new Vector3i();
        Vector3i min = new Vector3i(Integer.MAX_VALUE);
        Vector3i max = new Vector3i(Integer.MIN_VALUE);
        byte[] field = new byte[(256 + 2) * (256 + 2) * (256 + 2)];
        try (InputStream is = getSystemResourceAsStream("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house5.vox");
             BufferedInputStream bis = new BufferedInputStream(is)) {
            new MagicaVoxelLoader().read(bis, new MagicaVoxelLoader.Callback() {
                public void voxel(int x, int y, int z, byte c) {
                    y = dims.z - y - 1;
                    field[idx(x, z, y, dims.x, dims.z)] = c;
                    min.set(Math.min(min.x, x), Math.min(min.y, z), Math.min(min.z, y));
                    max.set(Math.max(max.x, x), Math.max(max.y, z), Math.max(max.z, y));
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
        }
        System.out.println("Voxel field dimensions: " + dims);
        System.out.println("Actual voxel data: " + min + " - " + max);
        VoxelField res = new VoxelField();
        res.w = dims.x;
        res.d = dims.z;
        res.ny = min.y;
        res.py = max.y;
        res.field = field;
        return res;
    }

    private ArrayList<Face> buildFaces(VoxelField vf) {
        System.out.println("Building faces...");
        /* Greedy-meshing */
        GreedyMeshing gm = new GreedyMeshing(0, vf.ny, 0, vf.py, vf.w, vf.d);
        ArrayList<Face> faces = new ArrayList<>();
        gm.mesh(vf.field, faces);
        System.out.println("Num faces: " + faces.size());
        return faces;
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
        new VoxelLightmapping2().run();
    }
}
