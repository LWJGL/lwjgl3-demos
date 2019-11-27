/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.opengl.ARBSeamlessCubeMap.*;
import static org.lwjgl.opengl.ARBTextureCubeMap.*;
import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Like {@link FullscreenCubemapDemo} but renders the black hole using a
 * regular polygon that encompasses only the circular area of influence of the
 * black hole.
 * 
 * @author Kai Burjack
 */
public class BillboardCubemapDemo {

    long window;
    int width = 1024;
    int height = 768;

    int backgroundProgram;
    int background_invViewProjUniform;
    int background_cameraPositionUniform;

    int blackholeProgram;
    int blackhole_viewProjUniform;
    int blackhole_cameraPositionUniform;
    int blackhole_blackholePositionUniform;
    int blackhole_blackholeSizeUniform;
    int blackhole_debugUniform;

    Vector3f blackholePosition = new Vector3f(1.0f, 2.0f, 0.0f);
    float blackholeSize = 5.0f;
    boolean debug;

    ByteBuffer quadVertices;
    ByteBuffer circleVertices;

    /**
     * The number of vertices of the regular polygon.
     * Must be at least 3.
     */
    int circleRefinement = 5;

    Vector3f tmp = new Vector3f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f viewProjMatrix = new Matrix4f();
    Matrix4f invViewProjMatrix = new Matrix4f();
    FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    GLCapabilities caps;
    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    Callback debugProc;
    boolean isCrappyIntel;

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 1.1 or higher.");
                delegate.invoke(error, description);
            }

            @Override
            public void free() {
                delegate.free();
            }
        });

        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Cubemap texture sampling with projected quad", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (BillboardCubemapDemo.this.width != width || BillboardCubemapDemo.this.height != height)) {
                    BillboardCubemapDemo.this.width = width;
                    BillboardCubemapDemo.this.height = height;
                }
            }
        });

        System.out.println("Press 'D' to debug the blackhole rendering.");
        System.out.println("Press 'arrow up' to increase the polygon refinement.");
        System.out.println("Press 'arrow down ' to decrease the polygon refinement.");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_D && action == GLFW_RELEASE) {
                    debug = !debug;
                } else if (key == GLFW_KEY_UP && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                    circleRefinement++;
                    createBillboardPolygon();
                    System.out.println("Circle refinement: " + circleRefinement);
                } else if (key == GLFW_KEY_DOWN && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                    circleRefinement = Math.max(circleRefinement - 1, 3);
                    createBillboardPolygon();
                    System.out.println("Circle refinement: " + circleRefinement);
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

        caps = GL.createCapabilities();

        String vendor = glGetString(GL_VENDOR);
        isCrappyIntel = vendor != null && vendor.toLowerCase().contains("intel");
        if (!caps.GL_ARB_shader_objects) {
            throw new AssertionError("This demo requires the ARB_shader_objects extension.");
        }
        if (!caps.GL_ARB_vertex_shader) {
            throw new AssertionError("This demo requires the ARB_vertex_shader extension.");
        }
        if (!caps.GL_ARB_fragment_shader) {
            throw new AssertionError("This demo requires the ARB_fragment_shader extension.");
        }
        if (!caps.GL_ARB_texture_cube_map && !caps.OpenGL13) {
            throw new AssertionError("This demo requires the ARB_texture_cube_map extension or OpenGL 1.3.");
        }

        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        createTexture();
        createFullScreenQuad();
        createBillboardPolygon();
        createBackgroundProgram();
        createBlackholeProgram();

        /* Activate vertex array */
        glEnableClientState(GL_VERTEX_ARRAY);
    }

    void createFullScreenQuad() {
        quadVertices = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = quadVertices.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put( 1.0f).put(-1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put(-1.0f).put( 1.0f);
        fv.put(-1.0f).put(-1.0f);
    }

    /**
     * This will construct a regular polygon with {@link #circleRefinement} vertices which will circumscribe the unit circle.
     * <p>
     * The draw mode will be {@link GL11#GL_TRIANGLE_FAN}.
     */
    void createBillboardPolygon() {
        // Compute the scale factor to make the regular polygon circumscribe the unit circle/sphere.
        // Reference: http://www.mathopenref.com/polygonradius.html
        float scale = 1.0f / (float) Math.cos(Math.PI / circleRefinement);

        circleVertices = BufferUtils.createByteBuffer(4 * 2 * (circleRefinement + 2));
        FloatBuffer fv = circleVertices.asFloatBuffer();
        fv.put(0.0f).put(0.0f);
        for (int i = 0; i < circleRefinement + 1; i++) {
            float angle = (float) (2.0 * Math.PI * (i % circleRefinement) / circleRefinement);
            float x = (float) Math.cos(angle) * scale;
            float y = (float) Math.sin(angle) * scale;
            fv.put(x).put(y);
        }
    }

    static int createShader(String resource, int type) throws IOException {
        int shader = glCreateShaderObjectARB(type);
        ByteBuffer source = ioResourceToByteBuffer(resource, 1024);
        PointerBuffer strings = BufferUtils.createPointerBuffer(1);
        IntBuffer lengths = BufferUtils.createIntBuffer(1);
        strings.put(0, source);
        lengths.put(0, source.remaining());
        glShaderSourceARB(shader, strings, lengths);
        glCompileShaderARB(shader);
        int compiled = glGetObjectParameteriARB(shader, GL_OBJECT_COMPILE_STATUS_ARB);
        String shaderLog = glGetInfoLogARB(shader);
        if (shaderLog.trim().length() > 0) {
            System.err.println(shaderLog);
        }
        if (compiled == 0) {
            throw new AssertionError("Could not compile shader");
        }
        return shader;
    }

    void createBackgroundProgram() throws IOException {
        int program = glCreateProgramObjectARB();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/cubemapBack.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/cubemapBack.fs", GL_FRAGMENT_SHADER_ARB);
        glAttachObjectARB(program, vshader);
        glAttachObjectARB(program, fshader);
        glLinkProgramARB(program);
        int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
        String programLog = glGetInfoLogARB(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        glUseProgramObjectARB(program);
        int texLocation = glGetUniformLocationARB(program, "tex");
        glUniform1iARB(texLocation, 0);
        background_invViewProjUniform = glGetUniformLocationARB(program, "invViewProj");
        background_cameraPositionUniform = glGetUniformLocationARB(program, "cameraPosition");
        glUseProgramObjectARB(0);
        this.backgroundProgram = program;
    }

    void createBlackholeProgram() throws IOException {
        int program = glCreateProgramObjectARB();
        int vshader = createShader("org/lwjgl/demo/opengl/textures/cubemapBH.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/opengl/textures/cubemapBH.fs", GL_FRAGMENT_SHADER_ARB);
        glAttachObjectARB(program, vshader);
        glAttachObjectARB(program, fshader);
        glLinkProgramARB(program);
        int linked = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
        String programLog = glGetInfoLogARB(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        glUseProgramObjectARB(program);
        int texLocation = glGetUniformLocationARB(program, "tex");
        glUniform1iARB(texLocation, 0);
        blackhole_viewProjUniform = glGetUniformLocationARB(program, "viewProj");
        blackhole_cameraPositionUniform = glGetUniformLocationARB(program, "cameraPosition");
        blackhole_blackholePositionUniform = glGetUniformLocationARB(program, "blackholePosition");
        blackhole_blackholeSizeUniform = glGetUniformLocationARB(program, "blackholeSize");
        blackhole_debugUniform = glGetUniformLocationARB(program, "debug");
        glUseProgramObjectARB(0);
        this.blackholeProgram = program;
    }

    void createTexture() throws IOException {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP_ARB, tex);
        glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        ByteBuffer imageBuffer;
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer comp = BufferUtils.createIntBuffer(1);
        String[] names = { "right", "left", "top", "bottom", "front", "back" };
        ByteBuffer image;
        if (caps.GL_EXT_texture_filter_anisotropic) {
            float maxAnisotropy = glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
            System.out.println("EXT_texture_filter_anisotropic available: Will use " + (int) maxAnisotropy + "x anisotropic filtering.");
            glTexParameterf(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
        } else {
            System.err.println("EXT_texture_filter_anisotropic unavailable: Distorted light might look too blurry.");
        }
        if (caps.OpenGL14) {
            System.out.println("OpenGL 1.4 available: Will use automatic mipmap generation via GL_GENERATE_MIPMAP.");
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL14.GL_GENERATE_MIPMAP, GL_TRUE);
        } else {
            System.err.println("OpenGL 1.4 unavailable: Aliasing effects might be visible (texture looks too crisp).");
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        }
        for (int i = 0; i < 6; i++) {
            imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/space_" + names[i] + (i + 1) + ".jpg", 8 * 1024);
            if (!stbi_info_from_memory(imageBuffer, w, h, comp))
                throw new IOException("Failed to read image information: " + stbi_failure_reason());
            image = stbi_load_from_memory(imageBuffer, w, h, comp, 0);
            if (image == null)
                throw new IOException("Failed to load image: " + stbi_failure_reason());
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X_ARB + i, 0, GL_RGB8, w.get(0), h.get(0), 0, GL_RGB, GL_UNSIGNED_BYTE, image);
            stbi_image_free(image);
        }
        if (caps.OpenGL32 || caps.GL_ARB_seamless_cube_map) {
            System.out.println("ARB_seamless_cube_map available: Will use seamless cubemap sampling.");
            glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        } else {
            System.err.println("ARB_seamless_cube_map unavailable: Cubemap might have seams.");
        }
    }

    float rot = 0.0f;
    long lastTime = System.nanoTime();
    void update() {
        projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
        viewMatrix.setLookAt(0.0f, 5.0f, 10.0f,
                             0.0f, 0.0f, 0.0f,
                             0.0f, 1.0f, 0.0f)
                  .rotateY(rot).rotateX(rot * 0.23f).rotateZ(rot * -0.562f)
                  .originAffine(tmp);
        viewProjMatrix.set(projMatrix).mulPerspectiveAffine(viewMatrix).invert(invViewProjMatrix);

        /* Update the background shader */
        glUseProgramObjectARB(backgroundProgram);
        glUniformMatrix4fvARB(background_invViewProjUniform, false, invViewProjMatrix.get(matrixBuffer));
        glUniform3fARB(background_cameraPositionUniform, tmp.x, tmp.y, tmp.z);

        /* Update the black hole shader */
        glUseProgramObjectARB(blackholeProgram);
        glUniformMatrix4fvARB(blackhole_viewProjUniform, false, viewProjMatrix.get(matrixBuffer));
        glUniform3fARB(blackhole_cameraPositionUniform, tmp.x, tmp.y, tmp.z);
        glUniform3fARB(blackhole_blackholePositionUniform, blackholePosition.x, blackholePosition.y, blackholePosition.z);
        glUniform1fARB(blackhole_blackholeSizeUniform, blackholeSize);
        glUniform1fARB(blackhole_debugUniform, debug ? 1.0f : 0.0f);

        long thisTime = System.nanoTime();
        float diff = (thisTime - lastTime) / 1E9f;
        lastTime = thisTime;
        rot += diff * 0.1f;
    }

    void render() {
        /*
         * Workaround an apparent bug in Intel's drivers:
         * Even though we don't need to clear the color buffer because every frame we render to the entire viewport,
         * we have to clear it, because otherwise if the window is being minimized and restored there is nothing rendered
         * anymore (just pure black).
         */
        if (isCrappyIntel)
            glClear(GL_COLOR_BUFFER_BIT);

        /* Draw the cubemap background */
        glUseProgramObjectARB(backgroundProgram);
        glVertexPointer(2, GL_FLOAT, 0, quadVertices);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        
        /* Draw a single black hole */
        glUseProgramObjectARB(blackholeProgram);
        glVertexPointer(2, GL_FLOAT, 0, circleVertices);
        glDrawArrays(GL_TRIANGLE_FAN, 0, circleRefinement+2);
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            render();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null) {
                debugProc.free();
            }

            errCallback.free();
            keyCallback.free();
            fbCallback.free();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new BillboardCubemapDemo().run();
    }

}