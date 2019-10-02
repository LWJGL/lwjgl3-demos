package org.lwjgl.demo.util;

import static java.lang.Math.*;

import java.util.Arrays;
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

    private final short[] du = new short[3], dv = new short[3], q = new short[3], x = new short[3];
    private final short[] m;
    private final short dx, dy;
    private final short[] dims;

    public GreedyMeshing(int dx, int dy, int dz) {
        if (dx < 1 || dx > 255)
            throw new IllegalArgumentException("dx");
        if (dy < 1 || dy > 255)
            throw new IllegalArgumentException("dy");
        if (dz < 1 || dz > 255)
            throw new IllegalArgumentException("dz");
        this.dx = (short) dx;
        this.dy = (short) dy;
        this.m = new short[max(dx, dy) * max(dy, dz)];
        this.dims = new short[] { (short) dx, (short) dy, (short) dz };
    }

    private byte at(byte[] vs, int x, int y, int z) {
        return vs[x + dx * (y + dy * z)];
    }

    public void mesh(byte[] vs, List<Face> faces) {
        for (byte d = 0; d < 3; d++) {
            short u = (short) ((d + 1) % 3), v = (short) ((d + 2) % 3);
            Arrays.fill(q, (byte) 0);
            q[d] = 1;
            for (x[d] = -1; x[d] < dims[d];) {
                generateMask(vs, d, u, v, m);
                x[d]++;
                mergeAndGenerateFaces(faces, u, v, m, d);
            }
        }
    }

    private void generateMask(byte[] vs, byte d, short u, short v, short[] m) {
        int n = 0;
        for (x[v] = 0; x[v] < dims[v]; x[v]++)
            for (x[u] = 0; x[u] < dims[u]; x[u]++, n++)
                generateMask(vs, d, m, n);
    }

    private void generateMask(byte[] vs, byte d, short[] m, int n) {
        short a = x[d] >= 0 ? at(vs, x[0], x[1], x[2]) : 0;
        short b = x[d] < dims[d] - 1 ? at(vs, x[0] + q[0], x[1] + q[1], x[2] + q[2]) : 0;
        if (a == b)
            m[n] = 0;
        else if (a != 0)
            m[n] = a;
        else
            m[n] = (short) -b;
    }

    private void mergeAndGenerateFaces(List<Face> faces, short u, short v, short[] m, byte d) {
        for (short j = 0, n = 0; j < dims[v]; ++j)
            for (short i = 0, incr; i < dims[u]; i += incr, n += incr)
                incr = mergeAndGenerateFace(faces, u, v, m, n, j, i, d);
    }

    private short mergeAndGenerateFace(List<Face> faces, short u, short v, short[] m, short n, short j, short i,
            byte d) {
        if (m[n] == 0)
            return 1;
        short w = determineWidth(u, m, n, i, m[n]);
        short h = determineHeight(u, v, m, n, j, m[n], w);
        byte s = determineFaceRegion(u, v, m, n, j, i, d, w, h);
        addFace(faces, u, v, d, s);
        eraseMask(u, m, n, w, h);
        return w;
    }

    private void eraseMask(short u, short[] m, short n, short w, short h) {
        for (short l = 0; l < h; l++)
            for (short k = 0; k < w; k++)
                m[n + k + l * dims[u]] = 0;
    }

    private byte determineFaceRegion(short u, short v, short[] m, short n, short j, short i, byte d, short w, short h) {
        x[u] = i;
        x[v] = j;
        if (m[n] > 0) {
            du[d] = dv[d] = du[v] = dv[u] = 0;
            dv[v] = h;
            du[u] = w;
            return 0;
        } else {
            du[d] = dv[d] = du[u] = dv[v] = 0;
            du[v] = h;
            dv[u] = w;
            return 1;
        }
    }

    private short determineWidth(short u, short[] m, short n, short i, short c) {
        short w = 1;
        while (n + w < m.length && i + w < dims[u] && c == m[n + w])
            w++;
        return w;
    }

    private short determineHeight(short u, short v, short[] m, short n, short j, short c, short w) {
        short h;
        boolean done = false;
        for (h = 1; j + h < dims[v]; h++) {
            for (short k = 0; k < w; k++)
                if (c != m[n + k + h * dims[u]]) {
                    done = true;
                    break;
                }
            if (done)
                break;
        }
        return h;
    }

    private void addFace(List<Face> faces, short u, short v, byte d, byte s) {
        faces.add(new Face(x[u], x[v], x[u] + du[u] + dv[u], x[v] + du[v] + dv[v], x[d], (d << 1) + s));
    }
}
