/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.instancing;

import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.SimplexNoise;
import org.joml.Vector2f;
import org.joml.sampling.BestCandidateSampling;
import org.joml.sampling.Callback2d;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;

/**
 * Uses hardware instancing to render grass patches.
 * <p>
 * This demo uses per-instance attributes to position, rotate and animate the grass blades.
 * 
 * @author Kai Burjack
 */
public class GrassDemo {

    private static final int NUM_FACES_PER_PATCH = 3;
    private static final int NUM_GRASS_PATCHES = 130000;
    private static final float MEADOW_SIZE = 100;
    private static final float NOISE_SPATIAL_FACTOR_X = 0.04f;
    private static final float NOISE_SPATIAL_FACTOR_Y = 0.02f;
    private static final float NOISE_TIME_FACTOR = 0.2f;
    private static final float DISPLACEMENT_FACTOR = 0.6f;

    private long window;
    private int width = 1920;
    private int height = 1080;

    private int grassVao;
    private int grassDisplacementVbo;
    private int groundVao;

    private int grassProgram;
    private int grassPositionAttribute;
    private int grassTexCoordAttribute;
    private int grassDisplacementAttribute;
    private int grassWorldPositionAttribute;
    private int grassRotationAttribute;
    private int grassVpMatrixUniform;
    private int grassTex0;
    private int grassTex1;
    private int grassTex2;
    private int grassTex3;

    private int groundProgram;
    private int groundPositionAttribute;
    private int groundVpMatrixUniform;
    private int groundSizeUniform;

    private float time;

    private Matrix4f vpMatrix = new Matrix4f();
    private FloatBuffer mat4Buffer = BufferUtils.createFloatBuffer(4 * 4);

    private GLFWErrorCallback errCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private GLFWKeyCallback keyCallback;
    private Callback debugProc;
    private Vector2f[] grassPatchPositions = new Vector2f[NUM_GRASS_PATCHES];
    private final FloatBuffer grassDisplacement = BufferUtils.createFloatBuffer(NUM_GRASS_PATCHES * 2);
    private long lastTime = System.nanoTime();

