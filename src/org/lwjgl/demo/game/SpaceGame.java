/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.game;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.opengl.util.WavefrontMeshLoader;
import org.lwjgl.demo.opengl.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.libffi.Closure;
import org.joml.GeometryUtils;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4d;

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
import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBEasyFont.stb_easy_font_print;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A little 3D space shooter.
 * 
 * @author Kai Burjack
 */
public class SpaceGame {
    private static class SpaceCamera {
        public Vector3f linearAcc = new Vector3f();
        public Vector3f linearVel = new Vector3f();
        public float linearDamping = 0.08f;

        /** ALWAYS rotation about the local XYZ axes of the camera! */
        public Vector3f angularAcc = new Vector3f();
        public Vector3f angularVel = new Vector3f();
        public float angularDamping = 0.5f;

        public Vector3d position = new Vector3d(0, 0, 10);
        public Quaternionf rotation = new Quaternionf();

        public SpaceCamera update(float dt) {
            // update linear velocity based on linear acceleration
            linearVel.fma(dt, linearAcc);
            // update angular velocity based on angular acceleration
            angularVel.fma(dt, angularAcc);
            // update the rotation based on the angular velocity
            rotation.integrate(dt, angularVel.x, angularVel.y, angularVel.z);
            angularVel.mul(1.0f - angularDamping * dt);
            // update position based on linear velocity
            position.fma(dt, linearVel);
            linearVel.mul(1.0f - linearDamping * dt);
            return this;
        }
        public Vector3f right(Vector3f dest) {
            return rotation.positiveX(dest);
        }
        public Vector3f up(Vector3f dest) {
            return rotation.positiveY(dest);
        }
        public Vector3f forward(Vector3f dest) {
            return rotation.positiveZ(dest).negate();
        }
    }

    private static float shotVelocity = 250.0f;
    private static float shotSeparation = 0.4f;
    private static int shotMilliseconds = 100;
    private static int shotOpponentMilliseconds = 200;
    private static float straveThrusterAccFactor = 20.0f;
    private static float mainThrusterAccFactor = 50.0f;
    private static float maxLinearVel = 200.0f;
    private static float maxShotLifetime = 30.0f;

    private long window;
    private int width = 800;
    private int height = 600;

    private int cubemapProgram;
    private int cubemap_invViewProjUniform;

    private int shipProgram;
    private int ship_viewUniform;
    private int ship_projUniform;
    private int ship_modelUniform;

    private int shotProgram;
    private int shot_projUniform;

    private ByteBuffer quadVertices;
    private Mesh ship;
    private Mesh sphere;
    private int shipCount = 256;
    private static float shipSpread = 1000.0f;
    private static float shipRadius = 4.0f;
    private Vector4d[] ships = new Vector4d[shipCount];
    {
        for (int i = 0; i < ships.length; i++) {
            double x = (Math.random() - 0.5) * shipSpread;
            double y = (Math.random() - 0.5) * shipSpread;
            double z = (Math.random() - 0.5) * shipSpread;
            double radius = shipRadius;
            Vector4d ship = new Vector4d(x, y, z, radius);
            ships[i] = ship;
        }
    }

    private Vector3d[] projectilePositions = new Vector3d[2048];
    private Vector4d[] projectileVelocities = new Vector4d[2048];
    {
        for (int i = 0; i < projectilePositions.length; i++) {
            Vector3d projectilePosition = new Vector3d(0, 0, 0);
            projectilePositions[i] = projectilePosition;
            Vector4d projectileVelocity = new Vector4d(0, 0, 0, 0);
            projectileVelocities[i] = projectileVelocity;
        }
    }
    private FloatBuffer shotsVertices = BufferUtils.createFloatBuffer(6 * 6 * 2048);
    private FloatBuffer crosshairVertices = BufferUtils.createFloatBuffer(6 * 2);

    private ByteBuffer charBuffer = BufferUtils.createByteBuffer(16 * 270);

