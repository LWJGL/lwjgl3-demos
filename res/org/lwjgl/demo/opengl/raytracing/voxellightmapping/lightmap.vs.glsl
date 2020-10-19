/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform ivec2 lightmapSize;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uvec2 sideIndexAndOffset;
layout(location=2) in vec4 lightmapCoords;

out vec3 normal_varying;
out vec3 position_varying;

vec3 offset() {
  int xyz = int(sideIndexAndOffset.y);
  return vec3((xyz&0x3)-1, ((xyz>>2)&0x3)-1, ((xyz>>4)&0x3)-1);
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

#define CENTER_TO_CORNER_FACTOR 0.497

void main(void) {
  normal_varying = normals[sideIndexAndOffset.x];
  position_varying = positionAndType.xyz + offset() * CENTER_TO_CORNER_FACTOR;
  gl_Position = vec4((lightmapCoords.xy + lightmapCoords.zw) / vec2(lightmapSize) * 2.0 - 1.0, 0.0, 1.0);
}
