/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

/**
 * @author Kai Burjack
 */
public interface MortonBoundable<T> extends Boundable<T>, Comparable<T> {
    long morton();
    void morton(long code);
}
