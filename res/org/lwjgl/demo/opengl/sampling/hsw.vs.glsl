/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
out vec2 texcoords;
void main(void) {
  vec2 vertex = vec2(-1.0) + vec2(
    float((gl_VertexID & 1) << 2),
    float((gl_VertexID & 2) << 1));
  texcoords = vertex * 0.5 + 0.5;
  gl_Position = vec4(vertex, 0.0, 1.0);
}
