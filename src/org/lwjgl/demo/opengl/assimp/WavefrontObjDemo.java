/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.assimp;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexBufferObject.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Shows how to load models in Wavefront obj and mlt format with Assimp binding and render them with
 * OpenGL.
 *
 * @author Zhang Hai
 */
public class WavefrontObjDemo {

    long window;
    int width = 1024;
    int height = 768;
    int fbWidth = 1024;
    int fbHeight = 768;
    float fov = 60;
    float rotation;

    int program;
    int vertexAttribute;
    int normalAttribute;
    int modelMatrixUniform;
    int viewProjectionMatrixUniform;
    int normalMatrixUniform;
    int lightPositionUniform;
    int viewPositionUniform;
    int ambientColorUniform;
    int diffuseColorUniform;
    int specularColorUniform;

    Model model;

    Matrix4f modelMatrix = new Matrix4f().rotateY(0.5f * (float) Math.PI).scale(1.5f, 1.5f, 1.5f);
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f projectionMatrix = new Matrix4f();
    Matrix4f viewProjectionMatrix = new Matrix4f();
    Vector3f viewPosition = new Vector3f();
    Vector3f lightPosition = new Vector3f(-5f, 5f, 5f);

    private FloatBuffer modelMatrixBuffer = BufferUtils.createFloatBuffer(4 * 4);
    private FloatBuffer viewProjectionMatrixBuffer = BufferUtils.createFloatBuffer(4 * 4);
    private Matrix3f normalMatrix = new Matrix3f();
    private FloatBuffer normalMatrixBuffer = BufferUtils.createFloatBuffer(3 * 3);
    private FloatBuffer lightPositionBuffer = BufferUtils.createFloatBuffer(3);
    private FloatBuffer viewPositionBuffer = BufferUtils.createFloatBuffer(3);

    GLCapabilities caps;
    Callback debugProc;

    void init() throws IOException {

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height,
                "Wavefront obj model loading with Assimp demo", NULL, NULL);
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");

