/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform mat4 projMatrix;
uniform mat4 viewMatrix;

in vec3 vertexPosition;

void main(void) {
  vec4 worldPosition = vec4(vertexPosition, 1.0);
  gl_Position = projMatrix * viewMatrix * worldPosition;
}
