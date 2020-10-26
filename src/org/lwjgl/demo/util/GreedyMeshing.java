/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Integer.numberOfLeadingZeros;
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
public class GreedyMeshing {
    public static class Face implements Boundable<Face> {
        public static final byte SIDE_NX = 0;
        public static final byte SIDE_PX = 1;
        public static final byte SIDE_NY = 2;
        public static final byte SIDE_PY = 3;
        public static final byte SIDE_NZ = 4;
        public static final byte SIDE_PZ = 5;

        public byte s;
        public short u0, v0, u1, v1, p, v, tx, ty;

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

        public int lod() {
            return Math.min(32 - numberOfLeadingZeros(u0 ^ u1),
                            32 - numberOfLeadingZeros(v0 ^ v1));
        }

        public Face(int u0, int v0, int u1, int v1, int p, int s, short v) {
            this.u0 = (short) u0;
            this.v0 = (short) v0;
            this.u1 = (short) u1;
            this.v1 = (short) v1;
            this.p = (short) p;
            this.s = (byte) s;
            this.v = v;
        }

        private Face(int u0, int v0, int u1, int v1, int p, int s, short v, int tx, int ty) {
            this.u0 = (short) u0;
            this.v0 = (short) v0;
            this.u1 = (short) u1;
            this.v1 = (short) v1;
            this.p = (short) p;
            this.s = (byte) s;
            this.v = v;
            this.tx = (short) tx;
            this.ty = (short) ty;
        }

        public int min(int axis) {
            switch (s >>> 1) {
            case 0:
                return minX(axis);
            case 1:
                return minY(axis);
            case 2:
                return minZ(axis);
            default:
                throw new IllegalArgumentException();
            }
        }
        private int minZ(int axis) {
            switch (axis) {
            case 0: return u0;
            case 1: return v0;
            case 2: return p;
            default: throw new IllegalArgumentException();
            }
        }
        private int minY(int axis) {
            switch (axis) {
            case 0: return v0;
            case 1: return p;
            case 2: return u0;
            default: throw new IllegalArgumentException();
            }
        }
        private int minX(int axis) {
            switch (axis) {
            case 0: return p;
            case 1: return u0;
            case 2: return v0;
            default: throw new IllegalArgumentException();
            }
        }
        public int max(int axis) {
            switch (s >>> 1) {
            case 0:
                return maxX(axis);
            case 1:
                return maxY(axis);
            case 2:
                return maxZ(axis);
            default:
                throw new IllegalArgumentException();
            }
        }
        private int maxZ(int axis) {
            switch (axis) {
            case 0: return u1;
            case 1: return v1;
            case 2: return p;
            default: throw new IllegalArgumentException();
            }
        }
        private int maxY(int axis) {
            switch (axis) {
            case 0: return v1;
            case 1: return p;
            case 2: return u1;
            default: throw new IllegalArgumentException();
            }
        }
        private int maxX(int axis) {
            switch (axis) {
            case 0: return p;
            case 1: return u1;
            case 2: return v1;
            default: throw new IllegalArgumentException();
            }
        }
        public boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return false;
        }

        public Face splitLeft(int splitAxis, int splitPos) {
            if (splitAxis == (s >>> 1))
                throw new IllegalArgumentException();
            switch (s >>> 1) {
            case 0:
                return splitLeftX(splitAxis, splitPos);
            case 1:
                return splitLeftY(splitAxis, splitPos);
            case 2:
                return splitLeftZ(splitAxis, splitPos);
            default:
                throw new IllegalArgumentException();
            }
        }
        
