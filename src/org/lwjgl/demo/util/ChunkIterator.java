/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Math.*;
import static org.lwjgl.demo.util.ChunkIteratorVisitor.*;

import java.util.BitSet;
import java.util.PriorityQueue;

import org.joml.*;

/**
 * Iterates over chunk indices in front-to-back ordering.
 * 
 * @author Kai Burjack
 */
public class ChunkIterator {
    private final int w, h, d;
    private final FrustumIntersection fi = new FrustumIntersection();
    private final BitSet visited;
    private final PriorityQueue<Chunk> queue = new PriorityQueue<>(1024);
    private final Matrix4f vp = new Matrix4f();
    private float ox, oy, oz;

    private class Chunk implements Comparable<Chunk> {
        float d;
        byte x, y, z;

        Chunk(byte x, byte y, byte z) {
            this.d = distance2(x, y, z);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Chunk o) {
            return Float.compare(d, o.d);
        }

        @Override
        public String toString() {
            return "Chunk [x=" + x + ", y=" + y + ", z=" + z + "]";
        }
    }

    public ChunkIterator(int w, int h, int d) {
        if (w < 1 || w > 256)
            throw new IllegalArgumentException("w");
        if (h < 1 || h > 256)
            throw new IllegalArgumentException("h");
        if (d < 1 || d > 256)
            throw new IllegalArgumentException("d");
        this.w = w;
        this.h = h;
        this.d = d;
        this.visited = new BitSet(w * h * d);
    }

    private float distance2(int x1, int y1, int z1) {
        float dx = x1 - ox, dy = y1 - oy, dz = z1 - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void remember(int x, int y, int z) {
        visited.set(x + w * (y + h * z));
    }

    private boolean has(int x, int y, int z) {
        return visited.get(x + w * (y + h * z));
    }

    public boolean visible(int x, int y, int z) {
        return fi.testAab(x, y, z, x + 1, y + 1, z + 1);
    }

    /**
     * @param rp       position relative to the chunk's origin
     * @param view     view orientation
     * @param proj     projection matrix
     * @param consumer consumes chunk indices; iteration continues as long as it
     *                 returns <code>true</code>
     */
    public void iterateFrontToBack(Vector3f rp, Quaternionf view, Matrix4f proj, ChunkIteratorVisitor consumer) {
        ox = min(max(rp.x, 0.0f), w) - 0.5f;
        oy = min(max(rp.y, 0.0f), h) - 0.5f;
        oz = min(max(rp.z, 0.0f), d) - 0.5f;
        Vector3f forward = view.positiveZ(new Vector3f()).negate();
        fi.set(vp.set(proj).rotate(view).translate(-rp.x, -rp.y, -rp.z));
        add((int) ox, (int) oy, (int) oz);
        double minD = Double.NaN;
        loop: while (!queue.isEmpty()) {
            Chunk c = queue.remove();
            if (c.d < minD)
                continue;
            minD = c.d;
            int r = consumer.visit(c.x, c.y, c.z, c.d);
            switch (r) {
            case ABORT: break loop;
            case CANCEL: continue;
            }
            if (c.x < w - 1 && forward.x >= 0 && !has(c.x + 1, c.y, c.z) && visible(c.x + 1, c.y, c.z))
                add(c.x + 1, c.y, c.z);
            if (c.x > 0 && forward.x <= 0 && !has(c.x - 1, c.y, c.z) && visible(c.x - 1, c.y, c.z))
                add(c.x - 1, c.y, c.z);
            if (c.y < h - 1 && forward.y >= 0 && !has(c.x, c.y + 1, c.z) && visible(c.x, c.y + 1, c.z))
                add(c.x, c.y + 1, c.z);
            if (c.y > 0 && forward.y <= 0 && !has(c.x, c.y - 1, c.z) && visible(c.x, c.y - 1, c.z))
                add(c.x, c.y - 1, c.z);
            if (c.z < d - 1 && forward.z >= 0 && !has(c.x, c.y, c.z + 1) && visible(c.x, c.y, c.z + 1))
                add(c.x, c.y, c.z + 1);
            if (c.z > 0 && forward.z <= 0 && !has(c.x, c.y, c.z - 1) && visible(c.x, c.y, c.z - 1))
                add(c.x, c.y, c.z - 1);
        }
        queue.clear();
    }

    private void add(int x, int y, int z) {
        remember(x, y, z);
        queue.add(new Chunk((byte) x, (byte) y, (byte) z));
    }
}
