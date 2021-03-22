/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import org.joml.Vector3f;
import org.lwjgl.demo.util.KDTreei.Voxel;

import java.util.ArrayList;

import static java.lang.Float.*;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.*;
import static java.util.Collections.sort;

/**
 * Collision handling for basic primitives such as AABBs and spheres.
 * 
 * Instances of this class are <i>not</i> thread-safe and method calls to the
 * same instance must be externally synchronized!
 * 
 * Most methods in this class are directly derived from code in the book
 * "Real-Time Collision Detection Book" by Christer Ericson.
 * 
 * @author Kai Burjack
 */
public class Collider {

    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;

    /* Scratch/temporary memory */
    private final ArrayList<Voxel> candidates = new ArrayList<>(32);
    private final ArrayList<Contact> contacts = new ArrayList<>(32);
    private final Vector3f _tmp0 = new Vector3f();
    private final Vector3f _tmp1 = new Vector3f();

    private static class Contact implements Comparable<Contact> {
        public Vector3f n = new Vector3f();
        public float t;
        public Voxel vx;

        @Override
        public int compareTo(Contact o) {
            return compare(t, o.t);
        }

        @Override
        public String toString() {
            return "(" + n + ") @ " + t;
        }
    }

    public void handleCollisionAabbAabbs(AABBf box, Vector3f v, KDTreei<Voxel> tree, Vector3f delta) {
        float minX = box.minX + min(0.0f, v.x), minY = box.minY + min(0.0f, v.y), minZ = box.minZ + min(0.0f, v.z);
        float maxX = box.maxX + max(0.0f, v.x), maxY = box.maxY + max(0.0f, v.x), maxZ = box.maxZ + max(0.0f, v.z);
        candidates.clear();
        tree.intersects(minX, minY, minZ, maxX, maxY, maxZ, candidates);
        if (candidates.isEmpty()) {
            delta.set(v);
            return;
        }
        handleCollisionAabbAabbsCntd(box, v, delta);
    }

    private void removeImplausibleContacts() {
        for (int i = 0; i < contacts.size(); i++) {
            Contact c0 = contacts.get(i);
            int k = c0.n.maxComponent();
            for (int j = 0; j < contacts.size(); j++) {
                Contact c1 = contacts.get(j);
                if (c1.n.get(k) != 0.0f || ((c0.n.get(k) > 0.0f || c1.vx.min(k) != c0.vx.min(k))
                        && (c0.n.get(k) < 0.0f || c1.vx.max(k) != c0.vx.max(k))))
                    continue;
                contacts.remove(j--);
                if (j < i)
                    i--;
            }
        }
    }

    private void handleCollisionAabbAabbsCntd(AABBf box, Vector3f v, Vector3f delta) {
        contacts.clear();
        for (Voxel c : candidates)
            intersectAabbAabb(box, v, c);
        removeImplausibleContacts();
        sort(contacts);
        delta.zero();
        float elapsedTime = 0.0f;
        float vx = v.x, vy = v.y, vz = v.z;
        for (Contact contact : contacts) {
            float t = contact.t - elapsedTime;
            delta.add(vx * t, vy * t, vz * t);
            elapsedTime += t;
            if (contact.n.get(X) != 0)
                vx = 0.0f;
            else if (contact.n.get(Y) != 0)
                vy = 0.0f;
            else if (contact.n.get(Z) != 0)
                vz = 0.0f;
        }
        float trem = 1.0f - elapsedTime;
        delta.add(vx * trem, vy * trem, vz * trem);
    }

    private void intersectAabbAabb(AABBf box, Vector3f v, Voxel vx) {
        for (int k = 0; k < 3; k++) {
            float invEntry, invExit;
            boolean mask;
            if (v.get(k) > 0.0f) {
                invEntry = vx.min(k) - box.getMax(k);
                invExit = vx.max(k) - box.getMin(k);
                mask = (vx.sides & (1 << k * 2)) == 0;
            } else {
                invEntry = vx.max(k) - box.getMin(k);
                invExit = vx.min(k) - box.getMax(k);
                mask = (vx.sides & (1 << k * 2 + 1)) == 0;
            }
            intersectAabbAabb(vx, v, invEntry, invExit, mask, k);
        }
    }

