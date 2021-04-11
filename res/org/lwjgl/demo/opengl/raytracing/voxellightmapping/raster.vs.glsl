/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 mvp;
uniform ivec2 lightmapSize;
uniform float lod;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uvec2 sideAndOffset;
layout(location=2) in vec2 lightmapCoords;

centroid out vec2 lightmapCoords_varying;
flat out int matIndex;
out vec4 ao;
out vec2 uv;

const float aos[4] = float[4](0.72, 0.81, 0.91, 1.0);

vec3 offset() {
  uint s = sideAndOffset.x & 7u;
  vec3 r = vec3(gl_VertexID & 1, gl_VertexID >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return s < 2u ? r.zxy : s < 4u ? r.yzx : r.xyz;
}

/**
 * Vertex morphing between two lod levels.
 * Reference: https://0fps.net/2018/03/03/a-level-of-detail-method-for-blocky-voxels/
 */
vec3 lodPosition() {
  uint lodI = uint(lod);
  uint scale0 = 1u<<lodI, scale1 = 1u<<(lodI+1u);
  float vblend = fract(lod);
  return floor(positionAndType.xyz / scale1) * scale1 * vblend
       + floor(positionAndType.xyz / scale0) * scale0 * (1.0 - vblend);
}

void main(void) {
  uv = vec2(gl_VertexID & 1, gl_VertexID >> 1 & 1);
  ao = vec4(aos[sideAndOffset.y & 3u], aos[sideAndOffset.y >> 2u & 3u], aos[sideAndOffset.y >> 4u & 3u], aos[sideAndOffset.y >> 6u & 3u]);
  vec3 p = lodPosition();
  float w = dot(transpose(mvp)[3], vec4(p, 1.0));
  matIndex = int(positionAndType.w);
  lightmapCoords_varying = (lightmapCoords + vec2(0.5)) / vec2(lightmapSize);
  gl_Position = mvp * vec4(p + offset() * 1E-4 * w, 1.0);
}