        System.out.println("Move the mouse to look around");
        System.out.println("Zoom in/out with mouse wheel");
        glfwSetFramebufferSizeCallback(window, (window, width, height) -> {
            if (width > 0 && height > 0 && (fbWidth != width || fbHeight != height)) {
                fbWidth = width;
                fbHeight = height;
            }
        });
        glfwSetWindowSizeCallback(window, (window, width, height) -> {
            if (width > 0 && height > 0 && (this.width != width || this.height != height)) {
                this.width = width;
                this.height = height;
            }
        });
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) {
                return;
            }
            if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetCursorPosCallback(window, (window, x, y) -> {
            rotation = ((float)x / width - 0.5f) * 2f * (float)Math.PI;
        });
        glfwSetScrollCallback(window, (window, xoffset, yoffset) -> {
            if (yoffset < 0) {
                fov *= 1.05f;
            } else {
                fov *= 1f / 1.05f;
            }
            if (fov < 10.0f) {
                fov = 10.0f;
            } else if (fov > 120.0f) {
                fov = 120.0f;
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwSetCursorPos(window, width / 2, height / 2);

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }

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
        debugProc = GLUtil.setupDebugMessageCallback();

        glClearColor(0f, 0f, 0f, 1f);
        glEnable(GL_DEPTH_TEST);

        /* Create all needed GL resources */
        loadModel();
        createProgram();

        /* Show window */
        glfwShowWindow(window);
    }

    void loadModel() {
        AIFileIO fileIo = AIFileIO.create()
            .OpenProc((pFileIO, fileName, openMode) -> {
                ByteBuffer data;
                String fileNameUtf8 = memUTF8(fileName);
                try {
                    data = ioResourceToByteBuffer(fileNameUtf8, 8192);
                } catch (IOException e) {
                    throw new RuntimeException("Could not open file: " + fileNameUtf8);
                }

                return AIFile.create()
                    .ReadProc((pFile, pBuffer, size, count) -> {
                        long max = Math.min(data.remaining(), size * count);
                        memCopy(memAddress(data) + data.position(), pBuffer, max);
                        return max;
                    })
                    .SeekProc((pFile, offset, origin) -> {
                        if (origin == Assimp.aiOrigin_CUR) {
                            data.position(data.position() + (int) offset);
                        } else if (origin == Assimp.aiOrigin_SET) {
                            data.position((int) offset);
                        } else if (origin == Assimp.aiOrigin_END) {
                            data.position(data.limit() + (int) offset);
                        }
                        return 0;
                    })
                    .FileSizeProc(pFile -> data.limit())
                    .address();
            })
            .CloseProc((pFileIO, pFile) -> {
                AIFile aiFile = AIFile.create(pFile);

                aiFile.ReadProc().free();
                aiFile.SeekProc().free();
                aiFile.FileSizeProc().free();
            });
        AIScene scene = aiImportFileEx("org/lwjgl/demo/opengl/assimp/magnet.obj",
                aiProcess_JoinIdenticalVertices | aiProcess_Triangulate, fileIo);
        fileIo.OpenProc().free();
        fileIo.CloseProc().free();
        if (scene == null) {
            throw new IllegalStateException(aiGetErrorString());
        }
        model = new Model(scene);
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

    void createProgram() throws IOException {

        program = glCreateProgramObjectARB();
        int vertexShader = createShader("org/lwjgl/demo/opengl/assimp/magnet.vs",
                GL_VERTEX_SHADER_ARB);
        int fragmentShader = createShader("org/lwjgl/demo/opengl/assimp/magnet.fs",
                GL_FRAGMENT_SHADER_ARB);
        glAttachObjectARB(program, vertexShader);
        glAttachObjectARB(program, fragmentShader);
        glLinkProgramARB(program);
        int linkStatus = glGetObjectParameteriARB(program, GL_OBJECT_LINK_STATUS_ARB);
        String programLog = glGetInfoLogARB(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linkStatus == 0) {
            throw new AssertionError("Could not link program");
        }

        glUseProgramObjectARB(program);
        vertexAttribute = glGetAttribLocationARB(program, "aVertex");
        glEnableVertexAttribArrayARB(vertexAttribute);
        normalAttribute = glGetAttribLocationARB(program, "aNormal");
        glEnableVertexAttribArrayARB(normalAttribute);
        modelMatrixUniform = glGetUniformLocationARB(program, "uModelMatrix");
        viewProjectionMatrixUniform = glGetUniformLocationARB(program, "uViewProjectionMatrix");
        normalMatrixUniform = glGetUniformLocationARB(program, "uNormalMatrix");
        lightPositionUniform = glGetUniformLocationARB(program, "uLightPosition");
        viewPositionUniform = glGetUniformLocationARB(program, "uViewPosition");
        ambientColorUniform = glGetUniformLocationARB(program, "uAmbientColor");
        diffuseColorUniform = glGetUniformLocationARB(program, "uDiffuseColor");
        specularColorUniform = glGetUniformLocationARB(program, "uSpecularColor");
    }

    void update() {
        projectionMatrix.setPerspective((float) Math.toRadians(fov), (float) width / height, 0.01f,
                100.0f);
        viewPosition.set(10f * (float) Math.cos(rotation), 10f, 10f * (float) Math.sin(rotation));
        viewMatrix.setLookAt(viewPosition.x, viewPosition.y, viewPosition.z, 0f, 0f, 0f, 0f, 1f,
                0f);
        projectionMatrix.mul(viewMatrix, viewProjectionMatrix);
    }

    void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glUseProgramObjectARB(program);
        for (Model.Mesh mesh : model.meshes) {

            glBindBufferARB(GL_ARRAY_BUFFER_ARB, mesh.vertexArrayBuffer);
            glVertexAttribPointerARB(vertexAttribute, 3, GL_FLOAT, false, 0, 0);
            glBindBufferARB(GL_ARRAY_BUFFER_ARB, mesh.normalArrayBuffer);
            glVertexAttribPointerARB(normalAttribute, 3, GL_FLOAT, false, 0, 0);

            glUniformMatrix4fvARB(modelMatrixUniform, false, modelMatrix.get(modelMatrixBuffer));
            glUniformMatrix4fvARB(viewProjectionMatrixUniform, false,
                    viewProjectionMatrix.get(viewProjectionMatrixBuffer));
            normalMatrix.set(modelMatrix).invert().transpose();
            glUniformMatrix3fvARB(normalMatrixUniform, false, normalMatrix.get(normalMatrixBuffer));
            glUniform3fvARB(lightPositionUniform, lightPosition.get(lightPositionBuffer));
            glUniform3fvARB(viewPositionUniform, viewPosition.get(viewPositionBuffer));

            Model.Material material = model.materials.get(mesh.mesh.mMaterialIndex());
            nglUniform3fvARB(ambientColorUniform, 1, material.mAmbientColor.address());
            nglUniform3fvARB(diffuseColorUniform, 1, material.mDiffuseColor.address());
            nglUniform3fvARB(specularColorUniform, 1, material.mSpecularColor.address());

            glBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, mesh.elementArrayBuffer);
            glDrawElements(GL_TRIANGLES, mesh.elementCount, GL_UNSIGNED_INT, 0);
        }
    }

    void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            glViewport(0, 0, fbWidth, fbHeight);
            update();
            render();
            glfwSwapBuffers(window);
        }
        GL.setCapabilities(null);
    }

    void run() {
        try {
            init();
            loop();
            model.free();
            if (debugProc != null) {
                debugProc.free();
            }
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new WavefrontObjDemo().run();
    }

    static class Model {

        public AIScene scene;
        public List<Mesh> meshes;
        public List<Material> materials;

        Model(AIScene scene) {

            this.scene = scene;

            int meshCount = scene.mNumMeshes();
            PointerBuffer meshesBuffer = scene.mMeshes();
            meshes = new ArrayList<>();
            for (int i = 0; i < meshCount; ++i) {
                meshes.add(new Mesh(AIMesh.create(meshesBuffer.get(i))));
            }

            int materialCount = scene.mNumMaterials();
            PointerBuffer materialsBuffer = scene.mMaterials();
            materials = new ArrayList<>();
            for (int i = 0; i < materialCount; ++i) {
                materials.add(new Material(AIMaterial.create(materialsBuffer.get(i))));
            }
        }

        public void free() {
            aiReleaseImport(scene);
            scene = null;
            meshes = null;
            materials = null;
        }

        public static class Mesh {

            public AIMesh mesh;
            public int vertexArrayBuffer;
            public int normalArrayBuffer;
            public int elementArrayBuffer;
            public int elementCount;

            public Mesh(AIMesh mesh) {
                this.mesh = mesh;

                vertexArrayBuffer = glGenBuffersARB();
                glBindBufferARB(GL_ARRAY_BUFFER_ARB, vertexArrayBuffer);
                AIVector3D.Buffer vertices = mesh.mVertices();
                nglBufferDataARB(GL_ARRAY_BUFFER_ARB, AIVector3D.SIZEOF * vertices.remaining(),
                        vertices.address(), GL_STATIC_DRAW_ARB);

                normalArrayBuffer = glGenBuffersARB();
                glBindBufferARB(GL_ARRAY_BUFFER_ARB, normalArrayBuffer);
                AIVector3D.Buffer normals = mesh.mNormals();
                nglBufferDataARB(GL_ARRAY_BUFFER_ARB, AIVector3D.SIZEOF * normals.remaining(),
                        normals.address(), GL_STATIC_DRAW_ARB);

                int faceCount = mesh.mNumFaces();
                elementCount = faceCount * 3;
                IntBuffer elementArrayBufferData = BufferUtils.createIntBuffer(elementCount);
                AIFace.Buffer facesBuffer = mesh.mFaces();
                for (int i = 0; i < faceCount; ++i) {
                    AIFace face = facesBuffer.get(i);
                    if (face.mNumIndices() != 3) {
                        throw new IllegalStateException("AIFace.mNumIndices() != 3");
                    }
                    elementArrayBufferData.put(face.mIndices());
                }
                elementArrayBufferData.flip();
                elementArrayBuffer = glGenBuffersARB();
                glBindBufferARB(GL_ELEMENT_ARRAY_BUFFER_ARB, elementArrayBuffer);
                glBufferDataARB(GL_ELEMENT_ARRAY_BUFFER_ARB, elementArrayBufferData,
                        GL_STATIC_DRAW_ARB);
            }
        }

        public static class Material {

            public AIMaterial mMaterial;
            public AIColor4D mAmbientColor;
            public AIColor4D mDiffuseColor;
            public AIColor4D mSpecularColor;

            public Material(AIMaterial material) {

                mMaterial = material;

                mAmbientColor = AIColor4D.create();
                if (aiGetMaterialColor(mMaterial, AI_MATKEY_COLOR_AMBIENT,
                        aiTextureType_NONE, 0, mAmbientColor) != 0) {
                    throw new IllegalStateException(aiGetErrorString());
                }
                mDiffuseColor = AIColor4D.create();
                if (aiGetMaterialColor(mMaterial, AI_MATKEY_COLOR_DIFFUSE,
                        aiTextureType_NONE, 0, mDiffuseColor) != 0) {
                    throw new IllegalStateException(aiGetErrorString());
                }
                mSpecularColor = AIColor4D.create();
                if (aiGetMaterialColor(mMaterial, AI_MATKEY_COLOR_SPECULAR,
                        aiTextureType_NONE, 0, mSpecularColor) != 0) {
                    throw new IllegalStateException(aiGetErrorString());
                }
            }
        }
    }
}
