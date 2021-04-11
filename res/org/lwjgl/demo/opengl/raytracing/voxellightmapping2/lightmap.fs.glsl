/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D blendIndices;
uniform float time;

in vec3 normal_varying;
in vec3 position_varying;

out vec4 color;
out float blendIndex_out;

struct hitinfo {
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
  vec2 uv;
};
bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, out hitinfo shinfo);
vec4 randomCosineWeightedHemisphereDirection(vec3 n, vec2 rand);
vec2 randvec2(float time, float frame);

#define PI 3.14159265359
#define ONE_OVER_PI (1.0 / PI)

vec3 trace(vec3 origin, vec3 normal, vec3 dir) {
  hitinfo hinfo;
  if (intersectScene(0u, origin, dir, hinfo)) {
    return vec3(0.0);
  }
  return vec3(dot(normal, dir)) * ONE_OVER_PI;
}

void main(void) {
  float blendIdx = texelFetch(blendIndices, ivec2(gl_FragCoord.xy), 0).r;
  vec4 s = randomCosineWeightedHemisphereDirection(normal_varying, randvec2(1.7, blendIdx));
  float blendFactor = blendIdx / (blendIdx+1.0);
  color = vec4(trace(position_varying + normal_varying*1E-4, normal_varying, s.xyz) / s.w, blendFactor);
  blendIndex_out = blendIdx + 1.0;
}
