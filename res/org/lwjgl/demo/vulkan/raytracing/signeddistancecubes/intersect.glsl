/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable
#extension GL_EXT_scalar_block_layout : enable

struct aabb {
  vec3 min;
  vec3 max;
};
layout(scalar, binding = 3) buffer AABBs { aabb[] aabbs; };

#define RADIUS 0.3

hitAttributeEXT vec3 normal;

float sdRoundBox(vec3 p, vec3 b, float r) {
  // http://iquilezles.org/www/articles/distfunctions/distfunctions.htm
  vec3 q = abs(p) - b + vec3(r);
  return length(max(q, 0.0)) + min(max(q.x,max(q.y,q.z)), 0.0) - r;
}
vec3 repLim(vec3 p, vec3 lima, vec3 limb) {
  // http://iquilezles.org/www/articles/distfunctions/distfunctions.htm
  return p - clamp(round(p), lima, limb);
}
float f(vec3 p, vec3 minp, vec3 maxp) {
  vec3 p2 = repLim(p - vec3(0.5), minp, maxp - vec3(1.0));
  return sdRoundBox(p2, vec3(0.5), RADIUS);
}
float f_(vec3 p, vec3 minp, vec3 maxp) {
  vec3 p2 = repLim(p - vec3(0.5), minp, maxp - vec3(1.0));
  vec3 c = (maxp + minp) * 0.5;
  vec3 hs = (maxp - minp) * 0.5;
  return sdRoundBox(p-c, hs, RADIUS);
}
vec3 calcNormal(vec3 p, vec3 minp, vec3 maxp) {
  // http://iquilezles.org/www/articles/normalsSDF/normalsSDF.htm
  vec2 h = vec2(1E-4, 0.0);
  return normalize(vec3(f(p+h.xyy, minp, maxp) - f(p-h.xyy, minp, maxp),
                        f(p+h.yxy, minp, maxp) - f(p-h.yxy, minp, maxp),
                        f(p+h.yyx, minp, maxp) - f(p-h.yyx, minp, maxp)));
}

void main(void) {
  aabb a = aabbs[gl_PrimitiveID];
  float t = 0.0;
  vec3 o = gl_WorldRayOriginEXT;
  vec3 d = gl_WorldRayDirectionEXT;
  for (int i = 0; i < 32; i++) {
    float tn = f(o, a.min, a.max);
    t += tn;
    o += d * tn;
  }
  normal = calcNormal(o, a.min, a.max);
  reportIntersectionEXT(t, 0u);
}
