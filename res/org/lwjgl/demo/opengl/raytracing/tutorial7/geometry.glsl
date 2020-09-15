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

#define SIDE_X_POS 0
#define SIDE_X_NEG 1
#define SIDE_Y_POS 2
#define SIDE_Y_NEG 3
#define SIDE_Z_POS 4
#define SIDE_Z_NEG 5

/**
 * Assuming the ray `origin + t/invdir` intersects the box
 * `(boxMin, boxMax)`, return the `t` for which `origin + t/invdir` is
 * the point on the box where the ray exits the box. Additionally, write
 * the box side [0..5] to `exitSide`.
 *
 * @param origin the ray's origin
 * @param invdir the inverse of the ray's direction
 * @param boxMin the minimum box coordinates
 * @param boxMax the maximum box coordinates
 * @param[out] exitSide the index within [0..5] of the side the ray
 *                      exits the box (see the #defines above)
 * @returns the `t` in the ray equation `origin + t/invdir` at which the
 *          ray exits the box
 */
float exitSide(vec3 origin, vec3 invdir, vec3 boxMin, vec3 boxMax, out int exitSide) {
  bvec3 lt = lessThan(invdir, vec3(0.0));
  vec3 tmax = (mix(boxMax, boxMin, lt) - origin) * invdir;
  ivec3 signs = ivec3(lt);
  vec2 vals;
  vals = mix(vec2(tmax.y, SIDE_Y_POS + signs.y), vec2(tmax.x, signs.x), tmax.y > tmax.x);
  vals = mix(vec2(tmax.z, SIDE_Z_POS + signs.z), vec2(vals.x, vals.y), tmax.z > vals.x);
  exitSide = int(vals.y);
  return vals.x;
}
