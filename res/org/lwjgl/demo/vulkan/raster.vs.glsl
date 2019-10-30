/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : require

layout(binding = 0, set = 0) uniform Camera {
  mat4 mvp;
  float scale;
} camera;

layout(location = 0) in vec3 vertex;
layout(location = 1) in i8vec3 offset;
layout(location = 2) in vec3 normal;

layout(location = 0) out vec2 out_normal;

#define OFFSET_SCALE 3E-4

vec2 encodeNormal(vec3 n) {
  return ((n.xy / (n.z + 1.0)) * 1.0 / 1.7777) * 0.5 + vec2(0.5);
}

void main(void) {
  float w = dot(transpose(camera.mvp)[3], vec4(vertex, 1.0));
  out_normal = encodeNormal(normal);
  gl_Position = camera.mvp * vec4(vertex + vec3(offset) * (OFFSET_SCALE / camera.scale) * w, 1.0);
}