    private boolean windowed = false;
    private boolean[] keyDown = new boolean[GLFW.GLFW_KEY_LAST];
    private boolean leftMouseDown = false;
    private boolean rightMouseDown = false;
    private long lastShotTime = 0L;
    private long lastOpponentShotTime = 0L;
    private int shootingShip = 0;
    private float mouseX = 0.0f;
    private float mouseY = 0.0f;
    private long lastTime = System.nanoTime();
    private SpaceCamera cam = new SpaceCamera();
    private Vector3d tmp = new Vector3d();
    private Vector3f tmp2 = new Vector3f();
    private Vector3f tmp3 = new Vector3f();
    private Vector3f tmp4 = new Vector3f();
    private Matrix4f projMatrix = new Matrix4f();
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f modelMatrix = new Matrix4f();
    private Matrix4f viewProjMatrix = new Matrix4f();
    private Matrix4f invViewProjMatrix = new Matrix4f();
    private ByteBuffer matrixByteBuffer = BufferUtils.createByteBuffer(4 * 16);

    private GLCapabilities caps;
    private GLFWKeyCallback keyCallback;
    private GLFWCursorPosCallback cpCallback;
    private GLFWMouseButtonCallback mbCallback;
    private GLFWFramebufferSizeCallback fbCallback;
    private Closure debugProc;

