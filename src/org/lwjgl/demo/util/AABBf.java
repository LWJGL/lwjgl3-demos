package org.lwjgl.demo.util;

public class AABBf {
    public float minX, minY, minZ, maxX, maxY, maxZ;

    public float getMax(int k) {
        switch (k) {
        case 0: return maxX;
        case 1: return maxY;
        case 2: return maxZ;
        default: throw new IllegalArgumentException();
        }
    }

    public float getMin(int k) {
        switch (k) {
        case 0: return minX;
        case 1: return minY;
        case 2: return minZ;
        default: throw new IllegalArgumentException();
        }
    }
}
