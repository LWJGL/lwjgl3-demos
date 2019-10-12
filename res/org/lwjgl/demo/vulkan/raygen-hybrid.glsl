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
  mat4x3 viewInverse;
} cam;
layout(binding = 5, set = 0, rg8) uniform image2D normalImage;
layout(binding = 6, set = 0) uniform sampler2D depthImage;
layout(location = 0) rayPayloadNV Payload {
  vec3 normal;
  float t;
} payload;
layout(binding = 7, set = 0) uniform sampler2D blueNoiseImage;

#define BOUNCES 2
#define SAMPLES 1
#define BOX_COLOR vec3(0.83, 0.64, 0.49)
#define BLUENOISE

#include "random.inc.glsl"

vec2 pc;
ivec2 bns;
ivec2 off;

vec2 randBlueNoise(int s) {
  vec2 o = vec2(random2(vec2(s, off.x)), random2(vec2(s, off.y)));
  return textureLod(blueNoiseImage, fract(o + pc / bns), 0.0).rg;
}
vec2 randWhiteNoise(int s) {
  return vec2(
    random3(vec3(pc + vec2(s, 0.34), 0.35 * s)),
    random3(vec3(pc + vec2(s, -1.53), 1.15 * s + 0.6)));
}
vec2 randNoise(int s) {
#ifdef BLUENOISE
  return randBlueNoise(s);
#else
  return randWhiteNoise(s);
#endif
}

vec3 sky(vec3 d) {
  return vec3(pow(min(1.0, max(0.0, d.y + 1.0)), 4.0));
}

void main() {
  bns = textureSize(blueNoiseImage, 0);
  ivec2 px = ivec2(gl_LaunchIDNV.xy);
  pc = vec2(px) + vec2(0.5);
  off = px / bns;
  vec2 tx = pc / vec2(gl_LaunchSizeNV.xy);
  vec2 nc = tx * 2.0 - vec2(1.0);
  float invz = 1.0 - textureLod(depthImage, tx, 0.0).r;
  vec4 nci = cam.projInverse * vec4(nc, invz, 1.0);
  vec3 direction = (cam.viewInverse * vec4(nci.xyz / nci.z, 0.0)).xyz;
  if (invz == 1.0) {
    imageStore(image, px, vec4(sky(normalize(-direction)), 1.0));
    return;
  }
  vec3 origin = cam.viewInverse[3].xyz + direction * nci.z / nci.w;
  vec2 nv = imageLoad(normalImage, px).xy;
  vec3 nr = vec3(nv, sqrt(max(0.0, 1.0 - dot(nv, nv))));
  vec3 normal = (cam.viewInverse * vec4(nr, 0.0)).xyz;
  vec3 col = vec3(0.0);
  for (int s = 0; s < SAMPLES; s++) {
    vec3 att = vec3(1.0), o = origin, n = normal;
    for (int i = 0; i < BOUNCES; i++) {
      vec2 rnd = randNoise(s * BOUNCES + i);
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
        o += d * payload.t + n * 1E-3;
      }
    }
  }
  imageStore(image, px, vec4(col / SAMPLES, 1.0));
}
