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

vec3 offset() {
  vec3 r = vec3(gl_VertexID & 1, gl_VertexID >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return sideAndOffset.x < 2u ? r.zxy : sideAndOffset.x < 4u ? r.yzx : r.xyz;
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
  vec3 p = lodPosition();
  float w = dot(transpose(mvp)[3], vec4(p, 1.0));
  matIndex = int(positionAndType.w);
  lightmapCoords_varying = (lightmapCoords + vec2(0.5)) / vec2(lightmapSize);
  gl_Position = mvp * vec4(p + offset() * 1E-4 * w, 1.0);
}
