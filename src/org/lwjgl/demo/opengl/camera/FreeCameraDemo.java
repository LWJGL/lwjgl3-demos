/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.camera;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBFragmentShader.GL_FRAGMENT_SHADER_ARB;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.GL_VERTEX_SHADER_ARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

import java.lang.Math;
import java.nio.IntBuffer;

import org.joml.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Simple "free fly" camera demo.
 * 
 * @author Kai Burjack
 */
public class FreeCameraDemo {
    private long window;
    private int width = 1200;
    private int height = 800;
    private int mouseX, mouseY;
    private boolean viewing;

    private final Matrix4f mat = new Matrix4f();
    private final Quaternionf orientation = new Quaternionf();
    private final Vector3f position = new Vector3f(0, 2, 5).negate();
    private boolean[] keyDown = new boolean[GLFW_KEY_LAST + 1];

    private int grid;
    private int gridProgram;
    private int gridProgramMatLocation;

    private void run() {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Hello, free fly camera!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");
        glfwSetKeyCallback(window, (long window1, int key, int scancode, int action, int mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window1, true);
            } else if (key >= 0 && key <= GLFW_KEY_LAST) {
                keyDown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        });
        glfwSetFramebufferSizeCallback(window, (long window3, int w, int h) -> {
            if (w > 0 && h > 0) {
                width = w;
                height = h;
            }
        });
        glfwSetCursorPosCallback(window, (long window2, double xpos, double ypos) -> {
            if (viewing) {
                float deltaX = (float) xpos - mouseX;
                float deltaY = (float) ypos - mouseY;
                orientation.rotateLocalX(deltaY * 0.01f).rotateLocalY(deltaX * 0.01f);
            }
            mouseX = (int) xpos;
            mouseY = (int) ypos;
        });
        glfwSetMouseButtonCallback(window, (long window4, int button, int action, int mods) -> {
            if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_PRESS) {
                viewing = true;
            } else {
                viewing = false;
            }
        });
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferSize = stack.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_ARB_shader_objects)
            throw new UnsupportedOperationException("ARB_shader_objects unsupported");
        if (!caps.GL_ARB_vertex_shader)
            throw new UnsupportedOperationException("ARB_vertex_shader unsupported");
        if (!caps.GL_ARB_fragment_shader)
            throw new UnsupportedOperationException("ARB_fragment_shader unsupported");
        glClearColor(0.7f, 0.8f, 0.9f, 1);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        createGridProgram();
        createGrid();
        glfwShowWindow(window);
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
            glUseProgramObjectARB(gridProgram);
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) * 1E-9f;
            lastTime = thisTime;
            try (MemoryStack stack = stackPush()) {
                glUniformMatrix4fvARB(gridProgramMatLocation, false, updateMatrices(dt).get(stack.mallocFloat(16)));
            }
            glCallList(grid);
            glfwSwapBuffers(window);
        }
    }

    private Matrix4f updateMatrices(float dt) {
        float rotateZ = 0f;
        float speed = 2f;
        if (keyDown[GLFW_KEY_LEFT_SHIFT])
            speed = 10f;
        if (keyDown[GLFW_KEY_Q])
            rotateZ -= 1f;
        if (keyDown[GLFW_KEY_E])
            rotateZ += 1f;
        if (keyDown[GLFW_KEY_W])
            position.add(orientation.positiveZ(new Vector3f()).mul(dt * speed));
        orientation.rotateLocalZ(rotateZ * dt * speed);
        return mat.setPerspective((float) Math.toRadians(60), (float) width / height, 0.1f, 1000.0f)
                  .rotate(orientation)
                  .translate(position);
    }

    private void createGridProgram() {
        gridProgram = glCreateProgramObjectARB();
        int vs = glCreateShaderObjectARB(GL_VERTEX_SHADER_ARB);
        glShaderSourceARB(vs, 
                "#version 110\n" +
                "uniform mat4 viewProjMatrix;\n" +
                "varying vec4 wp;\n" +
                "void main(void) {\n" +
                "  wp = gl_Vertex;\n" +
                "  gl_Position = viewProjMatrix * gl_Vertex;\n" +
                "}");
        glCompileShaderARB(vs);
        glAttachObjectARB(gridProgram, vs);
        int fs = glCreateShaderObjectARB(GL_FRAGMENT_SHADER_ARB);
        glShaderSourceARB(fs,
                "#version 110\n" +
                "varying vec4 wp;\n" +
                "void main(void) {\n" +
                "  vec2 p = wp.xz / wp.w;\n" +
                "  vec2 g = 0.5 * abs(fract(p) - 0.5) / fwidth(p);\n" +
                "  float a = min(min(g.x, g.y), 1.0);\n" +
                "  gl_FragColor = vec4(vec3(a), 1.0 - a);\n" +
                "}");
        glCompileShaderARB(fs);
        glAttachObjectARB(gridProgram, fs);
        glLinkProgramARB(gridProgram);
        gridProgramMatLocation = glGetUniformLocationARB(gridProgram, "viewProjMatrix");
    }

    private void createGrid() {
        grid = glGenLists(1);
        glNewList(grid, GL_COMPILE);
        glBegin(GL_TRIANGLES);
        glVertex4f(-1, 0, -1, 0);
        glVertex4f(-1, 0,  1, 0);
        glVertex4f( 0, 0,  0, 1);
        glVertex4f(-1, 0,  1, 0);
        glVertex4f( 1, 0,  1, 0);
        glVertex4f( 0, 0,  0, 1);
        glVertex4f( 1, 0,  1, 0);
        glVertex4f( 1, 0, -1, 0);
        glVertex4f( 0, 0,  0, 1);
        glVertex4f( 1, 0, -1, 0);
        glVertex4f(-1, 0, -1, 0);
        glVertex4f( 0, 0,  0, 1);
        glEnd();
        glEndList();
    }

    public static void main(String[] args) {
        new FreeCameraDemo().run();
    }
}
