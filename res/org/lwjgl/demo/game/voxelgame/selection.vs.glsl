/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout(std140) uniform Uniforms {
  mat4 mvp;
};

layout(location=0) in vec2 quad;

out vec2 quad_out;

void main(void) {
  quad_out = (quad + vec2(1.0)) * 0.5;
  gl_Position = mvp * vec4(quad, 0.0, 1.0);
}
