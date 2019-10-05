/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

layout(location = 0) out vec4 color;
layout(location = 0) in vec3 normal;

void main(void) {
  color = vec4(normal, 0.0);
}
