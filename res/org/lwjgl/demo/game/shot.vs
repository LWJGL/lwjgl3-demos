/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 proj;
varying float power;
varying vec2 pointCoord;

void main(void) {
  power = 1.0 - gl_Vertex.w / 20.0;
  pointCoord = gl_MultiTexCoord0.st;
  gl_Position = proj * vec4(gl_Vertex.xyz, 1.0);
}
