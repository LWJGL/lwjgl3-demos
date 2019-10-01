package org.lwjgl.demo.util;

public interface Boundable<T> {
    int min(int axis);
    int max(int axis);
    T splitLeft(int splitAxis, int splitPos);
    T splitRight(int splitAxis, int splitPos);
    boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
}
