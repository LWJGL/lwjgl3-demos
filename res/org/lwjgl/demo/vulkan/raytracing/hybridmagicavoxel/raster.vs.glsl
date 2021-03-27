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

layout(location = 0) in vec3 vertex;
layout(location = 1) in uint typeAndSide;

layout(location = 0) out vec3 out_normal;
layout(location = 1) flat out uint type;

const vec3 N[6] = vec3[6](vec3(-1,0,0), vec3(1,0,0), vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1), vec3(0,0,1));

void main(void) {
  out_normal = N[typeAndSide >> 8u];
  type = typeAndSide & 0xFFu;
  gl_Position = cam.mvp * vec4(vertex, 1.0);
}
