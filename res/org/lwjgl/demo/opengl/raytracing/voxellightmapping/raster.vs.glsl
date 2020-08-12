/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 mvp;
layout(location=0) in vec4 position;
layout(location=2) in vec4 lightmapCoords;

centroid out vec2 lightmapCoords_varying;
flat out int matIndex;

vec3 offset() {
  int mxyz = int(position.w);
  return vec3(((mxyz>>8)&0x3)-1, ((mxyz>>10)&0x3)-1, ((mxyz>>12)&0x3)-1);
}

void main(void) {
  float w = dot(transpose(mvp)[3], vec4(position.xyz, 1.0));
  matIndex = int(position.w)&0xFF;
  lightmapCoords_varying = lightmapCoords.zw;
  gl_Position = mvp * vec4(position.xyz + offset() * 1E-4 * w, 1.0);
}
