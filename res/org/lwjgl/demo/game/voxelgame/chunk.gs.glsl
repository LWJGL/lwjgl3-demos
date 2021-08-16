/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#pragma {{DEFINES}}

layout(points) in;
layout(triangle_strip, max_vertices = 4) out;

layout(std140) uniform Uniforms {
  mat4 mvp;
  vec4 tmvp;
  ivec4 camPos;
};

in VS_OUT {
  uvec4 typeSideAndAoFactors;
  ivec4 chunkInfo;
  uint pu0v0u1v1;
} gs_in[];

out FS_IN {
  vec4 ao;
  vec2 surfacePos;
  flat int matIndex;
} gs_out;
centroid out vec2 uv;

const float aos[4] = float[4](AO_FACTORS);
const vec3 N[6] = vec3[6](vec3(1,0,0), vec3(-1,0,0), vec3(0,1,0), vec3(0,-1,0), vec3(0,0,1), vec3(0,0,-1));
const vec3 Ox[3] = vec3[3](vec3(0,1,0), vec3(0,0,1), vec3(1,0,0));
const vec3 Oy[3] = vec3[3](vec3(0,0,1), vec3(1,0,0), vec3(0,1,0));

vec3 relChunkPos() {
  ivec4 ci = gs_in[0].chunkInfo;
  return vec3(ci.x, 0, ci.z) - camPos.xyz;
}

void main(void) {
  uvec3 p0u0v0 = uvec3(gs_in[0].pu0v0u1v1 & 0xFFu, gs_in[0].pu0v0u1v1 >> 8u & 0x3Fu, gs_in[0].pu0v0u1v1 >> 14u & 0x3Fu);
  uint s = gs_in[0].typeSideAndAoFactors.y, sd = s >> 1u;
  vec3 rp = relChunkPos();
  if (dot(N[s], p0u0v0.yxz + rp) < 0.0)
    return; 
  uvec2 du1v1  = uvec2(gs_in[0].pu0v0u1v1 >> 20u & 0x1Fu, gs_in[0].pu0v0u1v1 >> 25u & 0x1Fu);
  uvec2 u = uvec2(du1v1.x + 1u, 0u), v = uvec2(0u, du1v1.y + 1u);
  vec3 up = mix(vec3(u.yxy), mix(vec3(u.yyx), vec3(u.xyy), step(2.0, float(sd))), step(1.0, float(sd)));
  vec3 vp = mix(vec3(v.xxy), mix(vec3(v.yxx), vec3(v.xyx), step(2.0, float(sd))), step(1.0, float(sd)));
  vec2 sp = mix(p0u0v0.xz, mix(p0u0v0.yz, p0u0v0.xy, step(2.0, float(sd))), step(1.0, float(sd)));
  gs_out.matIndex = int(gs_in[0].typeSideAndAoFactors.x) & 0xFF;
  gs_out.ao = vec4(aos[gs_in[0].typeSideAndAoFactors.z & 3u],
                   aos[gs_in[0].typeSideAndAoFactors.z >> 2u & 3u],
                   aos[gs_in[0].typeSideAndAoFactors.z >> 4u & 3u],
                   aos[gs_in[0].typeSideAndAoFactors.z >> 6u & 3u]);
  float w;
  vec3 ox = 2E-4 * Ox[sd], oy = 2E-4 * Oy[sd], p = p0u0v0.yxz + rp;
  {
    gs_out.surfacePos = sp + u;
    uv = vec2(1.0, 0.0);
    w = dot(tmvp, vec4(p + up, 1.0));
    gl_Position = mvp * vec4(p + up + ox*w - oy*w, 1.0);
    EmitVertex();
  }
  {
    gs_out.surfacePos = sp + u + v;
    uv = vec2(1.0);
    w = dot(tmvp, vec4(p + up + vp, 1.0));
    gl_Position = mvp * vec4(p + up + vp + ox*w + oy*w, 1.0);
    EmitVertex();
  }
  {
    gs_out.surfacePos = sp;
    uv = vec2(0.0);
    w = dot(tmvp, vec4(p, 1.0));
    gl_Position = mvp * vec4(p - ox*w - oy*w, 1.0);
    EmitVertex();
  }
  {
    gs_out.surfacePos = sp + v;
    uv = vec2(0.0, 1.0);
    w = dot(tmvp, vec4(p + vp, 1.0));
    gl_Position = mvp * vec4(p + vp - ox*w + oy*w, 1.0);
    EmitVertex();
  }
}
