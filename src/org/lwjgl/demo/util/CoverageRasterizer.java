/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Float.*;
import static java.lang.Integer.*;
import static java.util.Arrays.*;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.joml.Matrix4f;

/**
 * Coverage-mask-only software rasterizer.
 * 
 * @author Kai Burjack
 */
public class CoverageRasterizer {
    public final int width, height;
    public final boolean[] maskbuffer;

    public CoverageRasterizer(int width, int height) {
        this.width = width;
        this.height = height;
        this.maskbuffer = new boolean[width * height];
        clearMask();
    }

    public void clearMask() {
        fill(maskbuffer, false);
    }

    public int rasterize_Vu8_Iu16(Matrix4f m, ByteBuffer vb, ShortBuffer ib, boolean maskTest, boolean maskWrite, int minSamples) {
        int samplesPassed = 0;
        int irem = ib.remaining();
        for (int i = 0; i < irem; i += 3) {
            // Load next three indices
            int i0 = idx(ib, i), i1 = idx(ib, i + 1), i2 = idx(ib, i + 2);
            // Load corresponding vertex positions
            int v0x = vx(vb, i0), v0y = vy(vb, i0), v0z = vz(vb, i0);
            int v1x = vx(vb, i1), v1y = vy(vb, i1), v1z = vz(vb, i1);
            int v2x = vx(vb, i2), v2y = vy(vb, i2), v2z = vz(vb, i2);
            // Transform to NDC space
            float w0i = invw(m, v0x, v0y, v0z);
            float v0xp = dx(m, v0x, v0y, v0z) * w0i, v0yp = dy(m, v0x, v0y, v0z) * w0i, v0zp = dz(m, v0x, v0y, v0z) * w0i;
            float w1i = invw(m, v1x, v1y, v1z);
            float v1xp = dx(m, v1x, v1y, v1z) * w1i, v1yp = dy(m, v1x, v1y, v1z) * w1i, v1zp = dz(m, v1x, v1y, v1z) * w1i;
            float w2i = invw(m, v2x, v2y, v2z);
            float v2xp = dx(m, v2x, v2y, v2z) * w2i, v2yp = dy(m, v2x, v2y, v2z) * w2i, v2zp = dz(m, v2x, v2y, v2z) * w2i;
            // Cull backfaces with clockwise winding
            if (s(v0xp, v0yp, v1xp, v1yp, v2xp, v2yp) <= 0)
                continue;
            // Transform to window space
            float v0xw = winx(v0xp), v0yw = winy(v0yp);
            float v1xw = winx(v1xp), v1yw = winy(v1yp);
            float v2xw = winx(v2xp), v2yw = winy(v2yp);
            // Compute bounding box
            float minX = min3(v0xw, v1xw, v2xw), minY = min3(v0yw, v1yw, v2yw), minZ = min3(v0zp, v1zp, v2zp);
            float maxX = max3(v0xw, v1xw, v2xw), maxY = max3(v0yw, v1yw, v2yw), maxZ = max3(v0zp, v1zp, v2zp);
            if (minX >= width || minY >= height || maxX < 0 || maxY < 0 || minZ > 1.0f || maxZ < -1.0f)
                continue;
            // Clamp bounding box
            int miX = max(min((int) minX, width - 1), 0), miY = max(min((int) minY, height - 1), 0);
            int maX = max(min((int) maxX, width - 1), 0), maY = max(min((int) maxY, height - 1), 0);
            // Compute barycentric transformation matrix
            float y10 = v0yw - v1yw, x01 = v1xw - v0xw;
            float y21 = v1yw - v2yw, x12 = v2xw - v1xw;
            float y02 = v2yw - v0yw, x20 = v0xw - v2xw;
            float b0 = s(v1xw, v1yw, v2xw, v2yw, miX, miY);
            float b1 = s(v2xw, v2yw, v0xw, v0yw, miX, miY);
            float b2 = s(v0xw, v0yw, v1xw, v1yw, miX, miY);
            // Iterate over every pixel in the bounding rectangle
            for (int y = miY; y <= maY; y++, b0 += x12, b1 += x20, b2 += x01) {
                float w0 = b0, w1 = b1, w2 = b2;
                boolean in = false;
                for (int x = miX; x <= maX; x++, w0 += y21, w1 += y02, w2 += y10) {
                    boolean covered = maskbuffer[x + y * width];
                    if (covered)
                        continue;
                    if (((floatToRawIntBits(w0)
                        | floatToRawIntBits(w1)
                        | floatToRawIntBits(w2)) & 0x80000000) != 0)
                        if (in)
                            break;
                        else
                            continue;
                    in = true;
                    float d = w0 * v0zp + w1 * v1zp + w2 * v2zp;
                    float s = w0 + w1 + w2;
                    if (d < 0.0f || d > s)
                        continue;
                    if (maskWrite)
                        maskbuffer[x + y * width] = true;
                    samplesPassed++;
                    if (samplesPassed >= minSamples)
                        return samplesPassed;
                }
            }
        }
        return samplesPassed;
    }

    private static float s(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static float max3(float v0, float v1, float v2) {
        return max(max(v0, v1), v2);
    }

    private static float min3(float v0, float v1, float v2) {
        return min(min(v0, v1), v2);
    }

    private float winy(float yp) {
        return ((yp * 0.5f) + 0.5f) * height - 0.5f;
    }

    private float winx(float xp) {
        return ((xp * 0.5f) + 0.5f) * width - 0.5f;
    }

    private static int idx(ShortBuffer ib, int i) {
        return ib.get(i) & 0xFFFF;
    }

    private static int vz(ByteBuffer vb, int i) {
        return vb.get(3 * i + 2) & 0xFF;
    }

    private static int vy(ByteBuffer vb, int i) {
        return vb.get(3 * i + 1) & 0xFF;
    }

    private int vx(ByteBuffer vb, int i) {
        return vb.get(3 * i) & 0xFF;
    }

    private static float dz(Matrix4f m, int vx, int vy, int vz) {
        return m.m02() * vx + m.m12() * vy + m.m22() * vz + m.m32();
    }

    private static float dy(Matrix4f m, int vx, int vy, int vz) {
        return m.m01() * vx + m.m11() * vy + m.m21() * vz + m.m31();
    }

    private static float dx(Matrix4f m, int vx, int vy, int vz) {
        return m.m00() * vx + m.m10() * vy + m.m20() * vz + m.m30();
    }

    private static float invw(Matrix4f m, int vx, int vy, int vz) {
        return 1.0f / (m.m03() * vx + m.m13() * vy + m.m23() * vz + m.m33());
    }
}
