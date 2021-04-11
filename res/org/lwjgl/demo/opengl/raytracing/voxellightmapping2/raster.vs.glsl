/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 mvp;
uniform ivec2 lightmapSize;
uniform vec3 camPos;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uvec2 sideAndOffset;
layout(location=2) in vec2 lightmapCoords;

centroid out vec2 lightmapCoords_varying;
flat out int matIndex;
out vec3 normal_varying;
out vec3 dir_varying;
centroid out vec3 pos;

vec3 offset() {
  vec3 r = vec3(gl_VertexID & 1, gl_VertexID >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return sideAndOffset.x < 2u ? r.zxy : sideAndOffset.x < 4u ? r.yzx : r.xyz;
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

void main(void) {
  pos = positionAndType.xyz;
  float w = dot(transpose(mvp)[3], vec4(pos, 1.0));
  matIndex = int(positionAndType.w);
  lightmapCoords_varying = (lightmapCoords + vec2(0.5)) / vec2(lightmapSize);
  normal_varying = normals[sideAndOffset.x];
  dir_varying = pos - camPos;
  gl_Position = mvp * vec4(pos + offset() * 1E-4 * w, 1.0);
}
