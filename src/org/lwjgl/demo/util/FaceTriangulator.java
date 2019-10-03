package org.lwjgl.demo.util;

import org.lwjgl.demo.util.GreedyMeshing.Face;

public class FaceTriangulator {

    /**
     * Adapted from:
     * https://stackoverflow.com/questions/6162651/half-precision-floating-point-in-java#answer-6162687
     */
    private static short f16(float f32) {
        int fbits = Float.floatToIntBits(f32);
        int sign = fbits >>> 16 & 0x8000;
        int val = (fbits & 0x7fffffff) + 0x1000;
        return val >= 0x38800000 ? (short) (sign | val - 0x38000000 >>> 13) : (short) sign;
    }

    public static void triangulateFloat(Iterable<Face> faces, DynamicByteBuffer positions, DynamicByteBuffer normals,
            DynamicByteBuffer indices) {
        int i = 0;
        for (Face f : faces) {
            switch (f.s) {
            case 0:
            case 1:
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v0 & 0xFF));
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v0 & 0xFF));
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v1 & 0xFF));
                positions.putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v1 & 0xFF));
                normals.putByte((byte) (127 * (2 * -f.s + 1))).putByte(0).putByte(0).putByte(0);
                normals.putByte((byte) (127 * (2 * -f.s + 1))).putByte(0).putByte(0).putByte(0);
                normals.putByte((byte) (127 * (2 * -f.s + 1))).putByte(0).putByte(0).putByte(0);
                normals.putByte((byte) (127 * (2 * -f.s + 1))).putByte(0).putByte(0).putByte(0);
                break;
            case 2:
            case 3:
                positions.putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF));
                positions.putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF));
                positions.putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u1 & 0xFF));
                positions.putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF)).putShort(f16(f.u0 & 0xFF));
                normals.putByte(0).putByte((byte) (127 * (2 * -f.s + 5))).putByte(0).putByte(0);
                normals.putByte(0).putByte((byte) (127 * (2 * -f.s + 5))).putByte(0).putByte(0);
                normals.putByte(0).putByte((byte) (127 * (2 * -f.s + 5))).putByte(0).putByte(0);
                normals.putByte(0).putByte((byte) (127 * (2 * -f.s + 5))).putByte(0).putByte(0);
                break;
            case 4:
            case 5:
                positions.putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v0 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putShort(f16(f.u1 & 0xFF)).putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF));
                positions.putShort(f16(f.u0 & 0xFF)).putShort(f16(f.v1 & 0xFF)).putShort(f16(f.p & 0xFF));
                normals.putByte(0).putByte(0).putByte((byte) (127 * (2 * -f.s + 9))).putByte(0);
                normals.putByte(0).putByte(0).putByte((byte) (127 * (2 * -f.s + 9))).putByte(0);
                normals.putByte(0).putByte(0).putByte((byte) (127 * (2 * -f.s + 9))).putByte(0);
                normals.putByte(0).putByte(0).putByte((byte) (127 * (2 * -f.s + 9))).putByte(0);
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
