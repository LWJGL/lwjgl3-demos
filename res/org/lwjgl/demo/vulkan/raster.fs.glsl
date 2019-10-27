/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

layout(early_fragment_tests) in;

layout(location = 0) in vec2 normal;

layout(location = 0) out vec2 color;

void main(void) {
  color = normal;
}
