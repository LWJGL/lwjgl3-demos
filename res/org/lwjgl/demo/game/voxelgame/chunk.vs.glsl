/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#pragma {{DEFINES}}

layout(std140) uniform Uniforms {
  mat4 mvp;
  vec4 tmvp;
  ivec4 camPos;
};

#if !MDI
uniform isamplerBuffer chunkInfo;
#endif

const float aos[4] = float[4](AO_FACTORS);

layout(location=0) in uvec4 positionAndType;
layout(location=1) in uvec2 sideAndAoFactors;
#if MDI
layout(location=2) in ivec4 chunkInfo;
#else
layout(location=2) in uint chunkIndex;
#endif

out FS_IN {
  vec4 ao;
  vec2 surfacePos;
  flat int matIndex;
} vs_out;
centroid out vec2 uv;

vec3 offset() {
  uint s = sideAndAoFactors.x;
  vec3 r = vec3(gl_VertexID & 1, gl_VertexID >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return mix(r.zxy, mix(r.yzx, r.xyz, step(4.0, float(s))), step(2.0, float(s)));
}

vec2 surfpos() {
  uint s = sideAndAoFactors.x;
  return mix(positionAndType.yz, mix(positionAndType.zx, positionAndType.xy, step(4.0, float(s))), step(2.0, float(s)));
}

vec3 relChunkPos() {
  ivec4 ci;
#if MDI
  ci = chunkInfo;
#else
  ci = texelFetch(chunkInfo, int(chunkIndex));
#endif
  return vec3(ci.x, 0, ci.z) - camPos.xyz;
}

void main(void) {
  uv = vec2(gl_VertexID & 1, gl_VertexID >> 1 & 1);
  vs_out.ao = vec4(aos[sideAndAoFactors.y & 3u],
                   aos[sideAndAoFactors.y >> 2u & 3u],
                   aos[sideAndAoFactors.y >> 4u & 3u],
                   aos[sideAndAoFactors.y >> 6u & 3u]);
  vs_out.surfacePos = surfpos();
  vec3 p = positionAndType.xyz + relChunkPos();
  float w = dot(tmvp, vec4(p, 1.0));
  vs_out.matIndex = int(positionAndType.w & 0xFFu);
  gl_Position = mvp * vec4(p + offset() * 2E-4 * w, 1.0);
}
