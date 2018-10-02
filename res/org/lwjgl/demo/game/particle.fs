/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

varying float power;
varying vec2 pointCoord;

float smootStep(float x) {
  float c = clamp(x, 0.0, 1.0);
  c = c * c * (3.0 - 2.0 * c); // <- smoothstep
  return c;
}

void main(void) {
  float len = 1.0 - length(pointCoord);
  vec4 color = vec4(0.1, 1.3, 1.1, 1.0 * power) * smootStep(len);
  gl_FragColor = color;
}
