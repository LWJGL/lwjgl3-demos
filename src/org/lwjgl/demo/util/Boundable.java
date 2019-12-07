/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

/**
 * @author Kai Burjack
 */
public interface Boundable<T> {
    int min(int axis);
    int max(int axis);
    default T splitLeft(int splitAxis, int splitPos) {
        throw new UnsupportedOperationException();
    }
    default T splitRight(int splitAxis, int splitPos) {
        throw new UnsupportedOperationException();
    }
    boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
}