    void run() throws IOException {
        glfwSetErrorCallback(errCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Hello instanced grass", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                GrassDemo.this.width = width;
                GrassDemo.this.height = height;
            }
        });
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;
                if (key == GLFW_KEY_ESCAPE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        GLCapabilities caps = createCapabilities();
        if (!caps.GL_ARB_vertex_array_object)
            throw new UnsupportedOperationException("ARB_vertex_array_object is not available");
        if (!caps.GL_ARB_draw_instanced)
            throw new UnsupportedOperationException("ARB_draw_instanced is not available");
        if (!caps.GL_ARB_instanced_arrays)
            throw new UnsupportedOperationException("ARB_instanced_arrays is not available");
        debugProc = GLUtil.setupDebugMessageCallback();
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        createGrassTextures();
        createGrassProgram();
        generateGrassPatchVao();
        createGroundProgram();
        generateGroundVao();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            update();
            render();
            glfwSwapBuffers(window);
        }
        glfwDestroyWindow(window);
        glfwTerminate();
        errCallback.free();
        if (debugProc != null)
            debugProc.free();
        fbCallback.free();
        keyCallback.free();
    }

    private void update() {
        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        lastTime = thisTime;
        time += diff;
        vpMatrix.setPerspective((float) Math.toRadians(30), (float) width / height, 0.1f, 300.0f).lookAt(4, 7, 110, 0, 0, 80, 0, 1, 0).rotateY(time*0.01f);
        /* Update grass displacement using simplex noise */
        grassDisplacement.clear();
        for (int i = 0; i < NUM_GRASS_PATCHES; i++) {
            Vector2f g = grassPatchPositions[i];
            grassDisplacement.put(
                    DISPLACEMENT_FACTOR * SimplexNoise.noise(g.x * NOISE_SPATIAL_FACTOR_X + time * NOISE_TIME_FACTOR, g.y * NOISE_SPATIAL_FACTOR_Y + time * NOISE_TIME_FACTOR));
            grassDisplacement.put(
                    DISPLACEMENT_FACTOR * SimplexNoise.noise(g.y * NOISE_SPATIAL_FACTOR_Y + time * NOISE_TIME_FACTOR, g.x * NOISE_SPATIAL_FACTOR_X + time * NOISE_TIME_FACTOR));
        }
        grassDisplacement.flip();
        glBindBuffer(GL_ARRAY_BUFFER, grassDisplacementVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, grassDisplacement);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        // Render ground
        glUseProgram(groundProgram);
        glUniformMatrix4fv(groundVpMatrixUniform, false, vpMatrix.get(mat4Buffer));
        ARBVertexArrayObject.glBindVertexArray(groundVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        ARBVertexArrayObject.glBindVertexArray(0);
        // Render grass patches
        glUseProgram(grassProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, grassTex0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, grassTex1);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, grassTex2);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, grassTex3);
        glUniformMatrix4fv(grassVpMatrixUniform, false, vpMatrix.get(mat4Buffer));
        ARBVertexArrayObject.glBindVertexArray(grassVao);
        ARBDrawInstanced.glDrawArraysInstancedARB(GL_TRIANGLES, 0, 6 * NUM_FACES_PER_PATCH, NUM_GRASS_PATCHES);
        ARBVertexArrayObject.glBindVertexArray(0);
        glUseProgram(0);
        glActiveTexture(GL_TEXTURE0);
    }

    private void createGrassProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/instancing/grass.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/instancing/grass.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.grassProgram = program;
        glUseProgram(program);
        grassVpMatrixUniform = glGetUniformLocation(program, "vpMatrix");
        grassPositionAttribute = glGetAttribLocation(program, "position");
        grassTexCoordAttribute = glGetAttribLocation(program, "texCoord");
        grassDisplacementAttribute = glGetAttribLocation(program, "displacement");
        grassWorldPositionAttribute = glGetAttribLocation(program, "worldPosition");
        grassRotationAttribute = glGetAttribLocation(program, "rotation");
        int tex0Uniform = glGetUniformLocation(program, "tex0");
        glUniform1i(tex0Uniform, 0);
        int tex1Uniform = glGetUniformLocation(program, "tex1");
        glUniform1i(tex1Uniform, 1);
        int tex2Uniform = glGetUniformLocation(program, "tex2");
        glUniform1i(tex2Uniform, 2);
        int tex3Uniform = glGetUniformLocation(program, "tex3");
        glUniform1i(tex3Uniform, 3);
        glUseProgram(0);
    }

    private void createGroundProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/instancing/ground.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/instancing/ground.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0)
            System.err.println(programLog);
        if (linked == 0)
            throw new AssertionError("Could not link program");
        this.groundProgram = program;
        glUseProgram(program);
        groundVpMatrixUniform = glGetUniformLocation(program, "vpMatrix");
        groundPositionAttribute = glGetAttribLocation(program, "position");
        groundSizeUniform = glGetUniformLocation(program, "groundSize");
        glUniform1f(groundSizeUniform, MEADOW_SIZE);
        glUseProgram(0);
    }

    private static int loadTexture(String name) throws IOException {
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        IntBuffer components = BufferUtils.createIntBuffer(1);
        ByteBuffer data = stbi_load_from_memory(ioResourceToByteBuffer("org/lwjgl/demo/opengl/instancing/" + name, 1024), width, height, components, 4);
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_GENERATE_MIPMAP, GL_TRUE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        glBindTexture(GL_TEXTURE_2D, 0);
        stbi_image_free(data);
        return id;
    }

    private void createGrassTextures() throws IOException {
        grassTex0 = loadTexture("grass.png");
        grassTex1 = loadTexture("grass2.png");
        grassTex2 = loadTexture("grass_flower.png");
        grassTex3 = loadTexture("grass_flower_blue.png");
    }

    private void generateGroundVao() {
        FloatBuffer fb = BufferUtils.createFloatBuffer(6 * 2);
        fb.put(0).put(0);
        fb.put(1).put(0);
        fb.put(1).put(1);
        fb.put(1).put(1);
        fb.put(0).put(1);
        fb.put(0).put(0);
        fb.flip();
        groundVao = ARBVertexArrayObject.glGenVertexArrays();
        ARBVertexArrayObject.glBindVertexArray(groundVao);
        int modelBuffer = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, modelBuffer);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(groundPositionAttribute, 2, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(groundPositionAttribute);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        ARBVertexArrayObject.glBindVertexArray(0);
    }

    private void generateGrassPatchVao() {
        int n = NUM_FACES_PER_PATCH;
        // Generate the model attributes of a single grass patch
        FloatBuffer fb = BufferUtils.createFloatBuffer(NUM_FACES_PER_PATCH * 6 * (3 + 2));
        for (int i = 0; i < n; i++) {
            float x0 = (float) (Math.cos((double) i / n * Math.PI))*0.5f;
            float z0 = (float) (Math.sin((double) i / n * Math.PI))*0.5f;
            float x1 = (float) (Math.cos((double) i / n * Math.PI + Math.PI))*0.5f;
            float z1 = (float) (Math.sin((double) i / n * Math.PI + Math.PI))*0.5f;
            fb.put(x0).put(0.0f).put(z0).put(0).put(1);
            fb.put(x1).put(0.0f).put(z1).put(1).put(1);
            fb.put(x1).put(1.0f).put(z1).put(1).put(0);
            fb.put(x1).put(1.0f).put(z1).put(1).put(0);
            fb.put(x0).put(1.0f).put(z0).put(0).put(0);
            fb.put(x0).put(0.0f).put(z0).put(0).put(1);
        }
        fb.flip();
        // Generate the world-space positions of each patch
        final FloatBuffer pb = BufferUtils.createFloatBuffer(NUM_GRASS_PATCHES * 4);
        new BestCandidateSampling.Quad().numSamples(NUM_GRASS_PATCHES).numCandidates(20).generate(new Callback2d() {
            final Random rnd = new Random(0L);
            int index = 0;
            public void onNewSample(float x, float y) {
                float px = x * MEADOW_SIZE;
                float py = y * MEADOW_SIZE;
                grassPatchPositions[index++] = new Vector2f(px, py);
                pb.put(x * MEADOW_SIZE).put(y * MEADOW_SIZE).put(rnd.nextFloat() * 0.2f + 0.9f).put((SimplexNoise.noise(x*2.342f, y*2.0352f) + 1.0f)*0.5f);
            }
        });
        pb.flip();
        // Generate the random rotations for each grass patch
        FloatBuffer rb = BufferUtils.createFloatBuffer(NUM_GRASS_PATCHES * 4);
        Random rnd = new Random();
        Matrix3x2f m = new Matrix3x2f();
        for (int i = 0; i < NUM_GRASS_PATCHES; i++) {
            float angle = 2.0f * (float) Math.PI * rnd.nextFloat();
            m.rotation(angle);
            rb.put(m.m00).put(m.m01).put(m.m10).put(m.m11);
        }
        rb.flip();
        grassVao = ARBVertexArrayObject.glGenVertexArrays();
        ARBVertexArrayObject.glBindVertexArray(grassVao);
        int modelBuffer = glGenBuffers();
        int grassBladesPositionsVbo = glGenBuffers();
        grassDisplacementVbo = glGenBuffers();
        int grassRotationVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, modelBuffer);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(grassPositionAttribute, 3, GL_FLOAT, false, 4 * (3 + 2), 0L);
        glVertexAttribPointer(grassTexCoordAttribute, 2, GL_FLOAT, false, 4 * (3 + 2), 4 * 3L);
        glEnableVertexAttribArray(grassPositionAttribute);
        glEnableVertexAttribArray(grassTexCoordAttribute);
        glBindBuffer(GL_ARRAY_BUFFER, grassBladesPositionsVbo);
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
        glVertexAttribPointer(grassWorldPositionAttribute, 4, GL_FLOAT, false, 0, 0L);
        ARBInstancedArrays.glVertexAttribDivisorARB(grassWorldPositionAttribute, 1);
        glEnableVertexAttribArray(grassWorldPositionAttribute);
        glBindBuffer(GL_ARRAY_BUFFER, grassDisplacementVbo);
        glBufferData(GL_ARRAY_BUFFER, 4 * 2 * NUM_GRASS_PATCHES, GL_STATIC_DRAW);
        glVertexAttribPointer(grassDisplacementAttribute, 2, GL_FLOAT, false, 0, 0L);
        ARBInstancedArrays.glVertexAttribDivisorARB(grassDisplacementAttribute, 1);
        glEnableVertexAttribArray(grassDisplacementAttribute);
        glBindBuffer(GL_ARRAY_BUFFER, grassRotationVbo);
        glBufferData(GL_ARRAY_BUFFER, rb, GL_STATIC_DRAW);
        glVertexAttribPointer(grassRotationAttribute, 4, GL_FLOAT, false, 0, 0L);
        ARBInstancedArrays.glVertexAttribDivisorARB(grassRotationAttribute, 1);
        glEnableVertexAttribArray(grassRotationAttribute);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        ARBVertexArrayObject.glBindVertexArray(0);
    }

    public static void main(String[] args) throws IOException {
        new GrassDemo().run();
    }

}
