/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.shader;

import static java.lang.Integer.parseInt;
import static org.lwjgl.demo.util.IOUtils.ioResourceToByteBuffer;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.GLUtil.setupDebugMessageCallback;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.opengl.ARBDebugOutput.GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB;
import static org.lwjgl.opengl.GL43C.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import org.joml.*;
import org.joml.Random;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * Conway's Game of Life using OpenGL compute shader.
 * 
 * @author Kai Burjack
 */
public class GameOfLife {

    private static final boolean DEBUG = has("debug", false);
    static {
        if (DEBUG) {
            /* When we are in debug mode, enable all LWJGL debug flags */
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        } else {
            System.setProperty("org.lwjgl.util.NoChecks", "true");
        }
    }

    private static class GolPattern {
        List<Vector2i> points = new ArrayList<>();
        int height;
    }

    private static boolean has(String prop, boolean def) {
        String value = System.getProperty(prop);
        return value != null ? value.isEmpty() || Boolean.parseBoolean(value) : def;
    }

    private static final int MAX_NUM_CELLS_X = 1024 * 2;
    private static final int MAX_NUM_CELLS_Y = 1024 * 2;
    private static final int WORK_GROUP_SIZE_X = 16;
    private static final int WORK_GROUP_SIZE_Y = 16;

    private static long window;
    private static int width = 1024;
    private static int height = 768;

    /* All OpenGL resources */
    private static int[] textures = new int[2];
    private static int readTexIndex;
    private static int iterationProgram;
    private static int renderProgram;
    private static int renderProgramMatUniform;
    private static int vao;

    /* State */
    private static Callback debugProc;
    private static Matrix3x2f proj = new Matrix3x2f();
    private static Matrix3x2f view = new Matrix3x2f();
    private static double mouseX, mouseDownX, mouseY, mouseDownY;
    private static boolean mouseDown;
    private static List<GolPattern> patterns = new ArrayList<>();
    private static boolean stopped, step;

    private static void determineOpenGLCapabilities() {
        GL.createCapabilities();
    }

