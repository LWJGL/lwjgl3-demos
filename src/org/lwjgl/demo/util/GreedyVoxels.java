package org.lwjgl.demo.util;

/**
 * Greedy voxel merging.
 * 
 * @author Kai Burjack
 */
public class GreedyVoxels {
    @FunctionalInterface
    public static interface Callback {
        void voxel(int x0, int y0, int z0, int w, int h, int d, int v);
    }

    private final short[] m;
    private final Callback callback;
    private final int dx, dy, dz;
    private boolean mergeCulled;
    private boolean singleOpaque;

    public boolean isMergeCulled() {
        return mergeCulled;
    }

    public void setMergeCulled(boolean mergeCulled) {
        this.mergeCulled = mergeCulled;
    }

    public boolean isSingleOpaque() {
        return singleOpaque;
    }

    public void setSingleOpaque(boolean singleOpaque) {
        this.singleOpaque = singleOpaque;
    }

    public GreedyVoxels(int dx, int dy, int dz, Callback callback) {
        if (dx < 1 || dx > Short.MAX_VALUE)
            throw new IllegalArgumentException("dx");
        if (dy < 1 || dy > Short.MAX_VALUE)
            throw new IllegalArgumentException("dy");
        if (dz < 1 || dz > Short.MAX_VALUE)
            throw new IllegalArgumentException("dz");
        this.callback = callback;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.m = new short[dx * dy * dz];
    }

    private int at(int x, int y, int z) {
        return x + dx * (y + dy * z);
    }

    public void merge(byte[] vs, boolean[] culled) {
        for (int z = 0; z < dz; z++)
            for (int y = 0; y < dy; y++)
                for (int x = 0; x < dx; x++) {
                    int i = at(x, y, z);
                    m[i] = (short) (culled[i] ? -1 : vs[i] & 0xFF);
                }
        for (int z = 0; z < dz; z++)
            for (int y = 0; y < dy; y++)
                for (int x = 0; x < dx; x++)
                    x += mergeAndGenerateFace(x, y, z) - 1;
    }

    private int mergeAndGenerateFace(int x, int y, int z) {
        int mn = m[at(x, y, z)];
        if (mn <= 0)
            return 1;
        int w = determineWidth(mn, x, y, z);
        int d = determineDepth(mn, x, y, z, w);
        int h = determineHeight(mn, x, y, z, w, d);
        callback.voxel(x, y, z, w, h, d, mn);
        eraseMask(x, y, z, w, h, d);
        return w;
    }

    private void eraseMask(int x, int y, int z, int w, int h, int d) {
        for (int zi = 0; zi < d; zi++)
            for (int yi = 0; yi < h; yi++)
                for (int xi = 0; xi < w; xi++)
                    m[at(x + xi, y + yi, z + zi)] = 0;
    }

    private boolean sameOrOccluded(int c, int mn) {
        return c == mn || (mergeCulled && mn == -1) || (singleOpaque && mn > 0);
    }

    private int determineWidth(int c, int x, int y, int z) {
        int w = 1;
        while (x + w < dx && sameOrOccluded(c, m[at(x + w, y, z)]))
            w++;
        return w;
    }

    private int determineDepth(int c, int x, int y, int z, int w) {
        int d = 1;
        for (; z + d < dz; d++)
            for (int xi = 0; xi < w; xi++)
                if (!sameOrOccluded(c, m[at(x + xi, y, z + d)]))
                    return d;
        return d;
    }

    private int determineHeight(int c, int x, int y, int z, int w, int d) {
        int h = 1;
        for (; y + h < dy; h++)
            for (int zi = 0; zi < d; zi++)
                for (int xi = 0; xi < w; xi++)
                    if (!sameOrOccluded(c, m[at(x + xi, y + h, z + zi)]))
                        return h;
        return h;
    }
}
