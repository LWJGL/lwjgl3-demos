/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.ARBSeamlessCubeMap;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.util.IOUtil.ioResourceToByteBuffer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Loads and displays a simple starfield cubemap as well as a "black hole."
 * <p>
 * The starfield was generated with <a href="http://alexcpeterson.com/spacescape/">Spacescape</a>.
 * 
 * @author Kai Burjack
 */
public class FullscreenCubemapDemo {

    long window;
    int width = 1024;
    int height = 768;

    int tex;
    int vbo;
    int program;
    int invViewProjUniform;
    int cameraPositionUniform;

    Vector3f tmp = new Vector3f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f invViewProjMatrix = new Matrix4f();
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

        window = glfwCreateWindow(width, height, "Cubemap texture sampling", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (FullscreenCubemapDemo.this.width != width || FullscreenCubemapDemo.this.height != height)) {
                    FullscreenCubemapDemo.this.width = width;
                    FullscreenCubemapDemo.this.height = height;
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
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        createTexture();
        createFullScreenVbo();
        createProgram();
    }

    void createFullScreenVbo() {
        this.vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put( 1.0f).put(-1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put(-1.0f).put( 1.0f);
        fv.put(-1.0f).put(-1.0f);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
    }

    void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/textures/cubemap.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/textures/cubemap.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
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
        glUseProgram(program);
        int texLocation = glGetUniformLocation(program, "tex");
        glUniform1i(texLocation, 0);
        invViewProjUniform = glGetUniformLocation(program, "invViewProj");
        cameraPositionUniform = glGetUniformLocation(program, "cameraPosition");
    }

    void createTexture() throws IOException {
        this.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, this.tex);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        ByteBuffer imageBuffer;
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer comp = BufferUtils.createIntBuffer(1);
        String[] names = { "right", "left", "top", "bottom", "front", "back" };
        ByteBuffer image;
        if (caps.GL_EXT_texture_filter_anisotropic) {
            float maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
            glTexParameterf(GL_TEXTURE_CUBE_MAP, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_GENERATE_MIPMAP, GL_TRUE);
        for (int i = 0; i < 6; i++) {
            imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/opengl/textures/space_" + names[i] + (i+1) + ".jpg", 8 * 1024);
            if (stbi_info_from_memory(imageBuffer, w, h, comp) == 0)
                throw new IOException("Failed to read image information: " + stbi_failure_reason());
            image = stbi_load_from_memory(imageBuffer, w, h, comp, 0);
            if (image == null)
                throw new IOException("Failed to load image: " + stbi_failure_reason());
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGB8, w.get(0), h.get(0), 0, GL_RGB, GL_UNSIGNED_BYTE, image);
        }
        if (caps.GL_ARB_seamless_cube_map) {
            glEnable(ARBSeamlessCubeMap.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        }
    }

    float rot = 0.0f;
    long lastTime = System.nanoTime();
    void update() {
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
        viewMatrix.setLookAt(0.0f, 5.0f, 10.0f,
                             0.0f, 0.0f, 0.0f,
                             0.0f, 1.0f, 0.0f)
                  .rotateY(rot).rotateX(rot*0.23f).rotateZ(rot*-0.562f)
                  .origin(tmp);
        invViewProjMatrix.set(projMatrix)
                         .mul(viewMatrix)
                         .invert()
                         .get(matrixByteBuffer);

        glUniformMatrix4fv(invViewProjUniform, 1, false, matrixByteBuffer);
        glUniform3f(cameraPositionUniform, tmp.x, tmp.y, tmp.z);

        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        lastTime = thisTime;
        rot += diff * 0.1f;
    }

    void render() {
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    void loop() {
        while (glfwWindowShouldClose(window) == GL_FALSE) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);

            update();
            render();

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
        new FullscreenCubemapDemo().run();
    }

}