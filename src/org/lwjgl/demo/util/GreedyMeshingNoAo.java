/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Math.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy meshing based on the JavaScript code from
 * https://0fps.net/2012/07/07/meshing-minecraft-part-2/
 * <p>
 * Instances of this class are <i>not</i> thread-safe, so calls to
 * {@link #mesh(byte[], List)} on the same instance must be externally
 * synchronized.
 * 
 * @author Kai Burjack
 */
public class GreedyMeshingNoAo {
    public static class Face {
        public static final byte SIDE_NX = 0;
        public static final byte SIDE_PX = 1;
        public static final byte SIDE_NY = 2;
        public static final byte SIDE_PY = 3;
        public static final byte SIDE_NZ = 4;
        public static final byte SIDE_PZ = 5;

        public byte s;
        public short u0, v0, u1, v1, p, tx, ty;
        public int v;

        public int w() {
            return u1 - u0;
        }
        public int h() {
            return v1 - v0;
        }
        public int tw() {
            return w() + 1;
        }
        public int th() {
            return h() + 1;
        }
        public int tx() {
            return tx;
        }
        public void tx(int tx) {
            this.tx = (short) tx;
        }
        public int ty() {
            return ty;
        }
        public void ty(int ty) {
            this.ty = (short) ty;
        }
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

    private final int[] m;
    private byte[] vs;
    private int dx, dy, dz, nx, ny, nz;

    public GreedyMeshingNoAo(int nx, int ny, int nz, int py, int dx, int dz) {
        if (dx < 1 || dx > Short.MAX_VALUE)
            throw new IllegalArgumentException("dx");
        if (ny < 0 || ny > Short.MAX_VALUE)
            throw new IllegalArgumentException("ny");
        if (py < 0 || py > Short.MAX_VALUE)
            throw new IllegalArgumentException("py");
        if (dz < 1 || dz > Short.MAX_VALUE)
            throw new IllegalArgumentException("dz");
        this.dx = dx;
        this.dy = py - ny + 1;
        this.dz = dz;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.m = new int[max(dx, dy) * max(dy, dz)];
    }

    private byte at(int x, int y, int z) {
        return vs[idx(x, y, z)];
    }

    private int idx(int x, int y, int z) {
        return x + 1 + (dx + 2) * (z + 1 + (dz + 2) * (y + 1));
    }

    public void mesh(byte[] vs, List<Face> faces) {
        @SuppressWarnings("unchecked")
        List<Face>[] fs = new List[] {
                new ArrayList<Face>(), new ArrayList<Face>(),
                new ArrayList<Face>(), new ArrayList<Face>(),
                new ArrayList<Face>(), new ArrayList<Face>()};
        mesh(vs, fs);
        for (int i = 0; i < 6; i++)
            faces.addAll(fs[i]);
    }

    public void mesh(byte[] vs, List<Face>[] faces) {
        this.vs = vs;
        meshX(faces);
        meshY(faces);
        meshZ(faces);
    }

    private void meshX(List<Face>[] faces) {
        for (int x0 = nx - 1; x0 < dx;) {
            generateMaskX(x0);
            mergeAndGenerateFacesX(faces, ++x0);
        }
    }

    private void meshY(List<Face>[] faces) {
        for (int y = ny - 1; y < dy;) {
            generateMaskY(y);
            mergeAndGenerateFacesY(faces, ++y);
        }
    }

    private void meshZ(List<Face>[] faces) {
        for (int z = nz - 1; z < dz;) {
            generateMaskZ(z);
            mergeAndGenerateFacesZ(faces, ++z);
        }
    }

    private void generateMaskX(int x) {
        int n = 0;
        for (int z = 0; z < dz; z++)
            for (int y = ny; y < dy; y++, n++)
                generateMaskX(x, y, z, n);
    }

    private void generateMaskY(int y) {
        int n = 0;
        for (int x = 0; x < dx; x++)
            for (int z = 0; z < dz; z++, n++)
                generateMaskY(x, y, z, n);
    }

    private void generateMaskZ(int z) {
        int n = 0;
        for (int y = ny; y < dy; y++)
            for (int x = 0; x < dx; x++, n++)
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

    private void mergeAndGenerateFacesX(List<Face>[] faces, int x) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dz; j++)
            for (i = ny; i < dy; i += incr, n += incr)
                incr = mergeAndGenerateFaceX(faces, x, n, i, j);
    }

    private void mergeAndGenerateFacesY(List<Face>[] faces, int y) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dx; j++)
            for (i = 0; i < dz; i += incr, n += incr)
                incr = mergeAndGenerateFaceY(faces, y, n, i, j);
    }

    private void mergeAndGenerateFacesZ(List<Face>[] faces, int z) {
        int i, j, n, incr;
        for (j = ny, n = 0; j < dy; j++)
            for (i = 0; i < dx; i += incr, n += incr)
                incr = mergeAndGenerateFaceZ(faces, z, n, i, j);
    }

    private int mergeAndGenerateFaceX(List<Face>[] faces, int x, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthX(mn, n, i);
        int h = determineHeightX(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, x, 0 + (mn > 0 ? 1 : 0), mn);
        faces[f.s].add(f);
        eraseMaskX(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceY(List<Face>[] faces, int y, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthY(mn, n, i);
        int h = determineHeightY(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, y, 2 + (mn > 0 ? 1 : 0), mn);
        faces[f.s].add(f);
        eraseMaskY(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceZ(List<Face>[] faces, int z, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthZ(mn, n, i);
        int h = determineHeightZ(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, z, 4 + (mn > 0 ? 1 : 0), mn);
        faces[f.s].add(f);
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
        while (n + w < dy * dz && i + w < dy && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthY(int c, int n, int i) {
        int w = 1;
        while (n + w < dz * dx && i + w < dz && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthZ(int c, int n, int i) {
        int w = 1;
        while (n + w < dx * dy && i + w < dx && c == m[n + w])
            w++;
        return w;
    }

    private int determineHeightX(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h < dz; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dy])
                    return h;
        return h;
    }

    private int determineHeightY(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h < dx; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dz])
                    return h;
        return h;
    }

    private int determineHeightZ(int c, int n, int j, int w) {
        int h;
        for (h = 1; j + h < dy; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dx])
                    return h;
        return h;
    }
}
