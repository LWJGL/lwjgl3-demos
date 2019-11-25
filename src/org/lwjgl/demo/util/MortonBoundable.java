package org.lwjgl.demo.util;

public interface MortonBoundable<T> extends Boundable<T>, Comparable<T> {
    long morton();
    void morton(long code);
}
