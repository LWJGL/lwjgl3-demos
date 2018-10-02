/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 140

uniform int lod = 5;
uniform int numPoints;
uniform mat4 transform;

uniform ControlPoints {
  vec3[256] points;
};

void main(void) {
  int i = gl_VertexID % lod;
  int segment = gl_VertexID / lod;
  int start = -3 + segment;
  float t = float(i) / float(lod);
  float t2 = t * t;
  float t3 = t2 * t;
  float it = 1.0 - t;
  float w0 = it * it * it / 6.0;
  float w1 = 0.5 * t3 - t2 + 2.0 / 3.0;
  float w2 = 0.5 * (-t3 + t2 + t) + 1.0 / 6.0;
  float w3 = t3 / 6.0;
  vec3 p = w0 * points[clamp(start+0, 0, numPoints - 1)] +
           w1 * points[clamp(start+1, 0, numPoints - 1)] +
           w2 * points[clamp(start+2, 0, numPoints - 1)] +
           w3 * points[clamp(start+3, 0, numPoints - 1)];
  gl_Position = transform * vec4(p, 1.0);
}
