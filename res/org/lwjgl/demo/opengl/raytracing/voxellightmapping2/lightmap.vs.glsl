/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform ivec2 lightmapSize;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uint sideAndOffset;
layout(location=2) in vec4 lightmapCoords;

out vec3 normal_varying;
out vec3 position_varying;

vec3 offset() {
  float s = float(sideAndOffset & 0x7u);
  vec3 r = vec3(sideAndOffset >> 3u & 0x3u, sideAndOffset >> 5u & 0x3u, 1.0) - vec3(1.0);
  return mix(r.zxy, mix(r.xzy, r.xyz, step(4.0, s)), step(2.0, s));
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

#define CENTER_TO_CORNER_FACTOR 0.497

void main(void) {
  normal_varying = normals[sideAndOffset & 0x7u];
  position_varying = positionAndType.xyz + offset() * CENTER_TO_CORNER_FACTOR;
  gl_Position = vec4((lightmapCoords.xy + lightmapCoords.zw) / vec2(lightmapSize) * 2.0 - 1.0, 0.0, 1.0);
}
