/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110
#extension GL_EXT_geometry_shader4 : require

bool IsFront(vec4 A, vec4 B, vec4 C) {
  return 0.0 < (A.x * B.y - B.x * A.y) + (B.x * C.y - C.x * B.y) + (C.x * A.y - A.x * C.y);
}

void EmitEdge(vec4 P0, vec4 P1) {
  gl_Position = P0;
  EmitVertex();
  gl_Position = P1;
  EmitVertex();
  EndPrimitive();
}

void main(void) {
  vec4 v0 = gl_PositionIn[0] / gl_PositionIn[0].w;
  vec4 v1 = gl_PositionIn[1] / gl_PositionIn[1].w;
  vec4 v2 = gl_PositionIn[2] / gl_PositionIn[2].w;
  vec4 v3 = gl_PositionIn[3] / gl_PositionIn[3].w;
  vec4 v4 = gl_PositionIn[4] / gl_PositionIn[4].w;
  vec4 v5 = gl_PositionIn[5] / gl_PositionIn[5].w;
  if (IsFront(v0, v2, v4)) {
    if (!IsFront(v0, v1, v2)) EmitEdge(v0, v2);
    if (!IsFront(v2, v3, v4)) EmitEdge(v2, v4);
    if (!IsFront(v0, v4, v5)) EmitEdge(v4, v0);
  }
}
