/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.lwjgl.BufferUtils;

import org.joml.Vector3f;

/**
 * A simple Wavefront obj file loader.
 * <p>
 * Does not load material files.
 * 
 * @author Kai Burjack
 */
public class WavefrontMeshLoader {

    public static class Mesh {
        public FloatBuffer positions;
        public FloatBuffer normals;
        public int numVertices;
        public float boundingSphereRadius;
        public List<MeshObject> objects = new ArrayList<MeshObject>();
    }

    private static class WavefrontInfo {
        int numberOfVertices;
        int numberOfFaces;
        int numberOfNormals;
    }

    public class MeshObject {
        public String name;
        public int first;
        public int count;
        public Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        public Vector3f max = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);

        public String toString() {
            return name + "(" + min + " " + max + ")";
        }
    }

    private boolean fourComponentPosition;

    public WavefrontMeshLoader() {
    }

    public boolean isFourComponentPosition() {
        return fourComponentPosition;
    }

    public void setFourComponentPosition(boolean fourComponentPosition) {
        this.fourComponentPosition = fourComponentPosition;
    }

    private static WavefrontInfo getInfo(BufferedReader reader) throws IOException {
        String line = "";
        WavefrontInfo info = new WavefrontInfo();
        while (true) {
            line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.startsWith("v ")) {
                info.numberOfVertices++;
            } else if (line.startsWith("f ")) {
                info.numberOfFaces++;
            } else if (line.startsWith("vn ")) {
                info.numberOfNormals++;
            }
        }
        return info;
    }

    private static byte[] readSingleFileZip(String zipResource) throws IOException {
        ZipInputStream zipStream = new ZipInputStream(WavefrontMeshLoader.class.getClassLoader().getResourceAsStream(
                zipResource));
        zipStream.getNextEntry();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read = 0;
        while ((read = zipStream.read(buffer)) > 0) {
            baos.write(buffer, 0, read);
        }
        zipStream.close();
        return baos.toByteArray();
    }

    public Mesh loadMesh(String resource) throws IOException {
        byte[] arr = readSingleFileZip(resource);
        WavefrontInfo info = getInfo(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(arr))));

        // Allocate buffers for all vertices/normal
        ByteBuffer positionByteBuffer = BufferUtils.createByteBuffer(3 * info.numberOfVertices * 4);
        ByteBuffer normalByteBuffer = BufferUtils.createByteBuffer(3 * info.numberOfNormals * 4);
        FloatBuffer positions = positionByteBuffer.asFloatBuffer();
        FloatBuffer normals = normalByteBuffer.asFloatBuffer();

        // Allocate buffers for the actual face vertices/normals
        ByteBuffer positionDataByteBuffer = BufferUtils.createByteBuffer((fourComponentPosition ? 4 : 3) * 3 * info.numberOfFaces * 4);
        ByteBuffer normalDataByteBuffer = BufferUtils.createByteBuffer(3 * 3 * info.numberOfFaces * 4);
        FloatBuffer positionData = positionDataByteBuffer.asFloatBuffer();
        FloatBuffer normalData = normalDataByteBuffer.asFloatBuffer();

        Mesh mesh = new Mesh();
        MeshObject object = null;

        float minX = 1E38f, minY = 1E38f, minZ = 1E38f;
        float maxX = -1E38f, maxY = -1E38f, maxZ = -1E38f;

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(arr)));
        String line;
        int faceIndex = 0;
        Vector3f tmp = new Vector3f();
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("o ")) {
                String name = line.substring(2);
                object = new MeshObject();
                object.name = name;
                object.first = faceIndex;
                mesh.objects.add(object);
            } else if (line.startsWith("vn ")) {
                String[] ns = line.split(" +");
                float x = Float.parseFloat(ns[1]);
                float y = Float.parseFloat(ns[2]);
                float z = Float.parseFloat(ns[3]);
                normals.put(x).put(y).put(z);
            } else if (line.startsWith("v ")) {
                String[] vs = line.split(" +");
                float x = Float.parseFloat(vs[1]);
                float y = Float.parseFloat(vs[2]);
                float z = Float.parseFloat(vs[3]);
                positions.put(x).put(y).put(z);
            } else if (line.startsWith("f")) {
                String[] fs = line.split(" +");
                String[] f1 = fs[1].split("/");
                String[] f2 = fs[2].split("/");
                String[] f3 = fs[3].split("/");
                int v1 = Integer.parseInt(f1[0]);
                int v2 = Integer.parseInt(f2[0]);
                int v3 = Integer.parseInt(f3[0]);
                int n1 = Integer.parseInt(f1[2]);
                int n2 = Integer.parseInt(f2[2]);
                int n3 = Integer.parseInt(f3[2]);
                float ver1X = positions.get(3 * (v1 - 1) + 0);
                float ver1Y = positions.get(3 * (v1 - 1) + 1);
                float ver1Z = positions.get(3 * (v1 - 1) + 2);
                minX = minX < ver1X ? minX : ver1X;
                minY = minY < ver1Y ? minY : ver1Y;
                minZ = minZ < ver1Z ? minZ : ver1Z;
                maxX = maxX > ver1X ? maxX : ver1X;
                maxY = maxY > ver1Y ? maxY : ver1Y;
                maxZ = maxZ > ver1Z ? maxZ : ver1Z;
                tmp.set(ver1X, ver1Y, ver1Z);
                if (object != null) {
                    object.min.min(tmp);
                    object.max.max(tmp);
                }
                float ver2X = positions.get(3 * (v2 - 1) + 0);
                float ver2Y = positions.get(3 * (v2 - 1) + 1);
                float ver2Z = positions.get(3 * (v2 - 1) + 2);
                minX = minX < ver2X ? minX : ver2X;
                minY = minY < ver2Y ? minY : ver2Y;
                minZ = minZ < ver2Z ? minZ : ver2Z;
                maxX = maxX > ver2X ? maxX : ver2X;
                maxY = maxY > ver2Y ? maxY : ver2Y;
                maxZ = maxZ > ver2Z ? maxZ : ver2Z;
                tmp.set(ver2X, ver2Y, ver2Z);
                if (object != null) {
                    object.min.min(tmp);
                    object.max.max(tmp);
                }
                float ver3X = positions.get(3 * (v3 - 1) + 0);
                float ver3Y = positions.get(3 * (v3 - 1) + 1);
                float ver3Z = positions.get(3 * (v3 - 1) + 2);
                minX = minX < ver3X ? minX : ver3X;
                minY = minY < ver3Y ? minY : ver3Y;
                minZ = minZ < ver3Z ? minZ : ver3Z;
                maxX = maxX > ver3X ? maxX : ver3X;
                maxY = maxY > ver3Y ? maxY : ver3Y;
                maxZ = maxZ > ver3Z ? maxZ : ver3Z;
                tmp.set(ver3X, ver3Y, ver3Z);
                if (object != null) {
                    object.min.min(tmp);
                    object.max.max(tmp);
                }
                positionData.put(ver1X).put(ver1Y).put(ver1Z);
                if (fourComponentPosition) {
                    positionData.put(1.0f);
                }
                positionData.put(ver2X).put(ver2Y).put(ver2Z);
                if (fourComponentPosition) {
                    positionData.put(1.0f);
                }
                positionData.put(ver3X).put(ver3Y).put(ver3Z);
                if (fourComponentPosition) {
                    positionData.put(1.0f);
                }
                float norm1X = normals.get(3 * (n1 - 1) + 0);
                float norm1Y = normals.get(3 * (n1 - 1) + 1);
                float norm1Z = normals.get(3 * (n1 - 1) + 2);
                float norm2X = normals.get(3 * (n2 - 1) + 0);
                float norm2Y = normals.get(3 * (n2 - 1) + 1);
                float norm2Z = normals.get(3 * (n2 - 1) + 2);
                float norm3X = normals.get(3 * (n3 - 1) + 0);
                float norm3Y = normals.get(3 * (n3 - 1) + 1);
                float norm3Z = normals.get(3 * (n3 - 1) + 2);
                normalData.put(norm1X).put(norm1Y).put(norm1Z);
                normalData.put(norm2X).put(norm2Y).put(norm2Z);
                normalData.put(norm3X).put(norm3Y).put(norm3Z);
                faceIndex++;
                if (object != null) {
                    object.count++;
                }
            }
        }
        if (mesh.objects.isEmpty()) {
            object = new MeshObject();
            object.count = faceIndex;
            mesh.objects.add(object);
        }
        positionData.flip();
        normalData.flip();
        mesh.boundingSphereRadius = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) * 0.5f;
        mesh.positions = positionData;
        mesh.normals = normalData;
        mesh.numVertices = positionData.limit() / (fourComponentPosition ? 4 : 3);
        return mesh;
    }
}
