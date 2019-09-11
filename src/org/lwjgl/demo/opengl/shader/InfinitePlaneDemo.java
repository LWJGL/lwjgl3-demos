/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Render an infinite XZ plane with antialiased grid pattern.
 * 
 * @author Kai Burjack
 */
public class InfinitePlaneDemo {
    private GLFWErrorCallback errorCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback fbCallback;

    private long window;
    private int width = 1200;
    private int height = 800;

    private final Matrix4f viewProjMatrix = new Matrix4f();
    private final FloatBuffer fb = BufferUtils.createFloatBuffer(16);

    private void run() {
        try {
            init();
            loop();
            glfwDestroyWindow(window);
            keyCallback.free();
            fbCallback.free();
        } finally {
            glfwTerminate();
            errorCallback.free();
        }
    }

    private void init() {
        glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Hello, infinite plane!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);
    }

    private int compileGrid() {
        int displayList = glGenLists(1);
        glNewList(displayList, GL_COMPILE);
        glBegin(GL_TRIANGLES);
        glVertex4f(-1, 0,-1, 0);
        glVertex4f(-1, 0, 1, 0);
        glVertex4f( 0, 0, 0, 1);
        glVertex4f(-1, 0, 1, 0);
        glVertex4f( 1, 0, 1, 0);
        glVertex4f( 0, 0, 0, 1);
        glVertex4f( 1, 0, 1, 0);
        glVertex4f( 1, 0,-1, 0);
        glVertex4f( 0, 0, 0, 1);
        glVertex4f( 1, 0,-1, 0);
        glVertex4f(-1, 0,-1, 0);
        glVertex4f( 0, 0, 0, 1);
        glEnd();
        glEndList();
        return displayList;
    }

    private void loop() {
        glfwMakeContextCurrent(window);
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_ARB_shader_objects)
            throw new UnsupportedOperationException("ARB_shader_objects unsupported");
        if (!caps.GL_ARB_vertex_shader)
            throw new UnsupportedOperationException("ARB_vertex_shader unsupported");
        if (!caps.GL_ARB_fragment_shader)
            throw new UnsupportedOperationException("ARB_fragment_shader unsupported");

        int grid = compileGrid();

        glClearColor(0.6f, 0.7f, 0.8f, 1);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        int program = glCreateProgramObjectARB();
        int vs = glCreateShaderObjectARB(GL_VERTEX_SHADER_ARB);
        glShaderSourceARB(vs,
                "uniform mat4 viewProjMatrix;\n" +
                "varying vec4 wp;\n" +
                "void main(void) {\n" +
                "  wp = gl_Vertex;\n" +
                "  gl_Position = viewProjMatrix * gl_Vertex;\n" +
                "}");
        glCompileShaderARB(vs);
        glAttachObjectARB(program, vs);
        int fs = glCreateShaderObjectARB(GL_FRAGMENT_SHADER_ARB);
        glShaderSourceARB(fs,
                "varying vec4 wp;\n" +
                "void main(void) {\n" +
                "  vec2 p = wp.xz / wp.w;\n" +
                "  vec2 g = 0.5 * abs(fract(p) - 0.5) / fwidth(p);\n" + 
                "  float a = min(min(g.x, g.y), 1.0);\n" +
                "  gl_FragColor = vec4(vec3(a), 1.0 - a);\n" +
                "}");
        glCompileShaderARB(fs);
        glAttachObjectARB(program, fs);
        glLinkProgramARB(program);
        glUseProgramObjectARB(program);
        glValidateProgramARB(program);

        int matLocation = glGetUniformLocationARB(program, "viewProjMatrix");
        long lastTime = System.nanoTime();

        float angle = 0;
        float z = 0;
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            angle += dt*0.1f;
            z -= dt*5;
            lastTime = thisTime;

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);

            viewProjMatrix.setPerspective((float) Math.toRadians(60.0), (float) width / height, 0.1f, Float.POSITIVE_INFINITY)
                          .rotateX(0.1f)
                          .rotateY(angle)
                          .rotateZ((float) Math.sin(angle*4) * 0.1f)
                          .translate(0, -4, -z);

            glUniformMatrix4fvARB(matLocation, false, viewProjMatrix.get(fb));
            glCallList(grid);
            glfwSwapBuffers(window);
        }
    }

    public static void main(String[] args) {
        new InfinitePlaneDemo().run();
    }
}