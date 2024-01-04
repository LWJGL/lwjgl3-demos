/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout (location = 0) in vec3 pos;

uniform mat4 mvp;
uniform mat4 invModel;
uniform vec3 camPosition;

out vec3 o;
out vec3 d;

void main(void) {
  o = (invModel * vec4(camPosition, 1.0)).xyz;
  d = pos - o;
  gl_Position = mvp * vec4(pos, 1.0);
}
