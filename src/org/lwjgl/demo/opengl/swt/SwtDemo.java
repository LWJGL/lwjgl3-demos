/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.swt;

import static org.lwjgl.opengl.GL20.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.opengl.GLCanvas;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public class SwtDemo {
    public static void main(String[] args) {
        int minClientWidth = 640;
        int minClientHeight = 480;
        final Display display = new Display();
        final Shell shell = new Shell(display);
        shell.setLayout(new FillLayout());
        shell.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.stateMask == SWT.ALT && (e.keyCode == SWT.KEYPAD_CR || e.keyCode == SWT.CR)) {
                    shell.setFullScreen(!shell.getFullScreen());
                }
            }
        });
        int dw = shell.getSize().x - shell.getClientArea().width;
        int dh = shell.getSize().y - shell.getClientArea().height;
        shell.setMinimumSize(minClientWidth + dw, minClientHeight + dh);
        GLData data = new GLData();
        data.doubleBuffer = true;
        final GLCanvas canvas = new GLCanvas(shell, SWT.NO_BACKGROUND | SWT.NO_REDRAW_RESIZE, data);
        canvas.setCurrent();
        GL.createCapabilities();

        final Rectangle rect = new Rectangle(0, 0, 0, 0);
        canvas.addListener(SWT.Resize, new Listener() {
            public void handleEvent(Event event) {
                Rectangle bounds = canvas.getBounds();
                rect.width = bounds.width;
                rect.height = bounds.height;
            }
        });
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

        glClearColor(0.3f, 0.5f, 0.8f, 1.0f);

        // Create a simple shader program
        int program = glCreateProgram();
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs,
                "uniform float rot;" +
                "uniform float aspect;" +
                "void main(void) {" +
                "  vec4 v = gl_Vertex * 0.5;" +
                "  vec4 v_ = vec4(0.0, 0.0, 0.0, 1.0);" +
                "  v_.x = v.x * cos(rot) - v.y * sin(rot);" +
                "  v_.y = v.y * cos(rot) + v.x * sin(rot);" +
                "  v_.x /= aspect;" +
                "  gl_Position = v_;" +
                "}");
        glCompileShader(vs);
        glAttachShader(program, vs);
        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs,
                "void main(void) {" +
                "  gl_FragColor = vec4(0.1, 0.3, 0.5, 1.0);" +
                "}");
        glCompileShader(fs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glUseProgram(program);
        final int rotLocation = glGetUniformLocation(program, "rot");
        final int aspectLocation = glGetUniformLocation(program, "aspect");

        // Create a simple quad
        int vbo = glGenBuffers();
        int ibo = glGenBuffers();
        float[] vertices = {
            -1, -1, 0,
             1, -1, 0,
             1,  1, 0,
            -1,  1, 0
        };
        int[] indices = {
            0, 1, 2,
            2, 3, 0
        };
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (FloatBuffer) BufferUtils
                .createFloatBuffer(vertices.length).put(vertices).flip(),
                GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, (IntBuffer) BufferUtils
                .createIntBuffer(indices.length).put(indices).flip(),
                GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);

        shell.setSize(800, 600);
        shell.open();

        display.asyncExec(new Runnable() {
            float rot;
            long lastTime = System.nanoTime();
            public void run() {
                if (!canvas.isDisposed()) {
                    canvas.setCurrent();
                    glClear(GL_COLOR_BUFFER_BIT);
                    glViewport(0, 0, rect.width, rect.height);

                    float aspect = (float) rect.width / rect.height;
                    glUniform1f(aspectLocation, aspect);
                    glUniform1f(rotLocation, rot);
                    glDrawElements(GL11.GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

                    canvas.swapBuffers();
                    display.asyncExec(this);

                    long thisTime = System.nanoTime();
                    float delta = (thisTime - lastTime) / 1E9f;
                    rot += delta;
                    if (rot > 2.0 * Math.PI) {
                        rot -= 2.0f * (float) Math.PI;
                    }
                    lastTime = thisTime;
                }
            }
        });

        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
        display.dispose();
    }
}
