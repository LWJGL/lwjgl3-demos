/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.bgfx;

import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;
import org.lwjgl.bgfx.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;

import static org.lwjgl.bgfx.BGFX.*;
import static org.lwjgl.system.APIUtil.apiLog;
import static org.lwjgl.system.MemoryUtil.*;

@SuppressWarnings("StaticNonFinalField")
final class BGFXDemoUtil {

    private static int renderer = -1;
    private static boolean zZeroToOne;

    private static BGFXReleaseFunctionCallback releaseMemoryCb = BGFXReleaseFunctionCallback.create((_ptr, _userData) -> nmemFree(_ptr));

    private BGFXDemoUtil() {
    }

    static void configure(int renderer) {
        BGFXDemoUtil.renderer = renderer;
        BGFXDemoUtil.zZeroToOne = !bgfx_get_caps().homogeneousDepth();
    }

    static void dispose() {
        releaseMemoryCb.free();
    }

    static BGFXVertexLayout createVertexLayout(boolean withNormals, boolean withColor, int numUVs) {

        BGFXVertexLayout layout = BGFXVertexLayout.calloc();

        bgfx_vertex_layout_begin(layout, renderer);

        bgfx_vertex_layout_add(layout,
                BGFX_ATTRIB_POSITION,
                3,
                BGFX_ATTRIB_TYPE_FLOAT,
                false,
                false);

        if (withNormals) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_NORMAL,
                    3,
                    BGFX_ATTRIB_TYPE_FLOAT,
                    false,
                    false);
        }

        if (withColor) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_COLOR0,
                    4,
                    BGFX_ATTRIB_TYPE_UINT8,
                    true,
                    false);
        }

        if (numUVs > 0) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_TEXCOORD0,
                    2,
                    BGFX_ATTRIB_TYPE_FLOAT,
                    false,
                    false);
        }

        bgfx_vertex_layout_end(layout);

        return layout;
    }

    static short createVertexBuffer(ByteBuffer buffer, BGFXVertexLayout layout, Object[][] vertices) {

        for (Object[] vtx : vertices) {
            for (Object attr : vtx) {
                if (attr instanceof Float) {
                    buffer.putFloat((float) attr);
                } else if (attr instanceof Integer) {
                    buffer.putInt((int) attr);
                } else {
                    throw new RuntimeException("Invalid parameter type");
                }
            }
        }

        if (buffer.remaining() != 0) {
            throw new RuntimeException("ByteBuffer size and number of arguments do not match");
        }

        buffer.flip();

        return createVertexBuffer(buffer, layout);
    }

    static short createVertexBuffer(ByteBuffer buffer, BGFXVertexLayout layout) {

        BGFXMemory vbhMem = bgfx_make_ref(buffer);

        return bgfx_create_vertex_buffer(vbhMem, layout, BGFX_BUFFER_NONE);
    }

    static short createIndexBuffer(ByteBuffer buffer, int[] indices) {

        for (int idx : indices) {
            buffer.putShort((short) idx);
        }

        if (buffer.remaining() != 0) {
            throw new RuntimeException("ByteBuffer size and number of arguments do not match");
        }

        buffer.flip();

        BGFXMemory ibhMem = bgfx_make_ref(buffer);

        return bgfx_create_index_buffer(ibhMem, BGFX_BUFFER_NONE);
    }

    private static ByteBuffer loadResource(String resourcePath, String name) throws IOException {

        URL url = BGFXDemoUtil.class.getResource(resourcePath + name);

        if (url == null) {
            throw new IOException("Resource not found: " + resourcePath + "/" + name);
        }

        int resourceSize = url.openConnection().getContentLength();

        apiLog("bgfx: loading resource '" + url.getFile() + "' (" + resourceSize + " bytes)");

        ByteBuffer resource = memAlloc(resourceSize);

        try (BufferedInputStream bis = new BufferedInputStream(url.openStream())) {
            int b;
            do {
                b = bis.read();
                if (b != -1) {
                    resource.put((byte) b);
                }
            } while (b != -1);
        }

        resource.flip();

        return resource;
    }

    static short loadShader(String name) throws IOException {

        String resourcePath = "/org/lwjgl/demo/bgfx/shaders/";

        switch (renderer) {

            case BGFX_RENDERER_TYPE_DIRECT3D11:
            case BGFX_RENDERER_TYPE_DIRECT3D12:
                resourcePath += "dx11/";
                break;

            case BGFX_RENDERER_TYPE_OPENGL:
                resourcePath += "glsl/";
                break;

            case BGFX_RENDERER_TYPE_METAL:
                resourcePath += "metal/";
                break;

            default:
                throw new IOException("No demo shaders supported for " + bgfx_get_renderer_name(renderer) + " renderer");
        }

        ByteBuffer shaderCode = loadResource(resourcePath, name + ".bin");

        return bgfx_create_shader(bgfx_make_ref_release(shaderCode, releaseMemoryCb, NULL));
    }

    static short loadShader(char[] shaderCodeGLSL, char[] shaderCodeD3D9, char[] shaderCodeD3D11, char[] shaderCodeMtl) throws IOException {
        char[] sc;

        switch (renderer) {

            case BGFX_RENDERER_TYPE_DIRECT3D11:
            case BGFX_RENDERER_TYPE_DIRECT3D12:
                sc = shaderCodeD3D11;
                break;

            case BGFX_RENDERER_TYPE_OPENGL:
                sc = shaderCodeGLSL;
                break;

            case BGFX_RENDERER_TYPE_METAL:
                sc = shaderCodeMtl;
                break;

            default:
                throw new IOException("No demo shaders supported for " + bgfx_get_renderer_name(renderer) + " renderer");
        }

        ByteBuffer shaderCode = memAlloc(sc.length);

        for (char c : sc) {
            shaderCode.put((byte) c);
        }

        shaderCode.flip();

        return bgfx_create_shader(bgfx_make_ref_release(shaderCode, releaseMemoryCb, NULL));
    }

    static short loadTexture(String fileName) throws IOException {

        ByteBuffer textureData = loadResource("/org/lwjgl/demo/bgfx/textures/", fileName);

        BGFXMemory textureMemory = bgfx_make_ref_release(textureData, releaseMemoryCb, NULL);

        return bgfx_create_texture(textureMemory, BGFX_TEXTURE_NONE, 0, null);
    }

    static void reportSupportedRenderers() {
        int[] rendererTypes = new int[BGFX_RENDERER_TYPE_COUNT];
        int count = bgfx_get_supported_renderers(rendererTypes);

        apiLog("bgfx: renderers supported");

        for (int i = 0; i < count; i++) {
            apiLog("    " + bgfx_get_renderer_name(rendererTypes[i]));
        }
    }

    static void lookAt(Vector3f at, Vector3f eye, Matrix4x3f dest) {
        dest.setLookAtLH(eye.x, eye.y, eye.z, at.x, at.y, at.z, 0.0f, 1.0f, 0.0f);
    }

    static void perspective(float fov, int width, int height, float near, float far, Matrix4f dest) {
        float fovRadians = fov * (float) Math.PI / 180.0f;
        float aspect = width / (float) height;
        dest.setPerspectiveLH(fovRadians, aspect, near, far, zZeroToOne);
    }

    static void ortho(float left, float right, float bottom, float top, float zNear, float zFar, Matrix4x3f dest) {
        dest.setOrthoLH(left, right, bottom, top, zNear, zFar, zZeroToOne);
    }

}
