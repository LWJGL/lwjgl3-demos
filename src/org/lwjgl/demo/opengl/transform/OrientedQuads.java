/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.transform;

import static org.lwjgl.demo.util.IOUtils.ioResourceToByteBuffer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

/**
 * @author Kai Burjack
 */
public class OrientedQuads {
  static int width = 800;
  static int height = 600;

  public static void main(String[] args) throws IOException {
    if (!glfwInit())
      throw new IllegalStateException("Unable to initialize GLFW");
    long window = glfwCreateWindow(width, height, "Hello oriented quads!", NULL, NULL);
    if (window == NULL)
      throw new RuntimeException("Failed to create the GLFW window");
    glfwSetKeyCallback(window, (wnd, key, scancode, action, mods) -> {
      if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
        glfwSetWindowShouldClose(window, true);
    });
    glfwSetFramebufferSizeCallback(window, (wnd, w, h) -> {
      width = w;
      height = h;
    });
    glfwMakeContextCurrent(window);
    glfwShowWindow(window);
    GL.createCapabilities();

    // set global GL state
    glClearColor(0.6f, 0.7f, 0.8f, 1.0f);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_TEXTURE_2D);
    glEnable(GL_CULL_FACE);
    glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    // load arrow texture with stb_image
    try (MemoryStack stack = stackPush()) {
      ByteBuffer imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/opengl/transform/arrow.png", 8 * 1024);
      IntBuffer w1 = stack.mallocInt(1);
      IntBuffer h1 = stack.mallocInt(1);
      IntBuffer c = stack.mallocInt(1);
      ByteBuffer img = STBImage.stbi_load_from_memory(imageBuffer, w1, h1, c, 4);
      int tex = glGenTextures();
      glBindTexture(GL_TEXTURE_2D, tex);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, img);
      STBImage.stbi_image_free(img);
    }

    Matrix4f projMatrix = new Matrix4f();
    Matrix4x3f viewMatrix = new Matrix4x3f();
    Matrix4x3f modelMatrix = new Matrix4x3f();
    Matrix4x3f modelViewMatrix = new Matrix4x3f();
    FloatBuffer fb = BufferUtils.createFloatBuffer(16);

    while (!glfwWindowShouldClose(window)) {
      glViewport(0, 0, width, height);
      projMatrix.setPerspective((float) Math.toRadians(40), (float) width / height, 0.01f, 100.0f);
      glMatrixMode(GL_PROJECTION);
      glLoadMatrixf(projMatrix.get(fb));

      Vector3f cameraPosition = new Vector3f(0, 6, 10);
      viewMatrix.setLookAt(
          cameraPosition.x, cameraPosition.y, cameraPosition.z,
          0.0f, -1.0f, 0.0f,
          0.0f, 1.0f, 0.0f);
      glMatrixMode(GL_MODELVIEW);
      glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

      // Render some grid of quads at different x and z positions
      for (int x = -2; x <= 2; x++) {
        for (int z = -2; z <= 2; z++) {
          Vector3f quadPosition = new Vector3f(x * 2.0f, 0, z * 2.0f);
          Vector3f targetPosition = new Vector3f();
          Vector3f quadToTarget = new Vector3f(targetPosition).sub(quadPosition);
          Vector3f quadToCamera = new Vector3f(cameraPosition).sub(quadPosition);
          modelMatrix
            .setLookAt(quadPosition, targetPosition, quadToCamera)
            .invert()
            .rotateXYZ((float) Math.toRadians(-90), 0, (float) Math.toRadians(90));
          // instead of rotateXYZ, we can also do: .mapnZnXY()
          /*
          // or:
          modelMatrix
            .translation(quadPosition)
            .rotateTowards(quadToTarget, quadToCamera)
            .rotateXYZ((float) Math.toRadians(-90), 0, (float) Math.toRadians(-90));
          // instead of rotateXYZ, we can also do: .mapZXY()
          // or:
          quadToTarget.normalize();
          modelMatrix
            .billboardCylindrical(quadPosition, cameraPosition, quadToTarget)
            .rotateZ((float) Math.toRadians(90));
          // instead of rotateZ, we can also do: .mapYnXZ()
           */
          glLoadMatrixf(viewMatrix.mul(modelMatrix, modelViewMatrix).get4x4(fb));
          renderQuad();
        }
      }
      glfwSwapBuffers(window);
      glfwPollEvents();
    }
  }

  private static void renderQuad() {
    glBegin(GL_QUADS);
    glTexCoord2f(1, 0);
    glVertex3f(0.5f, -0.5f, 0);
    glTexCoord2f(1, 1);
    glVertex3f(0.5f, 0.5f, 0);
    glTexCoord2f(0, 1);
    glVertex3f(-0.5f, 0.5f, 0);
    glTexCoord2f(0, 0);
    glVertex3f(-0.5f, -0.5f, 0);
    glEnd();
  }
}