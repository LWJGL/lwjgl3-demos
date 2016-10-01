/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.shader;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.Example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;

/**
 * Renders a cubic B-spline without using any vertex source but fully computing the vertex positions in the vertex shader.
 * <p>
 * This demo implements cubic B-spline evaluation in the vertex shader and stores the control points in a Uniform Buffer Object.
 * 
 * @author Kai Burjack
 */
public class NoVerticesBSplineDemo extends Example {

    private static final int POINT_COUNT = 50;

    private static final int LOD = 10;

    private int program;
    private int transformUniform;
    private int lodUniform;
    private int numPointsUniform;

    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private Matrix4f transform = new Matrix4f();

    private float angle = 0.0f;
    private long lastTime = System.nanoTime();

    @Override
    public void init() throws IOException {
        super.init();

        // Create all needed GL resources
        createProgram();
        createUbo();
        // and set some GL state
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
    }

    @Override
    public void render() {
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(this.program);

        // Compute rotation angle
        long thisTime = System.nanoTime();
        float delta = (thisTime - lastTime) / 1E9f;
        angle += delta;
        lastTime = thisTime;

        // Build some transformation matrix
        transform.setPerspective((float) Math.toRadians(45.0f), (float)width/height, 0.1f, 100.0f)
                .lookAt(0, 0, 6,
                        0, 2, 0,
                        0, 1, 0)
                .rotateY(angle * (float) Math.toRadians(180)); // 180 degrees per second
        // and upload it to the shader
        glUniformMatrix4fv(transformUniform, false, transform.get(matrixBuffer));

        glUniform1i(lodUniform, LOD);
        glUniform1i(numPointsUniform, POINT_COUNT);

        glDrawArrays(GL_LINE_STRIP, 0, LOD * (POINT_COUNT +1) + 1);

        glUseProgram(0);
    }

    private void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/noverticesbspline.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/noverticesbspline.fs", GL_FRAGMENT_SHADER);
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
        transformUniform = glGetUniformLocation(program, "transform");
        lodUniform = glGetUniformLocation(program, "lod");
        numPointsUniform = glGetUniformLocation(program, "numPoints");
        glUseProgram(0);
    }

    private static void createUbo() {
        int ubo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, ubo);
        int pointsPerCircle = 5;
        ByteBuffer bb = BufferUtils.createByteBuffer(POINT_COUNT * 4 * 4);
        FloatBuffer fb = bb.asFloatBuffer();
        for (int i = 0; i < POINT_COUNT; i++) {
            float scale = 1.0f - (float)i/ POINT_COUNT;
            float t = (float)i / pointsPerCircle;
            float ang = 2.0f * (float)Math.PI * t;
            float x = (float) Math.cos(ang) * scale;
            float y = i / 10.0f;
            float z = (float) Math.sin(ang) * scale;
            fb.put(x).put(y).put(z).put(1.0f);
        }
        glBufferData(GL_UNIFORM_BUFFER, bb, GL_STATIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
    }

    public static void main(String[] args) {
        new NoVerticesBSplineDemo().run();
    }
}