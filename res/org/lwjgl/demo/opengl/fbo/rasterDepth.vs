/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 viewProjMatrix;

attribute vec3 vertexPosition;

void main(void) {
  vec4 worldPosition = vec4(vertexPosition, 1.0);
  gl_Position = viewProjMatrix * worldPosition;
}
