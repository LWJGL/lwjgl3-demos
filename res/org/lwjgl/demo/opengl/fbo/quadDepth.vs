/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

attribute vec2 vertex;
varying vec2 texcoord;
varying vec2 vertexNDC;

void main(void) {
  vertexNDC = vertex;
  texcoord = vertex * 0.5 + vec2(0.5, 0.5);
  gl_Position = vec4(vertex, 0.0, 1.0);
}
