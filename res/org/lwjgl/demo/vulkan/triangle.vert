/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 420 core

layout(location=0) in vec2 position;

void main(void) {
  gl_Position = vec4(position, 0.0, 1.0);
}
