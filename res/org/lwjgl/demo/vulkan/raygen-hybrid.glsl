/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

#extension GL_NV_ray_tracing : require
#if GL_NV_gpu_shader5
#extension GL_NV_gpu_shader5 : enable
#elif GL_EXT_shader_8bit_storage
#extension GL_EXT_shader_8bit_storage : enable
#endif

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
layout(binding = 7) readonly buffer SobolBuffer {
  uint8_t[] sobols;
};
layout(binding = 8) readonly buffer ScrambleBuffer {
  uint8_t[] scrambles;
};
layout(binding = 9) readonly buffer RankingBuffer {
  uint8_t[] rankings;
};

#define BOUNCES 2
#define SAMPLES 2
#define BOX_COLOR vec3(196.0, 152.0, 116.0)/255.0
#define BLUENOISE

#include "random.inc.glsl"

ivec2 px;
vec2 pc;

float sampleBlueNoise(uint sampleIndex, uint sampleDimension) {
  uint xoff = hash2(sampleIndex, ((px.y>>7)<<7) | (px.x>>7)) & 255;
  uint yoff = hash2(sampleIndex, ((px.x>>7)<<7) | (px.y>>7)) & 255;
  uvec2 pxo = (px + ivec2(xoff, yoff)) & 127;
  sampleIndex = sampleIndex & 255;
  sampleDimension = sampleDimension & 255;
  uint pxv = (pxo.x + (pxo.y<<7))<<3;
  uint rankedSampleIndex = sampleIndex ^ uint(rankings[sampleDimension + pxv]);
  uint value = uint(sobols[sampleDimension + (rankedSampleIndex << 8)]);
  value = value ^ uint(scrambles[(sampleDimension & 7) + pxv]);
  return (0.5 + float(value)) / 256.0;
}

vec2 randBlueNoise(uint i, uint s) {
  return vec2(
    sampleBlueNoise(i, 2*s),
    sampleBlueNoise(i, 2*s+1)
  );
}
vec2 randWhiteNoise(uint i, uint s) {
  return vec2(
    random3(vec3(pc + vec2(s, 0.34), 0.35 * s)),
    random3(vec3(pc + vec2(s, -1.53), 1.15 * s + 0.6)));
}
vec2 randNoise(uint i, uint s) {
#ifdef BLUENOISE
  return randBlueNoise(i, s);
#else
  return randWhiteNoise(i, s);
#endif
}

vec3 sky(vec3 d) {
  return vec3(pow(min(1.0, max(0.0, d.y + 1.0)), 4.0));
}

void main() {
  px = ivec2(gl_LaunchIDNV.xy);
  pc = vec2(px) + vec2(0.5);
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
      vec2 rnd = randNoise(s, i);
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
