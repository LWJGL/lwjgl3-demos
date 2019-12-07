/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Math.*;

/**
 * Nelder-Mead algorithm for non-linear optimization.
 * <p>
 * This implementation is optimized for a three-dimensional input vector.
 * 
 * @author Kai Burjack
 */
public class NelderMead3D {

    @FunctionalInterface
    public interface FitnessFunction {
        float f(float x, float y, float z);
    }

    private final float[] s = new float[4 * 3];
    private final float[] f = new float[4];
    private final float reflect;
    private final float expand;
    private final float contract;
    private final float shrink;

    public NelderMead3D() {
        this(1.0f, 2.0f, 0.5f, 0.5f);
    }

    public NelderMead3D(float reflect, float expand, float contract, float shrink) {
        this.reflect = reflect;
        this.expand = expand;
        this.contract = contract;
        this.shrink = shrink;
    }

    public float minimize(float[] init, float d, float minDf, int N, FitnessFunction fn, float[] outMin) {
        s[0] = init[0];
        s[1] = init[1];
        s[2] = init[2];
        for (int i = 1; i < 4; i++) {
            s[3 * i] = init[0];
            s[3 * i + 1] = init[1];
            s[3 * i + 2] = init[2];
            s[3 * i + i - 1] += d;
        }
        for (int i = 0; i < 4; i++)
            f[i] = fn.f(s[3 * i], s[3 * i + 1], s[3 * i + 2]);
        int lo = 0, hi, nh, i;
        for (i = 0; i < N; i++) {
            lo = hi = nh = 0;
            for (int j = 0; j < 3; j++) {
                if (f[j + 1] < f[lo])
                    lo = j + 1;
                if (f[j + 1] > f[hi])
                    hi = j + 1;
                else if (f[j + 1] > f[nh])
                    nh = j + 1;
            }
            float a = abs(f[lo]), b = abs(f[hi]);
            if (2.0f * abs(a - b) <= (a + b) * minDf)
                break;
            float ox = 0.0f, oy = 0.0f, oz = 0.0f;
            for (int j = 0; j < 4; j++) {
                if (j == hi)
                    continue;
                ox += s[3 * j];
                oy += s[3 * j + 1];
                oz += s[3 * j + 2];
            }
            ox *= 0.33333333333f;
            oy *= 0.33333333333f;
            oz *= 0.33333333333f;
            float rx = ox + reflect * (ox - s[3 * hi]);
            float ry = oy + reflect * (oy - s[3 * hi + 1]);
            float rz = oz + reflect * (oz - s[3 * hi + 2]);
            float fr = fn.f(rx, ry, rz);
            if (fr < f[nh]) {
                if (!expand(fr, ox, oy, oz, lo, hi, fn))
                    reflect(fr, rx, ry, rz, hi);
            } else {
                if (!contract(ox, oy, oz, hi, fn))
                    shrink(lo, fn);
            }
        }
        outMin[0] = s[3 * lo];
        outMin[1] = s[3 * lo + 1];
        outMin[2] = s[3 * lo + 2];
        return f[lo];
    }

    private boolean expand(float fr, float ox, float oy, float oz, int lo, int hi, FitnessFunction fn) {
        if (fr < f[lo]) {
            float ex = ox + expand * (ox - s[3 * hi]);
            float ey = oy + expand * (oy - s[3 * hi + 1]);
            float ez = oz + expand * (oz - s[3 * hi + 2]);
            float fe = fn.f(ex, ey, ez);
            if (fe < fr) {
                s[3 * hi] = ex;
                s[3 * hi + 1] = ey;
                s[3 * hi + 2] = ez;
                f[hi] = fe;
                return true;
            }
        }
        return false;
    }

    private void reflect(float fr, float rx, float ry, float rz, int hi) {
        s[3 * hi] = rx;
        s[3 * hi + 1] = ry;
        s[3 * hi + 2] = rz;
        f[hi] = fr;
    }

    private boolean contract(float ox, float oy, float oz, int hi, FitnessFunction fn) {
        float cx = ox - contract * (ox - s[3 * hi]);
        float cy = oy - contract * (oy - s[3 * hi + 1]);
        float cz = oz - contract * (oz - s[3 * hi + 2]);
        float fc = fn.f(cx, cy, cz);
        if (fc < f[hi]) {
            s[3 * hi] = cx;
            s[3 * hi + 1] = cy;
            s[3 * hi + 2] = cz;
            f[hi] = fc;
            return true;
        }
        return false;
    }

    private void shrink(int lo, FitnessFunction fn) {
        for (int k = 0; k < 4; k++) {
            if (k == lo)
                continue;
            for (int i = 0; i < 3; i++)
                s[3 * k + i] = s[3 * lo + i] + shrink * (s[3 * k + i] - s[3 * lo + i]);
            f[k] = fn.f(s[3 * k], s[3 * k + 1], s[3 * k + 2]);
        }
    }

}
