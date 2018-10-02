/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110
#extension GL_EXT_geometry_shader4 : require

uniform vec2 viewportSize;

#define MAX_DIST 1.0E9

varying in float vertexVisible[];

varying out vec3 distance;

void main(void) {
  // taken from 'Single-Pass Wireframe Rendering'
  vec2 p0 = viewportSize * gl_PositionIn[0].xy / gl_PositionIn[0].w;
  vec2 p1 = viewportSize * gl_PositionIn[1].xy / gl_PositionIn[1].w;
  vec2 p2 = viewportSize * gl_PositionIn[2].xy / gl_PositionIn[2].w;

  vec2 v0 = p2 - p1;
  vec2 v1 = p2 - p0;
  vec2 v2 = p1 - p0;
  float fArea = abs(v1.x * v2.y - v1.y * v2.x);

  distance = vec3(fArea/length(v0), 0, 0);
  if (vertexVisible[0] == 0.0)
    distance.x = MAX_DIST;
  gl_Position = gl_PositionIn[0];
  EmitVertex();

  distance = vec3(0, fArea/length(v1), 0);
  if (vertexVisible[1] == 0.0)
    distance.y = MAX_DIST;
  gl_Position = gl_PositionIn[1];
  EmitVertex();

  distance = vec3(0, 0, fArea/length(v2));
  if (vertexVisible[2] == 0.0)
    distance.z = MAX_DIST;
  gl_Position = gl_PositionIn[2];
  EmitVertex();
}
