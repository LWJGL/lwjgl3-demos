/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.camera;

import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.joml.Intersectionf.intersectRayPlane;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases draggable arcball camera.
 * 
 * @author Kai Burjack
 */
public class ArcballCameraDemo {
    private static final Vector3f zero = new Vector3f();

    private long window;
    private int width = 1200;
    private int height = 800;
    private int mouseX, mouseY;
    private boolean dragging, viewing;

    private Vector3f dragStartWorldPos = new Vector3f();
    private Vector3f dragCamNormal = new Vector3f();
    private Vector3f dragRayOrigin = new Vector3f();
    private Vector3f dragRayDir = new Vector3f();
    private Vector3f translation = new Vector3f();
    private final Matrix4f pMat = new Matrix4f();
    private final Matrix4x3f vMat = new Matrix4x3f();
    private final Matrix4f vpMat = new Matrix4f();
    private float xAngle = 0.5f, yAngle = 0.3f, radius = 20;

    private int grid, cube;
    private int gridProgram;
    private int gridProgramMatLocation;

    private void initGlfw() {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
    }

    private void ensureCapabilities() {
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_ARB_shader_objects)
            throw new UnsupportedOperationException("ARB_shader_objects unsupported");
        if (!caps.GL_ARB_vertex_shader)
            throw new UnsupportedOperationException("ARB_vertex_shader unsupported");
        if (!caps.GL_ARB_fragment_shader)
            throw new UnsupportedOperationException("ARB_fragment_shader unsupported");
    }

    private void createWindow() {
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Hello, infinite draggable plane with arcball camera!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");
    }

    private void registerWindowEventHandler() {
        glfwSetKeyCallback(window, this::onKey);
        glfwSetFramebufferSizeCallback(window, this::onFramebufferSize);
        glfwSetCursorPosCallback(window, this::onMouseMove);
        glfwSetMouseButtonCallback(window, this::onMouseButton);
        glfwSetScrollCallback(window, this::onScroll);
    }

    private void run() {
        initGlfw();
        createWindow();
        registerWindowEventHandler();
        readFramebufferSizeForHiDPI();
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        ensureCapabilities();
        setGlobalGlState();

        createGridProgram();
        createGrid();
        createCube();

        glfwShowWindow(window);

        while (!glfwWindowShouldClose(window)) {
            try (MemoryStack stack = stackPush()) {
                glfwPollEvents();
                glViewport(0, 0, width, height);
                glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
                // draw cube
                glUseProgramObjectARB(0);
                glLoadMatrixf(updateMatrices(true).get(stack.mallocFloat(16)));
                glCallList(cube);
                // draw grid
                glUseProgramObjectARB(gridProgram);
                glUniformMatrix4fvARB(gridProgramMatLocation, false, updateMatrices(false).get(stack.mallocFloat(16)));
                glCallList(grid);
                glfwSwapBuffers(window);
            }
        }
    }

    private void setGlobalGlState() {
        glClearColor(0.7f, 0.8f, 0.9f, 1);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
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

    private void createCube() {
        cube = glGenLists(1);
        glNewList(cube, GL_COMPILE);
        glBegin(GL_QUADS);
        glColor3f(  0.0f,  0.0f,  0.2f);
        glVertex3f( 0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f);
        glVertex3f( 0.5f,  0.5f, -0.5f);
        glColor3f(  0.0f,  0.0f,  1.0f);
        glVertex3f( 0.5f, -0.5f,  0.5f);
        glVertex3f( 0.5f,  0.5f,  0.5f);
        glVertex3f(-0.5f,  0.5f,  0.5f);
        glVertex3f(-0.5f, -0.5f,  0.5f);
        glColor3f(  1.0f,  0.0f,  0.0f);
        glVertex3f( 0.5f, -0.5f, -0.5f);
        glVertex3f( 0.5f,  0.5f, -0.5f);
        glVertex3f( 0.5f,  0.5f,  0.5f);
        glVertex3f( 0.5f, -0.5f,  0.5f);
        glColor3f(  0.2f,  0.0f,  0.0f);
        glVertex3f(-0.5f, -0.5f,  0.5f);
        glVertex3f(-0.5f,  0.5f,  0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        glColor3f(  0.0f,  1.0f,  0.0f);
        glVertex3f( 0.5f,  0.5f,  0.5f);
        glVertex3f( 0.5f,  0.5f, -0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f);
        glVertex3f(-0.5f,  0.5f,  0.5f);
        glColor3f(  0.0f,  0.2f,  0.0f);
        glVertex3f( 0.5f, -0.5f, -0.5f);
        glVertex3f( 0.5f, -0.5f,  0.5f);
        glVertex3f(-0.5f, -0.5f,  0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f);
        glEnd();
        glEndList();
    }

    private Matrix4f updateMatrices(boolean cube) {
        pMat.setPerspective((float) Math.toRadians(60), (float) width / height, 0.1f, 1000.0f);
        return pMat.mul(vMat.translation(0, 0, -radius)
                            .rotateX(xAngle)
                            .rotateY(yAngle)
                            .translate(cube ? zero : translation), vpMat);
    }

    private void readFramebufferSizeForHiDPI() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferSize = stack.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }
    
    private void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
            glfwSetWindowShouldClose(window, true);
    }

    private void onFramebufferSize(long window, int w, int h) {
        if (w > 0 && h > 0) {
            width = w;
            height = h;
        }
    }

    private void onMouseMove(long window, double xpos, double ypos) {
        if (dragging)
            drag(xpos, ypos);
        else if (viewing)
            rotate(xpos, ypos);
        mouseX = (int) xpos;
        mouseY = (int) ypos;
    }

    private void dragBegin() {
        dragging = true;
        // find "picked" point on the grid
        vpMat.unprojectRay(mouseX, height - mouseY, new int[] {0, 0, width, height}, dragRayOrigin, dragRayDir);
        float t = intersectRayPlane(dragRayOrigin, dragRayDir, new Vector3f(), new Vector3f(0, dragRayOrigin.y > 0 ? 1 : -1, 0), 1E-5f);
        dragStartWorldPos.set(dragRayDir).mul(t).add(dragRayOrigin);
        vMat.positiveZ(dragCamNormal);
    }
    private void drag(double xpos, double ypos) {
        // find new position of the picked point
        vpMat.unprojectRay((float) xpos, height - (float) ypos, new int[] {0, 0, width, height}, dragRayOrigin, dragRayDir);
        float t = intersectRayPlane(dragRayOrigin, dragRayDir, dragStartWorldPos, dragCamNormal, 1E-5f);
        Vector3f dragWorldPosition = new Vector3f(dragRayDir).mul(t).add(dragRayOrigin);
        translation.add(dragWorldPosition.sub(dragStartWorldPos));
    }
    private void dragEnd() {
        dragging = false;
    }

    private void rotate(double xpos, double ypos) {
        float deltaX = (float) xpos - mouseX;
        float deltaY = (float) ypos - mouseY;
        xAngle += deltaY * 0.005f;
        yAngle += deltaX * 0.005f;
    }

    private void onMouseButton(long window, int button, int action, int mods) {
        if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_PRESS)
            dragBegin();
        else if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_RELEASE)
            dragEnd();
        else if (button == GLFW_MOUSE_BUTTON_2 && action == GLFW_PRESS)
            viewBegin();
        else if (button == GLFW_MOUSE_BUTTON_2 && action == GLFW_RELEASE)
            viewEnd();
    }

    private void onScroll(long window, double xoffset, double yoffset) {
        radius *= yoffset > 0 ? 1f/1.1f : 1.1f;
    }

    private void viewEnd() {
        viewing = false;
    }

    private void viewBegin() {
        viewing = true;
    }

    public static void main(String[] args) {
        new ArcballCameraDemo().run();
    }

}
