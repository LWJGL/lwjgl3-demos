/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#if __VERSION__ >= 130
  #define varying out
#endif

/* Write interpolated texture coordinate to fragment shader */
varying vec2 texcoord;

void main(void) {
  vec2 vertex = vec2(-1.0) + vec2(
    float((gl_VertexID & 1) << 2),
    float((gl_VertexID & 2) << 1));
  gl_Position = vec4(vertex, 0.0, 1.0);

  /*
   * Compute texture coordinate by simply
   * interval-mapping from [-1..+1] to [0..1]
   */
  texcoord = vertex * 0.5 + vec2(0.5, 0.5);
}
