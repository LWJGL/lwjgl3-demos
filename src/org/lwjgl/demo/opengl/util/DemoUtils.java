/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.util;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.memUTF8;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.joml.Vector3f;

/**
 * Utility methods for most of the ray tracing demos.
 * 
 * @author Kai Burjack
 */
public class DemoUtils {

    private static final Vector3f VECTOR_MINUS_ONE = new Vector3f(-1.0f, -1.0f, -1.0f);
    private static final Vector3f VECTOR_PLUS_ONE = new Vector3f(1.0f, 1.0f, 1.0f);

    /**
     * Write the vertices (position and normal) of an axis-aligned unit box into
     * the provided {@link FloatBuffer}.
     *
     * @param fv
     *            the {@link FloatBuffer} receiving the vertex position and
     *            normal
     */
    public static void triangulateUnitBox(FloatBuffer fv) {
        triangulateBox(VECTOR_MINUS_ONE, VECTOR_PLUS_ONE, fv);
    }

    /**
     * Write the vertices (position and normal) of an axis-aligned box with the
     * given corner coordinates into the provided {@link FloatBuffer}.
     *
     * @param min
     *            the min corner
     * @param max
     *            the max corner
     * @param fv
     *            the {@link FloatBuffer} receiving the vertex position and
     *            normal
     */
    public static void triangulateBox(Vector3f min, Vector3f max, FloatBuffer fv) {
        /* Front face */
        fv.put(min.x).put(min.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        fv.put(max.x).put(min.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        fv.put(max.x).put(max.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        fv.put(max.x).put(max.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        fv.put(min.x).put(max.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        fv.put(min.x).put(min.y).put(max.z).put(0.0f).put(0.0f).put(1.0f);
        /* Back face */
        fv.put(max.x).put(min.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        fv.put(min.x).put(min.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        fv.put(min.x).put(max.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        fv.put(min.x).put(max.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        fv.put(max.x).put(max.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        fv.put(max.x).put(min.y).put(min.z).put(0.0f).put(0.0f).put(-1.0f);
        /* Left face */
        fv.put(min.x).put(min.y).put(min.z).put(-1.0f).put(0.0f).put(0.0f);
        fv.put(min.x).put(min.y).put(max.z).put(-1.0f).put(0.0f).put(0.0f);
        fv.put(min.x).put(max.y).put(max.z).put(-1.0f).put(0.0f).put(0.0f);
        fv.put(min.x).put(max.y).put(max.z).put(-1.0f).put(0.0f).put(0.0f);
        fv.put(min.x).put(max.y).put(min.z).put(-1.0f).put(0.0f).put(0.0f);
        fv.put(min.x).put(min.y).put(min.z).put(-1.0f).put(0.0f).put(0.0f);
        /* Right face */
        fv.put(max.x).put(min.y).put(max.z).put(1.0f).put(0.0f).put(0.0f);
        fv.put(max.x).put(min.y).put(min.z).put(1.0f).put(0.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(min.z).put(1.0f).put(0.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(min.z).put(1.0f).put(0.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(max.z).put(1.0f).put(0.0f).put(0.0f);
        fv.put(max.x).put(min.y).put(max.z).put(1.0f).put(0.0f).put(0.0f);
        /* Top face */
        fv.put(min.x).put(max.y).put(max.z).put(0.0f).put(1.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(max.z).put(0.0f).put(1.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(min.z).put(0.0f).put(1.0f).put(0.0f);
        fv.put(max.x).put(max.y).put(min.z).put(0.0f).put(1.0f).put(0.0f);
        fv.put(min.x).put(max.y).put(min.z).put(0.0f).put(1.0f).put(0.0f);
        fv.put(min.x).put(max.y).put(max.z).put(0.0f).put(1.0f).put(0.0f);
        /* Bottom face */
        fv.put(min.x).put(min.y).put(min.z).put(0.0f).put(-1.0f).put(0.0f);
        fv.put(max.x).put(min.y).put(min.z).put(0.0f).put(-1.0f).put(0.0f);
        fv.put(max.x).put(min.y).put(max.z).put(0.0f).put(-1.0f).put(0.0f);
        fv.put(max.x).put(min.y).put(max.z).put(0.0f).put(-1.0f).put(0.0f);
        fv.put(min.x).put(min.y).put(max.z).put(0.0f).put(-1.0f).put(0.0f);
        fv.put(min.x).put(min.y).put(min.z).put(0.0f).put(-1.0f).put(0.0f);
    }

    /**
     * Create a shader object from the given classpath resource.
     *
     * @param resource
     *            the class path
     * @param type
     *            the shader type
     *
     * @return the shader object id
     *
     * @throws IOException
     */
    public static int createShader(String resource, int type) throws IOException {
        return createShader(resource, type, null);
    }

    private static ByteBuffer resizeBuffer(ByteBuffer buffer, int newCapacity) {
        ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }

    /**
     * Reads the specified resource and returns the raw data as a ByteBuffer.
     *
     * @param resource   the resource to read
     * @param bufferSize the initial buffer size
     *
     * @return the resource data
     *
     * @throws IOException if an IO error occurs
     */
    public static ByteBuffer ioResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;
        URL url = Thread.currentThread().getContextClassLoader().getResource(resource);
        File file = new File(url.getFile());
        if (file.isFile()) {
            FileInputStream fis = new FileInputStream(file);
            FileChannel fc = fis.getChannel();
            buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            fc.close();
            fis.close();
        } else {
            buffer = BufferUtils.createByteBuffer(bufferSize);
            InputStream source = url.openStream();
            if (source == null)
                throw new FileNotFoundException(resource);
            try {
                byte[] buf = new byte[8192];
                while (true) {
                    int bytes = source.read(buf, 0, buf.length);
                    if (bytes == -1)
                        break;
                    if (buffer.remaining() < bytes)
                        buffer = resizeBuffer(buffer, buffer.capacity() * 2);
                    buffer.put(buf, 0, bytes);
                }
                buffer.flip();
            } finally {
                source.close();
            }
        }
        return buffer;
    }

    /**
     * Create a shader object from the given classpath resource.
     *
     * @param resource
     *            the class path
     * @param type
     *            the shader type
     * @param version
     *            the GLSL version to prepend to the shader source, or null
     *
     * @return the shader object id
     *
     * @throws IOException
     */
    public static int createShader(String resource, int type, String version) throws IOException {
        int shader = glCreateShader(type);

        ByteBuffer source = ioResourceToByteBuffer(resource, 8192);

        if ( version == null ) {
            PointerBuffer strings = BufferUtils.createPointerBuffer(1);
            IntBuffer lengths = BufferUtils.createIntBuffer(1);

            strings.put(0, source);
            lengths.put(0, source.remaining());

            glShaderSource(shader, strings, lengths);
        } else {
            PointerBuffer strings = BufferUtils.createPointerBuffer(2);
            IntBuffer lengths = BufferUtils.createIntBuffer(2);

            ByteBuffer preamble = memUTF8("#version " + version + "\n", false);

            strings.put(0, preamble);
            lengths.put(0, preamble.remaining());

            strings.put(1, source);
            lengths.put(1, source.remaining());

            glShaderSource(shader, strings, lengths);
        }

        glCompileShader(shader);
        int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
        String shaderLog = glGetShaderInfoLog(shader);
        if (shaderLog.trim().length() > 0) {
            System.err.println(shaderLog);
        }
        if (compiled == 0) {
            throw new AssertionError("Could not compile shader");
        }
        return shader;
    }

}