    private void intersectAabbAabb(Voxel vx, Vector3f v, float invEntry, float invExit, boolean mask, int k) {
        if (v.get(k) != 0.0f && mask) {
            float entry = invEntry / v.get(k);
            float exit = invExit / v.get(k);
            if (entry >= -1.0f && entry < exit) {
                Contact c = new Contact();
                contacts.add(c);
                c.n.setComponent(k, invEntry > 0 ? -1 : 1);
                c.t = entry;
                c.vx = vx;
            }
        }
    }

    private static float intersectSegmentSphere(Vector3f a, float dx, float dy, float dz, Vector3f p, float r) {
        float tm = dx * dx + dy * dy + dz * dz;
        float mx = a.x - p.x, my = a.y - p.y, mz = a.z - p.z;
        float c = mx * mx + my * my + mz * mz - r * r;
        if (tm <= 0.0f)
            return c > 0.0f ? NaN : 0.0f;
        tm = (float) sqrt(tm);
        float tmi = 1.0f / tm;
        return intersectSegmentSphereCntd(dx * tmi, dy * tmi, dz * tmi, tm, mx, my, mz, c);
    }

    private static float intersectSegmentSphereCntd(float dx, float dy, float dz, float tm, float mx, float my,
            float mz, float c) {
        float b = mx * dx + my * dy + mz * dz;
        if (c > 0.0f && b > 0.0f)
            return NaN;
        float d = b * b - c;
        if (d < 0.0f)
            return NaN;
        float t = -b - (float) sqrt(d);
        return t > tm ? NaN : max(0.0f, t / tm);
    }

    private static float intersectSegmentCapsule(Vector3f s0, Vector3f n, Vector3f p, Vector3f q, float r) {
        float dx = q.x - p.x, dy = q.y - p.y, dz = q.z - p.z;
        float mx = s0.x - p.x, my = s0.y - p.y, mz = s0.z - p.z;
        float nx = n.x, ny = n.y, nz = n.z;
        float md = mx * dx + my * dy + mz * dz;
        float nd = nx * dx + ny * dy + nz * dz;
        if (md < 0.0f && md + nd < 0.0f)
            return intersectSegmentSphere(s0, nx, ny, nz, p, r);
        float dd = dx * dx + dy * dy + dz * dz;
        if (md > dd && md + nd > dd)
            return intersectSegmentSphere(s0, nx, ny, nz, q, r);
        float a = dd * (nx * nx + ny * ny + nz * nz) - nd * nd;
        float c = dd * (mx * mx + my * my + mz * mz - r * r) - md * md;
        if (abs(a) < 1E-6f)
            return c > 0.0f ? NaN
                    : md < 0.0f ? intersectSegmentSphere(s0, nx, ny, nz, p, r)
                            : md > dd ? intersectSegmentSphere(s0, nx, ny, nz, q, r) : 0.0f;
        float b = dd * (mx * nx + my * ny + mz * nz) - nd * md;
        float d = b * b - a * c;
        if (d < 0.0f)
            return NaN;
        float t = (-b - (float) sqrt(d)) / a;
        return md + t * nd < 0.0f ? intersectSegmentSphere(s0, nx, ny, nz, p, r)
                : md + t * nd > dd ? intersectSegmentSphere(s0, nx, ny, nz, q, r) : t >= 0.0f && t <= 1.0f ? t : NaN;
    }

    private static float intersectRayAab(Vector3f o, Vector3f d, float minX, float minY, float minZ, float maxX,
            float maxY, float maxZ) {
        float invDirX = 1.0f / d.x, invDirY = 1.0f / d.y, invDirZ = 1.0f / d.z;
        float tNear, tFar, tymin, tymax;
        if (invDirX >= 0.0f) {
            tNear = (minX - o.x) * invDirX;
            tFar = (maxX - o.x) * invDirX;
        } else {
            tNear = (maxX - o.x) * invDirX;
            tFar = (minX - o.x) * invDirX;
        }
        if (invDirY >= 0.0f) {
            tymin = (minY - o.y) * invDirY;
            tymax = (maxY - o.y) * invDirY;
        } else {
            tymin = (maxY - o.y) * invDirY;
            tymax = (minY - o.y) * invDirY;
        }
        if (tNear > tymax || tymin > tFar)
            return NaN;
        return intersectRayAabCntd(o.z, minZ, maxZ, invDirZ, tNear, tFar, tymin, tymax);
    }

