/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.geometry;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.EXTGeometryShader4.*;
import static org.lwjgl.system.MemoryUtil.*;

public class GeometryShaderTest20 {

    long window;
    int width = 1024;
    int height = 768;

    int vao;
    int program;

    int viewMatrixUniform;
    int projMatrixUniform;
    int viewportSizeUniform;

    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
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

        window = glfwCreateWindow(width, height, "Antialiased wireframe rendering with geometry shader", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (GeometryShaderTest20.this.width != width || GeometryShaderTest20.this.height != height)) {
                    GeometryShaderTest20.this.width = width;
                    GeometryShaderTest20.this.height = height;
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
        if (!caps.GL_EXT_geometry_shader4) {
            throw new AssertionError("This demo requires the EXT_geometry_shader4 extension");
        }

        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(0.55f, 0.75f, 0.95f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        /* Create all needed GL resources */
        createVao();
        createRasterProgram();
        initProgram();
    }

    static void quadPattern(IntBuffer vb) {
        vb.put(1).put(0).put(1).put(1).put(0).put(1);
    }

    static void quadWithDiagonalPattern(IntBuffer vb) {
        vb.put(1).put(1).put(1).put(1).put(1).put(1);
    }

    static void createVao() {
        IntBuffer vb = BufferUtils.createIntBuffer(6 * 6);
        FloatBuffer pb = BufferUtils.createFloatBuffer(3 * 6 * 6);
        quadPattern(vb);
        pb.put(0.5f).put(0.5f).put(-0.5f);
        pb.put(0.5f).put(-0.5f).put(-0.5f);
        pb.put(-0.5f).put(-0.5f).put(-0.5f);
        pb.put(-0.5f).put(-0.5f).put(-0.5f);
        pb.put(-0.5f).put(0.5f).put(-0.5f);
        pb.put(0.5f).put(0.5f).put(-0.5f);
        quadWithDiagonalPattern(vb);
        pb.put(0.5f).put(-0.5f).put(0.5f);
        pb.put(0.5f).put(0.5f).put(0.5f);
        pb.put(-0.5f).put(0.5f).put(0.5f);
        pb.put(-0.5f).put(0.5f).put(0.5f);
        pb.put(-0.5f).put(-0.5f).put(0.5f);
        pb.put(0.5f).put(-0.5f).put(0.5f);
        quadPattern(vb);
        pb.put(0.5f).put(-0.5f).put(-0.5f);
        pb.put(0.5f).put(0.5f).put(-0.5f);
        pb.put(0.5f).put(0.5f).put(0.5f);
        pb.put(0.5f).put(0.5f).put(0.5f);
        pb.put(0.5f).put(-0.5f).put(0.5f);
        pb.put(0.5f).put(-0.5f).put(-0.5f);
        quadWithDiagonalPattern(vb);
        pb.put(-0.5f).put(-0.5f).put(0.5f);
        pb.put(-0.5f).put(0.5f).put(0.5f);
        pb.put(-0.5f).put(0.5f).put(-0.5f);
        pb.put(-0.5f).put(0.5f).put(-0.5f);
        pb.put(-0.5f).put(-0.5f).put(-0.5f);
        pb.put(-0.5f).put(-0.5f).put(0.5f);
        quadPattern(vb);
        pb.put(0.5f).put(0.5f).put(0.5f);
        pb.put(0.5f).put(0.5f).put(-0.5f);
        pb.put(-0.5f).put(0.5f).put(-0.5f);
        pb.put(-0.5f).put(0.5f).put(-0.5f);
        pb.put(-0.5f).put(0.5f).put(0.5f);
        pb.put(0.5f).put(0.5f).put(0.5f);
        quadWithDiagonalPattern(vb);
        pb.put(0.5f).put(-0.5f).put(-0.5f);
        pb.put(0.5f).put(-0.5f).put(0.5f);
        pb.put(-0.5f).put(-0.5f).put(0.5f);
        pb.put(-0.5f).put(-0.5f).put(0.5f);
        pb.put(-0.5f).put(-0.5f).put(-0.5f);
        pb.put(0.5f).put(-0.5f).put(-0.5f);
        pb.flip();
        vb.flip();
        // setup vertex positions buffer
        int posVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, posVbo);
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        // setup vertex visibility buffer
        int visVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, visVbo);
        glBufferData(GL_ARRAY_BUFFER, vb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 1, GL_UNSIGNED_INT, false, 0, 0L);
    }

    void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/geometry/vs110.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/geometry/fs110.glsl", GL_FRAGMENT_SHADER);
        int gshader = createShader("org/lwjgl/demo/opengl/geometry/gs110.glsl", GL_GEOMETRY_SHADER_EXT);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, gshader);
        glBindAttribLocation(program, 0, "position");
        glBindAttribLocation(program, 1, "visible");
        glProgramParameteriEXT(program, GL_GEOMETRY_VERTICES_OUT_EXT, 3);
        glProgramParameteriEXT(program, GL_GEOMETRY_INPUT_TYPE_EXT, GL_TRIANGLES);
        glProgramParameteriEXT(program, GL_GEOMETRY_OUTPUT_TYPE_EXT, GL_TRIANGLE_STRIP);
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
    void initProgram() {
        glUseProgram(this.program);
        viewMatrixUniform = glGetUniformLocation(this.program, "viewMatrix");
        projMatrixUniform = glGetUniformLocation(this.program, "projMatrix");
        viewportSizeUniform = glGetUniformLocation(this.program, "viewportSize");
        glUseProgram(0);
    }

    float angle = 0.0f;
    long lastTime = System.nanoTime();

    void update() {
        projMatrix.setPerspective((float) Math.toRadians(30), (float) width / height, 0.01f, 50.0f);
        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        angle += diff;
        viewMatrix.setLookAt(0.0f, 2.0f, 5.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f).rotateY(angle);
        lastTime = thisTime;
    }

    void render() {
        glUseProgram(this.program);

        glUniformMatrix4fv(viewMatrixUniform, false, viewMatrix.get(matrixBuffer));
        glUniformMatrix4fv(projMatrixUniform, false, projMatrix.get(matrixBuffer));
        glUniform2f(viewportSizeUniform, width, height);

        glDrawArrays(GL_TRIANGLES, 0, 3 * 2 * 6);

        glUseProgram(0);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

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
        new GeometryShaderTest20().run();
    }

}