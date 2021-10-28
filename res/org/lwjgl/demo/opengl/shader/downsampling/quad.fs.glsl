/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform sampler2D tex;
uniform int level;
in vec2 texcoord;
out vec4 color;

void main(void) {
  color = textureLod(tex, texcoord, float(level));
}
