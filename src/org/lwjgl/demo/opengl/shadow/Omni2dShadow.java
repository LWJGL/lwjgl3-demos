/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shadow;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.lang.Math;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

import org.joml.*;
import org.lwjgl.*;
import org.lwjgl.assimp.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Omnidirectional 2D shadows using "1D shadow mapping".
 * 
 * @author Kai Burjack
 */
public class Omni2dShadow {

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
             *                 the Assimp {@link AIMesh} object
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

    static Vector3f UP = new Vector3f(0, 0, -1);
    static int shadowMapWidth = 1024;
    static Vector3f lightPosition = new Vector3f(0.0f, 0.5f, 0.0f);
    static Vector3f cameraPosition = new Vector3f(0.0f, 16.0f, 4.0f);
    static Vector3f cameraLookAt = new Vector3f();
    static float near = 0.2f;
    static float far = 30.0f;

    long window;
    int width = 1200;
    int height = 800;
    float time = 0.0f;

    Model model;
    int shadowProgram;
    int shadowProgramProjectionUniform;
    int shadowProgramLightPositionUniform;
    int normalProgram;
    int normalProgramViewProjectionUniform;
    int normalProgramLightProjectionUniform;
    int normalProgramLightPositionUniform;
    int fbo;
    int depthTexture;

    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    Matrix4f identity = new Matrix4f();
    Matrix4f lightProjection = new Matrix4f().setFrustum(-near, near, near * -1E-4f, near * 1E-4f, near, far);
    Matrix4f camera = new Matrix4f();
    Matrix4f lightTexProjection = new Matrix4f(lightProjection).translateLocal(1, 1, 1).scaleLocal(0.5f);

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback cpCallback;
    Callback debugProc;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 3.2 or higher.");
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Omnidirectional 2D Shadow Mapping Demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (Omni2dShadow.this.width != width || Omni2dShadow.this.height != height)) {
                    Omni2dShadow.this.width = width;
                    Omni2dShadow.this.height = height;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            Vector3f origin = new Vector3f();
            Vector3f dir = new Vector3f();
            Vector3f point = new Vector3f();
            Vector3f normal = new Vector3f(0, 1, 0);
            int[] viewport = new int[4];

            public void invoke(long window, double xpos, double ypos) {
                viewport[2] = width;
                viewport[3] = height;
                camera.unprojectRay((float) xpos, height - (float) ypos, viewport, origin, dir);
                float t = Intersectionf.intersectRayPlane(origin, dir, point, normal, 1E-6f);
                lightPosition.set(dir).mul(t).add(origin).add(0f, 0.5f, 0f);
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Set some GL states */
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.2f, 0.3f, 0.4f, 1.0f);

        /* Create all needed GL resources */
        loadModel();
        createDepthTexture();
        createFbo();
        createShadowProgram();
        initShadowProgram();
        createNormalProgram();
        initNormalProgram();
    }

    /**
     * Create the texture storing the depth values of the light-render.
     */
    void createDepthTexture() {
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_1D_ARRAY, depthTexture);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_1D_ARRAY, 0, GL_DEPTH24_STENCIL8, shadowMapWidth, 4, 0, GL_DEPTH_COMPONENT,
                GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_1D_ARRAY, 0);
    }

    /**
     * Create the FBO to render the depth values of the light-render into the depth texture.
     */
    void createFbo() {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glFramebufferTexture(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depthTexture, 0);
        int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Could not create FBO: " + fboStatus);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void loadModel() throws IOException {
        byte[] bytes = readSingleFileZip("scene.obj.zip");
        ByteBuffer bb = BufferUtils.createByteBuffer(bytes.length);
        bb.put(bytes).flip();
        AIScene scene = Assimp.aiImportFileFromMemory(bb, 0, "obj");
        model = new Model(scene);
    }

    private static byte[] readSingleFileZip(String zipResource) throws IOException {
        ZipInputStream zipStream = new ZipInputStream(Omni2dShadow.class.getResourceAsStream(zipResource));
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

    void createShadowProgram() throws IOException {
        shadowProgram = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-vs.glsl", GL_VERTEX_SHADER);
        int gshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-gs.glsl", GL_GEOMETRY_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadow-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(shadowProgram, vshader);
        glAttachShader(shadowProgram, gshader);
        glAttachShader(shadowProgram, fshader);
        glBindAttribLocation(shadowProgram, 0, "position");
        glLinkProgram(shadowProgram);
        int linked = glGetProgrami(shadowProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(shadowProgram);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
    }

    void initShadowProgram() {
        glUseProgram(shadowProgram);
        shadowProgramProjectionUniform = glGetUniformLocation(shadowProgram, "projection");
        shadowProgramLightPositionUniform = glGetUniformLocation(shadowProgram, "lightPosition");
        glUseProgram(0);
    }

    void createNormalProgram() throws IOException {
        normalProgram = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadowShade-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shadow/omni2dShadowShade-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(normalProgram, vshader);
        glAttachShader(normalProgram, fshader);
        glBindAttribLocation(normalProgram, 0, "position");
        glBindAttribLocation(normalProgram, 1, "normal");
        glBindFragDataLocation(normalProgram, 0, "color");
        glLinkProgram(normalProgram);
        int linked = glGetProgrami(normalProgram, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(normalProgram);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
    }

    void initNormalProgram() {
        glUseProgram(normalProgram);
        int depthMapsUniform = glGetUniformLocation(normalProgram, "depthMaps");
        normalProgramViewProjectionUniform = glGetUniformLocation(normalProgram, "viewProjection");
        normalProgramLightProjectionUniform = glGetUniformLocation(normalProgram, "lightProjection");
        normalProgramLightPositionUniform = glGetUniformLocation(normalProgram, "lightPosition");
        glUniform1i(depthMapsUniform, 0);
        glUseProgram(0);
    }

    void update() {
        camera.setPerspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 30.0f).lookAt(cameraPosition,
                cameraLookAt, UP);
    }

    void renderModel() {
        for (Model.Mesh mesh : model.meshes) {
            glBindVertexArray(mesh.vao);
            glDrawElements(GL_TRIANGLES, mesh.elementCount, GL_UNSIGNED_INT, 0L);
        }
    }

    /**
     * Render the shadow map into a depth texture.
     */
    void renderShadowMap() {
        glUseProgram(shadowProgram);
        /* Set VP matrix of the "light cameras" */
        glUniformMatrix4fv(shadowProgramProjectionUniform, false, lightProjection.get(matrixBuffer));
        glUniform3f(shadowProgramLightPositionUniform, lightPosition.x, lightPosition.y, lightPosition.z);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, shadowMapWidth, 1);
        /* Only clear depth buffer, since we don't have a color draw buffer */
        glClear(GL_DEPTH_BUFFER_BIT);
        glCullFace(GL_FRONT);
        renderModel();
        glCullFace(GL_BACK);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
    }

    /**
     * Render the scene normally, with sampling the previously rendered depth texture.
     */
    void renderNormal() {
        glUseProgram(normalProgram);
        /* Set VP matrix of camera */
        glUniformMatrix4fv(normalProgramViewProjectionUniform, false, camera.get(matrixBuffer));
        /* Set Bias*P matrix that was used when doing the light-render */
        glUniformMatrix4fv(normalProgramLightProjectionUniform, false, lightTexProjection.get(matrixBuffer));
        /* Light position for lighting computation */
        glUniform3f(normalProgramLightPositionUniform, lightPosition.x, lightPosition.y, lightPosition.z);
        glViewport(0, 0, width, height);
        /* Must clear both color and depth, since we are re-rendering the scene */
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glBindTexture(GL_TEXTURE_1D_ARRAY, depthTexture);
        renderModel();
        glBindTexture(GL_TEXTURE_1D_ARRAY, 0);
        glUseProgram(0);
    }

    void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            long thisTime = System.nanoTime();
            float diff = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            time += diff;
            update();
            renderShadowMap();
            renderNormal();

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
            cpCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new Omni2dShadow().run();
    }

}