/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform ivec2 lightmapSize;

layout(location=0) in vec4 positionAndType;
layout(location=1) in uint side;
layout(location=2) in vec2 lightmapCoords;

out vec3 normal_varying;
out vec3 position_varying;

vec3 offset(vec2 txoff) {
  vec3 r = vec3(txoff, 0.5) * 2.0 - vec3(1.0);
  return side < 2u ? r.zxy : side < 4u ? r.yzx : r.xyz;
}

vec2 txoffset() {
  return vec2(gl_VertexID & 1, gl_VertexID >> 1 & 1);
}

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

#define CENTER_TO_CORNER_FACTOR 0.497

void main(void) {
  vec2 txoff = txoffset();
  normal_varying = normals[side];
  position_varying = positionAndType.xyz + offset(txoff) * CENTER_TO_CORNER_FACTOR;
  gl_Position = vec4((lightmapCoords + txoff) / vec2(lightmapSize) * 2.0 - 1.0, 0.0, 1.0);
}
