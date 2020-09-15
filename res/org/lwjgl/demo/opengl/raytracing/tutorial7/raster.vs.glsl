/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

in vec3 vertex;
in vec3 normal;

out vec3 worldNormal;
out vec4 viewPosition;

void main(void) {
  vec4 worldPosition = vec4(vertex, 1.0);
  worldNormal = normal;
  viewPosition = viewMatrix * worldPosition;
  gl_Position = projMatrix * viewPosition;
}
