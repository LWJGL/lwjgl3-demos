/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130
#extension GL_ARB_bindless_texture : require
#extension GL_ARB_uniform_buffer_object : require

#define MAX_BOXES 128

uniform Samplers {
  samplerCube samplers[MAX_BOXES];
};

in vec3 positionOnUnitCube;
flat in int level;

out vec4 color;

void main(void) {
  float r = texture(samplers[level], positionOnUnitCube).r;
  color = vec4(r, r, r, 1.0);
}
