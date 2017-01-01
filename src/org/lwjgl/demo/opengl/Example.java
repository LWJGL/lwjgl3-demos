package org.lwjgl.demo.opengl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

public abstract class Example {
    private long window;

    protected int width = 1024;
    protected int height = 768;

    protected GLCapabilities caps;

    protected GLFWErrorCallback errCallback;
    protected GLFWKeyCallback keyCallback;
    protected GLFWFramebufferSizeCallback fbCallback;
    protected Callback debugProc;

    protected  void init() throws IOException {
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
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Immediate mode shader demo", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (width != Example.this.width || height != Example.this.height)) {
                    Example.this.width = width;
                    Example.this.height = height;
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
        glfwSwapInterval(1);
        glfwShowWindow(window);
        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public abstract void render();

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            render();

            glfwSwapBuffers(window);
        }
    }

    public void run() {
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
}
