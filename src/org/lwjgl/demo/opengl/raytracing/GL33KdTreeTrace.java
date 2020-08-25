/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing;

import static java.lang.ClassLoader.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GLUtil.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.nio.*;
import java.util.*;

import org.joml.*;
import org.joml.Math;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.KDTreei.Voxel;
import org.lwjgl.demo.util.MagicaVoxelLoader.Material;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.*;

/**
 * Stackless kd-tree ray tracing in OpenGL 3.3 with Buffer Textures.
 * 
 * @author Kai Burjack
 */
public class GL33KdTreeTrace {
  private long window;
  private int width = 1200;
  private int height = 800;
  private int quadVao;
  private int rayTracingProgram;
  private int camUniform;
  private Matrix4f pMat = new Matrix4f();
  private Matrix4f vMat = new Matrix4f().lookAt(70, 60, 180, 60, 20, 80, 0, 1, 0);
  private Matrix4f ivpMat = new Matrix4f();
  private Vector3f camPos = new Vector3f();
  private Vector3f v = new Vector3f();
  private Material[] materials = new Material[512];
  private FloatBuffer vbuf = memAllocFloat(3);
  private Callback debugProc;
  private int nodesBufferBO;
  private int nodesBufferTex;
  private int voxelsBufferBO;
  private int voxelsBufferTex;
  private int nodeGeomsBufferBO;
  private int nodeGeomsBufferTex;
  private int leafNodesBufferBO;
  private int leafNodesBufferTex;
  private int materialsBufferBO;
  private int materialsBufferTex;

  private void init() throws IOException {
    if (!glfwInit())
      throw new IllegalStateException("Unable to initialize GLFW");

    glfwDefaultWindowHints();
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

    window = glfwCreateWindow(width, height, "Stackless Kd-Tree with OpenGL 3.3", NULL, NULL);
    if (window == NULL)
      throw new AssertionError("Failed to create the GLFW window");

    glfwSetFramebufferSizeCallback(window, (long window, int w, int h) -> {
      if (w > 0 && h > 0 && (w != width || h != height)) {
        width = w;
        height = h;
        glViewport(0, 0, width, height);
      }
    });
    glfwSetKeyCallback(window, (long window, int key, int scancode, int action, int mods) -> {
      if (key == GLFW_KEY_ESCAPE)
        glfwSetWindowShouldClose(window, true);
    });

    // Center the window on the screen
    GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
    glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
    glfwMakeContextCurrent(window);

    try (MemoryStack frame = stackPush()) {
      IntBuffer framebufferSize = frame.mallocInt(2);
      nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
      width = framebufferSize.get(0);
      height = framebufferSize.get(1);
    }

    createCapabilities();
    debugProc = setupDebugMessageCallback();

    // Create OpenGL resources
    quadVao = glGenVertexArrays();
    createRayTracingProgram();
    createSceneTBOs(buildTerrainVoxels());

    glfwShowWindow(window);
  }

  private static List<KDTreei.Node<Voxel>> allocate(KDTreei.Node<Voxel> node) {
    List<KDTreei.Node<Voxel>> linearNodes = new ArrayList<>();
    LinkedList<KDTreei.Node<Voxel>> nodes = new LinkedList<>();
    int index = 0, leafIndex = 0;
    nodes.add(node);
    while (!nodes.isEmpty()) {
      KDTreei.Node<Voxel> n = nodes.removeFirst();
      linearNodes.add(n);
      n.index = index++;
      if (n.left != null) {
        nodes.addFirst(n.right);
        nodes.addFirst(n.left);
      } else {
        n.leafIndex = leafIndex++;
        n.boundables.forEach(v -> v.nindex = n.index);
      }
    }
    return linearNodes;
  }

  private static int createShader(String resource, int type) throws IOException {
    int shader = glCreateShader(type);
    ByteBuffer source = ioResourceToByteBuffer(resource, 8192);
    try (MemoryStack stack = stackPush()) {
      glShaderSource(shader, stack.pointers(source), stack.ints(source.remaining()));
    }
    glCompileShader(shader);
    int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
    String log = glGetShaderInfoLog(shader);
    if (log.trim().length() > 0)
      System.err.println(log);
    if (compiled == 0)
      throw new AssertionError("Could not compile shader: " + resource);
    return shader;
  }

  private void createRayTracingProgram() throws IOException {
    int program = glCreateProgram();
    int vshader = createShader("org/lwjgl/demo/opengl/raytracing/gl33kdtreetrace/quad.vs.glsl", GL_VERTEX_SHADER);
    int fshader = createShader("org/lwjgl/demo/opengl/raytracing/gl33kdtreetrace/raytracing.fs.glsl", GL_FRAGMENT_SHADER);
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
    glUseProgram(program);
    camUniform = glGetUniformLocation(program, "cam[0]");
    glUniform1i(glGetUniformLocation(program, "nodes"), 0);
    glUniform1i(glGetUniformLocation(program, "nodegeoms"), 1);
    glUniform1i(glGetUniformLocation(program, "leafnodes"), 2);
    glUniform1i(glGetUniformLocation(program, "voxels"), 3);
    glUniform1i(glGetUniformLocation(program, "materials"), 4);
    glUseProgram(0);
    rayTracingProgram = program;
  }

