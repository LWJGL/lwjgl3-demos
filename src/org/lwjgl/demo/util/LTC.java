package org.lwjgl.demo.util;

import org.joml.Vector3f;

import static java.lang.Math.*;

/**
 * Linearly Transformed Cosines evaluation.
 * <p>
 * This code was adapted from: <a href=
 * "https://github.com/selfshadow/ltc_code/blob/master/fit/LTC.h">https://github.com/selfshadow/ltc_code/blob/master/fit/LTC.h</a>
 * 
 * @author Kai Burjack
 */
public class LTC {
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final float ONE_OVER_PI = (float) (1.0 / Math.PI);

    public float magnitude = 1;
    public float fresnel = 1;
    public float m11 = 1, m22 = 1, m13;
    public float Xx = 1, Xz, Zx, Zz = 1;

    private float absInvDet;
    private float nm00, nm02, nm20, nm22;
    private float im00, im02, im11, im20, im22;
    {
        update();
    }

    public void update() {
        nm00 = Xx * m11;
        nm02 = Xz * m11;
        nm20 = Xx * m13 + Zx;
        nm22 = Xz * m13 + Zz;
        float s = 1.0f / (nm00 * m22 * nm22 - nm20 * m22 * nm02);
        im00 = (m22 * nm22) * s;
        im02 = -(m22 * nm02) * s;
        im11 = (nm00 * nm22 - nm20 * nm02) * s;
        im20 = -(nm20 * m22) * s;
        im22 = (nm00 * m22) * s;
        absInvDet = abs(s);
    }

    public float eval(float x, float y, float z) {
        float rx = im00 * x + im20 * z;
        float ry = im11 * y;
        float rz = im02 * x + im22 * z;
        float invlen = 1.0f / (float) sqrt(rx * rx + ry * ry + rz * rz);
        rx *= invlen;
        ry *= invlen;
        rz *= invlen;
        float kx = nm00 * rx + nm20 * rz;
        float ky = m22 * ry;
        float kz = nm02 * rx + nm22 * rz;
        float len = (float) sqrt(kx * kx + ky * ky + kz * kz);
        return ONE_OVER_PI * magnitude * max(0.0f, rz) * (len * len * len) * absInvDet;
    }

    public Vector3f sampleUniform(float cosTheta, float phi01, Vector3f dest) {
        return sample(cosTheta, phi01, dest);
    }

    public Vector3f sampleCosineWeighted(float cosTheta, float phi01, Vector3f dest) {
        return sample((float) sqrt(cosTheta), phi01, dest);
    }

    private Vector3f sample(float c, float phi, Vector3f dest) {
        float s = (float) sqrt(1.0 - c * c);
        float p = TWO_PI * phi;
        float sinp = (float) sin(p);
        float cosp = (float) cos(p);
        float x = s * cosp, y = s * sinp, z = c;
        float kx = nm00 * x + nm20 * z;
        float ky = m22 * y;
        float kz = nm02 * x + nm22 * z;
        float invlen = 1.0f / (float) sqrt(kx * kx + ky * ky + kz * kz);
        dest.x = kx * invlen;
        dest.y = ky * invlen;
        dest.z = kz * invlen;
        return dest;
    }

}
