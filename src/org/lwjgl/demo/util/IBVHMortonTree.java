/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import java.util.*;

/**
 * Bounding Volume Hierarchy for integer lattices using morton code
 * partitioning.
 * 
 * @author Kai Burjack
 */
public class IBVHMortonTree<T extends MortonBoundable<T>> {
    public static final int SIDE_X_NEG = 0;
    public static final int SIDE_X_POS = 1;
    public static final int SIDE_Y_NEG = 2;
    public static final int SIDE_Y_POS = 3;
    public static final int SIDE_Z_NEG = 4;
    public static final int SIDE_Z_POS = 5;

    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;

    public static final int MAX_POINTS_IN_NODE = 8;

    public IBVHMortonTree<T> parent;
    public IBVHMortonTree<T> left;
    public IBVHMortonTree<T> right;
    public IBVHMortonTree<T>[] ropes;
    public byte splitAxis;
    public short splitPos;
    public int index;
    public int first, last = -1;
    public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    IBVHMortonTree(IBVHMortonTree<T> parent, int first, int last) {
        this.parent = parent;
        this.first = first;
        this.last = last;
    }

    IBVHMortonTree(IBVHMortonTree<T> parent, List<T> voxels, int first, int last) {
        this.parent = parent;
        this.first = first;
        this.last = last;
        for (int i = first; i <= last; i++) {
            T v = voxels.get(i);
            int vminx = v.min(X), vminy = v.min(Y), vminz = v.min(Z);
            int vmaxx = v.max(X), vmaxy = v.max(Y), vmaxz = v.max(Z);
            this.minX = this.minX < vminx ? this.minX : vminx;
            this.minY = this.minY < vminy ? this.minY : vminy;
            this.minZ = this.minZ < vminz ? this.minZ : vminz;
            this.maxX = this.maxX > vmaxx ? this.maxX : vmaxx;
            this.maxY = this.maxY > vmaxy ? this.maxY : vmaxy;
            this.maxZ = this.maxZ > vmaxz ? this.maxZ : vmaxz;
        }
    }

    private static long expandBits(long v) {
        v &= 0x00000000001fffffL;
        v = (v | v << 32L) & 0x001f00000000ffffL;
        v = (v | v << 16L) & 0x001f0000ff0000ffL;
        v = (v | v << 8L) & 0x010f00f00f00f00fL;
        v = (v | v << 4L) & 0x10c30c30c30c30c3L;
        v = (v | v << 2L) & 0x1249249249249249L;
        return v;
    }

    private static long morton3d(int x, int y, int z) {
        return (expandBits(y) << 2L) + (expandBits(z) << 1L) + expandBits(x);
    }

    private static <T extends MortonBoundable<T>> int findSplit(List<T> sortedMortonCodes, int first, int last) {
        long firstCode = sortedMortonCodes.get(first).morton();
        long lastCode = sortedMortonCodes.get(last).morton();
        if (firstCode == lastCode)
            return first + last >> 1;
        int commonPrefix = Long.numberOfLeadingZeros(firstCode ^ lastCode);
        int split = first;
        int step = last - first;
        do {
            step = step + 1 >> 1;
            int newSplit = split + step;
            if (newSplit < last) {
                long splitCode = sortedMortonCodes.get(newSplit).morton();
                int splitPrefix = Long.numberOfLeadingZeros(firstCode ^ splitCode);
                if (splitPrefix > commonPrefix)
                    split = newSplit;
            }
        } while (step > 1);
        return split;
    }

