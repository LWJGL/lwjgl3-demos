/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.geometry;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.EXTGeometryShader4.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;

/**
 * Demo how to render the silhouette/outline of a mesh using the geometry shader.
 * <p>
 * First, the triangles with adjacency information (GL_TRIANGLES_ADJACENCY) are calculated based on a normal
 * GL_TRIANGLES mesh. Using this, the geometry shader is invoked which checks whether each one of the three edges is a
 * silhouette edge by determining the dot product between the front face and the back/adjacent face.
 * 
 * @author Kai Burjack
 */
public class SilhouetteDemo {

    long window;
    int width = 1024;
    int height = 768;

    int vao;
    int program;

    int viewProjMatrixUniform;

    Matrix4f viewProjMatrix = new Matrix4f();
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

        window = glfwCreateWindow(width, height, "Silhouette rendering with geometry shader", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (SilhouetteDemo.this.width != width || SilhouetteDemo.this.height != height)) {
                    SilhouetteDemo.this.width = width;
                    SilhouetteDemo.this.height = height;
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

        /* Create all needed GL resources */
        createBoxVao();
        createRasterProgram();
        initProgram();
    }

    static void createBoxVao() {
        FloatBuffer pb = BufferUtils.createFloatBuffer(3 * 8);
        pb.put(-1).put(-1).put(-1); // 0
        pb.put( 1).put(-1).put(-1); // 1
        pb.put(-1).put( 1).put(-1); // 2
        pb.put(-1).put(-1).put( 1); // 3
        pb.put( 1).put( 1).put(-1); // 4
        pb.put(-1).put( 1).put( 1); // 5
        pb.put( 1).put(-1).put( 1); // 6
        pb.put( 1).put( 1).put( 1); // 7
        pb.flip();
        IntBuffer ib = BufferUtils.createIntBuffer(3 * 2 * 6);
        ib.put(0).put(3).put(5).put(5).put(2).put(0); // -X
        ib.put(6).put(1).put(4).put(4).put(7).put(6); // +X
        ib.put(3).put(0).put(1).put(1).put(6).put(3); // -Y
        ib.put(5).put(7).put(4).put(4).put(2).put(5); // +Y
        ib.put(1).put(0).put(2).put(2).put(4).put(1); // -Z
        ib.put(3).put(6).put(7).put(7).put(5).put(3); // +Z
        ib.flip();
        // Create GL_TRIANGLES_ADJACENCY index buffer
        IntBuffer adj = BufferUtils.createIntBuffer(ib.remaining() * 2);
        Adjacency.computeAdjacency(ib, adj);
        // setup vertex positions buffer
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        // setup index buffer with adjacency indices
        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, adj, GL_STATIC_DRAW);
    }

    void createRasterProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/geometry/silhouette-vs.glsl", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/geometry/silhouette-fs.glsl", GL_FRAGMENT_SHADER);
        int gshader = createShader("org/lwjgl/demo/opengl/geometry/silhouette-gs.glsl", GL_GEOMETRY_SHADER_EXT);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glAttachShader(program, gshader);
        glBindAttribLocation(program, 0, "position");
        glProgramParameteriEXT(program, GL_GEOMETRY_VERTICES_OUT_EXT, 6);
        glProgramParameteriEXT(program, GL_GEOMETRY_INPUT_TYPE_EXT, GL_TRIANGLES_ADJACENCY_EXT);
        glProgramParameteriEXT(program, GL_GEOMETRY_OUTPUT_TYPE_EXT, GL_LINE_STRIP);
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
        viewProjMatrixUniform = glGetUniformLocation(this.program, "viewProjMatrix");
        glUseProgram(0);
    }

    float angle = 0.0f;
    long lastTime = System.nanoTime();

    void update() {
        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        lastTime = thisTime;
        angle += diff;

        viewProjMatrix
            .setPerspective((float) Math.toRadians(30), (float) width / height, 0.01f, 50.0f)
            .lookAt(0.0f, 2.0f, 7.0f,
                    0.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f)
            .rotateY(angle);
    }

    void render() {
        glUseProgram(this.program);
        glUniformMatrix4fv(viewProjMatrixUniform, false, viewProjMatrix.get(matrixBuffer));
        glDrawElements(GL_TRIANGLES_ADJACENCY_EXT, 6 * 2 * 6, GL_UNSIGNED_INT, 0L);
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

            if (debugProc != null)
                debugProc.free();
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
        new SilhouetteDemo().run();
    }

}
