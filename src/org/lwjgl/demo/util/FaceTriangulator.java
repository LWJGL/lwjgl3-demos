package org.lwjgl.demo.util;

import org.lwjgl.demo.util.GreedyMeshing.Face;

public class FaceTriangulator {
    public static void triangulateFloat(Iterable<Face> faces, DynamicByteBuffer positions, DynamicByteBuffer normals,
            DynamicByteBuffer indices) {
        int i = 0;
        for (Face f : faces) {
            switch (f.s) {
            case 0:
            case 1:
                positions.putFloat(f.p & 0xFF).putFloat(f.u0 & 0xFF).putFloat(f.v0 & 0xFF);
                positions.putFloat(f.p & 0xFF).putFloat(f.u1 & 0xFF).putFloat(f.v0 & 0xFF);
                positions.putFloat(f.p & 0xFF).putFloat(f.u1 & 0xFF).putFloat(f.v1 & 0xFF);
                positions.putFloat(f.p & 0xFF).putFloat(f.u0 & 0xFF).putFloat(f.v1 & 0xFF);
                normals.putFloat(2 * f.s - 1).putFloat(0).putFloat(0).putFloat(0);
                normals.putFloat(2 * f.s - 1).putFloat(0).putFloat(0).putFloat(0);
                normals.putFloat(2 * f.s - 1).putFloat(0).putFloat(0).putFloat(0);
                normals.putFloat(2 * f.s - 1).putFloat(0).putFloat(0).putFloat(0);
                break;
            case 2:
            case 3:
                positions.putFloat(f.v0 & 0xFF).putFloat(f.p & 0xFF).putFloat(f.u0 & 0xFF);
                positions.putFloat(f.v0 & 0xFF).putFloat(f.p & 0xFF).putFloat(f.u1 & 0xFF);
                positions.putFloat(f.v1 & 0xFF).putFloat(f.p & 0xFF).putFloat(f.u1 & 0xFF);
                positions.putFloat(f.v1 & 0xFF).putFloat(f.p & 0xFF).putFloat(f.u0 & 0xFF);
                normals.putFloat(0).putFloat(2 * f.s - 1).putFloat(0).putFloat(0);
                normals.putFloat(0).putFloat(2 * f.s - 1).putFloat(0).putFloat(0);
                normals.putFloat(0).putFloat(2 * f.s - 1).putFloat(0).putFloat(0);
                normals.putFloat(0).putFloat(2 * f.s - 1).putFloat(0).putFloat(0);
                break;
            case 4:
            case 5:
                positions.putFloat(f.u0 & 0xFF).putFloat(f.v0 & 0xFF).putFloat(f.p & 0xFF);
                positions.putFloat(f.u1 & 0xFF).putFloat(f.v0 & 0xFF).putFloat(f.p & 0xFF);
                positions.putFloat(f.u1 & 0xFF).putFloat(f.v1 & 0xFF).putFloat(f.p & 0xFF);
                positions.putFloat(f.u0 & 0xFF).putFloat(f.v1 & 0xFF).putFloat(f.p & 0xFF);
                normals.putFloat(0).putFloat(0).putFloat(2 * f.s - 1).putFloat(0);
                normals.putFloat(0).putFloat(0).putFloat(2 * f.s - 1).putFloat(0);
                normals.putFloat(0).putFloat(0).putFloat(2 * f.s - 1).putFloat(0);
                normals.putFloat(0).putFloat(0).putFloat(2 * f.s - 1).putFloat(0);
                break;
            }
            if ((f.s % 2) == 0) {
                indices.putInt(4 * i).putInt(4 * i + 1).putInt(4 * i + 2);
                indices.putInt(4 * i + 2).putInt(4 * i + 3).putInt(4 * i);
            } else {
                indices.putInt(4 * i).putInt(4 * i + 3).putInt(4 * i + 2);
                indices.putInt(4 * i + 2).putInt(4 * i + 1).putInt(4 * i);
            }
            i++;
        }
    }
}
