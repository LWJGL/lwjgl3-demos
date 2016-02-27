#version 130

uniform mat4 transform;
uniform ivec2 size;
const ivec2 off[6] = ivec2[] ( ivec2(0,0), ivec2(1,0), ivec2(1,1), ivec2(1,1), ivec2(0,1), ivec2(0,0) );

void main(void) {
  int vertIdx = gl_VertexID % 6;
  int gridIdx = gl_VertexID / 6;
  vec2 coord = vec2(gridIdx / size.x, gridIdx % size.x);
  coord = (coord + vec2(off[vertIdx])) / vec2(size.y, size.x) * 2.0 - 1.0;
  gl_Position = transform * vec4(coord.x, 0.0, coord.y, 1.0);
}
