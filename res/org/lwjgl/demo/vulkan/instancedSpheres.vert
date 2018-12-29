/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 450

layout(location=0) in vec3 position;

layout(binding=0) uniform Matrices
{
  mat4 viewProj;
  mat4 spheres[256];
};

layout(location=0) out vec3 outColor;

void main(void) {
  outColor = position;
  gl_Position = viewProj * spheres[gl_InstanceIndex] * vec4(position, 1.0);
}