    private static float intersectRayAabCntd(float originZ, float minZ, float maxZ, float invDirZ, float tNear,
            float tFar, float tymin, float tymax) {
        float tzmin, tzmax;
        if (invDirZ >= 0.0f) {
            tzmin = (minZ - originZ) * invDirZ;
            tzmax = (maxZ - originZ) * invDirZ;
        } else {
            tzmin = (maxZ - originZ) * invDirZ;
            tzmax = (minZ - originZ) * invDirZ;
        }
        if (tNear > tzmax || tzmin > tFar)
            return NaN;
        return intersectRayAabCntd(tNear, tFar, tymin, tymax, tzmin, tzmax);
    }

    private static float intersectRayAabCntd(float tNear, float tFar, float tymin, float tymax, float tzmin,
            float tzmax) {
        tNear = tymin > tNear || isNaN(tNear) ? tymin : tNear;
        tFar = tymax < tFar || isNaN(tFar) ? tymax : tFar;
        tNear = tzmin > tNear ? tzmin : tNear;
        tFar = tzmax < tFar ? tzmax : tFar;
        return tNear < tFar && tFar >= 0.0f ? tNear : NaN;
    }

    private static enum PointLocation {
        INSIDE, OUTSIDE, ON_SURFACE
    }

    private static PointLocation closestPoint(Vector3f p, Vector3f min, Vector3f max, Vector3f closest,
            Vector3f normal) {
        PointLocation pl = PointLocation.INSIDE;
        for (int i = 0; i < 3; i++) {
            float v = p.get(i);
            if (v < min.get(i)) {
                normal.setComponent(i, v - min.get(i));
                v = min.get(i);
                pl = PointLocation.OUTSIDE;
            } else if (v > max.get(i)) {
                normal.setComponent(i, v - max.get(i));
                v = max.get(i);
                pl = PointLocation.OUTSIDE;
            }
            closest.setComponent(i, v);
        }
        if (pl == PointLocation.INSIDE) {
            pl = closestPointCntd(p, min, max, closest, normal, pl);
        } else {
            normal.normalize();
        }
        return pl;
    }

    private static PointLocation closestPointCntd(Vector3f p, Vector3f min, Vector3f max, Vector3f closest,
            Vector3f normal, PointLocation pl) {
        int ci = -1;
        float md = POSITIVE_INFINITY;
        boolean on = false, maxv = false;
        for (int i = 0; i < 3; i++) {
            float v = p.get(i), vm = v - min.get(i), mv = max.get(i) - v;
            if (vm < mv) {
                if (vm < 1E-6f) {
                    md = 0;
                    ci = i;
                    normal.setComponent(i, -1);
                    if (!on) {
                        on = true;
                        for (int j = 0; j < i; j++)
                            normal.setComponent(j, 0);
                    }
                } else if (ci == -1 || vm < md) {
                    md = vm;
                    ci = i;
                    normal.setComponent(i, 1);
                    maxv = false;
                }
            } else {
                if (mv < 1E-6f) {
                    md = 0;
                    ci = i;
                    normal.setComponent(i, 1);
                    if (!on) {
                        on = true;
                        for (int j = 0; j < i; j++)
                            normal.setComponent(j, 0);
                    }
                } else if (ci == -1 || mv < md) {
                    md = mv;
                    ci = i;
                    normal.setComponent(i, -1);
                    maxv = true;
                }
            }
            if (on) {
                pl = PointLocation.ON_SURFACE;
                normal.normalize();
                closest.set(p);
            } else if (ci == i) {
                for (int j = 0; j < 3; j++) {
                    if (j != ci)
                        normal.setComponent(j, 0);
                }
                closest.set(p);
                if (maxv) {
                    closest.setComponent(ci, max.get(ci));
                } else
                    closest.setComponent(ci, min.get(ci));
            }
        }
        return pl;
    }

    private static class CollisionResult {
        Vector3f normal = new Vector3f();
        Vector3f point = new Vector3f();
        float t;
    }

    private static Vector3f corner(Vector3f min, Vector3f max, int n, Vector3f c) {
        return c.set((n & 1 << X) != 0 ? max.x : min.x, (n & 1 << Y) != 0 ? max.y : min.y,
                (n & 1 << Z) != 0 ? max.z : min.z);
    }

