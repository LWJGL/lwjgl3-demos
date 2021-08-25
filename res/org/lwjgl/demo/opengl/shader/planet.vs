/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 transform;
uniform mat4 transformDir;
uniform mat4 viewProj;
uniform int clouds;

in vec3 position;
out vec3 dir;
flat out vec3 dirflat;

void main(void) {
  dir = clouds == 1 ? (transformDir * vec4(position, 1.0)).xyz : position;
  dirflat = dir;
  vec3 sposition = position * (clouds == 1 ? 1.04 : 1.0);
  gl_Position = viewProj * transform * vec4(sposition, 1.0);
}