    private static void createTextures() {
        for (int i = 0; i < textures.length; i++) {
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8UI, MAX_NUM_CELLS_X, MAX_NUM_CELLS_Y);
            textures[i] = tex;
        }
    }

    private static void installDebugCallback() {
        debugProc = setupDebugMessageCallback();
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB);
    }

    private static void onFramebufferSize(long window, int w, int h) {
        if (w != 0 && h != 0) {
            width = w;
            height = h;
            glViewport(0, 0, width, height);
        }
    }

    private static void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE)
            glfwSetWindowShouldClose(window, true);
        else if (key == GLFW_KEY_SPACE && action == GLFW_PRESS)
            stopped = !stopped;
        else if (key == GLFW_KEY_S && action == GLFW_PRESS)
            step = true;
    }

    private static void onScroll(long window, double x, double y) {
        float ar = (float) width / height;
        view.scaleAroundLocal(y > 0.0 ? 1.1f : 1 / 1.1f, (float) (mouseX * 2 - width) / width * ar, -(float) (mouseY * 2 - height) / height);
    }

    private static void onMouseMove(long window, double x, double y) {
        mouseX = x;
        mouseY = y;
        if (mouseDown) {
            float ar = (float) width / height;
            view.translateLocal((float) (mouseX - mouseDownX) / width * ar * 2.0f, -(float) (mouseY - mouseDownY) / height * 2.0f);
            mouseDownX = mouseX;
            mouseDownY = mouseY;
        }
    }

    private static void onMouseButton(long window, int button, int action, int mods) {
        if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_PRESS) {
            mouseDown = true;
            mouseDownX = mouseX;
            mouseDownY = mouseY;
        } else if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_RELEASE) {
            mouseDown = false;
        }
    }

    private static void registerWindowCallbacks() {
        glfwSetFramebufferSizeCallback(window, GameOfLife::onFramebufferSize);
        glfwSetKeyCallback(window, GameOfLife::onKey);
        glfwSetScrollCallback(window, GameOfLife::onScroll);
        glfwSetCursorPosCallback(window, GameOfLife::onMouseMove);
        glfwSetMouseButtonCallback(window, GameOfLife::onMouseButton);
    }

    private static int createShader(String resource, int type, Map<String, String> defines) throws IOException {
        int shader = glCreateShader(type);
        try (InputStream is = GameOfLife.class.getClassLoader().getResourceAsStream(resource);
                InputStreamReader isr = new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr)) {
            String lines = br.lines().collect(Collectors.joining("\n"));
            lines = lines.replace("#pragma {{DEFINES}}",
                    defines.entrySet().stream().map(e -> "#define " + e.getKey() + " " + e.getValue()).collect(Collectors.joining("\n")));
            glShaderSource(shader, lines);
        }
        glCompileShader(shader);
        if (DEBUG) {
            int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
            String log = glGetShaderInfoLog(shader);
            if (log.trim().length() > 0)
                System.err.println(log);
            if (compiled == 0)
                throw new AssertionError("Could not compile shader: " + resource);
        }
        return shader;
    }

    private static void createVao() {
        vao = glGenVertexArrays();
    }

    private static void createIterationProgram() throws IOException {
        int program = glCreateProgram();
        Map<String, String> defines = new HashMap<>();
        defines.put("WX", WORK_GROUP_SIZE_X + "u");
        defines.put("WY", WORK_GROUP_SIZE_Y + "u");
        int cshader = createShader("org/lwjgl/demo/opengl/shader/gameoflife/iteration.cs.glsl", GL_COMPUTE_SHADER, defines);
        glAttachShader(program, cshader);
        glLinkProgram(program);
        glDeleteShader(cshader);
        if (DEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        iterationProgram = program;
    }

    private static void createRenderProgram() throws IOException {
        int program = glCreateProgram();
        Map<String, String> defines = new HashMap<>();
        int vshader = createShader("org/lwjgl/demo/opengl/shader/gameoflife/render.vs.glsl", GL_VERTEX_SHADER, defines);
        int fshader = createShader("org/lwjgl/demo/opengl/shader/gameoflife/render.fs.glsl", GL_FRAGMENT_SHADER, defines);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        glDeleteShader(vshader);
        glDeleteShader(fshader);
        if (DEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "tex"), 0);
        renderProgramMatUniform = glGetUniformLocation(program, "mat");
        glUseProgram(0);
        renderProgram = program;
    }

    private static void set(int x, int y, ByteBuffer bb) {
        bb.put(x + y * MAX_NUM_CELLS_X, (byte) 1);
    }

    private static void loadPattern(int x, int y, GolPattern pattern, ByteBuffer bb) {
        for (Vector2i c : pattern.points)
            set(x + c.x, y + c.y, bb);
    }

    private static GolPattern choosePattern(Random rnd) {
        return patterns.get(rnd.nextInt(patterns.size() - 1) + 1);
    }

    private static void loadPatterns() throws IOException {
        try (InputStream is = GameOfLife.class.getResourceAsStream("gameoflife/patterns.txt");
                Reader r = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(r)) {
            String line;
            Pattern p = Pattern.compile("(.+?)\\s(\\d+)\\s(\\d+)(\\s(-?\\d+)/(\\d+)\\s(\\d+)/(\\d+))?");
            while ((line = br.readLine()) != null) {
                Matcher m = p.matcher(line);
                if (!m.find())
                    throw new AssertionError();
                int width = parseInt(m.group(2)), height = parseInt(m.group(3));
                GolPattern pat = new GolPattern();
                pat.height = height;
                ByteBuffer buf = ioResourceToByteBuffer(GameOfLife.class.getPackage().getName().replace('.', '/') + "/gameoflife/" + m.group(1), 8192);
                try (MemoryStack stack = stackPush()) {
                    IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
                    ByteBuffer mem = stbi_load_from_memory(buf, w, h, c, 3);
                    for (int y = 0; y < height; y++)
                        for (int x = 0; x < width; x++) {
                            int px = (int) ((float) (0.5f + x) / width * w.get(0));
                            int py = (int) ((float) (0.5f + y) / height * h.get(0));
                            if (mem.get(3 * (px + py * w.get(0))) != -1 || mem.get(3 * (px + py * w.get(0)) + 1) != -1
                                    || mem.get(3 * (px + py * w.get(0)) + 2) != -1)
                                pat.points.add(new Vector2i(x, y));
                        }
                    stbi_image_free(mem);
                    patterns.add(pat);
                }
            }
        }
    }

    private static void computeNextState() {
        glUseProgram(iterationProgram);
        glBindImageTexture(0, textures[readTexIndex], 0, false, 0, GL_READ_ONLY, GL_R8UI);
        glBindImageTexture(1, textures[1 - readTexIndex], 0, false, 0, GL_WRITE_ONLY, GL_R8UI);
        int numWorkGroupsX = MAX_NUM_CELLS_X / WORK_GROUP_SIZE_X;
        int numWorkGroupsY = MAX_NUM_CELLS_Y / WORK_GROUP_SIZE_Y;
        glDispatchCompute(numWorkGroupsX, numWorkGroupsY, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    private static void draw() {
        glUseProgram(renderProgram);
        try (MemoryStack stack = stackPush()) {
            float ar = (float) width / height;
            proj.identity().view(-1.0f * ar, 1.0f * ar, -1, +1).mul(view);
            glUniformMatrix4fv(renderProgramMatUniform, false, proj.get4x4(stack.mallocFloat(16)));
        }
        glBindTexture(GL_TEXTURE_2D, textures[readTexIndex]);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void initState() {
        glBindTexture(GL_TEXTURE_2D, textures[readTexIndex]);
        ByteBuffer bb = memCalloc(MAX_NUM_CELLS_X * MAX_NUM_CELLS_Y);
        Random rnd = new Random();
        for (int x = 0; x < MAX_NUM_CELLS_X - 40; x += 39)
            loadPattern(x, 300, patterns.get(0), bb);
        for (int x = 0; x < MAX_NUM_CELLS_X - 40; x += 80) {
            int incr = 0;
            for (int y = 600; y < MAX_NUM_CELLS_Y - 200; y += incr) {
                GolPattern p = choosePattern(rnd);
                loadPattern(x, y, p, bb);
                incr = p.height + 80;
            }
        }
        bb.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, MAX_NUM_CELLS_X, MAX_NUM_CELLS_Y, GL_RED_INTEGER, GL_UNSIGNED_BYTE, bb);
        memFree(bb);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public static void main(String[] args) throws IOException {
        init();
        loop();
        destroy();
    }

    private static void init() throws AssertionError, IOException {
        initGlfw();
        createWindow();
        registerWindowCallbacks();
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        determineOpenGLCapabilities();
        if (DEBUG)
            installDebugCallback();
        createTextures();
        createIterationProgram();
        createRenderProgram();
        createVao();
        loadPatterns();
        initState();
        glFlush();
        glFinish();
        glfwShowWindow(window);
    }

    private static void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            if (!stopped || step)
                computeNextState();
            glClear(GL_COLOR_BUFFER_BIT);
            draw();
            glfwSwapBuffers(window);
            if (!stopped || step)
                readTexIndex = 1 - readTexIndex;
            step = false;
        }
    }

    private static void destroy() {
        GL.setCapabilities(null);
        if (debugProc != null)
            debugProc.free();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static void createWindow() {
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        if (DEBUG) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        }
        window = glfwCreateWindow(width, height, "Conway's Game of Life", NULL, NULL);
        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }

    private static void initGlfw() throws AssertionError {
        if (!glfwInit())
            throw new AssertionError("Failed to initialize GLFW");
    }

}
