/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.swt;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.opengl.GLCanvas;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Shows how to use SWT and GLFW windows side-by-side.
 * 
 * @author Kai Burjack
 */
public class SwtAndGlfwDemo {
    public static void main(String[] args) {
        // Create SWT window
        Display display = new Display();
        final Shell shell = new Shell(display);
        shell.setText("SWT window");
        shell.setLayout(new FillLayout());
        shell.addListener(SWT.Traverse, new Listener() {
            public void handleEvent(Event event) {
                switch (event.detail) {
                case SWT.TRAVERSE_ESCAPE:
                    shell.close();
                    event.detail = SWT.TRAVERSE_NONE;
                    event.doit = false;
                    break;
                default:
                    break;
                }
            }
        });
        GLData data = new GLData();
        data.doubleBuffer = true;
        GLCanvas swtCanvas = new GLCanvas(shell, SWT.NO_BACKGROUND | SWT.NO_REDRAW_RESIZE, data);
        shell.setSize(800, 600);
        shell.open();
        swtCanvas.setCurrent();
        GLCapabilities swtCapabilities = createCapabilities();

        // Create GLFW window
        glfwInit();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        long glfwWindow = glfwCreateWindow(600, 600, "GLFW window", 0L, 0L);
        GLFWKeyCallback keyCallback;
        glfwSetKeyCallback(glfwWindow, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, GL_TRUE);
            }
        });
        glfwMakeContextCurrent(glfwWindow);
        GLCapabilities glfwCapabilities = createCapabilities();

        while (!shell.isDisposed() && glfwWindowShouldClose(glfwWindow) == GLFW_FALSE) {
            // Process SWT window messages
            display.readAndDispatch();
            // Render to SWT window
            if (!swtCanvas.isDisposed()) {
                swtCanvas.setCurrent();
                setCapabilities(swtCapabilities);
                glClearColor(0.2f, 0.4f, 0.6f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                swtCanvas.swapBuffers();
            }

            // Process GLFW window messages
            glfwPollEvents();
            // Render to GLFW window
            if (glfwGetWindowAttrib(glfwWindow, GLFW_VISIBLE) == GLFW_TRUE) {
                glfwMakeContextCurrent(glfwWindow);
                setCapabilities(glfwCapabilities);
                glClearColor(0.2f, 0.3f, 0.4f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                glfwSwapBuffers(glfwWindow);
            }
        }

        // Dispose of SWT
        if (!shell.isDisposed())
            shell.dispose();
        display.dispose();

        // Dispose of GLFW
        keyCallback.release();
        glfwDestroyWindow(glfwWindow);
        glfwTerminate();
    }
}
