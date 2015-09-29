/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.fbo;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.raytracing.Scene;
import org.lwjgl.demo.opengl.util.Camera;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases simple reconstruction of the world position from the depth buffer.
 * <p>
 * It uses a depth attachment texture to render depth-only to the FBO.
 * Afterwards, the world-space coordinates are reconstructed via the depth
 * values from the depth texture.
 * 
 * @author Kai Burjack
 */
public class ReadDepthBuffer15Demo {

	/**
	 * The scene as (min, max) axis-aligned boxes.
	 */
	private static Vector3f[] boxes = Scene.boxes;

	private long window;
	private int width = 1024;
	private int height = 768;
	private boolean resetFramebuffer = true;

	private int depthTexture;
	private int fullScreenQuadVbo;
	private int fullScreenQuadProgram;
	private int depthOnlyProgram;
	private int fbo;
	private int vboScene;

	private int viewMatrixUniform;
	private int projectionMatrixUniform;
	private int inverseProjectionViewMatrixUniform;

	private Camera camera;
	private float mouseDownX;
	private float mouseX;
	private boolean mouseDown;

	private float currRotationAboutY = 0.0f;
	private float rotationAboutY = 0.0f;

	private Vector3f tmpVector = new Vector3f();
	private Vector3f cameraLookAt = new Vector3f(0.0f, 0.5f, 0.0f);
	private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
	private ByteBuffer matrixByteBuffer = BufferUtils.createByteBuffer(4 * 16);
	private FloatBuffer matrixByteBufferFloatView = matrixByteBuffer.asFloatBuffer();

	GLFWErrorCallback errCallback;
	GLFWKeyCallback keyCallback;
	GLFWCursorPosCallback cpCallback;
	GLFWFramebufferSizeCallback fbCallback;
	GLFWMouseButtonCallback mbCallback;
	Closure debugProc;

