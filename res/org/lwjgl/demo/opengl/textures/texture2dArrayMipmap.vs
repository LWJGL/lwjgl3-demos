/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150

in vec2 position;

uniform mat4 viewProjMatrix;

out vec2 texcoord;

void main(void) {
  texcoord = position * 0.5 + vec2(0.5, 0.5);
  vec2 pos = position;
  pos *= 10.0;
  gl_Position = viewProjMatrix * vec4(pos.x, 0.0, pos.y, 1.0);
}
