/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.fbo;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.system.libffi.Closure;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.errorCallbackPrint;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.ARBTextureMultisample.*;
import static org.lwjgl.opengl.ARBFramebufferObject.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases multisampled FBO rendering.
 * 
 * @author Kai Burjack
 */
public class MultisampledFboDemo {

	long window;
	int width = 1024;
	int height = 768;
	boolean resetFramebuffer;
	boolean destroyed;
	Object lock = new Object();

	int colorTexture;
	int depthRenderBuffer;
	int fbo;
	int samples = 8;

	GLFWErrorCallback errorCallback;
	GLFWKeyCallback keyCallback;
	GLFWFramebufferSizeCallback fbCallback;
	Closure debugProc;

	void run() {
		try {
			init();
			winProcLoop();

			synchronized (lock) {
				destroyed = true;
				glfwDestroyWindow(window);
			}
			keyCallback.release();
			fbCallback.release();
		} finally {
			glfwTerminate();
			errorCallback.release();
		}
	}

	void init() {
		glfwSetErrorCallback(errorCallback = errorCallbackPrint(System.err));
		if (glfwInit() != GL_TRUE)
			throw new IllegalStateException("Unable to initialize GLFW");

		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);

		window = glfwCreateWindow(width, height, "Multisampled FBO", NULL, NULL);
		if (window == NULL) {
			throw new AssertionError("Failed to create the GLFW window");
		}

		glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
			@Override
			public void invoke(long window, int key, int scancode, int action, int mods) {
				if (action != GLFW_RELEASE)
					return;

				if (key == GLFW_KEY_ESCAPE) {
					glfwSetWindowShouldClose(window, GL_TRUE);
				}
			}
		});

		glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
			@Override
			public void invoke(long window, int width, int height) {
				if (width > 0 && height > 0 && (MultisampledFboDemo.this.width != width || MultisampledFboDemo.this.height != height)) {
					MultisampledFboDemo.this.width = width;
					MultisampledFboDemo.this.height = height;
					MultisampledFboDemo.this.resetFramebuffer = true;
				}
			}
		});

		ByteBuffer vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
		glfwSetWindowPos(window, (GLFWvidmode.width(vidmode) - width) / 2, (GLFWvidmode.height(vidmode) - height) / 2);
		glfwShowWindow(window);

		IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
		nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
		width = framebufferSize.get(0);
		height = framebufferSize.get(1);
	}

	void createFramebufferObject() {
		colorTexture = glGenTextures();
		depthRenderBuffer = glGenRenderbuffers();
		fbo = glGenFramebuffers();

		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, colorTexture);
		glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, samples, GL_RGBA, width, height, true);

		glBindRenderbuffer(GL_RENDERBUFFER, depthRenderBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, width, height);

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D_MULTISAMPLE, colorTexture, 0);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderBuffer);
		int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
			throw new AssertionError("Could not create FBO: " + fboStatus);
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	void resizeFramebufferTexture() {
		glDeleteRenderbuffers(depthRenderBuffer);
		glDeleteTextures(colorTexture);
		glDeleteFramebuffers(fbo);
		createFramebufferObject();
	}

	void update() {
		if (resetFramebuffer) {
			resizeFramebufferTexture();
			resetFramebuffer = false;
		}
	}

	void renderLoop() {
		glfwMakeContextCurrent(window);
		GLContext.createFromCurrent().setupDebugMessageCallback();
		glClearColor(1.0f, 1.0f, 1.0f, 0.0f);
		glColor3f(0.1f, 0.1f, 0.1f);

		/* Query maximum sample count */
		samples = glGetInteger(GL_MAX_SAMPLES);
		System.err.println("Using " + samples + "x multisampling");

		/* Initially create the FBO with color texture and renderbuffer */
		createFramebufferObject();

		long lastTime = System.nanoTime();
		while (!destroyed) {
			/* Render to multisampled FBO */
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			{
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
				glViewport(0, 0, width, height);

				/* Update the FBO if the window changed in size */
				update();

				long thisTime = System.nanoTime();
				float elapsed = (lastTime - thisTime) / 1E9f;
				lastTime = thisTime;

				/* Simple orthographic project */
				float aspect = (float) width / height;
				glMatrixMode(GL_PROJECTION);
				glLoadIdentity();
				glOrtho(-1.0f * aspect, +1.0f * aspect, -1.0f, +1.0f, -1.0f, +1.0f);

				/* Rotate a bit and draw a quad */
				glMatrixMode(GL_MODELVIEW);
				glRotatef(elapsed, 0, 0, 1);
				glBegin(GL_QUADS);
				glVertex2f(-0.5f, -0.5f);
				glVertex2f(+0.5f, -0.5f);
				glVertex2f(+0.5f, +0.5f);
				glVertex2f(-0.5f, +0.5f);
				glEnd();
			}
			glBindFramebuffer(GL_FRAMEBUFFER, 0);

			/* Blit to default framebuffer */
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
			glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

			synchronized (lock) {
				if (!destroyed) {
					glfwSwapBuffers(window);
				}
			}
		}
	}

	void winProcLoop() {
		/*
		 * Start new thread to have the OpenGL context current in and which does
		 * the rendering.
		 */
		new Thread(new Runnable() {
			public void run() {
				renderLoop();
			}
		}).start();

		while (glfwWindowShouldClose(window) == GL_FALSE) {
			glfwWaitEvents();
		}
	}

	public static void main(String[] args) {
		new MultisampledFboDemo().run();
	}

}