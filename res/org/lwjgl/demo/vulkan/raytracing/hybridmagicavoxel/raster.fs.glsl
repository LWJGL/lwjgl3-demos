/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460

layout(early_fragment_tests) in;

layout(location = 0) in vec3 normal;
layout(location = 1) flat in uint type;

layout(location = 0) out vec4 color;

/**
 * Encode an uint in the range [0..255] to a float
 * that will be stored in an 8-bit SNORM format.
 */
float uintToSnorm8(uint v) {
  return float(v) / 127.0 - 1.0;
}

void main(void) {
  color = vec4(normal, uintToSnorm8(type));
}
