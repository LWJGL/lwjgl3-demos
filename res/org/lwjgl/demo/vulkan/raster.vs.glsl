/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : require

layout(binding = 0, set = 0) uniform Camera {
  mat4 mvp;
  mat3 n;
} camera;

layout(location = 0) in vec3 vertex;
layout(location = 1) in i8vec3 offset;
layout(location = 2) in vec3 normal;

layout(location = 0) out vec2 out_normal;

#define OFFSET_SCALE 3E-4

void main(void) {
  float w = dot(transpose(camera.mvp)[3], vec4(vertex, 1.0));
  out_normal = (camera.n * normal).xy;
  gl_Position = camera.mvp * vec4(vertex + vec3(offset) * OFFSET_SCALE * w, 1.0);
}