	private void init() throws IOException {
		glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
			private GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

			@Override
			public void invoke(int error, long description) {
				if (error == GLFW_VERSION_UNAVAILABLE)
					System.err.println("This demo requires OpenGL 1.5 or higher.");
				delegate.invoke(error, description);
			}

			@Override
			public void release() {
				delegate.release();
				super.release();
			}
		});

		if (glfwInit() != GL_TRUE)
			throw new IllegalStateException("Unable to initialize GLFW");

		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 1);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
		glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);

		window = glfwCreateWindow(width, height, "Sample depth buffer", NULL, NULL);
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

		glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
			@Override
			public void invoke(long window, double x, double y) {
				ReadDepthBuffer15Demo.this.mouseX = (float) x;
			}
		});

		glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
			@Override
			public void invoke(long window, int width, int height) {
				if (width > 0 && height > 0 && (ReadDepthBuffer15Demo.this.width != width || ReadDepthBuffer15Demo.this.height != height)) {
					ReadDepthBuffer15Demo.this.width = width;
					ReadDepthBuffer15Demo.this.height = height;
					ReadDepthBuffer15Demo.this.resetFramebuffer = true;
				}
			}
		});

		glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
			@Override
			public void invoke(long window, int button, int action, int mods) {
				if (action == GLFW_PRESS) {
					ReadDepthBuffer15Demo.this.mouseDownX = ReadDepthBuffer15Demo.this.mouseX;
					ReadDepthBuffer15Demo.this.mouseDown = true;
				} else if (action == GLFW_RELEASE) {
					ReadDepthBuffer15Demo.this.mouseDown = false;
					ReadDepthBuffer15Demo.this.rotationAboutY = ReadDepthBuffer15Demo.this.currRotationAboutY;
				}
			}
		});

		ByteBuffer vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
		glfwSetWindowPos(window, (GLFWvidmode.width(vidmode) - width) / 2, (GLFWvidmode.height(vidmode) - height) / 2);
		glfwMakeContextCurrent(window);
		glfwSwapInterval(0);
		glfwShowWindow(window);

		IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
		nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
		width = framebufferSize.get(0);
		height = framebufferSize.get(1);

		GLCapabilities caps = GL.createCapabilities();
		if (!caps.GL_EXT_framebuffer_object) {
			throw new AssertionError("This demo requires the EXT_framebuffer_object extension");
		}
		if (!caps.GL_ARB_shader_objects) {
			throw new AssertionError("This demo requires the ARB_shader_objects extension");
		}
		if (!caps.GL_ARB_vertex_shader) {
			throw new AssertionError("This demo requires the ARB_vertex_shader extension");
		}
		if (!caps.GL_ARB_fragment_shader) {
			throw new AssertionError("This demo requires the ARB_fragment_shader extension");
		}
		debugProc = GLUtil.setupDebugMessageCallback();

		/* Create all needed GL resources */
		createDepthTexture();
		createFramebufferObject();
		createFullScreenVbo();
		createSceneVbo();
		createDepthOnlyProgram();
		initDepthOnlyProgram();
		createFullScreenQuadProgram();
		initFullScreenQuadProgram();

		glEnable(GL_DEPTH_TEST);
		glEnable(GL_CULL_FACE);

		/* Setup camera */
		camera = new Camera();
	}

	private void createFullScreenVbo() {
		int vbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		ByteBuffer bb = BufferUtils.createByteBuffer(4 * 2 * 6);
		FloatBuffer fv = bb.asFloatBuffer();
		fv.put(-1.0f).put(-1.0f);
		fv.put(1.0f).put(-1.0f);
		fv.put(1.0f).put(1.0f);
		fv.put(1.0f).put(1.0f);
		fv.put(-1.0f).put(1.0f);
		fv.put(-1.0f).put(-1.0f);
		glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
		//glEnableVertexAttribArray(0);
		//glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		fullScreenQuadVbo = vbo;
	}

	private void createSceneVbo() {
		int vbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		ByteBuffer bb = BufferUtils.createByteBuffer(boxes.length / 2 * 4 * (3 + 3) * 6 * 6);
		FloatBuffer fv = bb.asFloatBuffer();
		for (int i = 0; i < boxes.length; i += 2) {
			DemoUtils.triangulateBox(boxes[i], boxes[i + 1], fv);
		}
		glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
		//glEnableVertexAttribArray(0);
		//glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		this.vboScene = vbo;
	}

	private void createFramebufferObject() {
		this.fbo = glGenFramebuffersEXT();
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		glDrawBuffer(GL_NONE); // we are not rendering to color buffers!
		glReadBuffer(GL_NONE); // we are also not reading from color buffers!
		glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_TEXTURE_2D, depthTexture, 0);
		int fboStatus = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
		if (fboStatus != GL_FRAMEBUFFER_COMPLETE_EXT) {
			throw new AssertionError("Could not create FBO: " + fboStatus);
		}
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
	}

	private void createFullScreenQuadProgram() throws IOException {
		int program = glCreateProgramObjectARB();
		int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/quadDepth.vs", GL_VERTEX_SHADER_ARB);
		int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/quadDepth.fs", GL_FRAGMENT_SHADER_ARB);
		glAttachObjectARB(program, vshader);
		glAttachObjectARB(program, fshader);
		glBindAttribLocationARB(program, 0, "vertex");
		//glBindFragDataLocation(program, 0, "color");
		glLinkProgramARB(program);
		int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
		String programLog = glGetInfoLogARB(program);
		if (programLog.trim().length() > 0) {
			System.err.println(programLog);
		}
		if (linked == 0) {
			throw new AssertionError("Could not link program");
		}
		this.fullScreenQuadProgram = program;
	}

	private void createDepthOnlyProgram() throws IOException {
		int program = glCreateProgramObjectARB();
		int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/rasterDepth.vs", GL_VERTEX_SHADER_ARB);
		int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/fbo/rasterDepth.fs", GL_FRAGMENT_SHADER_ARB);
		glAttachObjectARB(program, vshader);
		glAttachObjectARB(program, fshader);
		glBindAttribLocationARB(program, 0, "vertexPosition");
		glLinkProgramARB(program);
		int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
		String programLog = glGetInfoLogARB(program);
		if (programLog.trim().length() > 0) {
			System.err.println(programLog);
		}
		if (linked == 0) {
			throw new AssertionError("Could not link program");
		}
		this.depthOnlyProgram = program;
	}

	private void initDepthOnlyProgram() {
		glUseProgramObjectARB(depthOnlyProgram);
		viewMatrixUniform = glGetUniformLocationARB(depthOnlyProgram, "viewMatrix");
		projectionMatrixUniform = glGetUniformLocationARB(depthOnlyProgram, "projectionMatrix");
		glUseProgramObjectARB(0);
	}

	private void initFullScreenQuadProgram() {
		glUseProgramObjectARB(fullScreenQuadProgram);
		int texUniform = glGetUniformLocationARB(fullScreenQuadProgram, "tex");
		inverseProjectionViewMatrixUniform = glGetUniformLocationARB(fullScreenQuadProgram, "inverseProjectionViewMatrix");
		glUniform1iARB(texUniform, 0);
		glUseProgramObjectARB(0);
	}

	private void createDepthTexture() {
		this.depthTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, depthTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void resizeFramebufferTexture() {
		glDeleteTextures(depthTexture);
		glDeleteFramebuffersEXT(fbo);

		createDepthTexture();
		createFramebufferObject();
	}

	private void update() {
		if (mouseDown) {
			/*
			 * If mouse is down, compute the camera rotation based on mouse
			 * cursor location.
			 */
			currRotationAboutY = rotationAboutY + (mouseX - mouseDownX) * 0.01f;
		} else {
			currRotationAboutY = rotationAboutY;
		}

		/* Rotate camera about Y axis. */
		tmpVector.set((float) sin(-currRotationAboutY) * 3.0f, 2.0f, (float) cos(-currRotationAboutY) * 3.0f);
		camera.setLookAt(tmpVector, cameraLookAt, cameraUp);

		if (resetFramebuffer) {
			camera.setFrustumPerspective(60.0f, (float) width / height, 0.01f, 100.0f);
			resizeFramebufferTexture();
			resetFramebuffer = false;
		}
	}

	private void matrixUniform(int location, Matrix4f value, boolean transpose) {
		value.get(matrixByteBufferFloatView);
		glUniformMatrix4fvARB(location, 1, transpose, matrixByteBuffer);
	}

	private void renderDepthOnly() {
		glEnable(GL_DEPTH_TEST);
		glUseProgramObjectARB(depthOnlyProgram);

		/* Update matrices in shader */
		Matrix4f viewMatrix = camera.getViewMatrix();
		matrixUniform(viewMatrixUniform, viewMatrix, false);
		Matrix4f projMatrix = camera.getProjectionMatrix();
		matrixUniform(projectionMatrixUniform, projMatrix, false);

		/* Rasterize the boxes into the FBO */
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		glClear(GL_DEPTH_BUFFER_BIT);
		glBindBuffer(GL_ARRAY_BUFFER, vboScene);
		glEnableVertexAttribArrayARB(0);
		glVertexAttribPointerARB(0, 3, GL_FLOAT, false, 4 * (3 + 3), 0L);
		glDrawArrays(GL_TRIANGLES, 0, 6 * 6 * boxes.length / 2);
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
		glUseProgramObjectARB(0);
	}

	private void present() {
		glDisable(GL_DEPTH_TEST);
		glUseProgramObjectARB(fullScreenQuadProgram);

		/* Set the inverse(proj * view) matrix in the shader */
		Matrix4f inverseProjectionViewMatrix = camera.getInverseProjectionViewMatrix();
		matrixUniform(inverseProjectionViewMatrixUniform, inverseProjectionViewMatrix, false);

		glBindBuffer(GL_ARRAY_BUFFER, fullScreenQuadVbo);
		glEnableVertexAttribArrayARB(0);
		glVertexAttribPointerARB(0, 2, GL_FLOAT, false, 0, 0L);
		glBindTexture(GL_TEXTURE_2D, depthTexture);
		glDrawArrays(GL_TRIANGLES, 0, 6);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgramObjectARB(0);
	}

	private void loop() {
		while (glfwWindowShouldClose(window) == GL_FALSE) {
			glfwPollEvents();
			glViewport(0, 0, width, height);

			update();
			renderDepthOnly();
			present();

			glfwSwapBuffers(window);
		}
	}

	private void run() {
		try {
			init();
			loop();

			if (debugProc != null)
				debugProc.release();

			errCallback.release();
			keyCallback.release();
			cpCallback.release();
			fbCallback.release();
			mbCallback.release();
			glfwDestroyWindow(window);
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			glfwTerminate();
		}
	}

	public static void main(String[] args) {
		new ReadDepthBuffer15Demo().run();
	}

}