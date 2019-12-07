/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.util.Arrays.*;

/**
 * Flood fill algorithm to compute face connectivity graph.
 * <p>
 * Idea from: https://tomcc.github.io/2014/08/31/visibility-1.html
 * 
 * @author Kai Burjack
 */
public class FaceConnectivity {
    private final byte[] vs;
    private final int w, h, d;
    private final int[] stack;
    private int stackPos;

    public FaceConnectivity(int w, int h, int d) {
        if (w < 1 || w > 256)
            throw new IllegalArgumentException("w");
        if (h < 1 || h > 256)
            throw new IllegalArgumentException("h");
        if (d < 1 || d > 256)
            throw new IllegalArgumentException("d");
        this.w = w;
        this.h = h;
        this.d = d;
        this.vs = new byte[w * h * d];
        this.stack = new int[w * h * d * 5];
    }

    private int idx(int x, int y, int z) {
        return x + w * (y + z * h);
    }

    public int computeConnectivity(byte[] ds) {
        fill(vs, (byte) 0);
        return computeX(ds) | (computeY(ds) << 6) | (computeZ(ds) << 12);
    }

    private int computeZ(byte[] ds) {
        int ret = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                ret |= floodFill(ds, x, y, 0, (byte) 3);
        return ret;
    }

    private int computeY(byte[] ds) {
        int ret = 0;
        for (int z = 0; z < d; z++)
            for (int x = 0; x < w; x++)
                ret |= floodFill(ds, x, 0, z, (byte) 2);
        return ret;
    }

    private int computeX(byte[] ds) {
        int ret = 0;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                ret |= floodFill(ds, 0, y, z, (byte) 1);
        return ret;
    }

    private int floodFill(byte[] ds, int x, int y, int z, byte p) {
        if (ds[idx(x, y, z)] != 0)
            return 0;
        stack[stackPos = 0] = x | y << 8 | z << 16;
        int ret = 0;
        while (stackPos >= 0) {
            int sv = stack[stackPos--], px = sv & 0xFF, py = sv >>> 8 & 0xFF, pz = sv >>> 16 & 0xFF;
            if (ds[idx(px, py, pz)] != 0 || vs[idx(px, py, pz)] == p)
                continue;
            vs[idx(px, py, pz)] = p;
            if (px < w - 1)
                stack[++stackPos] = px + 1 | py << 8 | pz << 16;
            else
                ret |= 2;
            if (px > 0) {
                if (p != 1)
                    stack[++stackPos] = px - 1 | py << 8 | pz << 16;
            } else
                ret |= 1;
            if (py < h - 1)
                stack[++stackPos] = px | py + 1 << 8 | pz << 16;
            else
                ret |= 8;
            if (py > 0) {
                if (p != 2)
                    stack[++stackPos] = px | py - 1 << 8 | pz << 16;
            } else
                ret |= 4;
            if (pz < d - 1)
                stack[++stackPos] = px | py << 8 | pz + 1 << 16;
            else
                ret |= 32;
            if (pz > 0) {
                if (p != 3)
                    stack[++stackPos] = px | py << 8 | pz - 1 << 16;
            } else
                ret |= 16;
        }
        return ret;
    }

}
