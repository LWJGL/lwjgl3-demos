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

#define OFFSET_SCALE 3E-4

vec3 offset() {
  uint s = typeAndSide >> 8u;
  vec3 r = vec3(gl_VertexIndex & 1, gl_VertexIndex >> 1 & 1, 0.5) * 2.0 - vec3(1.0);
  return s < 2u ? r.zxy : s < 4u ? r.yzx : r.xyz;
}

void main(void) {
  out_normal = N[typeAndSide >> 8u];
  type = typeAndSide & 0xFFu;
  gl_Position = cam.mvp * vec4(vertex, 1.0);
}
