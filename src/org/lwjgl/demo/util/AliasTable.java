/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import java.util.*;

import org.joml.Random;

/**
 * Alias table for efficiently sampling from discrete distributions.
 * 
 * @author Kai Burjack
 */
public final class AliasTable {
    private final int[] a;
    private final float[] p;

    /**
     * @param ws will be modified by this constructor
     */
    public AliasTable(float... w) {
        int N = w.length;
        this.p = new float[N];
        this.a = new int[N];
        float avg = 1.0f / N;
        ArrayDeque<Integer> s = new ArrayDeque<Integer>(), l = new ArrayDeque<Integer>();
        for (int i = 0; i < N; ++i)
            if (w[i] >= avg)
                l.add(i);
            else
                s.add(i);
        while (!s.isEmpty() && !l.isEmpty()) {
            int sm = s.poll(), lg = l.poll();
            p[sm] = w[sm] * N;
            a[sm] = lg;
            w[lg] = w[lg] + w[sm] - avg;
            if (w[lg] >= avg)
                l.add(lg);
            else
                s.add(lg);
        }
        while (!s.isEmpty())
            p[s.poll()] = 1.0f;
        while (!l.isEmpty())
            p[l.poll()] = 1.0f;
    }

    public int next(Random r) {
        int column = r.nextInt(p.length);
        return r.nextFloat() < p[column] ? column : a[column];
    }
}
