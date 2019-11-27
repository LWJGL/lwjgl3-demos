/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.fbo;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.opengl.ARBTextureFloat.*;
import java.io.IOException;
import java.nio.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.util.WavefrontMeshLoader;
import org.lwjgl.demo.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Uses an edge detection filter to render the edges of a mesh.
 * <p>
 * The edges are detected based on the reconstructed view-space position of the
 * mesh using the depth buffer.
 * 
 * @author Kai Burjack
 */
public class DepthEdgeShaderDemo20 {

    long window;
    int width = 1024;
    int height = 768;
    boolean resize;

    int fbo;
    int depthTexture;
    int normalTexture;

    int cubeVbo;
    long normalsOffset;
    int numVertices;
    int quadVbo;

    int normalProgram;
    int viewMatrixUniform;
    int projMatrixUniform;
    int normalMatrixUniform;

    int edgeProgram;
    int normalTexUniform;
    int depthTexUniform;
    int inverseMatrixUniform;
    int invWidthUniform;
    int invHeightUniform;
    int showEdgeUniform;

    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix4f invMatrix = new Matrix4f();
    Matrix3f normalMatrix = new Matrix3f();
    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    boolean showEdge = true;

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 2.0 or higher.");
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Silhouette rendering with edge detection shader", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Press spacebar to show/hide edges.");

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (DepthEdgeShaderDemo20.this.width != width || DepthEdgeShaderDemo20.this.height != height)) {
                    DepthEdgeShaderDemo20.this.width = width;
                    DepthEdgeShaderDemo20.this.height = height;
                    DepthEdgeShaderDemo20.this.resize = true;
                }
            }
        });

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_SPACE) {
                    showEdge = !showEdge;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        if (!caps.GL_EXT_framebuffer_object) {
            throw new AssertionError("This demo requires the EXT_framebuffer_object extension");
        }
        if (!caps.GL_ARB_texture_float) {
            throw new AssertionError("This demo requires the ARB_texture_float extension");
        }

        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(0.0f, 0.0f, 1.0f, 0.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        /* Create all needed GL resources */
        createCube();
        createQuad();
        createNormalProgram();
        createEdgeProgram();
        createTex();
        createFbo();
    }

    void createTex() {
        if (normalTexture != 0) {
            glDeleteTextures(normalTexture);
            glDeleteTextures(depthTexture);
        }
        normalTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F_ARB, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, width, height, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE, 0L);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void createFbo() {
        if (fbo != 0) {
            glDeleteFramebuffersEXT(fbo);
        }
        fbo = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_TEXTURE_2D, depthTexture, 0);
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, normalTexture, 0);
        int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Incomplete framebuffer: " + status);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }

    void createQuad() {
        FloatBuffer pb = BufferUtils.createFloatBuffer(2 * 6);
        pb.put(-1).put(-1);
        pb.put( 1).put(-1);
        pb.put( 1).put( 1);
        pb.put( 1).put( 1);
        pb.put(-1).put( 1);
        pb.put(-1).put(-1);
        pb.flip();
        this.quadVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, this.quadVbo);
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    void createCube() throws IOException {
        WavefrontMeshLoader loader = new WavefrontMeshLoader();
        Mesh mesh = loader.loadMesh("org/lwjgl/demo/opengl/models/cube.obj.zip");
        this.numVertices = mesh.numVertices;
        long bufferSize = 4 * (3 + 3) * mesh.numVertices;
        this.normalsOffset = 4L * 3 * mesh.numVertices;
        this.cubeVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, this.cubeVbo);
        glBufferData(GL_ARRAY_BUFFER, bufferSize, GL_STATIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, mesh.positions);
        glBufferSubData(GL_ARRAY_BUFFER, normalsOffset, mesh.normals);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    void createNormalProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/fbo/normal-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/fbo/normal-fs.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "position");
        glBindAttribLocation(program, 1, "normal");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.normalProgram = program;
        glUseProgram(this.normalProgram);
        viewMatrixUniform = glGetUniformLocation(this.normalProgram, "viewMatrix");
        projMatrixUniform = glGetUniformLocation(this.normalProgram, "projMatrix");
        normalMatrixUniform = glGetUniformLocation(this.normalProgram, "normalMatrix");
        glUseProgram(0);
    }

    void createEdgeProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/fbo/depth-edge-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/fbo/depth-edge-fs.glsl", GL_FRAGMENT_SHADER);
        int fshader2 = createShader("org/lwjgl/demo/opengl/fbo/color.glsl", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, fshader2);
        glBindAttribLocation(program, 0, "position");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.edgeProgram = program;
        glUseProgram(this.edgeProgram);
        normalTexUniform = glGetUniformLocation(this.edgeProgram, "normalTex");
        depthTexUniform = glGetUniformLocation(this.edgeProgram, "depthTex");
        inverseMatrixUniform = glGetUniformLocation(this.edgeProgram, "inverseMatrix");
        invWidthUniform = glGetUniformLocation(this.edgeProgram, "invWidth");
        invHeightUniform = glGetUniformLocation(this.edgeProgram, "invHeight");
        showEdgeUniform = glGetUniformLocation(this.edgeProgram, "showEdge");
        glUseProgram(0);
    }

    float angle = 0.0f;
    long lastTime = System.nanoTime();

    void update() {
        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        angle += diff;
        lastTime = thisTime;

        projMatrix.setPerspective((float) Math.toRadians(30), (float) width / height, 0.01f, 50.0f);
        // compute inverse of projection matrix to reconstruct view-space position
        projMatrix.invertPerspective(invMatrix);
        viewMatrix.setLookAt(
                0.0f, 2.0f, 7.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f)
                  .rotateY(angle / 2.0f)
                  .rotateX(angle / 8.0f);
        // compute normal matrix to transform mesh normals
        viewMatrix.normal(normalMatrix);

        if (resize) {
            createTex();
            createFbo();
            resize = false;
        }
    }

    /**
     * Render the normals and depth into textures.
     */
    void renderNormalAndDepth() {
        glEnable(GL_DEPTH_TEST);
        glUseProgram(this.normalProgram);

        glUniformMatrix4fv(viewMatrixUniform, false, viewMatrix.get(matrixBuffer));
        glUniformMatrix4fv(projMatrixUniform, false, projMatrix.get(matrixBuffer));
        glUniformMatrix3fv(normalMatrixUniform, false, normalMatrix.get(matrixBuffer));

        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glBindBuffer(GL_ARRAY_BUFFER, this.cubeVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, normalsOffset);
        glDrawArrays(GL_TRIANGLES, 0, numVertices);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

        glUseProgram(0);
    }

    /**
     * Render the edges using the edge detection filter and blend that with the normals.
     */
    void renderEdge() {
        glDisable(GL_DEPTH_TEST);
        glUseProgram(this.edgeProgram);

        glClear(GL_COLOR_BUFFER_BIT);

        glUniformMatrix4fv(inverseMatrixUniform, false, invMatrix.get(matrixBuffer));
        glUniform1f(invWidthUniform, 1.0f / width);
        glUniform1f(invHeightUniform, 1.0f / height);
        glUniform1i(normalTexUniform, 0);
        glUniform1i(showEdgeUniform, showEdge ? 1 : 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, normalTexture);
        glUniform1i(depthTexUniform, 1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glBindBuffer(GL_ARRAY_BUFFER, this.quadVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glUseProgram(0);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            renderNormalAndDepth();
            renderEdge();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null) {
                debugProc.free();
            }

            errCallback.free();
            keyCallback.free();
            fbCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new DepthEdgeShaderDemo20().run();
    }

}