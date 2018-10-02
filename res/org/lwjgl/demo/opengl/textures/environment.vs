/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 invViewProj;
varying vec3 dir;

void main(void) {
  vec4 tmp = invViewProj * vec4(gl_Vertex.xy, 1.0, 1.0);
  dir = tmp.xyz / tmp.w;
  gl_Position = gl_Vertex;
}
