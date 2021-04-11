/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform ivec2 lightmapSize;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uvec2 sideAndOffset;
layout(location=2) in vec2 lightmapCoords;

out vec3 normal_varying;
out vec3 position_varying;

vec2 vertexoffset() {
  return vec2(sideAndOffset.x >> 3u & 3u, sideAndOffset.x >> 5u & 3u) - vec2(1.0);
}

vec3 offset(vec2 txoff) {
  uint s = sideAndOffset.x & 7u;
  vec2 tx = txoff - vec2(0.5);
  vec3 r = vec3(tx + sign(tx) * vertexoffset() * 1E-3, 0.0);
  return s < 2u ? r.zxy : s < 4u ? r.yzx : r.xyz;
}

vec2 txoffset() {
  return vec2(gl_VertexID & 1, gl_VertexID >> 1 & 1);
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

void main(void) {
  vec2 txoff = txoffset();
  normal_varying = normals[sideAndOffset.x & 7u];
  position_varying = positionAndType.xyz + offset(txoff);
  gl_Position = vec4((lightmapCoords + txoff) / vec2(lightmapSize) * 2.0 - 1.0, 0.0, 1.0);
}
