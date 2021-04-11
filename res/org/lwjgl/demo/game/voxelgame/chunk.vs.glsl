/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#pragma {{DEFINES}}

layout(std140) uniform Uniforms {
  mat4 mvp;
  mat4 mv;
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
  centroid vec2 uv;
  flat int matIndex;
} vs_out;

vec3 offset() {
  uint s = sideAndAoFactors.x;
  vec3 r = vec3(gl_VertexID & 1, gl_VertexID >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return s < 2u ? r.zxy : s < 4u ? r.yzx : r.xyz;
}

vec2 surfpos() {
  uint s = sideAndAoFactors.x;
  return s < 2u ? positionAndType.yz : s < 4u ? positionAndType.zx : positionAndType.xy;
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
  vs_out.uv = vec2(gl_VertexID & 1, gl_VertexID >> 1 & 1);
  vs_out.ao = vec4(aos[sideAndAoFactors.y & 3u],
                   aos[sideAndAoFactors.y >> 2u & 3u],
                   aos[sideAndAoFactors.y >> 4u & 3u],
                   aos[sideAndAoFactors.y >> 6u & 3u]);
  vs_out.surfacePos = surfpos();
  vec3 p = positionAndType.xyz + relChunkPos();
  float w = dot(transpose(mvp)[3], vec4(p, 1.0));
  vs_out.matIndex = int(positionAndType.w & 0xFFu);
  gl_Position = mvp * vec4(p + offset() * 2E-4 * w, 1.0);
}
