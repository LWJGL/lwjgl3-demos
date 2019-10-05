/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

layout(binding = 0, set = 0) uniform Camera {
  mat4 mvp;
} camera;

layout(location = 0) in vec3 vertex;
layout(location = 1) in vec3 normal;

layout(location = 0) out vec3 out_normal;

void main(void) {
  out_normal = normal;
  gl_Position = camera.mvp * vec4(vertex, 1.0);
}
