/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

layout(binding = 0, set = 0) uniform Camera {
  mat4 mvp;
  mat4 projInverse;
  mat4 viewInverse;
} cam;

layout(location = 0) in vec4 vertex;

layout(location = 0) out vec3 normal;
layout(location = 1) flat out uint type;

const vec3 N[6] = vec3[6](vec3(-1,0,0), vec3(1,0,0), vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1), vec3(0,0,1));

/**
 * Decode a float stored in an 16-bit UNORM format
 * to an uint in the range [0..65535].
 */
uint unorm16toUint(float unorm16) {
  return uint(unorm16 * 65535.0);
}

void main(void) {
  uint typeAndSide = unorm16toUint(vertex.w);
  normal = N[typeAndSide >> 8u];
  type = typeAndSide & 0xFFu;
  gl_Position = cam.mvp * vec4(vertex.xyz, 1.0);
}
