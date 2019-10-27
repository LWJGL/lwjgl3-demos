package org.lwjgl.demo.util;

import static java.lang.Math.*;

import java.util.List;

/**
 * Greedy meshing based on the JavaScript code from
 * https://0fps.net/2012/07/07/meshing-minecraft-part-2/
 * 
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

        public byte u0, v0, u1, v1, p, s;

        public Face(int u0, int v0, int u1, int v1, int p, int s) {
            this.u0 = (byte) u0;
            this.v0 = (byte) v0;
            this.u1 = (byte) u1;
            this.v1 = (byte) v1;
            this.p = (byte) p;
            this.s = (byte) s;
        }
    }

    private final int[] q = new int[3], x = new int[3];
    private int n, u, v, d, i, j, w, h;
    private final int[] m;
    private final int dx, dy;
    private final int[] dims;
    private int dimsu, dimsv;
    private boolean singleOpaque;
    private byte[] vs;

    public GreedyMeshing(int dx, int dy, int dz) {
        if (dx < 1 || dx > 256)
            throw new IllegalArgumentException("dx");
        if (dy < 1 || dy > 256)
            throw new IllegalArgumentException("dy");
        if (dz < 1 || dz > 256)
            throw new IllegalArgumentException("dz");
        this.dx = dx;
        this.dy = dy;
        this.m = new int[max(dx, dy) * max(dy, dz)];
        this.dims = new int[] { dx, dy, dz };
    }

    public void setSingleOpaque(boolean singleOpaque) {
        this.singleOpaque = singleOpaque;
    }

    public boolean isSingleOpaque() {
        return singleOpaque;
    }

    private byte at(int x, int y, int z) {
        return vs[x + 1 + (dx + 2) * (y + 1 + (dy + 2) * (z + 1))];
    }

    public void mesh(byte[] vs, List<Face> faces) {
        this.vs = vs;
        for (d = 0; d < 3; d++) {
            u = ((d + 1) % 3);
            v = ((d + 2) % 3);
            dimsu = dims[u];
            dimsv = dims[v];
            q[0] = 0;
            q[1] = 0;
            q[2] = 0;
            q[d] = 1;
            for (x[d] = -1; x[d] < dims[d];) {
                generateMask();
                x[d]++;
                mergeAndGenerateFaces(faces);
            }
        }
    }

    private void generateMask() {
        n = 0;
        for (x[v] = 0; x[v] < dimsv; x[v]++)
            for (x[u] = 0; x[u] < dimsu; x[u]++, n++)
                generateMask2();
    }

    private void generateMask2() {
        int a = at(x[0], x[1], x[2]);
        int b = at(x[0] + q[0], x[1] + q[1], x[2] + q[2]);
        if (((a == 0) == (b == 0)))
            m[n] = 0;
        else if (a != 0)
            m[n] = singleOpaque ? 1 : a;
        else
            m[n] = -(singleOpaque ? 1 : b);
    }

    private void mergeAndGenerateFaces(List<Face> faces) {
        n = 0;
        int incr;
        for (j = 0, n = 0; j < dimsv; ++j)
            for (i = 0; i < dimsu; i += incr, n += incr)
                incr = mergeAndGenerateFace(faces);
    }

    private int mergeAndGenerateFace(List<Face> faces) {
        int mn = m[n];
        if (mn == 0)
            return 1;
        w = determineWidth(mn);
        h = determineHeight(mn);
        x[u] = i;
        x[v] = j;
        faces.add(new Face(i, j, i + w, j + h, x[d], (d << 1) + (m[n] > 0 ? 1 : 0)));
        eraseMask();
        return w;
    }

    private void eraseMask() {
        for (int l = 0; l < h; l++)
            for (int k = 0; k < w; k++)
                m[n + k + l * dimsu] = 0;
    }

    private int determineWidth(int c) {
        int w = 1;
        while (n + w < dimsu * dimsv && i + w < dimsu && c == m[n + w])
            w++;
        return w;
    }

    private int determineHeight(int c) {
        int h;
        boolean done = false;
        for (h = 1; j + h < dimsv; h++) {
            for (int k = 0; k < w; k++)
                if (c != m[n + k + h * dimsu]) {
                    done = true;
                    break;
                }
            if (done)
                break;
        }
        return h;
    }
}
