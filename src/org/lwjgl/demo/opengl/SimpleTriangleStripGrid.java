package org.lwjgl.demo.opengl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.nio.*;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Rendering a simple GL_TRIANGLE_STRIP grid.
 * 
 * @author Kai Burjack
 */
public class SimpleTriangleStripGrid {
    private int width = 800, height = 600;

    private void run() {
        // Initialize GLFW and create window
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
        GLFWKeyCallback keyCallback;
        GLFWFramebufferSizeCallback fbCallback;
        long window = glfwCreateWindow(width, height, "Hello, triangle strip grid!", NULL, NULL);
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
        glfwMakeContextCurrent(window);
        // HiDPI fix:
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        // Tell LWJGL about an active OpenGL context:
        GL.createCapabilities();

        // Initialize some state
        glClearColor(0.3f, 0.45f, 0.72f, 1.0f);
        glEnable(GL_CULL_FACE);
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glLoadIdentity();

        int columns = 100, rows = 100;
        glOrtho(0, columns - 1, rows - 1, 0, -1, 1);

        // Build vertices
        float[] vertices = new float[columns * rows * 2];
        int i = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                vertices[i++] = x;
                vertices[i++] = y;
            }
        }

        // Build indices
        i = 0;
        int[] indices = new int[(rows - 1) * (columns + 1) * 2];
        for (int y = 0; y < rows - 1; y++) {
            for (int x = 0; x < columns; x++) {
                indices[i++] = y * columns + x;
                indices[i++] = (y + 1) * columns + x;
            }
            if (y < height - 1) {
                indices[i++] = (y + 2) * columns - 1;
                indices[i++] = (y + 1) * columns;
            }
        }

        // Upload to buffer objects
        int vbo = glGenBuffers(), ibo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glEnableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glVertexPointer(2, GL_FLOAT, 0, 0L);

        while (!glfwWindowShouldClose(window)) {
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawElements(GL_TRIANGLE_STRIP, indices.length, GL_UNSIGNED_INT, 0L);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static void main(String[] args) {
        new SimpleTriangleStripGrid().run();
    }
}
