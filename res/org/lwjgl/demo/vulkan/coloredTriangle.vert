/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 420 core

layout(location=0) in vec2 position;
layout(location=1) in vec3 color;

layout(location = 0) out vec3 outColor;

void main(void) {
  outColor = color;
  gl_Position = vec4(position, 0.0, 1.0);
}
