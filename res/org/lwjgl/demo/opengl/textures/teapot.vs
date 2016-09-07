/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

uniform mat4 viewProj;
uniform vec3 cameraPosition;
varying vec3 dir;
varying vec3 normal;

void main(void) {
  normal = gl_Normal;
  dir = gl_Vertex.xyz - cameraPosition;
  gl_Position = viewProj * gl_Vertex;
}
