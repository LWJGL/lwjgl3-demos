/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 450

layout(location=0) in vec3 position;
layout(location=1) in vec3 color;

layout(binding=0) uniform Matrices
{
  mat4 viewProjection;
};

layout(location=0) out vec3 outColor;

void main(void) {
  outColor = color;
  gl_Position = viewProjection * vec4(position, 1.0);
}
