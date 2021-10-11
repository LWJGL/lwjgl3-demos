/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static org.joml.Math.*;
import static org.joml.SimplexNoise.noise;
import static org.lwjgl.demo.opengl.util.DemoUtils.createShader;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.par.ParShapes.*;

import java.io.IOException;
import java.nio.*;

import org.joml.*;
import org.joml.Math;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import org.lwjgl.util.par.*;

/**
 * Simple planet with clouds.
 * 
 * @author Kai Burjack
 */
public class Planet {
    private static int width = 800;
    private static int height = 600;

    public static void main(String[] args) throws IOException {
        if (!glfwInit())
            throw new AssertionError("Unable to initialize GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        long window = glfwCreateWindow(width, height, "Hello, Planet!", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        GLFWKeyCallback keyCallback;
        GLFWFramebufferSizeCallback fbCallback;
        Callback debugProc;

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (Planet.width != width || Planet.height != height)) {
                    Planet.width = width;
                    Planet.height = height;
                }
            }
        });

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

        try (MemoryStack frame = stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
        GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        // generate planet/cloud mesh
        ParShapesMesh mesh = par_shapes_create_subdivided_sphere(4);
        int vao = glGenVertexArrays();
        int numIndices = mesh.ntriangles() * 3;
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        int ebo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, mesh.points(mesh.npoints() * 3), GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * 4, 0L);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.triangles(numIndices), GL_STATIC_DRAW);
        par_shapes_free_mesh(mesh);

        // set global GL state
        glClearColor(0.01f, 0.03f, 0.05f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // create shader
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/planet.vs", GL_VERTEX_SHADER);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/planet.fs", GL_FRAGMENT_SHADER);
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
        glUseProgram(program);
        int transformUniform = glGetUniformLocation(program, "transform");
        int transformDirUniform = glGetUniformLocation(program, "transformDir");
        int viewProjUniform = glGetUniformLocation(program, "viewProj");
        int cloudsUniform = glGetUniformLocation(program, "clouds");

        // create noise texture
        int texW = 256, texH = 256;
        FloatBuffer fb = MemoryUtil.memAllocFloat(texW * texH);
        float R = 4.0f;
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                float lon = (float) y / texH, lat = (float) x / texW;
                float cx = cos(lat) * cos(lon) * R;
                float cy = cos(lat) * sin(lon) * R;
                float cz = sin(lat) * R;
                fb.put(abs(noise(cx, cy, cz)) +
                       0.3f * abs(noise(cx * 2.86f, cy * 2.86f, cz * 2.86f)));
            }
        }
        fb.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, texW, texH, 0, GL_RED, GL_FLOAT, fb);

        Matrix4f viewProj = new Matrix4f();
        Matrix4x3f planetTransform = new Matrix4x3f();
        Matrix4x3f cloudTransform = new Matrix4x3f();
        Matrix4x3f cloudDirTransform = new Matrix4x3f();
        float angle = 0.0f;
        long lastTime = System.nanoTime();

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            long thisTime = System.nanoTime();
            float delta = (thisTime - lastTime) / 1E9f;
            angle += delta;
            lastTime = thisTime;

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            viewProj
              .setPerspective(
                    (float) Math.toRadians(43.0f),
                    (float) width / height, 0.1f, 100.0f)
              .lookAt(0, 0.5f, 3,
                      0, 0, 0,
                      0, 1, 0);
            planetTransform.rotationY(angle * (float) Math.toRadians(10));
            cloudTransform
              .rotationY(angle * 2.0f * (float) Math.toRadians(10));
            planetTransform.invert(cloudDirTransform).mul(cloudTransform, cloudDirTransform);

            try (MemoryStack stack = stackPush()) {
                glUniformMatrix4fv(viewProjUniform, false, viewProj.get(stack.mallocFloat(16)));
            }

            // Render planet
            glEnable(GL_CULL_FACE);
            glUniform1i(cloudsUniform, 0);
            try (MemoryStack stack = stackPush()) {
                glUniformMatrix4fv(transformUniform, false, planetTransform.get4x4(stack.mallocFloat(16)));
            }
            glDrawElements(GL_TRIANGLES, numIndices, GL_UNSIGNED_INT, 0L);

            // Render clouds
            glDisable(GL_CULL_FACE);
            glUniform1i(cloudsUniform, 1);
            try (MemoryStack stack = stackPush()) {
                glUniformMatrix4fv(transformUniform, false, cloudTransform.get4x4(stack.mallocFloat(16)));
                glUniformMatrix4fv(transformDirUniform, false, cloudDirTransform.get4x4(stack.mallocFloat(16)));
            }
            glDrawElements(GL_TRIANGLES, numIndices, GL_UNSIGNED_INT, 0L);
            glfwSwapBuffers(window);
        }
        if (debugProc != null) {
            debugProc.free();
        }
        keyCallback.free();
        fbCallback.free();
        glfwDestroyWindow(window);
    }

}
