/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

/**
 * Description of all necessary information of a ray-triangle
 * intersection. This includes the `t` in `origin + t * dir` where the
 * ray hits the triangle, as well as the barycentric coordiantes of the
 * point on the triangle in order to calculate interpolated normals.
 */
struct trianglehitinfo {
  float t, u, v; // <- u, v = barycentric coordinates
};

/**
 * Test whether the given ray `origin + t * dir` intersects the triangle
 * given via its three vertices `v0`, `v1` and `v2` and write the 
 * `t` at which `origin + t * dir` is the intersection point, as well as
 * the barycentric coordinates into the given `thinfo`.
 *
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param v0 the triangle's first vertex
 * @param v1 the triangle's second vertex
 * @param v2 the triangle's third vertex
 * @param[out] thinfo will hold the `t` at the point of intersection as
 *                    well as the barycentric coordinates
 */
bool intersectTriangle(vec3 origin, vec3 dir, vec3 v0, vec3 v1, vec3 v2, out trianglehitinfo thinfo) {
  vec3 e1 = v1 - v0, e2 = v2 - v0;
  vec3 pvec = cross(dir, e2);
  float det = dot(e1, pvec);
  if (det < 0.0)
    return false;
  vec3 tvec = origin - v0;
  float u = dot(tvec, pvec);
  if (u < 0.0 || u > det)
    return false;
  vec3 qvec = cross(tvec, e1);
  float v = dot(dir, qvec);
  if (v < 0.0 || u + v > det)
    return false;
  float invDet = 1.0 / det;
  float t = dot(e2, qvec) * invDet;
  thinfo.u = u * invDet;
  thinfo.v = v * invDet;
  thinfo.t = t;
  return t >= 0.0;
}

/**
 * Test whether the given ray `origin + t/invdir` intersects the box
 * `(boxMin, boxMax)` and return true/false as well as the near `t` of
 * the intersection.
 * 
 * @param origin the ray's origin
 * @param invdir the inverse of the ray's direction
 * @param boxMin the minimum box coordinates
 * @param boxMax the maximum box coordinates
 * @param[out] t the `t` in the ray equation `origin + t/invdir` at
 *               which the ray enters the box
 * @returns true if the ray intersects the box; false otherwise
 */
bool intersectBox(vec3 origin, vec3 invdir, vec3 boxMin, vec3 boxMax, out float t) {
  bvec3 lt = lessThan(invdir, vec3(0.0));
  vec3 m1 = (boxMin - origin) * invdir;
  vec3 m2 = (boxMax - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt);
  vec3 tmax = mix(m2, m1, lt);
  float mint = max(max(tmin.x, tmin.y), tmin.z);
  float maxt = min(min(tmax.x, tmax.y), tmax.z);
  t = mint;
  return maxt >= 0.0 && mint <= maxt * 1.00000024;
}
