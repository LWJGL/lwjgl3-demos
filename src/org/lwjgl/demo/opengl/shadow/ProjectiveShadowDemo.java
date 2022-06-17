/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shadow;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * @author Kai Burjack
 */
public class ProjectiveShadowDemo {
    GLFWErrorCallback errorCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;

    long window;
    int width = 300;
    int height = 300;

    // FloatBuffer for transferring matrices to OpenGL
    FloatBuffer fb = BufferUtils.createFloatBuffer(16);

    void run() {
        try {
            init();
            loop();

            glfwDestroyWindow(window);
            keyCallback.free();
        } finally {
            glfwTerminate();
            glfwSetErrorCallback(null).free();
        }
    }

    void init() {
        glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure our window
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4);

        window = glfwCreateWindow(width, height, "Hello projective shadows!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);
    }

    void renderPlane() {
        glBegin(GL_QUADS);
        glColor3f(0.5f, 0.6f, 0.7f);
        glVertex3f(-1.0f, 0.0f, 1.0f);
        glVertex3f(1.0f, 0.0f, 1.0f);
        glVertex3f(1.0f, 0.0f, -1.0f);
        glVertex3f(-1.0f, 0.0f, -1.0f);
        glEnd();
    }

    void renderLight() {
        glPointSize(10.0f);
        glBegin(GL_POINTS);
        glColor3f(1.0f, 1.0f, 0.0f);
        glVertex3f(0.0f, 0.0f, 0.0f);
        glEnd();
    }

    void renderCube(boolean shadow) {
        glBegin(GL_QUADS);
        if (shadow) {
            glColor3f(0.2f, 0.2f, 0.2f);
        } else {
            glColor3f(0.0f, 0.0f, 0.2f);
        }
        glVertex3f(0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f, 0.5f, -0.5f);
        glVertex3f(0.5f, 0.5f, -0.5f);
        if (!shadow) {
            glColor3f(0.0f, 0.0f, 1.0f);
        }
        glVertex3f(0.5f, -0.5f, 0.5f);
        glVertex3f(0.5f, 0.5f, 0.5f);
        glVertex3f(-0.5f, 0.5f, 0.5f);
        glVertex3f(-0.5f, -0.5f, 0.5f);
        if (!shadow) {
            glColor3f(1.0f, 0.0f, 0.0f);
        }
        glVertex3f(0.5f, -0.5f, -0.5f);
        glVertex3f(0.5f, 0.5f, -0.5f);
        glVertex3f(0.5f, 0.5f, 0.5f);
        glVertex3f(0.5f, -0.5f, 0.5f);
        if (!shadow) {
            glColor3f(0.2f, 0.0f, 0.0f);
        }
        glVertex3f(-0.5f, -0.5f, 0.5f);
        glVertex3f(-0.5f, 0.5f, 0.5f);
        glVertex3f(-0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        if (!shadow) {
            glColor3f(0.0f, 1.0f, 0.0f);
        }
        glVertex3f(0.5f, 0.5f, 0.5f);
        glVertex3f(0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, 0.5f, 0.5f);
        if (!shadow) {
            glColor3f(0.0f, 0.2f, 0.0f);
        }
        glVertex3f(0.5f, -0.5f, -0.5f);
        glVertex3f(0.5f, -0.5f, 0.5f);
        glVertex3f(-0.5f, -0.5f, 0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        glEnd();
    }

    void loop() {
        GL.createCapabilities();

        glClearColor(0.6f, 0.7f, 0.8f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_STENCIL_TEST);
        glEnable(GL_CULL_FACE);

        long firstTime = System.nanoTime();

        Matrix4f m = new Matrix4f();
        Matrix4f m2 = new Matrix4f();
        Matrix4f planeTransform = new Matrix4f().translate(0.0f, -0.5f, 0.0f).scale(10.0f);
        Vector4f lightPos = new Vector4f();

        // when we write stencil, we always replace the current value
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);

        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float diff = (thisTime - firstTime) / 1E9f;
            float angle = diff;

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

            glMatrixMode(GL_PROJECTION);
            glLoadMatrixf(m.setPerspective((float) Math.toRadians(30.0f), (float) width / height, 0.01f, 100.0f).get(fb));

            glMatrixMode(GL_MODELVIEW);
            glLoadMatrixf(m.setLookAt(1.0f, 6.0f, 12.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f).rotateY(angle / 5.0f).get(fb));

            // always write stencil = 1
            glStencilFunc(GL_ALWAYS, 1, 1);

            // render cube in the center
            renderCube(false);

            // Render the plane on which to project the shadow
            glLoadMatrixf(m.mulAffine(planeTransform, m2).get(fb));
            renderPlane();

            // Render light bulb
            m2.rotationY(angle).translate(0, 0.8f, 2).transform(lightPos.set(0, 0, 0, 1));
            glLoadMatrixf(m.mulAffine(m2, m2).get(fb));
            renderLight();

            // Render projected shadow of the cube
            glLoadMatrixf(m.shadow(lightPos, planeTransform).get(fb));
            // Draw only on the stenciled area
            glStencilFunc(GL_EQUAL, 1, 1);
            glEnable(GL_POLYGON_OFFSET_FILL);
            // use polygon offset to combat z-fighting between plane and projected shadow
            glPolygonOffset(-1.0f, -1.0f);
            renderCube(true);
            glDisable(GL_POLYGON_OFFSET_FILL);

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static void main(String[] args) {
        new ProjectiveShadowDemo().run();
    }
}