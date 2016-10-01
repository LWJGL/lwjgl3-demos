/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.shader;

import org.joml.Matrix3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.Example;

import java.io.IOException;
import java.nio.FloatBuffer;

import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL11.glVertex2f;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glUniformMatrix3fv;
import static org.lwjgl.opengl.GL20.glUseProgram;

/**
 * Shows how to use immediate mode with a simple shader.
 * 
 * @author Kai Burjack
 */
public class ImmediateModeDemo extends Example {

    private int program;
    private int transformUniform;

    private FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(9);
    private Matrix3f transform = new Matrix3f();

    private float angle = 0.0f;
    private long lastTime = System.nanoTime();


    @Override
    protected void init() throws IOException {
        super.init();

        // Create all needed GL resources
        createProgram();
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
        float invAspect = (float) height / width;
        transform.scaling(invAspect, 1, 1) // correct the aspect ratio with some scaling
                 .rotateZ(angle * (float) Math.toRadians(45)) // rotate 45 degrees per second
                 .scale(0.5f); // make everything a bit smaller
        // and upload it to the shader
        glUniformMatrix3fv(transformUniform, false, transform.get(matrixBuffer));

        // draw some quad
        glBegin(GL_QUADS);
        glVertex2f(-1, -1);
        glVertex2f(+1, -1);
        glVertex2f(+1, +1);
        glVertex2f(-1, +1);
        glEnd();

        glUseProgram(0);
    }

    private void createProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/simple.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/simple.fs", GL_FRAGMENT_SHADER);
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
        glUseProgram(0);
    }

    public static void main(String[] args) {
        new ImmediateModeDemo().run();
    }

}