/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 model;
uniform mat4 view;
uniform mat4 proj;

varying vec3 worldNormal;

void main(void) {
  worldNormal = gl_Normal;
  gl_Position = proj * view * model * gl_Vertex;
}
