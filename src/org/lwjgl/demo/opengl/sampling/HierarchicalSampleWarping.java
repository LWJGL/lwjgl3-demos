package org.lwjgl.demo.opengl.sampling;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.demo.util.IOUtils.ioResourceToByteBuffer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * Demo showcasing Hierarchical Sample Warping (HSW) as first proposed in the paper
 * "Wavelet Importance: Efficiently Evaluating Products of Complex Functions" by Clarberg et al.
 * <p>
 * This demo implements importance sampling via hierarchical sample warping in its associated fragment shader
 * by using a 2D lightmap texture and generating samples with probabilities proportional to a texel's luminance.
 * <p>
 * See:
 * <ul>
 * <li><a href="https://link.springer.com/content/pdf/10.1007/978-1-4842-4427-2_16.pdf">section 16.4.2.3 HIERARCHICAL TRANSFORMATION in chapter "Transformations Zoo" of "Ray Tracing Gems".</a></li>
 * <li><a href="http://graphics.ucsd.edu/~henrik/papers/wavelet_importance_sampling.pdf">"Wavelet Importance: Efficiently Evaluating Products of Complex Functions" by Clarberg et al.</a></li>
 * <li><a href="https://www.ea.com/seed/news/siggraph21-global-illumination-surfels">SIGGRAPH 21: Global Illumination Based on Surfels</a></li>
 * </ul>
 *
 * @author Kai Burjack
 */
public class HierarchicalSampleWarping {
  private static int width = 800, height = 800;
  public static void main(String[] args) throws IOException {
    if (!glfwInit())
      throw new IllegalStateException("Unable to initialize GLFW");
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
    long window = glfwCreateWindow(width, height, "Hello HSW!", NULL, NULL);
    if (window == NULL)
      throw new RuntimeException("Failed to create the GLFW window");
    glfwSetKeyCallback(window, new GLFWKeyCallback() {
      public void invoke(long window, int key, int scancode, int action, int mods) {
        if (action != GLFW_RELEASE)
          return;
        if (key == GLFW_KEY_ESCAPE)
          glfwSetWindowShouldClose(window, true);
      }
    });
    glfwMakeContextCurrent(window);
    glfwSwapInterval(1);
    GL.createCapabilities();

    // Read actualy framebuffer size for HiDPI displays
    try (MemoryStack stack = stackPush()) {
      IntBuffer framebufferSize = stack.mallocInt(2);
      nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
      width = framebufferSize.get(0);
      height = framebufferSize.get(1);
    }

    // Create shader program which implements visualization of the hierarchical sample warping
    int program = glCreateProgram();
    {
      int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/sampling/hsw.vs.glsl", GL_VERTEX_SHADER, null);
      int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/sampling/hsw.fs.glsl", GL_FRAGMENT_SHADER, null);
      glAttachShader(program, vshader);
      glAttachShader(program, fshader);
      glBindFragDataLocation(program, 0, "color");
      glLinkProgram(program);
      int linked = glGetProgrami(program, GL_LINK_STATUS);
      String programLog = glGetProgramInfoLog(program);
      if (programLog.trim().length() > 0)
        System.err.println(programLog);
      if (linked == 0)
        throw new AssertionError("Could not link program");
    }
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "tex"), 0);
    int timeLocation = glGetUniformLocation(program, "time");
    int blendFactorLocation = glGetUniformLocation(program, "blendFactor");

    // Create empty VAO
    int vao = glGenVertexArrays();
    glBindVertexArray(vao);

    // Create FBO with 32-bit floating point color buffer to do the sample accumulation
    int fbo = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    int framebufferTex = glGenTextures();
    {
      glBindTexture(GL_TEXTURE_2D, framebufferTex);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, framebufferTex, 0);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw new AssertionError("Incomplete framebuffer");
    }

    // Load an image, that we want to sample via hierarchical sample warping, into a texture
    // and generate mipmaps for it
    int tex = glGenTextures();
    {
      glBindTexture(GL_TEXTURE_2D, tex);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      IntBuffer w = BufferUtils.createIntBuffer(1);
      IntBuffer h = BufferUtils.createIntBuffer(1);
      IntBuffer comp = BufferUtils.createIntBuffer(1);
      ByteBuffer imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/opengl/sampling/env-square2.hdr", 64 * 1024);
      stbi_set_flip_vertically_on_load(true);
      if (!stbi_info_from_memory(imageBuffer, w, h, comp))
        throw new IOException("Failed to read image information: " + stbi_failure_reason());
      FloatBuffer image = stbi_loadf_from_memory(imageBuffer, w, h, comp, 3);
      if (image == null)
        throw new IOException("Failed to load image: " + stbi_failure_reason());
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, w.get(0), h.get(0), 0, GL_RGB, GL_FLOAT, (ByteBuffer) null);
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w.get(0), h.get(0), GL_RGB, GL_FLOAT, image);
      stbi_image_free(image);
      glGenerateMipmap(GL_TEXTURE_2D);
    }

    glfwShowWindow(window);

    long lastTime = System.nanoTime();
    float time = 0.0f;
    int frameNumber = 0;

    // Enable blending for sample accumulation in the FBO
    glBlendFunc(GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA);
    glEnable(GL_BLEND);

    while (!glfwWindowShouldClose(window)) {
      glfwPollEvents();

      long thisTime = System.nanoTime();
      float elapsed = (thisTime - lastTime) / 1E9f;
      time += elapsed;
      lastTime = thisTime;

      glViewport(0, 0, width, height);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
      glUseProgram(program);
      glBindTexture(GL_TEXTURE_2D, tex);
      glUniform1f(blendFactorLocation, frameNumber / (frameNumber + 1.0f));
      glUniform1f(timeLocation, time);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
      glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

      glfwSwapBuffers(window);
      frameNumber++;
    }
  }
}
