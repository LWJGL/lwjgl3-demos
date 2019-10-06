/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.util.WavefrontMeshLoader;
import org.lwjgl.demo.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.ARBVertexBufferObject.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Just like {@link EnvironmentDemo}, but also adds a reflective teapot.
 * 
 * @author Kai Burjack
 */
public class EnvironmentTeapotDemo {

    long window;
    int width = 1024;
    int height = 768;
    int fbWidth = 1024;
    int fbHeight = 768;
    float fov = 60, rotX, rotY;

    int environmentProgram;
    int teapotProgram;

    int invViewProjUniform;
    int viewProjUniform;
    int cameraPositionUniform;

    Matrix4f projectionMatrix = new Matrix4f();
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f viewProjMatrix = new Matrix4f();
    Matrix4f invViewProjMatrix = new Matrix4f();
    Vector3f cameraPosition = new Vector3f();
    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    Mesh mesh;
    int numVertices;
    long normalsOffset;
    int teapotVbo;
    int fullscreenVbo;

    GLCapabilities caps;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWWindowSizeCallback wsCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWScrollCallback sCallback;
    Callback debugProc;

    void init() throws IOException {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Spherical environment mapping with teapot demo", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        System.out.println("Move the mouse to look around");
        System.out.println("Zoom in/out with mouse wheel");
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (EnvironmentTeapotDemo.this.fbWidth != width || EnvironmentTeapotDemo.this.fbHeight != height)) {
                    EnvironmentTeapotDemo.this.fbWidth = width;
                    EnvironmentTeapotDemo.this.fbHeight = height;
                }
            }
        });
        glfwSetWindowSizeCallback(window, wsCallback = new GLFWWindowSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (EnvironmentTeapotDemo.this.width != width || EnvironmentTeapotDemo.this.height != height)) {
                    EnvironmentTeapotDemo.this.width = width;
                    EnvironmentTeapotDemo.this.height = height;
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
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                float nx = (float) x / width * 2.0f - 1.0f;
                float ny = (float) y / height * 2.0f - 1.0f;
                rotX = ny * (float)Math.PI * 0.5f;
                rotY = nx * (float)Math.PI;
            }
        });
        glfwSetScrollCallback(window, sCallback = new GLFWScrollCallback() {
            @Override
            public void invoke(long window, double xoffset, double yoffset) {
                if (yoffset < 0)
                    fov *= 1.05f;
                else
                    fov *= 1f/1.05f;
                if (fov < 10.0f)
                    fov = 10.0f;
                else if (fov > 120.0f)
                    fov = 120.0f;
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        glfwSetCursorPos(window, width / 2, height / 2);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        caps = GL.createCapabilities();
        if (!caps.GL_ARB_shader_objects)
            throw new AssertionError("This demo requires the ARB_shader_objects extension.");
        if (!caps.GL_ARB_vertex_shader)
            throw new AssertionError("This demo requires the ARB_vertex_shader extension.");
        if (!caps.GL_ARB_fragment_shader)
            throw new AssertionError("This demo requires the ARB_fragment_shader extension.");
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        loadMesh();
        createTexture();
        createFullScreenQuad();
        createTeapotProgram();
        createEnvironmentProgram();
    }

    void loadMesh() throws IOException {
        mesh = new WavefrontMeshLoader().loadMesh("org/lwjgl/demo/opengl/models/teapot.obj.zip");
        this.numVertices = mesh.numVertices;
        long bufferSize = 4 * (3 + 3) * mesh.numVertices;
        this.normalsOffset = 4L * 3 * mesh.numVertices;
        this.teapotVbo = glGenBuffersARB();
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, this.teapotVbo);
        glBufferDataARB(GL_ARRAY_BUFFER_ARB, bufferSize, GL_STATIC_DRAW_ARB);
        glBufferSubDataARB(GL_ARRAY_BUFFER_ARB, 0L, mesh.positions);
        glBufferSubDataARB(GL_ARRAY_BUFFER_ARB, normalsOffset, mesh.normals);
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, 0);
    }

    void createFullScreenQuad() {
        ByteBuffer vertices = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = vertices.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put( 1.0f).put(-1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put(-1.0f).put( 1.0f);
        fv.put(-1.0f).put(-1.0f);
        this.fullscreenVbo = glGenBuffersARB();
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, this.fullscreenVbo);
        glBufferDataARB(GL_ARRAY_BUFFER_ARB, vertices, GL_STATIC_DRAW_ARB);
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, 0);
    }

    static int createShader(String resource, int type) throws IOException {
        int shader = glCreateShaderObjectARB(type);
        ByteBuffer source = ioResourceToByteBuffer(resource, 1024);
        PointerBuffer strings = BufferUtils.createPointerBuffer(1);
        IntBuffer lengths = BufferUtils.createIntBuffer(1);
        strings.put(0, source);
        lengths.put(0, source.remaining());
        glShaderSourceARB(shader, strings, lengths);
        glCompileShaderARB(shader);
        int compiled = glGetObjectParameteriARB(shader, GL_OBJECT_COMPILE_STATUS_ARB);
        String shaderLog = glGetInfoLogARB(shader);
        if (shaderLog.trim().length() > 0) {
            System.err.println(shaderLog);
        }
        if (compiled == 0) {
            throw new AssertionError("Could not compile shader");
        }
        return shader;
    }

    void createEnvironmentProgram() throws IOException {
        int program = glCreateProgramObjectARB();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/environment.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/environment.fs", GL_FRAGMENT_SHADER_ARB);
        glAttachObjectARB(program, vshader);
        glAttachObjectARB(program, fshader);
        glLinkProgramARB(program);
        int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
        String programLog = glGetInfoLogARB(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        glUseProgramObjectARB(program);
        int texLocation = glGetUniformLocationARB(program, "tex");
        glUniform1iARB(texLocation, 0);
        invViewProjUniform = glGetUniformLocationARB(program, "invViewProj");
        this.environmentProgram = program;
    }

    void createTeapotProgram() throws IOException {
        int program = glCreateProgramObjectARB();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/teapot.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/teapot.fs", GL_FRAGMENT_SHADER_ARB);
        glAttachObjectARB(program, vshader);
        glAttachObjectARB(program, fshader);
        glLinkProgramARB(program);
        int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
        String programLog = glGetInfoLogARB(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        glUseProgramObjectARB(program);
        viewProjUniform = glGetUniformLocationARB(program, "viewProj");
        cameraPositionUniform = glGetUniformLocationARB(program, "cameraPosition");
        this.teapotProgram = program;
    }

    static void createTexture() throws IOException {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        ByteBuffer imageBuffer;
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer comp = BufferUtils.createIntBuffer(1);
        ByteBuffer image;
        imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/opengl/textures/environment.jpg", 8 * 1024);
        if (!stbi_info_from_memory(imageBuffer, w, h, comp))
            throw new IOException("Failed to read image information: " + stbi_failure_reason());
        image = stbi_load_from_memory(imageBuffer, w, h, comp, 3);
        if (image == null)
            throw new IOException("Failed to load image: " + stbi_failure_reason());
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w.get(0), h.get(0), 0, GL_RGB, GL_UNSIGNED_BYTE, image);
        stbi_image_free(image);
    }

    void update() {
        projectionMatrix.setPerspective((float) Math.toRadians(fov), (float) width / height, 0.01f, 100.0f);
        viewMatrix.translation(0, 0, -10.0f)
                  .rotateX(rotX)
                  .rotateY(rotY);
        viewMatrix.originAffine(cameraPosition);
        projectionMatrix.mulPerspectiveAffine(viewMatrix, viewProjMatrix).invert(invViewProjMatrix);
        glUseProgramObjectARB(environmentProgram);
        glUniformMatrix4fvARB(invViewProjUniform, false, invViewProjMatrix.get(matrixBuffer));
        glUseProgramObjectARB(teapotProgram);
        glUniformMatrix4fvARB(viewProjUniform, false, viewProjMatrix.get(matrixBuffer));
        glUniform3fARB(cameraPositionUniform, cameraPosition.x, cameraPosition.y, cameraPosition.z);
    }

    void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        /* Render environment map */
        glDisable(GL_DEPTH_TEST);
        glUseProgramObjectARB(environmentProgram);
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, this.fullscreenVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 0, 0L);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisableClientState(GL_VERTEX_ARRAY);

        /* Render teapot */
        glEnable(GL_DEPTH_TEST);
        glUseProgramObjectARB(teapotProgram);
        glBindBufferARB(GL_ARRAY_BUFFER_ARB, this.teapotProgram);
        glEnableClientState(GL_NORMAL_ARRAY);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, GL_FLOAT, 3 * 4, 0L);
        glNormalPointer(GL_FLOAT, 3 * 4, normalsOffset);
        glDrawArrays(GL_TRIANGLES, 0, numVertices);
        glDisableClientState(GL_VERTEX_ARRAY);
        glDisableClientState(GL_NORMAL_ARRAY);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, fbWidth, fbHeight);
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
                debugProc.free();
            }
            cpCallback.free();
            keyCallback.free();
            fbCallback.free();
            wsCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new EnvironmentTeapotDemo().run();
    }

}