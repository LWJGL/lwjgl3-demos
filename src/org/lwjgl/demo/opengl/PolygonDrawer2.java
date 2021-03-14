package org.lwjgl.demo.opengl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.BitSet;

import org.joml.Matrix4f;
import org.joml.PolygonsIntersection;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.opengl.GL;

/**
 * Like the {@link PolygonDrawer} but it rotates everything around the viewport center.
 * <p>
 * Intersection tests and drawing at the right position still work! :)
 * 
 * @author Kai Burjack
 */
public class PolygonDrawer2 {
    GLFWErrorCallback errorCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWWindowSizeCallback wsCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWMouseButtonCallback mbCallback;

    long window;
    int width = 800;
    int height = 600;
    int fbWidth = 800;
    int fbHeight = 600;
    int x, y;
    boolean down;
    float[] verticesXY = new float[1024 * 1024];
    int[] polygons = new int[0];
    PolygonsIntersection pointIntersection;
    BitSet hitPolygons = new BitSet();
    int num = 0;
    boolean inside;
    int querymicroseconds = 0;
    int hitPolygonIndex = -1;
    Matrix4f transformation = new Matrix4f();
    Matrix4f transformationInv = new Matrix4f();
    Vector3f p = new Vector3f();
    FloatBuffer matBuffer = BufferUtils.createFloatBuffer(16);

    void run() {
        try {
            init();
            loop();

            glfwDestroyWindow(window);
            keyCallback.free();
            fbCallback.free();
            wsCallback.free();
            cpCallback.free();
            mbCallback.free();
        } finally {
            glfwTerminate();
            errorCallback.free();
        }
    }
    
    void updateStats() {
        glfwSetWindowTitle(window, "Polygon Demo (" + num + " vertices @ " + querymicroseconds + " µs.)");
    }
    
    void intersect() {
        if (pointIntersection != null) {
            long time1 = System.nanoTime();
            transformationInv.transformPosition(p.set(x, y, 0));
            inside = pointIntersection.testPoint(p.x, p.y, hitPolygons);
            if (inside) {
                hitPolygonIndex = hitPolygons.nextSetBit(0);
            }
            long time2 = System.nanoTime();
            querymicroseconds = (int) ((time2 - time1) / 1E3);
            updateStats();
        }
        else
            inside = false;
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

        System.out.println("Draw polygons with holding the left mouse button down");
        System.out.println("Move the mouse cursor in and out of the polygons");
        System.out.println("Press 'C' to clear all polygons");

        window = glfwCreateWindow(width, height, "Polygon Demo", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");
        long cursor = glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        glfwSetCursor(window, cursor);

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                else if (key == GLFW_KEY_C && action == GLFW_RELEASE) {
                    num = 0;
                    polygons = new int[0];
                    updateStats();
                }
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    fbWidth = w;
                    fbHeight = h;
                }
            }
        });
        glfwSetWindowSizeCallback(window, wsCallback = new GLFWWindowSizeCallback() {
            @Override
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double xpos, double ypos) {
                x = (int) xpos;
                y = (int) ypos;
                transformationInv.transformPosition(p.set(x, y, 0));
                if (down) {
                    verticesXY[2 * num + 0] = p.x;
                    verticesXY[2 * num + 1] = p.y;
                    num++;
                    updateStats();
                }
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_LEFT) {
                    down = true;
                    inside = false;
                } else if (action == GLFW_RELEASE && button == GLFW_MOUSE_BUTTON_LEFT) {
                    down = false;
                    int[] newPolygons = new int[polygons.length + 1];
                    System.arraycopy(polygons, 0, newPolygons, 0, polygons.length);
                    newPolygons[polygons.length] = num;
                    polygons = newPolygons;
                    pointIntersection = new PolygonsIntersection(verticesXY, polygons, num);
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        fbWidth = framebufferSize.get(0);
        fbHeight = framebufferSize.get(1);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        warmup();
        updateStats();
    }

    void warmup() {
        glfwSetWindowTitle(window, "Warming up...");
        glfwPollEvents();
        // Warmup polygon
        int warmupCount = 1024 * 64;
        float[] warmupVertices = new float[warmupCount * 2];
        for (int i = 0; i < warmupCount; i++) {
            warmupVertices[2*i+0] = ((float)Math.cos((float)i/warmupCount) - 0.5f) * 2.0f;
            warmupVertices[2*i+1] = ((float)Math.cos((float)i/warmupCount) - 0.5f) * 2.0f;
        }
        pointIntersection = new PolygonsIntersection(warmupVertices, new int[0], warmupCount);
        int warmupIterations = 1024 * 1024 * 8;
        float[] warmupSamples = new float[256];
        for (int i = 0; i < warmupSamples.length; i++) {
            warmupSamples[i] = ((float)Math.random() - 0.5f);
        }
        for (int i = 0; i < warmupIterations; i++) {
            float x = warmupSamples[i%256];
            float y = warmupSamples[(i+1)%256];
            pointIntersection.testPoint(x, y);
        }
        pointIntersection = new PolygonsIntersection(verticesXY, new int[0], 0);
    }

    void renderPolygon() {
        glBegin(GL_LINE_STRIP);
        if (num > 0) {
            int curr = 0;
            int first = 0;
            for (int i = 0; i < num; i++) {
                if (inside && curr == hitPolygonIndex)
                    glColor3f(1.0f, 0.3f, 0.3f);
                else
                    glColor3f(0.01f, 0.01f, 0.01f);
                if ((i == (num - 1)) && down)
                    glColor3f(0.8f, 0.8f, 0.8f);
                if (polygons.length > curr && polygons[curr] == i) {
                    // close current polygon
                    glVertex2f(verticesXY[2 * first + 0], verticesXY[2 * first + 1]);
                    first = i;
                    curr++;
                    glEnd();
                    glBegin(GL_LINE_STRIP);
                    if (inside && curr == hitPolygonIndex)
                        glColor3f(1.0f, 0.3f, 0.3f);
                    else
                        glColor3f(0.01f, 0.01f, 0.01f);
                }
                glVertex2f(verticesXY[2 * i + 0], verticesXY[2 * i + 1]);
            }
            glVertex2f(verticesXY[2 * first + 0], verticesXY[2 * first + 1]);
        }
        glEnd();
    }

    float angle = 0.0f;
    void loop() {
        GL.createCapabilities();

        glClearColor(0.99f, 0.99f, 0.99f, 1.0f);
        glLineWidth(1.8f);

        long lastTime = System.nanoTime();

        while (!glfwWindowShouldClose(window)) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;

            glViewport(0, 0, fbWidth, fbHeight);
            glClear(GL_COLOR_BUFFER_BIT);

            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glOrtho(0, width, height, 0, -1, 1);
            glMatrixMode(GL_MODELVIEW);
            transformation
                .identity()
                .translate(width/2, height/2, 0)
                .rotateZ(angle += dt * 0.2f)
                .translate(-width/2, -height/2, 0)
                .invert(transformationInv);
            glLoadMatrixf(transformation.get(matBuffer));

            intersect();
            renderPolygon();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static void main(String[] args) {
        new PolygonDrawer2().run();
    }
}