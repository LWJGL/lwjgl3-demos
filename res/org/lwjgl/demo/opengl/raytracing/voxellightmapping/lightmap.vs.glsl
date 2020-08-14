/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform ivec2 lightmapSize;

layout(location=0) in vec4 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 lightmapCoords;

out vec3 normal_varying;
out vec3 position_varying;

vec3 offset() {
  int mxyz = int(position.w);
  return vec3(((mxyz>>8)&0x3)-1, ((mxyz>>10)&0x3)-1, ((mxyz>>12)&0x3)-1);
}

void main(void) {
  normal_varying = normal;
  position_varying = position.xyz + offset()*0.497;
  gl_Position = vec4((lightmapCoords.xy + lightmapCoords.zw) / vec2(lightmapSize) * 2.0 - 1.0, 0.0, 1.0);
}
