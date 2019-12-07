/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import java.util.List;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * @author Kai Burjack
 */
public class ArrayQuadtree<T> {
    private final FrustumIntersection fi = new FrustumIntersection();
    private final int[][] innerNodes;
    private final Object[] leafNodes;
    private final int sx, sz, levels;

    public ArrayQuadtree(int levels, int scaleX, int scaleZ) {
        if (levels < 0)
            throw new IllegalArgumentException("levels");
        this.levels = levels;
        this.sx = scaleX;
        this.sz = scaleZ;
        this.innerNodes = new int[levels][];
        this.leafNodes = new Object[1 << (levels << 1)];
        for (int i = 0; i < levels; i++)
            this.innerNodes[i] = new int[1 << (i << 1)];
    }

    private static int idx(int x, int z, int lvl) {
        return z * (1 << lvl) + x;
    }

    public List<T> visible(Matrix4f m, int height, List<T> visible) {
        visible(m, visible, 0, 0, 0, height);
        return visible;
    }

    public List<T> invisible(Matrix4f m, int height, List<T> invisible) {
        invisible(m, invisible, 0, 0, 0, height);
        return invisible;
    }

    private void set(int x, int z, T v, int incr) {
        leafNodes[idx(x, z, levels)] = v;
        for (int lvl = levels - 1; lvl >= 0; lvl--)
            innerNodes[lvl][idx(x >>>= 1, z >>>= 1, lvl)] += incr;
    }

    public void set(int x, int z, T v) {
        set(x, z, v, 1);
    }

    public void unset(int x, int z) {
        set(x, z, null, -1);
    }

    private void visible(Matrix4f m, List<T> visible, int level, int x, int z, int height) {
        if (level == levels)
            visibleLeaf(m, visible, level, x, z, height);
        else
            visibleInnerNode(m, visible, level, x, z, height);
    }

    private void invisible(Matrix4f m, List<T> invisible, int level, int x, int z, int height) {
        if (level == levels)
            invisibleLeaf(m, invisible, level, x, z, height);
        else
            invisibleInnerNode(m, invisible, level, x, z, height);
    }

    private void visibleLeaf(Matrix4f m, List<T> visible, int level, int x, int z, int height) {
        if (outsideFrustum(m, level, x, z, height))
            return;
        @SuppressWarnings("unchecked")
        T v = (T) leafNodes[idx(x, z, level)];
        if (v != null)
            visible.add(v);
    }

    private void invisibleLeaf(Matrix4f m, List<T> invisible, int level, int x, int z, int height) {
        if (!outsideFrustum(m, level, x, z, height))
            return;
        @SuppressWarnings("unchecked")
        T v = (T) leafNodes[idx(x, z, level)];
        if (v != null)
            invisible.add(v);
    }

    private boolean outsideFrustum(Matrix4f m, int level, int x, int z, int height) {
        return intersect(m, level, x, z, height) >= 0;
    }

    private int intersect(Matrix4f m, int level, int x, int z, int height) {
        int w = 1 << levels - level;
        return fi.set(m).intersectAab(x * sx, 0, z * sz, (x + w) * sx, height, (z + w) * sz);
    }

    private boolean fullyInsideFrustum(Matrix4f m, int level, int x, int z, int height) {
        return intersect(m, level, x, z, height) == FrustumIntersection.INSIDE;
    }

    private void visibleInnerNode(Matrix4f m, List<T> visible, int level, int x, int z, int height) {
        if (innerNodes[level][idx(x, z, level)] == 0 || outsideFrustum(m, level, x, z, height))
            return;
        for (int i = 0; i < 4; i++)
            visible(m, visible, level + 1, (x << 1) + (i & 1), (z << 1) + (i >>> 1 & 1), height);
    }

    private void invisibleInnerNode(Matrix4f m, List<T> invisible, int level, int x, int z, int height) {
        if (innerNodes[level][idx(x, z, level)] == 0 || fullyInsideFrustum(m, level, x, z, height))
            return;
        for (int i = 0; i < 4; i++)
            invisible(m, invisible, level + 1, (x << 1) + (i & 1), (z << 1) + (i >>> 1 & 1), height);
    }
}
