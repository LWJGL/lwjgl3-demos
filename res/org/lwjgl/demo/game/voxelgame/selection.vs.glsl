/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout(std140) uniform Uniforms {
  mat4 mvp;
};

out vec2 quad_out;

void main(void) {
  vec2 vertex = vec2(-1.0) + vec2(float((gl_VertexID & 1) <<1), float(gl_VertexID & 2));
  quad_out = (vertex + vec2(1.0)) * 0.5;
  gl_Position = mvp * vec4(vertex, 0.0, 1.0);
}
