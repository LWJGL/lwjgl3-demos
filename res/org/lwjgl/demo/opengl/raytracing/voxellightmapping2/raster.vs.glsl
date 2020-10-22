/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 mvp;
uniform ivec2 lightmapSize;
uniform vec3 camPos;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uint sideAndOffset;
layout(location=2) in vec4 lightmapCoords;

centroid out vec2 lightmapCoords_varying;
flat out int matIndex;
out vec3 normal_varying;
out vec3 dir_varying;
centroid out vec3 pos;

vec3 offset() {
  float s = float(sideAndOffset & 0x7u);
  vec3 r = vec3(sideAndOffset >> 3u & 0x3u, sideAndOffset >> 5u & 0x3u, 1.0) - vec3(1.0);
  return mix(r.zxy, mix(r.xzy, r.xyz, step(4.0, s)), step(2.0, s));
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

void main(void) {
  pos = positionAndType.xyz;
  float w = dot(transpose(mvp)[3], vec4(pos, 1.0));
  matIndex = int(positionAndType.w)&0xFF;
  lightmapCoords_varying = (lightmapCoords.xy + vec2(0.5)) / vec2(lightmapSize);
  normal_varying = normals[sideAndOffset & 0x7u];
  dir_varying = pos - camPos;
  gl_Position = mvp * vec4(pos + offset() * 1E-4 * w, 1.0);
}
