/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Math.*;

/**
 * Greedy meshing based on the JavaScript code from
 * https://0fps.net/2012/07/07/meshing-minecraft-part-2/
 * <p>
 * Instances of this class are <i>not</i> thread-safe, so calls to
 * {@link #mesh(byte[], FaceConsumer)} on the same instance must be externally
 * synchronized.
 * 
 * @author Kai Burjack
 */
public class GreedyMeshingNoAo {
    public static class Face {
        public byte s;
        public short u0, v0, u1, v1, p, tx, ty;
        public int v;
        public Face(int u0, int v0, int u1, int v1, int p, int s, int v) {
            this.u0 = (short) u0;
            this.v0 = (short) v0;
            this.u1 = (short) u1;
            this.v1 = (short) v1;
            this.p = (short) p;
            this.s = (byte) s;
            this.v = v;
        }
    }
    @FunctionalInterface
    public interface FaceConsumer {
        void consume(int u0, int v0, int u1, int v1, int p, int s, int v);
    }

    private final int[] m;
    private byte[] vs;
    private final int dx, dy, dz, nx, ny, nz, px, py, pz, vdx, vdz;

    public GreedyMeshingNoAo(int nx, int ny, int nz, int px, int py, int pz, int vdx, int vdz) {
        this.vdx = vdx;
        this.vdz = vdz;
        this.dx = px - nx + 1;
        this.dy = py - ny + 1;
        this.dz = pz - nz + 1;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.m = new int[max(dx, dy) * max(dy, dz)];
    }

    private byte at(int x, int y, int z) {
        return vs[idx(x, y, z)];
    }

    private int idx(int x, int y, int z) {
        return x + 1 + (vdx + 2) * (z + 1 + (vdz + 2) * (y + 1));
    }

    public void mesh(byte[] vs, FaceConsumer consumer) {
        this.vs = vs;
        meshX(consumer);
        meshY(consumer);
        meshZ(consumer);
    }

    private void meshX(FaceConsumer consumer) {
        for (int x = nx - 1; x <= px;) {
            generateMaskX(x);
            mergeAndGenerateFacesX(consumer, ++x);
        }
    }

    private void meshY(FaceConsumer consumer) {
        for (int y = ny - 1; y <= py;) {
            generateMaskY(y);
            mergeAndGenerateFacesY(consumer, ++y);
        }
    }

    private void meshZ(FaceConsumer consumer) {
        for (int z = nz - 1; z <= pz;) {
            generateMaskZ(z);
            mergeAndGenerateFacesZ(consumer, ++z);
        }
    }

    private void generateMaskX(int x) {
        int n = 0;
        for (int z = nz; z <= pz; z++)
            for (int y = ny; y <= py; y++, n++)
                generateMaskX(x, y, z, n);
    }

    private void generateMaskY(int y) {
        int n = 0;
        for (int x = nx; x <= px; x++)
            for (int z = nz; z <= pz; z++, n++)
                generateMaskY(x, y, z, n);
    }

    private void generateMaskZ(int z) {
        int n = 0;
        for (int y = ny; y <= py; y++)
            for (int x = nx; x <= px; x++, n++)
                generateMaskZ(x, y, z, n);
    }

    private void generateMaskX(int x, int y, int z, int n) {
        int a = at(x, y, z) & 0xFF;
        int b = at(x + 1, y, z) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0) {
            m[n] = a;
        } else
            m[n] = b | 0x80000000;
    }

    private void generateMaskY(int x, int y, int z, int n) {
        int a = at(x, y, z) & 0xFF;
        int b = at(x, y + 1, z) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0) {
            m[n] = a;
        } else
            m[n] = b | 0x80000000;
    }

    private void generateMaskZ(int x, int y, int z, int n) {
        int a = at(x, y, z) & 0xFF;
        int b = at(x, y, z + 1) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0)
            m[n] = a;
        else
            m[n] = b | 0x80000000;
    }

    private void mergeAndGenerateFacesX(FaceConsumer consumer, int x) {
        int i, j, n, incr;
        for (j = nz, n = 0; j <= pz; j++)
            for (i = ny; i <= py; i += incr, n += incr)
                incr = mergeAndGenerateFaceX(consumer, x, n, i, j);
    }

    private void mergeAndGenerateFacesY(FaceConsumer consumer, int y) {
        int i, j, n, incr;
        for (j = nx, n = 0; j <= px; j++)
            for (i = nz; i <= pz; i += incr, n += incr)
                incr = mergeAndGenerateFaceY(consumer, y, n, i, j);
    }

    private void mergeAndGenerateFacesZ(FaceConsumer consumer, int z) {
        int i, j, n, incr;
        for (j = ny, n = 0; j <= py; j++)
            for (i = nx; i <= px; i += incr, n += incr)
                incr = mergeAndGenerateFaceZ(consumer, z, n, i, j);
    }

    private int mergeAndGenerateFaceX(FaceConsumer consumer, int x, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthX(mn, n, i);
        int h = determineHeightX(mn, n, j, w);
        consumer.consume(i, j, i + w, j + h, x, mn > 0 ? 1 : 0, mn);
        eraseMaskX(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceY(FaceConsumer consumer, int y, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthY(mn, n, i);
        int h = determineHeightY(mn, n, j, w);
        consumer.consume(i, j, i + w, j + h, y, 2 + (mn > 0 ? 1 : 0), mn);
        eraseMaskY(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceZ(FaceConsumer consumer, int z, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthZ(mn, n, i);
        int h = determineHeightZ(mn, n, j, w);
        consumer.consume(i, j, i + w, j + h, z, 4 + (mn > 0 ? 1 : 0), mn);
        eraseMaskZ(n, w, h);
        return w;
    }

    private void eraseMaskX(int n, int w, int h) {
        for (int l = 0; l < h; l++)
            for (int k = 0; k < w; k++)
                m[n + k + l * dy] = 0;
    }

    private void eraseMaskY(int n, int w, int h) {
        for (int l = 0; l < h; l++)
            for (int k = 0; k < w; k++)
                m[n + k + l * dz] = 0;
    }

    private void eraseMaskZ(int n, int w, int h) {
        for (int l = 0; l < h; l++)
            for (int k = 0; k < w; k++)
                m[n + k + l * dx] = 0;
    }

    private int determineWidthX(int c, int n, int i) {
        int w = 1;
        while (i + w <= py && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthY(int c, int n, int i) {
        int w = 1;
        while (i + w <= pz && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthZ(int c, int n, int i) {
        int w = 1;
        while (i + w <= px && c == m[n + w])
            w++;
        return w;
    }

    private int determineHeightX(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h <= pz; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dy])
                    return h;
        return h;
    }

    private int determineHeightY(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h <= px; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dz])
                    return h;
        return h;
    }

    private int determineHeightZ(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h <= py; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dx])
                    return h;
        return h;
    }
}
