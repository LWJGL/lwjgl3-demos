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
 * Example of temporal antialiasing using reprojection but without any filtering or neighbour clamping.
 * <p>
 * This is like Tutorial8 but without filtering. In addition, this demo uses a velocity buffer.
 * <p>
 * There are three main color render buffers. The first two will hold the previous and next frame result and the FBO
 * ping-pongs between them. The third is an intermediate buffer to ray trace the scene into, which is then used as input
 * to the reprojection shader. This shader also uses a fourth render buffer which holds the pixel velocity information
 * computed by the ray tracing shader (which like in Tutorial8 is also a rasterization fragment shader).
 * 
 * @author Kai Burjack
 */
public class Tutorial8_2 {

    private static class Box {
        Vector3f min;
        Vector3f max;
    }

    private static final float MIN_Z = 0.1f;
    private static final float MAX_Z = 50.0f;
    private List<Box> boxes;
    private long window;
    private int windowWidth = 1920;
    private int windowHeight = 1080;
    private int fbSizeScale = 1;
    private boolean rebuildFramebuffer;
    private int quadVao;
    private int ubo;
    private int boxesUboIndex;
    private int rasterAndTraceProgram;
    private int cameraPositionUniform;
    private int viewMatrixUniform;
    private int projectionMatrixUniform;
    private int[] colorTextures = new int[3];
    private int velocityTexture;
    private int fbo;
    private int depthBuffer;
    private int vaoScene;
    private int quadProgram;
    private int reprojectProgram;
    private int linearSampler, nearestSampler;
    private int timeUniform;
    private int numBoxesUniform;
    private float mouseX, mouseY;
    private boolean mouseDown;
    private final boolean[] keydown = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f[] viewMatrix = { new Matrix4f(), new Matrix4f() };
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private int curr = 0, prev = 1;
    private final Vector3f tmpVector3f = new Vector3f();
    private static final float EYE_HEIGHT = 1.0f;
    private final Vector3f cameraPosition = new Vector3f(-5.0f, EYE_HEIGHT, 3.0f);
    private float alpha, beta;
    private final IntBuffer[] drawBuffers = { BufferUtils.createIntBuffer(1).put(0, GL_COLOR_ATTACHMENT0),
                    BufferUtils.createIntBuffer(1).put(0, GL_COLOR_ATTACHMENT1),
                    BufferUtils.createIntBuffer(2).put(0, GL_COLOR_ATTACHMENT2).put(1, GL_COLOR_ATTACHMENT3), };

