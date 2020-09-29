/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

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
 * Render an infinite XZ plane with antialiased grid pattern and allow
 * dragging the plane with the mouse and to move around with mouse/keyboard controls.
 * 
 * @author Kai Burjack
 */
public class InfiniteDraggablePlaneDemo {

    private long window;
    private int width = 1200;
    private int height = 800;
    private int mouseX, mouseY;
    private Vector3f dragStartWorldPos = new Vector3f();
    private Vector3f dragCamNormal = new Vector3f();
    private Vector3f dragRayOrigin = new Vector3f();
    private Vector3f dragRayDir = new Vector3f();
    private Vector3f dragTranslation = new Vector3f();
    private boolean dragging, viewing;
    private final boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private int matLocation;
    private int grid;
    private int program;

    private final Matrix4f pMat = new Matrix4f();
    private final Matrix4x3f vMat = new Matrix4x3f().lookAt(0, 5, 10, 0, 0, 0, 0, 1, 0);
    private final Matrix4x3f vWithTranslationMat = new Matrix4x3f();
    private final Matrix4f vpMat = new Matrix4f();
    private final Matrix4f vpMatDrag = new Matrix4f();

    private void run() {
        initGlfw();
        createWindow();
        registerWindowEventHandler();
        readFramebufferSizeForHiDPI();
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        ensureCapabilities();
        setGlobalGlState();

        program = createShaderProgram();
        grid = createDisplayList();
        matLocation = glGetUniformLocationARB(program, "viewProjMatrix");

        glfwShowWindow(window);
        loop();
    }

    private void loop() {
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;
            handleKeyboardInput(dt);

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            try (MemoryStack stack = stackPush()) {
                glUniformMatrix4fvARB(matLocation, false, updateMatrices().get(stack.mallocFloat(16)));
            }
            glCallList(grid);
            glfwSwapBuffers(window);
        }
    }

    private Matrix4f updateMatrices() {
        pMat.setPerspective((float) Math.toRadians(60), (float) width / height, 0.1f, 1000.0f);
        return pMat.mul(vMat.translate(dragTranslation, vWithTranslationMat), vpMat);
    }

    private int createShaderProgram() {
        int program = glCreateProgramObjectARB();
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
        glAttachObjectARB(program, vs);
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
        glAttachObjectARB(program, fs);
        glLinkProgramARB(program);
        glUseProgramObjectARB(program);
        glValidateProgramARB(program);
        return program;
    }

    private void setGlobalGlState() {
        glClearColor(0.7f, 0.8f, 0.9f, 1);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private int createDisplayList() {
        int displayList = glGenLists(1);
        glNewList(displayList, GL_COMPILE);
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
        return displayList;
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

    private void readFramebufferSizeForHiDPI() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferSize = stack.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }
    
    private void handleKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
            glfwSetWindowShouldClose(window, true);
        else if (key >= 0)
            keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
    }

    private void handleFramebufferSize(long window, int w, int h) {
        if (w > 0 && h > 0) {
            width = w;
            height = h;
        }
    }

    private void handleCursorPos(long window, double xpos, double ypos) {
        if (dragging)
            drag(xpos, ypos);
        else if (viewing)
            rotate(xpos, ypos);
        mouseX = (int) xpos;
        mouseY = (int) ypos;
    }

    private void rotate(double xpos, double ypos) {
        float deltaX = (float) xpos - mouseX;
        float deltaY = (float) ypos - mouseY;
        vMat.rotateLocalY(deltaX * 0.005f);
        vMat.rotateLocalX(deltaY * 0.005f);
    }

    private void drag(double xpos, double ypos) {
        vpMatDrag.unprojectRay((float) xpos, height - (float) ypos, new int[] {0, 0, width, height}, dragRayOrigin, dragRayDir);
        float t = intersectRayPlane(dragRayOrigin, dragRayDir, dragStartWorldPos, dragCamNormal, 1E-5f);
        Vector3f dragWorldPosition = new Vector3f(dragRayDir).mul(t).add(dragRayOrigin);
        dragWorldPosition.sub(dragStartWorldPos, dragTranslation);
    }

    private void handleMouseButton(long window, int button, int action, int mods) {
        if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_PRESS)
            dragBegin();
        else if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_RELEASE)
            dragEnd();
        else if (button == GLFW_MOUSE_BUTTON_2 && action == GLFW_PRESS)
            viewBegin();
        else if (button == GLFW_MOUSE_BUTTON_2 && action == GLFW_RELEASE)
            viewEnd();
    }

    private void viewEnd() {
        viewing = false;
    }

    private void viewBegin() {
        viewing = true;
    }

    private void dragEnd() {
        dragging = false;
        vMat.translate(dragTranslation);
        dragTranslation.zero();
    }

    private void dragBegin() {
        dragging = true;
        vpMatDrag.set(vpMat);
        vpMatDrag.unprojectRay(mouseX, height - mouseY, new int[] {0, 0, width, height}, dragRayOrigin, dragRayDir);
        float t = intersectRayPlane(dragRayOrigin, dragRayDir, new Vector3f(), new Vector3f(0, dragRayOrigin.y > 0 ? 1 : -1, 0), 1E-5f);
        dragStartWorldPos.set(dragRayDir).mul(t).add(dragRayOrigin);
        vMat.positiveZ(dragCamNormal);
    }

    private void registerWindowEventHandler() {
        glfwSetKeyCallback(window, this::handleKey);
        glfwSetFramebufferSizeCallback(window, this::handleFramebufferSize);
        glfwSetCursorPosCallback(window, this::handleCursorPos);
        glfwSetMouseButtonCallback(window, this::handleMouseButton);
    }

    private void initGlfw() {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");
    }

    private void createWindow() {
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "Hello, infinite draggable plane!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");
    }

    private void handleKeyboardInput(float dt) {
        if (dragging)
            return;
        float factor = 10.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 30.0f;
        if (keydown[GLFW_KEY_W])
            vMat.translateLocal(0, 0, factor * dt);
        if (keydown[GLFW_KEY_S])
            vMat.translateLocal(0, 0, -factor * dt);
        if (keydown[GLFW_KEY_A])
            vMat.translateLocal(factor * dt, 0, 0);
        if (keydown[GLFW_KEY_D])
            vMat.translateLocal(-factor * dt, 0, 0);
        if (keydown[GLFW_KEY_Q])
            vMat.rotateLocalZ(-factor * dt * 0.2f);
        if (keydown[GLFW_KEY_E])
            vMat.rotateLocalZ(factor * dt * 0.2f);
        if (keydown[GLFW_KEY_LEFT_CONTROL])
            vMat.translateLocal(0, factor * dt, 0);
        if (keydown[GLFW_KEY_SPACE])
            vMat.translateLocal(0, -factor * dt, 0);
    }

    public static void main(String[] args) {
        printInfo();
        new InfiniteDraggablePlaneDemo().run();
    }

    private static void printInfo() {
        System.out.println("Use left mouse button + drag to move the plane");
        System.out.println("Use right mouse button + drag to rotate the view around");
        System.out.println("Use WASD to move forward/left/backward/right");
        System.out.println("Use Left Ctrl / Space to down / up");
        System.out.println("Use Q/E to roll left/right");
        System.out.println("Use Shift to move/roll faster");
    }

}
