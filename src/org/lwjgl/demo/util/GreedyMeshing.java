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

        public int lod() {
            return Math.min(32 - numberOfLeadingZeros(u0 ^ u1),
                            32 - numberOfLeadingZeros(v0 ^ v1));
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

        private Face(int u0, int v0, int u1, int v1, int p, int s, int v, int tx, int ty) {
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

    private static final int[] NEIGHBOR_CONFIGS = computeNeighborConfigs();

    private final int[] m;
    private byte[] vs;
    private int dx, dy, dz, nx, ny, nz;
    private boolean singleOpaque;
    private int maxMergeLength = Integer.MAX_VALUE;
    private int splitShift = 16;
    private int splitMask = (1 << splitShift) - 1;

    private static int[] computeNeighborConfigs() {
        int[] offs = new int[256];
        for (int i = 0; i < 256; i++) {
            boolean cxny = (i & 1<<0) == 1<<0, nxny = (i & 1<<1) == 1<<1, nxcy = (i & 1<<2) == 1<<2, nxpy = (i & 1<<3) == 1<<3;
            boolean cxpy = (i & 1<<4) == 1<<4, pxpy = (i & 1<<5) == 1<<5, pxcy = (i & 1<<6) == 1<<6, pxny = (i & 1<<7) == 1<<7;
            int fnunv = (cxny ? 1 : 0) + (nxny ? 2 : 0) + (nxcy ? 4 : 0);
            int fpunv = (cxny ? 1 : 0) + (pxny ? 2 : 0) + (pxcy ? 4 : 0);
            int fnupv = (cxpy ? 1 : 0) + (nxpy ? 2 : 0) + (nxcy ? 4 : 0);
            int fpupv = (cxpy ? 1 : 0) + (pxpy ? 2 : 0) + (pxcy ? 4 : 0);
            offs[i] = fnunv | fpunv << 3 | fnupv << 6 | fpupv << 9;
        }
        return offs;
    }

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
            m[n] = (singleOpaque ? 1 : a) | neighborsX(x + 1, y, z) << 8;
        } else
            m[n] = (singleOpaque ? 1 : b) | (x >= 0 ? neighborsX(x, y, z) << 8 : 0) | 1 << 31;
    }

    private int neighborsX(int x, int y, int z) {
        // UV = YZ
        int n0 = at(x, y, z - 1) != 0 ? 1 : 0;
        int n1 = at(x, y - 1, z - 1) != 0 ? 2 : 0;
        int n2 = at(x, y - 1, z) != 0 ? 4 : 0;
        int n3 = at(x, y - 1, z + 1) != 0 ? 8 : 0;
        int n4 = at(x, y, z + 1) != 0 ? 16 : 0;
        int n5 = at(x, y + 1, z + 1) != 0 ? 32 : 0;
        int n6 = at(x, y + 1, z) != 0 ? 64 : 0;
        int n7 = at(x, y + 1, z - 1) != 0 ? 128 : 0;
        return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
    }

    private void generateMaskY(int x, int y, int z, int n) {
        int a = at(x, y, z) & 0xFF;
        int b = at(x, y + 1, z) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0) {
            m[n] = (singleOpaque ? 1 : a) | neighborsY(x, y + 1, z) << 8;
        } else
            m[n] = (singleOpaque ? 1 : b) | (y >= 0 ? neighborsY(x, y, z) << 8 : 0) | 1 << 31;
    }

    private int neighborsY(int x, int y, int z) {
        // UV = ZX
        int n0 = at(x - 1, y, z) != 0 ? 1 : 0;
        int n1 = at(x - 1, y, z - 1) != 0 ? 2 : 0;
        int n2 = at(x, y, z - 1) != 0 ? 4 : 0;
        int n3 = at(x + 1, y, z - 1) != 0 ? 8 : 0;
        int n4 = at(x + 1, y, z) != 0 ? 16 : 0;
        int n5 = at(x + 1, y, z + 1) != 0 ? 32 : 0;
        int n6 = at(x, y, z + 1) != 0 ? 64 : 0;
        int n7 = at(x - 1, y, z + 1) != 0 ? 128 : 0;
        return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
    }

    private void generateMaskZ(int x, int y, int z, int n) {
        int a = at(x, y, z) & 0xFF;
        int b = at(x, y, z + 1) & 0xFF;
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0)
            m[n] = (singleOpaque ? 1 : a) | neighborsZ(x, y, z + 1) << 8;
        else
            m[n] = (singleOpaque ? 1 : b) | (z >= 0 ? neighborsZ(x, y, z) << 8 : 0) | 1 << 31;
    }

    private int neighborsZ(int x, int y, int z) {
        // UV = XY
        int n0 = at(x, y - 1, z) != 0 ? 1 : 0;
        int n1 = at(x - 1, y - 1, z) != 0 ? 2 : 0;
        int n2 = at(x - 1, y, z) != 0 ? 4 : 0;
        int n3 = at(x - 1, y + 1, z) != 0 ? 8 : 0;
        int n4 = at(x, y + 1, z) != 0 ? 16 : 0;
        int n5 = at(x + 1, y + 1, z) != 0 ? 32 : 0;
        int n6 = at(x + 1, y, z) != 0 ? 64 : 0;
        int n7 = at(x + 1, y - 1, z) != 0 ? 128 : 0;
        return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
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
