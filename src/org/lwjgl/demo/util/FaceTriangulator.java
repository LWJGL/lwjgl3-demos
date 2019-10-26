package org.lwjgl.demo.util;

import static java.lang.Float.*;
import static org.lwjgl.demo.util.GreedyMeshing.Face.*;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.demo.util.GreedyMeshing.Face;

public class FaceTriangulator {

    /**
     * Adapted from:
     * https://stackoverflow.com/questions/6162651/half-precision-floating-point-in-java#answer-6162687
     */
    private static short f16(float f32) {
        int val = (floatToIntBits(f32) & 0x7fffffff) + 0x1000;
        return val >= 0x38800000 ? (short) (val - 0x38000000 >>> 13) : (short) 0;
    }

    private static boolean isPositiveSide(byte side) {
        return (side & 1) != 0;
    }

    public static void triangulate_Vu8_Iu16(Iterable<Face> faces, ByteBuffer positions, ShortBuffer indices) {
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
                indices.put((short) (i << 2)).put((short) ((i << 2) + 1)).put((short) ((i << 2) + 2));
                indices.put((short) ((i << 2) + 2)).put((short) ((i << 2) + 3)).put((short) (i << 2));
            } else {
                indices.put((short) (i << 2)).put((short) ((i << 2) + 3)).put((short) ((i << 2) + 2));
                indices.put((short) ((i << 2) + 2)).put((short) ((i << 2) + 1)).put((short) (i << 2));
            }
            i++;
        }
    }

    public static void triangulate_Vf16_Iu16(Iterable<Face> faces, DynamicByteBuffer positions,
            DynamicByteBuffer normals, DynamicByteBuffer indices) {
        int i = 0;
        for (Face f : faces) {
            switch (f.s) {
            case SIDE_NX:
            case SIDE_PX:
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v0 & 0xFF));
                positions.putByte(0).putByte(-1).putByte(-1).putByte(0);
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v0 & 0xFF));
                positions.putByte(0).putByte(1).putByte(-1).putByte(0);
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v1 & 0xFF));
                positions.putByte(0).putByte(1).putByte(1).putByte(0);
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v1 & 0xFF));
                positions.putByte(0).putByte(-1).putByte(1).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                normals.putByte(127 * ((f.s << 1) - 1)).putByte(0).putByte(0).putByte(0);
                break;
            case SIDE_NY:
            case SIDE_PY:
                positions.putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF));
                positions.putByte(-1).putByte(0).putByte(-1).putByte(0);
                positions.putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF));
                positions.putByte(-1).putByte(0).putByte(1).putByte(0);
                positions.putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF));
                positions.putByte(1).putByte(0).putByte(1).putByte(0);
                positions.putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF));
                positions.putByte(1).putByte(0).putByte(-1).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                normals.putByte(0).putByte(127 * ((f.s << 1) - 5)).putByte(0).putByte(0);
                break;
            case SIDE_NZ:
            case SIDE_PZ:
                positions.putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putByte(-1).putByte(-1).putByte(0).putByte(0);
                positions.putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putByte(1).putByte(-1).putByte(0).putByte(0);
                positions.putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putByte(1).putByte(1).putByte(0).putByte(0);
                positions.putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putByte(-1).putByte(1).putByte(0).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                normals.putByte(0).putByte(0).putByte(127 * ((f.s << 1) - 9)).putByte(0);
                break;
            }
            if (isPositiveSide(f.s)) {
                indices.putShort(i << 2).putShort((i << 2) + 1).putShort((i << 2) + 2);
                indices.putShort((i << 2) + 2).putShort((i << 2) + 3).putShort(i << 2);
            } else {
                indices.putShort(i << 2).putShort((i << 2) + 3).putShort((i << 2) + 2);
                indices.putShort((i << 2) + 2).putShort((i << 2) + 1).putShort(i << 2);
            }
            i++;
        }
    }
}
