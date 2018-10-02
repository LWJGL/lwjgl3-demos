/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130

uniform mat4 transform;
uniform ivec2 size;
const vec2 off[6] = vec2[] (vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                            vec2(1.0, 1.0), vec2(0.0, 1.0), vec2(0.0, 0.0));

void main(void) {
  int vertIdx = gl_VertexID % 6;
  int gridIdx = gl_VertexID / 6;
  vec2 pos = vec2(gridIdx / size.x, gridIdx % size.x);
  pos = (pos + off[vertIdx]) / vec2(size).yx * 2.0 - 1.0;
  gl_Position = transform * vec4(pos.x, 0.0, pos.y, 1.0);
}
