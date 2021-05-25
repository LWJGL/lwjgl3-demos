/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.*;
import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Texture2DArrayMipmapping {

    private long window;
    private int width = 1024;
    private int height = 768;
    private int texSize = 1024;

    private int tex;
    private int vao;
    private int program;
    private int viewProjMatrixUniform;

    private Matrix4f viewProjMatrix = new Matrix4f();
    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;

    private void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 3.0 or higher.");
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
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Mipmapping with 2D array textures", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0
                        && height > 0
                        && (Texture2DArrayMipmapping.this.width != width || Texture2DArrayMipmapping.this.height != height)) {
                    Texture2DArrayMipmapping.this.width = width;
                    Texture2DArrayMipmapping.this.height = height;
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
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        /* Create all needed GL resources */
        createTexture();
        createVao();
        createRasterProgram();
        initProgram();
    }

    private void createTexture() {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, this.tex);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        /* Add maximum anisotropic filtering, if available */
        if (caps.GL_EXT_texture_filter_anisotropic) {
            float maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
            glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
        }
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGB8, texSize, texSize, 2, 0, GL_RGB,
                GL_UNSIGNED_BYTE, (ByteBuffer) null);
        ByteBuffer bb = BufferUtils.createByteBuffer(3 * texSize * texSize);
        int checkSize = 5;
        /* Generate some checker board pattern */
        for (int y = 0; y < texSize; y++) {
            for (int x = 0; x < texSize; x++) {
                if (((x/checkSize + y/checkSize) % 2) == 0) {
                    bb.put((byte) 255).put((byte) 255).put((byte) 255);
                } else {
                    bb.put((byte) 0).put((byte) 0).put((byte) 0);
                }
            }
        }
        bb.flip();
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, texSize, texSize, 1, GL_RGB, GL_UNSIGNED_BYTE, bb);
        /* Generate some diagonal lines for the second layer */
        for (int y = 0; y < texSize; y++) {
            for (int x = 0; x < texSize; x++) {
                if ((x + y) / 3 % 3 == 0) {
                    bb.put((byte) 255).put((byte) 255).put((byte) 255);
                } else {
                    bb.put((byte) 0).put((byte) 0).put((byte) 0);
                }
            }
        }
        bb.flip();
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 1, texSize, texSize, 1, GL_RGB, GL_UNSIGNED_BYTE, bb);
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    private void createVao() {
        this.vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put(1.0f).put(-1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(-1.0f).put(1.0f);
        fv.put(-1.0f).put(-1.0f);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Create the raster shader.
     *
     * @throws IOException
     */
    private void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/texture2dArrayMipmap.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/texture2dArrayMipmap.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "position");
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
        this.program = program;
    }

    /**
     * Initialize the shader program.
     */
    private void initProgram() {
        glUseProgram(this.program);
        viewProjMatrixUniform = glGetUniformLocation(this.program, "viewProjMatrix");
        glUseProgram(0);
    }

    private void update() {
        viewProjMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f)
              .lookAt(0.0f, 1.0f, 5.0f,
                      0.0f, 0.0f, 0.0f,
                      0.0f, 1.0f, 0.0f);
    }

    private void render() {
        glUseProgram(this.program);

        glUniformMatrix4fv(viewProjMatrixUniform, false, viewProjMatrix.get(matrixBuffer));

        glBindVertexArray(vao);
        glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glBindVertexArray(0);

        glUseProgram(0);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            update();
            render();

            glfwSwapBuffers(window);
        }
    }

    private void run() {
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
        new Texture2DArrayMipmapping().run();
    }

}