/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

attribute vec3 position;
attribute vec3 normal;

uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform mat3 normalMatrix;

varying vec3 viewNormal;

void main(void) {
  viewNormal = normalMatrix * normal;
  gl_Position = projMatrix * viewMatrix * vec4(position, 1.0);
}
