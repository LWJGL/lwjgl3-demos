/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110
#extension GL_EXT_gpu_shader4 : enable

#define PI 3.14159265359

uniform mat3 transform;
uniform int count;

void main(void) {
  vec2 pos = vec2(0.0, 0.0);
  if (gl_VertexID > 0) {
    float t = float(gl_VertexID-1);
    float ang = t * 2.0 * PI / float(count);
    float x = cos(ang);
    float y = sin(ang);
    pos = vec2(x, y);
  }
  gl_Position = vec4(transform * vec3(pos, 0.0), 1.0);
}