    private boolean isLeafNode() {
        return left == null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends MortonBoundable<T>> IBVHMortonTree<T>[] newArray() {
        return new IBVHMortonTree[6];
    }

    private void processNode(IBVHMortonTree<T>[] ropes) {
        if (isLeafNode()) {
            this.ropes = ropes;
        } else {
            int sideLeft;
            int sideRight;
            if (splitAxis == X) {
                sideLeft = SIDE_X_NEG;
                sideRight = SIDE_X_POS;
            } else if (splitAxis == Y) {
                sideLeft = SIDE_Y_NEG;
                sideRight = SIDE_Y_POS;
            } else if (splitAxis == Z) {
                sideLeft = SIDE_Z_NEG;
                sideRight = SIDE_Z_POS;
            } else {
                throw new AssertionError();
            }
            this.left.ropes = newArray();
            System.arraycopy(ropes, 0, this.left.ropes, 0, 6);
            this.left.ropes[sideRight] = this.right;
            this.left.processNode(this.left.ropes);
            this.right.ropes = newArray();
            System.arraycopy(ropes, 0, this.right.ropes, 0, 6);
            this.right.ropes[sideLeft] = this.left;
            this.right.processNode(this.right.ropes);
        }
    }

    private void optimizeRopes() {
        for (int i = 0; i < 6; i++) {
            ropes[i] = optimizeRope(ropes[i], i);
        }
        if (left != null)
            left.optimizeRopes();
        if (right != null)
            right.optimizeRopes();
    }

    private int isParallelTo(int side) {
        if (splitAxis == X) {
            return side == SIDE_X_NEG ? -1 : side == SIDE_X_POS ? +1 : 0;
        } else if (splitAxis == Y) {
            return side == SIDE_Y_NEG ? -1 : side == SIDE_Y_POS ? +1 : 0;
        }
        return side == SIDE_Z_NEG ? -1 : side == SIDE_Z_POS ? +1 : 0;
    }

    private int min(int axis) {
        switch (axis) {
        case X:
            return minX;
        case Y:
            return minY;
        case Z:
            return minZ;
        default:
            throw new IllegalArgumentException();
        }
    }

    private int max(int axis) {
        switch (axis) {
        case X:
            return maxX;
        case Y:
            return maxY;
        case Z:
            return maxZ;
        default:
            throw new IllegalArgumentException();
        }
    }

    private IBVHMortonTree<T> optimizeRope(IBVHMortonTree<T> rope, int side) {
        if (rope == null) {
            return rope;
        }
        IBVHMortonTree<T> r = rope;
        while (!r.isLeafNode()) {
            int parallelSide = r.isParallelTo(side);
            if (parallelSide == +1) {
                r = r.left;
            } else if (parallelSide == -1) {
                r = r.right;
            } else {
                if (r.splitPos < min(r.splitAxis)) {
                    r = r.right;
                } else if (r.splitPos > max(r.splitAxis)) {
                    r = r.left;
                } else {
                    break;
                }
            }
        }
        return r;
    }

    public static <T extends MortonBoundable<T>> IBVHMortonTree<T> build(List<T> voxels, int maxVoxelsPerNode,
            int maxDepth) {
        voxels.forEach(v -> v
                .morton(morton3d((v.max(X) + v.min(X)) / 2, (v.max(Y) + v.min(Y)) / 2, (v.max(Z) + v.min(Z)) / 2)));
        Collections.sort(voxels);
        IBVHMortonTree<T> root = build(null, voxels, 0, voxels.size() - 1, 0, maxVoxelsPerNode, maxDepth);
        root.processNode(root.ropes = newArray());
        root.optimizeRopes();
        return root;
    }

    private static <T extends MortonBoundable<T>> IBVHMortonTree<T> build(IBVHMortonTree<T> parent,
            List<T> mortonSortedTriangles, int first, int last, int depth, int maxVoxelsPerNode, int maxDepth) {
        if (first > last - maxVoxelsPerNode || depth >= maxDepth)
            return new IBVHMortonTree<T>(parent, mortonSortedTriangles, first, last);
        int split = findSplit(mortonSortedTriangles, first, last);
        IBVHMortonTree<T> tree = new IBVHMortonTree<T>(parent, first, last);
        tree.left = build(tree, mortonSortedTriangles, first, split, depth, maxVoxelsPerNode, maxDepth);
        tree.right = build(tree, mortonSortedTriangles, split + 1, last, depth, maxVoxelsPerNode, maxDepth);
        tree.minX = java.lang.Math.min(tree.left.minX, tree.right.minX);
        tree.minY = java.lang.Math.min(tree.left.minY, tree.right.minY);
        tree.minZ = java.lang.Math.min(tree.left.minZ, tree.right.minZ);
        tree.maxX = java.lang.Math.max(tree.left.maxX, tree.right.maxX);
        tree.maxY = java.lang.Math.max(tree.left.maxY, tree.right.maxY);
        tree.maxZ = java.lang.Math.max(tree.left.maxZ, tree.right.maxZ);
        int cxl = (tree.left.maxX + tree.left.minX);
        int cyl = (tree.left.maxY + tree.left.minY);
        int czl = (tree.left.maxZ + tree.left.minZ);
        int cxr = (tree.right.maxX + tree.right.minX);
        int cyr = (tree.right.maxY + tree.right.minY);
        int czr = (tree.right.maxZ + tree.right.minZ);
        int dx = (cxl - cxr) * (cxl - cxr);
        int dy = (cyl - cyr) * (cyl - cyr);
        int dz = (czl - czr) * (czl - czr);
        if (dx > dy && dx > dz) {
            tree.splitAxis = X;
            tree.splitPos = (short) ((cxl + cxr) * 0.5f);
        } else if (dy > dz) {
            tree.splitAxis = Y;
            tree.splitPos = (short) ((cyl + cyr) * 0.5f);
        } else {
            tree.splitAxis = Z;
            tree.splitPos = (short) ((czl + czr) * 0.5f);
        }
        return tree;
    }
}