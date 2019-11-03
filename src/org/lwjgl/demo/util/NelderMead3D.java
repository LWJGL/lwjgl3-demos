package org.lwjgl.demo.util;

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

    public float minimize(float[] init, float d, float minDf, int N, FitnessFunction fn, float[] outMin) {
        float reflect = 1.0f, expand = 2.0f, contract = 0.5f, shrink = 0.5f;
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
            if (f[1] < f[lo])
                lo = 1;
            if (f[1] > f[hi]) {
                nh = hi;
                hi = 1;
            } else if (f[1] > f[nh])
                nh = 1;
            if (f[2] < f[lo])
                lo = 2;
            if (f[2] > f[hi]) {
                nh = hi;
                hi = 2;
            } else if (f[2] > f[nh])
                nh = 2;
            if (f[3] < f[lo])
                lo = 3;
            if (f[3] > f[hi]) {
                nh = hi;
                hi = 3;
            } else if (f[3] > f[nh])
                nh = 3;
            float a = Math.abs(f[lo]), b = Math.abs(f[hi]);
            if (2.0f * Math.abs(a - b) <= (a + b) * minDf)
                break;
            float ox = 0.0f, oy = 0.0f, oz = 0.0f;
            if (0 != hi) {
                ox += s[0];
                oy += s[1];
                oz += s[2];
            }
            if (1 != hi) {
                ox += s[3];
                oy += s[4];
                oz += s[5];
            }
            if (2 != hi) {
                ox += s[6];
                oy += s[7];
                oz += s[8];
            }
            if (3 != hi) {
                ox += s[9];
                oy += s[10];
                oz += s[11];
            }
            ox *= 0.33333333333f;
            oy *= 0.33333333333f;
            oz *= 0.33333333333f;
            float rx, ry, rz;
            rx = ox + reflect * (ox - s[3 * hi]);
            ry = oy + reflect * (oy - s[3 * hi + 1]);
            rz = oz + reflect * (oz - s[3 * hi + 2]);
            float fr = fn.f(rx, ry, rz);
            if (fr < f[nh]) {
                minimize(fr, rx, ry, rz, ox, oy, oz, expand, lo, hi, fn);
                continue;
            }
            minimize(ox, oy, oz, contract, lo, hi, shrink, fn);
        }
        outMin[0] = s[3 * lo];
        outMin[1] = s[3 * lo + 1];
        outMin[2] = s[3 * lo + 2];
        return f[lo];
    }

    private void minimize(float fr, float rx, float ry, float rz, float ox, float oy, float oz, float expand, int lo,
            int hi, FitnessFunction fn) {
        if (fr < f[lo]) {
            float ex, ey, ez;
            ex = ox + expand * (ox - s[3 * hi]);
            ey = oy + expand * (oy - s[3 * hi + 1]);
            ez = oz + expand * (oz - s[3 * hi + 2]);
            float fe = fn.f(ex, ey, ez);
            if (fe < fr) {
                s[3 * hi] = ex;
                s[3 * hi + 1] = ey;
                s[3 * hi + 2] = ez;
                f[hi] = fe;
                return;
            }
        }
        s[3 * hi] = rx;
        s[3 * hi + 1] = ry;
        s[3 * hi + 2] = rz;
        f[hi] = fr;
    }

    private void minimize(float ox, float oy, float oz, float contract, int lo, int hi, float shrink,
            FitnessFunction fn) {
        float cx, cy, cz;
        cx = ox - contract * (ox - s[3 * hi]);
        cy = oy - contract * (oy - s[3 * hi + 1]);
        cz = oz - contract * (oz - s[3 * hi + 2]);
        float fc = fn.f(cx, cy, cz);
        if (fc < f[hi]) {
            s[3 * hi] = cx;
            s[3 * hi + 1] = cy;
            s[3 * hi + 2] = cz;
            f[hi] = fc;
            return;
        }
        for (int k = 0; k < 4; k++) {
            if (k == lo)
                continue;
            for (int i = 0; i < 3; i++)
                s[3 * k + i] = s[3 * lo + i] + shrink * (s[3 * k + i] - s[3 * lo + i]);
            f[k] = fn.f(s[3 * k], s[3 * k + 1], s[3 * k + 2]);
        }
    }

}