    public boolean intersectSweptSphereAabb(Vector3f s, float r, Vector3f d, Vector3f min, Vector3f max,
            CollisionResult result) {
        PointLocation pl = closestPoint(s, min, max, result.point, result.normal);
        Vector3f cmf = new Vector3f(result.point).sub(s);
        if (pl != PointLocation.OUTSIDE || cmf.lengthSquared() <= r * r) {
            result.t = 0.0f;
            return true;
        }
        float minX = min.x - r, minY = min.y - r, minZ = min.z - r;
        float maxX = max.x + r, maxY = max.y + r, maxZ = max.z + r;
        float t = intersectRayAab(s, d, minX, minY, minZ, maxX, maxY, maxZ);
        if (isNaN(t) || t > 1.0f)
            return false;
        return intersectSweptSphereAabbCtnd(s, r, d, min, max, result, t);
    }

    private boolean intersectSweptSphereAabbCtnd(Vector3f s, float r, Vector3f d, Vector3f min, Vector3f max,
            CollisionResult result, float t) {
        result.t = t;
        int u = 0, v = 0;
        u |= result.point.x < min.x ? 1 << X : 0;
        v |= result.point.x > max.x ? 1 << X : 0;
        u |= result.point.y < min.y ? 1 << Y : 0;
        v |= result.point.y > max.y ? 1 << Y : 0;
        u |= result.point.z < min.z ? 1 << Z : 0;
        v |= result.point.z > max.z ? 1 << Z : 0;
        int m = u | v;
        Vector3f c0 = corner(min, max, v, _tmp0);
        if (m == 0x7)
            return intersectSweptSphereAabbCorner(s, r, d, min, max, result, v, c0);
        return intersectSweptSphereAabbCtnd(s, r, d, min, max, result, v, m, c0);
    }

    private boolean intersectSweptSphereAabbCtnd(Vector3f s, float r, Vector3f d, Vector3f min, Vector3f max,
            CollisionResult result, int v, int m, Vector3f c0) {
        if ((m & (m - 1)) == 0)
            return intersectSweptSphereAabbFace(r, min, max, result);
        Vector3f c4 = corner(min, max, v ^ 7, _tmp1);
        float t = intersectSegmentCapsule(s, d, c4, c0, r);
        if (t >= 0.0f && t <= 1.0f)
            return intersectSweptSphereAabbEdge(s, d, result, t, c0, c4);
        return false;
    }

    private static boolean intersectSweptSphereAabbEdge(Vector3f s, Vector3f d, CollisionResult result, float t,
            Vector3f c0, Vector3f c4) {
        result.t = t;
        lineSegmentClosestPoint(c4, c0, d.x * t + s.x, d.y * t + s.y, d.z * t + s.z, result.point, result.normal);
        return true;
    }

    private static boolean intersectSweptSphereAabbFace(float r, Vector3f min, Vector3f max, CollisionResult result) {
        for (int i = 0; i < 3; i++) {
            if (result.point.get(i) < min.get(i)) {
                result.point.setComponent(i, result.point.get(i) + r);
                break;
            } else if (result.point.get(i) > max.get(i)) {
                result.point.setComponent(i, result.point.get(i) - r);
                break;
            }
        }
        return true;
    }

    private boolean intersectSweptSphereAabbCorner(Vector3f s, float r, Vector3f d, Vector3f min, Vector3f max,
            CollisionResult result, int v, Vector3f c0) {
        float tmin = POSITIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            Vector3f c = corner(min, max, v ^ 1 << k, _tmp1);
            float t = intersectSegmentCapsule(s, d, c0, c, r);
            if (!isNaN(t))
                tmin = min(t, tmin);
        }
        result.point.set(c0);
        result.normal.set(d).mul(tmin).add(s).sub(c0).normalize();
        result.t = tmin;
        return true;
    }

    private static void lineSegmentClosestPoint(Vector3f start, Vector3f end, float px, float py, float pz,
            Vector3f closest, Vector3f normal) {
        float abx = end.x - start.x, aby = end.y - start.y, abz = end.z - start.z;
        float ab2 = abx * abx + aby * aby + abz * abz;
        if (ab2 < 1E-6f)
            closest.set(start);
        else {
            float d = (px - start.x) * abx + (py - start.y) * aby + (pz - start.z) * abz;
            float t = min(1.0f, max(0.0f, d / ab2));
            closest.set(abx, aby, abz).mul(t).add(start);
        }
        normal.set(px, py, pz).sub(closest).normalize();
    }

}
