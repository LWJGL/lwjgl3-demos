/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_NV_ray_tracing : require

layout(binding = 0, set = 0) uniform accelerationStructureNV topLevelAS;
layout(binding = 1, set = 0, rgba8) uniform image2D image;
layout(binding = 2, set = 0) uniform CameraProperties
{
  mat4 projInverse;
  mat4 viewInverse;
} cam;
layout(binding = 5, set = 0, rg8) uniform image2D normalImage;
layout(binding = 6, set = 0) uniform sampler2D depthImage;
layout(location = 0) rayPayloadNV Payload {
  vec3 normal;
  float t;
} payload;

#define BOUNCES 2
#define BOX_COLOR vec3(0.83, 0.64, 0.49)

#include "random.inc.glsl"

ivec2 px;

vec2 randWhiteNoise(int i) {
  return vec2(
    random3(vec3(vec2(px), i)),
    random3(vec3(vec2(px), 5.3 * i + 1.7)));
}

vec3 sky(vec3 d) {
  return vec3(pow(min(1.0, max(0.0, d.y + 1.0)), 4.0));
}

void main() {
  px = ivec2(gl_LaunchIDNV.xy);
  vec2 pc = vec2(px) + vec2(0.5);
  vec2 tx = pc / vec2(gl_LaunchSizeNV.xy);
  vec2 nc = tx * 2.0 - vec2(1.0);
  float invz = texture(depthImage, tx).r;
  vec4 nci = cam.projInverse * vec4(nc, invz, 1.0);
  vec3 direction = (cam.viewInverse * vec4(nci.xyz / nci.z, 0.0)).xyz;
  if (invz == 1.0) {
    imageStore(image, px, vec4(sky(normalize(-direction)), 1.0));
    return;
  }
  vec3 o = cam.viewInverse[3].xyz + direction * nci.z / nci.w;
  vec2 nv = imageLoad(normalImage, px).xy;
  vec3 nr = vec3(nv, sqrt(max(0.0, 1.0 - dot(nv, nv))));
  vec3 n = (cam.viewInverse * vec4(nr, 0.0)).xyz;
  vec3 col = vec3(0.0);
  vec3 att = vec3(1.0);
  for (int i = 0; i < BOUNCES; i++) {
    vec2 rnd = randWhiteNoise(i);
    vec4 s = randomCosineWeightedHemisphereDirection(n, rnd);
    if (s.w > 1E-4)
      att /= s.w;
    vec3 d = s.xyz;
    att *= max(0.0, dot(d, n)) * BOX_COLOR * ONE_OVER_PI;
    payload.t = -1.0;
    traceNV(topLevelAS, gl_RayFlagsOpaqueNV | gl_RayFlagsCullBackFacingTrianglesNV, 0x1, 0, 0, 0, o, 1E-3, d, 1E4, 0);
    if (payload.t < 0.0) {
      col += att * sky(d);
      break;
    } else {
      n = payload.normal;
      o += d * payload.t * 0.99;
    }
  }
  imageStore(image, px, vec4(col, 1.0));
}
