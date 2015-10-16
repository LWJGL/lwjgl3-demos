/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.fbo;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.opengl.EXTFramebufferMultisample.*;
import static org.lwjgl.opengl.EXTFramebufferBlit.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;

/**
 * Same as {@link EdgeShaderDemo20} but renders the normals on a multisampled renderbuffer.
 * <p>
 * This demo is also suitable for cards/drivers that do not support multisampled textures via ARB_texture_multisample,
 * because this demo uses multisampled renderbuffers and uses EXT_framebuffer_blit to resolve the samples
 * onto a single-sampled FBO.
 * 
 * @author Kai Burjack
 */
public class EdgeShaderMultisampleDemo20 {

    long window;
    int width = 1024;
    int height = 768;
    boolean resize;

    int fbo;
    int rbo;
    int rbo2;
    int fbo2;
    int tex;

    int cubeVbo;
    int quadVbo;

    int normalProgram;
    int viewMatrixUniform;
    int projMatrixUniform;
    int normalMatrixUniform;

    int edgeProgram;
    int normalTexUniform;
    int invWidthUniform;
    int invHeightUniform;

    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix3f normalMatrix = new Matrix3f();
    ByteBuffer matrixByteBuffer = BufferUtils.createByteBuffer(4 * 16);

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Closure debugProc;

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
            public void release() {
                delegate.release();
                super.release();
            }
        });

        if (glfwInit() != GL_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);

        window = glfwCreateWindow(width, height, "Multisampled silhouette rendering with Sobel edge detection shader", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (EdgeShaderMultisampleDemo20.this.width != width || EdgeShaderMultisampleDemo20.this.height != height)) {
                    EdgeShaderMultisampleDemo20.this.width = width;
                    EdgeShaderMultisampleDemo20.this.height = height;
                    EdgeShaderMultisampleDemo20.this.resize = true;
                }
            }
        });

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, GL_TRUE);
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.getWidth() - width) / 2, (vidmode.getHeight() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        if (!caps.GL_EXT_framebuffer_object) {
            throw new AssertionError("This demo requires the EXT_framebuffer_object extension");
        }
        if (!caps.GL_EXT_framebuffer_multisample) {
            throw new AssertionError("This demo requires the EXT_framebuffer_multisample extension");
        }
        if (!caps.GL_EXT_framebuffer_blit) {
            throw new AssertionError("This demo requires the EXT_framebuffer_blit extension");
        }

        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        /* Create all needed GL resources */
        createCube();
        createQuad();
        createNormalProgram();
        createEdgeProgram();
        createTex();
        createFbos();
    }

    void createTex() {
        if (tex != 0) {
            glDeleteTextures(tex);
        }
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void createFbos() {
        if (fbo != 0) {
            glDeleteFramebuffersEXT(fbo);
            glDeleteFramebuffersEXT(fbo2);
            glDeleteRenderbuffersEXT(rbo);
            glDeleteRenderbuffersEXT(rbo2);
        }
        fbo = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        rbo = glGenRenderbuffersEXT();
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, rbo);
        glRenderbufferStorageMultisampleEXT(GL_RENDERBUFFER_EXT, 4, GL_DEPTH_COMPONENT, width, height);
        glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, rbo);
        rbo2 = glGenRenderbuffersEXT();
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, rbo2);
        glRenderbufferStorageMultisampleEXT(GL_RENDERBUFFER_EXT, 4, GL_RGBA8, width, height);
        glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_RENDERBUFFER_EXT, rbo2);
        int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Incomplete framebuffer: " + status);
        }
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, 0);

        fbo2 = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo2);
        glBindTexture(GL_TEXTURE_2D, tex);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, tex, 0);
        status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new AssertionError("Incomplete framebuffer: " + status);
        }
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, 0);
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

    void createCube() {
        FloatBuffer pb = BufferUtils.createFloatBuffer((3 + 3) * 6 * 6);
        // -X
        pb.put(-1).put(-1).put(-1).put(-1).put( 0).put( 0);
        pb.put(-1).put(-1).put( 1).put(-1).put( 0).put( 0);
        pb.put(-1).put( 1).put( 1).put(-1).put( 0).put( 0);
        pb.put(-1).put( 1).put( 1).put(-1).put( 0).put( 0);
        pb.put(-1).put( 1).put(-1).put(-1).put( 0).put( 0);
        pb.put(-1).put(-1).put(-1).put(-1).put( 0).put( 0);
        // +X
        pb.put( 1).put(-1).put( 1).put( 1).put( 0).put( 0);
        pb.put( 1).put(-1).put(-1).put( 1).put( 0).put( 0);
        pb.put( 1).put( 1).put(-1).put( 1).put( 0).put( 0);
        pb.put( 1).put( 1).put(-1).put( 1).put( 0).put( 0);
        pb.put( 1).put( 1).put( 1).put( 1).put( 0).put( 0);
        pb.put( 1).put(-1).put( 1).put( 1).put( 0).put( 0);
        // -Y
        pb.put(-1).put(-1).put( 1).put( 0).put(-1).put( 0);
        pb.put(-1).put(-1).put(-1).put( 0).put(-1).put( 0);
        pb.put( 1).put(-1).put(-1).put( 0).put(-1).put( 0);
        pb.put( 1).put(-1).put(-1).put( 0).put(-1).put( 0);
        pb.put( 1).put(-1).put( 1).put( 0).put(-1).put( 0);
        pb.put(-1).put(-1).put( 1).put( 0).put(-1).put( 0);
        // +Y
        pb.put(-1).put( 1).put( 1).put( 0).put( 1).put( 0);
        pb.put( 1).put( 1).put( 1).put( 0).put( 1).put( 0);
        pb.put( 1).put( 1).put(-1).put( 0).put( 1).put( 0);
        pb.put( 1).put( 1).put(-1).put( 0).put( 1).put( 0);
        pb.put(-1).put( 1).put(-1).put( 0).put( 1).put( 0);
        pb.put(-1).put( 1).put( 1).put( 0).put( 1).put( 0);
        // -Z
        pb.put( 1).put(-1).put(-1).put( 0).put( 0).put(-1);
        pb.put(-1).put(-1).put(-1).put( 0).put( 0).put(-1);
        pb.put(-1).put( 1).put(-1).put( 0).put( 0).put(-1);
        pb.put(-1).put( 1).put(-1).put( 0).put( 0).put(-1);
        pb.put( 1).put( 1).put(-1).put( 0).put( 0).put(-1);
        pb.put( 1).put(-1).put(-1).put( 0).put( 0).put(-1);
        // +Z
        pb.put(-1).put(-1).put( 1).put( 0).put( 0).put( 1);
        pb.put( 1).put(-1).put( 1).put( 0).put( 0).put( 1);
        pb.put( 1).put( 1).put( 1).put( 0).put( 0).put( 1);
        pb.put( 1).put( 1).put( 1).put( 0).put( 0).put( 1);
        pb.put(-1).put( 1).put( 1).put( 0).put( 0).put( 1);
        pb.put(-1).put(-1).put( 1).put( 0).put( 0).put( 1);
        pb.flip();
        this.cubeVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, this.cubeVbo);
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
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
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
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
            createFbos();
            resize = false;
        }
    }

    /**
     * Render the normals into a texture.
     */
    void renderNormal() {
        glEnable(GL_DEPTH_TEST);
        glUseProgram(this.normalProgram);

        glUniformMatrix4fv(viewMatrixUniform, 1, false, viewMatrix.get(matrixByteBuffer));
        glUniformMatrix4fv(projMatrixUniform, 1, false, projMatrix.get(matrixByteBuffer));
        glUniformMatrix3fv(normalMatrixUniform, 1, false, normalMatrix.get(matrixByteBuffer));

        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glBindBuffer(GL_ARRAY_BUFFER, this.cubeVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 4 * (3 + 3), 4 * 3L);
        glDrawArrays(GL_TRIANGLES, 0, 6 * 6);
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
        glUseProgram(this.edgeProgram);

        // Resolve the multisampled normals color renderbuffer
        // onto the single-sampled FBO 'fbo2' to which the 
        // texture 'tex' is attached.
        glBindFramebufferEXT(GL_READ_FRAMEBUFFER_EXT, fbo);
        glBindFramebufferEXT(GL_DRAW_FRAMEBUFFER_EXT, fbo2);
        glBlitFramebufferEXT(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

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

        glUseProgram(0);
    }

    void loop() {
        while (glfwWindowShouldClose(window) == GL_FALSE) {
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
                debugProc.release();
            }

            errCallback.release();
            keyCallback.release();
            fbCallback.release();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new EdgeShaderMultisampleDemo20().run();
    }

}