package org.lwjgl.demo.opengl.util;

import static java.lang.Float.*;
import static java.lang.Math.*;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.*;

import java.util.*;

import org.joml.*;
import org.lwjgl.demo.opengl.util.KDTreei.Voxel;

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
    public static final byte COLLISION_SIDE_NX = 1 << 0;
    public static final byte COLLISION_SIDE_PX = 1 << 1;
    public static final byte COLLISION_SIDE_NY = 1 << 2;
    public static final byte COLLISION_SIDE_PY = 1 << 3;
    public static final byte COLLISION_SIDE_NZ = 1 << 4;
    public static final byte COLLISION_SIDE_PZ = 1 << 5;

    /* Scratch/temporary memory */
    private final List<Voxel> candidates = new ArrayList<>(32);
    private final List<Collision> collisions = new ArrayList<>(32);
    private final Vector3f n = new Vector3f();
    private final Vector3f _tmp0 = new Vector3f();
    private final Vector3f _tmp1 = new Vector3f();

    private static class Collision implements Comparable<Collision> {
        private final float nx, ny, nz;
        private final float t;

        private Collision(Vector3f n, float t) {
            this.nx = n.x;
            this.ny = n.y;
            this.nz = n.z;
            this.t = t;
        }

        @Override
        public int compareTo(Collision o) {
            return compare(t, o.t);
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

    private void handleCollisionAabbAabbsCntd(AABBf box, Vector3f v, Vector3f delta) {
        collisions.clear();
        for (Voxel c : candidates) {
            float t = intersectAabbAabb(box, v, c, n);
            if (t < 1.0f)
                collisions.add(new Collision(n, t));
        }
        sort(collisions);
        delta.zero();
        float elapsedTime = 0.0f;
        float vx = v.x, vy = v.y, vz = v.z;
        for (Collision collision : collisions) {
            float t = collision.t - elapsedTime;
            delta.add(vx * t, vy * t, vz * t);
            elapsedTime += t;
            if (collision.nx != 0)
                vx = 0.0f;
            else if (collision.ny != 0)
                vy = 0.0f;
            else if (collision.nz != 0)
                vz = 0.0f;
        }
        float trem = 1.0f - elapsedTime;
        delta.add(vx * trem, vy * trem, vz * trem);
    }

    private static float intersectAabbAabb(AABBf box, Vector3f v, Voxel vx, Vector3f n) {
        float xInvEntry, yInvEntry, zInvEntry;
        float xInvExit, yInvExit, zInvExit;
        boolean maskX, maskY, maskZ;
        float areaX = (min(vx.max(Y), box.maxY) - max(vx.min(Y), box.minY))
                * (min(vx.max(Z), box.maxZ) - max(vx.min(Z), box.minZ));
        float areaY = (min(vx.max(X), box.maxX) - max(vx.min(X), box.minX))
                * (min(vx.max(Z), box.maxZ) - max(vx.min(Z), box.minZ));
        float areaZ = (min(vx.max(X), box.maxX) - max(vx.min(X), box.minX))
                * (min(vx.max(Y), box.maxY) - max(vx.min(Y), box.minY));
        if (v.x > 0.0f) {
            xInvEntry = vx.min(X) - box.maxX;
            xInvExit = vx.max(X) - box.minX;
            maskX = (vx.sides & COLLISION_SIDE_NX) == 0;
        } else {
            xInvEntry = vx.max(X) - box.minX;
            xInvExit = vx.min(X) - box.maxX;
            maskX = (vx.sides & COLLISION_SIDE_PX) == 0;
        }
        if (v.y > 0.0f) {
            yInvEntry = vx.min(Y) - box.maxY;
            yInvExit = vx.max(Y) - box.minY;
            maskY = (vx.sides & COLLISION_SIDE_NY) == 0;
        } else {
            yInvEntry = vx.max(Y) - box.minY;
            yInvExit = vx.min(Y) - box.maxY;
            maskY = (vx.sides & COLLISION_SIDE_PY) == 0;
        }
        if (v.z > 0.0f) {
            zInvEntry = vx.min(Z) - box.maxZ;
            zInvExit = vx.max(Z) - box.minZ;
            maskZ = (vx.sides & COLLISION_SIDE_NZ) == 0;
        } else {
            zInvEntry = vx.max(Z) - box.minZ;
            zInvExit = vx.min(Z) - box.maxZ;
            maskZ = (vx.sides & COLLISION_SIDE_PZ) == 0;
        }
        return intersectAabbAabbCntd(v, n, xInvEntry, yInvEntry, zInvEntry, xInvExit, yInvExit, zInvExit, maskX, maskY,
                maskZ, areaX, areaY, areaZ);
    }

    private static float intersectAabbAabbCntd(Vector3f v, Vector3f n, float xInvEntry, float yInvEntry,
            float zInvEntry, float xInvExit, float yInvExit, float zInvExit, boolean maskX, boolean maskY,
            boolean maskZ, float areaX, float areaY, float areaZ) {
        float xEntry = NEGATIVE_INFINITY, yEntry = NEGATIVE_INFINITY, zEntry = NEGATIVE_INFINITY;
        float xExit = POSITIVE_INFINITY, yExit = POSITIVE_INFINITY, zExit = POSITIVE_INFINITY;
        if (v.x != 0.0f && maskX) {
            xEntry = xInvEntry / v.x;
            xExit = xInvExit / v.x;
        }
        if (v.y != 0.0f && maskY) {
            yEntry = yInvEntry / v.y;
            yExit = yInvExit / v.y;
        }
        if (v.z != 0.0f && maskZ) {
            zEntry = zInvEntry / v.z;
            zExit = zInvExit / v.z;
        }
        float entryTime = max(max(xEntry, yEntry), zEntry);
        float exitTime = min(min(xExit, yExit), zExit);
        if (entryTime > exitTime || xEntry < -1f && yEntry < -1f && zEntry < -1f
                || xEntry > 1.0f && yEntry > 1.0f && zEntry > 1.0f) {
            return POSITIVE_INFINITY;
        } else {
            if (xEntry > yEntry && xEntry > zEntry && areaX > areaY && areaX > areaZ) {
                n.set(xInvEntry > 0 ? -1 : 1, 0, 0);
            } else if (yEntry > zEntry && areaY > areaZ) {
                n.set(0, yInvEntry > 0 ? -1 : 1, 0);
            } else if (areaZ > 1E-6f) {
                n.set(0, 0, zInvEntry > 0 ? -1 : 1);
            } else {
                return POSITIVE_INFINITY;
            }
            return entryTime;
        }
    }

    private static float intersectSegmentSphere(float ax, float ay, float az, float dx, float dy, float dz, float cx,
            float cy, float cz, float r) {
        float tm = dx * dx + dy * dy + dz * dz;
        float mx = ax - cx, my = ay - cy, mz = az - cz;
        float c = mx * mx + my * my + mz * mz - r * r;
        if (tm > 0.0f) {
            tm = (float) sqrt(tm);
            float tmi = 1.0f / tm;
            dx = dx * tmi;
            dy = dy * tmi;
            dz = dz * tmi;
        } else
            return c > 0.0f ? NaN : 0.0f;
        return intersectSegmentSphereCntd(dx, dy, dz, tm, mx, my, mz, c);
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

    private static float intersectSegmentCapsule(float sx0, float sy0, float sz0, float sx1, float sy1, float sz1,
            float px, float py, float pz, float qx, float qy, float qz, float r) {
        float dx = qx - px, dy = qy - py, dz = qz - pz;
        float mx = sx0 - px, my = sy0 - py, mz = sz0 - pz;
        float nx = sx1 - sx0, ny = sy1 - sy0, nz = sz1 - sz0;
        float md = mx * dx + my * dy + mz * dz;
        float nd = nx * dx + ny * dy + nz * dz;
        if (md < 0.0f && md + nd < 0.0f)
            return intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, px, py, pz, r);
        float dd = dx * dx + dy * dy + dz * dz;
        if (md > dd && md + nd > dd)
            return intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, qx, qy, qz, r);
        float a = dd * (nx * nx + ny * ny + nz * nz) - nd * nd;
        float c = dd * (mx * mx + my * my + mz * mz - r * r) - md * md;
        if (abs(a) < 1E-6f)
            return c > 0.0f ? NaN
                    : md < 0.0f ? intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, px, py, pz, r)
                            : md > dd ? intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, qx, qy, qz, r) : 0.0f;
        float b = dd * (mx * nx + my * ny + mz * nz) - nd * md;
        float d = b * b - a * c;
        if (d < 0.0f)
            return NaN;
        float t = (-b - (float) sqrt(d)) / a;
        return md + t * nd < 0.0f ? intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, px, py, pz, r)
                : md + t * nd > dd ? intersectSegmentSphere(sx0, sy0, sz0, nx, ny, nz, qx, qy, qz, r)
                        : t >= 0.0f && t <= 1.0f ? t : NaN;
    }

    private static float intersectRayAab(float originX, float originY, float originZ, float dirX, float dirY,
            float dirZ, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float invDirX = 1.0f / dirX, invDirY = 1.0f / dirY, invDirZ = 1.0f / dirZ;
        float tNear, tFar, tymin, tymax;
        if (invDirX >= 0.0f) {
            tNear = (minX - originX) * invDirX;
            tFar = (maxX - originX) * invDirX;
        } else {
            tNear = (maxX - originX) * invDirX;
            tFar = (minX - originX) * invDirX;
        }
        if (invDirY >= 0.0f) {
            tymin = (minY - originY) * invDirY;
            tymax = (maxY - originY) * invDirY;
        } else {
            tymin = (maxY - originY) * invDirY;
            tymax = (minY - originY) * invDirY;
        }
        if (tNear > tymax || tymin > tFar)
            return NaN;
        return intersectRayAabCntd(originZ, minZ, maxZ, invDirZ, tNear, tFar, tymin, tymax);
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
        if (tNear < tFar && tFar >= 0.0f)
            return tNear;
        return NaN;
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
        float t = intersectRayAab(s.x, s.y, s.z, d.x, d.y, d.z, minX, minY, minZ, maxX, maxY, maxZ);
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
        float t = intersectSegmentCapsule(s.x, s.y, s.z, s.x + d.x, s.y + d.y, s.z + d.z, c4.x, c4.y, c4.z, c0.x, c0.y,
                c0.z, r);
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
        float t;
        float tmin = POSITIVE_INFINITY;
        Vector3f c1 = corner(min, max, v ^ 1 << X, _tmp1);
        t = intersectSegmentCapsule(s.x, s.y, s.z, s.x + d.x, s.y + d.y, s.z + d.z, c0.x, c0.y, c0.z, c1.x, c1.y, c1.z,
                r);
        if (!isNaN(t))
            tmin = min(t, tmin);
        Vector3f c2 = corner(min, max, v ^ 1 << Y, _tmp1);
        t = intersectSegmentCapsule(s.x, s.y, s.z, s.x + d.x, s.y + d.y, s.z + d.z, c0.x, c0.y, c0.z, c2.x, c2.y, c2.z,
                r);
        if (!isNaN(t))
            tmin = min(t, tmin);
        Vector3f c3 = corner(min, max, v ^ 1 << Z, _tmp1);
        t = intersectSegmentCapsule(s.x, s.y, s.z, s.x + d.x, s.y + d.y, s.z + d.z, c0.x, c0.y, c0.z, c3.x, c3.y, c3.z,
                r);
        if (!isNaN(t))
            tmin = min(t, tmin);
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
