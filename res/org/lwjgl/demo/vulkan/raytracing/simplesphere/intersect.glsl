/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable

hitAttributeEXT vec3 normal;

float sphere(vec3 o, vec3 d, vec3 s, float r, out vec3 n) {
 	vec3 so = o - s;
  float b = dot(so, d), c = dot(so, so) - r * r, t = b * b - c;
  float tr = t > 0.0 ? -b - sqrt(t) : t;
  n = normalize(o + tr*d - s);
  return tr;
}

void main(void) {
  reportIntersectionEXT(
    sphere(
      gl_WorldRayOriginEXT,
      gl_WorldRayDirectionEXT,
      vec3(0.0),
      1.0,
      normal),
    0u);
}
