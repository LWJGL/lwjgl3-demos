package org.lwjgl.demo.util;

import static java.lang.Math.*;

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
    public static class Face {
        public static final byte SIDE_NX = 0;
        public static final byte SIDE_PX = 1;
        public static final byte SIDE_NY = 2;
        public static final byte SIDE_PY = 3;
        public static final byte SIDE_NZ = 4;
        public static final byte SIDE_PZ = 5;

        public short u0, v0, u1, v1, p, s;

        public Face(int u0, int v0, int u1, int v1, int p, int s) {
            this.u0 = (short) u0;
            this.v0 = (short) v0;
            this.u1 = (short) u1;
            this.v1 = (short) v1;
            this.p = (short) p;
            this.s = (short) s;
        }
    }

    private final int[] m;
    private byte[] vs;
    private int dx, dy, dz;
    private boolean singleOpaque;
    private int maxMergeLength = Integer.MAX_VALUE;
    private int splitShift = 16;
    private int splitMask = (1 << splitShift) - 1;

    public GreedyMeshing(int dx, int dy, int dz) {
        if (dx < 1 || dx > Short.MAX_VALUE)
            throw new IllegalArgumentException("dx");
        if (dy < 1 || dy > Short.MAX_VALUE)
            throw new IllegalArgumentException("dy");
        if (dz < 1 || dz > Short.MAX_VALUE)
            throw new IllegalArgumentException("dz");
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
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
        return vs[x + 1 + (dx + 2) * (y + 1 + (dy + 2) * (z + 1))];
    }

    public void mesh(byte[] vs, List<Face> faces) {
        this.vs = vs;
        meshX(faces);
        meshY(faces);
        meshZ(faces);
    }

    private void meshX(List<Face> faces) {
        for (int x0 = -1; x0 < dx;) {
            generateMaskX(x0);
            x0++;
            mergeAndGenerateFacesX(faces, x0);
        }
    }

    private void meshY(List<Face> faces) {
        for (int x1 = -1; x1 < dy;) {
            generateMaskY(x1);
            x1++;
            mergeAndGenerateFacesY(faces, x1);
        }
    }

    private void meshZ(List<Face> faces) {
        for (int x2 = -1; x2 < dz;) {
            generateMaskZ(x2);
            x2++;
            mergeAndGenerateFacesZ(faces, x2);
        }
    }

    private void generateMaskX(int x0) {
        int n = 0;
        for (int x2 = 0; x2 < dz; x2++)
            for (int x1 = 0; x1 < dy; x1++, n++)
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
        for (int x1 = 0; x1 < dy; x1++)
            for (int x0 = 0; x0 < dx; x0++, n++)
                generateMaskZ(x0, x1, x2, n);
    }

    private void generateMaskX(int x0, int x1, int x2, int n) {
        writeMask(n, at(x0, x1, x2), at(x0 + 1, x1, x2));
    }

    private void generateMaskY(int x0, int x1, int x2, int n) {
        writeMask(n, at(x0, x1, x2), at(x0, x1 + 1, x2));
    }

    private void generateMaskZ(int x0, int x1, int x2, int n) {
        writeMask(n, at(x0, x1, x2), at(x0, x1, x2 + 1));
    }

    private void writeMask(int n, int a, int b) {
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0)
            m[n] = singleOpaque ? 1 : a;
        else
            m[n] = -(singleOpaque ? 1 : b);
    }

    private void mergeAndGenerateFacesX(List<Face> faces, int x0) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dz; j++)
            for (i = 0; i < dy; i += incr, n += incr)
                incr = mergeAndGenerateFaceX(faces, x0, n, i, j);
    }

    private void mergeAndGenerateFacesY(List<Face> faces, int x1) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dx; j++)
            for (i = 0; i < dz; i += incr, n += incr)
                incr = mergeAndGenerateFaceY(faces, x1, n, i, j);
    }

    private void mergeAndGenerateFacesZ(List<Face> faces, int x2) {
        int i, j, n, incr;
        for (j = 0, n = 0; j < dy; j++)
            for (i = 0; i < dx; i += incr, n += incr)
                incr = mergeAndGenerateFaceZ(faces, x2, n, i, j);
    }

    private int mergeAndGenerateFaceX(List<Face> faces, int x0, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthX(mn, n, i);
        int h = determineHeightX(mn, n, j, w);
        faces.add(new Face(i, j, i + w, j + h, x0, 0 + (m[n] > 0 ? 1 : 0)));
        eraseMaskX(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceY(List<Face> faces, int x1, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthY(mn, n, i);
        int h = determineHeightY(mn, n, j, w);
        faces.add(new Face(i, j, i + w, j + h, x1, 2 + (m[n] > 0 ? 1 : 0)));
        eraseMaskY(n, w, h);
        return w;
    }

    private int mergeAndGenerateFaceZ(List<Face> faces, int x2, int n, int i, int j) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        int w = determineWidthZ(mn, n, i);
        int h = determineHeightZ(mn, n, j, w);
        faces.add(new Face(i, j, i + w, j + h, x2, 4 + (m[n] > 0 ? 1 : 0)));
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
