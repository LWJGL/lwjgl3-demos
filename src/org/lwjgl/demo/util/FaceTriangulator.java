package org.lwjgl.demo.util;

import static org.lwjgl.demo.util.GreedyMeshing.Face.*;

import java.nio.*;

import org.lwjgl.demo.util.GreedyMeshing.Face;

public class FaceTriangulator {

    private static boolean isPositiveSide(int side) {
        return (side & 1) != 0;
    }

    private static int sn16(int bitsForPosition, int v) {
        return v << (Short.SIZE - 1 - bitsForPosition);
    }

    public static void triangulate_Vu16_Iu32(Iterable<Face> faces, ShortBuffer positions, IntBuffer indices) {
        int i = 0;
        for (Face f : faces) {
            switch (f.s) {
            case SIDE_NX:
            case SIDE_PX:
                positions.put(f.p).put(f.u0).put(f.v0);
                positions.put(f.p).put(f.u1).put(f.v0);
                positions.put(f.p).put(f.u1).put(f.v1);
                positions.put(f.p).put(f.u0).put(f.v1);
                break;
            case SIDE_NY:
            case SIDE_PY:
                positions.put(f.v0).put(f.p).put(f.u0);
                positions.put(f.v0).put(f.p).put(f.u1);
                positions.put(f.v1).put(f.p).put(f.u1);
                positions.put(f.v1).put(f.p).put(f.u0);
                break;
            case SIDE_NZ:
            case SIDE_PZ:
                positions.put(f.u0).put(f.v0).put(f.p);
                positions.put(f.u1).put(f.v0).put(f.p);
                positions.put(f.u1).put(f.v1).put(f.p);
                positions.put(f.u0).put(f.v1).put(f.p);
                break;
            }
            if (isPositiveSide(f.s)) {
                indices.put(i << 2).put((i << 2) + 1).put((i << 2) + 2);
                indices.put((i << 2) + 2).put((i << 2) + 3).put(i << 2);
            } else {
                indices.put(i << 2).put((i << 2) + 3).put((i << 2) + 2);
                indices.put((i << 2) + 2).put((i << 2) + 1).put(i << 2);
            }
            i++;
        }
    }

    public static void triangulate_Vsn16_Iu32(int bits, Iterable<Face> faces, DynamicByteBuffer positions,
            DynamicByteBuffer normals, DynamicByteBuffer indices) {
        int i = 0;
        for (Face f : faces) {
            switch (f.s) {
            case SIDE_NX:
            case SIDE_PX:
                positions.putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u0)).putShort(sn16(bits, f.v0));
                positions.putByte(0).putByte(-1).putByte(-1).putByte(0);
                positions.putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u1)).putShort(sn16(bits, f.v0));
                positions.putByte(0).putByte(1).putByte(-1).putByte(0);
                positions.putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u1)).putShort(sn16(bits, f.v1));
                positions.putByte(0).putByte(1).putByte(1).putByte(0);
                positions.putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u0)).putShort(sn16(bits, f.v1));
                positions.putByte(0).putByte(-1).putByte(1).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                break;
            case SIDE_NY:
            case SIDE_PY:
                positions.putShort(sn16(bits, f.v0)).putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u0));
                positions.putByte(-1).putByte(0).putByte(-1).putByte(0);
                positions.putShort(sn16(bits, f.v0)).putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u1));
                positions.putByte(-1).putByte(0).putByte(1).putByte(0);
                positions.putShort(sn16(bits, f.v1)).putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u1));
                positions.putByte(1).putByte(0).putByte(1).putByte(0);
                positions.putShort(sn16(bits, f.v1)).putShort(sn16(bits, f.p)).putShort(sn16(bits, f.u0));
                positions.putByte(1).putByte(0).putByte(-1).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                break;
            case SIDE_NZ:
            case SIDE_PZ:
                positions.putShort(sn16(bits, f.u0)).putShort(sn16(bits, f.v0)).putShort(sn16(bits, f.p));
                positions.putByte(-1).putByte(-1).putByte(0).putByte(0);
                positions.putShort(sn16(bits, f.u1)).putShort(sn16(bits, f.v0)).putShort(sn16(bits, f.p));
                positions.putByte(1).putByte(-1).putByte(0).putByte(0);
                positions.putShort(sn16(bits, f.u1)).putShort(sn16(bits, f.v1)).putShort(sn16(bits, f.p));
                positions.putByte(1).putByte(1).putByte(0).putByte(0);
                positions.putShort(sn16(bits, f.u0)).putShort(sn16(bits, f.v1)).putShort(sn16(bits, f.p));
                positions.putByte(-1).putByte(1).putByte(0).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                break;
            }
            if (isPositiveSide(f.s)) {
                indices.putInt(i << 2).putInt((i << 2) + 1).putInt((i << 2) + 2);
                indices.putInt((i << 2) + 2).putInt((i << 2) + 3).putInt(i << 2);
            } else {
                indices.putInt(i << 2).putInt((i << 2) + 3).putInt((i << 2) + 2);
                indices.putInt((i << 2) + 2).putInt((i << 2) + 1).putInt(i << 2);
            }
            i++;
        }
    }
}
