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

layout(location = 0) out vec3 pos;
layout(location = 1) flat out uint type;

/**
 * Decode a float stored in an 8-bit UNORM format
 * to an uint in the range [0..255].
 */
uint unorm8toUint(float unorm8) {
  return uint(unorm8 * 255.0);
}

void main(void) {
  pos = vertex.xyz;
  type = unorm8toUint(vertex.w);
  gl_Position = cam.mvp * vec4(vertex.xyz, 1.0);
}
