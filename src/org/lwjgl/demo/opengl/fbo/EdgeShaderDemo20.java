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
 * Uses a Sobel edge detection filter to render the edges/outline of a mesh.
 * <p>
 * The edges are detected based on the view-space normals of the mesh.
 * The outlines are detected based on a simple mask written to the color alpha channel.
 * 
 * @author Kai Burjack
 */
public class EdgeShaderDemo20 {

    long window;
    int width = 1024;
    int height = 768;
    boolean resize;

    int fbo;
    int rbo;
    int tex;

    int cubeVbo;
    long normalsOffset;
    int numVertices;
    int quadVbo;

    int normalProgram;
    int viewMatrixUniform;
    int projMatrixUniform;
    int normalMatrixUniform;

    int edgeProgram;
    int outlineProgram;
    int normalTexUniform;
    int invWidthUniform;
    int invHeightUniform;
    int edgeShowEdgeUniform;
    int outlineShowEdgeUniform;

    boolean outlineOnly;
    boolean showEdge = true;

    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix3f normalMatrix = new Matrix3f();
    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

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

        window = glfwCreateWindow(width, height, "Silhouette rendering with Sobel edge detection shader", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        System.out.println("Press letter 'O' to toggle between outline/edges.");
        System.out.println("Press spacebar to show/hide edges.");

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (EdgeShaderDemo20.this.width != width || EdgeShaderDemo20.this.height != height)) {
                    EdgeShaderDemo20.this.width = width;
                    EdgeShaderDemo20.this.height = height;
                    EdgeShaderDemo20.this.resize = true;
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
                } else if (key == GLFW_KEY_O) {
                    outlineOnly = !outlineOnly;
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

        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(1.0f, 1.0f, 1.0f, 0.0f); // using alpha = 0.0 is important here for the outline to work!
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        /* Create all needed GL resources */
        createCube();
        createQuad();
        createNormalProgram();
        createEdgeProgram();
        createOutlineProgram();
        createTex();
        createFbo();
    }

    void createTex() {
        if (tex != 0) {
            glDeleteTextures(tex);
        }
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void createFbo() {
        if (fbo != 0) {
            glDeleteFramebuffersEXT(fbo);
            glDeleteRenderbuffersEXT(rbo);
        }
        fbo = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        rbo = glGenRenderbuffersEXT();
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, rbo);
        glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, GL_DEPTH_COMPONENT, width, height);
        glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, rbo);
        glBindTexture(GL_TEXTURE_2D, tex);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, tex, 0);
        int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Incomplete framebuffer: " + status);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, 0);
    }

    void createQuad() {
        FloatBuffer pb = BufferUtils.createFloatBuffer(2 * 6);
        pb.put(-1).put(-1);
        pb.put(1).put(-1);
        pb.put(1).put(1);
        pb.put(1).put(1);
        pb.put(-1).put(1);
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
        int vshader = createShader("org/lwjgl/demo/opengl/fbo/sobel-edge-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/fbo/sobel-edge-fs.glsl", GL_FRAGMENT_SHADER);
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
        invWidthUniform = glGetUniformLocation(this.edgeProgram, "invWidth");
        invHeightUniform = glGetUniformLocation(this.edgeProgram, "invHeight");
        edgeShowEdgeUniform = glGetUniformLocation(this.edgeProgram, "showEdge");
        glUseProgram(0);
    }

    void createOutlineProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/fbo/sobel-outline-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/fbo/sobel-outline-fs.glsl", GL_FRAGMENT_SHADER);
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
        this.outlineProgram = program;
        glUseProgram(this.outlineProgram);
        normalTexUniform = glGetUniformLocation(this.outlineProgram, "normalTex");
        invWidthUniform = glGetUniformLocation(this.outlineProgram, "invWidth");
        invHeightUniform = glGetUniformLocation(this.outlineProgram, "invHeight");
        outlineShowEdgeUniform = glGetUniformLocation(this.edgeProgram, "showEdge");
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
        viewMatrix.setLookAt(
                0.0f, 2.0f, 7.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f)
                  .rotateY(angle / 2.0f)
                  .rotateX(angle / 8.0f);
        viewMatrix.normal(normalMatrix);

        if (resize) {
            createTex();
            createFbo();
            resize = false;
        }
    }

    /**
     * Render the normals into a texture.
     */
    void renderNormal() {
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
     * Render the edges using the Sobel edge filter and blend that with the normals.
     */
    void renderEdge() {
        glDisable(GL_DEPTH_TEST);
        if (outlineOnly) {
            glUseProgram(this.outlineProgram);
            glUniform1i(outlineShowEdgeUniform, showEdge ? 1 : 0);
        } else {
            glUseProgram(this.edgeProgram);
            glUniform1i(edgeShowEdgeUniform, showEdge ? 1 : 0);
        }

        glClear(GL_COLOR_BUFFER_BIT);

        glUniform1f(invWidthUniform, 1.0f / width);
        glUniform1f(invHeightUniform, 1.0f / height);
        glUniform1i(normalTexUniform, 0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindBuffer(GL_ARRAY_BUFFER, this.quadVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);

        glUseProgram(0);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            renderNormal();
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
        new EdgeShaderDemo20().run();
    }

}