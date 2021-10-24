/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable
#extension GL_EXT_scalar_block_layout : enable

#define MAX_STEPS 128

struct aabb {
  vec3 min;
  vec3 max;
};
layout(scalar, binding = 3) buffer AABBs { aabb[] aabbs; };

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
float sdRoundedCylinder( vec3 p, float ra, float rb, float h ) {
  // http://iquilezles.org/www/articles/distfunctions/distfunctions.htm
  vec2 d = vec2( length(p.xz)-2.0*ra+rb, abs(p.y) - h );
  return min(max(d.x,d.y),0.0) + length(max(d,0.0)) - rb;
}
float f(vec3 p, vec3 minp, vec3 maxp) {
  vec3 p2 = repLim(p - vec3(0.5), minp, trunc(maxp) - vec3(1.0));
  vec3 p3 = repLim((p2 - vec3(-0.25, 0.5, -0.25))/0.5, vec3(0.0, 0.0, 0.0), vec3(1.0, 0.0, 1.0));
  return min(
           sdRoundBox(p2, vec3(0.497), 5E-3),
           sdRoundedCylinder(p3, 0.1, 0.01, 0.187)*0.5
         );
}
vec3 calcNormal(vec3 p, vec3 minp, vec3 maxp) {
  // http://iquilezles.org/www/articles/normalsSDF/normalsSDF.htm
  vec2 h = vec2(1E-5, 0.0);
  return normalize(vec3(f(p+h.xyy, minp, maxp) - f(p-h.xyy, minp, maxp),
                        f(p+h.yxy, minp, maxp) - f(p-h.yxy, minp, maxp),
                        f(p+h.yyx, minp, maxp) - f(p-h.yyx, minp, maxp)));
}

void main(void) {
  aabb a = aabbs[gl_PrimitiveID];
  float t = 0.0;
  vec3 o = gl_WorldRayOriginEXT;
  vec3 d = gl_WorldRayDirectionEXT;
  float tn;
  for (int i = 0; i < MAX_STEPS; i++) {
    tn = f(o, a.min, a.max);
    if (abs(tn) < 1E-3)
      break;
    t += tn;
    o += d * tn;
  }
  if (any(lessThan(o, a.min)) || any(greaterThan(o, a.max)))
    return;
  normal = calcNormal(o, a.min, a.max);
  reportIntersectionEXT(t, 0u);
}
