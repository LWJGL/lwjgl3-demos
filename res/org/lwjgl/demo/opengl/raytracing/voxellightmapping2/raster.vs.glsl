/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 mvp;
uniform ivec2 lightmapSize;
uniform vec3 camPos;

layout(location=0) in vec4 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 lightmapCoords;

centroid out vec2 lightmapCoords_varying;
flat out int matIndex;
out vec3 normal_varying;
out vec3 dir_varying;
centroid out vec3 pos;

vec3 offset() {
  int mxyz = int(position.w);
  return vec3(((mxyz>>8)&0x3)-1, ((mxyz>>10)&0x3)-1, ((mxyz>>12)&0x3)-1);
}

void main(void) {
  pos = position.xyz;
  float w = dot(transpose(mvp)[3], vec4(pos, 1.0));
  matIndex = int(position.w)&0xFF;
  lightmapCoords_varying = (lightmapCoords.xy + vec2(0.5)) / vec2(lightmapSize);
  normal_varying = normal;
  dir_varying = pos - camPos;
  gl_Position = mvp * vec4(pos + offset() * 1E-4 * w, 1.0);
}
