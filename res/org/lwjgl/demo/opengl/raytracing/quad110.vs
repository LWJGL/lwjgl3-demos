/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

/* The position of the vertex as two-dimensional vector */
attribute vec2 vertex;

/* Write interpolated texture coordinate to fragment shader */
varying vec2 texcoord;

void main(void) {
  gl_Position = vec4(vertex, 0.0, 1.0);

  /*
   * Compute texture coordinate by simply
   * interval-mapping from [-1..+1] to [0..1]
   */
  texcoord = vertex * 0.5 + vec2(0.5, 0.5);
}