    private void init() throws IOException {
        if (glfwInit() != GLFW_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_SAMPLES, 4);

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vidmode = glfwGetVideoMode(monitor);
        if (!windowed) {
        width = vidmode.width();
        height = vidmode.height();
        }
        window = glfwCreateWindow(width, height, "Little Space Shooter Game", !windowed ? monitor : 0L, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }
        glfwSetCursor(window, glfwCreateStandardCursor(GLFW_CROSSHAIR_CURSOR));

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0 && (SpaceGame.this.width != width || SpaceGame.this.height != height)) {
                    SpaceGame.this.width = width;
                    SpaceGame.this.height = height;
                }
            }
        });

        System.out.println("Press W/S to move forward/backward");
        System.out.println("Press L.Ctrl/Spacebar to move down/up");
        System.out.println("Press A/D to strafe left/right");
        System.out.println("Press Q/E to roll left/right");
        System.out.println("Hold the left mouse button to shoot");
        System.out.println("Hold the right mouse button to rotate towards the mouse cursor");
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, GL_TRUE);
                }
                if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                    keyDown[key] = true;
                } else {
                    keyDown[key] = false;
                }
            }
        });
        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            public void invoke(long window, double xpos, double ypos) {
                float normX = (float) ((xpos - width/2.0) / width * 2.0);
                float normY = (float) ((ypos - height/2.0) / height * 2.0);
                SpaceGame.this.mouseX = Math.max(-width/2.0f, Math.min(width/2.0f, normX));
                SpaceGame.this.mouseY = Math.max(-height/2.0f, Math.min(height/2.0f, normY));
            }
        });
        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    if (action == GLFW_PRESS)
                        leftMouseDown = true;
                    else if (action == GLFW_RELEASE)
                        leftMouseDown = false;
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    if (action == GLFW_PRESS)
                        rightMouseDown = true;
                    else if (action == GLFW_RELEASE)
                        rightMouseDown = false;
                }
            }
        });
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);
        caps = GL.createCapabilities();
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
        createCubemapTexture();
        createFullScreenQuad();
        createCubemapProgram();
        createShipProgram();
        createShip();
        createShotProgram();
        createSphere();

        glEnableClientState(GL_VERTEX_ARRAY);
        glEnable(GL_DEPTH_TEST);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
    }

    private void createFullScreenQuad() {
        quadVertices = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = quadVertices.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put( 1.0f).put(-1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put( 1.0f).put( 1.0f);
        fv.put(-1.0f).put( 1.0f);
        fv.put(-1.0f).put(-1.0f);
    }

    private void createShip() throws IOException {
        WavefrontMeshLoader loader = new WavefrontMeshLoader();
        ship = loader.loadMesh("org/lwjgl/demo/game/ship.obj.zip");
    }

    private void createSphere() throws IOException {
        WavefrontMeshLoader loader = new WavefrontMeshLoader();
        sphere = loader.loadMesh("org/lwjgl/demo/game/sphere.obj.zip");
    }

    private static int createShader(String resource, int type) throws IOException {
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

    private static int createProgram(int vshader, int fshader) {
        int program = glCreateProgramObjectARB();
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
        return program;
    }

    private void createCubemapProgram() throws IOException {
        int vshader = createShader("org/lwjgl/demo/game/cubemap.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/game/cubemap.fs", GL_FRAGMENT_SHADER_ARB);
        int program = createProgram(vshader, fshader);
        glUseProgramObjectARB(program);
        int texLocation = glGetUniformLocationARB(program, "tex");
        glUniform1iARB(texLocation, 0);
        cubemap_invViewProjUniform = glGetUniformLocationARB(program, "invViewProj");
        glUseProgramObjectARB(0);
        cubemapProgram = program;
    }

    private void createShipProgram() throws IOException {
        int vshader = createShader("org/lwjgl/demo/game/ship.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/game/ship.fs", GL_FRAGMENT_SHADER_ARB);
        int program = createProgram(vshader, fshader);
        glUseProgramObjectARB(program);
        ship_viewUniform = glGetUniformLocationARB(program, "view");
        ship_projUniform = glGetUniformLocationARB(program, "proj");
        ship_modelUniform = glGetUniformLocationARB(program, "model");
        glUseProgramObjectARB(0);
        shipProgram = program;
    }

    private void createShotProgram() throws IOException {
        int vshader = createShader("org/lwjgl/demo/game/shot.vs", GL_VERTEX_SHADER_ARB);
        int fshader = createShader("org/lwjgl/demo/game/shot.fs", GL_FRAGMENT_SHADER_ARB);
        int program = createProgram(vshader, fshader);
        glUseProgramObjectARB(program);
        shot_projUniform = glGetUniformLocationARB(program, "proj");
        glUseProgramObjectARB(0);
        shotProgram = program;
    }

    private void createCubemapTexture() throws IOException {
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
            glTexParameterf(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
        }
        if (caps.OpenGL14) {
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL14.GL_GENERATE_MIPMAP, GL_TRUE);
        } else {
            glTexParameteri(GL_TEXTURE_CUBE_MAP_ARB, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        }
        for (int i = 0; i < 6; i++) {
            imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/space_" + names[i] + (i + 1) + ".jpg", 8 * 1024);
            if (stbi_info_from_memory(imageBuffer, w, h, comp) == 0)
                throw new IOException("Failed to read image information: " + stbi_failure_reason());
            image = stbi_load_from_memory(imageBuffer, w, h, comp, 0);
            if (image == null)
                throw new IOException("Failed to load image: " + stbi_failure_reason());
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X_ARB + i, 0, GL_RGB8, w.get(0), h.get(0), 0, GL_RGB, GL_UNSIGNED_BYTE, image);
            stbi_image_free(image);
        }
        if (caps.OpenGL32 || caps.GL_ARB_seamless_cube_map) {
            glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        }
    }

    private void update() {
        long thisTime = System.nanoTime();
        float dt = (thisTime - lastTime) / 1E9f;
        lastTime = thisTime;
        updateShots(dt);
        cam.update(dt);

        projMatrix.setPerspective((float) Math.toRadians(40.0f), (float) width / height, 0.1f, 5000.0f);
        viewMatrix.set(cam.rotation);
        invViewProjMatrix.set(projMatrix).mul(viewMatrix).invert();
        viewProjMatrix.set(projMatrix).mul(viewMatrix);

        /* Update the background shader */
        glUseProgramObjectARB(cubemapProgram);
        glUniformMatrix4fvARB(cubemap_invViewProjUniform, 1, false, invViewProjMatrix.get(matrixByteBuffer));

        /* Update the ship shader */
        glUseProgramObjectARB(shipProgram);
        glUniformMatrix4fvARB(ship_viewUniform, 1, false, viewMatrix.get(matrixByteBuffer));
        glUniformMatrix4fvARB(ship_projUniform, 1, false, projMatrix.get(matrixByteBuffer));

        /* Update the shot shader */
        glUseProgramObjectARB(shotProgram);
        glUniformMatrix4fvARB(shot_projUniform, 1, false, projMatrix.get(matrixByteBuffer));

        updateControls();

        /* Let the player shoot a bullet */
        if (leftMouseDown && (thisTime - lastShotTime >= 1E6 * shotMilliseconds)) {
            shoot();
            lastShotTime = thisTime;
        }
        /* Let the opponent shoot a bullet */
        if (thisTime - lastOpponentShotTime >= 1E6 * shotOpponentMilliseconds) {
            shootFromShip(shootingShip);
            lastOpponentShotTime = thisTime;
        }
    }

    private void updateControls() {
        cam.linearAcc.zero();
        float rotZ = 0.0f;
        if (keyDown[GLFW_KEY_W])
            cam.linearAcc.fma(mainThrusterAccFactor, cam.forward(tmp2));
        if (keyDown[GLFW_KEY_S])
            cam.linearAcc.fma(-mainThrusterAccFactor, cam.forward(tmp2));
        if (keyDown[GLFW_KEY_D])
            cam.linearAcc.fma(straveThrusterAccFactor, cam.right(tmp2));
        if (keyDown[GLFW_KEY_A])
            cam.linearAcc.fma(-straveThrusterAccFactor, cam.right(tmp2));
        if (keyDown[GLFW_KEY_Q])
            rotZ = -1.0f;
        if (keyDown[GLFW_KEY_E])
            rotZ = +1.0f;
        if (keyDown[GLFW_KEY_SPACE])
            cam.linearAcc.fma(straveThrusterAccFactor, cam.up(tmp2));
        if (keyDown[GLFW_KEY_LEFT_CONTROL])
            cam.linearAcc.fma(-straveThrusterAccFactor, cam.up(tmp2));
        if (rightMouseDown)
            cam.angularAcc.set(2.0f*mouseY*mouseY*mouseY, 2.0f*mouseX*mouseX*mouseX, rotZ);
        else if (!rightMouseDown)
            cam.angularAcc.set(0, 0, rotZ);
        double linearVelAbs = cam.linearVel.length();
        if (linearVelAbs > maxLinearVel)
            cam.linearVel.normalize().mul(maxLinearVel);
    }

    private static Vector3f intercept(Vector3d shotOrigin, float shotSpeed, Vector3d targetOrigin, Vector3f targetVel, Vector3f out) {
        float dirToTargetX = (float) (targetOrigin.x - shotOrigin.x);
        float dirToTargetY = (float) (targetOrigin.y - shotOrigin.y);
        float dirToTargetZ = (float) (targetOrigin.z - shotOrigin.z);
        float len = (float) Math.sqrt(dirToTargetX * dirToTargetX + dirToTargetY * dirToTargetY + dirToTargetZ * dirToTargetZ);
        dirToTargetX /= len;
        dirToTargetY /= len;
        dirToTargetZ /= len;
        float targetVelOrthDot = targetVel.x * dirToTargetX + targetVel.y * dirToTargetY + targetVel.z * dirToTargetZ;
        float targetVelOrthX = dirToTargetX * targetVelOrthDot;
        float targetVelOrthY = dirToTargetY * targetVelOrthDot;
        float targetVelOrthZ = dirToTargetZ * targetVelOrthDot;
        float targetVelTangX = targetVel.x - targetVelOrthX;
        float targetVelTangY = targetVel.y - targetVelOrthY;
        float targetVelTangZ = targetVel.z - targetVelOrthZ;
        float shotVelSpeed = (float) Math.sqrt(targetVelTangX * targetVelTangX + targetVelTangY * targetVelTangY + targetVelTangZ * targetVelTangZ);
        if (shotVelSpeed > shotSpeed) {
            return null;
        }
        float shotSpeedOrth = (float) Math.sqrt(shotSpeed * shotSpeed - shotVelSpeed * shotVelSpeed);
        float shotVelOrthX = dirToTargetX * shotSpeedOrth;
        float shotVelOrthY = dirToTargetY * shotSpeedOrth;
        float shotVelOrthZ = dirToTargetZ * shotSpeedOrth;
        return out.set(shotVelOrthX + targetVelTangX, shotVelOrthY + targetVelTangY, shotVelOrthZ + targetVelTangZ).normalize();
    }

    private void shootFromShip(int index) {
        Vector4d ship = ships[index];
        if (ship == null)
            return;
        Vector3d shotPos = tmp.set(ship.x, ship.y, ship.z).sub(cam.position).negate().normalize().mul(1.01f * ship.w).add(ship.x, ship.y, ship.z);
        Vector3f icept = intercept(shotPos, shotVelocity, cam.position, cam.linearVel, tmp2);
        if (icept == null)
            return;
        // jitter the direction a bit
        GeometryUtils.perpendicular(icept, tmp3, tmp4);
        icept.fma(((float)Math.random() * 2.0f - 1.0f) * 0.01f, tmp3);
        icept.fma(((float)Math.random() * 2.0f - 1.0f) * 0.01f, tmp4);
        icept.normalize();
        for (int i = 0; i < projectilePositions.length; i++) {
            Vector3d projectilePosition = projectilePositions[i];
            Vector4d projectileVelocity = projectileVelocities[i];
            if (projectileVelocity.w <= 0.0f) {
                projectilePosition.set(shotPos);
                projectileVelocity.x = tmp2.x * shotVelocity;
                projectileVelocity.y = tmp2.y * shotVelocity;
                projectileVelocity.z = tmp2.z * shotVelocity;
                projectileVelocity.w = 0.01f;
                break;
            }
        }
    }

    private void shoot() {
        boolean firstShot = false;
        for (int i = 0; i < projectilePositions.length; i++) {
            Vector3d projectilePosition = projectilePositions[i];
            Vector4d projectileVelocity = projectileVelocities[i];
            invViewProjMatrix.transformProject(tmp2.set(mouseX, -mouseY, 1.0f)).normalize();
            if (projectileVelocity.w <= 0.0f) {
                projectileVelocity.x = cam.linearVel.x + tmp2.x * shotVelocity;
                projectileVelocity.y = cam.linearVel.y + tmp2.y * shotVelocity;
                projectileVelocity.z = cam.linearVel.z + tmp2.z * shotVelocity;
                projectileVelocity.w = 0.01f;
                if (!firstShot) {
                    projectilePosition.set(cam.right(tmp3)).mul(shotSeparation).add(cam.position);
                    firstShot = true;
                } else {
                    projectilePosition.set(cam.right(tmp3)).mul(-shotSeparation).add(cam.position);
                    break;
                }
            }
        }
    }

    private void drawCubemap() {
        glUseProgramObjectARB(cubemapProgram);
        glVertexPointer(2, GL_FLOAT, 0, quadVertices);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private void drawShips() {
        glUseProgramObjectARB(shipProgram);
        glVertexPointer(3, GL_FLOAT, 0, ship.positions);
        glEnableClientState(GL_NORMAL_ARRAY);
        glNormalPointer(GL_FLOAT, 0, ship.normals);
        for (int i = 0; i < ships.length; i++) {
            Vector4d ship = ships[i];
            if (ship == null)
                continue;
            modelMatrix.translation(
                    (float)(ship.x - cam.position.x),
                    (float)(ship.y - cam.position.y),
                    (float)(ship.z - cam.position.z));
            modelMatrix.scale((float) ship.w);
            glUniformMatrix4fvARB(ship_modelUniform, 1, false, modelMatrix.get(matrixByteBuffer));
            glDrawArrays(GL_TRIANGLES, 0, this.ship.numVertices);
        }
        glDisableClientState(GL_NORMAL_ARRAY);
    }

    private void drawShots() {
        float shotSize = 0.1f;
        glUseProgramObjectARB(shotProgram);
        shotsVertices.clear();
        int num = 0;
        for (int i = 0; i < projectilePositions.length; i++) {
            Vector3d projectilePosition = projectilePositions[i];
            Vector4d projectileVelocity = projectileVelocities[i];
            if (projectileVelocity.w > 0.0f) {
                float x = (float) (projectilePosition.x - cam.position.x);
                float y = (float) (projectilePosition.y - cam.position.y);
                float z = (float) (projectilePosition.z - cam.position.z);
                float w = (float) projectileVelocity.w;
                viewMatrix.transformPosition(tmp2.set(x, y, z));
                shotsVertices.put(tmp2.x - shotSize).put(tmp2.y - shotSize).put(tmp2.z).put(w).put(-1).put(-1);
                shotsVertices.put(tmp2.x + shotSize).put(tmp2.y - shotSize).put(tmp2.z).put(w).put( 1).put(-1);
                shotsVertices.put(tmp2.x + shotSize).put(tmp2.y + shotSize).put(tmp2.z).put(w).put( 1).put( 1);
                shotsVertices.put(tmp2.x + shotSize).put(tmp2.y + shotSize).put(tmp2.z).put(w).put( 1).put( 1);
                shotsVertices.put(tmp2.x - shotSize).put(tmp2.y + shotSize).put(tmp2.z).put(w).put(-1).put( 1);
                shotsVertices.put(tmp2.x - shotSize).put(tmp2.y - shotSize).put(tmp2.z).put(w).put(-1).put(-1);
                num++;
            }
        }
        shotsVertices.flip();
        if (num > 0) {
            glDepthMask(false);
            glEnable(GL_BLEND);
            glVertexPointer(4, GL_FLOAT, 6*4, shotsVertices);
            shotsVertices.position(4);
            glTexCoordPointer(2, GL_FLOAT, 6*4, shotsVertices);
            shotsVertices.position(0);
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glDrawArrays(GL_TRIANGLES, 0, num * 6);
            glDisableClientState(GL_TEXTURE_COORD_ARRAY);
            glDisable(GL_BLEND);
            glDepthMask(true);
        }
    }

    private void drawVelocityCompass() {
        glUseProgramObjectARB(0);
        glEnable(GL_BLEND);
        glVertexPointer(3, GL_FLOAT, 0, sphere.positions);
        glEnableClientState(GL_NORMAL_ARRAY);
        glNormalPointer(GL_FLOAT, 0, sphere.normals);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadMatrixf(projMatrix.get(matrixByteBuffer));
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(0, -1, -4);
        glMultMatrixf(viewMatrix.get(matrixByteBuffer));
        glScalef(0.3f, 0.3f, 0.3f);
        glColor4f(0.1f, 0.1f, 0.1f, 0.2f);
        glDisable(GL_DEPTH_TEST);
        glDrawArrays(GL_TRIANGLES, 0, sphere.numVertices);
        glEnable(GL_DEPTH_TEST);
        glBegin(GL_LINES);
        glColor4f(1, 0, 0, 1);
        glVertex3f(0, 0, 0);
        glVertex3f(1, 0, 0);
        glColor4f(0, 1, 0, 1);
        glVertex3f(0, 0, 0);
        glVertex3f(0, 1, 0);
        glColor4f(0, 0, 1, 1);
        glVertex3f(0, 0, 0);
        glVertex3f(0, 0, 1);
        glColor4f(1, 1, 1, 1);
        glVertex3f(0, 0, 0);
        glVertex3f(cam.linearVel.x/maxLinearVel, cam.linearVel.y/maxLinearVel, cam.linearVel.z/maxLinearVel);
        glEnd();
        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glDisableClientState(GL_NORMAL_ARRAY);
        glDisable(GL_BLEND);
    }

    private void drawCrosshair() {
        glUseProgramObjectARB(0);
        Vector4d enemyShip = ships[shootingShip];
        if (enemyShip == null)
            return;
        Vector3d targetOrigin = tmp;
        targetOrigin.set(enemyShip.x, enemyShip.y, enemyShip.z);
        Vector3f interceptorDir = intercept(cam.position, shotVelocity, targetOrigin, tmp3.set(cam.linearVel).negate(), tmp2);
        viewMatrix.transformDirection(interceptorDir);
        if (interceptorDir.z > 0.0)
            return;
        projMatrix.transformProject(interceptorDir);
        float crosshairSize = 0.01f;
        float xs = crosshairSize * height / width;
        float ys = crosshairSize;
        crosshairVertices.clear();
        crosshairVertices.put(interceptorDir.x - xs).put(interceptorDir.y - ys);
        crosshairVertices.put(interceptorDir.x + xs).put(interceptorDir.y - ys);
        crosshairVertices.put(interceptorDir.x + xs).put(interceptorDir.y + ys);
        crosshairVertices.put(interceptorDir.x - xs).put(interceptorDir.y + ys);
        crosshairVertices.flip();
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glVertexPointer(2, GL_FLOAT, 0, crosshairVertices);
        glDrawArrays(GL_QUADS, 0, 4);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }

    private void drawShipCaretHud() {
        glUseProgramObjectARB(0);
        Vector4d enemyShip = ships[shootingShip];
        if (enemyShip == null)
            return;
        Vector3f targetOrigin = tmp2;
        targetOrigin.set((float) (enemyShip.x - cam.position.x),
                         (float) (enemyShip.y - cam.position.y),
                         (float) (enemyShip.z - cam.position.z));
        tmp3.set(tmp2);
        viewMatrix.transformPosition(targetOrigin);
        boolean backward = targetOrigin.z > 0.0f;
        if (backward)
            return;
        projMatrix.transformProject(targetOrigin);
        if (targetOrigin.x < -1.0f)
            targetOrigin.x = -1.0f;
        if (targetOrigin.x > 1.0f)
            targetOrigin.x = 1.0f;
        if (targetOrigin.y < -1.0f)
            targetOrigin.y = -1.0f;
        if (targetOrigin.y > 1.0f)
            targetOrigin.y = 1.0f;
        float crosshairSize = 0.03f;
        float xs = crosshairSize * height / width;
        float ys = crosshairSize;
        crosshairVertices.clear();
        crosshairVertices.put(targetOrigin.x - xs).put(targetOrigin.y - ys);
        crosshairVertices.put(targetOrigin.x + xs).put(targetOrigin.y - ys);
        crosshairVertices.put(targetOrigin.x + xs).put(targetOrigin.y + ys);
        crosshairVertices.put(targetOrigin.x - xs).put(targetOrigin.y + ys);
        crosshairVertices.flip();
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glVertexPointer(2, GL_FLOAT, 0, crosshairVertices);
        glDrawArrays(GL_QUADS, 0, 4);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        // Draw distance text of enemy
        int quads = stb_easy_font_print(0, 0, Integer.toString((int)(tmp3.length())), null, charBuffer);
        glVertexPointer(2, GL_FLOAT, 16, charBuffer);
        glPushMatrix();
        // Scroll
        glTranslatef(targetOrigin.x, targetOrigin.y - crosshairSize * 1.1f, 0f);
        float aspect = (float)width / height;
        glScalef(1.0f / 500.0f, -1.0f / 500.0f * aspect, 0.0f);
        glDrawArrays(GL_QUADS, 0, quads * 4);
        glPopMatrix();
    }

    private void updateShots(float dt) {
        projectiles: for (int i = 0; i < projectilePositions.length; i++) {
            Vector3d projectilePosition = projectilePositions[i];
            for (int r = 0; r < shipCount; r++) {
                Vector4d ship = ships[r];
                if (ship == null)
                    continue;
                double dist = (ship.x - projectilePosition.x) * (ship.x - projectilePosition.x) + 
                        (ship.y - projectilePosition.y) * (ship.y - projectilePosition.y) +
                        (ship.z - projectilePosition.z) * (ship.z - projectilePosition.z);
                if (dist < ship.w * ship.w) {
                    ships[r] = null;
                    projectileVelocities[i].w = 0.0f;
                    if (r == shootingShip) {
                        for (int sr = 0; sr < shipCount; sr++) {
                            if (ships[sr] != null) {
                                shootingShip = sr;
                                break;
                            }
                        }
                    }
                    continue projectiles;
                }
            }

            Vector4d projectileVelocity = projectileVelocities[i];
            if (projectileVelocity.w > maxShotLifetime) {
                projectileVelocity.w = 0.0f;
            } else if (projectileVelocity.w > 0.0f) {
                projectileVelocity.w += dt;
                tmp.set(projectileVelocity.x, projectileVelocity.y, projectileVelocity.z).mul(dt).add(projectilePosition);
                projectilePosition.set(tmp);
            }
        }
    }

    private void render() {
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        drawShips();
        drawCubemap();
        drawShots();
        drawCrosshair();
        drawShipCaretHud();
        drawVelocityCompass();
    }

    private void loop() {
        while (glfwWindowShouldClose(window) == GL_FALSE) {
            glfwPollEvents();
            glViewport(0, 0, width, height);
            update();
            render();
            glfwSwapBuffers(window);
        }
    }

    private void run() {
        try {
            init();
            loop();

            if (debugProc != null)
                debugProc.release();

            keyCallback.release();
            cpCallback.release();
            mbCallback.release();
            fbCallback.release();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new SpaceGame().run();
    }

}