  private void createSceneTBOs(List<Voxel> voxels) {
    KDTreei<Voxel> root = KDTreei.build(voxels, 15);
    DynamicByteBuffer voxelsBuffer = new DynamicByteBuffer();
    DynamicByteBuffer nodesBuffer = new DynamicByteBuffer();
    DynamicByteBuffer nodeGeomsBuffer = new DynamicByteBuffer();
    DynamicByteBuffer leafNodesBuffer = new DynamicByteBuffer();
    kdTreeToBuffers(root, 0, 0, nodesBuffer, nodeGeomsBuffer, leafNodesBuffer, voxelsBuffer);
    nodesBufferBO = glGenBuffers();
    glBindBuffer(GL_TEXTURE_BUFFER, nodesBufferBO);
    nglBufferData(GL_TEXTURE_BUFFER, nodesBuffer.pos, nodesBuffer.addr, GL_STATIC_DRAW);
    nodesBufferTex = glGenTextures();
    glBindTexture(GL_TEXTURE_BUFFER, nodesBufferTex);
    glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, nodesBufferBO);
    voxelsBufferBO = glGenBuffers();
    glBindBuffer(GL_TEXTURE_BUFFER, voxelsBufferBO);
    nglBufferData(GL_TEXTURE_BUFFER, voxelsBuffer.pos, voxelsBuffer.addr, GL_STATIC_DRAW);
    voxelsBufferTex = glGenTextures();
    glBindTexture(GL_TEXTURE_BUFFER, voxelsBufferTex);
    glTexBuffer(GL_TEXTURE_BUFFER, GL_RG32UI, voxelsBufferBO);
    nodeGeomsBufferBO = glGenBuffers();
    glBindBuffer(GL_TEXTURE_BUFFER, nodeGeomsBufferBO);
    nglBufferData(GL_TEXTURE_BUFFER, nodeGeomsBuffer.pos, nodeGeomsBuffer.addr, GL_STATIC_DRAW);
    nodeGeomsBufferTex = glGenTextures();
    glBindTexture(GL_TEXTURE_BUFFER, nodeGeomsBufferTex);
    glTexBuffer(GL_TEXTURE_BUFFER, GL_RG32UI, nodeGeomsBufferBO);
    leafNodesBufferBO = glGenBuffers();
    glBindBuffer(GL_TEXTURE_BUFFER, leafNodesBufferBO);
    nglBufferData(GL_TEXTURE_BUFFER, leafNodesBuffer.pos, leafNodesBuffer.addr, GL_STATIC_DRAW);
    leafNodesBufferTex = glGenTextures();
    glBindTexture(GL_TEXTURE_BUFFER, leafNodesBufferTex);
    glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, leafNodesBufferBO);
    //
    DynamicByteBuffer materialsBuffer = new DynamicByteBuffer();
    for (Material mat : materials)
        if (mat != null)
            materialsBuffer.putInt(mat.color);
        else
            materialsBuffer.putInt(0);
    materialsBufferBO = glGenBuffers();
    glBindBuffer(GL_TEXTURE_BUFFER, materialsBufferBO);
    nglBufferData(GL_TEXTURE_BUFFER, materialsBuffer.pos, materialsBuffer.addr, GL_STATIC_DRAW);
    materialsBufferTex = glGenTextures();
    glBindTexture(GL_TEXTURE_BUFFER, materialsBufferTex);
    glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA8, materialsBufferBO);
  }

  private void kdTreeToBuffers(KDTreei<Voxel> root, int nodeIndexOffset, int voxelIndexOffset, DynamicByteBuffer nodesBuffer,
      DynamicByteBuffer nodeGeomsBuffer, DynamicByteBuffer leafNodesBuffer, DynamicByteBuffer voxelsBuffer) {
    int first = 0;
    List<KDTreei.Node<Voxel>> nodes = allocate(root.root);
    System.out.println("Num nodes in kd-tree: " + nodes.size());
    for (KDTreei.Node<Voxel> n : nodes) {
      int numVoxels = 0;
      if (n.left == null) {
        numVoxels = n.boundables.size();
        n.boundables.forEach(v -> {
          // RG32UI
          voxelsBuffer.putByte(v.x).putByte(v.y).putByte(v.z).putByte(v.paletteIndex);
          voxelsBuffer.putByte(v.ex).putByte(v.ey).putByte(v.ez).putByte(0);
        });
        // RGBA32UI
        leafNodesBuffer.putShort(first).putShort(numVoxels);
        for (int i = 0; i < 6; i++)
            leafNodesBuffer.putShort(n.ropes[i] != null ? n.ropes[i].index : -1);
      }
      // RG32UI
      nodeGeomsBuffer.putByte(n.bb.minX).putByte(n.bb.minY).putByte(n.bb.minZ).putByte(0);
      nodeGeomsBuffer.putByte(n.bb.maxX-1).putByte(n.bb.maxY-1).putByte(n.bb.maxZ-1).putByte(0);
      // R32UI
      nodesBuffer.putShort(n.right != null ? n.right.index + nodeIndexOffset : n.leafIndex);
      nodesBuffer.putShort(n.splitAxis == -1 ? -1 : n.splitAxis << (Short.SIZE - 2) | n.splitPos);
      first += numVoxels;
    }
    System.out.println("Num voxels in kd-tree: " + first);
  }

  private void update() {
    pMat.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.1f, 100.0f);
    pMat.invertPerspectiveView(vMat, ivpMat);
  }

  private void trace() {
    glUseProgram(rayTracingProgram);
    glUniform3fv(camUniform, vMat.originAffine(camPos).get(vbuf));
    glUniform3fv(camUniform + 1, ivpMat.transformProject(v.set(-1, -1, 0)).sub(camPos).get(vbuf));
    glUniform3fv(camUniform + 2, ivpMat.transformProject(v.set(-1, +1, 0)).sub(camPos).get(vbuf));
    glUniform3fv(camUniform + 3, ivpMat.transformProject(v.set(+1, -1, 0)).sub(camPos).get(vbuf));
    glUniform3fv(camUniform + 4, ivpMat.transformProject(v.set(+1, +1, 0)).sub(camPos).get(vbuf));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_BUFFER, nodesBufferTex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_BUFFER, nodeGeomsBufferTex);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_BUFFER, leafNodesBufferTex);
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_BUFFER, voxelsBufferTex);
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_BUFFER, materialsBufferTex);
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glUseProgram(0);
  }

  private static int idx(int x, int y, int z, int width, int depth) {
      return (x+1) + (width+2) * ((z+1) + (y+1) * (depth+2));
  }

  private List<KDTreei.Voxel> buildTerrainVoxels() throws IOException {
    Vector3i dims = new Vector3i();
    InputStream is = getSystemResourceAsStream("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house6.vox");
    BufferedInputStream bis = new BufferedInputStream(is);
    byte[] field = new byte[256 * 256 * 256];
    new MagicaVoxelLoader().read(bis, new MagicaVoxelLoader.Callback() {
      public void voxel(int x, int y, int z, byte c) {
        y = dims.z - y - 1;
        field[idx(x, z, y, dims.x, dims.z)] = c;
      }

      public void size(int x, int y, int z) {
        dims.x = x;
        dims.y = z;
        dims.z = y;
      }

      public void paletteMaterial(int i, Material mat) {
          materials[i] = mat;
      }
    });
    boolean[] culled = new boolean[(dims.x+2) * (dims.y+2) * (dims.z+2)];
    // Cull voxels
    int numVoxels = 0, numRetainedVoxels = 0;
    for (int z = 0; z < dims.z; z++) {
        for (int y = 0; y < dims.y; y++) {
            for (int x = 0; x < dims.x; x++) {
                int idx = idx(x, y, z, dims.x, dims.z);
                byte c = field[idx];
                if (c == 0)
                    continue;
                numVoxels++;
                boolean left = x > 0 && (field[idx(x - 1, y, z, dims.x, dims.z)]) != 0;
                boolean right = x < dims.x - 1 && (field[idx(x + 1, y, z, dims.x, dims.z)]) != 0;
                boolean down = y > 0 && (field[idx(x, y - 1, z, dims.x, dims.z)]) != 0;
                boolean up = y < dims.y - 1 && (field[idx(x, y + 1, z, dims.x, dims.z)]) != 0;
                boolean back = z > 0 && (field[idx(x, y, z - 1, dims.x, dims.z)]) != 0;
                boolean front = z < dims.z - 1 && (field[idx(x, y, z + 1, dims.x, dims.z)]) != 0;
                if (left && right && down && up && back && front) {
                    culled[idx] = true;
                } else {
                    numRetainedVoxels++;
                }
            }
        }
    }
    System.out.println("Num voxels: " + numVoxels);
    System.out.println("Num voxels after culling: " + numRetainedVoxels);
    /* Merge voxels */
    List<Voxel> voxels = new ArrayList<>();
    GreedyVoxels gv = new GreedyVoxels(0, dims.y - 1, dims.x, dims.z, (x, y, z, w, h, d, v) -> {
      voxels.add(new Voxel(x, y, z, w-1, h-1, d-1, v));
    });
    gv.merge(field, culled);
    System.out.println("Num voxels after merge: " + voxels.size());
    return voxels;
  }

  private void loop() {
    while (!glfwWindowShouldClose(window)) {
      glfwPollEvents();
      update();
      trace();
      glfwSwapBuffers(window);
    }
  }

  private void run() throws IOException {
    init();
    loop();
    if (debugProc != null)
      debugProc.free();
    glfwFreeCallbacks(window);
    glfwDestroyWindow(window);
  }

  public static void main(String[] args) throws IOException {
    new GL33KdTreeTrace().run();
  }
}