        private Face splitLeftZ(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 0: return new Face(u0, v0, splitPos, v1, p, s, v, tx, ty);
            case 1: return new Face(u0, v0, u1, splitPos, p, s, v, tx, ty);
            default:
                throw new IllegalArgumentException();
            }
        }
        private Face splitLeftY(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 0: return new Face(u0, v0, u1, splitPos, p, s, v, tx, ty);
            case 2: return new Face(u0, v0, splitPos, v1, p, s, v, tx, ty);
            default:
                throw new IllegalArgumentException();
            }
        }
        private Face splitLeftX(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 1: return new Face(u0, v0, splitPos, v1, p, s, v, tx, ty);
            case 2: return new Face(u0, v0, u1, splitPos, p, s, v, tx, ty);
            default:
                throw new IllegalArgumentException();
            }
        }
        public Face splitRight(int splitAxis, int splitPos) {
            if (splitAxis == (s >>> 1))
                throw new IllegalArgumentException();
            switch (s >>> 1) {
            case 0:
                return splitRightX(splitAxis, splitPos);
            case 1:
                return splitRightY(splitAxis, splitPos);
            case 2:
                return splitRightZ(splitAxis, splitPos);
            default:
                throw new IllegalArgumentException();
            }
        }
        private Face splitRightZ(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 0: return new Face(splitPos, v0, u1, v1, p, s, v, tx+(splitPos-u0), ty);
            case 1: return new Face(u0, splitPos, u1, v1, p, s, v, tx, ty+(splitPos-v0));
            default:
                throw new IllegalArgumentException();
            }
        }
        private Face splitRightY(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 0: return new Face(u0, splitPos, u1, v1, p, s, v, tx, ty+(splitPos-v0));
            case 2: return new Face(splitPos, v0, u1, v1, p, s, v, tx+(splitPos-u0), ty);
            default:
                throw new IllegalArgumentException();
            }
        }
        private Face splitRightX(int splitAxis, int splitPos) {
            switch (splitAxis) {
            case 1: return new Face(splitPos, v0, u1, v1, p, s, v, tx+(splitPos-u0), ty);
            case 2: return new Face(u0, splitPos, u1, v1, p, s, v, tx, ty+(splitPos-v0));
            default:
                throw new IllegalArgumentException();
            }
        }
    }

    private final int[] m;
    private byte[] vs;
    private int dx, dy, dz, nx, ny, nz;
    private boolean singleOpaque;
    private int maxMergeLength = Integer.MAX_VALUE;
    private int splitShift = 16;
    private int splitMask = (1 << splitShift) - 1;

    public GreedyMeshing(int nx, int ny, int nz, int py, int dx, int dz) {
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

    public void setMaxMergeLength(int maxMergeLength) {
        this.maxMergeLength = maxMergeLength;
    }

    public void setSingleOpaque(boolean singleOpaque) {
        this.singleOpaque = singleOpaque;
    }

    public void setSplitShift(int splitShift) {
        this.splitShift = splitShift;
        this.splitMask = (1 << splitShift) - 1;
    }

    public boolean isSingleOpaque() {
        return singleOpaque;
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
        for (int x1 = ny - 1; x1 < dy;) {
            generateMaskY(x1);
            mergeAndGenerateFacesY(faces, ++x1);
        }
    }

    private void meshZ(List<Face>[] faces) {
        for (int x2 = nz - 1; x2 < dz;) {
            generateMaskZ(x2);
            mergeAndGenerateFacesZ(faces, ++x2);
        }
    }

    private void generateMaskX(int x0) {
        int n = 0;
        for (int x2 = 0; x2 < dz; x2++)
            for (int x1 = ny; x1 < dy; x1++, n++)
                generateMaskX(x0, x1, x2, n);
    }

    private void generateMaskY(int x1) {
        int n = 0;
        for (int x0 = 0; x0 < dx; x0++)
            for (int x2 = 0; x2 < dz; x2++, n++)
                generateMaskY(x0, x1, x2, n);
    }

    private void generateMaskZ(int x2) {
        int n = 0;
        for (int x1 = ny; x1 < dy; x1++)
            for (int x0 = 0; x0 < dx; x0++, n++)
                generateMaskZ(x0, x1, x2, n);
    }

    private void generateMaskX(int x0, int x1, int x2, int n) {
        int a = at(x0, x1, x2) & 0xFF;
        int b = at(x0 + 1, x1, x2) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0) {
            m[n] = (singleOpaque ? 1 : a) | neighborsX(x0 + 1, x1, x2) << 8;
        } else
            m[n] = (singleOpaque ? 1 : b) | (x0 >= 0 ? neighborsX(x0, x1, x2) << 8 : 0) | 1 << 31;
    }

    private int neighborsX(int x0, int x1, int x2) {
        int n0 = at(x0, x1 - 1, x2 - 1) != 0 ? 1 : 0;
        int n1 = at(x0, x1, x2 - 1) != 0 ? 2 : 0;
        int n2 = at(x0, x1 + 1, x2 - 1) != 0 ? 4 : 0;
        int n3 = at(x0, x1 + 1, x2) != 0 ? 8 : 0;
        int n4 = at(x0, x1 + 1, x2 + 1) != 0 ? 16 : 0;
        int n5 = at(x0, x1, x2 + 1) != 0 ? 32 : 0;
        int n6 = at(x0, x1 - 1, x2 + 1) != 0 ? 64 : 0;
        int n7 = at(x0, x1 - 1, x2) != 0 ? 128 : 0;
        return n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7;
    }

    private void generateMaskY(int x0, int x1, int x2, int n) {
        int a = at(x0, x1, x2) & 0xFF;
        int b = at(x0, x1 + 1, x2) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0) {
            m[n] = (singleOpaque ? 1 : a) | neighborsY(x0, x1 + 1, x2) << 8;
        } else
            m[n] = (singleOpaque ? 1 : b) | (x1 >= 0 ? neighborsY(x0, x1, x2) << 8 : 0) | 1 << 31;
    }

    private int neighborsY(int x0, int x1, int x2) {
        int n0 = at(x0 - 1, x1, x2 - 1) != 0 ? 1 : 0;
        int n1 = at(x0, x1, x2 - 1) != 0 ? 2 : 0;
        int n2 = at(x0 + 1, x1, x2 - 1) != 0 ? 4 : 0;
        int n3 = at(x0 + 1, x1, x2) != 0 ? 8 : 0;
        int n4 = at(x0 + 1, x1, x2 + 1) != 0 ? 16 : 0;
        int n5 = at(x0, x1, x2 + 1) != 0 ? 32 : 0;
        int n6 = at(x0 - 1, x1, x2 + 1) != 0 ? 64 : 0;
        int n7 = at(x0 - 1, x1, x2) != 0 ? 128 : 0;
        return n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7;
    }

    private void generateMaskZ(int x0, int x1, int x2, int n) {
        int a = at(x0, x1, x2) & 0xFF;
        int b = at(x0, x1, x2 + 1) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0)
            m[n] = (singleOpaque ? 1 : a) | neighborsZ(x0, x1, x2 + 1) << 8;
        else
            m[n] = (singleOpaque ? 1 : b) | (x2 >= 0 ? neighborsZ(x0, x1, x2) << 8 : 0) | 1 << 31;
    }

    private int neighborsZ(int x0, int x1, int x2) {
        int n0 = at(x0 - 1, x1 - 1, x2) != 0 ? 1 : 0;
        int n1 = at(x0, x1 - 1, x2) != 0 ? 2 : 0;
        int n2 = at(x0 + 1, x1 - 1, x2) != 0 ? 4 : 0;
        int n3 = at(x0 + 1, x1, x2) != 0 ? 8 : 0;
        int n4 = at(x0 + 1, x1 + 1, x2) != 0 ? 16 : 0;
        int n5 = at(x0, x1 + 1, x2) != 0 ? 32 : 0;
        int n6 = at(x0 - 1, x1 + 1, x2) != 0 ? 64 : 0;
        int n7 = at(x0 - 1, x1, x2) != 0 ? 128 : 0;
        return n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7;
    }

    private void mergeAndGenerateFacesX(List<Face>[] faces, int x0) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dz; j++)
            for (i = ny; i < dy; i += incr, n += incr)
                incr = mergeAndGenerateFaceX(faces, x0, n, i, j);
    }

    private void mergeAndGenerateFacesY(List<Face>[] faces, int x1) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dx; j++)
            for (i = 0; i < dz; i += incr, n += incr)
                incr = mergeAndGenerateFaceY(faces, x1, n, i, j);
    }

    private void mergeAndGenerateFacesZ(List<Face>[] faces, int x2) {
        int i, j, n, incr;
        for (j = ny, n = 0; j < dy; j++)
            for (i = 0; i < dx; i += incr, n += incr)
                incr = mergeAndGenerateFaceZ(faces, x2, n, i, j);
    }

    private int mergeAndGenerateFaceX(List<Face>[] faces, int x0, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthX(mn, n, i);
        int h = determineHeightX(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, x0, 0 + (mn > 0 ? 1 : 0), (short) mn);
        faces[f.s].add(f);
        eraseMaskX(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceY(List<Face>[] faces, int x1, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthY(mn, n, i);
        int h = determineHeightY(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, x1, 2 + (mn > 0 ? 1 : 0), (short) mn);
        faces[f.s].add(f);
        eraseMaskY(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceZ(List<Face>[] faces, int x2, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthZ(mn, n, i);
        int h = determineHeightZ(mn, n, j, w);
        Face f = new Face(i, j, i + w, j + h, x2, 4 + (mn > 0 ? 1 : 0), (short) mn);
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
        while (w < maxMergeLength && n + w < dy * dz && ((i + w) & splitMask) != 0 && i + w < dy && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthY(int c, int n, int i) {
        int w = 1;
        while (w < maxMergeLength && n + w < dz * dx && ((i + w) & splitMask) != 0 && i + w < dz && c == m[n + w])
            w++;
        return w;
    }

    private int determineWidthZ(int c, int n, int i) {
        int w = 1;
        while (w < maxMergeLength && n + w < dx * dy && ((i + w) & splitMask) != 0 && i + w < dx && c == m[n + w])
            w++;
        return w;
    }

    private int determineHeightX(int c, int n, int j, int w) {
        int h;
        for (h = 1; h < maxMergeLength && ((j + h) & splitMask) != 0 && j + h < dz; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dy])
                    return h;
        return h;
    }

    private int determineHeightY(int c, int n, int j, int w) {
        int h;
        for (h = 1; h < maxMergeLength && ((j + h) & splitMask) != 0 && j + h < dx; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dz])
                    return h;
        return h;
    }

    private int determineHeightZ(int c, int n, int j, int w) {
        int h;
        for (h = 1; h < maxMergeLength && ((j + h) & splitMask) != 0 && j + h < dy; h++)
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dx])
                    return h;
        return h;
    }
}
