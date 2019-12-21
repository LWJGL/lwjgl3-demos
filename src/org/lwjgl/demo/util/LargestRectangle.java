/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

import static java.lang.Math.*;

import java.util.Arrays;
import java.util.List;

/**
 * @author Kai Burjack
 */
public class LargestRectangle {
    private static final byte[] a = new byte[256 * 256];
    private static final short[] w = new short[256 * 256];
    private static final short[] d = new short[256 * 256];

    public static void merge(byte[] arr, int width, int depth, int layer, int minArea, List<KDTreei.Voxel> voxels) {
        System.arraycopy(arr, width * depth * layer, a, 0, width * depth);
        int maxArea = 0;
        do {
            Arrays.fill(w, (short) 0);
            Arrays.fill(d, (short) 0);
            int c0 = 0, r0 = 0, c1 = 0, r1 = 0;
            maxArea = 0;
            for (int r = 0; r < depth; r++)
                for (int c = 0; c < width; c++) {
                    if (a[r * width + c] != ~0)
                        continue;
                    if (r == 0)
                        d[r * width + c] = 1;
                    else
                        d[r * width + c] = (short) (d[(r - 1) * width + c] + 1);
                    if (c == 0)
                        w[r * width + c] = 1;
                    else
                        w[r * width + c] = (short) (w[r * width + c - 1] + 1);
                    int minw = w[r * width + c];
                    for (int dh = 0; dh < d[r * width + c]; dh++) {
                        minw = min(minw, w[(r - dh) * width + c]);
                        int area = (dh + 1) * minw;
                        if (area > maxArea) {
                            maxArea = area;
                            r0 = r - dh;
                            c0 = c - minw + 1;
                            r1 = r;
                            c1 = c;
                        }
                    }
                }
            for (int r = r0; r <= r1; r++)
                for (int c = c0; c <= c1; c++)
                    a[r * width + c] = 1;
            if (maxArea > 0)
                voxels.add(new KDTreei.Voxel(c0, layer, r0, c1 - c0, 0, r1 - r0, 0));
        } while (maxArea >= minArea);
        for (int r = 0; r < depth; r++)
            for (int c = 0; c < width; c++) {
                if (a[r * width + c] != ~0)
                    continue;
                voxels.add(new KDTreei.Voxel(c, layer, r, 0));
            }
    }
}