    private GLFWErrorCallback errCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private Callback debugProc;
    private GLCapabilities caps;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(windowWidth, windowHeight, "Hybrid Path Tracing with TAA", NULL,
                        NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        System.out.println("Press WSAD to move around in the scene.");
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
                if (width > 0 && height > 0 && (windowWidth != width || windowHeight != height)) {
                    windowWidth = width;
                    windowHeight = height;
                    rebuildFramebuffer = true;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
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
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS && button == GLFW.GLFW_MOUSE_BUTTON_1) {
                    mouseDown = true;
                } else if (action == GLFW_RELEASE && button == GLFW.GLFW_MOUSE_BUTTON_1) {
                    mouseDown = false;
                }
            }
        });
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2, (vidmode.height() - windowHeight) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            windowWidth = framebufferSize.get(0);
            windowHeight = framebufferSize.get(1);
        }
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) windowWidth / windowHeight, MIN_Z, MAX_Z);
        for (int i = 0; i < viewMatrix.length; i++) {
            viewMatrix[i].setLookAt(cameraPosition, new Vector3f(0.0f, EYE_HEIGHT, 0.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f));
        }
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();
        importSceneAsBoxes();
        createSceneVao();
        this.quadVao = glGenVertexArrays();
        createSceneUBO();
        createSamplers();
        createFramebufferTextures();
        createFramebufferObject();
        createRasterAndTraceProgram();
        createReprojectProgram();
        if (!caps.GL_NV_draw_texture) {
            createQuadProgram();
        }
        glEnable(GL_CULL_FACE);
        glfwShowWindow(window);
    }

    private void importSceneAsBoxes() throws IOException {
        ByteBuffer bb = ioResourceToByteBuffer("org/lwjgl/demo/opengl/raytracing/tutorial8_2/cubes.obj", 8192);
        AIScene scene = Assimp.aiImportFileFromMemory(bb, 0, "obj");
        int meshCount = scene.mNumMeshes();
        PointerBuffer meshesBuffer = scene.mMeshes();
        boxes = new ArrayList<>();
        System.out.println("Loaded level with " + meshCount + " boxes");
        for (int i = 0; i < meshCount; i++) {
            AIMesh mesh = AIMesh.create(meshesBuffer.get(i));
            int verticesCount = mesh.mNumVertices();
            AIVector3D.Buffer vertices = mesh.mVertices();
            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
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

    private void createSceneVao() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(boxes.size() * 2 * 4 * (3 + 3) * 6 * 6);
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

    private void createSceneUBO() {
        this.ubo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, ubo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * (4 + 4) * boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            bb.putFloat(box.min.x).putFloat(box.min.y).putFloat(box.min.z).putFloat(0.0f);
            bb.putFloat(box.max.x).putFloat(box.max.y).putFloat(box.max.z).putFloat(0.0f);
        }
        bb.flip();
        glBufferData(GL_UNIFORM_BUFFER, bb, GL_STATIC_DRAW);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    private void createSamplers() {
        this.nearestSampler = glGenSamplers();
        this.linearSampler = glGenSamplers();
        glSamplerParameteri(this.linearSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(this.linearSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.linearSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.nearestSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private void createFramebufferTextures() {
        glGenTextures(this.colorTextures);
        for (int i = 0; i < this.colorTextures.length; i++) {
            glBindTexture(GL_TEXTURE_2D, colorTextures[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, windowWidth / fbSizeScale, windowHeight / fbSizeScale, 0,
                            GL_RGBA, GL_FLOAT, 0L);
        }
        this.velocityTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, velocityTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, windowWidth / fbSizeScale, windowHeight / fbSizeScale, 0, GL_RG,
                        GL_FLOAT, 0L);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void createFramebufferObject() {
        this.fbo = glGenFramebuffers();
        this.depthBuffer = glGenRenderbuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, windowWidth / fbSizeScale,
                        windowHeight / fbSizeScale);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextures[0], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, colorTextures[1], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, colorTextures[2], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, velocityTexture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void createRasterAndTraceProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/raster.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/rasterAndTrace.fs", GL_FRAGMENT_SHADER);
        int fshader2 = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/random.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader2);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertexPosition");
        glBindAttribLocation(program, 1, "vertexNormal");
        glBindAttribLocation(program, 2, "vertexIsLight");
        glBindFragDataLocation(program, 0, "color_out");
        glBindFragDataLocation(program, 1, "velocity_out");
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
        glUseProgram(rasterAndTraceProgram);
        cameraPositionUniform = glGetUniformLocation(rasterAndTraceProgram, "cameraPosition");
        viewMatrixUniform = glGetUniformLocation(rasterAndTraceProgram, "viewMatrix");
        projectionMatrixUniform = glGetUniformLocation(rasterAndTraceProgram, "projectionMatrix");
        timeUniform = glGetUniformLocation(rasterAndTraceProgram, "time");
        numBoxesUniform = glGetUniformLocation(rasterAndTraceProgram, "numBoxes");
        int prevColorTexUniform = glGetUniformLocation(rasterAndTraceProgram, "prevColorTex");
        glUniform1i(prevColorTexUniform, 0);
        int prevViewDepthTexUniform = glGetUniformLocation(rasterAndTraceProgram, "prevViewDepthTex");
        glUniform1i(prevViewDepthTexUniform, 1);
        boxesUboIndex = glGetUniformBlockIndex(rasterAndTraceProgram, "Boxes");
        glUseProgram(0);
    }

    private void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/quad.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/quad.fs.glsl", GL_FRAGMENT_SHADER);
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

    private void createReprojectProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/reproject.vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/raytracing/tutorial8_2/reproject.fs.glsl", GL_FRAGMENT_SHADER);
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
        this.reprojectProgram = program;
        glUseProgram(reprojectProgram);
        int texUniform = glGetUniformLocation(reprojectProgram, "tex");
        glUniform1i(texUniform, 0);
        glUniform1i(texUniform + 1, 1);
        int velocityUniform = glGetUniformLocation(reprojectProgram, "velocity");
        glUniform1i(velocityUniform, 2);
        glUseProgram(0);
    }

    private void resizeFramebuffer() {
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthBuffer);
        glDeleteTextures(colorTextures);
        glDeleteTextures(velocityTexture);
        createFramebufferTextures();
        createFramebufferObject();
    }

    private void update(float dt) {
        if (rebuildFramebuffer) {
            resizeFramebuffer();
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) windowWidth / windowHeight, MIN_Z, MAX_Z);
            rebuildFramebuffer = false;
        }
        float factor = 1.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 3.0f;
        if (keydown[GLFW_KEY_W]) {
            cameraPosition.sub(viewMatrix[curr].positiveZ(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_S]) {
            cameraPosition.add(viewMatrix[curr].positiveZ(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_A]) {
            cameraPosition.sub(viewMatrix[curr].positiveX(tmpVector3f).mul(dt * factor));
        }
        if (keydown[GLFW_KEY_D]) {
            cameraPosition.add(viewMatrix[curr].positiveX(tmpVector3f).mul(dt * factor));
        }
        cameraPosition.y = EYE_HEIGHT;
        viewMatrix[curr].rotationX(alpha).rotateY(beta).translate(-cameraPosition.x, -cameraPosition.y,
                        -cameraPosition.z);
    }

    private void present() {
        glViewport(0, 0, windowWidth, windowHeight);
        glDisable(GL_DEPTH_TEST);
        int tex = colorTextures[curr];
        if (!caps.GL_NV_draw_texture) {
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
            glUseProgram(quadProgram);
            glBindVertexArray(quadVao);
            glBindSampler(0, this.linearSampler);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, tex);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else {
            NVDrawTexture.glDrawTextureNV(tex, linearSampler, 0, 0, windowWidth, windowHeight, 0, 0, 0, 1, 1);
        }
    }

    private void reproject() {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glUseProgram(reprojectProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, linearSampler);
        glBindTexture(GL_TEXTURE_2D, colorTextures[colorTextures.length - 1]);
        glActiveTexture(GL_TEXTURE1);
        glBindSampler(1, linearSampler);
        glBindTexture(GL_TEXTURE_2D, colorTextures[prev]);
        glActiveTexture(GL_TEXTURE2);
        glBindSampler(2, linearSampler);
        glBindTexture(GL_TEXTURE_2D, velocityTexture);
        glViewport(0, 0, windowWidth / fbSizeScale, windowHeight / fbSizeScale);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffers(drawBuffers[curr]);
        glBindVertexArray(quadVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void rasterAndTrace(float elapsedSeconds) {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glUseProgram(rasterAndTraceProgram);
        glUniform3f(cameraPositionUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
        glUniformMatrix4fv(viewMatrixUniform, false, viewMatrix[curr].get(matrixBuffer));
        glUniformMatrix4fv(viewMatrixUniform + 1, false, viewMatrix[prev].get(matrixBuffer));
        glUniformMatrix4fv(projectionMatrixUniform, false, projMatrix.get(matrixBuffer));
        glUniform1f(timeUniform, elapsedSeconds);
        glUniform1i(numBoxesUniform, boxes.size());
        glBindBufferRange(GL_UNIFORM_BUFFER, boxesUboIndex, ubo, 0L, boxes.size() * (4 + 4) * 4);
        glViewport(0, 0, windowWidth / fbSizeScale, windowHeight / fbSizeScale);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffers(drawBuffers[drawBuffers.length - 1]);
        glClearBufferfv(GL_COLOR, 0, new float[] { 0.98f, 0.99f, 1.0f, 0.0f });
        glClearBufferfv(GL_COLOR, 1, new float[] { 0.0f, 0.0f, 0.0f, 0.0f });
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(vaoScene);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.size());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

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
            rasterAndTrace(thisTime * 1E-9f);
            reproject();
            present();
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
        new Tutorial8_2().run();
    }
}
