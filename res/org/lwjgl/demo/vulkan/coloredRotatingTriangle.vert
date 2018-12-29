/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 450

layout(location=0) in vec2 position;
layout(location=1) in vec3 color;

layout(binding=0) uniform Matrices
{
  mat4 model;
};

layout(location=0) out vec3 outColor;

void main(void) {
  outColor = color;
  gl_Position = model * vec4(position, 0.0, 1.0);
}
